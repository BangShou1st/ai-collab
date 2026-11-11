"""``POST /v1/planning/draft``：计划草稿（设计 §7.6，W5 冻结文档 §1）。

端到端流程：

1. 服务令牌鉴权（与 rerank/plan/decide 共用 ``api/auth.py``，scope=``planning``）；
2. 在 ``asyncio.timeout(min(请求 deadlineMs, AI_WORKER_PLANNING_DEADLINE_MS))`` 内从
   PromptStore 取 ``planning.draft.system``/``planning.draft.user``、调用 Java 网关
   （``purpose=PLANNING``）、解析并自校验；超时 → ``DEADLINE_EXCEEDED``(504)，无半成品；
3. 自校验包含冻结 §1 第 1–8 条（数量/tempKey/引用/依赖环/日期/成员/来源/warnings）；失败 →
   ``SCHEMA_VIOLATION``(422)，``details.reason`` 是固定短语，不回显模型原文；
4. 成员越界 → 置 null 并 warning（不报错）；**``sourceRefs`` 恒为空数组**：模型给出的任何
   ref 全部丢弃并记 ``ai_worker_planning_source_refs_dropped_total``（W5-Java 复核：Python 的
   引用命名空间无法映射 Java ``PlanSource.ref``，非空 ref 会让 shadow 稳定
   ``SOURCE_REF_INVALID``）；
5. ``usage`` 来自 Java 网关；缺失标 ``UNKNOWN`` 且 token 为 ``null``（不填 0）。

**边界**：Python 只产出草稿，不落库、不发事件、不改用户结果。Java 收到后**必须重跑**
``TaskPlanDraftValidator``（成员存在性、来源 id、日期、依赖环、数量上限、版本），
Python 的 warnings 只作提示（设计 §7.6）。

指标（独立 ``REGISTRY``，低基数）：``ai_worker_planning_draft_duration_seconds{outcome}``、
``ai_worker_planning_draft_warnings_total``、``ai_worker_planning_draft_schema_violation_total``、
``ai_worker_planning_source_refs_dropped_total``。
日志只记 ``call_id`` / ``outcome`` / ``elapsed_ms`` / ``warning_count``，不打印 goal、项目描述、
成员名单、Prompt 或模型输出。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.api.auth import require_token
from ai_collab_worker.clients.java_gateway import (
    PURPOSE_PLANNING,
    JavaGatewayClient,
)
from ai_collab_worker.cognition.planning.parser import DraftValidationError, parse_draft
from ai_collab_worker.cognition.planning.prompt import (
    build_planning_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.cognition.planning.validator import validate_draft
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.planning import (
    PlanningDraftRequest,
    PlanningDraftResponse,
    PlanningUsage,
    PlanningUsageSource,
)
from ai_collab_worker.contract.rerank import RerankUsage
from ai_collab_worker.observability.metrics import (
    PLANNING_DURATION,
    PLANNING_SCHEMA_VIOLATION,
    PLANNING_SOURCE_REFS_DROPPED,
    PLANNING_WARNINGS,
)
from ai_collab_worker.prompts.store import PromptStore

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 路由路径（router 带 ``/v1/planning`` 前缀）。
PLANNING_PATH: Final[str] = "/draft"

#: Java 网关的 prompt 上限（64 KiB，与 decide 同口径）。
PROMPT_BYTE_LIMIT: Final[int] = 64 * 1024

#: 指标 outcome 取值（低基数）。
OUTCOME_OK: Final[str] = "ok"
OUTCOME_DEADLINE: Final[str] = "deadline"
OUTCOME_MODEL_ERROR: Final[str] = "model_error"
OUTCOME_INVALID: Final[str] = "invalid"

router = APIRouter(prefix="/v1/planning", tags=["planning-draft"])


@dataclass(frozen=True)
class PlanningOutcome:
    """一次成功草稿：契约响应 + 被丢弃的 sourceRefs 条数（供指标计数）。"""

    response: PlanningDraftResponse
    dropped_source_refs: int


class PlanningService:
    """planning 的业务编排：Prompt → 网关 → 解析 → 自校验 → 契约响应。"""

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

    @property
    def model_alias(self) -> str:
        """本波响应不含 ``modelAlias``；保留给后续网关选路与日志关联。"""

        return self._settings.planning_model_alias

    def effective_deadline_ms(self, body: PlanningDraftRequest) -> int:
        """请求 deadline 与服务端上限取小；服务端永不放大调用方预算。"""

        return min(body.deadline_ms, self._settings.planning_deadline_ms)

    async def draft(
        self,
        body: PlanningDraftRequest,
        *,
        call_id: str,
        traceparent: str | None,
        deadline_ms: int,
        bearer_token: str | None = None,
    ) -> PlanningOutcome:
        """调用网关并返回通过自校验的草稿；非法输出 → ``SCHEMA_VIOLATION``(422)。"""

        prompt = build_planning_prompt(self._prompt_store, body)
        if prompt_size_bytes(prompt) > PROMPT_BYTE_LIMIT:
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "拼出的 prompt 超过 Java 网关 64 KiB 上限",
                status_code=422,
                details={"limitBytes": PROMPT_BYTE_LIMIT},
            )
        result = await self._gateway.call_model(
            system_prompt=prompt.system,
            user_prompt=prompt.user,
            deadline_ms=deadline_ms,
            call_id=call_id,
            traceparent=traceparent,
            # planning 草稿在 Java 侧按 ModelPurpose.PLANNING 选路/记账。
            purpose=PURPOSE_PLANNING,
            bearer_token=bearer_token,
        )
        try:
            output = parse_draft(result.content)
            validated = validate_draft(output, body)
        except DraftValidationError as exc:
            raise AppError(
                ErrorCode.SCHEMA_VIOLATION,
                "模型草稿输出不符合契约",
                status_code=422,
                details={"reason": exc.reason},
            ) from exc
        return PlanningOutcome(
            response=PlanningDraftResponse(
                plan=validated.plan,
                warnings=validated.warnings,
                usage=_usage_from_gateway(result.usage),
            ),
            dropped_source_refs=validated.dropped_source_refs,
        )


def get_planning_service(request: Request) -> PlanningService:
    """从 ``app.state.planning_service`` 取服务；未配置属于装配错误（500 INTERNAL）。"""

    service = getattr(request.app.state, "planning_service", None)
    if not isinstance(service, PlanningService):
        msg = "planning_service 未配置"
        raise RuntimeError(msg)
    return service


PlanningServiceDep = Annotated[PlanningService, Depends(get_planning_service)]


@router.post(
    PLANNING_PATH,
    response_model=PlanningDraftResponse,
    summary="生成计划草稿（里程碑 + 任务）；Python 只产出草稿，Java 必须重校验",
)
async def draft(
    request: Request, body: PlanningDraftRequest, service: PlanningServiceDep
) -> Response:
    """处理一次 planning draft 请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    call_id = _call_id()
    outcome: str = OUTCOME_MODEL_ERROR
    warning_count = 0
    try:
        bearer_token = require_token(request, scope="planning", audience="ai-worker")
        deadline_ms = service.effective_deadline_ms(body)
        try:
            async with asyncio.timeout(deadline_ms / 1000.0):
                result = await service.draft(
                    body,
                    call_id=call_id,
                    traceparent=request.headers.get("traceparent"),
                    deadline_ms=deadline_ms,
                    bearer_token=bearer_token,
                )
        except TimeoutError as exc:
            raise AppError(
                ErrorCode.DEADLINE_EXCEEDED,
                "计划草稿生成超时",
                status_code=504,
                retryable=True,
            ) from exc

        if result.dropped_source_refs:
            PLANNING_SOURCE_REFS_DROPPED.inc(result.dropped_source_refs)
        response = result.response
        warning_count = len(response.warnings)
        if warning_count:
            PLANNING_WARNINGS.inc(warning_count)
        outcome = OUTCOME_OK
        return _json_response(serialize_response(response))
    except AppError as exc:
        if exc.code is ErrorCode.SCHEMA_VIOLATION:
            PLANNING_SCHEMA_VIOLATION.inc()
        outcome = _outcome_for_error(exc)
        raise
    finally:
        elapsed = time.perf_counter() - started
        PLANNING_DURATION.labels(outcome=outcome).observe(elapsed)
        logger.info(
            "planning_draft call_id=%s outcome=%s elapsed_ms=%d warning_count=%d",
            call_id,
            outcome,
            int(elapsed * 1000),
            warning_count,
        )


def serialize_response(response: PlanningDraftResponse) -> bytes:
    """固定的紧凑 UTF-8 JSON（不含调用方输入原文）。"""

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

    return f"planning:{uuid.uuid4().hex}"


def _usage_from_gateway(usage: RerankUsage | None) -> PlanningUsage:
    """网关 usage → 契约 usage；缺失标 UNKNOWN 且 token 为 null，不填 0。"""

    if usage is None:
        return PlanningUsage()
    try:
        source = PlanningUsageSource(usage.source.value)
    except ValueError:
        source = PlanningUsageSource.UNKNOWN
    return PlanningUsage(
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
    "OUTCOME_DEADLINE",
    "OUTCOME_INVALID",
    "OUTCOME_MODEL_ERROR",
    "OUTCOME_OK",
    "PLANNING_PATH",
    "PROMPT_BYTE_LIMIT",
    "PlanningOutcome",
    "PlanningService",
    "get_planning_service",
    "router",
    "serialize_response",
]
