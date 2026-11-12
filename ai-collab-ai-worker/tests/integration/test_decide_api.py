"""``POST /v1/agent/decide`` 集成测试（离线，respx 拦截 Java 网关）。

覆盖：四决策、Prompt 渲染与版本、内部纠错重试一次、越界工具名、其它决策带 toolCall、
幂等重放/同键不同 body、deadline 与服务端上限、prompt 64 KiB、网关 429/5xx/非法 JSON、
usage/latency 缺失、鉴权、指标与不泄漏正文。
"""

from __future__ import annotations

import asyncio
import json
import logging
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
PROMPT_VERSION = "2026-11-12.2"

CALL_TOOL_CONTENT = json.dumps(
    {
        "decision": "CALL_TOOL",
        "toolCall": {"name": "search_project_knowledge", "arguments": {"query": "项目架构"}},
    },
    ensure_ascii=False,
)
FINAL_CONTENT = '{"decision":"FINAL"}'
ASK_USER_CONTENT = '{"decision":"ASK_USER"}'
NO_OP_CONTENT = '{"decision":"NO_OP"}'
INVALID_TOOL_CONTENT = json.dumps(
    {
        "decision": "CALL_TOOL",
        "toolCall": {"name": "delete_all_tasks", "arguments": {}},
    },
    ensure_ascii=False,
)
OTHER_DECISION_WITH_TOOL = json.dumps(
    {
        "decision": "FINAL",
        "toolCall": {"name": "search_project_knowledge", "arguments": {}},
    }
)


def _app(**overrides: object) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        service_token=TOKEN,
        java_gateway_url=GATEWAY_URL,
        **overrides,
    )
    return create_app(settings)


def _tools() -> list[dict[str, object]]:
    return [
        {
            "name": "search_project_knowledge",
            "description": "在项目知识库中检索",
            "inputSchema": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
            "risk": "READ_ONLY",
        },
        {
            "name": "get_project_snapshot",
            "description": "读取项目快照",
            "inputSchema": {"type": "object", "properties": {}},
            "risk": "READ_ONLY",
        },
    ]


def _body(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "schemaVersion": 1,
        "runId": "run-1",
        "attempt": 2,
        "turnNo": 5,
        "snapshotHash": "sha256:abc123",
        "scenario": {
            "code": "PROJECT_RESEARCH",
            "category": "SCENARIO",
            "promptPack": "scenario.project_research@2026-11-11.1",
        },
        "goal": "研究项目架构",
        "locale": "zh-CN",
        "context": {
            "facts": ["项目使用 Java 控制面 + Python 认知面。"],
            "messages": [{"role": "USER", "content": "研究一下架构决策。"}],
            "observations": [{"tool": "search_project_knowledge", "summary": "命中 2 条文档。"}],
        },
        "tools": _tools(),
        "policy": {"allowWrite": False, "allowExternal": False},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
    }
    body.update(overrides)
    return body


def _gateway_payload(
    content: str = CALL_TOOL_CONTENT,
    *,
    usage: object = "default",
    latency: object = 120,
) -> dict[str, object]:
    payload: dict[str, object] = {"schemaVersion": 1, "content": content}
    if usage == "default":
        payload["usage"] = {"inputTokens": 8123, "outputTokens": 96, "source": "PROVIDER"}
    elif usage is not None:
        payload["usage"] = usage
    if latency is not None:
        payload["latencyMs"] = latency
    return payload


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
    return client.post("/v1/agent/decide", json=body or _body(), headers=headers)


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


@pytest.fixture
def app() -> FastAPI:
    return _app()


