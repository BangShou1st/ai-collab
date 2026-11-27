"""``/v1/knowledge/search`` 请求校验与响应契约的单元测试（P2 契约 §4.4、§9.2，P2-R1 第 2 条）。

覆盖：分数区间与 ``final`` 语义、空可见集合、5000/5001 个 ID、未知 strategy、恰好一条 PRIMARY、
``topK``/``candidatePool`` 边界、令牌 ``uid/pid`` 不符（403）与缺失（401）、越权结果剔除。
"""

from __future__ import annotations

import math
import uuid
from typing import Any

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.knowledge_search import (
    MAX_VISIBLE_DOCUMENTS,
    KnowledgeSearchResponse,
    RetrievalStrategy,
    SearchHit,
    SearchRejectedError,
    SearchScores,
    admission_score,
    authorize_search,
    empty_response,
    enforce_visibility,
    parse_search_request,
    response_json,
)

USER_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
PROJECT_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
DOC_A = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
DOC_B = uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
HASH = "a" * 64
QUERY_SECRET = "机密查询原文-ZX81"


def _request(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "requestId": str(uuid.uuid4()),
        "projectId": str(PROJECT_ID),
        "userId": str(USER_ID),
        "intent": "KNOWLEDGE_QA",
        "strategy": "DENSE_WITH_BOUNDED_FALLBACK",
        "queries": [{"text": QUERY_SECRET, "role": "PRIMARY"}],
        "originalQuery": QUERY_SECRET,
        "visibleDocumentIds": [str(DOC_A)],
        "topK": 8,
        "candidatePool": 20,
        "headingFilter": {"exact": None, "contains": None},
        "rerank": "HEURISTIC",
        "expand": {"sentenceWindow": True, "parent": True},
        "deadlineMs": 8000,
    }
    body.update(overrides)
    return body


def _rejected(raw: object) -> SearchRejectedError:
    with pytest.raises(SearchRejectedError) as info:
        parse_search_request(raw)
    return info.value


# ---- 请求解析 ------------------------------------------------------------------


def test_valid_request_parses() -> None:
    request = parse_search_request(_request())
    assert request.primary_query == QUERY_SECRET
    assert request.sub_queries == ()
    assert request.visible_document_ids == (DOC_A,)
    assert request.strategy is RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK


@pytest.mark.parametrize("strategy", [s.value for s in RetrievalStrategy])
def test_all_four_strategies_accepted(strategy: str) -> None:
    assert parse_search_request(_request(strategy=strategy)).strategy.value == strategy


def test_unknown_strategy_is_400() -> None:
    rejection = _rejected(_request(strategy="SEMANTIC_MAGIC"))
    assert (rejection.status, rejection.code, rejection.reason) == (
        400,
        "VALIDATION_ERROR",
        "unknown_strategy",
    )


def test_empty_visible_set_is_valid() -> None:
    request = parse_search_request(_request(visibleDocumentIds=[]))
    assert request.visible_document_ids == ()
    response = empty_response(request, latency_ms=0)
    assert response.hits == ()
    assert response.generation_id is None
    assert response.strategy is request.strategy


def test_exactly_5000_visible_ids_accepted() -> None:
    ids = [str(uuid.uuid4()) for _ in range(MAX_VISIBLE_DOCUMENTS)]
    assert len(parse_search_request(_request(visibleDocumentIds=ids)).visible_document_ids) == 5000


def test_5001_visible_ids_is_400() -> None:
    ids = [str(uuid.uuid4()) for _ in range(MAX_VISIBLE_DOCUMENTS + 1)]
    rejection = _rejected(_request(visibleDocumentIds=ids))
    assert (rejection.status, rejection.code, rejection.reason) == (
        400,
        "VALIDATION_ERROR",
        "visible_documents_limit",
    )


def test_duplicate_visible_ids_are_collapsed() -> None:
    request = parse_search_request(_request(visibleDocumentIds=[str(DOC_A), str(DOC_A)]))
    assert request.visible_document_ids == (DOC_A,)


@pytest.mark.parametrize(
    "overrides",
    [
        {"queries": []},
        {"queries": [{"text": "a", "role": "SUB"}]},
        {"queries": [{"text": "a", "role": "PRIMARY"}, {"text": "b", "role": "PRIMARY"}]},
        {"queries": [{"text": "q", "role": "PRIMARY"}] + [{"text": "s", "role": "SUB"}] * 5},
        {"queries": [{"text": "", "role": "PRIMARY"}]},
        {"queries": [{"text": "x" * 2001, "role": "PRIMARY"}]},
        {"topK": 0},
        {"topK": 21},
        {"candidatePool": 7},
        {"candidatePool": 51},
        {"rerank": "NEURAL"},
        {"intent": "CHAT"},
        {"deadlineMs": 0},
        {"visibleDocumentIds": ["not-a-uuid"]},
        {"unexpectedField": 1},
    ],
)
def test_malformed_requests_are_400_without_echo(overrides: dict[str, Any]) -> None:
    rejection = _rejected(_request(**overrides))
    assert (rejection.status, rejection.code) == (400, "VALIDATION_ERROR")
    assert QUERY_SECRET not in str(rejection)


def test_non_object_body_is_400() -> None:
    assert _rejected([1, 2]).status == 400


# ---- 令牌主体 ------------------------------------------------------------------


def test_matching_principal_passes() -> None:
    request = parse_search_request(_request())
    authorize_search({"uid": str(USER_ID), "pid": str(PROJECT_ID)}, request)


