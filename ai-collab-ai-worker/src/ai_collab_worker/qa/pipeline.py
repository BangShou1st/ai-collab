"""知识问答管线（P3 契约 §2.1、§3.3）：消歧 → 路由 → 检索 → 装配 → 生成 → 预检 → finalize。

流程与 Java ``KnowledgeQuestionApplicationService`` / ``KnowledgeStreamQuestionService``
编排体逐语义对齐：

1. 多轮消歧（``QueryUnderstandingService`` 移植）：输入是 Java 下发的 history 快照，
   预算/窗口/改写全部在 worker 侧按现规则执行；
2. 路由（``QueryRouter`` 移植）：COMPLEX 分支可选进程内调用 query plan（Q3=a），
   采纳/回退语义与 Java 五重前置检查等价；
3. 检索：**进程内直调** ``KnowledgeSearchPipeline``（Q3=a）；空 ``visibleDocumentIds``
   直接走证据不足路径，不发检索租借（P2 §4.4 语义）；引用身份由 hits 直接携带；
4. 证据装配（``KnowledgeContextBuilder`` 移植）：5 源 / 2500 token / 单文档 2 名额；
   装配后无来源 → 统一拒答文案 finalize（``model=None``），不调模型；
5. 提示词装配（Q12=a）：``knowledge.system`` 注入北京时间行、``knowledge.user``
   渲染转义后的问题与证据块、会话上下文前缀；
6. 流式生成 + k-gram 泄漏预检（Q9=a）：发送侧留 ``(K_GRAM-1)`` 尾码点缓冲；命中
   指纹立即中止（``error(CONTENT_BLOCKED)``）；全文终检在流完成后执行，**通过才**
   补发尾段缓冲并 finalize——因此 ``finalize.answer`` 与 token 拼接逐字节一致
   （§8.2.5 不变式成立），泄漏内容也绝不比旧实现多发出一个码点；
7. finalize：引用身份（P2 §6）、retrieval/prompt/model 回执。Java 落库复核后才向
   客户端发 citations/done。

断连/取消：消费方断开时事件任务被取消，内核按 CANCELED 上报用量，**不落库**
（对齐 §1.3"不持久化未完成的交换"，由 Java 中继保证）。
"""

from __future__ import annotations

import asyncio
import datetime as dt
import logging
import uuid
from collections.abc import AsyncIterator
from typing import Final

from ai_collab_worker.api.query_plan import QueryPlanService
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.knowledge_qa import (
    CODE_AI_PROVIDER_UNAVAILABLE,
    CODE_CONTENT_BLOCKED,
    CODE_QA_DEADLINE_EXCEEDED,
    SEARCH_DEADLINE_MS,
    CitationIdentity,
    KnowledgeQaRequest,
    ModelTrace,
    PromptIdentity,
    RetrievalTrace,
    error_event,
    finalize_event,
    token_event,
)
from ai_collab_worker.contract.knowledge_search import (
    HeadingFilter,
    KnowledgeSearchRequest,
    QueryRole,
    SearchHit,
    SearchIntent,
    SearchQuery,
)
from ai_collab_worker.models.kernel import CallScope, ModelKernel, StreamAbortedError
from ai_collab_worker.models.lease import LeasePurpose
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.qa.assembly import (
    CandidateHit,
    KnowledgeContext,
    KnowledgeSource,
    build_with_token_budget,
)
from ai_collab_worker.qa.citation import KnowledgeCitationValidator
from ai_collab_worker.qa.constants import (
    CONTEXT_TOKEN_BUDGET,
    DEFAULT_MAX_SOURCES,
    INSUFFICIENT_ANSWER,
    K_GRAM,
    UNDERSTANDING_CONTEXT_CHAR_BUDGET,
    UNDERSTANDING_MAX_TURNS,
)
from ai_collab_worker.qa.prompt_text import escape_xml_text
from ai_collab_worker.qa.safety import (
    contains_any_fingerprint,
    fingerprint_set,
)
from ai_collab_worker.qa.safety import (
    inspect as full_inspect,
)
from ai_collab_worker.qa.time_context import beijing_time_context
from ai_collab_worker.retrieval.pipeline import KnowledgeSearchPipeline
from ai_collab_worker.retrieval.query.models import (
    ConversationMessage,
    QueryPlanDateRange,
    QueryPlanFilters,
    QueryPlanOutcome,
    QueryRouteDecision,
    RetrievalStrategy,
)
from ai_collab_worker.retrieval.query.routing import QueryRouter
from ai_collab_worker.retrieval.query.understanding import QueryUnderstandingService

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 引用 quote 截断（Java ``insertCitation`` 截 1000 code points 同口径）。
QUOTE_MAX_CODE_POINTS: Final[int] = 1000

