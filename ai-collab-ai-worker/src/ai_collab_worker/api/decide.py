"""``POST /v1/agent/decide``：Agent 单轮决策建议（设计 §7.5，W4 冻结文档 §2）。

Python 只返回**建议**，不构成任何授权：Java 每次仍重校验工具名、Schema、项目权限、
``grantedTools``、写审批、预算与幂等（设计 §5.3 J4 边界）。本端点不接收/解释
``leaseEpoch``，不返回 token 流，不写任何业务表。

端到端流程：

1. 服务令牌鉴权（与 rerank/query plan 共用 ``api/auth.py``，支持静态令牌或 Ed25519 短令牌）；
2. 幂等键 ``(runId, attempt, turnNo, snapshotHash)``：命中同 body 指纹 → **逐字节重放**且不再
   调网关；同键不同 body → ``INVALID_REQUEST``(400)；只缓存成功响应，TTL 10 分钟、上限 1024；
3. 在 ``asyncio.timeout(min(budget.deadlineMs, 服务端上限))`` 内取 Prompt、调网关、解析；
   超时 → ``DEADLINE_EXCEEDED``(504)，**不返回半成品**；
4. 模型输出非法 / ``CALL_TOOL`` 缺 ``toolCall`` / 工具名不在 ``tools`` 内 → **内部重试一次**
   （在 user prompt 末尾追加固定纠错指令），仍非法 → ``SCHEMA_VIOLATION``(422)；
   其它决策带 ``toolCall`` → 同样 422；
5. 拼出的 prompt（system+user，另预留纠错段）超过 Java 网关 64 KiB → ``INVALID_REQUEST``(422)；
6. ``promptVersion`` 取本次 ``decide.system`` 的 manifest 版本，``modelAlias`` 取 worker 配置，
   ``latencyMs`` 取网关返回值（缺失 null），``usage`` 缺失标 UNKNOWN 且 token 为 null。

指标（独立 ``REGISTRY``，低基数）：``ai_worker_decide_duration_seconds{outcome,decision}``、
``ai_worker_decide_retry_total``、``ai_worker_decide_invalid_tool_call_total``、
``ai_worker_decide_contract_violation_total``。日志只记 call_id / outcome / elapsed_ms。
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.api.auth import require_token
from ai_collab_worker.api.idempotency import CacheEntry, IdempotencyCache
from ai_collab_worker.clients.java_gateway import (
    PURPOSE_AGENT,
    JavaGatewayClient,
    ModelCallResult,
)
from ai_collab_worker.cognition.decide.parser import (
    DecisionResult,
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.cognition.decide.prompt import (
    MAX_CORRECTION_BYTES,
    DecidePrompt,
    build_correction_prompt,
    build_decide_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.agent_decide import (
    AgentDecideRequest,
    AgentDecideResponse,
    DecideDecision,
    DecideUsage,
    DecideUsageSource,
)
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.rerank import RerankUsage
from ai_collab_worker.observability.metrics import (
    DECIDE_CONTRACT_VIOLATION,
    DECIDE_DURATION,
    DECIDE_INVALID_TOOL_CALL,
    DECIDE_RETRY,
)
from ai_collab_worker.prompts.store import PromptStore

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 路由路径（router 带 ``/v1/agent`` 前缀）。
DECIDE_PATH: Final[str] = "/decide"

#: Java 网关的 prompt 上限（64 KiB，冻结文档 §2 上下文表最后一行的硬约束）。
PROMPT_BYTE_LIMIT: Final[int] = 64 * 1024

#: 指标 outcome 取值（低基数）。
OUTCOME_OK: Final[str] = "ok"
OUTCOME_DEADLINE: Final[str] = "deadline"
OUTCOME_MODEL_ERROR: Final[str] = "model_error"
OUTCOME_INVALID: Final[str] = "invalid"

#: 失败/无法判定时 ``decision`` 标签的取值。
DECISION_NONE: Final[str] = "none"

router = APIRouter(prefix="/v1/agent", tags=["agent-decide"])


@dataclass(frozen=True)
class DecideOutcome:
    """一次成功决策：响应 + 是否发生过内部纠错重试。"""

    response: AgentDecideResponse
    retried: bool


class DecideService:
    """decide 的业务编排：幂等缓存 → Prompt → 网关 → 解析/重试 → 契约响应。"""

    def __init__(
        self,
        *,
        settings: Settings,
        gateway: JavaGatewayClient,
        prompt_store: PromptStore,
    ) -> None:
        self._settings: Final[Settings] = settings
        self._gateway: Final[JavaGatewayClient] = gateway
        self._prompt_store: Final[PromptStore] = prompt_store
        self._cache: Final[IdempotencyCache] = IdempotencyCache(
            ttl_seconds=settings.idempotency_ttl_seconds
        )

    def cached(self, key: str, fingerprint: str) -> CacheEntry | None:
        """命中返回缓存条目；同键不同请求体抛 ``INVALID_REQUEST``(400)。"""

        entry = self._cache.get(key)
        if entry is None:
            return None
        if not hmac.compare_digest(entry.fingerprint, fingerprint):
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "同一幂等键的请求体不一致",
                status_code=400,
            )
        return entry

    def remember(self, key: str, *, fingerprint: str, body: bytes) -> None:
        """只缓存成功响应；失败不写缓存（瞬时失败允许重试）。"""

        self._cache.put(key, fingerprint=fingerprint, body=body)

    def effective_deadline_ms(self, body: AgentDecideRequest) -> int:
        """请求 budget.deadlineMs 与服务端上限取小；服务端永不放大调用方预算。"""

        return min(body.budget.deadline_ms, self._settings.decide_deadline_ms)

    async def decide(
        self,
        body: AgentDecideRequest,
        *,
        call_id: str,
        traceparent: str | None,
        deadline_ms: int,
        bearer_token: str | None = None,
    ) -> DecideOutcome:
        """调用网关并返回契约响应；非法输出内部重试一次后仍非法则 422。"""

        prompt = build_decide_prompt(self._prompt_store, body)
        if prompt_size_bytes(prompt) + MAX_CORRECTION_BYTES > PROMPT_BYTE_LIMIT:
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "拼出的 prompt 超过 Java 网关 64 KiB 上限",
                status_code=422,
                details={"limitBytes": PROMPT_BYTE_LIMIT},
            )

        deadline_at = time.monotonic() + deadline_ms / 1000.0
        first = await self._call_model(
            prompt=prompt,
            user_prompt=prompt.user,
            call_id=call_id,
            traceparent=traceparent,
            deadline_at=deadline_at,
            bearer_token=bearer_token,
        )
        retried = False
        try:
            parsed = parse_decision(first.content, body.tools)
        except DecisionValidationError as first_error:
            # 冻结文档 §2 规则 3：内部只重试一次，附纠错指令（不回显模型原文）。
            DECIDE_RETRY.inc()
            retried = True
            correction = build_correction_prompt(prompt.user, first_error.reason)
            second = await self._call_model(
                prompt=prompt,
                user_prompt=correction,
                call_id=f"{call_id}:retry",
                traceparent=traceparent,
                deadline_at=deadline_at,
                bearer_token=bearer_token,
            )
            try:
                parsed = parse_decision(second.content, body.tools)
            except DecisionValidationError as second_error:
                DECIDE_CONTRACT_VIOLATION.inc()
                if second_error.invalid_tool_call:
                    DECIDE_INVALID_TOOL_CALL.inc()
                raise AppError(
                    ErrorCode.SCHEMA_VIOLATION,
                    "模型决策输出不符合契约",
                    status_code=422,
                    details={"reason": second_error.reason},
                ) from second_error
            result = second
        else:
            result = first

        return DecideOutcome(
            response=self._response(prompt=prompt, decision=parsed, result=result),
            retried=retried,
        )

    async def _call_model(
        self,
        *,
        prompt: DecidePrompt,
        user_prompt: str,
        call_id: str,
        traceparent: str | None,
        deadline_at: float,
        bearer_token: str | None = None,
    ) -> ModelCallResult:
        """按剩余预算调用网关；外层 ``asyncio.timeout`` 负责端到端截止。"""

        remaining_ms = max(1, int((deadline_at - time.monotonic()) * 1000))
        return await self._gateway.call_model(
            system_prompt=prompt.system,
            user_prompt=user_prompt,
            deadline_ms=remaining_ms,
            call_id=call_id,
            traceparent=traceparent,
            # decide 属于 Agent 决策：Java 侧按 ModelPurpose.AGENT 选路/记账。
            purpose=PURPOSE_AGENT,
            bearer_token=bearer_token,
        )

    def _response(
        self,
        *,
        prompt: DecidePrompt,
        decision: DecisionResult,
        result: ModelCallResult,
    ) -> AgentDecideResponse:
        """组装契约响应；``modelAlias`` 取配置而不是上游响应，``latencyMs`` 取网关值。"""

        return AgentDecideResponse(
            decision=decision.decision,
            tool_call=decision.tool_call,
            usage=_usage_from_gateway(result.usage),
            prompt_version=prompt.version,
            model_alias=self._settings.decide_model_alias,
            latency_ms=result.latency_ms,
        )


def get_decide_service(request: Request) -> DecideService:
    """从 ``app.state.decide_service`` 取服务；未配置属于装配错误（500 INTERNAL）。"""

    service = getattr(request.app.state, "decide_service", None)
    if not isinstance(service, DecideService):
        msg = "decide_service 未配置"
        raise RuntimeError(msg)
    return service


DecideServiceDep = Annotated[DecideService, Depends(get_decide_service)]


@router.post(
    DECIDE_PATH,
    response_model=AgentDecideResponse,
    summary="对本次工具轮给出一条决策建议（CALL_TOOL/FINAL/ASK_USER/NO_OP）",
)
async def decide(request: Request, body: AgentDecideRequest, service: DecideServiceDep) -> Response:
    """处理一次 decide 请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    call_id = _call_id()
    outcome: str = OUTCOME_MODEL_ERROR
    decision_label: str = DECISION_NONE
    try:
        bearer_token = require_token(request, scope="decide", audience="ai-worker")
        key = idempotency_key(body)
        fingerprint = request_fingerprint(body)
        entry = service.cached(key, fingerprint)
        if entry is not None:
            decision_label = _decision_label(entry.body)
            outcome = OUTCOME_OK
            return _json_response(entry.body)

        deadline_ms = service.effective_deadline_ms(body)
        try:
            async with asyncio.timeout(deadline_ms / 1000.0):
                result = await service.decide(
                    body,
                    call_id=call_id,
                    traceparent=request.headers.get("traceparent"),
                    deadline_ms=deadline_ms,
                    bearer_token=bearer_token,
                )
        except TimeoutError as exc:
            raise AppError(
                ErrorCode.DEADLINE_EXCEEDED,
                "决策超时",
                status_code=504,
                retryable=True,
            ) from exc

        payload = serialize_response(result.response)
        service.remember(key, fingerprint=fingerprint, body=payload)
        outcome = OUTCOME_OK
        decision_label = result.response.decision.value
        return _json_response(payload)
    except AppError as exc:
        outcome = _outcome_for_error(exc)
        raise
    finally:
        elapsed = time.perf_counter() - started
        DECIDE_DURATION.labels(outcome=outcome, decision=decision_label).observe(elapsed)
        logger.info(
            "decide call_id=%s outcome=%s elapsed_ms=%d",
            call_id,
            outcome,
            int(elapsed * 1000),
        )


