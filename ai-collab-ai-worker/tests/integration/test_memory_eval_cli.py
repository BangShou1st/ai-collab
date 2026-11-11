"""``ai-eval memory`` 的 CLI 集成测试：逐字节稳定、sha256 自洽、指标与退出码。"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from ai_collab_worker.eval.canonical import canonicalize_document, digest

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MEMORY_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v1.jsonl"
CLI_MODULE = "ai_collab_worker.eval.cli"


def _run(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, "-m", CLI_MODULE, *args],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=300,
    )


def _report() -> dict[str, Any]:
    result = _run("memory", str(MEMORY_SET), "--json")
    assert result.returncode == 0, result.stderr
    payload: dict[str, Any] = json.loads(result.stdout)
    return payload


def test_json_report_is_byte_stable_and_self_consistent() -> None:
    first = _run("memory", str(MEMORY_SET), "--json")
    second = _run("memory", str(MEMORY_SET), "--json")

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first.stdout == second.stdout
    assert first.stderr == b""
    assert not first.stdout.endswith(b"\n")

    payload = json.loads(first.stdout)
    assert payload["schemaVersion"] == 1
    assert payload["scorer"] == "replay"
    assert payload["evidence"] == "TESTED (stub)"
    assert payload["qualityClaim"] == "NOT_VERIFIED"
    assert "不是真实模型结论" in payload["limitations"]
    assert payload["aggregate"]["cases"] == 14
    assert payload["aggregate"]["precision"] == 0.9
    assert payload["aggregate"]["recall"] == 0.9
    assert payload["aggregate"]["candidateAgreement"] == 0.9
    assert payload["aggregate"]["duplicateRate"] == 0.05
    assert payload["aggregate"]["schemaPassRate"] == 1.0

    body = {key: value for key, value in payload.items() if key != "sha256"}
    assert payload["sha256"] == digest(canonicalize_document(body))
    assert list(payload) == sorted(payload)
    assert b"\\u" not in first.stdout


def test_output_file_matches_stdout(tmp_path: Path) -> None:
    output = tmp_path / "report.json"
    result = _run("memory", str(MEMORY_SET), "--json", "--output", str(output))

    assert result.returncode == 0, result.stderr
    assert result.stdout == b""
    assert output.read_bytes() == _run("memory", str(MEMORY_SET), "--json").stdout


def test_text_summary_has_metrics_and_limitation() -> None:
    result = _run("memory", str(MEMORY_SET))
    assert result.returncode == 0, result.stderr
    text = result.stdout.decode("utf-8")

    assert text.startswith("scorer=replay cases=14\n")
    assert "precision=0.900000 recall=0.900000 candidateAgreement=0.900000" in text
    assert "duplicateRate=0.050000" in text
    assert "schemaPassRate=1.000000 candidates=20 confidence=LOW_CONFIDENCE" in text
    assert "limitations: precision/recall/candidateAgreement/duplicateRate 只在 stub" in text
    assert f"sha256={_report()['sha256']}" in text
    assert "Traceback" not in text


def test_invalid_dataset_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.jsonl"
    bad.write_bytes(b'{"id":"x-only"}\n')
    result = _run("memory", str(bad), "--json")

    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr
