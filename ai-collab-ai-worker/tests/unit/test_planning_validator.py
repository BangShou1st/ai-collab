"""Planning 自校验（冻结 §1 第 1–8 条）：数量、tempKey、引用、依赖环、日期、成员、来源。"""

from __future__ import annotations

import uuid
from typing import Any

import pytest

from ai_collab_worker.cognition.planning.validator import (
    REASON_DATE_RANGE,
    REASON_DEPENDENCY_CYCLE,
    REASON_MILESTONE_COUNT,
    REASON_REQUIRED_TEXT,
    REASON_TASK_COUNT,
    REASON_TEMP_KEY,
    REASON_UNKNOWN_DEPENDENCY,
    REASON_UNKNOWN_MILESTONE,
    DraftValidationError,
    validate_draft,
)
from ai_collab_worker.contract.planning import (
    PlanningDraftOutput,
    PlanningDraftRequest,
    PlanningMilestone,
    PlanningPlan,
    PlanningTask,
)

MEMBER_A = "11111111-1111-4111-8111-111111111111"
MEMBER_B = "22222222-2222-4222-8222-222222222222"


def _request(**overrides: Any) -> PlanningDraftRequest:
    payload: dict[str, Any] = {
        "goal": "排出迭代计划。",
        "locale": "zh-CN",
        "projectFacts": {
            "members": [
                {"id": MEMBER_A, "name": "张伟"},
                {"id": MEMBER_B, "name": "李娜"},
            ],
            "existingMilestoneTitles": ["需求冻结"],
            "existingTaskTitles": ["接口联调", "验收"],
        },
        "template": "ITERATION",
        "constraints": {
            "maxTasks": 3,
            "maxMilestones": 2,
            "startDate": "2026-11-20",
            "dueDate": "2026-12-31",
        },
    }
    payload.update(overrides)
    return PlanningDraftRequest.model_validate(payload)


def _milestone(key: str = "m1", **overrides: Any) -> PlanningMilestone:
    payload: dict[str, Any] = {
        "tempKey": key,
        "title": f"里程碑 {key}",
        "objective": f"完成里程碑 {key}。",
    }
    payload.update(overrides)
    return PlanningMilestone.model_validate(payload)


def _task(key: str, **overrides: Any) -> PlanningTask:
    payload: dict[str, Any] = {
        "tempKey": key,
        "milestoneTempKey": "m1",
        "title": f"任务 {key}",
        "objective": f"完成任务 {key}。",
        "description": f"任务 {key} 的验收说明。",
        "priority": "MEDIUM",
    }
    payload.update(overrides)
    return PlanningTask.model_validate(payload)


def _output(
    milestones: list[PlanningMilestone], tasks: list[PlanningTask], **kwargs: Any
) -> PlanningDraftOutput:
    return PlanningDraftOutput(
        plan=PlanningPlan(milestones=tuple(milestones), tasks=tuple(tasks)), **kwargs
    )


def _reason(output: PlanningDraftOutput, request: PlanningDraftRequest | None = None) -> str:
    with pytest.raises(DraftValidationError) as excinfo:
        validate_draft(output, request or _request())
    return excinfo.value.reason


def test_valid_draft_is_cleaned_and_returned() -> None:
    output = _output(
        [_milestone("m1", sourceRefs=["fact:project"])],
        [_task("t1", assigneeId=MEMBER_A, dependencyTempKeys=[])],
        warnings=("提示一",),
    )

    validated = validate_draft(output, _request())

    assert validated.plan.milestones[0].temp_key == "m1"
    assert validated.plan.milestones[0].source_refs == ()
    assert validated.warnings == ("提示一",)
    assert validated.dropped_source_refs == 1
    assert validated.cleared_assignees == 0


def test_milestone_count_over_request_limit() -> None:
    output = _output([_milestone("m1"), _milestone("m2"), _milestone("m3")], [_task("t1")])
    assert _reason(output) == REASON_MILESTONE_COUNT


def test_task_count_over_request_limit() -> None:
    output = _output(
        [_milestone("m1")],
        [_task("t1"), _task("t2"), _task("t3"), _task("t4")],
    )
    assert _reason(output) == REASON_TASK_COUNT


def test_temp_key_must_be_unique_in_each_domain() -> None:
    assert _reason(_output([_milestone("m1")], [_task("t1"), _task("t1")])) == REASON_TEMP_KEY
    assert _reason(_output([_milestone("m1"), _milestone("m1")], [_task("t1")])) == REASON_TEMP_KEY


def test_temp_key_must_not_be_blank() -> None:
    assert _reason(_output([_milestone("m1")], [_task("   ")])) == REASON_TEMP_KEY
    assert _reason(_output([_milestone("  ")], [_task("t1")])) == REASON_TEMP_KEY


def test_milestone_reference_must_exist() -> None:
    output = _output([_milestone("m1")], [_task("t1", milestoneTempKey="m9")])
    assert _reason(output) == REASON_UNKNOWN_MILESTONE


@pytest.mark.parametrize("dependency", ["t9", "t1"])
def test_dependency_must_reference_another_task(dependency: str) -> None:
    output = _output([_milestone("m1")], [_task("t1", dependencyTempKeys=[dependency])])
    assert _reason(output) == REASON_UNKNOWN_DEPENDENCY


def test_dependency_cycle_is_rejected() -> None:
    output = _output(
        [_milestone("m1")],
        [
            _task("t1", dependencyTempKeys=["t2"]),
            _task("t2", dependencyTempKeys=["t1"]),
        ],
    )
    assert _reason(output) == REASON_DEPENDENCY_CYCLE


