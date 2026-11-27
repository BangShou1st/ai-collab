"""P2 同步检索管线执行器（契约 §4.4、§5.1、§9.2，语义报告 §1–§3，R2-1–R2-5）。

严格落实中心 agent 裁决：
- 4 种 ``RetrievalStrategy`` 一一对应：``DENSE``、``HYBRID``、``MULTI_QUERY_HYBRID``、
  ``DENSE_WITH_BOUNDED_FALLBACK``；
- **零静默降级（R2-1）**：Dense（含向量化与租借）失败 → 503 ``RETRIEVAL_DENSE_UNAVAILABLE``；
  Lexical 失败 → 503 ``RETRIEVAL_LEXICAL_UNAVAILABLE``；重排失败（启发式或 ``CONFIGURED``）
  → 503 ``RERANK_UNAVAILABLE``；模型与 ACTIVE generation 不一致或无 ACTIVE generation
  → 409 ``INDEX_GENERATION_UNAVAILABLE``；
- ``CONFIGURED`` 重排对前 ``min(n, params.maxCandidates)`` 重排、其余按融合顺序接后，不回退；
- ``scores.final`` 恒为 ``max(dense, lexical)``，只表示准入分，绝不混入 ``rrf`` 或 ``rerank``。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from dataclasses import dataclass
from functools import cmp_to_key
from typing import Any, Final

from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.knowledge_search import (
    ChunkRef,
    ChunkType,
    HitContext,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse,
    RerankMode,
    RetrievalStrategy,
    SearchDiagnostics,
    SearchHit,
    SearchRejectedError,
    SearchScores,
    SourceRef,
    admission_score,
    empty_response,
    enforce_visibility,
)
from ai_collab_worker.index.db import VECTOR_DIMENSION
from ai_collab_worker.models.factory import (
    EmbeddingDimensionMismatchError,
    EmbeddingTaskType,
    RerankDocument,
)
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import Lease, LeasePurpose
from ai_collab_worker.retrieval.candidate import (
    RERANKER_CONFIGURED,
    Candidate,
    truncate,
)
from ai_collab_worker.retrieval.expand import expand_parent_child, expand_sentence_windows
from ai_collab_worker.retrieval.fusion import distinct_queries, merge_multi_query, rrf_fuse
from ai_collab_worker.retrieval.heuristic import heuristic_rerank, pass_through, rerank_order
from ai_collab_worker.retrieval.javatext import is_blank
from ai_collab_worker.retrieval.probe import has_usable_evidence
from ai_collab_worker.retrieval.store import (
    ActiveGeneration,
    RetrievalStorageError,
    RetrievalStore,
)

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: P2-R2 R2-2：Dense-only（``DENSE`` 及 ``DENSE_WITH_BOUNDED_FALLBACK`` 第一步）固定取前 20。
DENSE_ONLY_TOP_K: Final[int] = 20

CODE_INDEX_GENERATION_UNAVAILABLE: Final[str] = "INDEX_GENERATION_UNAVAILABLE"
CODE_RETRIEVAL_DENSE_UNAVAILABLE: Final[str] = "RETRIEVAL_DENSE_UNAVAILABLE"
CODE_RETRIEVAL_LEXICAL_UNAVAILABLE: Final[str] = "RETRIEVAL_LEXICAL_UNAVAILABLE"
CODE_RERANK_UNAVAILABLE: Final[str] = "RERANK_UNAVAILABLE"


@dataclass(frozen=True)
class _PipelineRunResult:
    candidates: list[Candidate]
    dense_candidates: int
    lexical_candidates: int
    include_rrf: bool


class KnowledgeSearchPipeline:
    """``POST /v1/knowledge/search`` 的核心异步检索执行器。"""

    def __init__(self, *, store: RetrievalStore, kernel: ModelKernel) -> None:
        self._store: Final[RetrievalStore] = store
        self._kernel: Final[ModelKernel] = kernel

    async def execute(
        self,
        request: KnowledgeSearchRequest,
        *,
        bearer_token: str,
    ) -> KnowledgeSearchResponse:
        started = time.perf_counter()
        if not request.visible_document_ids or is_blank(request.primary_query):
            elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
            return empty_response(request, latency_ms=elapsed_ms)

        try:
            active_gen = await self._store.get_active_generation()
        except RetrievalStorageError:
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_DENSE_UNAVAILABLE,
                "active_generation_lookup_failed",
                message="索引存储暂不可用",
                retryable=True,
            ) from None
        if active_gen is None:
            raise SearchRejectedError(
                409,
                CODE_INDEX_GENERATION_UNAVAILABLE,
                "no_active_generation",
                message="当前没有可用的 ACTIVE generation",
                retryable=False,
            )

        if request.strategy is RetrievalStrategy.DENSE:
            run = await self._run_dense_only(
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            iterative_scan = True
        elif request.strategy is RetrievalStrategy.HYBRID:
            run = await self._run_hybrid(
                query=request.primary_query,
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            iterative_scan = True
        elif request.strategy is RetrievalStrategy.MULTI_QUERY_HYBRID:
            run = await self._run_multi_query_hybrid(
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            iterative_scan = True
        elif request.strategy is RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK:
            run = await self._run_dense_with_bounded_fallback(
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            iterative_scan = True
        else:  # pragma: no cover - parse_search_request 已拦截未知 strategy
            raise SearchRejectedError(400, "VALIDATION_ERROR", "unknown_strategy")

        hits = tuple(
            _to_search_hit(index, cand, include_rrf=run.include_rrf)
            for index, cand in enumerate(run.candidates, start=1)
        )
        verified_hits, dropped = enforce_visibility(hits, request.visible_document_ids)
        if dropped > 0:
            logger.warning(
                "defense in depth dropped out-of-scope hits request_id=%s dropped=%d",
                request.request_id,
                dropped,
            )

        latency_ms = max(0, int((time.perf_counter() - started) * 1000))
        return KnowledgeSearchResponse(
            request_id=request.request_id,
            generation_id=active_gen.generation_id,
            strategy=request.strategy,
            hits=verified_hits,
            diagnostics=SearchDiagnostics(
                dense_candidates=run.dense_candidates,
                lexical_candidates=run.lexical_candidates,
                iterative_scan=iterative_scan,
                rerank=request.rerank,
                latency_ms=latency_ms,
            ),
        )

    async def _embed_queries(
        self,
        *,
        queries: Sequence[str],
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        bearer_token: str,
    ) -> tuple[tuple[float, ...], ...]:
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=request.user_id,
            project_id=request.project_id,
            bearer_token=bearer_token,
        )

        def _check_model(lease: Lease) -> None:
            if lease.model != active_gen.embedding_model:
                raise SearchRejectedError(
                    409,
                    CODE_INDEX_GENERATION_UNAVAILABLE,
                    "embedding_model_mismatch",
                    message="查询向量模型与 ACTIVE generation 不一致",
                    retryable=False,
                )

        try:
            result = await self._kernel.embed_texts(
                scope,
                queries,
                task_type=EmbeddingTaskType.QUERY,
                expected_dimension=VECTOR_DIMENSION,
                on_lease=_check_model,
            )
        except SearchRejectedError:
            raise
        except EmbeddingDimensionMismatchError:
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_DENSE_UNAVAILABLE,
                "embedding_dimension_mismatch",
                message="查询向量维度与索引列维度不符",
                retryable=False,
            ) from None
        except AppError as exc:
            reason = str((exc.details or {}).get("reason") or "dense_embedding_failed")
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_DENSE_UNAVAILABLE,
                reason,
                message="查询向量化服务暂不可用",
                retryable=exc.retryable,
            ) from None
        return result.vectors

    async def _search_dense(
        self,
        *,
        vector: Sequence[float],
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        top_k: int,
    ) -> list[Candidate]:
        try:
            return await self._store.search_dense(
                generation_id=active_gen.generation_id,
                project_id=request.project_id,
                query_vector=vector,
                document_ids=request.visible_document_ids,
                top_k=top_k,
                heading_filter=request.heading_filter,
            )
        except RetrievalStorageError:
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_DENSE_UNAVAILABLE,
                "dense_storage_unavailable",
                message="向量检索数据库暂不可用",
                retryable=True,
            ) from None

    async def _search_lexical(
        self,
        *,
        query: str,
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        top_k: int,
    ) -> list[Candidate]:
        try:
            return await self._store.search_lexical(
                generation_id=active_gen.generation_id,
                project_id=request.project_id,
                query=query,
                document_ids=request.visible_document_ids,
                top_k=top_k,
                heading_filter=request.heading_filter,
            )
        except RetrievalStorageError:
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_LEXICAL_UNAVAILABLE,
                "lexical_storage_unavailable",
                message="词法检索数据库暂不可用",
                retryable=True,
            ) from None

    async def _expand_candidates(
        self,
        *,
        candidates: list[Candidate],
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
    ) -> list[Candidate]:
        result = candidates
        try:
            if request.expand.sentence_window and result:
                result = await expand_sentence_windows(
                    store=self._store,
                    generation_id=active_gen.generation_id,
                    project_id=request.project_id,
                    candidates=result,
                )
            if request.expand.parent and result:
                result = await expand_parent_child(
                    store=self._store,
                    generation_id=active_gen.generation_id,
                    project_id=request.project_id,
                    candidates=result,
                )
        except RetrievalStorageError:
            raise SearchRejectedError(
                503,
                CODE_RETRIEVAL_DENSE_UNAVAILABLE,
                "expansion_storage_unavailable",
                message="上下文扩展读取数据库暂不可用",
                retryable=True,
            ) from None
        return result

    async def _rerank(
        self,
        *,
        query: str,
        candidates: Sequence[Candidate],
        top_n: int,
        request: KnowledgeSearchRequest,
        bearer_token: str,
    ) -> list[Candidate]:
        if not candidates:
            return []
        if request.rerank is RerankMode.HEURISTIC:
            try:
                return heuristic_rerank(query, candidates, top_n)
            except Exception:
                raise SearchRejectedError(
                    503,
                    CODE_RERANK_UNAVAILABLE,
                    "heuristic_rerank_failed",
                    message="启发式重排失败",
                    retryable=True,
                ) from None

        scope = CallScope(
            purpose=LeasePurpose.RERANK,
            user_id=request.user_id,
            project_id=request.project_id,
            bearer_token=bearer_token,
        )
        docs = [
            RerankDocument(heading=cand.heading, text=cand.effective_content) for cand in candidates
        ]
        try:
            reranked = await self._kernel.rerank(scope, query=query, documents=docs)
        except AppError as exc:
            reason = str((exc.details or {}).get("reason") or "configured_rerank_failed")
            raise SearchRejectedError(
                503,
                CODE_RERANK_UNAVAILABLE,
                reason,
                message="重排服务暂不可用",
                retryable=exc.retryable,
            ) from None
        except Exception:
            raise SearchRejectedError(
                503,
                CODE_RERANK_UNAVAILABLE,
                "configured_rerank_failed",
                message="重排服务暂不可用",
                retryable=True,
            ) from None

        scored_count = len(reranked.scores)
        head = [
            cand.with_rerank(score, RERANKER_CONFIGURED)
            for cand, score in zip(candidates[:scored_count], reranked.scores, strict=True)
        ]
        head.sort(key=cmp_to_key(rerank_order))
        tail = list(candidates[scored_count:])
        combined = head + tail
        return truncate(combined, top_n)

    async def _run_dense_only(
        self,
        *,
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        bearer_token: str,
        precomputed_vector: Sequence[float] | None = None,
    ) -> _PipelineRunResult:
        vector = precomputed_vector
        if vector is None:
            vectors = await self._embed_queries(
                queries=[request.primary_query],
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            vector = vectors[0]
        dense = await self._search_dense(
            vector=vector,
            request=request,
            active_gen=active_gen,
            top_k=DENSE_ONLY_TOP_K,
        )
        return _PipelineRunResult(
            candidates=pass_through(dense, request.top_k),
            dense_candidates=len(dense),
            lexical_candidates=0,
            include_rrf=False,
        )

    async def _run_hybrid(
        self,
        *,
        query: str,
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        bearer_token: str,
        precomputed_vector: Sequence[float] | None = None,
    ) -> _PipelineRunResult:
        if is_blank(query):
            return _PipelineRunResult([], 0, 0, True)
        vector = precomputed_vector
        if vector is None:
            vectors = await self._embed_queries(
                queries=[query],
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )
            vector = vectors[0]

        pool = request.candidate_pool
        dense = await self._search_dense(
            vector=vector,
            request=request,
            active_gen=active_gen,
            top_k=pool,
        )
        lexical = await self._search_lexical(
            query=query,
            request=request,
            active_gen=active_gen,
            top_k=pool,
        )
        fused = rrf_fuse(dense, lexical, pool)
        expanded = await self._expand_candidates(
            candidates=fused,
            request=request,
            active_gen=active_gen,
        )
        ranked = await self._rerank(
            query=query,
            candidates=expanded,
            top_n=request.top_k,
            request=request,
            bearer_token=bearer_token,
        )
        return _PipelineRunResult(
            candidates=ranked,
            dense_candidates=len(dense),
            lexical_candidates=len(lexical),
            include_rrf=True,
        )

    async def _run_multi_query_hybrid(
        self,
        *,
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        bearer_token: str,
    ) -> _PipelineRunResult:
        queries = distinct_queries([request.primary_query, *request.sub_queries])
        if len(queries) <= 1:
            return await self._run_hybrid(
                query=queries[0] if queries else request.primary_query,
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
            )

        vectors = await self._embed_queries(
            queries=queries,
            request=request,
            active_gen=active_gen,
            bearer_token=bearer_token,
        )
        per_query: list[list[Candidate]] = []
        dense_total = 0
        lexical_total = 0
        for query_text, vector in zip(queries, vectors, strict=True):
            sub_run = await self._run_hybrid(
                query=query_text,
                request=request,
                active_gen=active_gen,
                bearer_token=bearer_token,
                precomputed_vector=vector,
            )
            per_query.append(sub_run.candidates)
            dense_total += sub_run.dense_candidates
            lexical_total += sub_run.lexical_candidates

        merged = merge_multi_query(per_query, request.top_k)
        return _PipelineRunResult(
            candidates=merged,
            dense_candidates=dense_total,
            lexical_candidates=lexical_total,
            include_rrf=True,
        )

    async def _run_dense_with_bounded_fallback(
        self,
        *,
        request: KnowledgeSearchRequest,
        active_gen: ActiveGeneration,
        bearer_token: str,
    ) -> _PipelineRunResult:
        vectors = await self._embed_queries(
            queries=[request.primary_query],
            request=request,
            active_gen=active_gen,
            bearer_token=bearer_token,
        )
        primary_vector = vectors[0]
        dense_run = await self._run_dense_only(
            request=request,
            active_gen=active_gen,
            bearer_token=bearer_token,
            precomputed_vector=primary_vector,
        )
        if has_usable_evidence(request.intent, dense_run.candidates):
            return dense_run

        return await self._run_hybrid(
            query=request.primary_query,
            request=request,
            active_gen=active_gen,
            bearer_token=bearer_token,
            precomputed_vector=primary_vector,
        )


def _opt_int(raw: object) -> int | None:
    if isinstance(raw, int) and not isinstance(raw, bool):
        return raw
    return None


def _build_context(candidate: Candidate) -> HitContext | None:
    text: str | None = None
    if candidate.parent_context is not None and not is_blank(candidate.parent_context):
        if (
            candidate.expanded is None
            or is_blank(candidate.expanded)
            or len(candidate.parent_context) > len(candidate.expanded)
        ):
            text = candidate.parent_context
        else:
            text = candidate.expanded
    elif candidate.expanded is not None and not is_blank(candidate.expanded):
        text = candidate.expanded

    if text is None or not candidate.context_refs:
        return None
    refs = tuple(
        ChunkRef(chunk_no=ref.chunk_no, content_hash=ref.content_hash.lower())
        for ref in candidate.context_refs
    )
    return HitContext(text=text, chunk_refs=refs)


def _to_search_hit(rank: int, candidate: Candidate, *, include_rrf: bool) -> SearchHit:
    meta: dict[str, Any] = dict(candidate.metadata)
    return SearchHit(
        rank=rank,
        document_id=candidate.document_id,
        chunk_no=candidate.chunk_no,
        content_hash=candidate.content_hash.lower(),
        chunk_type=ChunkType(candidate.chunk_type),
        parent_chunk_no=candidate.parent_chunk_no,
        heading=candidate.heading,
        content=candidate.content,
        body_snapshot_id=candidate.body_snapshot_id,
        source_ref=SourceRef(
            char_from=_opt_int(meta.get("charFrom")),
            char_through=_opt_int(meta.get("charThrough")),
            page_number=_opt_int(meta.get("pageNumber")),
        ),
        context=_build_context(candidate),
        scores=SearchScores(
            dense=candidate.dense,
            lexical=candidate.lexical,
            rrf=candidate.rrf if include_rrf else None,
            rerank=candidate.rerank,
            final=admission_score(candidate.dense, candidate.lexical),
        ),
    )


__all__ = [
    "CODE_INDEX_GENERATION_UNAVAILABLE",
    "CODE_RERANK_UNAVAILABLE",
    "CODE_RETRIEVAL_DENSE_UNAVAILABLE",
    "CODE_RETRIEVAL_LEXICAL_UNAVAILABLE",
    "DENSE_ONLY_TOP_K",
    "KnowledgeSearchPipeline",
]
