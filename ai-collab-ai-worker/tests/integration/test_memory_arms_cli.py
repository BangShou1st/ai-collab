"""``ai-eval memory-arms`` CLI：默认逐字节稳定、C NOT_VERIFIED、门禁 NOT_EVALUATED。"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from ai_collab_worker.eval.canonical import canonicalize_document, digest

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ARMS_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_arms" / "arms_cases.v1.json"
CLI_MODULE = "ai_collab_worker.eval.cli"


def _run(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, "-m", CLI_MODULE, *args],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=300,
    )


def test_json_report_is_byte_stable_and_self_consistent() -> None:
    first = _run("memory-arms", str(ARMS_SET), "--json")
    second = _run("memory-arms", str(ARMS_SET), "--json")

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first.stdout == second.stdout
    assert first.stderr == b""
    assert not first.stdout.endswith(b"\n")

    payload = json.loads(first.stdout)
    assert payload["gate"]["status"] == "NOT_EVALUATED"
    assert payload["arms"]["A_LEGACY"]["status"] == "DIAGNOSTIC_ONLY"
    assert payload["arms"]["B_GOVERNED_RECALL"]["status"] == "DIAGNOSTIC_ONLY"
    assert payload["arms"]["C_DREAMING"]["status"] == "NOT_VERIFIED"
    assert payload["arms"]["C_DREAMING"]["cases"] == []
    assert payload["arms"]["B_GOVERNED_RECALL"]["revocationLeaks"] == 0
    assert payload["evidence"] == "TESTED (local mirror)"

    body = {key: value for key, value in payload.items() if key != "sha256"}
    assert payload["sha256"] == digest(canonicalize_document(body))
    assert list(payload) == sorted(payload)


def test_text_summary_reports_gate_and_c_status() -> None:
    result = _run("memory-arms", str(ARMS_SET))

    assert result.returncode == 0, result.stderr
    text = result.stdout.decode("utf-8")
    assert "A_LEGACY: status=DIAGNOSTIC_ONLY" in text
    assert "B_GOVERNED_RECALL: status=DIAGNOSTIC_ONLY" in text
    assert "C_DREAMING: status=NOT_VERIFIED" in text
    assert "gate=NOT_EVALUATED" in text
    assert "Traceback" not in text


def test_measure_latency_reports_local_scope() -> None:
    result = _run("memory-arms", str(ARMS_SET), "--measure-latency", "--runs", "3", "--json")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    latency = payload["arms"]["A_LEGACY"]["latency"]
    assert latency["scope"] == "local-retrieval-only-non-production"
    assert latency["samples"] == 33


def test_invalid_fixture_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text("{}", encoding="utf-8")
    result = _run("memory-arms", str(bad), "--json")

    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr


def test_missing_subcommand_lists_memory_arms() -> None:
    result = _run()

    assert result.returncode == 2
    assert b"memory-arms" in result.stderr
