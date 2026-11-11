"""``ai-eval planning`` 评测口径（冻结 §4.1）：聚合值、子集拆分与冻结集校验。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.eval.canonical import DatasetError, canonicalize_document, digest
from ai_collab_worker.eval.planning import (
    LIMITATIONS,
    TARGET_SCHEMA_PASS_RATE,
    count_duplicate_titles,
    evaluate,
    load_planning_set,
    report_document,
    seal_report,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PLANNING_SET = PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_baseline.v1.jsonl"


def test_frozen_set_has_twenty_one_cases_and_two_subsets() -> None:
    cases = load_planning_set(PLANNING_SET)

    assert len(cases) == 21
    assert sum(1 for case in cases if case.subset == "golden") == 16
    assert sum(1 for case in cases if case.subset == "adversarial") == 5


def test_aggregate_matches_frozen_baseline() -> None:
    report = evaluate(load_planning_set(PLANNING_SET))
    document = report_document(report)
    aggregate = document["aggregate"]

    assert aggregate["cases"] == 21
    assert aggregate["schemaPassRate"] == pytest.approx(16 / 21)
    assert aggregate["sourceViolations"] == 5
    assert aggregate["assigneeViolations"] == 1
    assert aggregate["duplicateTitles"] == 1
    assert aggregate["expectationMatched"] == 21
    assert aggregate["nonEmptySourceRefs"] == 0
    assert aggregate["confidence"] == "LOW_CONFIDENCE"
    assert aggregate["bySubset"] == {
        "golden": {"cases": 16, "schemaPassRate": 1.0},
        "adversarial": {"cases": 5, "rejectedRate": 1.0},
    }
    assert aggregate["bySubset"]["golden"]["schemaPassRate"] >= TARGET_SCHEMA_PASS_RATE
    assert document["evidence"] == "TESTED (stub)"
    assert document["qualityClaim"] == "NOT_VERIFIED"
    assert document["limitations"] == LIMITATIONS
    assert "sourceRefs 恒为空数组" in LIMITATIONS
    assert document["targetSchemaPassRate"] == TARGET_SCHEMA_PASS_RATE


def test_source_refs_are_always_empty_in_frozen_set() -> None:
    """响应契约要求 sourceRefs 恒为空数组：包括模型仍给出引用的 g-016。"""

    document = report_document(evaluate(load_planning_set(PLANNING_SET)))

    assert document["aggregate"]["nonEmptySourceRefs"] == 0
    for case in document["cases"]:
        if case["actual"]["schemaPass"]:
            assert case["actual"]["sourceRefsEmpty"] is True
    g016 = next(case for case in document["cases"] if case["id"] == "g-016")
    assert g016["actual"]["sourceViolations"] == 5
    assert g016["actual"]["schemaPass"] is True


def test_evaluation_is_deterministic_and_canonical() -> None:
    cases = load_planning_set(PLANNING_SET)
    first = seal_report(report_document(evaluate(cases)))
    second = seal_report(report_document(evaluate(cases)))

    assert canonicalize_document(first) == canonicalize_document(second)
    assert first["sha256"] == digest(
        canonicalize_document({key: value for key, value in first.items() if key != "sha256"})
    )
    assert [case["id"] for case in first["cases"]] == sorted(case["id"] for case in first["cases"])


def test_failed_cases_expose_fixed_reason_without_model_text() -> None:
    document = report_document(evaluate(load_planning_set(PLANNING_SET)))
    failed = [case for case in document["cases"] if not case["actual"]["schemaPass"]]

    assert len(failed) == 5
    for case in failed:
        assert case["actual"]["reason"]
        assert case["matched"] is True


def test_count_duplicate_titles_uses_normalized_titles() -> None:
    from ai_collab_worker.contract.planning import (
        PlanningMilestone,
        PlanningPlan,
        PlanningTask,
    )

    cases = load_planning_set(PLANNING_SET)
    request = cases[0].request
    plan = PlanningPlan(
        milestones=(
            PlanningMilestone(tempKey="m1", title="需求冻结", objective="完成需求冻结。"),
            PlanningMilestone(tempKey="m2", title="新里程碑", objective="完成新里程碑。"),
        ),
        tasks=(
            PlanningTask(
                tempKey="t1",
                milestoneTempKey="m1",
                title=" 需求冻结 ",
                objective="目标一。",
                description="说明一。",
                priority="LOW",
            ),
            PlanningTask(
                tempKey="t2",
                milestoneTempKey="m2",
                title="接口联调",
                objective="目标二。",
                description="说明二。",
                priority="LOW",
            ),
        ),
    )

    # 需求冻结（既有）+ 需求冻结（任务，NFKC/空白折叠后重名）+ 接口联调（既有）= 3
    assert count_duplicate_titles(plan, request) == 3


def _write(tmp_path: Path, case: dict[str, object]) -> Path:
    path = tmp_path / "bad.jsonl"
    path.write_text(json.dumps(case, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    return path


def _first_case() -> dict[str, object]:
    line = PLANNING_SET.read_text(encoding="utf-8").splitlines()[0]
    parsed: dict[str, object] = json.loads(line)
    return parsed


def test_loader_rejects_unknown_and_missing_fields(tmp_path: Path) -> None:
    raw = _first_case()
    with pytest.raises(DatasetError, match="未定义字段"):
        load_planning_set(_write(tmp_path, {**raw, "extra": 1}))

    missing = dict(raw)
    del missing["goal"]
    with pytest.raises(DatasetError, match="缺少字段"):
        load_planning_set(_write(tmp_path, missing))


def test_loader_rejects_bad_subset_and_expectation(tmp_path: Path) -> None:
    raw = _first_case()
    with pytest.raises(DatasetError, match="subset"):
        load_planning_set(_write(tmp_path, {**raw, "subset": "other"}))
    with pytest.raises(DatasetError, match=r"expected\.shouldPass"):
        load_planning_set(_write(tmp_path, {**raw, "expected": {}}))
    with pytest.raises(DatasetError, match="未定义字段"):
        load_planning_set(_write(tmp_path, {**raw, "expected": {"shouldPass": True, "x": 1}}))


def test_loader_rejects_invalid_request_fields(tmp_path: Path) -> None:
    raw = _first_case()
    with pytest.raises(DatasetError, match="planning 请求契约"):
        load_planning_set(_write(tmp_path, {**raw, "goal": ""}))
    with pytest.raises(DatasetError, match="modelOutput"):
        load_planning_set(_write(tmp_path, {**raw, "modelOutput": 42}))
