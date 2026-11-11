"""Planning 草稿的 Python 侧自校验（W5 冻结文档 §1 第 1–8 条）。

**这一段是 Java ``TaskPlanDraftValidator`` 的影子，不是替代**：冻结 §1 明确要求 Java 收到
Python 草稿后**重跑**自己的校验器；本模块只保证"明显非法"的草稿不占用 Java 的校验预算，
并且让在线与离线评测（``ai-eval planning``）复用同一段代码。

规则与冻结逐条对应：

1. ``milestones`` 1–``constraints.maxMilestones``；``tasks`` 1–``constraints.maxTasks``；
   ``tempKey`` 全局唯一（里程碑与任务分域），非空且 ≤64 字符；``title``/``objective``
   （里程碑与任务）与任务 ``description`` 必须非空白（Java ``PlanningDraftParser`` 对
   ``objective`` 用 ``requiredText``，COMPLETE 模式还要求任务描述非空）；
2. ``tasks[i].milestoneTempKey`` 必须存在于 ``milestones``；
3. ``dependencyTempKeys`` 必须引用本次 ``tasks``（不含自身），且**无环**（Kahn）；
4. ``priority`` 白名单与 ``estimatedHours`` 区间由契约模型（``contract/planning.py``）保证；
5. ``targetDate``/``startDate``/``dueDate`` 给出时必须在 ``constraints`` 区间内，
   且各自 ``dueDate >= startDate``；区间缺省时只校验顺序；
6. ``assigneeId`` 不在 ``projectFacts.members[].id`` 内 → 置 ``null`` 并追加 warning（不报错）；
7. ``sourceRefs``：**响应恒为空数组**。W5-Java 复核确认 Java ``TaskPlanDraftValidator`` 要求
   ``sourceRefs`` ∈ 本次 ``PlanSource.ref``（``S1..S12``），而 Python 的
   ``member:/milestone:/task:/fact:`` 命名空间无法映射，任何非空 ref 都会让 shadow 草稿稳定
   报 ``SOURCE_REF_FORMAT_INVALID``/``SOURCE_REF_INVALID``（schema_pass=fail）。因此模型给出的
   任何 ref 都**全部丢弃并计数**（``ValidatedDraft.dropped_source_refs``），字段保留只为契约
   向前兼容；引用由 Java 检索与 ``PlanSource`` 负责；
8. ``warnings`` 0–20、每项 1–500（契约模型保证）；自校验追加的 warning 在 20 条上限处截断，
   不因为"我们自己的提示"把一份合法草稿打成 422。

失败抛 :class:`DraftValidationError`，``reason`` 为固定短语；**绝不包含模型原文**。
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.planning import (
    MAX_WARNINGS,
    PlanningDraftOutput,
    PlanningDraftRequest,
    PlanningMilestone,
    PlanningPlan,
    PlanningTask,
)

#: 对外可见的固定失败短语（不回显模型原文）。
REASON_MILESTONE_COUNT: Final[str] = "里程碑数量不在本次约束范围内"
REASON_TASK_COUNT: Final[str] = "任务数量不在本次约束范围内"
REASON_TEMP_KEY: Final[str] = "tempKey 为空、过长或重复"
REASON_UNKNOWN_MILESTONE: Final[str] = "任务的 milestoneTempKey 不在本次里程碑内"
REASON_UNKNOWN_DEPENDENCY: Final[str] = "dependencyTempKeys 引用了未知任务或自身"
REASON_DEPENDENCY_CYCLE: Final[str] = "任务依赖存在环"
REASON_DATE_RANGE: Final[str] = "日期越界或顺序非法"
REASON_REQUIRED_TEXT: Final[str] = "必填文本（title/objective/description）不能为空白"


class DraftValidationError(Exception):
    """草稿未通过自校验；``reason`` 是固定短语（不含模型原文）。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class ValidatedDraft:
    """自校验通过的草稿：已清洗的 plan、合并后的 warnings 与评测用的计数。"""

    plan: PlanningPlan
    warnings: tuple[str, ...]
    dropped_source_refs: int
    cleared_assignees: int


