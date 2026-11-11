"""``tools/stub_java_gateway.py`` 的进程内测试。

不启子进程、不走生产代码路径：直接 ``create_server(port=0)`` 起一个线程内的
``ThreadingHTTPServer``，用 ``urllib`` POST 一次 ``/internal/ai/v1/model-calls``，
断言冻结 §1.6 的固定响应形状、``latencyMs=0``，以及 rerank content 能被生产
``parse_scores`` + ``score_candidates`` 完整消费（不覆盖任何候选）。
"""

from __future__ import annotations

import importlib.util
import json
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from ai_collab_worker.cognition.rerank.parser import parse_scores
from ai_collab_worker.cognition.rerank.scorer import score_candidates
from ai_collab_worker.contract.rerank import RerankCandidate

PROJECT_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = PROJECT_ROOT / "tools" / "stub_java_gateway.py"
MODEL_PATH = "/internal/ai/v1/model-calls"


def _load_tool() -> ModuleType:
    spec = importlib.util.spec_from_file_location("stub_java_gateway", TOOL_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def stub_module() -> ModuleType:
    return _load_tool()


def _serve(module: ModuleType, **kwargs: Any) -> Iterator[tuple[int, Any]]:
    server = module.create_server(port=0, **kwargs)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield int(server.server_address[1]), server
    finally:
        server.shutdown()
        thread.join(timeout=5)
        server.server_close()


def _prompt(indices: list[int] | None) -> str:
    if indices is None:
        return "Query: 备份策略\n\nCandidate Passages:\n没有编号的候选\n"
    lines = ["Query: 备份策略\n", "\n", "Candidate Passages:\n"]
    for index in indices:
        lines.append(f"[{index}] Content: 第{index}段正文\n\n")
    return "".join(lines)


def _call(port: int, path: str, *, data: bytes | None = None) -> tuple[int, bytes]:
    """发一次请求，返回 ``(status, body)``；4xx/5xx 也按状态码返回而不抛异常。"""

    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST" if data is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return int(response.status), response.read()
    except urllib.error.HTTPError as exc:
        return int(exc.code), exc.read()


def _post(port: int, payload: object) -> tuple[int, dict[str, Any]]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    status, raw = _call(port, MODEL_PATH, data=data)
    body = json.loads(raw)
    assert isinstance(body, dict)
    return status, body


def test_stub_returns_frozen_shape_and_zero_latency(stub_module: ModuleType) -> None:
    for port, server in _serve(stub_module, scenario="rerank"):
        assert server.server_address[0] == "127.0.0.1"
        status, body = _post(
            port,
            {
                "schemaVersion": 1,
                "purpose": "KNOWLEDGE_CHAT",
                "systemPrompt": "system",
                "userPrompt": _prompt([0, 1]),
                "outputFormat": "PROMPT_JSON",
                "deadlineMs": 3000,
                "callId": "rerank:test:1",
            },
        )

    assert status == 200
    assert set(body) == {"schemaVersion", "content", "usage", "modelAlias", "latencyMs"}
    assert body["schemaVersion"] == 1
    assert body["modelAlias"] == "stub-rerank"
    assert body["usage"] == {"inputTokens": 0, "outputTokens": 0, "source": "ESTIMATE"}
    # 冻结 §1.6：stub 固定 latencyMs=0（已知真值，而不是"未知"）。
    assert body["latencyMs"] == 0


def test_stub_content_covers_every_prompt_index_and_parser_accepts_it(
    stub_module: ModuleType,
) -> None:
    indices = list(range(20))
    for port, _server in _serve(stub_module, scenario="rerank"):
        _status, body = _post(port, {"userPrompt": _prompt(indices)})

    candidates = [RerankCandidate(id=f"c{index}", title="", text="正文") for index in indices]
    raw_scores = parse_scores(body["content"])
    scoring = score_candidates(candidates, raw_scores)

    assert set(raw_scores) == set(indices)
    assert all(0.0 <= value <= 1.0 for value in raw_scores.values())
    # 生产解析 + 打分链路不产生任何缺失 id（否则 stub 基准会落到 partial）。
    assert scoring.missing_ids == ()
    assert [score.id for score in scoring.scores] == [candidate.id for candidate in candidates]


def test_stub_content_ignores_unlisted_indexes_and_is_deterministic(
    stub_module: ModuleType,
) -> None:
    for port, _server in _serve(stub_module, scenario="rerank"):
        _status, first = _post(port, {"userPrompt": _prompt([0, 2, 4])})
        _status, second = _post(port, {"userPrompt": _prompt([0, 2, 4])})

    assert first["content"] == second["content"]
    assert json.loads(first["content"]) == {"0": 0.9, "2": 0.7, "4": 0.5}


def test_stub_falls_back_to_eight_candidates_without_markers(
    stub_module: ModuleType,
) -> None:
    for port, _server in _serve(stub_module, scenario="rerank"):
        _status, body = _post(port, {"userPrompt": _prompt(None)})

    assert set(json.loads(body["content"])) == {str(index) for index in range(8)}


def test_stub_delay_is_applied_but_latency_stays_zero(stub_module: ModuleType) -> None:
    for port, _server in _serve(stub_module, scenario="rerank", delay_ms=50):
        started = time.perf_counter()
        _status, body = _post(port, {"userPrompt": _prompt([0])})
        elapsed = time.perf_counter() - started

    assert elapsed >= 0.04
    assert body["latencyMs"] == 0
    assert set(json.loads(body["content"])) == {"0"}


@pytest.mark.parametrize("scenario", ["decide", "plan", "planning", "memory"])
def test_stub_placeholder_scenarios_return_parseable_content(
    stub_module: ModuleType, scenario: str
) -> None:
    for port, _server in _serve(stub_module, scenario=scenario):
        _status, body = _post(port, {"userPrompt": _prompt([0])})

    assert body["latencyMs"] == 0
    assert body["modelAlias"] == "stub-rerank"
    assert json.loads(body["content"]) == {"stub": True, "scenario": scenario}


def test_stub_health_and_unknown_path(stub_module: ModuleType) -> None:
    for port, _server in _serve(stub_module, scenario="rerank"):
        status, raw = _call(port, "/healthz")
        assert status == 200
        assert json.loads(raw) == {"status": "ok"}
        unknown_status, _unknown_body = _call(port, "/internal/ai/v1/other", data=b"{}")
        assert unknown_status == 404


def test_stub_rejects_invalid_json_body_without_echoing_it(stub_module: ModuleType) -> None:
    for port, _server in _serve(stub_module, scenario="rerank"):
        status, raw = _call(port, MODEL_PATH, data=b"not-json")

    assert status == 400
    assert raw == b'{"error":"invalid_json"}'


def test_stub_create_server_validates_arguments(stub_module: ModuleType) -> None:
    with pytest.raises(ValueError, match="scenario"):
        stub_module.create_server(port=0, scenario="unknown")
    with pytest.raises(ValueError, match="delay_ms"):
        stub_module.create_server(port=0, scenario="rerank", delay_ms=-1)
