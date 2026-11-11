"""端到端冒烟：用真实 ``create_app`` 装配打健康、指标、PromptPack 与错误信封。

覆盖 P0 退出条件里"服务能起、PromptPack 可取且 sha256 校验通过"这条链路；
不连接数据库/Redis/MinIO，也不发任何出站请求。
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"

KEY = "knowledge.system"
VERSION = "2026-11-11.2"


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    with TestClient(create_app(Settings(prompts_dir=PROMPTS_DIR, role="api"))) as test_client:
        yield test_client


def test_health_ready_and_metrics_are_served(client: TestClient) -> None:
    health = client.get("/healthz")
    ready = client.get("/readyz")
    metrics = client.get("/metrics")

    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    assert health.json()["schemaVersion"] == 1
    assert ready.status_code == 200
    assert ready.json() == {
        "status": "ready",
        "checks": {"prompts": "ok", "auth": "ok"},
        "schemaVersion": 1,
    }
    assert metrics.status_code == 200
    assert "text/plain" in metrics.headers["content-type"]
    assert b"ai_worker_info" in metrics.content
    assert b"ai_worker_http_requests_total" in metrics.content
    assert b'path="/healthz"' in metrics.content


def test_prompt_manifest_and_document_are_consistent(client: TestClient) -> None:
    manifest_response = client.get("/v1/prompts/manifest")
    assert manifest_response.status_code == 200
    manifest = manifest_response.json()
    assert manifest["schemaVersion"] == 1
    entry = next(
        item for item in manifest["prompts"] if item["key"] == KEY and item["version"] == VERSION
    )
    assert set(entry) == {"key", "version", "sha256", "chars"}

    document_response = client.get(f"/v1/prompts/{KEY}/{VERSION}")
    assert document_response.status_code == 200
    document = document_response.json()
    assert document["schemaVersion"] == 1
    assert document["key"] == KEY
    assert document["version"] == VERSION
    assert document["variables"] == ["timeContext"]
    assert len(document["text"]) == entry["chars"]
    assert hashlib.sha256(document["text"].encode("utf-8")).hexdigest() == entry["sha256"]


def test_unknown_prompt_returns_404_envelope(client: TestClient) -> None:
    response = client.get("/v1/prompts/knowledge.system/2026-11-12.1")

    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "PROMPT_UNAVAILABLE"
    assert body["retryable"] is False
    assert body["details"] == {"key": KEY, "version": "2026-11-12.1"}


def test_openapi_exposes_only_p0_routes(client: TestClient) -> None:
    paths = client.get("/openapi.json").json()["paths"]

    assert "/healthz" in paths
    assert "/readyz" in paths
    assert "/metrics" in paths
    assert "/v1/prompts/manifest" in paths
    assert "/v1/prompts/{key}/{version}" in paths