def validate_draft(output: PlanningDraftOutput, request: PlanningDraftRequest) -> ValidatedDraft:
    """按冻结 §1 第 1–8 条校验并清洗草稿；不合法时抛 :class:`DraftValidationError`。"""

    constraints = request.constraints
    milestones = output.plan.milestones
    tasks = output.plan.tasks

    _check_counts(
        milestone_count=len(milestones),
        task_count=len(tasks),
        max_milestones=constraints.max_milestones,
        max_tasks=constraints.max_tasks,
    )
    milestone_keys = _check_milestone_keys(milestones)
    _check_milestone_references(tasks, milestone_keys)
    _check_dependencies(tasks)

    window_start = _parse_optional_date(constraints.start_date)
    window_due = _parse_optional_date(constraints.due_date)

    cleaned_milestones: list[PlanningMilestone] = []
    dropped_refs = 0
    for milestone in milestones:
        _check_required_text(milestone.title, milestone.objective)
        _check_date_in_window(milestone.target_date, window_start, window_due)
        dropped_refs += _drop_source_refs(milestone.source_refs)
        cleaned_milestones.append(milestone.model_copy(update={"source_refs": ()}))

    member_ids = {str(member.id) for member in request.project_facts.members}
    cleaned_tasks: list[PlanningTask] = []
    cleared_assignees = 0
    generated_warnings: list[str] = []
    for position, task in enumerate(tasks, start=1):
        _check_required_text(task.title, task.objective, task.description)
        _check_date_in_window(task.start_date, window_start, window_due)
        _check_date_in_window(task.due_date, window_start, window_due)
        _check_date_order(task.start_date, task.due_date)
        dropped_refs += _drop_source_refs(task.source_refs)
        dependency_keys = _unique(task.dependency_temp_keys)
        assignee = task.assignee_id
        if assignee is not None and assignee not in member_ids:
            assignee = None
            cleared_assignees += 1
            generated_warnings.append(
                f"第 {position} 个任务的 assigneeId 不在项目成员内，已置为 null。"
            )
        cleaned_tasks.append(
            task.model_copy(
                update={
                    "assignee_id": assignee,
                    "dependency_temp_keys": dependency_keys,
                    "source_refs": (),
                }
            )
        )

    warnings = _merge_warnings(output.warnings, generated_warnings)
    return ValidatedDraft(
        plan=PlanningPlan(
            milestones=tuple(cleaned_milestones),
            tasks=tuple(cleaned_tasks),
        ),
        warnings=warnings,
        dropped_source_refs=dropped_refs,
        cleared_assignees=cleared_assignees,
    )


def _check_required_text(*values: str) -> None:
    """必填文本不能是空白串：Java ``requiredText`` 不接受纯空白，本侧提前 422。"""

    for value in values:
        if not value.strip():
            raise DraftValidationError(REASON_REQUIRED_TEXT)


def _drop_source_refs(refs: tuple[str, ...]) -> int:
    """``sourceRefs`` 恒为空数组：返回被丢弃的 ref 条数（供指标计数）。

    Python 的 ``member:/milestone:/task:/fact:`` 命名空间无法映射到 Java ``PlanSource.ref``
    （``S1..S12``），因此不做"保留合法子集"，而是一律丢弃——保留任何一个都会让 Java 的
    ``TaskPlanDraftValidator`` 判 ``SOURCE_REF_INVALID``，把命名空间问题伪装成草稿质量问题。
    """

    return len(refs)


def _check_counts(
    *,
    milestone_count: int,
    task_count: int,
    max_milestones: int,
    max_tasks: int,
) -> None:
    if not 1 <= milestone_count <= max_milestones:
        raise DraftValidationError(REASON_MILESTONE_COUNT)
    if not 1 <= task_count <= max_tasks:
        raise DraftValidationError(REASON_TASK_COUNT)


