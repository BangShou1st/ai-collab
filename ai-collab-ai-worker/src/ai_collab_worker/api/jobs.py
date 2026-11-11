"""``POST /v1/jobs``：Memory 提取 Job 的"执行一次"入口（设计 §7.7，W5 冻结文档 §3.4）。

**这个端点不是调度器**：Java 决定何时租约、是否重试、结果是否发布；Python 只做一次
"验令牌 → 心跳 → 拉快照 → 调模型 → 解析候选 → 回报结果"的同步执行，然后如实回报状态。
不建表、不写任何业务表、不调度、不重试、不发布候选（``ai_job_result.review_status`` 永远停在
``PENDING_REVIEW``，而那是 Java 的事，本侧根本没有该写路径）。

鉴权与租约绑定：

- ``require_claims(request, scope="job:execute", audience="ai-worker")``：只接受带 claims 的
  job token；``static`` 模式没有 claims → 503 fail closed（冻结 §3.4）；
- claims 必须含 ``jobId``（与 body 一致）、``attempt``、``leaseEpoch``、``snapshotHash``、
  ``projectRef``；缺失/形状非法/与 body 不一致 → **403 ``INVALID_REQUEST``**，``details.reason``
  是固定短语，不回显任何 claim 值；
- 快照返回的 ``jobId/attempt/leaseEpoch/snapshotHash/projectId`` 必须与 claims 逐项一致，
  否则同样 403 —— 这是 lease 绑定的第二道闸，避免用旧快照跑新租约。

失败映射（**二选一已定：一律 HTTP 200 + 契约状态**，除鉴权/claims/请求或模型输出非法外）：

| 情况 | 响应 |
| --- | --- |
| 成功回报 | 200 + ``status=SUCCEEDED`` |
| 端到端超时、网关超时/不可用/429、快照或结果回报的 5xx | 200 + ``FAILED_RETRYABLE`` |
| 快照 404、409 ``STALE_LEASE``、其它 4xx、心跳已终态 | 200 + ``FAILED`` |
| 模型输出不是合法候选 JSON / 字段越界 / 超过条数上限 | **422 ``SCHEMA_VIOLATION``** |
| 令牌/claims/请求体不合法 | 401 / 403 / 422 ``INVALID_REQUEST`` |

为什么 FAILED/FAILED_RETRYABLE 用 200：冻结 §3.4 允许"HTTP 503 或 200 + status"，而 200 +
契约状态让 Java 只走一条分支（读 body）就能拿到状态，不必同时解释 HTTP 码与 body；HTTP 层
错误码保留给"这次调用根本没被接受"的情况。**重试仍然只在 Java**：本侧不重试、不退避、不排期。

模型调用使用**入站同一 token**（不重签）；``purpose=AGENT``：Memory 提取属于 Agent 认知
（设计 §1.4 把"Memory 候选提取"与 Agent 决策并列），Java ``ModelPurpose`` 没有 MEMORY 档位，
本侧**不发明**新档位。``callId`` 形如 ``memory:<jobId>:<attempt>``，可安全进日志与账本。

指标（独立 ``REGISTRY``，低基数）：
``ai_worker_memory_job_requests_total{outcome}``、``ai_worker_memory_job_duration_seconds{outcome}``、
``ai_worker_memory_candidates_total``、``ai_worker_memory_invalid_source_total``，另加
``ai_worker_memory_invalid_dedupe_total``（dedupeKey 不一致被丢弃的候选数，冻结未列但需要可观测）。
日志只记 ``job_id`` / ``status`` / ``elapsed_ms`` / ``reason``。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.api.auth import require_claims
from ai_collab_worker.clients.java_gateway import (
    PURPOSE_AGENT,
    JavaGatewayClient,
)
from ai_collab_worker.clients.java_jobs import (
    REASON_NOT_FOUND,
    REASON_STALE_LEASE,
    JavaJobClient,
    JobGatewayError,
)
from ai_collab_worker.cognition.memory.parser import (
    MemoryCandidateError,
    parse_candidates,
)
from ai_collab_worker.cognition.memory.prompt import (
    build_memory_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.memory_job import (
    JobState,
    MemoryJobRequest,
    MemoryJobResponse,
    MemoryJobSnapshot,
    MemoryJobStatus,
)
from ai_collab_worker.observability.metrics import (
    MEMORY_CANDIDATES,
    MEMORY_INVALID_DEDUPE,
    MEMORY_INVALID_SOURCE,
    MEMORY_JOB_DURATION,
    MEMORY_JOB_REQUESTS,
)
from ai_collab_worker.prompts.store import PromptStore

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 路由路径（router 带 ``/v1`` 前缀）。
JOBS_PATH: Final[str] = "/jobs"

#: Java 网关的 prompt 上限（64 KiB）。
PROMPT_BYTE_LIMIT: Final[int] = 64 * 1024

#: job token claims 的字段名（冻结 §3.4 逐字）。
CLAIM_JOB_ID: Final[str] = "jobId"
CLAIM_ATTEMPT: Final[str] = "attempt"
CLAIM_LEASE_EPOCH: Final[str] = "leaseEpoch"
CLAIM_SNAPSHOT_HASH: Final[str] = "snapshotHash"
CLAIM_PROJECT_REF: Final[str] = "projectRef"

#: 对外可见的固定错误短语（不回显 claim 值）。
REASON_CLAIMS_MISMATCH: Final[str] = "job_token_claims_mismatch"
MESSAGE_CLAIMS_MISMATCH: Final[str] = "job token claims 缺失或与请求不一致"
REASON_SNAPSHOT_MISMATCH: Final[str] = "job_snapshot_binding_mismatch"
MESSAGE_SNAPSHOT_MISMATCH: Final[str] = "快照与 job token claims 不一致"

#: 指标 outcome 取值（低基数）。
OUTCOME_SUCCEEDED: Final[str] = "succeeded"
OUTCOME_FAILED_RETRYABLE: Final[str] = "failed_retryable"
OUTCOME_FAILED: Final[str] = "failed"
OUTCOME_INVALID: Final[str] = "invalid"
OUTCOME_SCHEMA_VIOLATION: Final[str] = "schema_violation"
OUTCOME_MODEL_ERROR: Final[str] = "model_error"

router = APIRouter(prefix="/v1", tags=["memory-jobs"])


@dataclass(frozen=True)
class JobClaims:
    """令牌里与租约绑定的 claims（已校验形状，尚未与快照比对）。"""

    job_id: str
    attempt: int
    lease_epoch: int
    snapshot_hash: str
    project_ref: str


@dataclass(frozen=True)
class JobOutcome:
    """一次 Job 执行的结果：契约响应 + 指标标签 + 被丢弃候选的计数。"""

    response: MemoryJobResponse
    outcome: str
    reason: str | None = None
    invalid_source: int = 0
    invalid_dedupe: int = 0


class JobService:
    """Memory Job 的业务编排：心跳 → 快照 → Prompt → 网关 → 解析 → 结果回报。"""

    def __init__(
        self,
        *,
        settings: Settings,
        gateway: JavaGatewayClient,
        jobs: JavaJobClient,
        prompt_store: PromptStore,
    ) -> None:
        self._settings: Final[Settings] = settings
        self._gateway: Final[JavaGatewayClient] = gateway
        self._jobs: Final[JavaJobClient] = jobs
        self._prompt_store: Final[PromptStore] = prompt_store

    @property
    def max_candidates(self) -> int:
        return self._settings.memory_max_candidates

    def effective_deadline_ms(self, body: MemoryJobRequest) -> int:
        """请求 deadline 与服务端上限取小；服务端永不放大调用方预算。"""

        return min(body.deadline_ms, self._settings.memory_job_deadline_ms)

    async def execute(
        self,
        body: MemoryJobRequest,
        *,
        claims: JobClaims,
        bearer_token: str,
        call_id: str,
        traceparent: str | None,
        deadline_ms: int,
    ) -> JobOutcome:
        """执行一次；失败按冻结映射成 ``FAILED`` / ``FAILED_RETRYABLE``（200 + status）。"""

        started = time.perf_counter()
        deadline_at = time.monotonic() + deadline_ms / 1000.0
        job_id = str(body.job_id)

        try:
            heartbeat = await self._jobs.heartbeat(
                job_id,
                attempt=claims.attempt,
                lease_epoch=claims.lease_epoch,
                deadline_ms=_remaining_ms(deadline_at),
                bearer_token=bearer_token,
                traceparent=traceparent,
            )
        except JobGatewayError as exc:
            return _failure_outcome(body, exc, started)

        terminal = _terminal_status(heartbeat.status)
        if terminal is not None:
            logger.info(
                "memory_job job_id=%s status=%s elapsed_ms=%d reason=terminal_state",
                job_id,
                terminal.value,
                _elapsed_ms(started),
            )
            return JobOutcome(
                response=MemoryJobResponse(
                    job_id=body.job_id,
                    status=terminal,
                    candidate_count=0,
                    latency_ms=_elapsed_ms(started),
                ),
                outcome=_outcome_for_status(terminal),
                reason="terminal_state",
            )

        try:
            snapshot = await self._jobs.fetch_snapshot(
                job_id,
                deadline_ms=_remaining_ms(deadline_at),
                bearer_token=bearer_token,
                traceparent=traceparent,
            )
        except JobGatewayError as exc:
            return _failure_outcome(body, exc, started)

        _require_snapshot_binding(snapshot, claims=claims, body=body)

        prompt = build_memory_prompt(
            self._prompt_store,
            snapshot,
            max_candidates=self._settings.memory_max_candidates,
        )
        if prompt_size_bytes(prompt) > PROMPT_BYTE_LIMIT:
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "拼出的 prompt 超过 Java 网关 64 KiB 上限",
                status_code=422,
                details={"limitBytes": PROMPT_BYTE_LIMIT},
            )
        try:
            result = await self._gateway.call_model(
                system_prompt=prompt.system,
                user_prompt=prompt.user,
                deadline_ms=_remaining_ms(deadline_at),
                call_id=call_id,
                traceparent=traceparent,
                # Memory 提取属于 Agent 认知；Java ModelPurpose 无 MEMORY 档位，不发明新值。
                purpose=PURPOSE_AGENT,
                bearer_token=bearer_token,
            )
        except AppError as exc:
            status = MemoryJobStatus.FAILED_RETRYABLE if exc.retryable else MemoryJobStatus.FAILED
            logger.info(
                "memory_job job_id=%s status=%s elapsed_ms=%d reason=model_error",
                job_id,
                status.value,
                _elapsed_ms(started),
            )
            return JobOutcome(
                response=MemoryJobResponse(
                    job_id=body.job_id,
                    status=status,
                    candidate_count=0,
                    latency_ms=_elapsed_ms(started),
                ),
                outcome=_outcome_for_status(status),
                reason="model_error",
            )

        try:
            parsed = parse_candidates(
                result.content,
                snapshot=snapshot,
                max_candidates=self._settings.memory_max_candidates,
            )
        except MemoryCandidateError as exc:
            raise AppError(
                ErrorCode.SCHEMA_VIOLATION,
                "模型候选输出不符合契约",
                status_code=422,
                details={"reason": exc.reason},
            ) from exc

        try:
            ack = await self._jobs.post_results(
                job_id,
                attempt=claims.attempt,
                lease_epoch=claims.lease_epoch,
                candidates=parsed.candidates,
                deadline_ms=_remaining_ms(deadline_at),
                bearer_token=bearer_token,
                traceparent=traceparent,
            )
        except JobGatewayError as exc:
            return _failure_outcome(body, exc, started)

        return JobOutcome(
            response=MemoryJobResponse(
                job_id=body.job_id,
                status=ack.status,
                candidate_count=len(parsed.candidates),
                latency_ms=_elapsed_ms(started),
            ),
            outcome=_outcome_for_status(ack.status),
            invalid_source=parsed.invalid_source,
            invalid_dedupe=parsed.invalid_dedupe,
        )


def validate_job_claims(claims: Mapping[str, object], body: MemoryJobRequest) -> JobClaims:
    """把 claims 校验成 :class:`JobClaims`；缺失/形状非法/与 body 不一致 → 403。

    错误信封只含固定短语，绝不回显 claim 值（可能是令牌内容的一部分）。
    """

    job_id = _claim_str(claims, CLAIM_JOB_ID)
    attempt = _claim_int(claims, CLAIM_ATTEMPT, minimum=1)
    lease_epoch = _claim_int(claims, CLAIM_LEASE_EPOCH, minimum=0)
    snapshot_hash = _claim_str(claims, CLAIM_SNAPSHOT_HASH)
    project_ref = _claim_str(claims, CLAIM_PROJECT_REF)
    if (
        job_id is None
        or attempt is None
        or lease_epoch is None
        or snapshot_hash is None
        or project_ref is None
        or job_id.strip().lower() != str(body.job_id).lower()
    ):
        raise _claims_error()
    return JobClaims(
        job_id=job_id,
        attempt=attempt,
        lease_epoch=lease_epoch,
        snapshot_hash=snapshot_hash,
        project_ref=project_ref,
    )


def get_job_service(request: Request) -> JobService:
    """从 ``app.state.job_service`` 取服务；未配置属于装配错误（500 INTERNAL）。"""

    service = getattr(request.app.state, "job_service", None)
    if not isinstance(service, JobService):
        msg = "job_service 未配置"
        raise RuntimeError(msg)
    return service


JobServiceDep = Annotated[JobService, Depends(get_job_service)]


@router.post(
    JOBS_PATH,
    response_model=MemoryJobResponse,
    summary="执行一次 Memory 提取 Job（只读快照 + 回报候选；不调度、不重试、不发布）",
)
async def run_job(request: Request, body: MemoryJobRequest, service: JobServiceDep) -> Response:
    """处理一次 Job 执行请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    job_id = str(body.job_id)
    call_id = _call_id(body)
    outcome: str = OUTCOME_MODEL_ERROR
    status_label = "error"
    reason = "-"
    try:
        bearer_token, claims = require_claims(request, scope="job:execute", audience="ai-worker")
        job_claims = validate_job_claims(claims, body)
        deadline_ms = service.effective_deadline_ms(body)
        try:
            async with asyncio.timeout(deadline_ms / 1000.0):
                result = await service.execute(
                    body,
                    claims=job_claims,
                    bearer_token=bearer_token,
                    call_id=call_id,
                    traceparent=request.headers.get("traceparent"),
                    deadline_ms=deadline_ms,
                )
        except TimeoutError:
            # 端到端超时：不回报半成品结果，按冻结返回 FAILED_RETRYABLE（HTTP 200 + status）。
            result = JobOutcome(
                response=MemoryJobResponse(
                    job_id=body.job_id,
                    status=MemoryJobStatus.FAILED_RETRYABLE,
                    candidate_count=0,
                    latency_ms=_elapsed_ms(started),
                ),
                outcome=OUTCOME_FAILED_RETRYABLE,
                reason="deadline",
            )

        if result.invalid_source:
            MEMORY_INVALID_SOURCE.inc(result.invalid_source)
        if result.invalid_dedupe:
            MEMORY_INVALID_DEDUPE.inc(result.invalid_dedupe)
        if result.response.candidate_count:
            MEMORY_CANDIDATES.inc(result.response.candidate_count)
        outcome = result.outcome
        status_label = result.response.status.value
        reason = result.reason or "-"
        return _json_response(serialize_response(result.response))
    except AppError as exc:
        outcome = _outcome_for_error(exc)
        reason = exc.code.value
        raise
    finally:
        elapsed = time.perf_counter() - started
        MEMORY_JOB_REQUESTS.labels(outcome=outcome).inc()
        MEMORY_JOB_DURATION.labels(outcome=outcome).observe(elapsed)
        logger.info(
            "memory_job job_id=%s status=%s elapsed_ms=%d reason=%s",
            job_id,
            status_label,
            int(elapsed * 1000),
            reason,
        )