def test_dependency_chain_is_accepted_and_deduplicated() -> None:
    output = _output(
        [_milestone("m1")],
        [
            _task("t1"),
            _task("t2", dependencyTempKeys=["t1", "t1"]),
            _task("t3", dependencyTempKeys=["t1", "t2"]),
        ],
    )

    validated = validate_draft(output, _request())

    assert validated.plan.tasks[1].dependency_temp_keys == ("t1",)


def test_dates_must_stay_inside_the_requested_window() -> None:
    assert (
        _reason(_output([_milestone("m1", targetDate="2026-11-19")], [_task("t1")]))
        == REASON_DATE_RANGE
    )
    assert (
        _reason(_output([_milestone("m1", targetDate="2027-01-01")], [_task("t1")]))
        == REASON_DATE_RANGE
    )
    assert (
        _reason(_output([_milestone("m1")], [_task("t1", startDate="2026-11-19")]))
        == REASON_DATE_RANGE
    )
    assert (
        _reason(_output([_milestone("m1")], [_task("t1", dueDate="2027-01-01")]))
        == REASON_DATE_RANGE
    )


def test_task_due_date_must_not_precede_start() -> None:
    output = _output(
        [_milestone("m1")],
        [_task("t1", startDate="2026-12-10", dueDate="2026-12-01")],
    )
    assert _reason(output) == REASON_DATE_RANGE


def test_calendar_invalid_date_is_rejected() -> None:
    output = _output([_milestone("m1", targetDate="2026-02-30")], [_task("t1")])
    assert _reason(output) == REASON_DATE_RANGE


def test_window_boundaries_are_inclusive() -> None:
    output = _output(
        [_milestone("m1", targetDate="2026-12-31")],
        [_task("t1", startDate="2026-11-20", dueDate="2026-12-31")],
    )
    assert validate_draft(output, _request()).plan.tasks[0].due_date == "2026-12-31"


def test_dates_outside_window_are_ignored_when_constraints_are_absent() -> None:
    request = _request(constraints={"maxTasks": 3, "maxMilestones": 2})
    output = _output([_milestone("m1", targetDate="2030-01-01")], [_task("t1")])
    assert validate_draft(output, request).plan.milestones[0].target_date == "2030-01-01"


def test_assignee_outside_members_is_nulled_with_warning() -> None:
    output = _output(
        [_milestone("m1")],
        [
            _task("t1", assigneeId="99999999-9999-4999-8999-999999999999"),
            _task("t2", assigneeId=MEMBER_B),
        ],
    )

    validated = validate_draft(output, _request())

    assert validated.plan.tasks[0].assignee_id is None
    assert validated.plan.tasks[1].assignee_id == MEMBER_B
    assert validated.cleared_assignees == 1
    assert len(validated.warnings) == 1
    assert "第 1 个任务" in validated.warnings[0]


def test_empty_members_clears_every_assignee() -> None:
    request = _request(projectFacts={"members": []})
    output = _output([_milestone("m1")], [_task("t1", assigneeId=MEMBER_A)])

    validated = validate_draft(output, request)

    assert validated.plan.tasks[0].assignee_id is None
    assert validated.cleared_assignees == 1


def test_all_source_refs_are_dropped_and_output_is_empty() -> None:
    output = _output(
        [_milestone("m1", sourceRefs=["fact:project", "doc:42", "member:" + MEMBER_A])],
        [
            _task(
                "t1",
                sourceRefs=[
                    "milestone:0",
                    "task:1",
                    "fact:unknown",
                    "fact:unknown",
                    "fact:project",
                ],
            )
        ],
    )

    validated = validate_draft(output, _request())

    assert validated.plan.milestones[0].source_refs == ()
    assert validated.plan.tasks[0].source_refs == ()
    assert validated.dropped_source_refs == 8
    assert not any(item.source_refs for item in (*validated.plan.milestones, *validated.plan.tasks))


def test_whitespace_only_required_text_is_rejected() -> None:
    assert _reason(_output([_milestone("m1", title="  ")], [_task("t1")])) == REASON_REQUIRED_TEXT
    assert (
        _reason(_output([_milestone("m1", objective=" ")], [_task("t1")])) == REASON_REQUIRED_TEXT
    )
    assert (
        _reason(_output([_milestone("m1")], [_task("t1", description="\n")]))
        == REASON_REQUIRED_TEXT
    )


def test_generated_warnings_are_truncated_at_limit() -> None:
    request = _request()
    tasks = [
        _task("t1", assigneeId=str(uuid.uuid4())),
        _task("t2", assigneeId=str(uuid.uuid4())),
        _task("t3", assigneeId=str(uuid.uuid4())),
    ]
    model_warnings = tuple(f"模型提示 {index}" for index in range(19))
    output = _output([_milestone("m1")], tasks, warnings=model_warnings)

    validated = validate_draft(output, request)

    assert len(validated.warnings) == 20
    assert validated.warnings[0] == "模型提示 0"
    assert validated.warnings[-1].startswith("第 1 个任务")


def test_model_warnings_at_limit_leave_no_room_for_generated_ones() -> None:
    model_warnings = tuple(f"模型提示 {index}" for index in range(20))
    output = _output(
        [_milestone("m1")],
        [_task("t1", assigneeId=str(uuid.uuid4()))],
        warnings=model_warnings,
    )

    validated = validate_draft(output, _request())

    assert validated.warnings == model_warnings
    assert validated.cleared_assignees == 1