def test_successful_decide_returns_full_contract(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, traceparent="00-trace-span-02")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {
        "schemaVersion",
        "decision",
        "toolCall",
        "usage",
        "promptVersion",
        "modelAlias",
        "latencyMs",
    }
    assert body["schemaVersion"] == 1
    assert body["decision"] == "CALL_TOOL"
    assert body["toolCall"] == {
        "name": "search_project_knowledge",
        "arguments": {"query": "项目架构"},
    }
    assert body["usage"] == {"inputTokens": 8123, "outputTokens": 96, "source": "PROVIDER"}
    assert body["promptVersion"] == PROMPT_VERSION
    assert body["modelAlias"] == "agent-main"
    assert body["latencyMs"] == 120
    for forbidden in ("delta", "stream", "streaming", "token", "chunk"):
        assert forbidden not in body

    gateway_request = route.calls[0].request
    assert gateway_request.headers["authorization"] == f"Bearer {TOKEN}"
    assert gateway_request.headers["traceparent"] == "00-trace-span-02"
    gateway_body = json.loads(gateway_request.content)
    assert gateway_body["outputFormat"] == "PROMPT_JSON"
    assert gateway_body["purpose"] == "AGENT"
    # deadline 按剩余预算向下取整，允许 1ms 内的截断误差。
    assert 29_990 <= gateway_body["deadlineMs"] <= 30_000
    assert "PROJECT_RESEARCH" in gateway_body["systemPrompt"]
    assert "研究项目架构" in gateway_body["systemPrompt"]
    assert "项目使用 Java 控制面" in gateway_body["userPrompt"]
    assert "search_project_knowledge" in gateway_body["userPrompt"]
    assert "CALL_TOOL" in gateway_body["systemPrompt"]


@pytest.mark.parametrize(
    ("content", "decision"),
    [
        (FINAL_CONTENT, "FINAL"),
        (ASK_USER_CONTENT, "ASK_USER"),
        (NO_OP_CONTENT, "NO_OP"),
    ],
)
def test_non_call_tool_decisions_have_null_tool_call(
    app: FastAPI, content: str, decision: str
) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload(content))
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == decision
    assert body["toolCall"] is None


def test_invalid_tool_name_retries_once_then_succeeds(app: FastAPI) -> None:
    retry_before = _metric("ai_worker_decide_retry_total")
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            side_effect=[
                httpx.Response(200, json=_gateway_payload(INVALID_TOOL_CONTENT)),
                httpx.Response(200, json=_gateway_payload(CALL_TOOL_CONTENT)),
            ]
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    assert response.json()["decision"] == "CALL_TOOL"
    assert route.call_count == 2
    second_request = route.calls[1].request
    second_body = json.loads(second_request.content)
    assert "<CORRECTION>" in second_body["userPrompt"]
    assert "上一次输出未通过校验" in second_body["userPrompt"]
    assert second_body["systemPrompt"] == json.loads(route.calls[0].request.content)["systemPrompt"]
    assert second_request.headers["x-call-id"].endswith(":retry")
    assert _metric("ai_worker_decide_retry_total") == retry_before + 1


def test_invalid_tool_name_twice_returns_422_and_counts_metrics(app: FastAPI) -> None:
    retry_before = _metric("ai_worker_decide_retry_total")
    invalid_before = _metric("ai_worker_decide_invalid_tool_call_total")
    violation_before = _metric("ai_worker_decide_contract_violation_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            side_effect=[
                httpx.Response(200, json=_gateway_payload(INVALID_TOOL_CONTENT)),
                httpx.Response(200, json=_gateway_payload(INVALID_TOOL_CONTENT)),
            ]
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert "delete_all_tasks" not in response.text
    assert route.call_count == 2
    assert _metric("ai_worker_decide_retry_total") == retry_before + 1
    assert _metric("ai_worker_decide_invalid_tool_call_total") == invalid_before + 1
    assert _metric("ai_worker_decide_contract_violation_total") == violation_before + 1


@pytest.mark.parametrize("content", ["not-json", '{"decision": "DELEGATE"}'])
def test_invalid_model_output_is_retried_and_returns_422(app: FastAPI, content: str) -> None:
    violation_before = _metric("ai_worker_decide_contract_violation_total")
    invalid_tool_before = _metric("ai_worker_decide_invalid_tool_call_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            side_effect=[
                httpx.Response(200, json=_gateway_payload(content)),
                httpx.Response(200, json=_gateway_payload(content)),
            ]
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    assert response.json()["code"] == "SCHEMA_VIOLATION"
    assert route.call_count == 2
    assert _metric("ai_worker_decide_contract_violation_total") == violation_before + 1
    assert _metric("ai_worker_decide_invalid_tool_call_total") == invalid_tool_before


def test_other_decision_with_tool_call_returns_422(app: FastAPI) -> None:
    violation_before = _metric("ai_worker_decide_contract_violation_total")
    invalid_tool_before = _metric("ai_worker_decide_invalid_tool_call_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            side_effect=[
                httpx.Response(200, json=_gateway_payload(OTHER_DECISION_WITH_TOOL)),
                httpx.Response(200, json=_gateway_payload(OTHER_DECISION_WITH_TOOL)),
            ]
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    assert route.call_count == 2
    assert _metric("ai_worker_decide_contract_violation_total") == violation_before + 1
    assert _metric("ai_worker_decide_invalid_tool_call_total") == invalid_tool_before