def serialize_response(response: MemoryJobResponse) -> bytes:
    """固定的紧凑 UTF-8 JSON（不含候选正文与快照内容）。"""

    payload = response.model_dump(by_alias=True, mode="json")
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _json_response(payload: bytes) -> Response:
    return Response(content=payload, status_code=200, media_type="application/json")


def _require_snapshot_binding(
    snapshot: MemoryJobSnapshot,
    *,
    claims: JobClaims,
    body: MemoryJobRequest,
) -> None:
    """快照必须与 claims/请求逐项一致（lease 绑定的第二道闸）。"""

    if (
        snapshot.job_id != body.job_id
        or snapshot.attempt != claims.attempt
        or snapshot.lease_epoch != claims.lease_epoch
        or snapshot.snapshot_hash != claims.snapshot_hash
        or snapshot.project_id != claims.project_ref
    ):
        raise AppError(
            ErrorCode.INVALID_REQUEST,
            MESSAGE_SNAPSHOT_MISMATCH,
            status_code=403,
            retryable=False,
            details={"reason": REASON_SNAPSHOT_MISMATCH},
        )


def _failure_outcome(
    body: MemoryJobRequest,
    exc: JobGatewayError,
    started: float,
) -> JobOutcome:
    """把 Java Job 端点的失败映射成 ``FAILED`` / ``FAILED_RETRYABLE``（HTTP 200 + status）。"""

    status = MemoryJobStatus.FAILED if not exc.retryable else MemoryJobStatus.FAILED_RETRYABLE
    if exc.reason in {REASON_NOT_FOUND, REASON_STALE_LEASE}:
        status = MemoryJobStatus.FAILED
    logger.info(
        "memory_job job_id=%s status=%s elapsed_ms=%d reason=%s",
        body.job_id,
        status.value,
        _elapsed_ms(started),
        exc.reason,
    )
    return JobOutcome(
        response=MemoryJobResponse(
            job_id=body.job_id,
            status=status,
            candidate_count=0,
            latency_ms=_elapsed_ms(started),
        ),
        outcome=_outcome_for_status(status),
        reason=exc.reason,
    )


