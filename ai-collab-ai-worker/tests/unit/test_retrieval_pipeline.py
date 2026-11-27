"""P2 检索管线单元测试：4 种策略、EvidenceSufficiencyProbe、扩展器与 R2-1 严苛失败口径。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import uuid
from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx2
import pytest

from ai_collab_worker.clients.java_internal import JavaInternalError
from ai_collab_worker.contract.knowledge_search import (
    KnowledgeSearchRequest,
    SearchIntent,
    SearchRejectedError,
    parse_search_request,
)
from ai_collab_worker.index.db import VECTOR_DIMENSION
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import Lease, LeaseCache, LeasePurpose, LeaseRequest
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.retrieval.candidate import Candidate, RowRef
from ai_collab_worker.retrieval.expand import (
    expand_parent_child,
    expand_sentence_windows,
    is_table_or_code_block,
    split_sentences,
    stitch_chunks,
)
from ai_collab_worker.retrieval.pipeline import (
    CODE_INDEX_GENERATION_UNAVAILABLE,
    CODE_RERANK_UNAVAILABLE,
    CODE_RETRIEVAL_DENSE_UNAVAILABLE,
    CODE_RETRIEVAL_LEXICAL_UNAVAILABLE,
    DENSE_ONLY_TOP_K,
    KnowledgeSearchPipeline,
)
from ai_collab_worker.retrieval.probe import has_usable_evidence
from ai_collab_worker.retrieval.store import (
    ActiveGeneration,
    RetrievalStorageError,
    SectionChunk,
)

USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
GEN_ID = uuid.UUID("66666666-6666-4666-8666-666666666661")
SNAP_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
DOC_A = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
DOC_B = uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
BEARER = "tok.pipeline.unit"


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _cand(
    doc: uuid.UUID,
    chunk_no: int,
    *,
    chunk_type: str = "STANDARD",
    parent_chunk_no: int | None = None,
    heading: str | None = "标题",
    content: str = "正文片段",
    dense: float | None = None,
    lexical: float | None = None,
    dense_rank: int | None = None,
    lexical_rank: int | None = None,
    snapshot_id: uuid.UUID = SNAP_ID,
    metadata: dict[str, Any] | None = None,
) -> Candidate:
    return Candidate(
        document_id=doc,
        chunk_no=chunk_no,
        content_hash=_sha(f"{doc}:{chunk_no}:{content}"),
        chunk_type=chunk_type,
        heading=heading,
        content=content,
        body_snapshot_id=snapshot_id,
        parent_chunk_no=parent_chunk_no,
        metadata=metadata or {"charFrom": 0, "charThrough": 10, "pageNumber": 1},
        dense=dense,
        lexical=lexical,
        dense_rank=dense_rank,
        lexical_rank=lexical_rank,
    )


def _search_req(**overrides: Any) -> KnowledgeSearchRequest:
    raw: dict[str, Any] = {
        "requestId": str(uuid.uuid4()),
        "projectId": str(PROJECT_ID),
        "userId": str(USER_ID),
        "intent": "KNOWLEDGE_QA",
        "strategy": "HYBRID",
        "queries": [{"text": "分布式 锁 续期", "role": "PRIMARY"}],
        "originalQuery": "分布式锁如何续期？",
        "visibleDocumentIds": [str(DOC_A), str(DOC_B)],
        "topK": 5,
        "candidatePool": 20,
        "headingFilter": {"exact": None, "contains": None},
        "rerank": "HEURISTIC",
        "expand": {"sentenceWindow": True, "parent": True},
        "deadlineMs": 5000,
    }
    raw.update(overrides)
    return parse_search_request(raw)


class FakeStore:
    def __init__(self) -> None:
        self.active_generation: ActiveGeneration | None = ActiveGeneration(
            generation_id=GEN_ID,
            embedding_model="qwen3-embedding:0.6b",
            dimension=VECTOR_DIMENSION,
        )
        self.dense_calls: list[tuple[int, Sequence[uuid.UUID]]] = []
        self.lexical_calls: list[tuple[str, int]] = []
        self.dense_by_call: list[list[Candidate]] = []
        self.lexical_by_call: list[list[Candidate]] = []
        self.section_chunks: dict[tuple[uuid.UUID, str | None], list[SectionChunk]] = {}
        self.parent_chunks: dict[tuple[uuid.UUID, int], SectionChunk] = {}
        self.dense_error: Exception | None = None
        self.lexical_error: Exception | None = None

    async def get_active_generation(self) -> ActiveGeneration | None:
        return self.active_generation

    async def search_dense(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query_vector: Sequence[float],
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: Any,
    ) -> list[Candidate]:
        _ = (generation_id, project_id, query_vector, heading_filter)
        self.dense_calls.append((top_k, list(document_ids)))
        if self.dense_error is not None:
            raise self.dense_error
        if self.dense_by_call:
            idx = min(len(self.dense_calls) - 1, len(self.dense_by_call) - 1)
            return list(self.dense_by_call[idx])[:top_k]
        return []

    async def search_lexical(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query: str,
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: Any,
    ) -> list[Candidate]:
        _ = (generation_id, project_id, document_ids, heading_filter)
        self.lexical_calls.append((query, top_k))
        if self.lexical_error is not None:
            raise self.lexical_error
        if self.lexical_by_call:
            idx = min(len(self.lexical_calls) - 1, len(self.lexical_by_call) - 1)
            return list(self.lexical_by_call[idx])[:top_k]
        return []

    async def fetch_section_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        document_id: uuid.UUID,
        body_snapshot_id: uuid.UUID,
        heading: str | None,
    ) -> list[SectionChunk]:
        _ = (generation_id, project_id, body_snapshot_id)
        return list(self.section_chunks.get((document_id, heading), []))

    async def fetch_parent_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        targets: Sequence[tuple[uuid.UUID, int]],
    ) -> dict[tuple[uuid.UUID, int], SectionChunk]:
        _ = (generation_id, project_id)
        return {k: self.parent_chunks[k] for k in targets if k in self.parent_chunks}


def _kernel(
    *,
    embed_model: str = "qwen3-embedding:0.6b",
    embed_dim: int = VECTOR_DIMENSION,
    rerank_max_candidates: int = 8,
    rerank_handler: Any | None = None,
    embed_error: Exception | None = None,
    rerank_lease_error: Exception | None = None,
) -> tuple[ModelKernel, list[LeaseRequest], list[dict[str, object]]]:
    lease_requests: list[LeaseRequest] = []
    usage_items: list[dict[str, object]] = []

    async def fetch_lease(req: LeaseRequest, _token: str) -> Lease:
        lease_requests.append(req)
        if req.purpose is LeasePurpose.EMBEDDING:
            if embed_error is not None:
                raise embed_error
            return Lease.model_validate(
                {
                    "leaseId": str(uuid.uuid4()),
                    "protocol": "OPENAI_EMBEDDINGS",
                    "providerType": "OPENAI_COMPATIBLE",
                    "baseUrl": "https://embed.test",
                    "apiPath": "/v1/embeddings",
                    "model": embed_model,
                    "apiKey": "sk-embed",
                    "params": {"dimensions": VECTOR_DIMENSION, "batchSize": 16},
                    "capabilities": ["EMBEDDING"],
                    "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                }
            )
        if rerank_lease_error is not None:
            raise rerank_lease_error
        return Lease.model_validate(
            {
                "leaseId": str(uuid.uuid4()),
                "protocol": "LLAMACPP_RERANK",
                "providerType": "LLAMACPP_RERANK",
                "baseUrl": "http://rerank.test",
                "apiPath": "/rerank",
                "model": "bge-reranker",
                "apiKey": None,
                "params": {"maxCandidates": rerank_max_candidates, "timeoutMs": 1000},
                "capabilities": ["RERANK"],
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        )

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        for entry in items:
            usage_items.append(dict(entry))
        return {str(entry["callId"]): True for entry in items}

    def transport(req: httpx2.Request) -> httpx2.Response:
        if req.url.path == "/v1/embeddings":
            body = json.loads(req.content.decode("utf-8"))
            return httpx2.Response(
                200,
                json={"data": [{"embedding": [0.05] * embed_dim} for _ in body["input"]]},
            )
        if rerank_handler is not None:
            return rerank_handler(req)
        body = json.loads(req.content.decode("utf-8"))
        return httpx2.Response(
            200,
            json={
                "results": [
                    {"index": i, "relevance_score": float(len(body["documents"]) - i)}
                    for i in range(len(body["documents"]))
                ]
            },
        )

    kernel = ModelKernel(
        leases=LeaseCache(fetch_lease),
        usage=UsageReporter(send_usage, max_batch=50, flush_interval=60.0),
        runtime=ModelRuntime(
            retry_backoff_seconds=0.0,
            transport=httpx2.MockTransport(transport),
        ),
    )
    return kernel, lease_requests, usage_items


def test_evidence_sufficiency_probe_matches_java_semantics() -> None:
    container = _cand(DOC_A, 1, chunk_type="STANDARD", content="容器块文本", dense=0.8)
    sentence_low = _cand(
        DOC_A, 2, chunk_type="SENTENCE", parent_chunk_no=1, content="句子块", dense=0.10
    )
    # SENTENCE(parentChunkNo=1) 抑制了 container(chunkNo=1)，剩下的 sentence_low < 0.15 -> False
    assert not has_usable_evidence(SearchIntent.KNOWLEDGE_QA, [container, sentence_low])
    # 非 KNOWLEDGE_QA 只看候选非空 -> True
    assert has_usable_evidence(SearchIntent.AGENT_RESEARCH, [container, sentence_low])
    assert has_usable_evidence(SearchIntent.PLANNING_SOURCE, [sentence_low])
    # 阈值边界 0.15 放行，空白内容拒绝
    assert has_usable_evidence(
        SearchIntent.KNOWLEDGE_QA,
        [_cand(DOC_A, 3, dense=0.15, content="有效文本")],
    )
    assert not has_usable_evidence(
        SearchIntent.KNOWLEDGE_QA,
        [_cand(DOC_A, 3, dense=0.90, content="   \n  ")],
    )


def test_sentence_window_and_parent_child_expansion() -> None:
    spans = split_sentences("第一句（含1.5小数）。第二句“引用句？”。```code```")
    assert [s.text for s in spans] == [
        "第一句（含1.5小数）。",
        "第二句“引用句？”",
        "。",
        "```code```",
    ]
    spans2 = split_sentences("第一句（含1.5小数）。第二句“引用句”。第三句结束！")
    assert [s.text for s in spans2] == [
        "第一句（含1.5小数）。",
        "第二句“引用句”。",
        "第三句结束！",
    ]
    assert stitch_chunks(["ABC重排", "重排DEF"]) == "ABC重排DEF"
    assert is_table_or_code_block(_cand(DOC_A, 9, content="| A | B |\n| --- | --- |\n| 1 | 2 |"))
    assert is_table_or_code_block(_cand(DOC_A, 10, content="```python\nprint(1)\n```"))

    store = FakeStore()
    parent_content = "第一句前置。第二句命中的关键描述。第三句补充说明。第四句尾巴。"
    store.section_chunks[(DOC_A, "架构")] = [
        SectionChunk(DOC_A, SNAP_ID, 0, "PARENT", "架构", parent_content, _sha(parent_content))
    ]
    store.parent_chunks[(DOC_A, 0)] = SectionChunk(
        DOC_A, SNAP_ID, 0, "PARENT", "架构", parent_content, _sha(parent_content)
    )
    c1 = _cand(
        DOC_A,
        1,
        chunk_type="CHILD",
        parent_chunk_no=0,
        heading="架构",
        content="第二句命中的关键描述。",
        dense=0.9,
    )
    table_cand = _cand(
        DOC_A,
        5,
        heading="架构",
        content="| k | v |\n| --- | --- |\n| a | b |",
        dense=0.5,
    )

    async def main() -> list[Candidate]:
        sw = await expand_sentence_windows(
            store=store,
            generation_id=GEN_ID,
            project_id=PROJECT_ID,
            candidates=[c1, table_cand],
        )
        return await expand_parent_child(
            store=store,
            generation_id=GEN_ID,
            project_id=PROJECT_ID,
            candidates=sw,
        )

    out = asyncio.run(main())
    by_no = {c.chunk_no: c for c in out}
    assert by_no[1].expanded == "第一句前置。 第二句命中的关键描述。 第三句补充说明。"
    assert by_no[1].parent_context == parent_content
    assert RowRef(0, _sha(parent_content)) in by_no[1].context_refs
    # 原文与 hash 绝不被改写
    assert by_no[1].content == "第二句命中的关键描述。"
    # 表格跳过窗口扩展
    assert by_no[5].expanded is None


def test_strategies_dense_hybrid_multiquery_and_bounded_fallback() -> None:
    store = FakeStore()
    store.dense_by_call = [[_cand(DOC_A, 1, content="分布式 锁 续期", dense=0.82, dense_rank=1)]]
    store.lexical_by_call = [[_cand(DOC_B, 2, content="关键词匹配", lexical=0.75, lexical_rank=1)]]
    kernel, lease_reqs, _ = _kernel()
    pipeline = KnowledgeSearchPipeline(store=store, kernel=kernel)

    # 1. DENSE：fixed denseTopK=20，不查词法、不扩展、不走 RERANK
    dense_resp = asyncio.run(
        pipeline.execute(
            _search_req(strategy="DENSE", rerank="CONFIGURED", candidatePool=40),
            bearer_token=BEARER,
        )
    )
    assert store.dense_calls[0][0] == DENSE_ONLY_TOP_K
    assert store.lexical_calls == []
    assert dense_resp.hits[0].scores.rrf is None
    assert dense_resp.hits[0].scores.final == pytest.approx(0.82)
    assert all(req.purpose is not LeasePurpose.RERANK for req in lease_reqs)

    # 2. DENSE_WITH_BOUNDED_FALLBACK：Dense 证据不足时补跑一次 HYBRID
    store2 = FakeStore()
    store2.dense_by_call = [
        [_cand(DOC_A, 1, content="弱相关", dense=0.10, dense_rank=1)],  # step1 < 0.15 -> 触发回退
        [_cand(DOC_A, 1, content="弱相关", dense=0.10, dense_rank=1)],  # step2 HYBRID
    ]
    store2.lexical_by_call = [[_cand(DOC_B, 3, content="分布式 锁", lexical=0.80, lexical_rank=1)]]
    k2, _, _ = _kernel()
    p2 = KnowledgeSearchPipeline(store=store2, kernel=k2)
    fb_resp = asyncio.run(
        p2.execute(_search_req(strategy="DENSE_WITH_BOUNDED_FALLBACK"), bearer_token=BEARER)
    )
    assert len(store2.dense_calls) == 2
    assert len(store2.lexical_calls) == 1
    assert fb_resp.hits[0].document_id == DOC_B
    assert fb_resp.hits[0].scores.final == pytest.approx(0.80)

    # 3. MULTI_QUERY_HYBRID：主查询保序 + 子查询去重增补，条数上限 min(topK + 3*(n-1), 24)
    store3 = FakeStore()
    store3.dense_by_call = [
        [
            _cand(DOC_A, i, content=f"主查询{i}", dense=0.9 - 0.01 * i, dense_rank=i + 1)
            for i in range(4)
        ],
        [
            _cand(DOC_B, i, content=f"子查询{i}", dense=0.85 - 0.01 * i, dense_rank=i + 1)
            for i in range(5)
        ],
    ]
    store3.lexical_by_call = [[], []]
    k3, _, _ = _kernel()
    p3 = KnowledgeSearchPipeline(store=store3, kernel=k3)
    mq_resp = asyncio.run(
        p3.execute(
            _search_req(
                strategy="MULTI_QUERY_HYBRID",
                topK=4,
                candidatePool=20,
                queries=[
                    {"text": "主查询", "role": "PRIMARY"},
                    {"text": "子查询", "role": "SUB"},
                    {"text": "主查询", "role": "SUB"},  # 重复会被去重，n=2 -> limit=7
                ],
            ),
            bearer_token=BEARER,
        )
    )
    assert len(mq_resp.hits) == 7  # min(4 + 3*(2-1), 24) > topK=4


def test_configured_rerank_slices_max_candidates_and_never_falls_back() -> None:
    store = FakeStore()
    store.dense_by_call = [
        [
            _cand(DOC_A, 0, content="cand0", dense=0.9, dense_rank=1),
            _cand(DOC_A, 1, content="cand1", dense=0.8, dense_rank=2),
            _cand(DOC_A, 2, content="cand2", dense=0.7, dense_rank=3),
        ]
    ]
    store.lexical_by_call = [[]]

    # 只重排前 2 条（翻转顺序），第 3 条保持融合顺序接在后面；final 仍等于 dense!
    def rerank_reverse(req: httpx2.Request) -> httpx2.Response:
        docs = json.loads(req.content.decode("utf-8"))["documents"]
        assert len(docs) == 2
        return httpx2.Response(
            200,
            json={
                "results": [
                    {"index": 0, "relevance_score": 0.2},
                    {"index": 1, "relevance_score": 0.95},
                ]
            },
        )

    kernel, _, usage_items = _kernel(rerank_max_candidates=2, rerank_handler=rerank_reverse)
    pipeline = KnowledgeSearchPipeline(store=store, kernel=kernel)
    resp = asyncio.run(
        pipeline.execute(_search_req(rerank="CONFIGURED", topK=3), bearer_token=BEARER)
    )
    asyncio.run(kernel._usage.flush(ignore_backoff=True))
    assert [h.chunk_no for h in resp.hits] == [1, 0, 2]
    assert resp.hits[0].scores.rerank == pytest.approx(0.95)
    assert resp.hits[0].scores.final == pytest.approx(0.8)  # 不混入 rerank!
    assert resp.hits[2].scores.rerank is None
    assert any(item["purpose"] == "RERANK" and item["inputTokens"] == 0 for item in usage_items)

    # 非法 JSON / 超时 / 租借 503 一律 503 RERANK_UNAVAILABLE，绝不退回启发式
    for bad_handler, bad_lease in [
        (lambda _r: httpx2.Response(200, json={"results": []}), None),
        (lambda _r: httpx2.Response(502, text="bad"), None),
        (None, JavaInternalError("down", status=503, retryable=True)),
    ]:
        k_err, _, _ = _kernel(
            rerank_handler=bad_handler,
            rerank_lease_error=bad_lease,
        )
        p_err = KnowledgeSearchPipeline(store=store, kernel=k_err)
        with pytest.raises(SearchRejectedError) as exc_info:
            asyncio.run(p_err.execute(_search_req(rerank="CONFIGURED"), bearer_token=BEARER))
        assert (exc_info.value.status, exc_info.value.code) == (503, CODE_RERANK_UNAVAILABLE)


def test_r2_1_no_active_generation_model_mismatch_dense_and_lexical_failures() -> None:
    # 1. 无 ACTIVE generation -> 409 INDEX_GENERATION_UNAVAILABLE
    store = FakeStore()
    store.active_generation = None
    k, _, _ = _kernel()
    with pytest.raises(SearchRejectedError) as e_no_gen:
        asyncio.run(
            KnowledgeSearchPipeline(store=store, kernel=k).execute(
                _search_req(), bearer_token=BEARER
            )
        )
    assert (e_no_gen.value.status, e_no_gen.value.code) == (409, CODE_INDEX_GENERATION_UNAVAILABLE)

    # 2. 模型与 ACTIVE generation 不一致 -> 409 INDEX_GENERATION_UNAVAILABLE
    store2 = FakeStore()
    k2, _, _ = _kernel(embed_model="other-model")
    with pytest.raises(SearchRejectedError) as e_model:
        asyncio.run(
            KnowledgeSearchPipeline(store=store2, kernel=k2).execute(
                _search_req(), bearer_token=BEARER
            )
        )
    assert (e_model.value.status, e_model.value.code) == (409, CODE_INDEX_GENERATION_UNAVAILABLE)

    # 3. Dense SQL 失败 -> 503 RETRIEVAL_DENSE_UNAVAILABLE（不降级为词法）
    store3 = FakeStore()
    store3.dense_error = RetrievalStorageError("dense")
    k3, _, _ = _kernel()
    with pytest.raises(SearchRejectedError) as e_dense:
        asyncio.run(
            KnowledgeSearchPipeline(store=store3, kernel=k3).execute(
                _search_req(), bearer_token=BEARER
            )
        )
    assert (e_dense.value.status, e_dense.value.code) == (503, CODE_RETRIEVAL_DENSE_UNAVAILABLE)

    # 4. Lexical SQL 失败 -> 503 RETRIEVAL_LEXICAL_UNAVAILABLE（不降级为空结果）
    store4 = FakeStore()
    store4.lexical_error = RetrievalStorageError("lexical")
    k4, _, _ = _kernel()
    with pytest.raises(SearchRejectedError) as e_lex:
        asyncio.run(
            KnowledgeSearchPipeline(store=store4, kernel=k4).execute(
                _search_req(), bearer_token=BEARER
            )
        )
    assert (e_lex.value.status, e_lex.value.code) == (503, CODE_RETRIEVAL_LEXICAL_UNAVAILABLE)
