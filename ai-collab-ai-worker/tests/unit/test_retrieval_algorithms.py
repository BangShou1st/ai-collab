"""检索算法逐行移植的单元测试：RRF 融合、多查询合并、启发式重排、Java 文本语义。"""

from __future__ import annotations

import uuid

import pytest

from ai_collab_worker.retrieval.candidate import (
    RERANKER_DEGRADED,
    RERANKER_HEURISTIC,
    RERANKER_PASS_THROUGH,
    Candidate,
    java_double_compare,
)
from ai_collab_worker.retrieval.fusion import (
    distinct_queries,
    merge_multi_query,
    multi_query_limit,
    rrf_fuse,
)
from ai_collab_worker.retrieval.heuristic import (
    content_score_of,
    extract_terms,
    heading_score_of,
    heuristic_rerank,
    pass_through,
)
from ai_collab_worker.retrieval.javatext import (
    is_blank,
    split_on_punct_symbol_space,
    trim,
    utf16_length,
)

SNAPSHOT = uuid.UUID("00000000-0000-0000-0000-0000000000ff")
DOC_A = uuid.UUID("00000000-0000-0000-0000-00000000000a")
DOC_B = uuid.UUID("00000000-0000-0000-0000-00000000000b")


def _cand(
    doc: uuid.UUID,
    chunk_no: int,
    *,
    content: str = "正文",
    heading: str | None = None,
    dense: float | None = None,
    lexical: float | None = None,
    rrf: float = 0.0,
) -> Candidate:
    return Candidate(
        document_id=doc,
        chunk_no=chunk_no,
        content_hash=f"h{chunk_no}",
        chunk_type="CHILD",
        heading=heading,
        content=content,
        body_snapshot_id=SNAPSHOT,
        dense=dense,
        lexical=lexical,
        rrf=rrf,
    )


# ---------------------------------------------------------------- javatext


def test_trim_only_strips_control_and_space() -> None:
    assert trim("　 a \t") == "　 a"  # 全角空格不是 ≤ U+0020
    assert trim("\x00\x1fab\x20") == "ab"


def test_is_blank_follows_java_whitespace() -> None:
    assert is_blank(None)
    assert is_blank("")
    assert is_blank(" \t　")  # U+3000 是 Zs，Java isWhitespace 为真
    assert not is_blank(" ")  # 不换行空格不算空白


def test_split_drops_trailing_and_keeps_order() -> None:
    assert split_on_punct_symbol_space("如何 配置,RAG检索？") == ["如何", "配置", "RAG检索"]
    assert split_on_punct_symbol_space(",,,") == []
    assert split_on_punct_symbol_space(",a")[-1] == "a"


def test_utf16_length_counts_surrogates() -> None:
    assert utf16_length("a\U0001f600") == 3


def test_java_double_compare_negative_zero_and_nan() -> None:
    assert java_double_compare(-0.0, 0.0) == -1
    assert java_double_compare(float("nan"), float("nan")) == 0
    assert java_double_compare(float("nan"), 1.0) == 1


# ---------------------------------------------------------------- fusion


def test_rrf_scores_and_dedup() -> None:
    a0 = _cand(DOC_A, 0, dense=0.9)
    a1 = _cand(DOC_A, 1, dense=0.8)
    b0 = _cand(DOC_B, 0, lexical=0.7)
    a0_lex = _cand(DOC_A, 0, lexical=0.5)
    fused = rrf_fuse([a0, a1], [a0_lex, b0], top_k=10)
    # A1、B0 同为 1/62、同一路、最好名次同为 2，按 (documentId, chunkNo) 升序。
    assert [c.key for c in fused] == [(DOC_A, 0), (DOC_A, 1), (DOC_B, 0)]
    top = fused[0]
    assert top.key == (DOC_A, 0)
    assert top.rrf == pytest.approx(1 / 61 + 1 / 61)
    assert top.dense == 0.9
    assert top.lexical == 0.5
    assert len({c.key for c in fused}) == 3


def test_rrf_tie_breaks_by_document_then_chunk() -> None:
    # 同名次、同路数时按 (documentId, chunkNo) 升序。
    fused = rrf_fuse([_cand(DOC_B, 0, dense=0.5)], [_cand(DOC_A, 3, lexical=0.5)], top_k=10)
    assert [c.key for c in fused] == [(DOC_A, 3), (DOC_B, 0)]


def test_rrf_truncates_and_rejects_bad_k() -> None:
    dense = [_cand(DOC_A, i, dense=0.5) for i in range(5)]
    assert len(rrf_fuse(dense, [], top_k=2)) == 2
    assert rrf_fuse([], [], top_k=2) == []
    with pytest.raises(ValueError, match="RRF"):
        rrf_fuse(dense, [], top_k=2, k=0)


