"""``POST /v1/rag/rerank`` 集成测试（离线，respx 拦截 Java 网关）。

覆盖：正常打分、网关超时/5xx/429/非法输出、缺 id 的 partial、幂等重放、同 key 不同
body、端到端 deadline、鉴权与 P0 端点兼容。所有测试零网络、零密钥；token 只作为
测试常量出现在请求头里。
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
TOKEN = "integration-test-service-token"
MODEL_PATH = "/internal/ai/v1/model-calls"

#: 集成测试专用 Ed25519 密钥（仅内存生成，不落盘、不是任何真实部署密钥）。
ED25519_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("33" * 32))
ED25519_PUBLIC_B64 = base64.b64encode(
    ED25519_KEY.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
).decode("ascii")


def _app(**overrides: object) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        service_token=TOKEN,
        java_gateway_url=GATEWAY_URL,
        **overrides,
    )
    return create_app(settings)


def _candidates(count: int = 2) -> list[dict[str, str]]:
    return [
        {"id": f"c{index}", "title": f"标题{index}", "text": f"第{index}段正文"}
        for index in range(count)
    ]


def _request_body(count: int = 2, **overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "schemaVersion": 1,
        "query": "核心架构是什么",
        "topK": 8,
        "candidates": _candidates(count),
        "deadlineMs": 3000,
    }
    body.update(overrides)
    return body


def _gateway_payload(content: str = '{"0": 0.9, "1": 0.4}') -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "content": content,
        "usage": {"inputTokens": 21, "outputTokens": 8, "source": "PROVIDER"},
        "modelAlias": "gateway-model",
        "latencyMs": 120,
    }


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _mint_jwt(
    *,
    kid: str = "k1",
    aud: tuple[str, ...] = ("ai-worker", "backend"),
    scope: str = "rerank model-call",
    iat: int | None = None,
    exp: int | None = None,
) -> str:
    """测试内签发一枚 Ed25519 短令牌；密钥只在内存中，用于断言验签与透传。"""

    issued_at = int(time.time()) if iat is None else iat
    header = _b64url(
        json.dumps({"alg": "EdDSA", "typ": "JWT", "kid": kid}, separators=(",", ":")).encode()
    )
    payload = _b64url(
        json.dumps(
            {
                "iss": "ai-collab-backend",
                "sub": "backend",
                "aud": list(aud),
                "scope": scope,
                "jti": "integration-jti",
                "iat": issued_at,
                "exp": issued_at + 300 if exp is None else exp,
            },
            separators=(",", ":"),
        ).encode()
    )
    signing_input = f"{header}.{payload}"
    return f"{signing_input}.{_b64url(ED25519_KEY.sign(signing_input.encode('ascii')))}"


def _ed25519_app() -> FastAPI:
    return create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            java_gateway_url=GATEWAY_URL,
            token_mode="ed25519",
            ed25519_public_keys=f"k1={ED25519_PUBLIC_B64}",
        )
    )


def _dual_app() -> FastAPI:
    return create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token=TOKEN,
            java_gateway_url=GATEWAY_URL,
            token_mode="dual",
            ed25519_public_keys=f"k1={ED25519_PUBLIC_B64}",
        )
    )


def _post(
    client: TestClient,
    body: dict[str, object] | None = None,
    *,
    key: str | None = None,
    token: str | None = TOKEN,
    traceparent: str | None = None,
) -> httpx.Response:
    headers: dict[str, str] = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if key is not None:
        headers["Idempotency-Key"] = key
    if traceparent is not None:
        headers["traceparent"] = traceparent
    return client.post("/v1/rag/rerank", json=body or _request_body(), headers=headers)


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


@pytest.fixture
def app() -> FastAPI:
    return _app()


def test_successful_rerank_covers_all_ids_and_passes_gateway_contract(
    app: FastAPI,
) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(
                client,
                _request_body(3, deadlineMs=2500),
                traceparent="00-trace-span-01",
            )

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "scores", "modelAlias", "usage"}
    assert body["schemaVersion"] == 1
    assert body["modelAlias"] == "rerank-default"
    assert body["usage"] == {
        "inputTokens": 21,
        "outputTokens": 8,
        "source": "PROVIDER",
    }
    assert response.headers["content-type"].startswith("application/json")

    # 第三候选模型未返回分数：仍覆盖全部 id，缺失补 0。
    assert body["scores"] == [
        {"id": "c0", "score": 0.9},
        {"id": "c1", "score": 0.4},
        {"id": "c2", "score": 0.0},
    ]

    assert route.call_count == 1
    gateway_request = route.calls[0].request
    assert gateway_request.headers["authorization"] == f"Bearer {TOKEN}"
    assert gateway_request.headers["x-call-id"]
    assert gateway_request.headers["traceparent"] == "00-trace-span-01"
    gateway_body = json.loads(gateway_request.content)
    assert gateway_body["outputFormat"] == "PROMPT_JSON"
    assert gateway_body["purpose"] == "KNOWLEDGE_CHAT"
    assert gateway_body["schemaVersion"] == 1
    assert gateway_body["deadlineMs"] == 2500
    assert gateway_body["callId"] == gateway_request.headers["x-call-id"]
    assert "Query: 核心架构是什么" in gateway_body["userPrompt"]
    assert "第0段正文" in gateway_body["userPrompt"]


def test_top_k_does_not_truncate_scores(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload('{"0": 0.1, "1": 0.2}'))
        )
        with TestClient(app) as client:
            response = _post(client, _request_body(2, topK=1))

    assert response.status_code == 200
    assert [score["id"] for score in response.json()["scores"]] == ["c0", "c1"]


def test_missing_usage_is_unknown_not_zero(app: FastAPI) -> None:
    payload = _gateway_payload()
    payload.pop("usage")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=payload))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    assert response.json()["usage"] == {
        "inputTokens": None,
        "outputTokens": None,
        "source": "UNKNOWN",
    }


def test_missing_id_marks_partial_and_counts_metric(
    app: FastAPI, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.WARNING, logger="ai_collab_worker.api.rerank")
    partial_before = _metric("ai_worker_rerank_requests_total", {"outcome": "partial"})
    missing_before = _metric("ai_worker_rerank_missing_scores_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload('{"0": 0.9}'))
        )
        with TestClient(app) as client:
            response = _post(client, _request_body(3))

    assert response.status_code == 200
    assert [score["score"] for score in response.json()["scores"]] == [0.9, 0.0, 0.0]
    assert _metric("ai_worker_rerank_requests_total", {"outcome": "partial"}) == (
        partial_before + 1
    )
    assert _metric("ai_worker_rerank_missing_scores_total") == missing_before + 2
    assert "missing_ids" in caplog.text
    assert "'c1'" in caplog.text
    assert "第1段正文" not in caplog.text


def test_invalid_model_output_returns_schema_violation(app: FastAPI) -> None:
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


@pytest.mark.parametrize(
    "side_effect",
    [
        httpx.ReadTimeout("read timeout"),
        httpx.ConnectTimeout("connect timeout"),
        httpx.ConnectError("connection refused"),
    ],
)
def test_gateway_transport_failures_are_classified(app: FastAPI, side_effect: Exception) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(side_effect=side_effect)
        with TestClient(app) as client:
            response = _post(client)

    if isinstance(side_effect, httpx.TimeoutException):
        assert response.status_code == 504
        assert response.json()["code"] == "DEADLINE_EXCEEDED"
    else:
        assert response.status_code == 503
        assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_gateway_invalid_json_returns_model_unavailable(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, text="<html>oops</html>"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_end_to_end_deadline_returns_504_without_scores(app: FastAPI) -> None:
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
    assert "scores" not in body


def test_idempotent_replay_returns_identical_bytes_without_gateway_call(
    app: FastAPI,
) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client, key="rerank-key-0001")
            second = _post(client, key="rerank-key-0001")

    assert first.status_code == second.status_code == 200
    assert first.content == second.content
    assert route.call_count == 1


def test_same_key_different_body_returns_400(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client, key="rerank-key-0002")
            second = _post(
                client,
                _request_body(2, query="完全不同的查询"),
                key="rerank-key-0002",
            )

    assert first.status_code == 200
    assert second.status_code == 400
    assert second.json()["code"] == "INVALID_REQUEST"
    assert route.call_count == 1


def test_same_key_with_changed_deadline_replays_instead_of_conflict(app: FastAPI) -> None:
    """deadline 是预算不是身份：Java 重试可能调整它，不应误判为不同 body。"""

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            first = _post(client, _request_body(deadlineMs=3000), key="rerank-key-0003")
            second = _post(client, _request_body(deadlineMs=500), key="rerank-key-0003")

    assert first.status_code == second.status_code == 200
    assert first.content == second.content
    assert route.call_count == 1


@pytest.mark.parametrize(
    "key",
    ["short", "bad key with spaces", "a" * 129, "x" * 7 + "!"],
)
def test_invalid_idempotency_key_returns_400_without_gateway_call(app: FastAPI, key: str) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, key=key)

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
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


def test_server_configured_max_candidates_is_enforced() -> None:
    app = _app(max_candidates=2)
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, _request_body(3))

    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"
    assert route.call_count == 0


def test_prompts_endpoints_are_not_authenticated(app: FastAPI) -> None:
    with TestClient(app) as client:
        manifest = client.get("/v1/prompts/manifest")
        document = client.get("/v1/prompts/knowledge.system/2026-11-11.2")

    assert manifest.status_code == 200
    assert document.status_code == 200


def test_readyz_does_not_depend_on_java_gateway() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token=TOKEN,
            java_gateway_url="http://127.0.0.1:1",
        )
    )
    with TestClient(app) as client:
        response = client.get("/readyz")

    assert response.status_code == 200
    assert response.json()["checks"] == {"prompts": "ok", "auth": "ok"}


def test_metrics_count_success_and_deadline_outcomes(app: FastAPI) -> None:
    ok_before = _metric("ai_worker_rerank_requests_total", {"outcome": "ok"})
    duration_before = _metric("ai_worker_rerank_duration_seconds_count")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload('{"0": 0.9, "1": 0.4}'))
        )
        with TestClient(app) as client:
            assert _post(client).status_code == 200

    assert _metric("ai_worker_rerank_requests_total", {"outcome": "ok"}) == ok_before + 1
    assert _metric("ai_worker_rerank_duration_seconds_count") == duration_before + 1


def test_metrics_are_exposed_on_metrics_endpoint(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload('{"0": 0.9, "1": 0.4}'))
        )
        with TestClient(app) as client:
            assert _post(client).status_code == 200
            metrics = client.get("/metrics")

    assert metrics.status_code == 200
    text = metrics.text
    assert "ai_worker_rerank_requests_total" in text
    assert 'outcome="ok"' in text
    assert "ai_worker_rerank_duration_seconds" in text
    assert "ai_worker_rerank_missing_scores_total" in text
    assert "integration-test-service-token" not in text


def test_response_does_not_leak_candidate_text_or_query(app: FastAPI) -> None:
    secret_text = "候选正文-SECRET-9f3a"
    body = {
        "query": "查询-SECRET-1a2b",
        "candidates": [{"id": "c0", "title": "标题", "text": secret_text}],
    }

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload('{"0": 0.5}'))
        )
        with TestClient(app) as client:
            response = _post(client, body)
            metrics = client.get("/metrics")

    assert response.status_code == 200
    assert secret_text not in response.text
    assert "查询-SECRET-1a2b" not in response.text
    assert secret_text not in metrics.text
    assert "查询-SECRET-1a2b" not in metrics.text


def test_gateway_call_is_not_retried_by_worker(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(return_value=httpx.Response(503, text="down"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    assert route.call_count == 1


def test_openapi_documents_rerank_route(app: FastAPI) -> None:
    with TestClient(app) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/rag/rerank" in paths
    post = paths["/v1/rag/rerank"]["post"]
    assert post["responses"]["200"]["content"]["application/json"]["schema"]


# ---------- W3.2 Ed25519 短令牌（只验签 + 原样透传） ----------


def test_ed25519_mode_accepts_valid_jwt_and_forwards_the_exact_token() -> None:
    app = _ed25519_app()
    jwt = _mint_jwt()

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=jwt)

    assert response.status_code == 200
    assert route.call_count == 1
    # 透传的是入站令牌本身，不是 worker 重新签发的令牌。
    assert route.calls[0].request.headers["authorization"] == f"Bearer {jwt}"


def test_ed25519_mode_rejects_expired_wrong_audience_and_wrong_scope() -> None:
    app = _ed25519_app()
    expired = _mint_jwt(iat=int(time.time()) - 400, exp=int(time.time()) - 120)
    wrong_audience = _mint_jwt(aud=("backend",))
    wrong_scope = _mint_jwt(scope="model-call")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            for token in (expired, wrong_audience, wrong_scope):
                response = _post(client, token=token)
                assert response.status_code == 401
                assert response.json()["code"] == "MODEL_UNAVAILABLE"
                assert token not in response.text

    assert route.call_count == 0


def test_ed25519_mode_rejects_static_token() -> None:
    app = _ed25519_app()

    with TestClient(app) as client:
        response = _post(client, token=TOKEN)

    assert response.status_code == 401
    assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_dual_mode_accepts_jwt_and_static_token_and_forwards_each() -> None:
    app = _dual_app()
    jwt = _mint_jwt()

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            assert _post(client, token=jwt).status_code == 200
            assert _post(client, token=TOKEN).status_code == 200

    assert route.call_count == 2
    assert route.calls[0].request.headers["authorization"] == f"Bearer {jwt}"
    assert route.calls[1].request.headers["authorization"] == f"Bearer {TOKEN}"


def test_readyz_fails_closed_when_ed25519_public_keys_are_missing() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            java_gateway_url=GATEWAY_URL,
            token_mode="ed25519",
            ed25519_public_keys="",
        )
    )

    with TestClient(app) as client:
        ready = client.get("/readyz")
        alive = client.get("/healthz")
        response = _post(client, token="any-token")

    assert alive.status_code == 200
    assert ready.status_code == 503
    assert ready.json()["checks"] == {"prompts": "ok", "auth": "failed"}
    assert ready.json()["reasons"]["auth"] == "ed25519 公钥未配置或非法"
    # 配置缺失是 503（fail closed），不是 401。
    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"


def test_readyz_fails_closed_when_ed25519_public_keys_are_invalid() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            java_gateway_url=GATEWAY_URL,
            token_mode="ed25519",
            ed25519_public_keys="k1=not-a-valid-spki",
        )
    )

    with TestClient(app) as client:
        ready = client.get("/readyz")

    assert ready.status_code == 503
    assert ready.json()["reasons"]["auth"] == "ed25519 公钥未配置或非法"


def test_readyz_is_ok_for_ed25519_mode_with_public_keys() -> None:
    app = _ed25519_app()
    with TestClient(app) as client:
        ready = client.get("/readyz")

    assert ready.status_code == 200
    assert ready.json()["checks"] == {"prompts": "ok", "auth": "ok"}
