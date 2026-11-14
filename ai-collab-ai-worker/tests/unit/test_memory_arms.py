"""D7-B：三组跨会话记忆对照骨架（A/B 本地镜像，C NOT_VERIFIED）。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.eval.canonical import DatasetError, canonicalize_document
from ai_collab_worker.eval.memory_arms import (
    ARM_DREAMING,
    ARM_GOVERNED,
    ARM_LEGACY,
    GATE_NOT_EVALUATED,
    NOT_VERIFIED,
    evaluate_memory_arms,
    load_memory_arms,
    report_document,
    seal_report,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ARMS_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_arms" / "arms_cases.v1.json"


def _report() -> dict[str, object]:
    dataset = load_memory_arms(ARMS_SET)
    return report_document(evaluate_memory_arms(dataset))


def test_fixture_has_eleven_cases_and_shared_pool() -> None:
    dataset = load_memory_arms(ARMS_SET)

    assert len(dataset.cases) == 11
    assert len(dataset.project_snapshots) == 2
    assert len(dataset.sessions) == 12
    assert any(memory.project_id == "p-beta" for memory in dataset.memories)
    assert any(memory.status == "DISABLED" for memory in dataset.memories)
    assert any(memory.verification_state == "SOURCE_REVOKED" for memory in dataset.memories)
    assert any(memory.superseded_by is not None for memory in dataset.memories)
    assert any(memory.verification_state == "STALE" for memory in dataset.memories)


def test_a_b_diagnostic_metrics() -> None:
    arms = _report()["arms"]
    assert isinstance(arms, dict)
    legacy = arms[ARM_LEGACY]
    governed = arms[ARM_GOVERNED]

    assert legacy["status"] == "DIAGNOSTIC_ONLY"
    assert legacy["recallAtK"] == pytest.approx(5 / 11)
    assert legacy["citationValidityRate"] == pytest.approx(0.7)
    assert legacy["conflictMisuseRate"] == 1.0
    assert legacy["revocationLeaks"] == 33
    assert legacy["crossProjectLeaks"] == 0

    assert governed["status"] == "DIAGNOSTIC_ONLY"
    assert governed["recallAtK"] == 1.0
    assert governed["citationValidityRate"] == 1.0
    assert governed["conflictMisuseRate"] == 0.0
    assert governed["revocationLeaks"] == 0
    assert governed["crossProjectLeaks"] == 0


def test_revocation_leak_case_is_explicit_for_a_and_zero_for_b() -> None:
    arms = _report()["arms"]
    assert isinstance(arms, dict)
    legacy_case = next(case for case in arms[ARM_LEGACY]["cases"] if case["id"] == "ma-004")
    governed_case = next(case for case in arms[ARM_GOVERNED]["cases"] if case["id"] == "ma-004")

    assert set(legacy_case["revocationLeaks"]) == {"mem-a13", "mem-a14", "mem-a16"}
    assert governed_case["revocationLeaks"] == []


def test_c_dreaming_is_not_verified_and_not_faked() -> None:
    arms = _report()["arms"]
    assert isinstance(arms, dict)
    dreaming = arms[ARM_DREAMING]

    assert dreaming["status"] == NOT_VERIFIED
    assert "未执行真实 C 对照" in str(dreaming["reason"])
    assert dreaming["cases"] == []
    for key in (
        "recallAtK",
        "citationValidityRate",
        "conflictMisuseRate",
        "revocationLeaks",
        "crossProjectLeaks",
        "taskAccuracy",
        "tokens",
        "latency",
        "dreamingCost",
        "humanApprovalRate",
    ):
        assert dreaming[key] == NOT_VERIFIED


def test_gate_is_not_evaluated_not_pass_or_fail() -> None:
    document = _report()
    gate = document["gate"]
    assert isinstance(gate, dict)

    assert gate["status"] == GATE_NOT_EVALUATED
    assert "不得写成 PASS/FAIL" in str(gate["reason"])
    assert "NOT_EVALUATED" != "PASS"
    assert "NOT_EVALUATED" != "FAIL"


def test_report_is_byte_stable() -> None:
    first = canonicalize_document(seal_report(_report()))
    second = canonicalize_document(seal_report(_report()))

    assert first == second
    payload = json.loads(first)
    assert payload["evidence"] == "TESTED (local mirror)"
    assert payload["qualityClaim"] == "NOT_VERIFIED"


def test_latency_is_not_reported_by_default_and_local_scope_when_enabled() -> None:
    dataset = load_memory_arms(ARMS_SET)
    default = report_document(evaluate_memory_arms(dataset))
    measured = report_document(evaluate_memory_arms(dataset, measure_latency=True, runs=3))

    assert default["arms"][ARM_LEGACY]["latency"] == NOT_VERIFIED
    latency = measured["arms"][ARM_LEGACY]["latency"]
    assert isinstance(latency, dict)
    assert latency["scope"] == "local-retrieval-only-non-production"
    assert latency["samples"] == 11 * 3
    assert 0.0 <= latency["p50Ms"] <= latency["p95Ms"]


def test_empty_document_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "empty.json"
    path.write_text("{}", encoding="utf-8")

    with pytest.raises(DatasetError):
        load_memory_arms(path)


def test_invalid_fixture_is_rejected(tmp_path: Path) -> None:
    payload = json.loads(ARMS_SET.read_text(encoding="utf-8"))
    payload["cases"][0]["expectedRecallIds"] = ["mem-missing"]
    path = tmp_path / "bad.json"
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(DatasetError, match="不存在记忆"):
        load_memory_arms(path)


def test_wrong_extension_and_bom_are_rejected(tmp_path: Path) -> None:
    wrong = tmp_path / "cases.jsonl"
    wrong.write_text("{}", encoding="utf-8")
    with pytest.raises(DatasetError, match=r"\.json"):
        load_memory_arms(wrong)

    bom = tmp_path / "bom.json"
    bom.write_bytes(b"\xef\xbb\xbf{}")
    with pytest.raises(DatasetError, match="BOM"):
        load_memory_arms(bom)
