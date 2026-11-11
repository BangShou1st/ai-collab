"""Java Job 内部端点客户端（设计 §7.7，W5 冻结文档 §3.3）。

调用面三个（全部由 Python 发起、都带**入站同一 token**）：

- ``GET  {base}/internal/ai/v1/jobs/{jobId}/snapshot``
- ``POST {base}/internal/ai/v1/jobs/{jobId}/heartbeat``
- ``POST {base}/internal/ai/v1/jobs/{jobId}/results``

模型调用不在本模块：它与其它端点共用 :class:`~ai_collab_worker.clients.java_gateway.
JavaGatewayClient`（同一连接池、同一套 ``purpose``/``outputFormat`` 口径）。

固定口径：

- **令牌只透传**：``bearer_token`` 是入站请求已验证的 Bearer 原值（Java 签发的 job-scoped
  Ed25519 短令牌），原样转发、**不重签、不落盘、不打印**；
- 超时沿用设计 §7.1：connect 2s、read = ``deadlineMs + 5s``、write 10s、pool 2s；
- 错误分类沿用 §7.1 语义：连接/读超时 → ``DEADLINE_EXCEEDED``(504, retryable)；429 →
  ``MODEL_RATE_LIMITED``(429)；5xx / 非 JSON / 契约不符 → ``MODEL_UNAVAILABLE``(503, retryable)；
  其它 4xx → ``MODEL_UNAVAILABLE``(503, retryable=False)。此外本客户端保留 HTTP 状态与一个
  固定 ``reason``，让 Job 层能区分 **404（job 不存在/已取消）** 与 **409（STALE_LEASE，
  迟到结果被 fence）**——这两种对 Job 状态机是"不可重试的失败"，而不是"网关瞬时不可用"；
- **不重试**：重试决策只在 Java（前端说"Python 不重试、不调度"）；
- 日志只记 ``job_id`` / ``status`` / ``elapsed_ms`` / ``reason``，绝不打印请求体、快照正文、
  候选正文与令牌。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from typing import Final, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from ai_collab_worker.contract.common import ErrorCode
from ai_collab_worker.contract.memory_job import (
    JobHeartbeatRequest,
    JobHeartbeatResponse,
    JobResultsRequest,
    JobResultsResponse,
    MemoryCandidate,
    MemoryJobSnapshot,
)

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: Job 内部端点根路径。
JOBS_PATH: Final[str] = "/internal/ai/v1/jobs"

#: 各子路径（相对 ``JOBS_PATH/{jobId}``）。
SNAPSHOT_SUFFIX: Final[str] = "/snapshot"
HEARTBEAT_SUFFIX: Final[str] = "/heartbeat"
RESULTS_SUFFIX: Final[str] = "/results"

#: 超时口径（设计 §7.1）。
CONNECT_TIMEOUT_SECONDS: Final[float] = 2.0
READ_TIMEOUT_GRACE_SECONDS: Final[float] = 5.0
WRITE_TIMEOUT_SECONDS: Final[float] = 10.0
POOL_TIMEOUT_SECONDS: Final[float] = 2.0

#: 固定 reason（低基数，只进日志与 Job 层状态映射，不进错误信封）。
REASON_TIMEOUT: Final[str] = "timeout"
REASON_UNAVAILABLE: Final[str] = "unavailable"
REASON_RATE_LIMITED: Final[str] = "rate_limited"
REASON_REJECTED: Final[str] = "rejected"
REASON_NOT_FOUND: Final[str] = "not_found"
REASON_STALE_LEASE: Final[str] = "stale_lease"
REASON_CONTRACT: Final[str] = "contract"

#: 内部端点响应的强类型模型（``MemoryJobSnapshot`` / 心跳 / 结果回报）。
ModelT = TypeVar("ModelT", bound=BaseModel)


class JobGatewayError(Exception):
    """Java Job 端点调用失败。

    ``code`` 是 §7.1 的九码子集（供统一错误信封与指标分类），``reason`` 是本客户端固定的
    低基数短语（供 Job 层把 404/409 映射成"不可重试"），``status`` 是上游 HTTP 状态（传输
    层失败为 ``None``）。异常消息只含 code 与 reason，**不含响应体与令牌**。
    """

    def __init__(
        self,
        code: ErrorCode,
        reason: str,
        *,
        status: int | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(f"{code.value}:{reason}")
        self.code = code
        self.reason = reason
        self.status = status
        self.retryable = retryable


class JavaJobClient:
    """``/internal/ai/v1/jobs/**`` 的异步客户端；生命周期跟随 app。"""

    def __init__(
        self,
        *,
        base_url: str,
        service_token: str = "",
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url: Final[str] = base_url.rstrip("/")
        self._service_token: Final[str] = service_token
        self._client: Final[httpx.AsyncClient] = httpx.AsyncClient(transport=transport)

    async def aclose(self) -> None:
        """关闭底层连接池（lifespan 退出时调用）。"""

        await self._client.aclose()

    async def fetch_snapshot(
        self,
        job_id: str,
        *,
        deadline_ms: int,
        bearer_token: str | None = None,
        traceparent: str | None = None,
    ) -> MemoryJobSnapshot:
        """拉取有界快照（≤64 KiB）；形状不符合冻结 §3.3 视为 ``contract`` 失败。"""

        payload = await self._request(
            "GET",
            f"{JOBS_PATH}/{job_id}{SNAPSHOT_SUFFIX}",
            body=None,
            deadline_ms=deadline_ms,
            bearer_token=bearer_token,
            traceparent=traceparent,
            job_id=job_id,
        )
        return self._validate(MemoryJobSnapshot, payload, job_id=job_id)

    async def heartbeat(
        self,
        job_id: str,
        *,
        attempt: int,
        lease_epoch: int,
        deadline_ms: int,
        bearer_token: str | None = None,
        traceparent: str | None = None,
    ) -> JobHeartbeatResponse:
        """上报一次心跳；``attempt``/``leaseEpoch`` 必须与令牌 claims 一致（Java 侧 fence）。"""

        request = JobHeartbeatRequest(attempt=attempt, lease_epoch=lease_epoch)
        payload = await self._request(
            "POST",
            f"{JOBS_PATH}/{job_id}{HEARTBEAT_SUFFIX}",
            body=request.model_dump(by_alias=True, mode="json"),
            deadline_ms=deadline_ms,
            bearer_token=bearer_token,
            traceparent=traceparent,
            job_id=job_id,
        )
        return self._validate(JobHeartbeatResponse, payload, job_id=job_id)

    async def post_results(
        self,
        job_id: str,
        *,
        attempt: int,
        lease_epoch: int,
        candidates: Sequence[MemoryCandidate],
        deadline_ms: int,
        bearer_token: str | None = None,
        traceparent: str | None = None,
    ) -> JobResultsResponse:
        """回报候选；重复回报时 Java 只更新心跳并返回既有结果（幂等键在 Java）。"""

        request = JobResultsRequest(
            attempt=attempt,
            lease_epoch=lease_epoch,
            candidates=tuple(candidates),
        )
        payload = await self._request(
            "POST",
            f"{JOBS_PATH}/{job_id}{RESULTS_SUFFIX}",
            body=request.model_dump(by_alias=True, mode="json"),
            deadline_ms=deadline_ms,
            bearer_token=bearer_token,
            traceparent=traceparent,
            job_id=job_id,
        )
        return self._validate(JobResultsResponse, payload, job_id=job_id)

    # ---- 内部 ----------------------------------------------------------------

    async def _request(
        self,
        method: str,
        path: str,
        *,
        body: dict[str, object] | None,
        deadline_ms: int,
        bearer_token: str | None,
        traceparent: str | None,
        job_id: str,
    ) -> dict[str, object]:
        headers = self._headers(bearer_token=bearer_token, traceparent=traceparent)
        timeout = httpx.Timeout(
            connect=CONNECT_TIMEOUT_SECONDS,
            read=deadline_ms / 1000.0 + READ_TIMEOUT_GRACE_SECONDS,
            write=WRITE_TIMEOUT_SECONDS,
            pool=POOL_TIMEOUT_SECONDS,
        )
        started = time.perf_counter()
        try:
            response = await self._client.request(
                method,
                f"{self._base_url}{path}",
                json=body,
                headers=headers,
                timeout=timeout,
            )
        except httpx.TimeoutException as exc:
            self._log_failure(job_id=job_id, started=started, reason=REASON_TIMEOUT)
            raise JobGatewayError(
                ErrorCode.DEADLINE_EXCEEDED,
                REASON_TIMEOUT,
                retryable=True,
            ) from exc
        except httpx.HTTPError as exc:
            self._log_failure(job_id=job_id, started=started, reason=REASON_UNAVAILABLE)
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_UNAVAILABLE,
                retryable=True,
            ) from exc

        elapsed_ms = int((time.perf_counter() - started) * 1000)
        self._raise_for_status(
            response.status_code,
            job_id=job_id,
            elapsed_ms=elapsed_ms,
        )
        return self._decode(response, job_id=job_id, elapsed_ms=elapsed_ms)

    def _headers(self, *, bearer_token: str | None, traceparent: str | None) -> dict[str, str]:
        headers: dict[str, str] = {}
        token = self._service_token if bearer_token is None else bearer_token
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if traceparent:
            headers["traceparent"] = traceparent
        return headers

    def _raise_for_status(self, status: int, *, job_id: str, elapsed_ms: int) -> None:
        if status == 200:
            return
        reason = _reason_for_status(status)
        logger.warning(
            "java job endpoint rejected job_id=%s status=%s reason=%s elapsed_ms=%d",
            job_id,
            status,
            reason,
            elapsed_ms,
        )
        if status == 429:
            raise JobGatewayError(
                ErrorCode.MODEL_RATE_LIMITED,
                REASON_RATE_LIMITED,
                status=status,
                retryable=True,
            )
        if status == 404:
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_NOT_FOUND,
                status=status,
                retryable=False,
            )
        if status == 409:
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_STALE_LEASE,
                status=status,
                retryable=False,
            )
        if 400 <= status < 500:
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_REJECTED,
                status=status,
                retryable=False,
            )
        raise JobGatewayError(
            ErrorCode.MODEL_UNAVAILABLE,
            REASON_UNAVAILABLE,
            status=status,
            retryable=True,
        )

    def _decode(
        self,
        response: httpx.Response,
        *,
        job_id: str,
        elapsed_ms: int,
    ) -> dict[str, object]:
        try:
            payload = response.json()
        except ValueError as exc:
            logger.warning(
                "java job endpoint invalid json job_id=%s status=%s elapsed_ms=%d",
                job_id,
                response.status_code,
                elapsed_ms,
            )
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_CONTRACT,
                status=response.status_code,
                retryable=True,
            ) from exc
        if not isinstance(payload, dict):
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_CONTRACT,
                status=response.status_code,
                retryable=True,
            )
        logger.info(
            "java job endpoint ok job_id=%s status=%s elapsed_ms=%d",
            job_id,
            response.status_code,
            elapsed_ms,
        )
        return payload

    def _validate(self, model: type[ModelT], payload: object, *, job_id: str) -> ModelT:
        try:
            return model.model_validate(payload)
        except ValidationError as exc:
            logger.warning("java job endpoint contract mismatch job_id=%s", job_id)
            raise JobGatewayError(
                ErrorCode.MODEL_UNAVAILABLE,
                REASON_CONTRACT,
                retryable=True,
            ) from exc

    def _log_failure(self, *, job_id: str, started: float, reason: str) -> None:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        logger.warning(
            "java job endpoint failed job_id=%s reason=%s elapsed_ms=%d",
            job_id,
            reason,
            elapsed_ms,
        )


def _reason_for_status(status: int) -> str:
    if status == 404:
        return REASON_NOT_FOUND
    if status == 409:
        return REASON_STALE_LEASE
    if status == 429:
        return REASON_RATE_LIMITED
    if 400 <= status < 500:
        return REASON_REJECTED
    return REASON_UNAVAILABLE


__all__ = [
    "CONNECT_TIMEOUT_SECONDS",
    "HEARTBEAT_SUFFIX",
    "JOBS_PATH",
    "REASON_CONTRACT",
    "REASON_NOT_FOUND",
    "REASON_RATE_LIMITED",
    "REASON_REJECTED",
    "REASON_STALE_LEASE",
    "REASON_TIMEOUT",
    "REASON_UNAVAILABLE",
    "RESULTS_SUFFIX",
    "SNAPSHOT_SUFFIX",
    "JavaJobClient",
    "JobGatewayError",
]