KNOWLEDGE_SYSTEM_KEY: Final[str] = "knowledge.system"
KNOWLEDGE_USER_KEY: Final[str] = "knowledge.user"

#: 进程内检索候选池（Java ``KnowledgeSearchClient.candidatePool(8)`` = max(20, 2*8)）。
QA_CANDIDATE_POOL: Final[int] = 20


def map_app_error_code(code: ErrorCode) -> str:
    """worker 冻结错误码 → P3 契约 §3.3 的 wire 码。"""

    if code in (
        ErrorCode.MODEL_UNAVAILABLE,
        ErrorCode.MODEL_RATE_LIMITED,
        ErrorCode.PROMPT_UNAVAILABLE,
        ErrorCode.BUDGET_EXCEEDED,
    ):
        return CODE_AI_PROVIDER_UNAVAILABLE
    if code is ErrorCode.DEADLINE_EXCEEDED:
        return CODE_QA_DEADLINE_EXCEEDED
    if code is ErrorCode.CONTENT_BLOCKED:
        return CODE_CONTENT_BLOCKED
    return code.value


class QaRejected(Exception):  # noqa: N818 - 与 SearchRejectedError 命名风格一致
    """流建立前的拒绝（端点渲染成统一错误信封）。"""

    def __init__(
        self,
        status: int,
        code: str,
        reason: str,
        *,
        message: str | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(f"{status} {code} reason={reason}")
        self.status = status
        self.code = code
        self.reason = reason
        self.message = message
        self.retryable = retryable


class KnowledgeQaPipeline:
    """问答编排：``stream_events`` 产出 SSE 事件字节流（token* → finalize；error 终止）。"""

    def __init__(
        self,
        *,
        kernel: ModelKernel,
        prompt_store: PromptStore,
        search_pipeline: KnowledgeSearchPipeline | None,
        plan_service: QueryPlanService | None,
        plan_enabled: bool = True,
    ) -> None:
        self._kernel = kernel
        self._prompt_store = prompt_store
        self._search_pipeline = search_pipeline
        self._plan_service = plan_service
        self._plan_enabled = plan_enabled
        self._understanding = QueryUnderstandingService(
            UNDERSTANDING_MAX_TURNS, UNDERSTANDING_CONTEXT_CHAR_BUDGET
        )
        self._validator = KnowledgeCitationValidator()

    async def stream_events(
        self, request: KnowledgeQaRequest, *, bearer_token: str
    ) -> AsyncIterator[bytes]:
        """产出事件流；内部任务失败渲染成 ``error`` 事件（终止性），断连即整体取消。"""

        queue: asyncio.Queue[bytes | None] = asyncio.Queue()

        async def run() -> None:
            try:
                await self._run(request, bearer_token=bearer_token, emit=queue)
            except StreamAbortedError:
                queue.put_nowait(
                    error_event(
                        CODE_CONTENT_BLOCKED, "回答包含不应披露的内部信息，已被安全策略拦截"
                    )
                )
            except QaRejected as rejected:
                queue.put_nowait(error_event(rejected.code, rejected.message or rejected.code))
            except AppError as exc:
                queue.put_nowait(error_event(map_app_error_code(exc.code), exc.message))
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("knowledge qa pipeline failed unexpectedly")
                queue.put_nowait(error_event("INTERNAL_ERROR", "内部错误"))
            finally:
                queue.put_nowait(None)

        task = asyncio.create_task(run())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                yield item
        finally:
            task.cancel()

    # ---- 主管线（在独立任务中执行） -------------------------------------------

    async def _run(
        self,
        request: KnowledgeQaRequest,
        *,
        bearer_token: str,
        emit: asyncio.Queue[bytes],
    ) -> None:
        # 1. 多轮消歧（history 由 Java 快照下发，预算/窗口在此执行）
        understanding = self._understanding.understand(_history_messages(request), request.question)
        retrieval_query = understanding.rewritten_query or request.question
        prompt_context = understanding.context_prompt

        # 2. 路由（COMPLEX 可选进程内 query plan 增强，采纳/回退与 Java 等价）
        router = QueryRouter(
            plan_enhancer=self._plan_enhancer(bearer_token),
            plan_enabled=self._plan_enabled,
        )
        decision = await router.route(
            retrieval_query,
            tuple(str(item) for item in request.document_ids),
            request.user_id,
            request.project_id,
        )
        structured = decision.structured_query
        effective_query = (
            structured.semantic_query
            if structured.semantic_query and structured.semantic_query.strip()
            else retrieval_query
        )
        retrieval_trace = RetrievalTrace(
            strategy=decision.strategy.value,
            effective_query=effective_query,
            route_reason=decision.reason,
            sub_queries=tuple(decision.sub_queries),
        )

        # 3. 检索（空可见集合 → 证据不足，不租借不访问数据库）
        context: KnowledgeContext | None = None
        if request.visible_document_ids:
            hits = await self._retrieve(request, decision, effective_query, bearer_token)
            candidates = [_candidate_of(hit, request.document_names) for hit in hits]
            built = build_with_token_budget(candidates, DEFAULT_MAX_SOURCES, CONTEXT_TOKEN_BUDGET)
            context = built.context

        if context is None or not context.sources:
            await emit.put(
                finalize_event(
                    request.request_id,
                    INSUFFICIENT_ANSWER,
                    insufficient_evidence=True,
                    citations=(),
                    retrieval=retrieval_trace,
                    prompt=None,
                    model=None,
                )
            )
            return

        # 4. 提示词装配（Q12=a：模板与装配都在 worker）
        system_doc = load_current(self._prompt_store, KNOWLEDGE_SYSTEM_KEY)
        user_doc = load_current(self._prompt_store, KNOWLEDGE_USER_KEY)
        system_prompt = render_template(system_doc.text, {"timeContext": beijing_time_context()})
        user_payload = render_template(
            user_doc.text,
            {"question": escape_xml_text(request.question), "sources": context.prompt_sources},
        )
        if prompt_context and prompt_context.strip():
            user_payload = prompt_context.strip() + "\n\n" + user_payload
        prompt_identity = PromptIdentity(key=system_doc.key, version=system_doc.version)

        # 5. 流式生成 + k-gram 泄漏预检（发送侧缓冲，Q9=a）
        fingerprints = fingerprint_set(system_prompt)
        buffer: list[str] = []

        async def on_delta(text: str) -> None:
            buffer.append(text)
            buffered = "".join(buffer)
            safe_count = max(0, len(buffered) - (K_GRAM - 1))
            flush_text = buffered[:safe_count]
            tail = buffered[safe_count:]
            buffer.clear()
            buffer.append(tail)
            if flush_text and contains_any_fingerprint(flush_text, fingerprints):
                raise StreamAbortedError("system prompt leakage detected")
            if flush_text:
                await emit.put(token_event(flush_text))

        scope = CallScope(
            purpose=LeasePurpose.KNOWLEDGE_CHAT,
            user_id=request.user_id,
            project_id=request.project_id,
            bearer_token=bearer_token,
        )
        completion = await self._kernel.complete_stream(
            scope,
            system_prompt=system_prompt,
            user_prompt=user_payload,
            on_delta=on_delta,
        )
        answer = completion.text

        # 6. 全文终检：通过才补发尾段缓冲并 finalize（保证 answer == token 拼接）
        safety = full_inspect(system_prompt, answer)
        if safety.leaked:
            logger.warning(
                "knowledge qa blocked system prompt leakage call_id=%s matchedSampleLen=%d",
                completion.call_id,
                len(safety.matched_sample),
            )
            raise StreamAbortedError("system prompt leakage detected")

        tail = "".join(buffer)
        buffer.clear()
        if tail:
            await emit.put(token_event(tail))

        # 7. 引用校验（[Sn] 对照 / 拒答判定）与 finalize
        validated = self._validator.validate(answer, list(context.sources), request.project_id)
        citations = tuple(_citation_of(source) for source in validated.cited_sources)
        model_trace = ModelTrace(
            provider=completion.provider,
            name=completion.model,
            latency_ms=completion.latency_ms,
            prompt_tokens=completion.input_tokens,
            completion_tokens=completion.output_tokens,
        )
        await emit.put(
            finalize_event(
                request.request_id,
                answer,
                insufficient_evidence=validated.insufficient_evidence,
                citations=citations,
                retrieval=retrieval_trace,
                prompt=prompt_identity,
                model=model_trace,
            )
        )

    async def _retrieve(
        self,
        request: KnowledgeQaRequest,
        decision: QueryRouteDecision,
        effective_query: str,
        bearer_token: str,
    ) -> tuple[SearchHit, ...]:
        if self._search_pipeline is None:
            raise QaRejected(
                503,
                CODE_AI_PROVIDER_UNAVAILABLE,
                "retrieval_unconfigured",
                message="检索存储未配置",
                retryable=True,
            )
        search_request = KnowledgeSearchRequest(
            request_id=request.request_id,
            project_id=request.project_id,
            user_id=request.user_id,
            intent=SearchIntent.KNOWLEDGE_QA,
            strategy=decision.strategy.value,
            queries=_outbound_queries(decision, effective_query),
            original_query=request.question,
            visible_document_ids=request.visible_document_ids,
            top_k=request.top_k,
            candidate_pool=QA_CANDIDATE_POOL,
            heading_filter=_heading_filter_of(decision),
            rerank=request.rerank,
            deadline_ms=SEARCH_DEADLINE_MS,
        )
        try:
            async with asyncio.timeout(SEARCH_DEADLINE_MS / 1000.0):
                response = await self._search_pipeline.execute(
                    search_request, bearer_token=bearer_token
                )
        except TimeoutError:
            raise QaRejected(
                504,
                CODE_QA_DEADLINE_EXCEEDED,
                "search_deadline_exceeded",
                message="检索超过 deadline",
                retryable=False,
            ) from None
        return response.hits

    def _plan_enhancer(self, bearer_token: str):
        """COMPLEX 分支的进程内 query plan 增强（Q3=a）：直接调 QueryPlanService。"""

        plan_service = self._plan_service
        if plan_service is None or not self._plan_enabled:
            return None

        async def enhance(
            semantic_query: str,
            document_ids: tuple[str, ...],
            user_id: uuid.UUID,
            project_id: uuid.UUID,
        ) -> QueryPlanOutcome | None:
            from ai_collab_worker.contract.query_plan import QueryPlanRequest

            body = QueryPlanRequest(
                query=semantic_query,
                locale="zh-CN",
                history_summary="",
                document_ids=tuple(document_ids),
            )
            scope = CallScope(
                purpose=LeasePurpose.QUERY_PLAN,
                user_id=user_id,
                project_id=project_id,
                bearer_token=bearer_token,
            )
            response, _unknown, _call_id = await plan_service.plan(body, scope=scope)
            plan_filters = response.filters
            date_range = plan_filters.date_range
            return QueryPlanOutcome(
                rewritten_query=response.rewritten_query,
                sub_queries=tuple(response.sub_queries),
                filters=QueryPlanFilters(
                    document_ids=tuple(plan_filters.document_ids),
                    doc_types=tuple(plan_filters.doc_types),
                    date_range=QueryPlanDateRange(
                        from_value=date_range.from_ if date_range else None,
                        to_value=date_range.to if date_range else None,
                    ),
                ),
                confidence=response.confidence,
            )

        return enhance


# --------------------------------------------------------------------------- #
# 检索请求适配（与 Java ``RemoteProjectKnowledgeSearch`` 的 outbound 构造逐条对齐）
# --------------------------------------------------------------------------- #


def _outbound_queries(
    decision: QueryRouteDecision, effective_query: str
) -> tuple[SearchQuery, ...]:
    """主查询 + MULTI_QUERY_HYBRID 的子查询（去重、至多 5 条）。"""

    queries: list[SearchQuery] = [SearchQuery(text=effective_query, role=QueryRole.PRIMARY)]
    if decision.strategy is RetrievalStrategy.MULTI_QUERY_HYBRID:
        texts: dict[str, None] = {effective_query: None}
        for subquery in decision.sub_queries:
            if subquery and subquery.strip() and subquery.strip() not in texts:
                texts[subquery.strip()] = None
                queries.append(SearchQuery(text=subquery.strip(), role=QueryRole.SUB))
            if len(queries) == 5:
                break
    return tuple(queries)


def _heading_filter_of(decision: QueryRouteDecision) -> HeadingFilter:
    """sectionHeading 过滤 → 检索请求的标题过滤（EQ→exact、CONTAINS→contains，后者覆盖）。"""

    exact: str | None = None
    contains: str | None = None
    for item in decision.structured_query.filters:
        if item.field != "sectionHeading":
            continue
        value = item.value
        if not isinstance(value, str):
            continue
        if item.operator.value == "EQ":
            exact = value.strip()
        elif item.operator.value == "CONTAINS":
            contains = value.strip()
    return HeadingFilter(exact=exact, contains=contains)


def _candidate_of(hit: SearchHit, document_names: dict[str, str]) -> CandidateHit:
    """检索 hit → 装配候选（metadata 形状与 Java ``toSearchHit`` 对齐）。"""

    metadata: dict[str, object] = {
        "chunkNo": hit.chunk_no,
        "bodySnapshotId": uuid.UUID(str(hit.body_snapshot_id)),
    }
    if hit.source_ref.page_number is not None:
        metadata["pageNumber"] = hit.source_ref.page_number
    metadata["chunkType"] = hit.chunk_type.value
    if hit.parent_chunk_no is not None:
        metadata["parentChunkNo"] = hit.parent_chunk_no
    if hit.context is not None and hit.context.text:
        metadata["expandedContent"] = hit.context.text
    row_id = uuid.uuid5(uuid.NAMESPACE_URL, f"hit:{hit.document_id}:{hit.chunk_no}")
    filename = document_names.get(str(hit.document_id), "")
    return CandidateHit(
        id=row_id,
        document_id=hit.document_id,
        original_filename=filename,
        heading=hit.heading or "",
        content=hit.content,
        content_hash=hit.content_hash,
        similarity=hit.scores.final,
        metadata=metadata,
    )


def _citation_of(source: KnowledgeSource) -> CitationIdentity:
    quote = source.content[:QUOTE_MAX_CODE_POINTS]
    return CitationIdentity(
        document_id=source.document_id,
        chunk_no=source.chunk_no if source.chunk_no is not None else 0,
        content_hash=source.content_hash,
        body_snapshot_id=source.body_snapshot_id,
        rank=source.rank,
        similarity=source.similarity,
        quote=quote,
    )


def _history_messages(request: KnowledgeQaRequest) -> list[ConversationMessage]:
    messages: list[ConversationMessage] = []
    for entry in request.history:
        created: dt.datetime | None = None
        if entry.created_at:
            try:
                created = dt.datetime.fromisoformat(entry.created_at.replace("Z", "+00:00"))
            except ValueError:
                created = None
        messages.append(
            ConversationMessage(
                role=entry.role.value,
                content=entry.content,
                created_at=created,
                insufficient_evidence=entry.insufficient_evidence,
            )
        )
    return messages


__all__ = ["KnowledgeQaPipeline", "QaRejected", "map_app_error_code"]
