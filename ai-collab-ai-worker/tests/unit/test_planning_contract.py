"""``POST /v1/planning/draft`` 契约边界（冻结 §1 请求表逐行，含 ±1）。"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.planning import (
    MAX_DEPENDENCIES,
    MAX_DESCRIPTION_CHARS,
    MAX_EXISTING_TITLE_CHARS,
    MAX_EXISTING_TITLES,
    MAX_GOAL_CHARS,
    MAX_MAX_MILESTONES,
    MAX_MAX_TASKS,
    MAX_MEMBER_NAME_CHARS,
    MAX_MEMBERS,
    MAX_OBJECTIVE_CHARS,
    MAX_PLANNING_DEADLINE_MS,
    MAX_PROJECT_DESCRIPTION_CHARS,
    MAX_PROJECT_NAME_CHARS,
    MAX_SOURCE_REFS,
    MAX_TITLE_CHARS,
    MAX_WARNING_CHARS,
    MAX_WARNINGS,
    PlanningDraftOutput,
    PlanningDraftRequest,
    PlanningDraftResponse,
    PlanningMilestone,
    PlanningPlan,
    PlanningProjectFacts,
    PlanningTask,
    PlanningTemplate,
    PlanningUsage,
    PlanningUsageSource,
)

MEMBER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")


def _request_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "goal": "为下个迭代排出里程碑与任务。",
        "locale": "zh-CN",
        "projectFacts": {
            "projectName": "AI Collab",
            "description": "协作平台",
            "members": [{"id": str(MEMBER_ID), "name": "张伟"}],
            "existingMilestoneTitles": ["需求冻结"],
            "existingTaskTitles": ["接口联调"],
        },
        "template": "ITERATION",
        "constraints": {"maxTasks": 20, "maxMilestones": 6},
    }
    payload.update(overrides)
    return payload


def _request(**overrides: Any) -> PlanningDraftRequest:
    return PlanningDraftRequest.model_validate(_request_payload(**overrides))


def _facts(**overrides: Any) -> dict[str, Any]:
    facts: dict[str, Any] = {
        "projectName": "AI Collab",
        "description": "协作平台",
        "members": [{"id": str(MEMBER_ID), "name": "张伟"}],
        "existingMilestoneTitles": ["需求冻结"],
        "existingTaskTitles": ["接口联调"],
    }
    facts.update(overrides)
    return facts


def _task(**overrides: Any) -> PlanningTask:
    payload: dict[str, Any] = {
        "tempKey": "t1",
        "milestoneTempKey": "m1",
        "title": "任务",
        "objective": "完成本任务。",
        "description": "按验收标准提交实现与测试记录。",
        "priority": "MEDIUM",
    }
    payload.update(overrides)
    return PlanningTask.model_validate(payload)


def test_example_request_is_accepted() -> None:
    request = _request()

    assert request.schema_version == 1
    assert request.template is PlanningTemplate.ITERATION
    assert request.constraints.max_tasks == 20
    assert request.constraints.max_milestones == 6
    assert request.deadline_ms == 60000
    assert request.project_facts.members[0].id == MEMBER_ID


@pytest.mark.parametrize("goal", ["", "x" * (MAX_GOAL_CHARS + 1)])
def test_goal_boundaries(goal: str) -> None:
    with pytest.raises(ValidationError):
        _request(goal=goal)
    assert _request(goal="x").goal == "x"
    assert _request(goal="x" * MAX_GOAL_CHARS).goal == "x" * MAX_GOAL_CHARS


@pytest.mark.parametrize("locale", ["", "z", "zh-CN", "en", "x" * 36, "中文"])
def test_locale_pattern(locale: str) -> None:
    if locale in {"zh-CN", "en"}:
        assert _request(locale=locale).locale == locale
    else:
        with pytest.raises(ValidationError):
            _request(locale=locale)


def test_project_name_and_description_boundaries() -> None:
    assert _request(projectFacts=_facts(projectName="x" * MAX_PROJECT_NAME_CHARS))
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(projectName="x" * (MAX_PROJECT_NAME_CHARS + 1)))
    assert _request(projectFacts=_facts(description="x" * MAX_PROJECT_DESCRIPTION_CHARS))
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(description="x" * (MAX_PROJECT_DESCRIPTION_CHARS + 1)))


def test_members_boundaries() -> None:
    members = [{"id": str(uuid.uuid4()), "name": "成员"} for _ in range(MAX_MEMBERS)]
    assert len(_request(projectFacts=_facts(members=members)).project_facts.members) == MAX_MEMBERS
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(members=[*members, {"id": str(uuid.uuid4()), "name": "多"}]))

    assert _request(projectFacts=_facts(members=[])).project_facts.members == ()
    assert _request(
        projectFacts=_facts(members=[{"id": str(MEMBER_ID), "name": "x" * MAX_MEMBER_NAME_CHARS}])
    )
    with pytest.raises(ValidationError):
        _request(
            projectFacts=_facts(
                members=[{"id": str(MEMBER_ID), "name": "x" * (MAX_MEMBER_NAME_CHARS + 1)}]
            )
        )
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(members=[{"id": str(MEMBER_ID), "name": ""}]))
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(members=[{"id": "not-a-uuid", "name": "张伟"}]))


def test_existing_titles_boundaries() -> None:
    titles = [f"里程碑 {index}" for index in range(MAX_EXISTING_TITLES)]
    assert (
        len(
            _request(
                projectFacts=_facts(existingMilestoneTitles=titles)
            ).project_facts.existing_milestone_titles
        )
        == MAX_EXISTING_TITLES
    )
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(existingTaskTitles=[*titles, "多一个"]))

    long_title = "x" * MAX_EXISTING_TITLE_CHARS
    assert _request(projectFacts=_facts(existingTaskTitles=[long_title]))
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(existingTaskTitles=["x" * (MAX_EXISTING_TITLE_CHARS + 1)]))
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(existingTaskTitles=[""]))


@pytest.mark.parametrize("template", ["ITERATION", "DELIVERY", "CUSTOM"])
def test_template_enum_accepts_three_values(template: str) -> None:
    assert _request(template=template).template.value == template


@pytest.mark.parametrize("template", ["iteration", "SPRINT", ""])
def test_template_enum_rejects_others(template: str) -> None:
    with pytest.raises(ValidationError):
        _request(template=template)


@pytest.mark.parametrize(
    ("name", "value", "valid"),
    [
        ("maxTasks", 1, True),
        ("maxTasks", MAX_MAX_TASKS, True),
        ("maxTasks", 0, False),
        ("maxTasks", MAX_MAX_TASKS + 1, False),
        ("maxMilestones", 1, True),
        ("maxMilestones", MAX_MAX_MILESTONES, True),
        ("maxMilestones", 0, False),
        ("maxMilestones", MAX_MAX_MILESTONES + 1, False),
    ],
)
def test_constraints_bounds(name: str, value: int, valid: bool) -> None:
    constraints: dict[str, Any] = {"maxTasks": 20, "maxMilestones": 6}
    constraints[name] = value
    if valid:
        assert _request(constraints=constraints)
    else:
        with pytest.raises(ValidationError):
            _request(constraints=constraints)


def test_constraints_boolean_is_not_an_integer() -> None:
    with pytest.raises(ValidationError):
        _request(constraints={"maxTasks": True, "maxMilestones": 6})
    with pytest.raises(ValidationError):
        _request(constraints={"maxTasks": 20, "maxMilestones": False})


def test_constraints_dates_are_calendar_checked_and_ordered() -> None:
    assert _request(constraints={"startDate": "2026-11-20", "dueDate": "2026-11-20"})
    with pytest.raises(ValidationError):
        _request(constraints={"startDate": "2026-12-01", "dueDate": "2026-11-01"})
    with pytest.raises(ValidationError):
        _request(constraints={"startDate": "2026-02-30"})
    with pytest.raises(ValidationError):
        _request(constraints={"startDate": "20261120"})


@pytest.mark.parametrize(
    ("value", "valid"),
    [
        (1, True),
        (MAX_PLANNING_DEADLINE_MS, True),
        (0, False),
        (MAX_PLANNING_DEADLINE_MS + 1, False),
    ],
)
def test_deadline_boundaries(value: int, valid: bool) -> None:
    if valid:
        assert _request(deadlineMs=value).deadline_ms == value
    else:
        with pytest.raises(ValidationError):
            _request(deadlineMs=value)


def test_schema_version_and_deadline_reject_booleans() -> None:
    with pytest.raises(ValidationError):
        _request(schemaVersion=True)
    with pytest.raises(ValidationError):
        _request(deadlineMs=True)


def test_extra_fields_are_forbidden_everywhere() -> None:
    with pytest.raises(ValidationError):
        _request(extra="x")
    with pytest.raises(ValidationError):
        _request(projectFacts={**_facts(), "extra": "x"})
    with pytest.raises(ValidationError):
        _request(projectFacts=_facts(members=[{"id": str(MEMBER_ID), "name": "张伟", "role": "x"}]))
    with pytest.raises(ValidationError):
        _request(constraints={"maxTasks": 20, "maxMilestones": 6, "maxHours": 8})


def test_task_bounds() -> None:
    assert _task(title="x" * MAX_TITLE_CHARS)
    with pytest.raises(ValidationError):
        _task(title="x" * (MAX_TITLE_CHARS + 1))
    assert _task(objective="x" * MAX_OBJECTIVE_CHARS)
    with pytest.raises(ValidationError):
        _task(objective="x" * (MAX_OBJECTIVE_CHARS + 1))
    assert _task(description="x" * MAX_DESCRIPTION_CHARS)
    with pytest.raises(ValidationError):
        _task(description="x" * (MAX_DESCRIPTION_CHARS + 1))

    assert _task(estimatedHours=0.5).estimated_hours == 0.5
    assert _task(estimatedHours=79.9).estimated_hours == 79.9
    assert _task(estimatedHours=80.0).estimated_hours == 80.0
    assert _task(estimatedHours=None).estimated_hours is None
    with pytest.raises(ValidationError):
        _task(estimatedHours=0.4)
    with pytest.raises(ValidationError):
        _task(estimatedHours=80.1)
    with pytest.raises(ValidationError):
        _task(estimatedHours=200.0)
    with pytest.raises(ValidationError):
        _task(estimatedHours=True)

    for priority in ("LOW", "MEDIUM", "HIGH", "URGENT"):
        assert _task(priority=priority).priority.value == priority
    with pytest.raises(ValidationError):
        _task(priority="P0")


def test_task_reference_guards() -> None:
    assert len(_task(dependencyTempKeys=["t2"] * MAX_DEPENDENCIES).dependency_temp_keys) == (
        MAX_DEPENDENCIES
    )
    with pytest.raises(ValidationError):
        _task(dependencyTempKeys=["t2"] * (MAX_DEPENDENCIES + 1))
    assert len(_task(sourceRefs=["fact:project"] * MAX_SOURCE_REFS).source_refs) == MAX_SOURCE_REFS


def test_milestone_requires_objective_but_description_is_optional() -> None:
    milestone = PlanningMilestone.model_validate(
        {"tempKey": "m1", "title": "需求", "objective": "完成需求冻结。"}
    )

    assert milestone.objective == "完成需求冻结。"
    assert milestone.description == ""
    assert milestone.target_date is None
    assert milestone.source_refs == ()
    with pytest.raises(ValidationError):
        PlanningMilestone.model_validate({"tempKey": "m1", "title": "需求"})
    with pytest.raises(ValidationError):
        PlanningMilestone.model_validate({"tempKey": "m1", "title": "需求", "objective": ""})


def test_task_requires_objective_and_description() -> None:
    task = _task()

    assert task.objective == "完成本任务。"
    assert task.description == "按验收标准提交实现与测试记录。"
    with pytest.raises(ValidationError):
        PlanningTask.model_validate(
            {
                "tempKey": "t1",
                "milestoneTempKey": "m1",
                "title": "任务",
                "description": "说明",
                "priority": "LOW",
            }
        )
    with pytest.raises(ValidationError):
        PlanningTask.model_validate(
            {
                "tempKey": "t1",
                "milestoneTempKey": "m1",
                "title": "任务",
                "objective": "目标",
                "priority": "LOW",
            }
        )
    with pytest.raises(ValidationError):
        _task(objective="")
    with pytest.raises(ValidationError):
        _task(description="")


def test_output_requires_plan_with_at_least_one_item() -> None:
    with pytest.raises(ValidationError):
        PlanningDraftOutput.model_validate({"plan": {"milestones": [], "tasks": []}})
    with pytest.raises(ValidationError):
        PlanningDraftOutput.model_validate({"plan": {"milestones": [], "tasks": []}, "extra": 1})


def test_warnings_boundaries() -> None:
    base = {
        "milestones": [{"tempKey": "m1", "title": "里程碑", "objective": "完成里程碑。"}],
        "tasks": [
            {
                "tempKey": "t1",
                "milestoneTempKey": "m1",
                "title": "任务",
                "objective": "完成任务。",
                "description": "任务说明。",
                "priority": "LOW",
            }
        ],
    }
    warnings = ["提示"] * MAX_WARNINGS
    assert len(
        PlanningDraftOutput.model_validate({"plan": base, "warnings": warnings}).warnings
    ) == (MAX_WARNINGS)
    with pytest.raises(ValidationError):
        PlanningDraftOutput.model_validate({"plan": base, "warnings": [*warnings, "多"]})
    with pytest.raises(ValidationError):
        PlanningDraftOutput.model_validate(
            {"plan": base, "warnings": ["x" * (MAX_WARNING_CHARS + 1)]}
        )
    with pytest.raises(ValidationError):
        PlanningDraftOutput.model_validate({"plan": base, "warnings": [""]})


def test_response_has_exactly_four_keys() -> None:
    response = PlanningDraftResponse(
        plan=PlanningPlan(
            milestones=(PlanningMilestone(tempKey="m1", title="里程碑", objective="完成里程碑。"),),
            tasks=(_task(),),
        )
    )

    dumped = response.model_dump(by_alias=True, mode="json")
    assert set(dumped) == {"schemaVersion", "plan", "warnings", "usage"}
    assert dumped["usage"] == {"inputTokens": None, "outputTokens": None, "source": "UNKNOWN"}
    assert set(dumped["plan"]) == {"milestones", "tasks"}


def test_usage_from_gateway_shape() -> None:
    usage = PlanningUsage(inputTokens=10, outputTokens=5, source="PROVIDER")
    assert usage.model_dump(by_alias=True, mode="json") == {
        "inputTokens": 10,
        "outputTokens": 5,
        "source": "PROVIDER",
    }
    assert PlanningUsage().source is PlanningUsageSource.UNKNOWN
    with pytest.raises(ValidationError):
        PlanningUsage(inputTokens=-1)
    with pytest.raises(ValidationError):
        PlanningUsage(inputTokens=True)


def test_project_facts_defaults_are_empty() -> None:
    facts = PlanningProjectFacts.model_validate({})
    assert facts.project_name == ""
    assert facts.description == ""
    assert facts.members == ()
    assert facts.existing_milestone_titles == ()
    assert facts.existing_task_titles == ()
