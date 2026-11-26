"""``POST /v1/rag/plan`` 集成测试（离线）。

P1 起 query plan 不再经 Java 模型代理：令牌（scope ``plan`` + ``uid``/``pid``）→ 凭据租借
``purpose=QUERY_PLAN``（respx 拦截 Java 内部接口）→ 本地模型客户端（OpenAI 兼容端点，httpx2
``MockTransport`` 回放）→ 用量上报（respx 拦截）。

覆盖：成功规划与各段请求形状、Prompt 渲染、filters.documentIds 子集过滤与指标、模型输出非法、
端到端 deadline 与服务端上限、厂商 429/5xx/非法响应、租借被拒、令牌缺 uid/pid、鉴权失败
（不租借、不调模型）、指标暴露与不泄漏正文/密钥。零网络、零真实密钥。
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import secrets
import time
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx
import httpx2
import pytest
import respx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.api.query_plan import QueryPlanService
from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.observability.metrics import REGISTRY

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
JAVA_URL = "http://java.test"
LEASE_PATH = "/internal/ai/credentials/lease"
USAGE_PATH = "/internal/ai/usage"
PROVIDER_URL = "https://provider.test"
USER_ID = "11111111-1111-4111-8111-111111111111"
PROJECT_ID = "22222222-2222-4222-8222-222222222222"
API_KEY = f"sk-plan-{secrets.token_hex(16)}"

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("55" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _claims(**overrides: object) -> dict[str, object]:
    now = int(time.time())
    payload: dict[str, object] = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["ai-worker", "backend"],
        "scope": "plan credentials:lease usage:report",
        "uid": USER_ID,
        "pid": PROJECT_ID,
        "purp": ["QUERY_PLAN"],
        "jti": "integration-plan-jti",
        "iat": now - 5,
        "exp": now + 3600,
    }
    payload.update(overrides)
    return {k: v for k, v in payload.items() if v is not None}


_HEADER: dict[str, object] = {"alg": "EdDSA", "typ": "JWT", "kid": "k1"}


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


def _mint(**overrides: object) -> str:
    return _sign_raw(_HEADER, _claims(**overrides))


TOKEN = _mint()

ProviderHandler = Callable[[httpx2.Request], Awaitable[httpx2.Response]]


class FakeProvider:
    """OpenAI 兼容 Chat Completions 的假厂商。"""

    def __init__(self) -> None:
        self.requests: list[httpx2.Request] = []
        self.handler: ProviderHandler = self._reply(200, _completion(_plan_payload()))

    @staticmethod
    def _reply(status: int, body: object) -> ProviderHandler:
        async def handler(_request: httpx2.Request) -> httpx2.Response:
            if isinstance(body, str):
                return httpx2.Response(status, text=body)
            return httpx2.Response(status, json=body)

        return handler

    def reply(self, status: int, body: object) -> None:
        self.handler = self._reply(status, body)

    async def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        return await self.handler(request)


def _completion(content: str) -> dict[str, object]:
    return {
        "id": "chatcmpl-plan",
        "object": "chat.completion",
        "created": 1764100000,
        "model": "plan-model",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 21, "completion_tokens": 8, "total_tokens": 29},
    }


def _lease_body() -> dict[str, object]:
    return {
        "leaseId": str(uuid.uuid4()),
        "protocol": "OPENAI_CHAT",
        "providerType": "OLLAMA",
        "baseUrl": PROVIDER_URL,
        "apiPath": "/v1/chat/completions",
        "model": "plan-model",
        "apiKey": API_KEY,
        "params": {"temperature": 0.2},
        "capabilities": ["JSON"],
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
    }


def _usage_reply(request: httpx.Request) -> httpx.Response:
    items = json.loads(request.content)["items"]
    return httpx.Response(
        200, json={"results": [{"callId": i["callId"], "accepted": True} for i in items]}
    )


def _app(provider: FakeProvider, **overrides: object) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        ed25519_public_keys=f"k1={_PUBLIC_SPKI}",
        java_internal_base_url=JAVA_URL,
        **overrides,
    )
    app = create_app(settings)
    # 生产装配的内核不带 transport；测试换成同一 lease 缓存/用量队列、注入假厂商的内核。
    kernel = ModelKernel(
        leases=app.state.lease_cache,
        usage=app.state.usage_reporter,
        runtime=ModelRuntime(
            timeout_seconds=5, max_retries=0, transport=httpx2.MockTransport(provider)
        ),
    )
    app.state.model_kernel = kernel
    app.state.query_plan_service = QueryPlanService(
        settings=settings, kernel=kernel, prompt_store=app.state.prompt_store
    )
    return app


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


def _post(
    client: TestClient,
    body: dict[str, object] | None = None,
    *,
    token: str | None = TOKEN,
) -> httpx.Response:
    headers: dict[str, str] = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    return client.post("/v1/rag/plan", json=body or _request_body(), headers=headers)


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


class Java:
    """respx 路由集合：lease 与 usage。"""

    def __init__(self, router: respx.MockRouter, *, lease_status: int = 200) -> None:
        if lease_status == 200:
            lease_response = httpx.Response(200, json=_lease_body())
        else:
            lease_response = httpx.Response(lease_status, json={"code": "LEASE_FORBIDDEN"})
        self.lease = router.post(LEASE_PATH).mock(return_value=lease_response)
        self.usage = router.post(USAGE_PATH).mock(side_effect=_usage_reply)


def _call(
    provider: FakeProvider,
    body: dict[str, object] | None = None,
    *,
    token: str | None = TOKEN,
    lease_status: int = 200,
    app: FastAPI | None = None,
) -> tuple[httpx.Response, Java]:
    target = app or _app(provider)
    with respx.mock(base_url=JAVA_URL, assert_all_called=False) as router:
        java = Java(router, lease_status=lease_status)
        with TestClient(target) as client:
            response = _post(client, body, token=token)
    return response, java


def _usage_items(java: Java) -> list[dict[str, object]]:
    items: list[dict[str, object]] = []
    for call in java.usage.calls:
        items.extend(json.loads(call.request.content)["items"])
    return items


@pytest.fixture
def provider() -> FakeProvider:
    return FakeProvider()


# --------------------------------------------------------------------------- #
# 成功路径
# --------------------------------------------------------------------------- #


def test_successful_plan_uses_lease_local_model_and_reports_usage(provider: FakeProvider) -> None:
    response, java = _call(provider)

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "rewrittenQuery", "subQueries", "filters", "confidence"}
    assert body["rewrittenQuery"] == "项目架构 设计 决策"
    assert body["filters"]["documentIds"] == ["doc-1"]
    assert body["confidence"] == 0.78

    lease_request = java.lease.calls[0].request
    assert lease_request.headers["authorization"] == f"Bearer {TOKEN}"
    assert json.loads(lease_request.content) == {
        "purpose": "QUERY_PLAN",
        "userId": USER_ID,
        "projectId": PROJECT_ID,
        "taskId": None,
        "runId": None,
    }

    assert len(provider.requests) == 1
    model_request = provider.requests[0]
    assert str(model_request.url) == f"{PROVIDER_URL}/v1/chat/completions"
    assert model_request.headers["authorization"] == f"Bearer {API_KEY}"
    sent = json.loads(model_request.content)
    assert sent["model"] == "plan-model"
    prompt_text = " ".join(str(m["content"]) for m in sent["messages"])
    assert "项目架构是什么" in prompt_text
    assert '["doc-1","doc-2"]' in prompt_text
    assert "zh-CN" in prompt_text
    assert sent["messages"][0]["role"] == "system"

    usage_request = java.usage.calls[0].request
    assert usage_request.headers["authorization"] == f"Bearer {TOKEN}"
    (item,) = _usage_items(java)
    assert item["purpose"] == "QUERY_PLAN"
    assert item["status"] == "SUCCESS"
    assert (item["inputTokens"], item["outputTokens"]) == (21, 8)
    assert item["model"] == "plan-model"
    raw = usage_request.content.decode()
    assert API_KEY not in raw
    assert "项目架构是什么" not in raw


def test_unknown_document_ids_are_dropped_and_counted(provider: FakeProvider) -> None:
    content = _plan_payload(
        filters={"documentIds": ["doc-1", "made-up", "doc-2", "made-up"], "docTypes": []}
    )
    provider.reply(200, _completion(content))
    before = _metric("ai_worker_query_plan_unknown_document_ids_total")
    confidence_sum_before = _metric("ai_worker_query_plan_confidence_sum")

    response, _java = _call(provider)

    assert response.status_code == 200
    assert response.json()["filters"]["documentIds"] == ["doc-1", "doc-2"]
    assert _metric("ai_worker_query_plan_unknown_document_ids_total") == before + 1
    assert _metric("ai_worker_query_plan_confidence_sum") == pytest.approx(
        confidence_sum_before + 0.78
    )


def test_lease_is_cached_across_requests(provider: FakeProvider) -> None:
    app = _app(provider)
    with respx.mock(base_url=JAVA_URL, assert_all_called=False) as router:
        java = Java(router)
        with TestClient(app) as client:
            assert _post(client).status_code == 200
            assert _post(client).status_code == 200

    assert java.lease.call_count == 1
    assert len(provider.requests) == 2


# --------------------------------------------------------------------------- #
# 模型/厂商失败
# --------------------------------------------------------------------------- #


def test_invalid_model_output_returns_schema_violation(provider: FakeProvider) -> None:
    provider.reply(200, _completion("not-json"))
    invalid_before = _metric("ai_worker_query_plan_duration_seconds_count", {"outcome": "invalid"})

    response, _java = _call(provider)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert "not-json" not in response.text
    assert (
        _metric("ai_worker_query_plan_duration_seconds_count", {"outcome": "invalid"})
        == invalid_before + 1
    )


@pytest.mark.parametrize("status", [500, 502, 503])
def test_provider_server_error_returns_503(provider: FakeProvider, status: int) -> None:
    provider.reply(status, {"error": {"message": f"upstream down {API_KEY}", "type": "server"}})

    response, java = _call(provider)

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "MODEL_UNAVAILABLE"
    assert body["retryable"] is True
    assert "upstream down" not in response.text
    assert API_KEY not in response.text
    (item,) = _usage_items(java)
    assert item["status"] == "PROVIDER_ERROR"


def test_provider_rate_limit_returns_429(provider: FakeProvider) -> None:
    provider.reply(429, {"error": {"message": "slow down", "type": "requests"}})

    response, java = _call(provider)

    assert response.status_code == 429
    assert response.json()["code"] == "MODEL_RATE_LIMITED"
    assert response.json()["retryable"] is True
    (item,) = _usage_items(java)
    assert item["status"] == "QUOTA_EXCEEDED"


def test_provider_auth_failure_is_not_retryable_and_drops_cached_lease(
    provider: FakeProvider,
) -> None:
    provider.reply(401, {"error": {"message": f"bad key {API_KEY}", "type": "auth"}})
    app = _app(provider)
    with respx.mock(base_url=JAVA_URL, assert_all_called=False) as router:
        java = Java(router)
        with TestClient(app) as client:
            first = _post(client)
            second = _post(client)

    assert first.status_code == 503
    assert second.status_code == 503
    assert first.json()["retryable"] is False
    assert API_KEY not in first.text
    assert java.lease.call_count == 2


def test_provider_garbage_response_returns_model_unavailable(provider: FakeProvider) -> None:
    provider.reply(200, "<html>oops</html>")

    response, _java = _call(provider)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert "oops" not in response.text


def test_end_to_end_deadline_returns_504_without_partial_response(provider: FakeProvider) -> None:
    async def slow(_request: httpx2.Request) -> httpx2.Response:
        await asyncio.sleep(0.5)
        return httpx2.Response(200, json=_completion(_plan_payload()))

    provider.handler = slow

    response, java = _call(provider, _request_body(deadlineMs=50))

    assert response.status_code == 504
    body = response.json()
    assert body["code"] == "DEADLINE_EXCEEDED"
    assert body["retryable"] is True
    assert "rewrittenQuery" not in body
    (item,) = _usage_items(java)
    assert item["status"] == "CANCELED"


def test_server_deadline_cap_is_applied(provider: FakeProvider) -> None:
    async def slow(_request: httpx2.Request) -> httpx2.Response:
        await asyncio.sleep(0.5)
        return httpx2.Response(200, json=_completion(_plan_payload()))

    provider.handler = slow
    app = _app(provider, query_plan_deadline_ms=50)

    response, _java = _call(provider, _request_body(deadlineMs=30000), app=app)

    assert response.status_code == 504


# --------------------------------------------------------------------------- #
# 租借与令牌
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(("status", "retryable"), [(403, False), (503, True)])
def test_lease_rejection_maps_to_model_unavailable(
    provider: FakeProvider, status: int, retryable: bool
) -> None:
    response, _java = _call(provider, lease_status=status)

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "MODEL_UNAVAILABLE"
    assert body["retryable"] is retryable
    assert provider.requests == []


@pytest.mark.parametrize(
    "overrides",
    [{"uid": None}, {"pid": None}, {"uid": "not-a-uuid"}],
    ids=["no-uid", "no-pid", "bad-uid"],
)
def test_token_without_scope_ids_is_rejected_before_lease(
    provider: FakeProvider, overrides: dict[str, object]
) -> None:
    response, java = _call(provider, token=_mint(**overrides))

    assert response.status_code == 403
    assert response.json()["code"] == "INVALID_REQUEST"
    assert response.json()["details"] == {"reason": "token_scope_claims_missing"}
    assert java.lease.call_count == 0
    assert provider.requests == []


def test_missing_token_is_rejected(provider: FakeProvider) -> None:
    response, java = _call(provider, token=None)

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_AUTH_FAILED"
    assert java.lease.call_count == 0
    assert provider.requests == []


def test_missing_plan_scope_is_rejected(provider: FakeProvider) -> None:
    response, java = _call(provider, token=_mint(scope="credentials:lease usage:report"))

    assert response.status_code == 401
    assert java.lease.call_count == 0


def test_auth_failure_never_leaks_token_to_logs_or_response(
    provider: FakeProvider, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.DEBUG)
    rejected = (
        _mint(scope="usage:report"),
        _mint(iat=int(time.time()) - 900, exp=int(time.time()) - 400),
    )
    app = _app(provider)
    with respx.mock(base_url=JAVA_URL, assert_all_called=False) as router:
        java = Java(router)
        with TestClient(app) as client:
            responses = [_post(client, token=token) for token in rejected]

    assert java.lease.call_count == 0
    assert "内部服务鉴权失败" in caplog.text
    for token, response in zip(rejected, responses, strict=True):
        assert response.status_code == 401
        for segment in token.split("."):
            assert segment not in response.text
            assert segment not in caplog.text


_OTHER_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("66" * 32))
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
    ("scope", _sign_raw(_HEADER, _claims(scope="usage:report"))),
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
    provider: FakeProvider,
    caplog: pytest.LogCaptureFixture,
    reason: str,
    token: str | None,
) -> None:
    caplog.set_level(logging.DEBUG)
    response, java = _call(provider, token=token)

    assert response.status_code == 401
    assert response.json() == {
        "code": "INTERNAL_AUTH_FAILED",
        "message": "内部服务鉴权失败",
        "retryable": False,
        "details": {"reason": reason},
    }
    assert java.lease.call_count == 0
    assert f"reason={reason}" in caplog.text
    if token is not None:
        for segment in token.split("."):
            assert segment not in response.text
            assert segment not in caplog.text


# --------------------------------------------------------------------------- #
# 可观测性与 OpenAPI
# --------------------------------------------------------------------------- #


def test_metrics_are_exposed_and_do_not_leak_query_or_key(provider: FakeProvider) -> None:
    secret_query = "绝密查询-不应出现在指标里"
    app = _app(provider)
    with respx.mock(base_url=JAVA_URL, assert_all_called=False) as router:
        Java(router)
        with TestClient(app) as client:
            assert _post(client, _request_body(query=secret_query)).status_code == 200
            metrics = client.get("/metrics")

    assert metrics.status_code == 200
    text = metrics.text
    assert "ai_worker_query_plan_duration_seconds" in text
    assert "ai_worker_query_plan_confidence" in text
    assert "ai_worker_query_plan_unknown_document_ids_total" in text
    assert "ai_model_calls_total" in text
    assert "ai_usage_report_failures_total" in text
    assert secret_query not in text
    assert TOKEN not in text
    assert API_KEY not in text


def test_openapi_documents_query_plan_route_and_no_jobs_route(provider: FakeProvider) -> None:
    with TestClient(_app(provider)) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/rag/plan" in paths
    assert "/v1/rag/rerank" not in paths
    assert not [path for path in paths if path.startswith("/v1/jobs")]
    post = paths["/v1/rag/plan"]["post"]
    assert post["responses"]["200"]["content"]["application/json"]["schema"]