def idempotency_key(body: AgentDecideRequest) -> str:
    """幂等键 ``(runId, attempt, turnNo, snapshotHash)`` 的无歧义字符串形式。"""

    return json.dumps(
        [body.run_id, body.attempt, body.turn_no, body.snapshot_hash],
        ensure_ascii=False,
        separators=(",", ":"),
    )


def request_fingerprint(body: AgentDecideRequest) -> str:
    """请求身份指纹：整个请求体（除 ``budget.deadlineMs``）的稳定 SHA-256。

    ``deadlineMs`` 是本次调用的剩余预算，不是请求身份：Java/调用方重试时可能把它调小，
    不应误判为"同键不同 body"（与 rerank 的 ``request_fingerprint`` 同口径）。
    """

    payload = body.model_dump(by_alias=True, mode="json")
    budget = payload.get("budget")
    if isinstance(budget, dict):
        payload["budget"] = {name: value for name, value in budget.items() if name != "deadlineMs"}
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def serialize_response(response: AgentDecideResponse) -> bytes:
    """固定的紧凑 UTF-8 JSON；缓存重放与首次响应共用同一份字节。"""

    payload = response.model_dump(by_alias=True, mode="json")
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _json_response(payload: bytes) -> Response:
    return Response(content=payload, status_code=200, media_type="application/json")


