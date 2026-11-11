"""``/v1/prompts`` 端点集成测试：TestClient 打真实装配（含统一错误处理器）。

不依赖工作目录：项目根用 ``Path(__file__).resolve().parents[2]`` 定位；不连任何外部依赖。
"""

from __future__ import annotations

import json
import shutil
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.api.prompts import router
from ai_collab_worker.contract.prompts import prompt_sha256, prompt_text_from_bytes
from ai_collab_worker.prompts.store import PromptStore

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
MANIFEST_PATH = PROMPTS_DIR / "manifest.json"

KEY = "knowledge.system"
VERSION = "2026-11-11.2"
EXPECTED_VARIABLES = ["timeContext"]
#: 正文自身以换行结尾的 Scenario 条目（冻结文档 §1.1/F2 的回归锚点）。
SCENARIO_KEY = "scenario.delivery_readiness.instruction"
SCENARIO_VERSION = "2026-11-11.1"


def _build_app(store: PromptStore | None) -> FastAPI:
    app = FastAPI()
    if store is not None:
        app.state.prompt_store = store
    app.include_router(router)
    install_error_handlers(app)
    return app


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    store = PromptStore(PROMPTS_DIR)
    assert store.readiness_failure() is None, "仓库 PromptPack 未就绪"
    with TestClient(_build_app(store)) as test_client:
        yield test_client


def test_manifest_endpoint_returns_wire_shape(client: TestClient) -> None:
    response = client.get("/v1/prompts/manifest")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "prompts"}
    assert body["schemaVersion"] == 1
    assert body["prompts"]
    for entry in body["prompts"]:
        assert set(entry) == {"key", "version", "sha256", "chars"}
    references = [(entry["key"], entry["version"]) for entry in body["prompts"]]
    assert references == sorted(references)


def test_manifest_endpoint_matches_manifest_file(client: TestClient) -> None:
    on_disk = json.loads(MANIFEST_PATH.read_bytes())["prompts"]
    expected = [
        {
            "key": entry["key"],
            "version": entry["version"],
            "sha256": entry["sha256"],
            "chars": entry["chars"],
        }
        for entry in on_disk
    ]

    assert client.get("/v1/prompts/manifest").json()["prompts"] == expected


def test_prompt_document_endpoint_returns_template_body(client: TestClient) -> None:
    response = client.get(f"/v1/prompts/{KEY}/{VERSION}")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "key", "version", "sha256", "text", "variables"}
    assert body["schemaVersion"] == 1
    assert body["key"] == KEY
    assert body["version"] == VERSION
    text = prompt_text_from_bytes((PROMPTS_DIR / KEY / f"{VERSION}.md").read_bytes())
    assert body["text"] == text
    assert not body["text"].endswith("\n")
    assert body["sha256"] == prompt_sha256(body["text"])
    assert body["variables"] == EXPECTED_VARIABLES


def test_scenario_prompt_document_preserves_trailing_newline(client: TestClient) -> None:
    """Scenario 正文自身以换行结尾：文件两个换行，响应正文保留那一个换行。"""

    response = client.get(f"/v1/prompts/{SCENARIO_KEY}/{SCENARIO_VERSION}")

    assert response.status_code == 200
    body = response.json()
    raw = (PROMPTS_DIR / SCENARIO_KEY / f"{SCENARIO_VERSION}.md").read_bytes()
    assert raw.endswith(b"\n\n"), "该 Scenario 模板应以两个换行结尾"
    assert body["text"].endswith("\n")
    assert body["text"] == prompt_text_from_bytes(raw)
    assert body["sha256"] == prompt_sha256(body["text"])
    assert body["variables"] == []


@pytest.mark.parametrize(
    ("key", "version"),
    [("knowledge.unknown", VERSION), (KEY, "2026-11-12.1")],
)
def test_unknown_prompt_returns_404_envelope(client: TestClient, key: str, version: str) -> None:
    response = client.get(f"/v1/prompts/{key}/{version}")

    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "PROMPT_UNAVAILABLE"
    assert body["retryable"] is False
    assert body["details"] == {"key": key, "version": version}


@pytest.mark.parametrize(("key", "version"), [(KEY, "v1"), ("Knowledge.System", VERSION)])
def test_invalid_prompt_reference_returns_400_envelope(
    client: TestClient, key: str, version: str
) -> None:
    response = client.get(f"/v1/prompts/{key}/{version}")

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_REQUEST"
    assert body["retryable"] is False


def test_broken_store_returns_503_envelope_without_paths(tmp_path: Path) -> None:
    store = PromptStore(tmp_path)
    with TestClient(_build_app(store)) as broken_client:
        manifest = broken_client.get("/v1/prompts/manifest")
        document = broken_client.get(f"/v1/prompts/{KEY}/{VERSION}")

    assert manifest.status_code == 503
    envelope = manifest.json()
    assert envelope["code"] == "PROMPT_UNAVAILABLE"
    assert envelope["retryable"] is False
    assert envelope["details"]["reason"]
    assert str(tmp_path) not in manifest.text

    assert document.status_code == 503
    assert document.json()["code"] == "PROMPT_UNAVAILABLE"


def test_tampered_template_returns_503_envelope(tmp_path: Path) -> None:
    pack = tmp_path / "prompts"
    shutil.copytree(PROMPTS_DIR, pack)
    store = PromptStore(pack)
    assert store.readiness_failure() is None
    template = pack / KEY / f"{VERSION}.md"
    template.write_bytes(template.read_bytes() + b"tampered\n")

    with TestClient(_build_app(store)) as tampered_client:
        response = tampered_client.get(f"/v1/prompts/{KEY}/{VERSION}")

    assert response.status_code == 503
    envelope = response.json()
    assert envelope["code"] == "PROMPT_UNAVAILABLE"
    assert "sha256" in envelope["details"]["reason"]
    assert str(pack) not in response.text


def test_missing_store_returns_500_internal() -> None:
    app = _build_app(None)

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/v1/prompts/manifest")

    assert response.status_code == 500
    assert response.json() == {"code": "INTERNAL", "message": "内部错误", "retryable": True}