def _terminal_status(state: JobState) -> MemoryJobStatus | None:
    """心跳已终态时不再执行；把 Java 的状态映射成契约三值。"""

    if state in {JobState.QUEUED, JobState.LEASED, JobState.RUNNING}:
        return None
    if state is JobState.SUCCEEDED:
        return MemoryJobStatus.SUCCEEDED
    if state is JobState.FAILED_RETRYABLE:
        return MemoryJobStatus.FAILED_RETRYABLE
    return MemoryJobStatus.FAILED


def _outcome_for_status(status: MemoryJobStatus) -> str:
    return {
        MemoryJobStatus.SUCCEEDED: OUTCOME_SUCCEEDED,
        MemoryJobStatus.FAILED_RETRYABLE: OUTCOME_FAILED_RETRYABLE,
        MemoryJobStatus.FAILED: OUTCOME_FAILED,
    }[status]


def _outcome_for_error(exc: AppError) -> str:
    if exc.code is ErrorCode.SCHEMA_VIOLATION:
        return OUTCOME_SCHEMA_VIOLATION
    if exc.code is ErrorCode.INVALID_REQUEST:
        return OUTCOME_INVALID
    return OUTCOME_MODEL_ERROR


def _claim_str(claims: Mapping[str, object], name: str) -> str | None:
    value = claims.get(name)
    if not isinstance(value, str) or not value.strip():
        return None
    return value


def _claim_int(claims: Mapping[str, object], name: str, *, minimum: int) -> int | None:
    value = claims.get(name)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        return None
    return value


def _claims_error() -> AppError:
    return AppError(
        ErrorCode.INVALID_REQUEST,
        MESSAGE_CLAIMS_MISMATCH,
        status_code=403,
        retryable=False,
        details={"reason": REASON_CLAIMS_MISMATCH},
    )


def _remaining_ms(deadline_at: float) -> int:
    return max(1, int((deadline_at - time.monotonic()) * 1000))


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _call_id(body: MemoryJobRequest) -> str:
    """``memory:<jobId>:<attempt>``：可安全进日志与 Java 账本（不含正文）。"""

    return f"memory:{body.job_id}:{uuid.uuid4().hex[:8]}"


__all__ = [
    "CLAIM_ATTEMPT",
    "CLAIM_JOB_ID",
    "CLAIM_LEASE_EPOCH",
    "CLAIM_PROJECT_REF",
    "CLAIM_SNAPSHOT_HASH",
    "JOBS_PATH",
    "JobClaims",
    "JobOutcome",
    "JobService",
    "get_job_service",
    "router",
    "serialize_response",
    "validate_job_claims",
]
