"""Rerank 契约快照与错误信封（contract）。

``jsonschema`` 不在本项目依赖树中（设计要求也只在"已有依赖"时使用），因此按 P0 既有方式
校验：入库快照必须等于模型重生成结果，并逐项断言关键约束；示例负载再用 Pydantic 模型
（真正的消费方）做一次正反校验。
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai_collab_worker.config import Settings
from ai_collab_worker.contract.rerank import RerankRequest, RerankResponse
from ai_collab_worker.contract.schema import (
    SCHEMA_MODELS,
    check,
    render_schema,
    snapshot_path,
)
from ai_collab_worker.main import create_app

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
TOKEN = "contract-test-token"

REQUEST_SCHEMA = "rerank-request"
RESPONSE_SCHEMA = "rerank-response"


def _load_snapshot(name: str) -> dict[str, object]:
    payload = json.loads(snapshot_path(name).read_text(encoding="utf-8"))
    assert isinstance(payload, dict)
    return payload


def test_rerank_snapshots_are_registered_and_match_models() -> None:
    assert {"rerank-request", "rerank-response"} <= set(SCHEMA_MODELS)
    assert check() == []
    for name in (REQUEST_SCHEMA, RESPONSE_SCHEMA):
        assert snapshot_path(name).is_file()
        assert snapshot_path(name).read_text(encoding="utf-8") == render_schema(SCHEMA_MODELS[name])


def test_request_snapshot_declares_frozen_bounds() -> None:
    schema = _load_snapshot(REQUEST_SCHEMA)
    properties = schema["properties"]

    assert schema["title"] == "RerankRequest"
    assert schema["additionalProperties"] is False
    assert properties["schemaVersion"]["const"] == 1
    assert properties["schemaVersion"]["default"] == 1
    assert properties["query"]["minLength"] == 1
    assert properties["query"]["maxLength"] == 4000
    assert properties["topK"]["default"] == 8
    assert properties["topK"]["minimum"] == 1
    assert properties["topK"]["maximum"] == 50
    assert properties["candidates"]["minItems"] == 1
    assert properties["candidates"]["maxItems"] == 50
    assert properties["deadlineMs"]["default"] == 3000
    assert properties["deadlineMs"]["minimum"] == 1
    assert properties["deadlineMs"]["maximum"] == 30000


def test_candidate_definition_declares_frozen_bounds() -> None:
    candidate = _load_snapshot(REQUEST_SCHEMA)["$defs"]["RerankCandidate"]

    assert candidate["additionalProperties"] is False
    assert set(candidate["properties"]) == {"id", "title", "text"}
    assert candidate["properties"]["id"]["minLength"] == 1
    assert candidate["properties"]["id"]["maxLength"] == 128
    assert candidate["properties"]["title"]["maxLength"] == 500
    assert candidate["properties"]["title"]["default"] == ""
    assert candidate["properties"]["text"]["minLength"] == 1
    assert candidate["properties"]["text"]["maxLength"] == 2000
    assert set(candidate["required"]) == {"id", "text"}


def test_response_snapshot_uses_camel_case_and_closed_score_interval() -> None:
    schema = _load_snapshot(RESPONSE_SCHEMA)
    properties = schema["properties"]

    assert schema["title"] == "RerankResponse"
    assert schema["additionalProperties"] is False
    assert set(properties) == {"schemaVersion", "scores", "modelAlias", "usage", "modelLatencyMs"}
    assert properties["schemaVersion"]["const"] == 1
    assert properties["scores"]["minItems"] == 1
    assert properties["scores"]["maxItems"] == 50
    # W3.1 冻结 §1.4：只加字段、schemaVersion 仍为 1；modelLatencyMs 可空且非负。
    model_latency = properties["modelLatencyMs"]
    assert model_latency["default"] is None
    assert {entry["type"] for entry in model_latency["anyOf"]} == {"integer", "null"}
    assert model_latency["anyOf"][0]["minimum"] == 0
    assert "modelLatencyMs" not in schema["required"]

    score = schema["$defs"]["RerankScore"]
    assert set(score["properties"]) == {"id", "score"}
    assert score["properties"]["score"]["minimum"] == 0.0
    assert score["properties"]["score"]["maximum"] == 1.0

    usage = schema["$defs"]["RerankUsage"]
    assert set(usage["properties"]) == {"inputTokens", "outputTokens", "source"}
    source = schema["$defs"]["RerankUsageSource"]
    assert set(source["enum"]) == {"PROVIDER", "ESTIMATE", "UNKNOWN"}


def test_snapshots_accept_valid_payloads_and_reject_extra_fields() -> None:
    valid_request = {
        "schemaVersion": 1,
        "query": "核心架构",
        "topK": 8,
        "candidates": [{"id": "c1", "title": "", "text": "正文"}],
        "deadlineMs": 3000,
    }
    assert RerankRequest.model_validate(valid_request).top_k == 8
    with pytest.raises(ValidationError, match="Extra inputs"):
        RerankRequest.model_validate({**valid_request, "unexpected": 1})

    valid_response = {
        "schemaVersion": 1,
        "scores": [{"id": "c1", "score": 0.5}],
        "modelAlias": "rerank-default",
        "usage": {"inputTokens": 1, "outputTokens": 2, "source": "PROVIDER"},
        "modelLatencyMs": 812,
    }
    parsed = RerankResponse.model_validate(valid_response)
    assert parsed.model_alias == "rerank-default"
    assert parsed.model_latency_ms == 812
    # 旧 worker 不发 modelLatencyMs 也必须能被新 Java/契约消费（宽松解析）。
    without_latency = dict(valid_response)
    without_latency.pop("modelLatencyMs")
    assert RerankResponse.model_validate(without_latency).model_latency_ms is None


@pytest.fixture
def client() -> Iterator[TestClient]:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token=TOKEN,
            java_gateway_url="http://gateway.invalid",
        )
    )
    with TestClient(app) as test_client:
        yield test_client


def test_validation_error_is_four_field_envelope(client: TestClient) -> None:
    candidates = [{"id": f"c{index}", "text": "正文"} for index in range(51)]
    response = client.post(
        "/v1/rag/rerank",
        json={"query": "q", "candidates": candidates},
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 422
    body = response.json()
    assert set(body) == {"code", "message", "retryable", "details"}
    assert body["code"] == "INVALID_REQUEST"
    assert body["retryable"] is False
    assert body["details"]["errors"]


def test_auth_failure_uses_three_field_envelope(client: TestClient) -> None:
    response = client.post(
        "/v1/rag/rerank",
        json={"query": "q", "candidates": [{"id": "c1", "text": "正文"}]},
    )

    assert response.status_code == 401
    body = response.json()
    assert set(body) == {"code", "message", "retryable"}
    assert body["code"] == "MODEL_UNAVAILABLE"
    assert body["retryable"] is False
    assert TOKEN not in response.text
