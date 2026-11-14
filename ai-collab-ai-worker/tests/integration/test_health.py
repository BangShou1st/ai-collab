"""健康检查端点的集成测试：存活、就绪、指标与错误信封。

全部通过 ``fastapi.testclient.TestClient`` 打真实装配的 app（含 lifespan），
零网络、零外部依赖。
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.api.health import add_readiness_check
from ai_collab_worker.config import Settings, UnsafeEnvironmentError
from ai_collab_worker.main import create_app

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"


@pytest.fixture
def app() -> FastAPI:
    # 就地装配真实 PromptPack：就绪探针的 ``prompts`` 检查必须通过。
    return create_app(Settings(prompts_dir=PROMPTS_DIR))


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


def test_healthz_reports_service_identity(client: TestClient) -> None:
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "ai-collab-ai-worker",
        "version": "0.1.0",
        "role": "api",
        "schemaVersion": 1,
    }


def test_healthz_uses_injected_role(tmp_path: Path) -> None:
    app = create_app(Settings(prompts_dir=tmp_path / "prompts", role="job"))
    with TestClient(app) as test_client:
        body = test_client.get("/healthz").json()
    assert body["role"] == "job"
    assert body["schemaVersion"] == 1


def test_metrics_endpoint_serves_prometheus_text(client: TestClient) -> None:
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "text/plain" in response.headers["content-type"]
    assert b"ai_worker_info" in response.content
    assert b"ai_worker_http_requests_total" in response.content


def test_readyz_reports_prompt_pack_ready(client: TestClient) -> None:
    response = client.get("/readyz")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "checks": {"prompts": "ok", "auth": "ok"},
        "schemaVersion": 1,
    }


def test_readyz_reports_prompt_pack_not_ready(tmp_path: Path) -> None:
    """PromptPack 缺失时进程仍存活，但就绪探针必须 503 且不泄漏路径。"""

    app = create_app(Settings(prompts_dir=tmp_path / "missing-prompts"))
    with TestClient(app) as test_client:
        response = test_client.get("/readyz")

    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "not_ready"
    assert body["checks"] == {"prompts": "failed", "auth": "ok"}
    assert body["reasons"]
    assert str(tmp_path) not in response.text


def test_readyz_runs_every_registered_check(app: FastAPI, client: TestClient) -> None:
    add_readiness_check(app, "manifest", lambda: None)
    add_readiness_check(app, "templates", lambda: None)

    response = client.get("/readyz")
    assert response.status_code == 200
    assert response.json()["checks"] == {
        "prompts": "ok",
        "auth": "ok",
        "manifest": "ok",
        "templates": "ok",
    }


def test_readyz_reports_failed_reason(app: FastAPI, client: TestClient) -> None:
    add_readiness_check(app, "manifest", lambda: "manifest 缺失")

    response = client.get("/readyz")
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "not_ready"
    assert body["checks"] == {"prompts": "ok", "auth": "ok", "manifest": "failed"}
    assert body["reasons"] == {"manifest": "manifest 缺失"}
    assert body["schemaVersion"] == 1


def test_readyz_masks_exception_details(app: FastAPI, client: TestClient) -> None:
    def boom() -> str | None:
        raise RuntimeError("SECRET-XYZ")

    add_readiness_check(app, "explode", boom)

    response = client.get("/readyz")
    assert response.status_code == 503
    assert response.json()["checks"] == {"prompts": "ok", "auth": "ok", "explode": "failed"}
    assert response.json()["reasons"] == {"explode": "RuntimeError"}
    assert "SECRET-XYZ" not in response.text


def test_unknown_path_is_error_envelope(client: TestClient) -> None:
    response = client.get("/v1/does-not-exist")
    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "INVALID_REQUEST"
    assert body["retryable"] is False


def test_lifespan_rejects_forbidden_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """启动期再校验一次环境：带密钥的进程必须在对外服务前失败，且不泄漏值。"""

    app = create_app(Settings(prompts_dir=tmp_path / "prompts"))
    monkeypatch.setenv("JWT_SECRET", "LEAK-STARTUP")

    with pytest.raises(UnsafeEnvironmentError) as excinfo, TestClient(app):
        pass
    assert "JWT_SECRET" in str(excinfo.value)
    assert "LEAK-STARTUP" not in str(excinfo.value)
