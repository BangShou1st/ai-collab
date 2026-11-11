"""``ai-eval planning`` 的 CLI 集成测试：两次运行逐字节一致、sha256 自洽、退出码 0/2。"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from ai_collab_worker.eval.canonical import canonicalize_document, digest

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PLANNING_SET = PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_baseline.v1.jsonl"
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
    result = _run("planning", str(PLANNING_SET), "--json")
    assert result.returncode == 0, result.stderr
    payload: dict[str, Any] = json.loads(result.stdout)
    return payload


def test_json_report_is_byte_stable_and_self_consistent() -> None:
    first = _run("planning", str(PLANNING_SET), "--json")
    second = _run("planning", str(PLANNING_SET), "--json")

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
    assert payload["targetSchemaPassRate"] == 0.95
    assert "不是真实模型结论" in payload["limitations"]
    assert payload["aggregate"]["cases"] == 20
    assert payload["aggregate"]["schemaPassRate"] == 0.75
    assert payload["aggregate"]["bySubset"]["golden"] == {
        "cases": 15,
        "schemaPassRate": 1.0,
    }

    body = {key: value for key, value in payload.items() if key != "sha256"}
    assert payload["sha256"] == digest(canonicalize_document(body))
    assert list(payload) == sorted(payload)
    assert b"\\u" not in first.stdout


def test_output_file_matches_stdout(tmp_path: Path) -> None:
    output = tmp_path / "report.json"
    result = _run("planning", str(PLANNING_SET), "--json", "--output", str(output))

    assert result.returncode == 0, result.stderr
    assert result.stdout == b""
    assert output.read_bytes() == _run("planning", str(PLANNING_SET), "--json").stdout


def test_text_summary_has_metrics_and_limitation() -> None:
    result = _run("planning", str(PLANNING_SET))
    assert result.returncode == 0, result.stderr
    text = result.stdout.decode("utf-8")

    assert text.startswith("scorer=replay cases=20\n")
    assert "schemaPassRate=0.750000 confidence=LOW_CONFIDENCE" in text
    assert "sourceViolations=3 assigneeViolations=1 duplicateTitles=1" in text
    assert "golden: cases=15 schemaPassRate=1.000000" in text
    assert "adversarial: cases=5 rejectedRate=1.000000" in text
    assert "limitations: schemaPassRate>=0.95 只在 golden 子集与 stub 冻结集输出上测量" in text
    assert f"sha256={_report()['sha256']}" in text
    assert "Traceback" not in text


def test_invalid_dataset_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.jsonl"
    bad.write_bytes(b'{"id":"x-only"}\n')
    result = _run("planning", str(bad), "--json")

    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr


def test_missing_subcommand_lists_planning_and_memory() -> None:
    result = _run()

    assert result.returncode == 2
    assert b"planning" in result.stderr
    assert b"memory" in result.stderr
