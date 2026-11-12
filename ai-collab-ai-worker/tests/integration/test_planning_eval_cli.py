"""``ai-eval planning`` 的 CLI 集成测试：两次运行逐字节一致、sha256 自洽、退出码 0/2。"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

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
    assert payload["aggregate"]["cases"] == 21
    assert payload["aggregate"]["schemaPassRate"] == pytest.approx(16 / 21)
    assert payload["aggregate"]["nonEmptySourceRefs"] == 0
    assert payload["aggregate"]["bySubset"]["golden"] == {
        "cases": 16,
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

    assert text.startswith("scorer=replay cases=21\n")
    assert "schemaPassRate=0.761905 confidence=LOW_CONFIDENCE" in text
    assert "sourceViolations=5 assigneeViolations=1 duplicateTitles=1 nonEmptySourceRefs=0" in text
    assert "golden: cases=16 schemaPassRate=1.000000" in text
    assert "adversarial: cases=5 rejectedRate=1.000000" in text
    assert "limitations: schemaPassRate>=0.95 只在 golden 子集与 stub 冻结集输出上测量" in text
    assert "sourceRefs 恒为空数组" in text
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


PLANNING_REAL = (
    PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_real_gemini31.v1.jsonl"
)


def test_default_report_sha_is_frozen() -> None:
    result = _run("planning", str(PLANNING_SET), "--json")

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["sha256"] == (
        "00e040beae57498472839931bbd9eabedac9be53cbb40adce08263e5ef53efc8"
    )


def test_adversarial_mutations_report_is_stable_and_all_rejected() -> None:
    args = ("planning", str(PLANNING_SET), "--adversarial-mutations", "--json")
    first = _run(*args)
    second = _run(*args)

    assert first.returncode == 0, first.stderr
    assert first.stdout == second.stdout
    payload = json.loads(first.stdout)
    aggregate = payload["aggregate"]
    assert aggregate["mutationSource"] == "replay_dataset"
    assert aggregate["mutationCases"] == 16
    assert aggregate["mutationVariants"] == 80
    assert aggregate["mutationRejectedRate"] == 1.0
    mutations = aggregate["bySubset"]["adversarialMutations"]
    assert mutations["rejectedRate"] == 1.0
    assert mutations["source"] == "replay_dataset"
    assert "不再作为校验器证据" in mutations["note"]
    golden_case = next(case for case in payload["cases"] if case["subset"] == "golden")
    assert len(golden_case["mutations"]) == 5
    assert all(item["rejected"] for item in golden_case["mutations"])


def test_real_gemini31_mutations_are_all_rejected() -> None:
    result = _run(
        "planning",
        str(PLANNING_REAL),
        "--adversarial-mutations",
        "--evidence",
        "REPLAY (archived real model output)",
        "--model",
        "gemini-3.1-flash-lite",
        "--json",
    )

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["evidence"] == "REPLAY (archived real model output)"
    assert payload["model"] == "gemini-3.1-flash-lite"
    assert payload["aggregate"]["schemaPassRate"] == 1.0
    assert payload["aggregate"]["mutationVariants"] == 21 * 5
    assert payload["aggregate"]["mutationRejectedRate"] == 1.0


def test_d10_labels_are_passed_through_and_change_sha() -> None:
    default = _run("planning", str(PLANNING_SET), "--json")
    explicit = _run(
        "planning",
        str(PLANNING_SET),
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


def test_empty_quality_claim_returns_2() -> None:
    result = _run("planning", str(PLANNING_SET), "--json", "--quality-claim", "")

    assert result.returncode == 2
    assert b"--quality-claim" in result.stderr
    assert b"Traceback" not in result.stderr
