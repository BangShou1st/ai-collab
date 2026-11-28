"""``POST /v1/knowledge/qa:stream`` 与 ``GET /v1/knowledge/qa/capabilities``（P3 §3.3/§3.5）。

- 入站令牌要求 scope ``knowledge:qa``，``uid/pid`` 必须与请求体一致；
- 空可见集合**仍然进入流**：管线走证据不足路径（不发检索租借、不调模型），
  finalize ``insufficientEvidence=true``（§3.3 语义）；
- SSE 输出：``text/event-stream``，事件序 ``token* → finalize``，错误 ``error`` 终止；
  响应头带 ``Cache-Control`` 与 ``X-Accel-Buffering: no``（对齐 Java 侧约定）；
- 请求级 deadline：超时向流内下发 ``error(QA_DEADLINE_EXCEEDED)`` 并结束；
- 严禁记录问题原文、回答正文或密钥，仅记 requestId/sessionId 与耗时。
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator
from typing import Annotated, Any, Final

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, StreamingResponse

from ai_collab_worker.api.auth import require_claims
from ai_collab_worker.api.query_plan import QueryPlanService
from ai_collab_worker.contract.knowledge_qa import (
    CODE_QA_DEADLINE_EXCEEDED,
    CODE_UNAUTHORIZED,
    CODE_VALIDATION_ERROR,
    QaRejectedError,
    authorize_qa,
    error_event,
    error_response,
    parse_qa_request,
)
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.observability.metrics import KNOWLEDGE_QA_DURATION, observe_histogram
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.qa.constants import (
    BLOCKED_ANSWER,
    CONTEXT_TOKEN_BUDGET,
    DEFAULT_MAX_SOURCES,
    INSUFFICIENT_ANSWER,
    K_GRAM,
    MAX_PROMPT_FILENAME_CODE_POINTS,
    MAX_PROMPT_HEADING_CODE_POINTS,
    MAX_PROMPT_SOURCE_CODE_POINTS,
    MAX_SOURCES_LIMIT,
    MIN_SIMILARITY,
    PER_DOCUMENT_SOURCE_CAP,
)
from ai_collab_worker.qa.pipeline import KnowledgeQaPipeline

logger: Final[logging.Logger] = logging.getLogger(__name__)

router: Final[APIRouter] = APIRouter(tags=["knowledge-qa"])

QA_STREAM_PATH: Final[str] = "/v1/knowledge/qa:stream"
QA_CAPABILITIES_PATH: Final[str] = "/v1/knowledge/qa/capabilities"
#: 请求级 deadline 上限（与 Java SseEmitter 300s 超时同口径）。
QA_STREAM_DEADLINE_MS_CAP: Final[int] = 300_000

_SSE_HEADERS: Final[dict[str, str]] = {
    "Cache-Control": "no-cache, no-transform",
    "X-Accel-Buffering": "no",
}


class KnowledgeQaService:
    """问答端点依赖的装配体：管线 + 计划增强开关。"""

    def __init__(
        self,
        *,
        kernel: ModelKernel,
        prompt_store: PromptStore,
        plan_service: QueryPlanService | None = None,
        plan_enabled: bool = True,
    ) -> None:
        self._kernel = kernel
        self._prompt_store = prompt_store
        self._plan_service = plan_service
        self._plan_enabled = plan_enabled

    def pipeline_for(self, request: Request) -> KnowledgeQaPipeline:
        search_pipeline = getattr(request.app.state, "knowledge_search_pipeline", None)
        return KnowledgeQaPipeline(
            kernel=self._kernel,
            prompt_store=self._prompt_store,
            search_pipeline=search_pipeline,
            plan_service=self._plan_service,
            plan_enabled=self._plan_enabled,
        )


def get_qa_service(request: Request) -> KnowledgeQaService:
    service = getattr(request.app.state, "knowledge_qa_service", None)
    if not isinstance(service, KnowledgeQaService):
        msg = "knowledge_qa_service 未配置"
        raise RuntimeError(msg)
    return service


QaServiceDep = Annotated[KnowledgeQaService, Depends(get_qa_service)]


@router.get(QA_CAPABILITIES_PATH, summary="问答能力开关与冻结常量（只读对账）")
async def qa_capabilities(_service: QaServiceDep) -> dict[str, Any]:
    """返回冻结常量与能力开关（幂等只读，供 Java 健康检查与 §8 对账）。"""

    return {
        "schemaVersion": 1,
        "stream": True,
        "eventSequence": ["token", "finalize", "error"],
        "constants": {
            "minSimilarity": MIN_SIMILARITY,
            "defaultMaxSources": DEFAULT_MAX_SOURCES,
            "maxSourcesLimit": MAX_SOURCES_LIMIT,
            "perDocumentSourceCap": PER_DOCUMENT_SOURCE_CAP,
            "contextTokenBudget": CONTEXT_TOKEN_BUDGET,
            "kGram": K_GRAM,
            "maxPromptSourceCodePoints": MAX_PROMPT_SOURCE_CODE_POINTS,
            "maxPromptFilenameCodePoints": MAX_PROMPT_FILENAME_CODE_POINTS,
            "maxPromptHeadingCodePoints": MAX_PROMPT_HEADING_CODE_POINTS,
        },
        "texts": {
            "insufficientAnswer": INSUFFICIENT_ANSWER,
            "blockedAnswer": BLOCKED_ANSWER,
        },
    }


@router.post(QA_STREAM_PATH, summary="知识问答流式生成（SSE：token* → finalize | error）")
async def stream_knowledge_qa(request: Request, service: QaServiceDep) -> StreamingResponse:
    started = time.perf_counter()
    # 1. 鉴权（流建立前失败 → 统一错误信封 401）
    try:
        token, claims = require_claims(request, scope="knowledge:qa")
    except Exception:
        logger.warning("knowledge qa unauthorized")
        return JSONResponse(
            status_code=401,
            content=error_response(CODE_UNAUTHORIZED, "内部服务鉴权失败", retryable=False),
        )

    # 2. 请求校验与身份一致性（流建立前失败 → 400/403）
    try:
        raw_body: object = await request.json()
        qa_request = parse_qa_request(raw_body)
        authorize_qa(claims, qa_request)
    except QaRejectedError as rejected:
        return JSONResponse(
            status_code=rejected.status,
            content=error_response(
                rejected.code,
                rejected.message or rejected.code,
                retryable=rejected.retryable,
                reason=rejected.reason,
            ),
        )

    outcome = "ok"
    pipeline = service.pipeline_for(request)
    deadline_seconds = min(qa_request.deadline_ms, QA_STREAM_DEADLINE_MS_CAP) / 1000.0

    async def event_stream() -> AsyncIterator[bytes]:
        nonlocal outcome
        try:
            async with asyncio.timeout(deadline_seconds):
                async for event in pipeline.stream_events(qa_request, bearer_token=token):
                    yield event
        except TimeoutError:
            outcome = "deadline"
            yield error_event(CODE_QA_DEADLINE_EXCEEDED, "问答超过 deadline")
        except asyncio.CancelledError:
            outcome = "canceled"
            raise

    async def with_done() -> AsyncIterator[bytes]:
        try:
            async for item in event_stream():
                yield item
        finally:
            elapsed = time.perf_counter() - started
            observe_histogram(KNOWLEDGE_QA_DURATION, elapsed, outcome=outcome, caller_type="PYTHON")
            logger.info(
                "knowledge qa stream request_id=%s session_id=%s outcome=%s elapsed_ms=%d",
                qa_request.request_id,
                qa_request.session_id,
                outcome,
                int(elapsed * 1000),
            )

    return StreamingResponse(
        with_done(),
        media_type="text/event-stream",
        headers=dict(_SSE_HEADERS),
    )


_ = CODE_VALIDATION_ERROR

__all__ = [
    "QA_CAPABILITIES_PATH",
    "QA_STREAM_PATH",
    "KnowledgeQaService",
    "get_qa_service",
    "router",
]
