"""定向变异器：把"校验器能否拒绝非法输出"变成可复现的离线证据（D6）。

背景：S3 的请求级 adversarial 负样本依赖"模型照抄非法输入"，真实模型不配合，
``rejectedRate`` 恒为 0，不能证明校验器有效。D6 改为**对合法输出做定向变异**：

- planning：不存在的 ``milestoneTempKey`` 引用、依赖环、任务数超 ``maxTasks``、
  日期越过 ``dueDate``、重复 ``tempKey``；
- decide：越界工具名、非法 decision 枚举、缺必填字段、非法 JSON。

每个变异体都喂回与在线端点同一段解析/校验核心（``parse_draft`` + ``validate_draft`` /
``parse_decision``），并记录是否被拒绝与固定原因。变异体只从**当前通过校验的个案**
派生；原输出不被误拒由基线评测本身保证。

``sourceRefs`` 非空不在变异列表里：当前 validator 对 ``sourceRefs`` 是**清洗并计数**，
不是拒绝；把它算进 ``mutationRejectedRate`` 会把"清洗"伪装成"拒绝"。这一点在
``planning`` 的 ``bySubset.adversarialMutations`` 说明中保留。
"""

from __future__ import annotations

import copy
import datetime as dt
import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Final

from ai_collab_worker.cognition.decide.parser import (
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.cognition.planning.parser import DraftValidationError, parse_draft
from ai_collab_worker.cognition.planning.validator import validate_draft
from ai_collab_worker.contract.agent_decide import AgentDecideRequest
from ai_collab_worker.contract.planning import PlanningDraftRequest

#: planning 变异类型（顺序固定，报告按此顺序输出）。
PLANNING_MUTATION_UNKNOWN_MILESTONE: Final[str] = "unknown_milestone"
PLANNING_MUTATION_DEPENDENCY_CYCLE: Final[str] = "dependency_cycle"
PLANNING_MUTATION_TOO_MANY_TASKS: Final[str] = "too_many_tasks"
PLANNING_MUTATION_DATE_BEYOND_DUE: Final[str] = "date_beyond_due"
PLANNING_MUTATION_DUPLICATE_TEMP_KEY: Final[str] = "duplicate_temp_key"

#: decide 变异类型（顺序固定，报告按此顺序输出）。
DECIDE_MUTATION_UNKNOWN_TOOL: Final[str] = "unknown_tool"
DECIDE_MUTATION_INVALID_DECISION: Final[str] = "invalid_decision"
DECIDE_MUTATION_MISSING_REQUIRED_FIELD: Final[str] = "missing_required_field"
DECIDE_MUTATION_INVALID_JSON: Final[str] = "invalid_json"

#: 变异源：replay 数据集里的 modelOutput / worker 真实响应解析后重建的决策 JSON。
MUTATION_SOURCE_REPLAY_DATASET: Final[str] = "replay_dataset"
MUTATION_SOURCE_WORKER_RESPONSE: Final[str] = "worker_response_reconstructed"

#: 变异源说明（写进报告，避免把 stub 变异当成真实模型输出）。
MUTATION_SOURCE_NOTES: Final[Mapping[str, str]] = {
    MUTATION_SOURCE_REPLAY_DATASET: (
        "变异源是 replay 数据集里的 modelOutput；若使用 *_real_* fixture，"
        "该 modelOutput 是归档真实模型输出（见报告 evidence/model 字段），"
        "否则是 stub 输出。"
    ),
    MUTATION_SOURCE_WORKER_RESPONSE: (
        "变异源是 worker 真实响应解析后的 decision/toolCall 重建 JSON，"
        "不是模型原始文本；arguments 按响应原样保留（若响应缺省则为空对象）。"
    ),
}

#: 合成变异使用的哨兵值；刻意不与 fixture 中的任何合法值重叠。
_SENTINEL_UNKNOWN_MILESTONE: Final[str] = "__eval_missing_milestone__"
_SENTINEL_UNKNOWN_TOOL: Final[str] = "__eval_forbidden_tool__"
_SENTINEL_INVALID_DECISION: Final[str] = "DELEGATE"
_MAX_TASKS_ABSOLUTE: Final[int] = 40
_MAX_TEMP_KEY_CHARS: Final[int] = 64


@dataclass(frozen=True, slots=True)
class MutationVariant:
    """一个待喂给校验器的变异体；``payload`` 是 JSON 文本。"""

    kind: str
    payload: str
    description: str


@dataclass(frozen=True, slots=True)
class MutationOutcome:
    """一个变异体的校验结果；``reason`` 是固定短语，不含模型原文。"""

    kind: str
    rejected: bool
    reason: str | None


def planning_mutations(
    model_output: str, request: PlanningDraftRequest
) -> tuple[MutationVariant, ...]:
    """从一份**合法** planning 输出派生确定性变异体；非法输入返回空元组。"""

    payload = _load_plan(model_output)
    if payload is None:
        return ()
    variants: list[MutationVariant] = []
    for variant in (
        _unknown_milestone_variant(payload),
        _dependency_cycle_variant(payload, request),
        _too_many_tasks_variant(payload, request),
        _date_beyond_due_variant(payload, request),
        _duplicate_temp_key_variant(payload, request),
    ):
        if variant is not None:
            variants.append(variant)
    return tuple(variants)


def evaluate_planning_mutations(
    model_output: str, request: PlanningDraftRequest
) -> tuple[MutationOutcome, ...]:
    """逐变异体执行 ``parse_draft`` + ``validate_draft``，记录是否被拒绝。"""

    outcomes: list[MutationOutcome] = []
    for variant in planning_mutations(model_output, request):
        try:
            output = parse_draft(variant.payload)
            validate_draft(output, request)
        except DraftValidationError as exc:
            outcomes.append(MutationOutcome(variant.kind, True, exc.reason))
        else:
            outcomes.append(MutationOutcome(variant.kind, False, None))
    return tuple(outcomes)


def decide_mutations(model_output: str, request: AgentDecideRequest) -> tuple[MutationVariant, ...]:
    """从一份**合法** decision 输出派生确定性变异体；非法输入返回空元组。"""

    root = _load_decision(model_output, request)
    if root is None:
        return ()
    return (
        _unknown_tool_variant(root),
        _invalid_decision_variant(root),
        _missing_decision_variant(root),
        _invalid_json_variant(),
    )


def evaluate_decide_mutations(
    model_output: str, request: AgentDecideRequest
) -> tuple[MutationOutcome, ...]:
    """逐变异体执行 ``parse_decision``，记录是否被拒绝。"""

    outcomes: list[MutationOutcome] = []
    for variant in decide_mutations(model_output, request):
        try:
            parse_decision(variant.payload, request.tools)
        except DecisionValidationError as exc:
            outcomes.append(MutationOutcome(variant.kind, True, exc.reason))
        else:
            outcomes.append(MutationOutcome(variant.kind, False, None))
    return tuple(outcomes)


# --------------------------------------------------------------------------- #
# planning 变异实现
# --------------------------------------------------------------------------- #


def _load_plan(model_output: str) -> dict[str, Any] | None:
    try:
        parsed = parse_draft(model_output)
    except DraftValidationError:
        return None
    payload: dict[str, Any] = parsed.model_dump(by_alias=True, mode="json")
    return payload


def _unknown_milestone_variant(payload: Mapping[str, Any]) -> MutationVariant | None:
    mutated = copy.deepcopy(dict(payload))
    tasks = _task_list(mutated)
    if not tasks:
        return None
    tasks[0]["milestoneTempKey"] = _SENTINEL_UNKNOWN_MILESTONE
    return MutationVariant(
        PLANNING_MUTATION_UNKNOWN_MILESTONE,
        _dump(mutated),
        "把第一个任务的 milestoneTempKey 指向不存在的里程碑",
    )


def _dependency_cycle_variant(
    payload: Mapping[str, Any], request: PlanningDraftRequest
) -> MutationVariant | None:
    mutated = copy.deepcopy(dict(payload))
    tasks = _task_list(mutated)
    if not tasks:
        return None
    if len(tasks) >= 2:
        first_key = tasks[0]["tempKey"]
        second_key = tasks[1]["tempKey"]
        tasks[0]["dependencyTempKeys"] = [second_key]
        tasks[1]["dependencyTempKeys"] = [first_key]
    else:
        if request.constraints.max_tasks < 2:
            return None
        first_key = tasks[0]["tempKey"]
        clone = copy.deepcopy(tasks[0])
        clone_key = "__eval_cycle_task__"
        if len(clone_key) > _MAX_TEMP_KEY_CHARS:
            return None
        clone["tempKey"] = clone_key
        clone["dependencyTempKeys"] = [first_key]
        tasks[0]["dependencyTempKeys"] = [clone_key]
        tasks.append(clone)
    return MutationVariant(
        PLANNING_MUTATION_DEPENDENCY_CYCLE,
        _dump(mutated),
        "构造两个任务互相依赖，形成依赖环",
    )


def _too_many_tasks_variant(
    payload: Mapping[str, Any], request: PlanningDraftRequest
) -> MutationVariant | None:
    max_tasks = request.constraints.max_tasks
    if max_tasks >= _MAX_TASKS_ABSOLUTE:
        return None
    mutated = copy.deepcopy(dict(payload))
    tasks = _task_list(mutated)
    if not tasks:
        return None
    if len(tasks) > max_tasks:
        return None
    missing = max_tasks + 1 - len(tasks)
    template = tasks[0]
    for index in range(missing):
        clone = copy.deepcopy(template)
        clone["tempKey"] = f"__eval_over_{index}__"
        clone["dependencyTempKeys"] = []
        tasks.append(clone)
    return MutationVariant(
        PLANNING_MUTATION_TOO_MANY_TASKS,
        _dump(mutated),
        f"把任务数补到 maxTasks+1（{max_tasks + 1}）",
    )


def _date_beyond_due_variant(
    payload: Mapping[str, Any], request: PlanningDraftRequest
) -> MutationVariant | None:
    due_raw = request.constraints.due_date
    if due_raw is None:
        return None
    try:
        due = dt.date.fromisoformat(due_raw)
    except ValueError:
        return None
    if due >= dt.date.max:
        return None
    mutated = copy.deepcopy(dict(payload))
    tasks = _task_list(mutated)
    if not tasks:
        return None
    tasks[0]["dueDate"] = (due + dt.timedelta(days=1)).isoformat()
    return MutationVariant(
        PLANNING_MUTATION_DATE_BEYOND_DUE,
        _dump(mutated),
        f"把第一个任务的 dueDate 推到约束 dueDate 之后（{due_raw} + 1 天）",
    )


def _duplicate_temp_key_variant(
    payload: Mapping[str, Any], request: PlanningDraftRequest
) -> MutationVariant | None:
    mutated = copy.deepcopy(dict(payload))
    tasks = _task_list(mutated)
    if not tasks:
        return None
    if len(tasks) >= 2:
        tasks[1]["tempKey"] = tasks[0]["tempKey"]
    else:
        if request.constraints.max_tasks < 2:
            return None
        clone = copy.deepcopy(tasks[0])
        clone["tempKey"] = tasks[0]["tempKey"]
        clone["dependencyTempKeys"] = []
        tasks.append(clone)
    return MutationVariant(
        PLANNING_MUTATION_DUPLICATE_TEMP_KEY,
        _dump(mutated),
        "让两个任务使用同一个 tempKey",
    )


def _task_list(payload: dict[str, Any]) -> list[dict[str, Any]]:
    plan = payload.get("plan")
    if not isinstance(plan, dict):
        return []
    tasks = plan.get("tasks")
    if not isinstance(tasks, list):
        return []
    if not all(isinstance(task, dict) for task in tasks):
        return []
    return tasks


# --------------------------------------------------------------------------- #
# decide 变异实现
# --------------------------------------------------------------------------- #


def _load_decision(model_output: str, request: AgentDecideRequest) -> dict[str, Any] | None:
    try:
        parse_decision(model_output, request.tools)
    except DecisionValidationError:
        return None
    root = _loads_object(model_output)
    if root is None:
        return None
    return root


def _unknown_tool_variant(root: Mapping[str, Any]) -> MutationVariant:
    mutated = copy.deepcopy(dict(root))
    mutated["decision"] = "CALL_TOOL"
    mutated["toolCall"] = {"name": _SENTINEL_UNKNOWN_TOOL, "arguments": {}}
    return MutationVariant(
        DECIDE_MUTATION_UNKNOWN_TOOL,
        _dump(mutated),
        "把 decision 改为 CALL_TOOL 并给出本次 tools 之外的名称",
    )


def _invalid_decision_variant(root: Mapping[str, Any]) -> MutationVariant:
    mutated = copy.deepcopy(dict(root))
    mutated["decision"] = _SENTINEL_INVALID_DECISION
    mutated.pop("toolCall", None)
    return MutationVariant(
        DECIDE_MUTATION_INVALID_DECISION,
        _dump(mutated),
        "把 decision 改为四个合法枚举之外的值",
    )


def _missing_decision_variant(root: Mapping[str, Any]) -> MutationVariant:
    mutated = copy.deepcopy(dict(root))
    mutated.pop("decision", None)
    return MutationVariant(
        DECIDE_MUTATION_MISSING_REQUIRED_FIELD,
        _dump(mutated),
        "删除必填字段 decision",
    )


def _invalid_json_variant() -> MutationVariant:
    return MutationVariant(
        DECIDE_MUTATION_INVALID_JSON,
        '{"decision":',
        "把输出截断成非法 JSON",
    )


def _loads_object(model_output: str) -> dict[str, Any] | None:
    cleaned = model_output.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[len("```json") :]
    elif cleaned.startswith("```"):
        cleaned = cleaned[len("```") :]
    if cleaned.endswith("```"):
        cleaned = cleaned[: -len("```")]
    cleaned = cleaned.strip()
    try:
        value: object = json.loads(cleaned)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def _dump(payload: Mapping[str, Any]) -> str:
    """稳定序列化：键排序、无多余空白；变异体只用于校验，不进入报告原文。"""

    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


__all__ = [
    "DECIDE_MUTATION_INVALID_DECISION",
    "DECIDE_MUTATION_INVALID_JSON",
    "DECIDE_MUTATION_MISSING_REQUIRED_FIELD",
    "DECIDE_MUTATION_UNKNOWN_TOOL",
    "MUTATION_SOURCE_NOTES",
    "MUTATION_SOURCE_REPLAY_DATASET",
    "MUTATION_SOURCE_WORKER_RESPONSE",
    "PLANNING_MUTATION_DATE_BEYOND_DUE",
    "PLANNING_MUTATION_DEPENDENCY_CYCLE",
    "PLANNING_MUTATION_DUPLICATE_TEMP_KEY",
    "PLANNING_MUTATION_TOO_MANY_TASKS",
    "PLANNING_MUTATION_UNKNOWN_MILESTONE",
    "MutationOutcome",
    "MutationVariant",
    "decide_mutations",
    "evaluate_decide_mutations",
    "evaluate_planning_mutations",
    "planning_mutations",
]
