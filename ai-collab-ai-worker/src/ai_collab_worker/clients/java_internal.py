"""Java 内部接口客户端（P1 契约 §2 凭据租借、§3 用量回报、§4.3 任务回调）。

全部由 Python 发起，路径固定：

- ``POST {base}/internal/ai/credentials/lease``（scope ``credentials:lease``）
- ``POST {base}/internal/ai/usage``（scope ``usage:report``）
- ``POST {base}/internal/ai/tasks/{taskId}/claim``
- ``GET  {base}/internal/ai/tasks/{taskId}/snapshot``
- ``POST {base}/internal/ai/tasks/{taskId}/heartbeat``
- ``POST {base}/internal/ai/tasks/{taskId}/result``
- ``POST {base}/internal/ai/tasks/{taskId}/fail``

固定口径：

- **令牌只透传**：``bearer_token`` 是 Java 签发的 Ed25519 短令牌原值，原样放进
  ``Authorization``，不重签、不落盘、不打印；
- 失败统一抛 :class:`JavaInternalError`：``reason`` 是低基数短语，``status`` 是 HTTP 状态
  （传输层失败为 ``None``），``code`` 是响应信封里的错误码（如 ``TASK_CLAIM_CONFLICT``，只接受
  ``[A-Z_]`` 形状，避免把任意响应内容带进日志）。异常消息只含 reason 与 status；
- 本客户端**不重试**：重试策略属于调用方（用量上报按同一 callId 退避重试，任务回调由消费框架
  决定），这样每个调用点的幂等语义都是显式的；
- 日志只记路径类别、状态与耗时，绝不记请求体、响应体、令牌与 lease 的 apiKey。
"""

from __future__ import annotations

import logging
import re
import time
from collections.abc import Mapping, Sequence
from typing import Any, Final

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pydantic.alias_generators import to_camel

from ai_collab_worker.models.lease import Lease, LeaseRequest

logger: Final[logging.Logger] = logging.getLogger(__name__)

LEASE_PATH: Final[str] = "/internal/ai/credentials/lease"
USAGE_PATH: Final[str] = "/internal/ai/usage"
TASKS_PATH: Final[str] = "/internal/ai/tasks"

CONNECT_TIMEOUT_SECONDS: Final[float] = 2.0
READ_TIMEOUT_SECONDS: Final[float] = 10.0
WRITE_TIMEOUT_SECONDS: Final[float] = 10.0
POOL_TIMEOUT_SECONDS: Final[float] = 2.0

REASON_TIMEOUT: Final[str] = "timeout"
REASON_UNAVAILABLE: Final[str] = "unavailable"
REASON_UNAUTHORIZED: Final[str] = "unauthorized"
REASON_FORBIDDEN: Final[str] = "forbidden"
REASON_NOT_FOUND: Final[str] = "not_found"
REASON_CONFLICT: Final[str] = "conflict"
REASON_RATE_LIMITED: Final[str] = "rate_limited"
REASON_REJECTED: Final[str] = "rejected"
REASON_CONTRACT: Final[str] = "contract"

#: 响应信封里允许透出的错误码形状。
_CODE_PATTERN: Final[re.Pattern[str]] = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")


class JavaInternalError(Exception):
    """Java 内部接口调用失败；消息不含响应体与令牌。"""

    def __init__(
        self,
        reason: str,
        *,
        status: int | None = None,
        code: str | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(f"{reason}:{status if status is not None else '-'}")
        self.reason = reason
        self.status = status
        self.code = code
        self.retryable = retryable


class _Wire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
        frozen=True,
        hide_input_in_errors=True,
    )


class ClaimResponse(_Wire):
    """claim 成功：``leaseToken`` 用于后续回调，``token`` 是新签的任务令牌。"""

    lease_token: str = Field(min_length=1, max_length=512, repr=False)
    lease_expires_at: str | None = Field(default=None, max_length=64)
    token: str = Field(min_length=1, max_length=8192, repr=False)


class HeartbeatResponse(_Wire):
    """heartbeat：续租 60s，换发新令牌；``cancelRequested`` 为真时调用方必须中止。"""

    lease_expires_at: str | None = Field(default=None, max_length=64)
    cancel_requested: bool = False
    token: str | None = Field(default=None, max_length=8192, repr=False)


