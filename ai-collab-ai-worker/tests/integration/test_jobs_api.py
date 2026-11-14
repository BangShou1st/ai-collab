"""``POST /v1/jobs`` 集成测试（离线：Ed25519 job token + respx 拦截 Java 内部端点）。

覆盖：job token claims 绑定、成功全链路（心跳→快照→模型→结果）、静态模式 503、
claims 缺失/不一致→403、过期→401、快照 404/409/超时、模型输出非法 422、模型 503、
来源非法/dedupeKey 不一致丢弃、结果幂等、端到端 deadline、指标与不泄漏令牌、
**不调用任何发布/业务写端点**（只允许四个固定 URL）。
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from pathlib import Path
from typing import Any

import httpx
import pytest
import respx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.cognition.memory.parser import compute_dedupe_key
from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.observability.metrics import REGISTRY

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
GATEWAY_URL = "http://gateway.test"
JOB_ID = "00000000-0000-4000-8000-000000000001"
MEMORY_ID = "44444444-4444-4444-8444-444444444444"
SNAPSHOT_HASH = "sha256:abc123"
PROJECT_REF = "project-1"

MODEL_PATH = "/internal/ai/v1/model-calls"
JOBS_PATH = f"/internal/ai/v1/jobs/{JOB_ID}"
SNAPSHOT_PATH = f"{JOBS_PATH}/snapshot"
HEARTBEAT_PATH = f"{JOBS_PATH}/heartbeat"
RESULTS_PATH = f"{JOBS_PATH}/results"

SNAPSHOT = {
    "schemaVersion": 1,
    "jobId": JOB_ID,
    "projectId": PROJECT_REF,
    "attempt": 2,
    "leaseEpoch": 7,
    "snapshotHash": SNAPSHOT_HASH,
    "facts": [{"ref": "fact-1", "kind": "DECISION", "text": "团队决定统一网关。"}],
    "existingMemories": [
        {
            "id": MEMORY_ID,
            "type": "CONSTRAINT",
            "title": "不写业务表",
            "content": "Python 不写业务表。",
        }
    ],
}

CANDIDATE = {
    "kind": "DECISION",
    "title": "统一网关",
    "content": "所有模型调用经 Java 网关。",
    "confidence": 0.8,
    "sourceRef": "fact-1",
}
CANDIDATES_CONTENT = json.dumps({"candidates": [CANDIDATE]}, ensure_ascii=False)

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("33" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")
SCOPES = "job:execute job:snapshot job:heartbeat job:results model-call"


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _mint(
    claims: dict[str, object] | None = None,
    *,
    scope: str = SCOPES,
    iat_offset: int = -5,
    exp_offset: int = 300,
    aud: tuple[str, ...] = ("ai-worker", "backend"),
) -> str:
    now = int(time.time())
    header = {"alg": "EdDSA", "typ": "JWT", "kid": "k1"}
    payload: dict[str, object] = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": list(aud),
        "scope": scope,
        "jti": "integration-job-jti",
        "iat": now + iat_offset,
        "exp": now + exp_offset,
        **(claims or {}),
    }
    header_segment = _b64(json.dumps(header, separators=(",", ":")).encode())
    payload_segment = _b64(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header_segment}.{payload_segment}"
    return f"{signing_input}.{_b64(_KEY.sign(signing_input.encode('ascii')))}"


def _claims(**overrides: Any) -> dict[str, object]:
    claims: dict[str, object] = {
        "jobId": JOB_ID,
        "attempt": 2,
        "leaseEpoch": 7,
        "snapshotHash": SNAPSHOT_HASH,
        "projectRef": PROJECT_REF,
    }
    claims.update(overrides)
    return claims


def _app(**overrides: Any) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        service_token="",
        token_mode="ed25519",
        ed25519_public_keys=f"k1={_PUBLIC_SPKI}",
        java_gateway_url=GATEWAY_URL,
        **overrides,
    )
    return create_app(settings)


def _body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "schemaVersion": 1,
        "jobId": JOB_ID,
        "type": "MEMORY_EXTRACTION",
        "deadlineMs": 30000,
    }
    body.update(overrides)
    return body


def _post(client: TestClient, body: dict[str, Any] | None = None, *, token: str | None = None):
    headers = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    return client.post("/v1/jobs", json=body or _body(), headers=headers)


def _model_payload(content: str = CANDIDATES_CONTENT) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "content": content,
        "usage": {"inputTokens": 100, "outputTokens": 40, "source": "PROVIDER"},
        "latencyMs": 12,
    }


def _results_payload(*, accepted: int = 1, duplicates: int = 0) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "jobId": JOB_ID,
        "status": "SUCCEEDED",
        "accepted": accepted,
        "duplicates": duplicates,
    }


def _mock_all(
    router: respx.Router, *, candidates: str = CANDIDATES_CONTENT
) -> dict[str, respx.Route]:
    return {
        "heartbeat": router.post(HEARTBEAT_PATH).mock(
            return_value=httpx.Response(
                200,
                json={
                    "schemaVersion": 1,
                    "status": "RUNNING",
                    "leaseExpiresAt": "2026-11-12T10:00:00Z",
                },
            )
        ),
        "snapshot": router.get(SNAPSHOT_PATH).mock(return_value=httpx.Response(200, json=SNAPSHOT)),
        "model": router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_model_payload(candidates))
        ),
        "results": router.post(RESULTS_PATH).mock(
            return_value=httpx.Response(200, json=_results_payload())
        ),
    }


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


@pytest.fixture
def app() -> FastAPI:
    return _app()


def test_successful_job_runs_four_calls_and_returns_contract(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "jobId", "status", "candidateCount", "latencyMs"}
    assert body["schemaVersion"] == 1
    assert body["jobId"] == JOB_ID
    assert body["status"] == "SUCCEEDED"
    assert body["candidateCount"] == 1
    assert isinstance(body["latencyMs"], int)
    assert body["latencyMs"] >= 0

    # 只有这四个内部端点被调用；没有任何发布/业务写路径。
    called = {
        (call.request.method, str(call.request.url).replace(GATEWAY_URL, ""))
        for route in routes.values()
        for call in route.calls
    }
    assert called == {
        ("POST", HEARTBEAT_PATH),
        ("GET", SNAPSHOT_PATH),
        ("POST", MODEL_PATH),
        ("POST", RESULTS_PATH),
    }
    for route in routes.values():
        for call in route.calls:
            assert call.request.headers["authorization"] == f"Bearer {token}"
            assert "agent_memory" not in str(call.request.url)

    model_body = json.loads(routes["model"].calls[0].request.content)
    assert model_body["purpose"] == "AGENT"
    assert model_body["callId"].startswith("memory:")

    results_body = json.loads(routes["results"].calls[0].request.content)
    assert results_body["attempt"] == 2
    assert results_body["leaseEpoch"] == 7
    candidate = results_body["candidates"][0]
    assert candidate["dedupeKey"] == compute_dedupe_key(
        "DECISION", CANDIDATE["title"], CANDIDATE["content"]
    )
    assert candidate["sourceRef"] == "fact-1"

    heartbeat_body = json.loads(routes["heartbeat"].calls[0].request.content)
    assert heartbeat_body == {"schemaVersion": 1, "attempt": 2, "leaseEpoch": 7}


def test_missing_claims_returns_403_without_any_upstream_call(app: FastAPI) -> None:
    token = _mint({})
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 403
    body = response.json()
    assert body["code"] == "INVALID_REQUEST"
    assert body["details"] == {"reason": "job_token_claims_mismatch"}
    assert all(route.call_count == 0 for route in routes.values())


def test_claim_job_id_mismatch_returns_403(app: FastAPI) -> None:
    token = _mint(_claims(jobId="00000000-0000-4000-8000-000000000002"))
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 403
    assert response.json()["code"] == "INVALID_REQUEST"
    assert all(route.call_count == 0 for route in routes.values())


@pytest.mark.parametrize(
    "overrides",
    [
        {"attempt": 0},
        {"leaseEpoch": -1},
        {"snapshotHash": ""},
        {"projectRef": ""},
    ],
)
def test_invalid_claim_values_return_403(app: FastAPI, overrides: dict[str, object]) -> None:
    token = _mint(_claims(**overrides))
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 403


def test_expired_token_returns_401(app: FastAPI) -> None:
    token = _mint(_claims(), iat_offset=-400, exp_offset=-120)
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 401
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert token not in response.text
    assert all(route.call_count == 0 for route in routes.values())


def test_missing_scope_returns_401(app: FastAPI) -> None:
    token = _mint(_claims(), scope="job:snapshot job:heartbeat job:results model-call")
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 401


def test_static_mode_fails_closed_with_503() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="static-token",
            java_gateway_url=GATEWAY_URL,
        )
    )
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token="static-token")

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "MODEL_UNAVAILABLE"
    assert body["retryable"] is False
    assert all(route.call_count == 0 for route in routes.values())


def test_snapshot_404_returns_failed_without_model_call(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["snapshot"].mock(return_value=httpx.Response(404, json={"code": "INVALID_REQUEST"}))
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED"
    assert body["candidateCount"] == 0
    assert routes["model"].call_count == 0
    assert routes["results"].call_count == 0


def test_snapshot_409_stale_lease_returns_failed(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["snapshot"].mock(return_value=httpx.Response(409, json={"code": "STALE_LEASE"}))
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    assert response.json()["status"] == "FAILED"
    assert routes["model"].call_count == 0


def test_snapshot_timeout_returns_failed_retryable(app: FastAPI) -> None:
    token = _mint(_claims())

    async def slow(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.2)
        return httpx.Response(200, json=SNAPSHOT)

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["snapshot"].mock(side_effect=slow)
        with TestClient(app) as client:
            # 让 httpx 的 read 超时先触发：deadline 很短时 asyncio.timeout 也可能先到，
            # 两者的契约结果相同（FAILED_RETRYABLE）。
            response = _post(client, _body(deadlineMs=40), token=token)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED_RETRYABLE"
    assert body["candidateCount"] == 0


def test_snapshot_binding_mismatch_returns_403(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["snapshot"].mock(return_value=httpx.Response(200, json={**SNAPSHOT, "attempt": 3}))
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 403
    assert response.json()["details"] == {"reason": "job_snapshot_binding_mismatch"}
    assert routes["model"].call_count == 0


def test_invalid_model_output_returns_422_and_skips_results(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router, candidates="not-json")
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert body["details"]["reason"] == "模型输出不是合法 JSON"
    assert "not-json" not in response.text
    assert routes["results"].call_count == 0


def test_model_gateway_503_returns_failed_retryable(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["model"].mock(return_value=httpx.Response(503, text="down"))
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    assert response.json()["status"] == "FAILED_RETRYABLE"
    assert routes["results"].call_count == 0


def test_invalid_source_ref_is_dropped_and_counted(app: FastAPI) -> None:
    token = _mint(_claims())
    candidates = json.dumps(
        {
            "candidates": [
                {**CANDIDATE, "sourceRef": "fact-9"},
                CANDIDATE,
            ]
        },
        ensure_ascii=False,
    )
    before = _metric("ai_worker_memory_invalid_source_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router, candidates=candidates)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    assert response.json()["candidateCount"] == 1
    assert _metric("ai_worker_memory_invalid_source_total") == before + 1
    submitted = json.loads(routes["results"].calls[0].request.content)["candidates"]
    assert len(submitted) == 1
    assert submitted[0]["sourceRef"] == "fact-1"


def test_invalid_dedupe_key_is_dropped_and_counted(app: FastAPI) -> None:
    token = _mint(_claims())
    candidates = json.dumps(
        {
            "candidates": [
                {**CANDIDATE, "dedupeKey": "0" * 64},
                CANDIDATE,
            ]
        },
        ensure_ascii=False,
    )
    before = _metric("ai_worker_memory_invalid_dedupe_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        _mock_all(router, candidates=candidates)
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    assert response.json()["candidateCount"] == 1
    assert _metric("ai_worker_memory_invalid_dedupe_total") == before + 1


def test_duplicate_results_are_idempotent_success(app: FastAPI) -> None:
    token = _mint(_claims())
    before = _metric("ai_worker_memory_job_requests_total", {"outcome": "succeeded"})

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["results"].mock(
            return_value=httpx.Response(200, json=_results_payload(accepted=0, duplicates=1))
        )
        with TestClient(app) as client:
            first = _post(client, token=token)
            second = _post(client, token=token)

    assert first.status_code == second.status_code == 200
    assert first.json()["status"] == second.json()["status"] == "SUCCEEDED"
    assert first.json()["candidateCount"] == 1
    assert routes["results"].call_count == 2
    assert _metric("ai_worker_memory_job_requests_total", {"outcome": "succeeded"}) == (before + 2)


def test_end_to_end_deadline_returns_failed_retryable(app: FastAPI) -> None:
    token = _mint(_claims())

    async def slow_model(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_model_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["model"].mock(side_effect=slow_model)
        with TestClient(app) as client:
            response = _post(client, _body(deadlineMs=50), token=token)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED_RETRYABLE"
    assert body["candidateCount"] == 0
    assert routes["results"].call_count == 0


def test_terminal_heartbeat_status_stops_execution(app: FastAPI) -> None:
    token = _mint(_claims())
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        routes = _mock_all(router)
        routes["heartbeat"].mock(
            return_value=httpx.Response(
                200, json={"schemaVersion": 1, "status": "FAILED", "leaseExpiresAt": None}
            )
        )
        with TestClient(app) as client:
            response = _post(client, token=token)

    assert response.status_code == 200
    assert response.json()["status"] == "FAILED"
    assert routes["snapshot"].call_count == 0
    assert routes["model"].call_count == 0


def test_metrics_and_logs_do_not_leak_token(app: FastAPI, caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO, logger="ai_collab_worker.api.jobs")
    token = _mint(_claims())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        _mock_all(router)
        with TestClient(app) as client:
            response = _post(client, token=token)
            metrics = client.get("/metrics")

    assert response.status_code == 200
    assert token not in response.text
    assert token not in metrics.text
    assert token not in caplog.text
    assert "团队决定统一网关" not in caplog.text
    assert "memory_job job_id=" in caplog.text
    assert "ai_worker_memory_job_requests_total" in metrics.text
    assert "ai_worker_memory_job_duration_seconds" in metrics.text
    assert "ai_worker_memory_candidates_total" in metrics.text
    assert "ai_worker_memory_invalid_source_total" in metrics.text


def test_openapi_documents_jobs_route(app: FastAPI) -> None:
    with TestClient(app) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/jobs" in paths
