"""``POST /v1/rag/plan`` 集成测试（离线，respx 拦截 Java 网关）。

覆盖：成功规划、Prompt 渲染、filters.documentIds 子集过滤与指标、模型输出非法、
端到端 deadline 与服务端上限、网关 429/5xx/非法 JSON、鉴权、指标暴露与不泄漏正文。
所有测试零网络、零密钥；token 只作为测试常量出现在请求头里。
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
import respx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.observability.metrics import REGISTRY

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
GATEWAY_URL = "http://gateway.test"
TOKEN = "integration-test-service-token"
MODEL_PATH = "/internal/ai/v1/model-calls"


def _app(**overrides: object) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        service_token=TOKEN,
        java_gateway_url=GATEWAY_URL,
        **overrides,
    )
    return create_app(settings)


def _plan_payload(**overrides: object) -> str:
    payload: dict[str, object] = {
        "rewrittenQuery": "项目架构 设计 决策",
        "subQueries": ["架构决策", "模块边界"],
        "filters": {
            "documentIds": ["doc-1"],
            "docTypes": ["SPEC"],
            "dateRange": {"from": "2026-01-01", "to": "2026-06-30"},
        },
        "confidence": 0.78,
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False)


def _request_body(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "schemaVersion": 1,
        "query": "项目架构是什么",
        "locale": "zh-CN",
        "historySummary": "上一轮讨论了 rerank。",
        "documentIds": ["doc-1", "doc-2"],
        "deadlineMs": 3000,
    }
    body.update(overrides)
    return body


def _gateway_payload(content: str | None = None) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "content": _plan_payload() if content is None else content,
        "usage": {"inputTokens": 21, "outputTokens": 8, "source": "PROVIDER"},
        "modelAlias": "gateway-model",
        "latencyMs": 120,
    }


def _post(
    client: TestClient,
    body: dict[str, object] | None = None,
    *,
    token: str | None = TOKEN,
    traceparent: str | None = None,
) -> httpx.Response:
    headers: dict[str, str] = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if traceparent is not None:
        headers["traceparent"] = traceparent
    return client.post("/v1/rag/plan", json=body or _request_body(), headers=headers)


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


@pytest.fixture
def app() -> FastAPI:
    return _app()


def test_successful_plan_returns_contract_response(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, traceparent="00-trace-span-01")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "rewrittenQuery", "subQueries", "filters", "confidence"}
    assert body["schemaVersion"] == 1
    assert body["rewrittenQuery"] == "项目架构 设计 决策"
    assert body["subQueries"] == ["架构决策", "模块边界"]
    assert body["filters"] == {
        "documentIds": ["doc-1"],
        "docTypes": ["SPEC"],
        "dateRange": {"from": "2026-01-01", "to": "2026-06-30"},
    }
    assert body["confidence"] == 0.78

    gateway_request = route.calls[0].request
    assert gateway_request.headers["authorization"] == f"Bearer {TOKEN}"
    assert gateway_request.headers["traceparent"] == "00-trace-span-01"
    assert gateway_request.headers["x-call-id"]
    gateway_body = json.loads(gateway_request.content)
    assert gateway_body["outputFormat"] == "PROMPT_JSON"
    assert gateway_body["purpose"] == "KNOWLEDGE_CHAT"
    assert gateway_body["schemaVersion"] == 1
    assert gateway_body["deadlineMs"] == 3000
    assert gateway_body["callId"] == gateway_request.headers["x-call-id"]
    assert "项目架构是什么" in gateway_body["userPrompt"]
    assert '["doc-1","doc-2"]' in gateway_body["userPrompt"]
    assert "zh-CN" in gateway_body["userPrompt"]
    assert "JSON" in gateway_body["systemPrompt"]


def test_unknown_document_ids_are_dropped_and_counted(app: FastAPI) -> None:
    content = _plan_payload(
        filters={"documentIds": ["doc-1", "made-up", "doc-2", "made-up"], "docTypes": []}
    )
    before = _metric("ai_worker_query_plan_unknown_document_ids_total")
    confidence_sum_before = _metric("ai_worker_query_plan_confidence_sum")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload(content))
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    assert response.json()["filters"]["documentIds"] == ["doc-1", "doc-2"]
    # made-up 去重后计 1，而不是按出现次数计 2。
    assert _metric("ai_worker_query_plan_unknown_document_ids_total") == before + 1
    assert _metric("ai_worker_query_plan_confidence_sum") == pytest.approx(
        confidence_sum_before + 0.78
    )


def test_invalid_model_output_returns_schema_violation(app: FastAPI) -> None:
    invalid_before = _metric("ai_worker_query_plan_duration_seconds_count", {"outcome": "invalid"})
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload("not-json"))
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert "not-json" not in response.text
    assert (
        _metric("ai_worker_query_plan_duration_seconds_count", {"outcome": "invalid"})
        == invalid_before + 1
    )


@pytest.mark.parametrize("status", [500, 502, 503, 504])
def test_gateway_server_error_returns_503(app: FastAPI, status: int) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(status, text="upstream down"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "MODEL_UNAVAILABLE"
    assert body["retryable"] is True
    assert "upstream down" not in response.text


def test_gateway_rate_limit_returns_429(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(429, text="slow"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 429
    assert response.json()["code"] == "MODEL_RATE_LIMITED"
    assert response.json()["retryable"] is True


def test_gateway_invalid_json_returns_model_unavailable(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, text="<html>oops</html>"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_end_to_end_deadline_returns_504_without_partial_response(app: FastAPI) -> None:
    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(client, _request_body(deadlineMs=50))

    assert response.status_code == 504
    body = response.json()
    assert body["code"] == "DEADLINE_EXCEEDED"
    assert body["retryable"] is True
    assert "rewrittenQuery" not in body


def test_server_deadline_cap_is_applied() -> None:
    app = _app(query_plan_deadline_ms=50)

    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(client, _request_body(deadlineMs=30000))

    assert response.status_code == 504
    if route.calls:
        gateway_body = json.loads(route.calls[0].request.content)
        assert gateway_body["deadlineMs"] == 50


def test_missing_token_fails_closed_when_not_configured() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            allow_insecure_internal=False,
            java_gateway_url=GATEWAY_URL,
        )
    )
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=None)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert route.call_count == 0


def test_insecure_internal_flag_allows_missing_token() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            allow_insecure_internal=True,
            java_gateway_url=GATEWAY_URL,
        )
    )
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=None)

    assert response.status_code == 200
    assert "authorization" not in route.calls[0].request.headers


def test_wrong_token_is_rejected(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token="wrong-token")

    assert response.status_code == 401
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert TOKEN not in response.text
    assert route.call_count == 0


def test_metrics_are_exposed_and_do_not_leak_query(app: FastAPI) -> None:
    secret = "查询-SECRET-9f3a"
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=_gateway_payload()))
        with TestClient(app) as client:
            assert _post(client, _request_body(query=secret)).status_code == 200
            metrics = client.get("/metrics")

    assert metrics.status_code == 200
    text = metrics.text
    assert "ai_worker_query_plan_duration_seconds" in text
    assert "ai_worker_query_plan_confidence" in text
    assert "ai_worker_query_plan_unknown_document_ids_total" in text
    assert secret not in text
    assert TOKEN not in text


def test_openapi_documents_query_plan_route(app: FastAPI) -> None:
    with TestClient(app) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/rag/plan" in paths
    assert "/v1/rag/rerank" in paths
    post = paths["/v1/rag/plan"]["post"]
    assert post["responses"]["200"]["content"]["application/json"]["schema"]
