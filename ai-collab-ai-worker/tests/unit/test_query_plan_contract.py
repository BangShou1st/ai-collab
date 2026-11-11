"""query plan 契约模型的边界测试（W4 冻结文档 §1 的逐项 ±1 校验）。"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.query_plan import (
    MAX_DOCUMENT_IDS,
    MAX_HISTORY_SUMMARY_CHARS,
    MAX_QUERY_CHARS,
    MAX_SUB_QUERIES,
    QueryPlanDateRange,
    QueryPlanFilters,
    QueryPlanRequest,
    QueryPlanResponse,
)


def _request(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "query": "项目架构是什么",
        "locale": "zh-CN",
    }
    payload.update(overrides)
    return payload


def _response(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "rewrittenQuery": "项目架构 设计 决策",
        "subQueries": ["架构决策", "模块边界"],
        "filters": {"documentIds": [], "docTypes": [], "dateRange": None},
        "confidence": 0.8,
    }
    payload.update(overrides)
    return payload


def test_request_defaults_match_frozen_contract() -> None:
    request = QueryPlanRequest.model_validate(_request())

    assert request.schema_version == 1
    assert request.history_summary == ""
    assert request.document_ids == ()
    assert request.deadline_ms == 8000


def test_query_boundaries() -> None:
    assert QueryPlanRequest.model_validate(_request(query="x" * MAX_QUERY_CHARS)).query
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(query="x" * (MAX_QUERY_CHARS + 1)))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(query=""))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(query="   "))


def test_locale_boundaries_and_shape() -> None:
    assert QueryPlanRequest.model_validate(_request(locale="zh-CN")).locale == "zh-CN"
    assert QueryPlanRequest.model_validate(_request(locale="en")).locale == "en"
    assert QueryPlanRequest.model_validate(_request(locale="pt-BR")).locale == "pt-BR"

    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(locale="z"))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(locale="zh_CN"))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(locale="en-" + "a" * 33))
    # 35 字符且形状合法 → 接受；36 字符即使形状合法也越界。
    at_limit = "en-" + "a" * 8 + "-" + "b" * 8 + "-" + "c" * 8 + "-" + "d" * 5
    assert len(at_limit) == 35
    assert QueryPlanRequest.model_validate(_request(locale=at_limit)).locale == at_limit
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(locale=at_limit + "e"))


def test_history_summary_boundaries() -> None:
    assert (
        QueryPlanRequest.model_validate(
            _request(historySummary="h" * MAX_HISTORY_SUMMARY_CHARS)
        ).history_summary
        == "h" * MAX_HISTORY_SUMMARY_CHARS
    )
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(
            _request(historySummary="h" * (MAX_HISTORY_SUMMARY_CHARS + 1))
        )


def test_document_ids_boundaries_and_uniqueness() -> None:
    ids = [f"doc-{index}" for index in range(MAX_DOCUMENT_IDS)]
    assert (
        len(QueryPlanRequest.model_validate(_request(documentIds=ids)).document_ids)
        == MAX_DOCUMENT_IDS
    )
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(documentIds=[*ids, "doc-extra"]))
    assert (
        QueryPlanRequest.model_validate(_request(documentIds=["d" * 128])).document_ids[0]
        == "d" * 128
    )
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(documentIds=["d" * 129]))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(documentIds=["doc-1", "doc-1"]))


def test_deadline_boundaries() -> None:
    assert QueryPlanRequest.model_validate(_request(deadlineMs=1)).deadline_ms == 1
    assert QueryPlanRequest.model_validate(_request(deadlineMs=30000)).deadline_ms == 30000
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(deadlineMs=0))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(deadlineMs=30001))


def test_schema_version_and_extra_fields_are_strict() -> None:
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(schemaVersion=True))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(schemaVersion=2))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(deadlineMs=True))
    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(_request(unexpected="x"))


def test_filters_allow_only_three_keys() -> None:
    filters = QueryPlanFilters.model_validate(
        {
            "documentIds": ["doc-1"],
            "docTypes": ["SPEC"],
            "dateRange": {"from": "2026-01-01", "to": "2026-06-30"},
        }
    )
    assert filters.document_ids == ("doc-1",)
    assert filters.doc_types == ("SPEC",)
    assert filters.date_range is not None

    with pytest.raises(ValidationError):
        QueryPlanFilters.model_validate({"documentIds": [], "unknown": True})
    with pytest.raises(ValidationError):
        QueryPlanFilters.model_validate({"docTypes": [f"T{index}" for index in range(11)]})
    with pytest.raises(ValidationError):
        QueryPlanFilters.model_validate({"docTypes": ["d" * 65]})


def test_date_range_boundaries_and_order() -> None:
    empty = QueryPlanDateRange.model_validate({"from": None, "to": None})
    assert (empty.from_, empty.to) == (None, None)
    assert QueryPlanDateRange.model_validate({"from": "2026-01-01"}).to is None
    assert QueryPlanDateRange.model_validate({"from": "2026-01-01", "to": "2026-01-01"})
    assert (
        QueryPlanDateRange.model_validate({"from": "2026-01-01", "to": "2026-06-30"}).from_
        == "2026-01-01"
    )

    with pytest.raises(ValidationError):
        QueryPlanDateRange.model_validate({"from": "2026-06-30", "to": "2026-01-01"})
    with pytest.raises(ValidationError):
        QueryPlanDateRange.model_validate({"from": "2026-02-30"})
    with pytest.raises(ValidationError):
        QueryPlanDateRange.model_validate({"from": "20260101"})
    with pytest.raises(ValidationError):
        QueryPlanDateRange.model_validate({"from": "2026-1-1"})
    with pytest.raises(ValidationError):
        QueryPlanDateRange.model_validate({"from": "abcd-ef-gh"})


def test_response_rewritten_query_boundaries() -> None:
    assert QueryPlanResponse.model_validate(_response(rewrittenQuery="q" * 4000)).rewritten_query
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(rewrittenQuery="q" * 4001))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(rewrittenQuery=""))


def test_response_sub_queries_boundaries_and_uniqueness() -> None:
    at_limit = [f"sub-{index}" for index in range(MAX_SUB_QUERIES)]
    assert len(QueryPlanResponse.model_validate(_response(subQueries=at_limit)).sub_queries) == 8
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(subQueries=[*at_limit, "extra"]))
    assert QueryPlanResponse.model_validate(_response(subQueries=["s" * 500])).sub_queries
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(subQueries=["s" * 501]))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(subQueries=["same", "same"]))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(subQueries=[""]))


def test_response_confidence_boundaries() -> None:
    assert QueryPlanResponse.model_validate(_response(confidence=0.0)).confidence == 0.0
    assert QueryPlanResponse.model_validate(_response(confidence=1.0)).confidence == 1.0
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(confidence=-0.1))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(confidence=1.1))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(confidence=float("nan")))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(confidence=float("inf")))
    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(_response(confidence=True))


def test_response_filters_are_strict_on_wire() -> None:
    response = QueryPlanResponse.model_validate(_response())
    payload = response.model_dump(by_alias=True, mode="json")
    assert set(payload) == {
        "schemaVersion",
        "rewrittenQuery",
        "subQueries",
        "filters",
        "confidence",
    }
    assert set(payload["filters"]) == {"documentIds", "docTypes", "dateRange"}
    assert payload["schemaVersion"] == 1

    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(
            _response(filters={"documentIds": [], "docTypes": [], "dateRange": None, "x": 1})
        )
