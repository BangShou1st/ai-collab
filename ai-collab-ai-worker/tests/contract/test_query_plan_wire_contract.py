"""query plan 的跨语言线缆契约：字段名、快照与 filters 白名单。

快照是 Java 侧对齐的唯一锚点：这里逐字段断言生成结果，任何"顺手改名"都会在这里红。
"""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.query_plan import (
    QueryPlanFilters,
    QueryPlanRequest,
    QueryPlanResponse,
)
from ai_collab_worker.contract.schema import SCHEMA_MODELS, render_schema


def _schema(name: str) -> dict[str, object]:
    return json.loads(render_schema(SCHEMA_MODELS[name]))


def _request() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "query": "项目架构是什么",
        "locale": "zh-CN",
        "historySummary": "",
        "documentIds": ["doc-1"],
        "deadlineMs": 8000,
    }


def test_request_wire_fields_are_exact() -> None:
    schema = _schema("query-plan-request")
    assert set(schema["properties"]) == {
        "schemaVersion",
        "query",
        "locale",
        "historySummary",
        "documentIds",
        "deadlineMs",
    }
    assert set(schema["required"]) == {"query", "locale"}
    assert schema["additionalProperties"] is False

    dumped = QueryPlanRequest.model_validate(_request()).model_dump(by_alias=True, mode="json")
    assert set(dumped) == set(schema["properties"])


def test_response_wire_fields_are_exact() -> None:
    schema = _schema("query-plan-response")
    assert set(schema["properties"]) == {
        "schemaVersion",
        "rewrittenQuery",
        "subQueries",
        "filters",
        "confidence",
    }
    assert schema["additionalProperties"] is False

    response = QueryPlanResponse.model_validate(
        {
            "rewrittenQuery": "架构 决策",
            "subQueries": [],
            "filters": {"documentIds": [], "docTypes": [], "dateRange": None},
            "confidence": 0.5,
        }
    )
    dumped = response.model_dump(by_alias=True, mode="json")
    assert set(dumped) == set(schema["properties"])
    assert set(dumped["filters"]) == {"documentIds", "docTypes", "dateRange"}


def test_filters_schema_has_exactly_three_keys_and_from_to() -> None:
    schema = _schema("query-plan-response")
    definitions = schema["$defs"]

    assert set(definitions["QueryPlanFilters"]["properties"]) == {
        "documentIds",
        "docTypes",
        "dateRange",
    }
    assert definitions["QueryPlanFilters"]["additionalProperties"] is False
    assert set(definitions["QueryPlanDateRange"]["properties"]) == {"from", "to"}
    assert definitions["QueryPlanDateRange"]["additionalProperties"] is False


def test_unknown_filter_keys_are_rejected_on_the_wire() -> None:
    with pytest.raises(ValidationError):
        QueryPlanFilters.model_validate({"documentIds": [], "unknownKey": []})

    with pytest.raises(ValidationError):
        QueryPlanResponse.model_validate(
            {
                "rewrittenQuery": "x",
                "subQueries": [],
                "filters": {"documentIds": [], "docTypes": [], "dateRange": None, "x": 1},
                "confidence": 0.5,
            }
        )


def test_request_extra_fields_are_rejected_on_the_wire() -> None:
    payload = _request()
    payload["projectId"] = "p-1"

    with pytest.raises(ValidationError):
        QueryPlanRequest.model_validate(payload)


def test_confidence_semantics_belong_to_java() -> None:
    """Python 只返回 confidence，不做 <0.5 的降级（契约注释与 README 均写明）。"""

    low = QueryPlanResponse.model_validate(
        {
            "rewrittenQuery": "原查询",
            "subQueries": [],
            "filters": {},
            "confidence": 0.2,
        }
    )
    assert low.confidence == 0.2
    assert low.rewritten_query == "原查询"