def _call_id() -> str:
    """每次模型调用唯一；不含请求正文，可安全进日志。"""

    return f"decide:{uuid.uuid4().hex}"


def _decision_label(body: bytes) -> str:
    """从缓存响应字节里取 decision 标签；无法判定时 ``none``（低基数）。"""

    try:
        payload = json.loads(body)
    except ValueError:
        return DECISION_NONE
    if not isinstance(payload, dict):
        return DECISION_NONE
    raw = payload.get("decision")
    allowed = {decision.value for decision in DecideDecision}
    return raw if isinstance(raw, str) and raw in allowed else DECISION_NONE


def _usage_from_gateway(usage: RerankUsage | None) -> DecideUsage:
    """网关 usage → 契约 usage；缺失标 UNKNOWN 且 token 为 null，不填 0。"""

    if usage is None:
        return DecideUsage()
    try:
        source = DecideUsageSource(usage.source.value)
    except ValueError:
        source = DecideUsageSource.UNKNOWN
    return DecideUsage(
        input_tokens=usage.input_tokens,
        output_tokens=usage.output_tokens,
        source=source,
    )


def _outcome_for_error(exc: AppError) -> str:
    if exc.code == ErrorCode.DEADLINE_EXCEEDED:
        return OUTCOME_DEADLINE
    if exc.code in {ErrorCode.INVALID_REQUEST, ErrorCode.SCHEMA_VIOLATION}:
        return OUTCOME_INVALID
    return OUTCOME_MODEL_ERROR


__all__ = [
    "DECIDE_PATH",
    "DECISION_NONE",
    "OUTCOME_DEADLINE",
    "OUTCOME_INVALID",
    "OUTCOME_MODEL_ERROR",
    "OUTCOME_OK",
    "PROMPT_BYTE_LIMIT",
    "DecideOutcome",
    "DecideService",
    "get_decide_service",
    "idempotency_key",
    "request_fingerprint",
    "router",
    "serialize_response",
]
