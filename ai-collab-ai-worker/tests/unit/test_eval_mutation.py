"""D6 定向变异器：规划/决策的每个变异体都必须被校验核心拒绝。"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.contract.agent_decide import DecideDecision
from ai_collab_worker.eval.decide import CaseOutcome, load_decide_set
from ai_collab_worker.eval.decide import evaluate as evaluate_decide
from ai_collab_worker.eval.mutation import (
    DECIDE_MUTATION_INVALID_DECISION,
    DECIDE_MUTATION_INVALID_JSON,
    DECIDE_MUTATION_MISSING_REQUIRED_FIELD,
    DECIDE_MUTATION_UNKNOWN_TOOL,
    MUTATION_SOURCE_WORKER_RESPONSE,
    PLANNING_MUTATION_DATE_BEYOND_DUE,
    PLANNING_MUTATION_DEPENDENCY_CYCLE,
    PLANNING_MUTATION_DUPLICATE_TEMP_KEY,
    PLANNING_MUTATION_TOO_MANY_TASKS,
    PLANNING_MUTATION_UNKNOWN_MILESTONE,
    decide_mutations,
    evaluate_decide_mutations,
    evaluate_planning_mutations,
    planning_mutations,
)
from ai_collab_worker.eval.planning import evaluate as evaluate_planning
from ai_collab_worker.eval.planning import load_planning_set

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PLANNING_SET = PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_baseline.v1.jsonl"
PLANNING_REAL = (
    PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_real_gemini31.v1.jsonl"
)
DECIDE_SET = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v1.jsonl"
DECIDE_REAL = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_real_gemini31.v1.jsonl"


def test_planning_each_mutation_is_rejected() -> None:
    cases = [case for case in load_planning_set(PLANNING_SET) if case.subset == "golden"]
    kinds_seen: set[str] = set()
    for case in cases:
        outcomes = evaluate_planning_mutations(case.model_output, case.request)
        assert len(outcomes) == 5
        assert all(outcome.rejected for outcome in outcomes)
        assert all(outcome.reason for outcome in outcomes)
        kinds_seen.update(outcome.kind for outcome in outcomes)

    assert kinds_seen == {
        PLANNING_MUTATION_UNKNOWN_MILESTONE,
        PLANNING_MUTATION_DEPENDENCY_CYCLE,
        PLANNING_MUTATION_TOO_MANY_TASKS,
        PLANNING_MUTATION_DATE_BEYOND_DUE,
        PLANNING_MUTATION_DUPLICATE_TEMP_KEY,
    }


def test_planning_mutations_do_not_reject_legal_baseline() -> None:
    report = evaluate_planning(load_planning_set(PLANNING_SET), adversarial_mutations=True)
    golden = [outcome for case, outcome in report.cases if case.subset == "golden"]

    assert all(outcome.schema_pass for outcome in golden)
    assert report.mutation_variants == 16 * 5
    assert report.mutation_rejected == report.mutation_variants
    assert report.mutation_rejected_rate == 1.0


def test_planning_real_output_mutations_are_rejected() -> None:
    report = evaluate_planning(load_planning_set(PLANNING_REAL), adversarial_mutations=True)

    assert report.schema_pass_rate == 1.0
    assert report.mutation_variants == 21 * 5
    assert report.mutation_rejected_rate == 1.0


def test_decide_each_mutation_is_rejected() -> None:
    cases = [case for case in load_decide_set(DECIDE_SET) if case.subset == "golden"]
    kinds_seen: set[str] = set()
    for case in cases:
        outcomes = evaluate_decide_mutations(case.model_output, case.request)
        assert len(outcomes) == 4
        assert all(outcome.rejected for outcome in outcomes)
        kinds_seen.update(outcome.kind for outcome in outcomes)

    assert kinds_seen == {
        DECIDE_MUTATION_UNKNOWN_TOOL,
        DECIDE_MUTATION_INVALID_DECISION,
        DECIDE_MUTATION_MISSING_REQUIRED_FIELD,
        DECIDE_MUTATION_INVALID_JSON,
    }


def test_decide_mutations_do_not_reject_legal_baseline() -> None:
    from ai_collab_worker.eval.decide import ReplayScorer

    report = evaluate_decide(
        load_decide_set(DECIDE_SET), ReplayScorer(), adversarial_mutations=True
    )
    golden = [outcome for case, outcome in report.cases if case.subset == "golden"]

    assert all(outcome.valid for outcome in golden)
    assert report.mutation_variants == 20 * 4
    assert report.mutation_rejected_rate == 1.0


def test_decide_real_reconstructed_mutations_are_rejected() -> None:
    from ai_collab_worker.eval.decide import ReplayScorer

    report = evaluate_decide(
        load_decide_set(DECIDE_REAL), ReplayScorer(), adversarial_mutations=True
    )

    assert report.mutation_variants == 31 * 4
    assert report.mutation_rejected_rate == 1.0


def test_decide_mutations_reuse_only_valid_sources() -> None:
    case = load_decide_set(DECIDE_SET)[0]

    assert decide_mutations("not json", case.request) == ()
    assert decide_mutations("", case.request) == ()
    assert planning_mutations("not json", load_planning_set(PLANNING_SET)[0].request) == ()


class _WorkerLikeScorer:
    """模拟 worker 路径：CaseOutcome 带重构的真实响应，而不是 dataset 的 stub。"""

    name = "worker"
    evidence = "TESTED (worker)"

    def __init__(self, raw_output: str) -> None:
        self._raw_output = raw_output

    def score(self, case: object) -> CaseOutcome:
        return CaseOutcome(
            valid=True,
            decision=DecideDecision.CALL_TOOL,
            tool_name="search_project_knowledge",
            invalid_tool_call=False,
            schema_violation=False,
            reason=None,
            raw_output=self._raw_output,
        )

    def close(self) -> None:
        return None


def test_worker_mutations_use_response_not_stub() -> None:
    scorer = _WorkerLikeScorer(
        '{"decision":"CALL_TOOL","toolCall":{"name":"search_project_knowledge","arguments":{}}}'
    )
    report = evaluate_decide(load_decide_set(DECIDE_SET), scorer, adversarial_mutations=True)

    assert report.mutation_source == MUTATION_SOURCE_WORKER_RESPONSE
    assert report.mutation_variants == 32 * 4
    assert report.mutation_rejected_rate == 1.0
    # d-028 的 stub modelOutput 根本不是 JSON；若误用 stub，该 case 会没有变异体。
    d028 = next(outcome for case, outcome in report.cases if case.id == "d-028")
    assert len(d028.mutations) == 4


@pytest.mark.parametrize(
    ("candidate", "judgement", "expected"),
    [
        ("检索链路统一至 Java 网关", "检索链路统一走 Java 网关", True),
        ("写操作必须经过人工审批", "写操作必须人工审批", True),
        ("写操作必须人工复核", "写操作必须人工审批", False),
        ("迭代长度调整为三周", "迭代长度调整为两周", False),
        ("外部排期无需预留缓冲", "外部排期要预留缓冲", False),
    ],
)
def test_semantic_threshold_separation(candidate: str, judgement: str, expected: bool) -> None:
    from ai_collab_worker.eval.memory import semantic_score

    score = semantic_score(candidate, judgement)
    assert (score is not None) is expected
