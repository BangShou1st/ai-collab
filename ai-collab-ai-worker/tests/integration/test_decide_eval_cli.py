"""``ai-eval decide`` 的集成测试（真实子进程 + MockTransport 注入 worker）。

覆盖：replay 报告逐字节稳定、canonical sha256 自洽、``--output`` 与 stdout 相同、
退出码 2/4、worker 请求契约（URL / Bearer / 身份字段 / 无 expected+modelOutput）、
worker 422 计入 case 指标而不是评测失败。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import httpx
import pytest

from ai_collab_worker.eval import cli, decide
from ai_collab_worker.eval.canonical import canonicalize_document, digest

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DECIDE_SET = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v1.jsonl"
CLI_MODULE = "ai_collab_worker.eval.cli"
TOKEN_ENV = "AI_EVAL_W4_S1_TEST_TOKEN"
PROMPT_VERSION = "2026-11-12.1"


def _run(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, "-m", CLI_MODULE, *args],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=300,
    )


def _replay_json() -> dict[str, Any]:
    result = _run("decide", str(DECIDE_SET), "--scorer", "replay", "--json")
    assert result.returncode == 0, result.stderr
    payload: dict[str, Any] = json.loads(result.stdout)
    return payload


def test_replay_json_is_byte_stable_and_self_consistent() -> None:
    first = _run("decide", str(DECIDE_SET), "--scorer", "replay", "--json")
    second = _run("decide", str(DECIDE_SET), "--scorer", "replay", "--json")

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first.stdout == second.stdout
    assert first.stderr == b""
    assert not first.stdout.endswith(b"\n")

    payload = json.loads(first.stdout)
    assert payload["schemaVersion"] == 1
    assert payload["scorer"] == "replay"
    assert payload["evidence"] == "TESTED (stub)"
    assert payload["aggregate"]["cases"] == 32
    assert payload["aggregate"]["confidence"] == "LOW_CONFIDENCE"
    assert payload["aggregate"]["decisionAgreement"] == pytest.approx(0.625)
    assert payload["aggregate"]["jsonValidityRate"] == pytest.approx(0.625)
    assert payload["aggregate"]["invalidToolCalls"] == 2
    assert payload["aggregate"]["schemaViolations"] == 10

    body = {key: value for key, value in payload.items() if key != "sha256"}
    assert payload["sha256"] == digest(canonicalize_document(body))
    assert b"\\u" not in first.stdout
    assert list(payload) == sorted(payload)


def test_output_file_matches_stdout(tmp_path: Path) -> None:
    output = tmp_path / "report.json"
    result = _run(
        "decide",
        str(DECIDE_SET),
        "--scorer",
        "replay",
        "--json",
        "--output",
        str(output),
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout == b""
    assert (
        output.read_bytes()
        == _run("decide", str(DECIDE_SET), "--scorer", "replay", "--json").stdout
    )


def test_text_summary_has_metrics_and_digest() -> None:
    result = _run("decide", str(DECIDE_SET), "--scorer", "replay")
    assert result.returncode == 0, result.stderr
    text = result.stdout.decode("utf-8")

    assert text.startswith("scorer=replay cases=32\n")
    assert "decisionAgreement=0.625000 jsonValidityRate=0.625000" in text
    assert "invalidToolCalls=2 schemaViolations=10 confidence=LOW_CONFIDENCE" in text
    assert "evidence=TESTED (stub)" in text
    assert f"sha256={_replay_json()['sha256']}" in text
    assert "Traceback" not in text


def test_invalid_dataset_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.jsonl"
    bad.write_bytes(b'{"id":"x-only"}\n')
    result = _run("decide", str(bad), "--json")

    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr


def test_worker_without_url_returns_2() -> None:
    result = _run("decide", str(DECIDE_SET), "--scorer", "worker", "--json")

    assert result.returncode == 2
    assert b"--worker-url" in result.stderr


def _mock_scorer(handler: Any) -> decide.WorkerScorer:
    return decide.WorkerScorer(
        "http://worker.test",
        token_env=TOKEN_ENV,
        transport=httpx.MockTransport(handler),
    )


def _worker_args() -> list[str]:
    return [
        "decide",
        str(DECIDE_SET),
        "--scorer",
        "worker",
        "--worker-url",
        "http://worker.test",
        "--service-token-env",
        TOKEN_ENV,
        "--json",
    ]


def _capture(monkeypatch: pytest.MonkeyPatch) -> list[bytes]:
    chunks: list[bytes] = []
    monkeypatch.setattr(cli, "_write_stdout", chunks.append)
    return chunks


def _response_for(body: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "decision": "CALL_TOOL",
        "toolCall": {"name": body["tools"][0]["name"], "arguments": {}},
        "usage": {"inputTokens": 10, "outputTokens": 5, "source": "PROVIDER"},
        "promptVersion": PROMPT_VERSION,
        "modelAlias": "agent-main",
        "latencyMs": 1,
    }


def test_worker_success_uses_frozen_request_and_is_byte_stable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    captured: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured.append(
            {
                "url": str(request.url),
                "authorization": request.headers.get("Authorization"),
                "body": body,
            }
        )
        return httpx.Response(200, json=_response_for(body))

    chunks = _capture(monkeypatch)
    monkeypatch.setattr(cli, "_make_decide_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == 0
    assert cli.main(_worker_args()) == 0

    assert len(chunks) == 2
    assert chunks[0] == chunks[1]
    payload = json.loads(chunks[0])
    assert payload["scorer"] == "worker"
    assert payload["evidence"] == "TESTED (worker)"
    assert payload["aggregate"]["cases"] == 32
    assert payload["aggregate"]["jsonValidityRate"] == 1.0
    assert payload["aggregate"]["invalidToolCalls"] == 0
    assert payload["aggregate"]["schemaViolations"] == 0

    cases = decide.load_decide_set(DECIDE_SET)
    expected_agreement = sum(
        1
        for case in cases
        if case.expected_decision.value == "CALL_TOOL"
        and case.expected_tool_name == "search_project_knowledge"
    ) / len(cases)
    assert payload["aggregate"]["decisionAgreement"] == pytest.approx(expected_agreement)

    first = captured[0]
    assert first["url"] == "http://worker.test/v1/agent/decide"
    assert first["authorization"] == "Bearer test-token-not-real"
    body = first["body"]
    assert set(body) == {
        "schemaVersion",
        "runId",
        "attempt",
        "turnNo",
        "snapshotHash",
        "scenario",
        "goal",
        "locale",
        "context",
        "tools",
        "policy",
        "budget",
    }
    assert body["attempt"] == 1
    assert body["turnNo"] == 0
    assert body["runId"].startswith("eval-decide-")
    assert body["snapshotHash"].startswith("sha256:")
    assert "expected" not in body
    assert "modelOutput" not in body


def test_worker_422_marks_case_invalid_instead_of_eval_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            422,
            json={
                "code": "SCHEMA_VIOLATION",
                "message": "模型决策输出不符合契约",
                "retryable": False,
            },
        )

    chunks = _capture(monkeypatch)
    monkeypatch.setattr(cli, "_make_decide_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == 0

    payload = json.loads(chunks[0])
    assert payload["aggregate"]["jsonValidityRate"] == 0.0
    assert payload["aggregate"]["schemaViolations"] == 32
    assert payload["aggregate"]["decisionAgreement"] == 0.0
    assert payload["aggregate"]["invalidToolCalls"] == 0
    assert payload["aggregate"]["confidence"] == "LOW_CONFIDENCE"


def test_worker_503_returns_exit_4(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            503,
            json={"code": "MODEL_UNAVAILABLE", "message": "模型不可用", "retryable": True},
        )

    monkeypatch.setattr(cli, "_make_decide_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED


def test_worker_503_without_envelope_maps_status(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="upstream down")

    monkeypatch.setattr(cli, "_make_decide_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    error = capsys.readouterr().err
    assert "code=MODEL_UNAVAILABLE" in error
    assert "status=503" in error
    assert "Traceback" not in error


def test_worker_invalid_contract_response_is_rejected(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"schemaVersion": 1, "decision": "CALL_TOOL"})

    monkeypatch.setattr(cli, "_make_decide_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=INVALID_RESPONSE" in capsys.readouterr().err


def test_worker_missing_service_token_fails_closed(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv(TOKEN_ENV, raising=False)
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=SERVICE_TOKEN_MISSING" in capsys.readouterr().err


def test_worker_without_httpx_reports_client_unavailable() -> None:
    prelude = "import sys; sys.modules['httpx'] = None; "
    set_path = json.dumps(str(DECIDE_SET))
    env = {**os.environ, TOKEN_ENV: "test-token-not-real"}
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            prelude
            + "from ai_collab_worker.eval.cli import main; "
            + f"raise SystemExit(main(['decide', {set_path}, '--scorer', 'worker', "
            + "'--worker-url', 'http://worker.test', "
            + f"'--service-token-env', {json.dumps(TOKEN_ENV)}, '--json']))",
        ],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=120,
        env=env,
    )
    assert result.returncode == 4
    assert b"code=WORKER_CLIENT_UNAVAILABLE" in result.stderr