@pytest.mark.parametrize(
    "claims",
    [
        {"uid": str(uuid.uuid4()), "pid": str(PROJECT_ID)},
        {"uid": str(USER_ID), "pid": str(uuid.uuid4())},
    ],
)
def test_principal_mismatch_is_403(claims: dict[str, str]) -> None:
    request = parse_search_request(_request())
    with pytest.raises(SearchRejectedError) as info:
        authorize_search(claims, request)
    assert (info.value.status, info.value.code) == (403, "SEARCH_FORBIDDEN")


@pytest.mark.parametrize("claims", [{}, {"uid": str(USER_ID)}, {"uid": 1, "pid": "x"}])
def test_missing_principal_claims_is_401(claims: dict[str, object]) -> None:
    request = parse_search_request(_request())
    with pytest.raises(SearchRejectedError) as info:
        authorize_search(claims, request)
    assert info.value.status == 401


# ---- 分数 ----------------------------------------------------------------------


def test_admission_score_is_max_of_present_channels() -> None:
    assert admission_score(0.83, None) == 0.83
    assert admission_score(None, 0.4) == 0.4
    assert admission_score(0.2, 0.6) == 0.6
    assert admission_score(None, None) == 0.0


def test_admission_score_is_clamped_into_unit_interval() -> None:
    assert admission_score(-0.3, None) == 0.0
    assert admission_score(1.0000001, None) == 1.0
    assert admission_score(math.nan, None) == 0.0


@pytest.mark.parametrize(
    ("dense", "lexical"),
    [(0.83, None), (None, 0.03), (0.1, 0.9), (None, None), (-0.5, None), (1.2, 0.3)],
)
def test_final_always_in_unit_interval(dense: float | None, lexical: float | None) -> None:
    final = admission_score(dense, lexical)
    assert 0.0 <= final <= 1.0
    SearchScores(dense=dense, lexical=lexical, rrf=0.0164, rerank=0.91, final=final)


def test_final_must_not_be_rerank_or_rrf() -> None:
    # 2026-11-07 事故的反面：final 取 rerank / rrf 一律拒绝。
    with pytest.raises(ValidationError):
        SearchScores(dense=0.83, lexical=None, rrf=0.0164, rerank=0.91, final=0.91)
    with pytest.raises(ValidationError):
        SearchScores(dense=0.83, lexical=None, rrf=0.0164, rerank=None, final=0.0164)
    with pytest.raises(ValidationError):
        SearchScores(dense=None, lexical=None, final=1.5)


def test_contract_example_scores_are_valid() -> None:
    scores = SearchScores(dense=0.83, lexical=None, rrf=0.0164, rerank=0.91, final=0.83)
    assert scores.model_dump(by_alias=True) == {
        "dense": 0.83,
        "lexical": None,
        "rrf": 0.0164,
        "rerank": 0.91,
        "final": 0.83,
    }


# ---- 越权剔除与响应形状 --------------------------------------------------------


def _hit(rank: int, document_id: uuid.UUID, chunk_no: int = 0) -> SearchHit:
    return SearchHit(
        rank=rank,
        document_id=document_id,
        chunk_no=chunk_no,
        content_hash=HASH,
        chunk_type="STANDARD",
        content="原文",
        body_snapshot_id=uuid.uuid4(),
        scores=SearchScores(dense=0.5, final=0.5),
    )


def test_hits_outside_visible_set_are_dropped_and_reranked() -> None:
    hits = [_hit(1, DOC_B), _hit(2, DOC_A, 3), _hit(3, DOC_B, 1), _hit(4, DOC_A, 4)]
    kept, dropped = enforce_visibility(hits, [DOC_A])
    assert dropped == 2
    assert {hit.document_id for hit in kept} == {DOC_A}
    assert [hit.rank for hit in kept] == [1, 2]
    assert [hit.chunk_no for hit in kept] == [3, 4]


def test_empty_visible_set_drops_everything() -> None:
    kept, dropped = enforce_visibility([_hit(1, DOC_A)], [])
    assert kept == ()
    assert dropped == 1


def test_response_rejects_gaps_in_rank_and_over_24_hits() -> None:
    request = parse_search_request(_request())
    base = empty_response(request, latency_ms=1)
    with pytest.raises(ValidationError):
        KnowledgeSearchResponse(
            request_id=base.request_id,
            generation_id=uuid.uuid4(),
            strategy=base.strategy,
            hits=(_hit(1, DOC_A), _hit(3, DOC_A, 1)),
            diagnostics=base.diagnostics,
        )
    with pytest.raises(ValidationError):
        KnowledgeSearchResponse(
            request_id=base.request_id,
            generation_id=uuid.uuid4(),
            strategy=base.strategy,
            hits=tuple(_hit(i, DOC_A, i) for i in range(1, 26)),
            diagnostics=base.diagnostics,
        )


def test_response_json_is_camel_case_and_has_no_query_text() -> None:
    request = parse_search_request(_request(visibleDocumentIds=[]))
    body = response_json(empty_response(request, latency_ms=3))
    assert set(body) == {"requestId", "generationId", "strategy", "hits", "diagnostics"}
    assert set(body["diagnostics"]) == {
        "denseCandidates",
        "lexicalCandidates",
        "iterativeScan",
        "rerank",
        "latencyMs",
    }
    assert QUERY_SECRET not in str(body)
