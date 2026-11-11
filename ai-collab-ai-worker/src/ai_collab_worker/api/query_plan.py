"""``POST /v1/rag/plan``：查询规划（设计 §7.4，W4 冻结文档 §1）。

端到端流程：

1. 服务令牌鉴权（与 rerank/decide 共用 ``api/auth.py``，支持静态令牌或 Ed25519 短令牌）；
2. 在 ``asyncio.timeout(min(请求 deadlineMs, 服务端上限))`` 内从 PromptStore 取
   ``query_plan.system``/``query_plan.user``、调用 Java 网关、解析模型输出；
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
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.api.auth import require_token
from ai_collab_worker.clients.java_gateway import JavaGatewayClient
from ai_collab_worker.cognition.query.parser import parse_query_plan
from ai_collab_worker.cognition.query.prompt import build_query_plan_prompt
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.query_plan import QueryPlanRequest, QueryPlanResponse
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
    """query plan 的业务编排：Prompt → 网关 → 解析 → 契约响应。"""

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

    def effective_deadline_ms(self, body: QueryPlanRequest) -> int:
        """请求 deadline 与服务端上限取小；服务端永不放大调用方预算。"""

        return min(body.deadline_ms, self._settings.query_plan_deadline_ms)

    async def plan(
        self,
        body: QueryPlanRequest,
        *,
        call_id: str,
        traceparent: str | None,
        deadline_ms: int,
        bearer_token: str | None = None,
    ) -> tuple[QueryPlanResponse, tuple[str, ...]]:
        """调用网关并返回契约响应与被丢弃的编造文档 id。"""

        prompt = build_query_plan_prompt(self._prompt_store, body)
        result = await self._gateway.call_model(
            system_prompt=prompt.system,
            user_prompt=prompt.user,
            deadline_ms=deadline_ms,
            call_id=call_id,
            traceparent=traceparent,
            bearer_token=bearer_token,
        )
        parsed = parse_query_plan(result.content, allowed_document_ids=body.document_ids)
        return parsed.response, parsed.unknown_document_ids


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
    summary="把用户问题改写成检索查询与过滤条件（经 Java 内部模型网关）",
)
async def query_plan(
    request: Request, body: QueryPlanRequest, service: QueryPlanServiceDep
) -> Response:
    """处理一次 query plan 请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    call_id = _call_id()
    outcome: str = OUTCOME_MODEL_ERROR
    try:
        bearer_token = require_token(request, scope="plan", audience="ai-worker")
        deadline_ms = service.effective_deadline_ms(body)
        try:
            async with asyncio.timeout(deadline_ms / 1000.0):
                response, unknown_ids = await service.plan(
                    body,
                    call_id=call_id,
                    traceparent=request.headers.get("traceparent"),
                    deadline_ms=deadline_ms,
                    bearer_token=bearer_token,
                )
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


def _call_id() -> str:
    """每次模型调用唯一；不含请求正文，可安全进日志。"""

    return f"query-plan:{uuid.uuid4().hex}"


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
