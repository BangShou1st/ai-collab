"""Prometheus 指标与 structlog 配置的单元测试。

只装配真实 app 并用 TestClient 打本地端口内的请求，零网络、零外部进程。
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
import structlog
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import REGISTRY, UNMATCHED_PATH, render_metrics


@pytest.fixture(autouse=True)
def _restore_log_level() -> Iterator[None]:
    """每个用例后把日志级别还原成 INFO，避免级别用例污染后续用例。"""

    yield
    configure_logging("INFO")


@pytest.fixture
def app(tmp_path: Path) -> FastAPI:
    return create_app(Settings(prompts_dir=tmp_path / "prompts"))


def _counter_value(method: str, path: str, status: str) -> float:
    """读 ``ai_worker_http_requests_total`` 当前值（序列不存在视为 0）。"""

    value = REGISTRY.get_sample_value(
        "ai_worker_http_requests_total",
        {"method": method, "path": path, "status": status},
    )
    return 0.0 if value is None else value


def _histogram_count(method: str, path: str) -> float:
    """读 ``ai_worker_http_request_duration_seconds_count`` 当前值。"""

    value = REGISTRY.get_sample_value(
        "ai_worker_http_request_duration_seconds_count",
        {"method": method, "path": path},
    )
    return 0.0 if value is None else value


def test_render_metrics_exposes_info_and_text_content_type(app: FastAPI) -> None:
    with TestClient(app) as client:
        assert client.get("/healthz").status_code == 200

    payload, content_type = render_metrics()
    assert b"ai_worker_info" in payload
    assert REGISTRY.get_sample_value("ai_worker_info", {"version": "0.1.0", "role": "api"}) == 1.0
    assert "text/plain" in content_type


def test_http_requests_counter_uses_route_template(app: FastAPI) -> None:
    before = _counter_value("GET", "/healthz", "200")
    with TestClient(app) as client:
        assert client.get("/healthz").status_code == 200
    assert _counter_value("GET", "/healthz", "200") == before + 1


def test_http_duration_histogram_uses_route_template(app: FastAPI) -> None:
    before = _histogram_count("GET", "/healthz")
    with TestClient(app) as client:
        assert client.get("/healthz").status_code == 200
    assert _histogram_count("GET", "/healthz") == before + 1


def test_unmatched_path_is_labelled_unmatched(app: FastAPI) -> None:
    raw_path = "/does-not-exist/ABC123"
    before = _counter_value("GET", UNMATCHED_PATH, "404")
    with TestClient(app) as client:
        assert client.get(raw_path).status_code == 404

    assert _counter_value("GET", UNMATCHED_PATH, "404") == before + 1
    assert _counter_value("GET", raw_path, "404") == 0.0
    payload, _ = render_metrics()
    assert b"does-not-exist" not in payload


def test_query_string_never_becomes_label(app: FastAPI) -> None:
    before = _counter_value("GET", "/healthz", "200")
    with TestClient(app) as client:
        assert client.get("/healthz?token=LEAK-QUERY").status_code == 200

    payload, _ = render_metrics()
    assert b"LEAK-QUERY" not in payload
    assert _counter_value("GET", "/healthz", "200") == before + 1


def test_configure_logging_is_idempotent_and_emits_json(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging("INFO")
    configure_logging("INFO")
    structlog.get_logger("unit").info("metric_check", answer=42)

    lines = [line for line in capsys.readouterr().out.splitlines() if "metric_check" in line]
    assert len(lines) == 1
    payload = json.loads(lines[0])
    assert payload["event"] == "metric_check"
    assert payload["level"] == "info"
    assert payload["answer"] == 42


def test_configure_logging_respects_level(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("WARNING")
    structlog.get_logger("unit").info("hidden_event")
    structlog.get_logger("unit").warning("visible_event")

    out = capsys.readouterr().out
    assert "hidden_event" not in out
    assert "visible_event" in out


def test_configure_logging_rejects_unknown_level() -> None:
    with pytest.raises(ValueError, match="非法日志级别"):
        configure_logging("VERBOSE")
