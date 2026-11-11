"""query plan 解析器：围栏、字段缺失/类型/越界、filters 白名单与 documentIds 子集。"""

from __future__ import annotations

import json

import pytest

from ai_collab_worker.cognition.query.parser import (
    REASON_EMPTY_OUTPUT,
    REASON_INVALID,
    REASON_NOT_JSON,
    REASON_NOT_OBJECT,
    ParsedQueryPlan,
    parse_query_plan,
)
from ai_collab_worker.contract.common import AppError, ErrorCode


def _payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "rewrittenQuery": "架构决策 检索链路",
        "subQueries": ["架构决策", "检索链路"],
        "filters": {
            "documentIds": ["doc-1"],
            "docTypes": ["SPEC"],
            "dateRange": {"from": "2026-01-01", "to": "2026-06-30"},
        },
        "confidence": 0.78,
    }
    payload.update(overrides)
    return payload


def _parse(content: str, allowed: tuple[str, ...] = ("doc-1", "doc-2")) -> ParsedQueryPlan:
    return parse_query_plan(content, allowed_document_ids=allowed)


def _assert_violation(content: str) -> AppError:
    with pytest.raises(AppError) as excinfo:
        _parse(content)
    error = excinfo.value
    assert error.code is ErrorCode.SCHEMA_VIOLATION
    assert error.status_code == 422
    assert error.retryable is False
    assert error.details is not None
    assert "reason" in error.details
    return error


def test_parses_plain_json_object() -> None:
    parsed = _parse(json.dumps(_payload(), ensure_ascii=False))

    assert parsed.response.rewritten_query == "架构决策 检索链路"
    assert parsed.response.sub_queries == ("架构决策", "检索链路")
    assert parsed.response.filters.document_ids == ("doc-1",)
    assert parsed.response.filters.doc_types == ("SPEC",)
    assert parsed.response.filters.date_range is not None
    assert parsed.response.confidence == 0.78
    assert parsed.unknown_document_ids == ()


def test_allows_markdown_fenced_object() -> None:
    content = "```json\n" + json.dumps(_payload(), ensure_ascii=False) + "\n```"

    assert _parse(content).response.confidence == 0.78
    assert _parse("```\n" + json.dumps(_payload()) + "\n```").response.rewritten_query


def test_unknown_document_ids_are_dropped_and_reported() -> None:
    payload = _payload(
        filters={
            "documentIds": ["doc-1", "made-up", "doc-2", "made-up", "doc-1"],
            "docTypes": ["SPEC"],
        }
    )
    parsed = _parse(json.dumps(payload, ensure_ascii=False))

    assert parsed.response.filters.document_ids == ("doc-1", "doc-2")
    assert parsed.unknown_document_ids == ("made-up",)


def test_document_ids_empty_when_model_omits_them() -> None:
    parsed = _parse(json.dumps(_payload(filters={"docTypes": []})))

    assert parsed.response.filters.document_ids == ()
    assert parsed.unknown_document_ids == ()


def test_top_level_extra_keys_are_ignored() -> None:
    payload = _payload(reasoning="internal chain of thought")

    assert _parse(json.dumps(payload)).response.confidence == 0.78


@pytest.mark.parametrize("missing", ["rewrittenQuery", "subQueries", "filters", "confidence"])
def test_missing_required_field_is_schema_violation(missing: str) -> None:
    payload = _payload()
    del payload[missing]

    error = _assert_violation(json.dumps(payload))
    assert error.details == {"reason": REASON_INVALID}


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        '{"rewrittenQuery": "x", ',
    ],
)
def test_invalid_json_is_schema_violation(content: str) -> None:
    error = _assert_violation(content)
    assert error.details == {"reason": REASON_NOT_JSON}


@pytest.mark.parametrize("content", ["", "   "])
def test_empty_output_reason_is_distinct(content: str) -> None:
    error = _assert_violation(content)

    assert error.details == {"reason": REASON_EMPTY_OUTPUT}


@pytest.mark.parametrize("content", ["[]", '"text"', "42", "null"])
def test_non_object_top_level_is_schema_violation(content: str) -> None:
    error = _assert_violation(content)
    assert error.details == {"reason": REASON_NOT_OBJECT}


@pytest.mark.parametrize(
    "overrides",
    [
        {"rewrittenQuery": 123},
        {"rewrittenQuery": ""},
        {"rewrittenQuery": "q" * 4001},
        {"subQueries": "not-a-list"},
        {"subQueries": ["dup", "dup"]},
        {"subQueries": ["s" * 501]},
        {"subQueries": [f"q{i}" for i in range(9)]},
        {"filters": []},
        {"filters": {"documentIds": [], "docTypes": [], "dateRange": None, "extra": 1}},
        {"filters": {"documentIds": "doc-1"}},
        {"filters": {"documentIds": [123]}},
        {"filters": {"docTypes": ["d" * 65]}},
        {"filters": {"dateRange": {"from": "2026-06-30", "to": "2026-01-01"}}},
        {"filters": {"dateRange": {"from": "2026-02-30"}}},
        {"confidence": -0.01},
        {"confidence": 1.01},
        {"confidence": "high"},
        {"confidence": True},
    ],
)
def test_contract_violations_are_schema_violation(overrides: dict[str, object]) -> None:
    error = _assert_violation(json.dumps(_payload(**overrides), ensure_ascii=False))
    assert error.details == {"reason": REASON_INVALID}


def test_schema_version_from_model_is_not_trusted() -> None:
    payload = _payload(schemaVersion=2)

    error = _assert_violation(json.dumps(payload))
    assert error.details == {"reason": REASON_INVALID}


def test_error_never_echoes_model_content() -> None:
    secret = "MODEL-SECRET-9f3a"
    content = json.dumps(_payload(rewrittenQuery=secret, confidence="high"), ensure_ascii=False)

    with pytest.raises(AppError) as excinfo:
        _parse(content)

    serialized = json.dumps(excinfo.value.details, ensure_ascii=False)
    assert secret not in serialized
    assert secret not in str(excinfo.value)
