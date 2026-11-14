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


MEMORY_V2 = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v2.jsonl"
MEMORY_REAL = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_real_gemini31.v1.jsonl"


def test_default_report_sha_is_frozen() -> None:
    result = _run("memory", str(MEMORY_SET), "--json")

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["sha256"] == (
        "74177ebe90cdf98881474382c02d1ac08ebdef0deb970c4c6aaa81bd102fad33"
    )


def test_v2_exact_output_is_byte_identical_to_v1() -> None:
    assert (
        _run("memory", str(MEMORY_V2), "--json").stdout
        == _run("memory", str(MEMORY_SET), "--json").stdout
    )


def test_semantic_real_replay_outputs_audit_and_diagnostics() -> None:
    result = _run(
        "memory",
        str(MEMORY_REAL),
        "--matcher",
        "semantic",
        "--evidence",
        "REPLAY (archived real model output)",
        "--quality-claim",
        "NOT_VERIFIED",
        "--model",
        "gemini-3.1-flash-lite",
        "--json",
    )

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["matcher"] == "semantic"
    assert payload["diagnosticOnly"] is True
    assert payload["model"] == "gemini-3.1-flash-lite"
    assert payload["aggregate"]["precision"] == 5 / 33
    assert payload["aggregate"]["recall"] == 0.25
    assert len(payload["matchAudit"]) == 5
    assert "不是人工语义裁决" in payload["limitations"]


def test_d10_labels_are_passed_through_and_change_sha() -> None:
    default = _run("memory", str(MEMORY_SET), "--json")
    explicit = _run(
        "memory",
        str(MEMORY_SET),
        "--json",
        "--evidence",
        "LIVE_VERIFIED (staging, real model)",
        "--quality-claim",
        "QUALITY_OK",
        "--model",
        "gemini-3.1-flash-lite",
    )

    assert default.returncode == 0, default.stderr
    assert explicit.returncode == 0, explicit.stderr
    payload = json.loads(explicit.stdout)
    assert payload["evidence"] == "LIVE_VERIFIED (staging, real model)"
    assert payload["qualityClaim"] == "QUALITY_OK"
    assert payload["model"] == "gemini-3.1-flash-lite"
    assert payload["sha256"] != json.loads(default.stdout)["sha256"]


def test_empty_evidence_label_returns_2() -> None:
    result = _run("memory", str(MEMORY_SET), "--json", "--evidence", "")

    assert result.returncode == 2
    assert b"--evidence" in result.stderr
    assert b"Traceback" not in result.stderr
