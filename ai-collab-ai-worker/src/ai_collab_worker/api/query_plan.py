"""``POST /v1/rag/plan``：查询规划（设计 §7.4，W4 冻结文档 §1）。

端到端流程：

1. Ed25519 短令牌鉴权（``api/auth.py``，scope ``plan``，aud ``ai-worker``）；P1 契约 §1 起令牌还
   必须带 ``uid``/``pid``（缺失或非法 → 403 ``INVALID_REQUEST``），同一令牌用来租借凭据与上报用量；
2. 在 ``asyncio.timeout(min(请求 deadlineMs, 服务端上限))`` 内从 PromptStore 取
   ``query_plan.system``/``query_plan.user``、经模型内核（租借凭据 ``purpose=QUERY_PLAN`` → 本地
   模型客户端 → 用量上报，P1 契约 §2/§3/§5）调模型、解析模型输出；
   超时 → ``DEADLINE_EXCEEDED``(504)，**不返回半成品**；
3. 解析层丢弃模型编造的 ``filters.documentIds``（必须是请求白名单的子集），并记
   ``ai_worker_query_plan_unknown_document_ids_total``（按 id 计），不报错；
4. ``confidence`` 原样返回；``confidence < 0.5`` 时 Java 使用原查询——该语义**只在 Java**，
   Python 不做降级。

指标（独立 ``REGISTRY``，低基数）：``ai_worker_query_plan_duration_seconds{outcome}``、
``ai_worker_query_plan_confidence``、``ai_worker_query_plan_unknown_document_ids_total``。
日志只记 call_id / outcome / elapsed_ms，不打印查询、历史摘要或模型输出。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections.abc import Mapping
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.api.auth import require_claims
from ai_collab_worker.cognition.query.parser import parse_query_plan
from ai_collab_worker.cognition.query.prompt import build_query_plan_prompt
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.query_plan import QueryPlanRequest, QueryPlanResponse
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import LeasePurpose
from ai_collab_worker.observability.metrics import (
    QUERY_PLAN_CONFIDENCE,
    QUERY_PLAN_DURATION,
    QUERY_PLAN_UNKNOWN_DOCUMENT_IDS,
)
from ai_collab_worker.prompts.store import PromptStore

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 路由路径（router 带 ``/v1/rag`` 前缀）。
QUERY_PLAN_PATH: Final[str] = "/plan"

#: 指标 outcome 取值（低基数）。
OUTCOME_OK: Final[str] = "ok"
OUTCOME_DEADLINE: Final[str] = "deadline"
OUTCOME_MODEL_ERROR: Final[str] = "model_error"
OUTCOME_INVALID: Final[str] = "invalid"

router = APIRouter(prefix="/v1/rag", tags=["query-plan"])


class QueryPlanService:
    """query plan 的业务编排：Prompt → 模型内核 → 解析 → 契约响应。"""

    def __init__(
        self,
        *,
        settings: Settings,
        kernel: ModelKernel,
        prompt_store: PromptStore,
    ) -> None:
        self._settings: Final[Settings] = settings
        self._kernel: Final[ModelKernel] = kernel
        self._prompt_store: Final[PromptStore] = prompt_store

    def effective_deadline_ms(self, body: QueryPlanRequest) -> int:
        """请求 deadline 与服务端上限取小；服务端永不放大调用方预算。"""

        return min(body.deadline_ms, self._settings.query_plan_deadline_ms)

    async def plan(
        self,
        body: QueryPlanRequest,
        *,
        scope: CallScope,
    ) -> tuple[QueryPlanResponse, tuple[str, ...], str]:
        """调用模型并返回契约响应、被丢弃的编造文档 id 与本次 callId。"""

        prompt = build_query_plan_prompt(self._prompt_store, body)
        completion = await self._kernel.complete(
            scope,
            system_prompt=prompt.system,
            user_prompt=prompt.user,
        )
        parsed = parse_query_plan(completion.text, allowed_document_ids=body.document_ids)
        return parsed.response, parsed.unknown_document_ids, completion.call_id


def get_query_plan_service(request: Request) -> QueryPlanService:
    """从 ``app.state.query_plan_service`` 取服务；未配置属于装配错误（500 INTERNAL）。"""

    service = getattr(request.app.state, "query_plan_service", None)
    if not isinstance(service, QueryPlanService):
        msg = "query_plan_service 未配置"
        raise RuntimeError(msg)
    return service


QueryPlanServiceDep = Annotated[QueryPlanService, Depends(get_query_plan_service)]


@router.post(
    QUERY_PLAN_PATH,
    response_model=QueryPlanResponse,
    summary="把用户问题改写成检索查询与过滤条件（凭据租借 + 本地模型客户端）",
)
async def query_plan(
    request: Request, body: QueryPlanRequest, service: QueryPlanServiceDep
) -> Response:
    """处理一次 query plan 请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    call_id = "-"
    outcome: str = OUTCOME_MODEL_ERROR
    try:
        bearer_token, claims = require_claims(request, scope="plan", audience="ai-worker")
        scope = _call_scope(claims, bearer_token)
        deadline_ms = service.effective_deadline_ms(body)
        try:
            async with asyncio.timeout(deadline_ms / 1000.0):
                response, unknown_ids, call_id = await service.plan(body, scope=scope)
        except TimeoutError as exc:
            raise AppError(
                ErrorCode.DEADLINE_EXCEEDED,
                "查询规划超时",
                status_code=504,
                retryable=True,
            ) from exc

        if unknown_ids:
            QUERY_PLAN_UNKNOWN_DOCUMENT_IDS.inc(len(unknown_ids))
        QUERY_PLAN_CONFIDENCE.observe(response.confidence)
        outcome = OUTCOME_OK
        return _json_response(serialize_response(response))
    except AppError as exc:
        outcome = _outcome_for_error(exc)
        raise
    finally:
        elapsed = time.perf_counter() - started
        QUERY_PLAN_DURATION.labels(outcome=outcome).observe(elapsed)
        logger.info(
            "query_plan call_id=%s outcome=%s elapsed_ms=%d",
            call_id,
            outcome,
            int(elapsed * 1000),
        )


def serialize_response(response: QueryPlanResponse) -> bytes:
    """固定的紧凑 UTF-8 JSON（不含任何用户输入原文）。"""

    payload = response.model_dump(by_alias=True, mode="json")
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _json_response(payload: bytes) -> Response:
    return Response(content=payload, status_code=200, media_type="application/json")


def _call_scope(claims: Mapping[str, object], bearer_token: str) -> CallScope:
    """从已验签的 claims 取 ``uid``/``pid``；缺失或非法 → 403（不回显 claim 值）。"""

    try:
        user_id = uuid.UUID(str(claims["uid"]))
        project_id = uuid.UUID(str(claims["pid"]))
    except (KeyError, ValueError):
        raise AppError(
            ErrorCode.INVALID_REQUEST,
            "令牌缺少 uid/pid",
            status_code=403,
            retryable=False,
            details={"reason": "token_scope_claims_missing"},
        ) from None
    return CallScope(
        purpose=LeasePurpose.QUERY_PLAN,
        user_id=user_id,
        project_id=project_id,
        bearer_token=bearer_token,
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
    "QUERY_PLAN_PATH",
    "QueryPlanService",
    "get_query_plan_service",
    "router",
    "serialize_response",
]