def test_distinct_queries_and_limit() -> None:
    assert distinct_queries(["a", " ", "a", "b", ""]) == ["a", "b"]
    assert multi_query_limit(8, 1) == 8
    assert multi_query_limit(8, 3) == 14
    assert multi_query_limit(20, 4) == 24


def test_merge_multi_query_primary_first_then_fused_extras() -> None:
    primary = [_cand(DOC_A, 0, rrf=0.02), _cand(DOC_A, 1, rrf=0.01)]
    sub1 = [_cand(DOC_B, 0, rrf=0.015), _cand(DOC_B, 1, rrf=0.03)]
    sub2 = [_cand(DOC_B, 0, rrf=0.02), _cand(DOC_A, 0, rrf=0.05)]
    merged = merge_multi_query([primary, sub1, sub2], top_k=8)
    keys = [c.key for c in merged]
    assert keys[:2] == [(DOC_A, 0), (DOC_A, 1)]
    # B0 跨查询 RRF 之和 0.035 > B1 的 0.03。
    assert keys[2:] == [(DOC_B, 0), (DOC_B, 1)]
    # 保留 RRF 最高的那个实例。
    assert merged[2].rrf == 0.02


# ---------------------------------------------------------------- heuristic


def test_extract_terms_matches_java_sample() -> None:
    terms = extract_terms("如何配置 RAG 检索的重排?")
    assert terms == ["如何配置", "rag", "检索的重排", "何配", "配置", "检索", "重排"]


def test_extract_terms_long_cjk_sentence_uses_sampled_bigrams() -> None:
    sentence = "".join(chr(0x4E00 + 100 + i) for i in range(120))
    terms = extract_terms(sentence)
    assert sentence not in terms  # 超过 10 个字不整串入库
    assert all(len(t) == 2 for t in terms)
    assert len(terms) <= 64


def test_extract_terms_fallback_keeps_single_chars() -> None:
    assert extract_terms("x") == ["x"]


def test_heading_and_content_scores() -> None:
    terms = ["rag", "检索"]
    assert heading_score_of("RAG 检索指南", "rag", terms) == 1.0
    assert heading_score_of("检索", "rag 检索", terms) == 0.5
    assert heading_score_of(None, "q", terms) == 0.0
    assert content_score_of("rag rag 检索", terms) == pytest.approx(min(1.0, 1.0 + 0.06))
    assert content_score_of("只有检索", terms) == pytest.approx(0.5 + 0.02)
    assert content_score_of("   ", terms) == 0.0


def test_heuristic_rerank_orders_and_marks() -> None:
    hit = _cand(DOC_B, 0, content="RAG 检索的重排说明", heading="重排", rrf=0.01)
    miss = _cand(DOC_A, 0, content="无关内容", rrf=0.03)
    ranked = heuristic_rerank("RAG 检索 重排", [miss, hit], top_n=5)
    assert ranked[0].key == (DOC_B, 0)
    assert all(c.reranker == RERANKER_HEURISTIC for c in ranked)
    assert ranked[1].rerank == pytest.approx(0.03 * 0.35)


def test_heuristic_rerank_blank_query_degrades() -> None:
    cands = [_cand(DOC_A, i, rrf=0.01 * i) for i in range(3)]
    ranked = heuristic_rerank("  ", cands, top_n=2)
    assert [c.key for c in ranked] == [(DOC_A, 0), (DOC_A, 1)]
    assert all(c.reranker == RERANKER_DEGRADED and c.rerank == c.rrf for c in ranked)


def test_heuristic_rerank_does_not_swallow_errors(monkeypatch: pytest.MonkeyPatch) -> None:
    # P2-R2 R2-1：删除 Java 的 catch(Throwable) → degrade，异常直接抛给调用方。
    import ai_collab_worker.retrieval.heuristic as module

    def boom(*_: object) -> float:
        raise RuntimeError("boom")

    monkeypatch.setattr(module, "content_score_of", boom)
    with pytest.raises(RuntimeError):
        heuristic_rerank("rag", [_cand(DOC_A, 0)], top_n=5)


def test_pass_through_keeps_order() -> None:
    cands = [_cand(DOC_B, 0, rrf=0.01), _cand(DOC_A, 0, rrf=0.02)]
    out = pass_through(cands, top_n=0)
    assert [c.key for c in out] == [(DOC_B, 0), (DOC_A, 0)]
    assert all(c.reranker == RERANKER_PASS_THROUGH for c in out)


def test_rerank_never_mutates_evidence_identity() -> None:
    original = _cand(DOC_A, 7, content="RAG", heading="h")
    (ranked,) = heuristic_rerank("rag", [original], top_n=5)
    assert (ranked.document_id, ranked.chunk_no, ranked.content_hash, ranked.content) == (
        original.document_id,
        original.chunk_no,
        original.content_hash,
        original.content,
    )