def _check_milestone_keys(milestones: tuple[PlanningMilestone, ...]) -> frozenset[str]:
    keys: list[str] = []
    for milestone in milestones:
        key = milestone.temp_key
        if not key.strip():
            raise DraftValidationError(REASON_TEMP_KEY)
        keys.append(key)
    if len(set(keys)) != len(keys):
        raise DraftValidationError(REASON_TEMP_KEY)
    return frozenset(keys)


def _check_task_keys(tasks: tuple[PlanningTask, ...]) -> frozenset[str]:
    keys: list[str] = []
    for task in tasks:
        key = task.temp_key
        if not key.strip():
            raise DraftValidationError(REASON_TEMP_KEY)
        keys.append(key)
    if len(set(keys)) != len(keys):
        raise DraftValidationError(REASON_TEMP_KEY)
    return frozenset(keys)


def _check_milestone_references(
    tasks: tuple[PlanningTask, ...], milestone_keys: frozenset[str]
) -> None:
    for task in tasks:
        if task.milestone_temp_key not in milestone_keys:
            raise DraftValidationError(REASON_UNKNOWN_MILESTONE)


def _check_dependencies(tasks: tuple[PlanningTask, ...]) -> None:
    task_keys = _check_task_keys(tasks)
    graph: dict[str, set[str]] = {}
    for task in tasks:
        dependencies = set(_unique(task.dependency_temp_keys))
        for dependency in dependencies:
            if dependency not in task_keys or dependency == task.temp_key:
                raise DraftValidationError(REASON_UNKNOWN_DEPENDENCY)
        graph[task.temp_key] = dependencies

    # Kahn：入度按"依赖 → 任务"方向统计；剩下的点即成环部分。
    indegree = {key: len(dependencies) for key, dependencies in graph.items()}
    ready = [key for key, degree in indegree.items() if degree == 0]
    visited = 0
    while ready:
        current = ready.pop()
        visited += 1
        for key, dependencies in graph.items():
            if current in dependencies:
                indegree[key] -= 1
                if indegree[key] == 0:
                    ready.append(key)
    if visited != len(graph):
        raise DraftValidationError(REASON_DEPENDENCY_CYCLE)


def _check_date_in_window(
    value: str | None,
    window_start: dt.date | None,
    window_due: dt.date | None,
) -> None:
    parsed = _parse_optional_date(value)
    if parsed is None:
        return
    if window_start is not None and parsed < window_start:
        raise DraftValidationError(REASON_DATE_RANGE)
    if window_due is not None and parsed > window_due:
        raise DraftValidationError(REASON_DATE_RANGE)


def _check_date_order(start: str | None, due: str | None) -> None:
    parsed_start = _parse_optional_date(start)
    parsed_due = _parse_optional_date(due)
    if parsed_start is not None and parsed_due is not None and parsed_due < parsed_start:
        raise DraftValidationError(REASON_DATE_RANGE)


def _parse_optional_date(value: str | None) -> dt.date | None:
    """解析 ``YYYY-MM-DD``；形状对但日历不存在（2026-02-30）同样视为非法。"""

    if value is None:
        return None
    try:
        return dt.date.fromisoformat(value)
    except ValueError as exc:
        raise DraftValidationError(REASON_DATE_RANGE) from exc


def _merge_warnings(model_warnings: tuple[str, ...], generated: list[str]) -> tuple[str, ...]:
    """模型 warning 优先，自校验追加的 warning 在 20 条上限处截断（不报错）。"""

    merged = list(model_warnings)
    for warning in generated:
        if len(merged) >= MAX_WARNINGS:
            break
        merged.append(warning)
    return tuple(merged)


def _unique(values: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(values))


__all__ = [
    "REASON_DATE_RANGE",
    "REASON_DEPENDENCY_CYCLE",
    "REASON_MILESTONE_COUNT",
    "REASON_REQUIRED_TEXT",
    "REASON_TASK_COUNT",
    "REASON_TEMP_KEY",
    "REASON_UNKNOWN_DEPENDENCY",
    "REASON_UNKNOWN_MILESTONE",
    "DraftValidationError",
    "ValidatedDraft",
    "validate_draft",
]
