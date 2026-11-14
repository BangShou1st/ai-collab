"""``ai-eval memory`` 评测口径（冻结 §4.2）：precision/recall/F1、重复率与冻结集校验。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_document,
    canonicalize_path,
    digest,
)
from ai_collab_worker.eval.memory import (
    LIMITATIONS,
    MATCHER_SEMANTIC,
    canonical_report_bytes,
    evaluate,
    load_memory_set,
    report_document,
    seal_report,
    semantic_score,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MEMORY_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v1.jsonl"
MEMORY_V2 = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v2.jsonl"
MEMORY_REAL = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_real_gemini31.v1.jsonl"


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


def _v2_line(case_id: str) -> dict[str, object]:
    for line in MEMORY_V2.read_text(encoding="utf-8").splitlines():
        parsed: dict[str, object] = json.loads(line)
        if parsed["id"] == case_id:
            return parsed
    raise AssertionError(f"v2 缺少 case {case_id}")


def test_v2_has_fact_refs_and_keeps_all_judgements() -> None:
    cases = load_memory_set(MEMORY_V2)

    assert len(cases) == 14
    assert sum(len(case.judgement) for case in cases) == 20
    for case in cases:
        fact_refs = {fact.ref for fact in case.snapshot.facts}
        assert all(judgement.fact_refs for judgement in case.judgement)
        for judgement in case.judgement:
            assert set(judgement.fact_refs) <= fact_refs
    for case_id in ("m-005", "m-009", "m-010"):
        case = next(case for case in cases if case.id == case_id)
        assert any(fact.ref == "fact-4" for fact in case.snapshot.facts)


def test_v2_fingerprint_is_frozen() -> None:
    assert digest(canonicalize_path(MEMORY_V2)) == (
        "73f3ef790c6d95623494bb0f84823b39e0196c579c42eade21a8c6d14c5c98a0"
    )
    assert digest(canonicalize_path(MEMORY_REAL)) == (
        "5a317baa2b28efb127f4e11d3fe22c065e3708e5c17e3e60efb60431a0370151"
    )


def test_v2_exact_report_is_byte_identical_to_v1() -> None:
    v1_bytes = canonical_report_bytes(report_document(evaluate(load_memory_set(MEMORY_SET))))
    v2_bytes = canonical_report_bytes(report_document(evaluate(load_memory_set(MEMORY_V2))))

    assert v1_bytes == v2_bytes
    assert json.loads(v1_bytes)["sha256"] == (
        "74177ebe90cdf98881474382c02d1ac08ebdef0deb970c4c6aaa81bd102fad33"
    )


def test_real_replay_exact_matches_s3_sealed_aggregate() -> None:
    document = seal_report(report_document(evaluate(load_memory_set(MEMORY_REAL))))

    assert document["aggregate"]["precision"] == 0.0
    assert document["aggregate"]["recall"] == 0.0
    # 与 S3 归档 aggregate-memory.json 的 sha256 一致，证明 replay 输入/口径未漂移。
    assert document["sha256"] == (
        "d5e2abe21070fc219d1be4c0f1d74a7e011c62f31c414e21e4be0359575c3ac2"
    )


def test_semantic_real_replay_audit_and_metrics() -> None:
    report = evaluate(load_memory_set(MEMORY_REAL), matcher=MATCHER_SEMANTIC)
    document = report_document(report)

    assert document["matcher"] == "semantic"
    assert document["diagnosticOnly"] is True
    assert "不是人工语义裁决" in document["limitations"]
    assert report.precision == pytest.approx(5 / 33)
    assert report.recall == pytest.approx(5 / 20)
    assert report.duplicate_rate == 0.0
    audit = document["matchAudit"]
    assert len(audit) == 5
    assert {entry["caseId"] for entry in audit} == {"m-001", "m-003", "m-011", "m-013", "m-014"}
    assert all(entry["method"] == "semantic" for entry in audit)
    assert all(entry["score"] >= 0.82 for entry in audit)
    assert all("candidateTitle" in entry and "judgementTitle" in entry for entry in audit)


def test_exact_mode_never_writes_semantic_audit() -> None:
    document = report_document(evaluate(load_memory_set(MEMORY_REAL)))

    assert "matcher" not in document
    assert "matchAudit" not in document
    assert "diagnosticOnly" not in document


@pytest.mark.parametrize(
    ("candidate", "judgement"),
    [
        ("写操作必须人工复核", "写操作必须人工审批"),
        ("迭代长度调整为三周", "迭代长度调整为两周"),
        ("外部排期无需预留缓冲", "外部排期要预留缓冲"),
        ("检索链路统一走 Python 网关", "检索链路统一走 Java 网关"),
    ],
)
def test_semantic_matcher_rejects_near_miss_titles(candidate: str, judgement: str) -> None:
    assert semantic_score(candidate, judgement) is None


@pytest.mark.parametrize(
    ("candidate", "judgement"),
    [
        ("写操作必须经过人工审批", "写操作必须人工审批"),
        ("检索链路统一至 Java 网关", "检索链路统一走 Java 网关"),
    ],
)
def test_semantic_matcher_matches_real_rewrites(candidate: str, judgement: str) -> None:
    score = semantic_score(candidate, judgement)

    assert score is not None
    assert score >= 0.82


def test_loader_rejects_invalid_fact_refs(tmp_path: Path) -> None:
    raw = _v2_line("m-001")
    judgement = raw["humanJudgement"]
    assert isinstance(judgement, list)
    judgement[0]["factRefs"] = ["fact-999"]

    with pytest.raises(DatasetError, match="不存在的事实"):
        load_memory_set(_write(tmp_path, raw))


def test_loader_rejects_empty_or_duplicate_fact_refs(tmp_path: Path) -> None:
    raw = _v2_line("m-001")
    judgement = raw["humanJudgement"]
    assert isinstance(judgement, list)
    judgement[0]["factRefs"] = []
    with pytest.raises(DatasetError, match="不能为空"):
        load_memory_set(_write(tmp_path, raw))

    raw = _v2_line("m-001")
    judgement = raw["humanJudgement"]
    assert isinstance(judgement, list)
    judgement[0]["factRefs"] = ["fact-1", "fact-1"]
    with pytest.raises(DatasetError, match="重复 ref"):
        load_memory_set(_write(tmp_path, raw))
