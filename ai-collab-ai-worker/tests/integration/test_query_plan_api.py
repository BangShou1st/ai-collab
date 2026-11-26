"""``POST /v1/rag/plan`` 集成测试（离线，respx 拦截 Java 网关）。

覆盖：成功规划、Prompt 渲染、filters.documentIds 子集过滤与指标、模型输出非法、
端到端 deadline 与服务端上限、网关 429/5xx/非法 JSON、鉴权、指标暴露与不泄漏正文。
所有测试零网络、零真实密钥；令牌由测试内存中的固定 Ed25519 私钥签发（scope ``plan``）。
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from pathlib import Path

import httpx
import pytest
import respx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.observability.metrics import REGISTRY

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
GATEWAY_URL = "http://gateway.test"
_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("55" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _mint(*, scope: str = "plan model-call", exp_offset: int = 3600) -> str:
    now = int(time.time())
    header = {"alg": "EdDSA", "typ": "JWT", "kid": "k1"}
    payload = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["ai-worker", "backend"],
        "scope": scope,
        "jti": "integration-plan-jti",
        "iat": now - 5,
        "exp": now + exp_offset,
    }
    header_segment = _b64(json.dumps(header, separators=(",", ":")).encode())
    payload_segment = _b64(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header_segment}.{payload_segment}"
    return f"{signing_input}.{_b64(_KEY.sign(signing_input.encode('ascii')))}"


TOKEN = _mint()
MODEL_PATH = "/internal/ai/v1/model-calls"


def _app(**overrides: object) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        ed25519_public_keys=f"k1={_PUBLIC_SPKI}",
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


def test_missing_token_is_rejected(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=None)

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_AUTH_FAILED"
    assert route.call_count == 0


def test_missing_plan_scope_is_rejected(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=_mint(scope="model-call"))

    assert response.status_code == 401
    assert route.call_count == 0


def test_wrong_token_is_rejected(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token="wrong-token")

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_AUTH_FAILED"
    assert TOKEN not in response.text
    assert route.call_count == 0


def test_auth_failure_never_leaks_token_to_logs_or_response(
    app: FastAPI, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.DEBUG)
    rejected = (_mint(scope="model-call"), _mint(exp_offset=-400))
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            responses = [_post(client, token=token) for token in rejected]

    assert route.call_count == 0
    assert "内部服务鉴权失败" in caplog.text
    for token, response in zip(rejected, responses, strict=True):
        assert response.status_code == 401
        for segment in token.split("."):
            assert segment not in response.text
            assert segment not in caplog.text


_OTHER_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("66" * 32))
_HEADER: dict[str, object] = {"alg": "EdDSA", "typ": "JWT", "kid": "k1"}


def _claims(**overrides: object) -> dict[str, object]:
    now = int(time.time())
    payload: dict[str, object] = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["ai-worker", "backend"],
        "scope": "plan model-call",
        "jti": "integration-plan-auth-jti",
        "iat": now - 5,
        "exp": now + 3600,
    }
    payload.update(overrides)
    return payload


def _sign_raw(
    header: dict[str, object],
    payload: dict[str, object],
    *,
    key: Ed25519PrivateKey = _KEY,
) -> str:
    header_segment = _b64(json.dumps(header, separators=(",", ":")).encode())
    payload_segment = _b64(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header_segment}.{payload_segment}"
    return f"{signing_input}.{_b64(key.sign(signing_input.encode('ascii')))}"


_VALID_PARTS = TOKEN.split(".")
_AUTH_FAILURE_CASES: list[tuple[str, str | None]] = [
    ("malformed", None),
    ("malformed", "static-service-token-LEAKME"),
    ("malformed", f"{_VALID_PARTS[0]}.{_VALID_PARTS[1]}"),
    ("alg", _sign_raw({**_HEADER, "alg": "HS256"}, _claims())),
    ("unknown_kid", _sign_raw({**_HEADER, "kid": "k9"}, _claims())),
    ("bad_signature", _sign_raw(_HEADER, _claims(), key=_OTHER_KEY)),
    ("subject", _sign_raw(_HEADER, _claims(sub="someone-else"))),
    ("audience", _sign_raw(_HEADER, _claims(aud=["backend"]))),
    ("scope", _sign_raw(_HEADER, _claims(scope="model-call"))),
    (
        "expired",
        _sign_raw(_HEADER, _claims(iat=int(time.time()) - 900, exp=int(time.time()) - 400)),
    ),
]


@pytest.mark.parametrize(
    ("reason", "token"),
    _AUTH_FAILURE_CASES,
    ids=[f"{reason}-{index}" for index, (reason, _token) in enumerate(_AUTH_FAILURE_CASES)],
)
def test_every_auth_failure_reason_uses_internal_auth_failed(
    app: FastAPI,
    caplog: pytest.LogCaptureFixture,
    reason: str,
    token: str | None,
) -> None:
    caplog.set_level(logging.DEBUG)
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 401
    assert response.json() == {
        "code": "INTERNAL_AUTH_FAILED",
        "message": "内部服务鉴权失败",
        "retryable": False,
        "details": {"reason": reason},
    }
    assert route.call_count == 0
    assert f"reason={reason}" in caplog.text
    if token is not None:
        for segment in token.split("."):
            assert segment not in response.text
            assert segment not in caplog.text


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
    assert "/v1/rag/rerank" not in paths
    post = paths["/v1/rag/plan"]["post"]
    assert post["responses"]["200"]["content"]["application/json"]["schema"]