class UsageItemResult(_Wire):
    """用量回报的逐条结果。"""

    call_id: str = Field(min_length=1, max_length=128)
    accepted: bool
    reason: str | None = Field(default=None, max_length=256)


class JavaInternalClient:
    """Java 内部接口的异步客户端；生命周期跟随 app（同一连接池）。"""

    def __init__(
        self,
        *,
        base_url: str,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url: Final[str] = base_url.rstrip("/")
        self._client: Final[httpx.AsyncClient] = httpx.AsyncClient(
            transport=transport,
            timeout=httpx.Timeout(
                connect=CONNECT_TIMEOUT_SECONDS,
                read=READ_TIMEOUT_SECONDS,
                write=WRITE_TIMEOUT_SECONDS,
                pool=POOL_TIMEOUT_SECONDS,
            ),
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    # ---- §2 凭据租借 ----------------------------------------------------------

    async def lease(self, request: LeaseRequest, *, bearer_token: str) -> Lease:
        """租借一次凭据；响应校验失败时不回显任何字段值（含 apiKey）。"""

        payload = await self._request(
            "POST",
            LEASE_PATH,
            body=request.model_dump(by_alias=True, mode="json"),
            bearer_token=bearer_token,
            kind="lease",
        )
        try:
            return Lease.model_validate(payload)
        except ValidationError:
            logger.warning("java internal contract mismatch kind=lease")
            raise JavaInternalError(REASON_CONTRACT, status=200, retryable=False) from None

    # ---- §3 用量回报 ----------------------------------------------------------

    async def report_usage(
        self,
        items: Sequence[Mapping[str, object]],
        *,
        bearer_token: str,
    ) -> tuple[UsageItemResult, ...]:
        """整批 200 返回逐条结果；逐条结果容忍 ``[...]`` 或 ``{"results"|"items": [...]}``。"""

        payload = await self._request(
            "POST",
            USAGE_PATH,
            body={"items": [dict(item) for item in items]},
            bearer_token=bearer_token,
            kind="usage",
            allow_list=True,
        )
        raw: object = payload
        if isinstance(payload, dict):
            raw = payload.get("results", payload.get("items", []))
        if not isinstance(raw, list):
            raise JavaInternalError(REASON_CONTRACT, status=200, retryable=True)
        try:
            return tuple(UsageItemResult.model_validate(entry) for entry in raw)
        except ValidationError:
            raise JavaInternalError(REASON_CONTRACT, status=200, retryable=True) from None

    # ---- §4.3 任务回调 --------------------------------------------------------

    async def claim(
        self,
        task_id: str,
        *,
        task_type: str,
        epoch: int,
        worker_id: str,
        bearer_token: str,
    ) -> ClaimResponse:
        payload = await self._request(
            "POST",
            f"{TASKS_PATH}/{task_id}/claim",
            body={"taskType": task_type, "epoch": epoch, "workerId": worker_id},
            bearer_token=bearer_token,
            kind="claim",
        )
        return _validate(ClaimResponse, payload, kind="claim")

    async def snapshot(self, task_id: str, *, bearer_token: str) -> dict[str, Any]:
        payload = await self._request(
            "GET",
            f"{TASKS_PATH}/{task_id}/snapshot",
            body=None,
            bearer_token=bearer_token,
            kind="snapshot",
        )
        if not isinstance(payload, dict):
            raise JavaInternalError(REASON_CONTRACT, status=200, retryable=True)
        return payload

    async def heartbeat(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        bearer_token: str,
    ) -> HeartbeatResponse:
        payload = await self._request(
            "POST",
            f"{TASKS_PATH}/{task_id}/heartbeat",
            body={"epoch": epoch, "leaseToken": lease_token},
            bearer_token=bearer_token,
            kind="heartbeat",
        )
        return _validate(HeartbeatResponse, payload, kind="heartbeat")

    async def result(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        payload: Mapping[str, object],
        bearer_token: str,
    ) -> None:
        await self._request(
            "POST",
            f"{TASKS_PATH}/{task_id}/result",
            body={"epoch": epoch, "leaseToken": lease_token, "payload": dict(payload)},
            bearer_token=bearer_token,
            kind="result",
            allow_empty=True,
        )

    async def fail(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        error_code: str,
        retryable: bool,
        bearer_token: str,
    ) -> None:
        await self._request(
            "POST",
            f"{TASKS_PATH}/{task_id}/fail",
            body={
                "epoch": epoch,
                "leaseToken": lease_token,
                "errorCode": error_code,
                "retryable": retryable,
            },
            bearer_token=bearer_token,
            kind="fail",
            allow_empty=True,
        )

    # ---- 内部 ----------------------------------------------------------------

    async def _request(
        self,
        method: str,
        path: str,
        *,
        body: Mapping[str, object] | None,
        bearer_token: str,
        kind: str,
        allow_list: bool = False,
        allow_empty: bool = False,
    ) -> Any:
        headers = {"Authorization": f"Bearer {bearer_token}"} if bearer_token else {}
        started = time.perf_counter()
        try:
            response = await self._client.request(
                method,
                f"{self._base_url}{path}",
                json=dict(body) if body is not None else None,
                headers=headers,
            )
        except httpx.TimeoutException:
            _log(kind, None, started, REASON_TIMEOUT)
            raise JavaInternalError(REASON_TIMEOUT, retryable=True) from None
        except httpx.HTTPError:
            _log(kind, None, started, REASON_UNAVAILABLE)
            raise JavaInternalError(REASON_UNAVAILABLE, retryable=True) from None

        status = response.status_code
        if status >= 300:
            reason, retryable = _classify(status)
            _log(kind, status, started, reason)
            raise JavaInternalError(
                reason, status=status, code=_error_code(response), retryable=retryable
            )
        _log(kind, status, started, "ok")
        if allow_empty and not response.content:
            return None
        try:
            payload = response.json()
        except ValueError:
            if allow_empty:
                return None
            raise JavaInternalError(REASON_CONTRACT, status=status, retryable=True) from None
        if isinstance(payload, dict) or (allow_list and isinstance(payload, list)) or allow_empty:
            return payload
        raise JavaInternalError(REASON_CONTRACT, status=status, retryable=True)


def _validate[ModelT: BaseModel](model: type[ModelT], payload: object, *, kind: str) -> ModelT:
    try:
        return model.model_validate(payload)
    except ValidationError:
        logger.warning("java internal contract mismatch kind=%s", kind)
        raise JavaInternalError(REASON_CONTRACT, status=200, retryable=True) from None


def _classify(status: int) -> tuple[str, bool]:
    if status == 401:
        return REASON_UNAUTHORIZED, False
    if status == 403:
        return REASON_FORBIDDEN, False
    if status == 404:
        return REASON_NOT_FOUND, False
    if status == 409:
        return REASON_CONFLICT, False
    if status == 429:
        return REASON_RATE_LIMITED, True
    if 400 <= status < 500:
        return REASON_REJECTED, False
    return REASON_UNAVAILABLE, True


def _error_code(response: httpx.Response) -> str | None:
    try:
        payload = response.json()
    except ValueError:
        return None
    if not isinstance(payload, dict):
        return None
    code = payload.get("code")
    if not isinstance(code, str):
        error = payload.get("error")
        code = error.get("code") if isinstance(error, dict) else None
    if isinstance(code, str) and _CODE_PATTERN.match(code):
        return code
    return None


def _log(kind: str, status: int | None, started: float, outcome: str) -> None:
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    level = logging.INFO if outcome == "ok" else logging.WARNING
    logger.log(
        level,
        "java internal call kind=%s status=%s outcome=%s elapsed_ms=%d",
        kind,
        status if status is not None else "-",
        outcome,
        elapsed_ms,
    )


__all__ = [
    "LEASE_PATH",
    "REASON_CONFLICT",
    "REASON_CONTRACT",
    "REASON_FORBIDDEN",
    "REASON_NOT_FOUND",
    "REASON_RATE_LIMITED",
    "REASON_REJECTED",
    "REASON_TIMEOUT",
    "REASON_UNAUTHORIZED",
    "REASON_UNAVAILABLE",
    "TASKS_PATH",
    "USAGE_PATH",
    "ClaimResponse",
    "HeartbeatResponse",
    "JavaInternalClient",
    "JavaInternalError",
    "UsageItemResult",
]
