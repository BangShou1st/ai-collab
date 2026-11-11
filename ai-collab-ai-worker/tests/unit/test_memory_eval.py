"""``ai-eval memory`` 评测口径（冻结 §4.2）：precision/recall/F1、重复率与冻结集校验。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.eval.canonical import DatasetError, canonicalize_document, digest
from ai_collab_worker.eval.memory import (
    LIMITATIONS,
    evaluate,
    load_memory_set,
    report_document,
    seal_report,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MEMORY_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v1.jsonl"


def test_frozen_set_has_fourteen_cases() -> None:
    cases = load_memory_set(MEMORY_SET)

    assert len(cases) == 14
    assert all(case.judgement for case in cases)


def test_aggregate_matches_frozen_baseline() -> None:
    report = evaluate(load_memory_set(MEMORY_SET))
    document = report_document(report)
    aggregate = document["aggregate"]

    assert aggregate["cases"] == 14
    assert aggregate["precision"] == pytest.approx(0.9)
    assert aggregate["recall"] == pytest.approx(0.9)
    assert aggregate["candidateAgreement"] == pytest.approx(0.9)
    assert aggregate["duplicateRate"] == pytest.approx(0.05)
    assert aggregate["schemaPassRate"] == pytest.approx(1.0)
    assert aggregate["candidates"] == 20
    assert aggregate["invalidSource"] == 1
    assert aggregate["invalidDedupe"] == 1
    assert aggregate["confidence"] == "LOW_CONFIDENCE"
    assert document["evidence"] == "TESTED (stub)"
    assert document["qualityClaim"] == "NOT_VERIFIED"
    assert document["limitations"] == LIMITATIONS


def test_evaluation_is_deterministic_and_canonical() -> None:
    cases = load_memory_set(MEMORY_SET)
    first = seal_report(report_document(evaluate(cases)))
    second = seal_report(report_document(evaluate(cases)))

    assert canonicalize_document(first) == canonicalize_document(second)
    assert first["sha256"] == digest(
        canonicalize_document({key: value for key, value in first.items() if key != "sha256"})
    )
    assert [case["id"] for case in first["cases"]] == sorted(case["id"] for case in first["cases"])


def test_candidate_agreement_is_f1() -> None:
    report = evaluate(load_memory_set(MEMORY_SET))

    expected = (
        2 * report.precision * report.recall / (report.precision + report.recall)
        if report.precision + report.recall
        else 0.0
    )
    assert report.candidate_agreement == pytest.approx(expected)


def _first_case() -> dict[str, object]:
    line = MEMORY_SET.read_text(encoding="utf-8").splitlines()[0]
    parsed: dict[str, object] = json.loads(line)
    return parsed


def _write(tmp_path: Path, case: dict[str, object]) -> Path:
    path = tmp_path / "bad.jsonl"
    path.write_text(json.dumps(case, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    return path


def test_loader_rejects_unknown_and_missing_fields(tmp_path: Path) -> None:
    raw = _first_case()
    with pytest.raises(DatasetError, match="未定义字段"):
        load_memory_set(_write(tmp_path, {**raw, "extra": 1}))

    missing = dict(raw)
    del missing["humanJudgement"]
    with pytest.raises(DatasetError, match="缺少字段"):
        load_memory_set(_write(tmp_path, missing))


def test_loader_rejects_bad_snapshot_and_judgement(tmp_path: Path) -> None:
    raw = _first_case()
    with pytest.raises(DatasetError, match="snapshot"):
        load_memory_set(_write(tmp_path, {**raw, "snapshot": {"schemaVersion": 1}}))
    with pytest.raises(DatasetError, match="humanJudgement"):
        load_memory_set(_write(tmp_path, {**raw, "humanJudgement": []}))
    with pytest.raises(DatasetError, match="kind"):
        load_memory_set(_write(tmp_path, {**raw, "humanJudgement": [{"title": "x"}]}))
    with pytest.raises(DatasetError, match="title"):
        load_memory_set(
            _write(tmp_path, {**raw, "humanJudgement": [{"kind": "LESSON", "title": ""}]})
        )
    with pytest.raises(DatasetError, match="未定义字段"):
        load_memory_set(
            _write(
                tmp_path,
                {**raw, "humanJudgement": [{"kind": "LESSON", "title": "x", "note": "y"}]},
            )
        )
