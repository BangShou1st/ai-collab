"""``POST /v1/knowledge/search`` 同步内部检索端点（P2 契约 §4.4、§9.2、§10，P2-R1/R2）。

- 入站令牌要求 scope ``knowledge:search``，且 ``uid/pid`` 必须与请求体 ``userId/projectId`` 一致；
- 空 ``visibleDocumentIds`` 直接返回 200 ``hits=[]``，不查数据库也不租借凭据；
- 严格执行 ``deadlineMs``（超时返回 504 ``SEARCH_DEADLINE_EXCEEDED``）；
- 严禁记录查询原文、chunk 正文或密钥，仅记 ``requestId/projectId/intent/strategy/hits/latencyMs``；
- 记录 §10 Prometheus 指标：``knowledge_search_latency_seconds``、``knowledge_search_hits``、
  ``knowledge_search_errors_total``（均带 ``caller_type="PYTHON"``）。
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Final

from fastapi import APIRouter, Request

from ai_collab_worker.api.auth import require_claims
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.knowledge_search import (
    CODE_UNAUTHORIZED,
    CODE_VALIDATION_ERROR,
    KnowledgeSearchRequest,
    SearchRejectedError,
    authorize_search,
    empty_response,
    parse_search_request,
    response_json,
)
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.observability.metrics import (
    KNOWLEDGE_SEARCH_ERRORS,
    KNOWLEDGE_SEARCH_HITS,
    KNOWLEDGE_SEARCH_LATENCY,
    increment_counter,
    observe_histogram,
)
from ai_collab_worker.retrieval.pipeline import (
    CODE_RETRIEVAL_DENSE_UNAVAILABLE,
    KnowledgeSearchPipeline,
)
from ai_collab_worker.retrieval.store import PostgresRetrievalStore

logger: Final[logging.Logger] = logging.getLogger(__name__)

router: Final[APIRouter] = APIRouter(tags=["knowledge-search"])

CALLER_TYPE: Final[str] = "PYTHON"
SCOPE_KNOWLEDGE_SEARCH: Final[str] = "knowledge:search"
CODE_SEARCH_DEADLINE_EXCEEDED: Final[str] = "SEARCH_DEADLINE_EXCEEDED"


def _resolve_pipeline(request: Request) -> KnowledgeSearchPipeline:
    override = getattr(request.app.state, "knowledge_search_pipeline", None)
    if override is not None:
        return override  # type: ignore[no-any-return]
    db = getattr(request.app.state, "index_db", None)
    kernel = getattr(request.app.state, "model_kernel", None)
    if db is None or not isinstance(kernel, ModelKernel):
        raise SearchRejectedError(
            503,
            CODE_RETRIEVAL_DENSE_UNAVAILABLE,
            "index_db_unconfigured",
            message="检索存储未配置",
            retryable=True,
        )
    pipeline = KnowledgeSearchPipeline(store=PostgresRetrievalStore(db), kernel=kernel)
    request.app.state.knowledge_search_pipeline = pipeline
    return pipeline


@router.post("/v1/knowledge/search")
async def search_knowledge(request: Request) -> dict[str, Any]:
    started = time.perf_counter()
    search_req: KnowledgeSearchRequest | None = None
    try:
        try:
            token, claims = require_claims(request, scope=SCOPE_KNOWLEDGE_SEARCH)
        except AppError as auth_err:
            reason = str((auth_err.details or {}).get("reason") or "unauthorized")
            raise SearchRejectedError(
                401,
                CODE_UNAUTHORIZED,
                reason,
                message="内部服务鉴权失败",
                retryable=False,
            ) from None

        try:
            raw_body: object = await request.json()
        except Exception:
            raise SearchRejectedError(
                400,
                CODE_VALIDATION_ERROR,
                "invalid_json",
                message="请求体必须是合法 JSON",
                retryable=False,
            ) from None

        search_req = parse_search_request(raw_body)
        authorize_search(claims, search_req)

        if not search_req.visible_document_ids:
            elapsed = time.perf_counter() - started
            resp = empty_response(search_req, latency_ms=max(0, int(elapsed * 1000)))
            observe_histogram(
                KNOWLEDGE_SEARCH_LATENCY,
                elapsed,
                intent=search_req.intent.value,
                strategy=search_req.strategy.value,
                caller_type=CALLER_TYPE,
            )
            observe_histogram(
                KNOWLEDGE_SEARCH_HITS,
                0,
                intent=search_req.intent.value,
                caller_type=CALLER_TYPE,
            )
            logger.info(
                "knowledge search ok request_id=%s project_id=%s intent=%s "
                "strategy=%s hits=0 latency_ms=%d",
                search_req.request_id,
                search_req.project_id,
                search_req.intent.value,
                search_req.strategy.value,
                resp.diagnostics.latency_ms,
            )
            return response_json(resp)

        pipeline = _resolve_pipeline(request)
        try:
            async with asyncio.timeout(search_req.deadline_ms / 1000.0):
                resp = await pipeline.execute(search_req, bearer_token=token)
        except TimeoutError:
            raise SearchRejectedError(
                504,
                CODE_SEARCH_DEADLINE_EXCEEDED,
                "deadline_exceeded",
                message="检索超过 deadline",
                retryable=False,
            ) from None

        elapsed = time.perf_counter() - started
        observe_histogram(
            KNOWLEDGE_SEARCH_LATENCY,
            elapsed,
            intent=search_req.intent.value,
            strategy=search_req.strategy.value,
            caller_type=CALLER_TYPE,
        )
        observe_histogram(
            KNOWLEDGE_SEARCH_HITS,
            len(resp.hits),
            intent=search_req.intent.value,
            caller_type=CALLER_TYPE,
        )
        logger.info(
            "knowledge search ok request_id=%s project_id=%s intent=%s "
            "strategy=%s hits=%d latency_ms=%d",
            search_req.request_id,
            search_req.project_id,
            search_req.intent.value,
            search_req.strategy.value,
            len(resp.hits),
            resp.diagnostics.latency_ms,
        )
        return response_json(resp)
    except SearchRejectedError as rejected:
        increment_counter(
            KNOWLEDGE_SEARCH_ERRORS,
            status=str(rejected.status),
            caller_type=CALLER_TYPE,
        )
        if search_req is not None:
            observe_histogram(
                KNOWLEDGE_SEARCH_LATENCY,
                time.perf_counter() - started,
                intent=search_req.intent.value,
                strategy=search_req.strategy.value,
                caller_type=CALLER_TYPE,
            )
        raise


__all__ = ["CODE_SEARCH_DEADLINE_EXCEEDED", "router"]