def test_idempotent_replay_returns_identical_bytes_without_gateway_call(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client)
            second = _post(client)

    assert first.status_code == second.status_code == 200
    assert first.content == second.content
    assert route.call_count == 1


def test_same_key_with_changed_deadline_replays_instead_of_conflict(app: FastAPI) -> None:
    body = _body()
    shorter = _body(budget={"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 500})
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client, body)
            second = _post(client, shorter)

    assert first.status_code == second.status_code == 200
    assert first.content == second.content
    assert route.call_count == 1


def test_same_key_different_body_returns_400(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client)
            second = _post(client, _body(goal="完全不同的问题"))

    assert first.status_code == 200
    assert second.status_code == 400
    assert second.json()["code"] == "INVALID_REQUEST"
    assert route.call_count == 1


def test_different_idempotency_tuple_does_not_replay(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client)
            second = _post(client, _body(turnNo=6))

    assert first.status_code == second.status_code == 200
    assert route.call_count == 2


def test_end_to_end_deadline_returns_504(app: FastAPI) -> None:
    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(
                client,
                _body(budget={"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 50}),
            )

    assert response.status_code == 504
    body = response.json()
    assert body["code"] == "DEADLINE_EXCEEDED"
    assert body["retryable"] is True
    assert "decision" not in body


def test_server_deadline_cap_is_applied() -> None:
    app = _app(decide_deadline_ms=50)

    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 504
    if route.calls:
        gateway_body = json.loads(route.calls[0].request.content)
        assert gateway_body["deadlineMs"] <= 50


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


def test_gateway_invalid_json_returns_model_unavailable(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, text="<html>oops</html>"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_missing_usage_and_latency_are_unknown_not_zero(app: FastAPI) -> None:
    payload = _gateway_payload(usage=None, latency=None)

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=payload))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["usage"] == {
        "inputTokens": None,
        "outputTokens": None,
        "source": "UNKNOWN",
    }
    assert body["latencyMs"] is None


def test_large_prompt_is_rejected_with_invalid_request_before_gateway_call() -> None:
    big_schema = {"type": "object", "padding": "x" * 8000}
    tools = [
        {
            "name": f"tool_{index}",
            "description": "工具",
            "inputSchema": big_schema,
            "risk": "READ_ONLY",
        }
        for index in range(32)
    ]
    app = _app()
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, _body(tools=tools))

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "INVALID_REQUEST"
    assert body["details"] == {"limitBytes": 64 * 1024}
    assert route.call_count == 0


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


def test_metrics_and_response_do_not_leak_goal_or_token(
    app: FastAPI, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="ai_collab_worker.api.decide")
    secret = "目标-SECRET-4c2f"

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=_gateway_payload()))
        with TestClient(app) as client:
            response = _post(client, _body(goal=secret))
            metrics = client.get("/metrics")

    assert response.status_code == 200
    assert secret not in response.text
    assert secret not in metrics.text
    assert TOKEN not in metrics.text
    assert secret not in caplog.text
    assert "ai_worker_decide_duration_seconds" in metrics.text
    assert "ai_worker_decide_retry_total" in metrics.text
    assert "ai_worker_decide_invalid_tool_call_total" in metrics.text
    assert "ai_worker_decide_contract_violation_total" in metrics.text


def test_metrics_count_success_decision_label(app: FastAPI) -> None:
    before = _metric(
        "ai_worker_decide_duration_seconds_count", {"outcome": "ok", "decision": "CALL_TOOL"}
    )

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=_gateway_payload()))
        with TestClient(app) as client:
            assert _post(client).status_code == 200

    assert (
        _metric(
            "ai_worker_decide_duration_seconds_count",
            {"outcome": "ok", "decision": "CALL_TOOL"},
        )
        == before + 1
    )


def test_openapi_documents_decide_route(app: FastAPI) -> None:
    with TestClient(app) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/agent/decide" in paths
    post = paths["/v1/agent/decide"]["post"]
    assert post["responses"]["200"]["content"]["application/json"]["schema"]
