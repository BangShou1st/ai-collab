"""Planning draft 端点契约（设计 §7.6，W5 施工冻结文档 §1 的落地版）。

请求固定为 camelCase：

```json
{"schemaVersion":1,"goal":"…","locale":"zh-CN",
 "projectFacts":{"projectName":"…","description":"…",
   "members":[{"id":"<uuid>","name":"张三"}],
   "existingMilestoneTitles":["…"],"existingTaskTitles":["…"]},
 "template":"ITERATION",
 "constraints":{"maxTasks":20,"maxMilestones":6,"startDate":"2026-11-20","dueDate":"2026-12-31"},
 "deadlineMs":60000}
```

响应固定为（**只有这四个键**，`extra=forbid`）：

```json
{"schemaVersion":1,
 "plan":{"milestones":[{"tempKey":"m1","title":"…","objective":"…","description":"…",
          "targetDate":"2026-12-01","sourceRefs":["fact:project"]}],
         "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"…","objective":"…",
                   "description":"…","priority":"MEDIUM","estimatedHours":8.0,
                   "startDate":"2026-11-20","dueDate":"2026-11-27",
                   "assigneeId":"<uuid|null>","dependencyTempKeys":[],
                   "sourceRefs":["fact:project"],"sortOrder":1}]},
 "warnings":["…"],"usage":{"inputTokens":0,"outputTokens":0,"source":"UNKNOWN"}}
```

边界（冻结 §1 请求表逐行）：

- ``goal`` 1–4000；``locale`` 同 W4（2–35，``^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$``）；
- ``projectFacts.projectName`` 0–200；``description`` 0–4000；
  ``members`` 0–50（``id`` UUID；``name`` 1–100）；
  ``existingMilestoneTitles`` / ``existingTaskTitles`` 各 0–40，每项 1–200；
- ``template ∈ {ITERATION, DELIVERY, CUSTOM}``；
- ``constraints.maxTasks`` 1–40 默认 20；``maxMilestones`` 1–8 默认 6；
  ``startDate`` / ``dueDate`` 为 ISO 日期（``YYYY-MM-DD``，可空），都有时 ``start<=due``；
- ``deadlineMs`` 1–120000 默认 60000（端到端硬 deadline）；
- 响应 Plan 的绝对上限是 ``maxMilestones=8`` / ``maxTasks=40``；**本次请求的实际上限**由
  ``constraints`` 决定，在 ``cognition/planning/validator.py`` 里校验（契约模型拿不到请求上下文）。

冻结未逐字规定的两处裁量（已在 README §4.4 与交付报告登记）：

1. ``objective``/``description``/``targetDate``/``estimatedHours``/``assigneeId``/
   ``dependencyTempKeys``/``sourceRefs``/``sortOrder`` 在 **模型输出**里允许缺省（分别补
   ``""``/``""``/``null``/``null``/``null``/``[]``/``[]``/``null``）。冻结 §1 给的是边界表
   而不是必填表；把"没写描述"判成 422 会把正常草稿打回，且 Java 侧还会重校验一次。
   必填字段只有 ``tempKey``/``title``（里程碑）与 ``tempKey``/``milestoneTempKey``/``title``/
   ``priority``（任务）。
2. ``sourceRefs`` 的**单个元素**不设长度/形状约束：冻结规则 7 要求"不在派生集合内即丢弃该
   ref"，而不是 422。若在契约层加长度上限，会让"本应被丢弃的长 ref"变成 422，违背该条。
   合法 ref 由派生集合（``member:<uuid>``、``milestone:<index>``、``task:<index>``、
   ``fact:project``）穷尽，故这里只保留"条目数上限"作为资源护栏。

``assigneeId`` 在响应模型里只是 ``str | None``（而不是 UUID）：冻结规则 6 要求"不在成员列表
内 → 置 null 并 warning，不报错"。若契约层强制 UUID，模型编造的姓名/id 会变成 422 而不是
被清空；"非 null 就一定是成员 UUID"由自校验保证。
"""

from __future__ import annotations

import datetime as dt
import re
import uuid
from enum import StrEnum
from typing import Annotated, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, CommonRequest, ContractModel

#: goal 与 W4 同口径。
MAX_GOAL_CHARS: Final[int] = 4000

#: locale 形状与 W4 逐字一致。
LOCALE_PATTERN: Final[str] = r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$"
MIN_LOCALE_CHARS: Final[int] = 2
MAX_LOCALE_CHARS: Final[int] = 35

#: projectFacts 边界（冻结 §1 请求表）。
MAX_PROJECT_NAME_CHARS: Final[int] = 200
MAX_PROJECT_DESCRIPTION_CHARS: Final[int] = 4000
MAX_MEMBERS: Final[int] = 50
MAX_MEMBER_NAME_CHARS: Final[int] = 100
MAX_EXISTING_TITLES: Final[int] = 40
MAX_EXISTING_TITLE_CHARS: Final[int] = 200

#: constraints 边界（冻结 §1 请求表）。
MIN_MAX_TASKS: Final[int] = 1
MAX_MAX_TASKS: Final[int] = 40
DEFAULT_MAX_TASKS: Final[int] = 20
MIN_MAX_MILESTONES: Final[int] = 1
MAX_MAX_MILESTONES: Final[int] = 8
DEFAULT_MAX_MILESTONES: Final[int] = 6

#: planning 专用 deadline 默认值与区间（毫秒）。
DEFAULT_PLANNING_DEADLINE_MS: Final[int] = 60_000
MIN_PLANNING_DEADLINE_MS: Final[int] = 1
MAX_PLANNING_DEADLINE_MS: Final[int] = 120_000

#: 响应里 plan 的绝对上限（本次请求的实际上限更小，由 validator 校验）。
ABSOLUTE_MAX_MILESTONES: Final[int] = MAX_MAX_MILESTONES
ABSOLUTE_MAX_TASKS: Final[int] = MAX_MAX_TASKS

#: 单条草稿条目的文本上限（冻结 §1 第 8 条）。
MAX_TITLE_CHARS: Final[int] = 100
MAX_OBJECTIVE_CHARS: Final[int] = 500
MAX_DESCRIPTION_CHARS: Final[int] = 2000
MAX_TEMP_KEY_CHARS: Final[int] = 64

#: estimatedHours 区间（冻结 §1 第 4 条）。
MIN_ESTIMATED_HOURS: Final[float] = 0.5
MAX_ESTIMATED_HOURS: Final[float] = 200.0

#: warnings 边界（冻结 §1 第 8 条）。
MAX_WARNINGS: Final[int] = 20
MAX_WARNING_CHARS: Final[int] = 500

#: sourceRefs 的条目数护栏（合法 ref 由派生集合穷尽，见模块 docstring 裁量 2）。
MAX_SOURCE_REFS: Final[int] = 500

#: dependencyTempKeys 的条目数护栏（非法引用按冻结第 3 条直接报错）。
MAX_DEPENDENCIES: Final[int] = 64

#: ISO 日期形状：只接受扩展格式 ``YYYY-MM-DD``。
ISO_DATE_PATTERN: Final[str] = r"^\d{4}-\d{2}-\d{2}$"

#: 排序位上限：只是资源护栏，冻结未规定该字段的语义。
MAX_SORT_ORDER: Final[int] = 10_000

_ISO_DATE: Final[re.Pattern[str]] = re.compile(ISO_DATE_PATTERN)

WarningText = Annotated[str, Field(min_length=1, max_length=MAX_WARNING_CHARS)]
ExistingTitle = Annotated[str, Field(min_length=1, max_length=MAX_EXISTING_TITLE_CHARS)]
TempKey = Annotated[str, Field(min_length=1, max_length=MAX_TEMP_KEY_CHARS)]
Title = Annotated[str, Field(min_length=1, max_length=MAX_TITLE_CHARS)]
Objective = Annotated[str, Field(max_length=MAX_OBJECTIVE_CHARS)]
Description = Annotated[str, Field(max_length=MAX_DESCRIPTION_CHARS)]


def _validate_iso_date(value: str, field_name: str) -> str:
    """校验 ``YYYY-MM-DD`` 且是真实存在的日历日期（拒绝 2026-02-30）。"""

    if _ISO_DATE.fullmatch(value) is None:
        msg = f"{field_name} 必须是 ISO-8601 日期（YYYY-MM-DD）"
        raise ValueError(msg)
    try:
        dt.date.fromisoformat(value)
    except ValueError as exc:
        msg = f"{field_name} 不是真实存在的日历日期"
        raise ValueError(msg) from exc
    return value


class PlanningTemplate(StrEnum):
    """草稿模板（冻结 §1 只允许三个取值）。"""

    ITERATION = "ITERATION"
    DELIVERY = "DELIVERY"
    CUSTOM = "CUSTOM"


class PlanningPriority(StrEnum):
    """任务优先级白名单（冻结 §1 第 4 条）。"""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    URGENT = "URGENT"


class PlanningUsageSource(StrEnum):
    """usage 来源；与 rerank/decide 的取值一致（未知即未知，不填 0）。"""

    PROVIDER = "PROVIDER"
    ESTIMATE = "ESTIMATE"
    UNKNOWN = "UNKNOWN"


class PlanningMember(ContractModel):
    """项目成员：``id`` 是 UUID，``name`` 只用于渲染 Prompt。"""

    id: uuid.UUID
    name: str = Field(min_length=1, max_length=MAX_MEMBER_NAME_CHARS)


class PlanningProjectFacts(ContractModel):
    """``projectFacts``：项目名/描述/成员/既有标题。"""

    project_name: str = Field(default="", max_length=MAX_PROJECT_NAME_CHARS)
    description: str = Field(default="", max_length=MAX_PROJECT_DESCRIPTION_CHARS)
    members: tuple[PlanningMember, ...] = Field(default=(), max_length=MAX_MEMBERS)
    existing_milestone_titles: tuple[ExistingTitle, ...] = Field(
        default=(), max_length=MAX_EXISTING_TITLES
    )
    existing_task_titles: tuple[ExistingTitle, ...] = Field(
        default=(), max_length=MAX_EXISTING_TITLES
    )


class PlanningConstraints(ContractModel):
    """``constraints``：数量上限与计划区间。"""

    max_tasks: int = Field(default=DEFAULT_MAX_TASKS, ge=MIN_MAX_TASKS, le=MAX_MAX_TASKS)
    max_milestones: int = Field(
        default=DEFAULT_MAX_MILESTONES, ge=MIN_MAX_MILESTONES, le=MAX_MAX_MILESTONES
    )
    start_date: str | None = Field(
        default=None, description="计划区间起点（含），YYYY-MM-DD；null 表示不限。"
    )
    due_date: str | None = Field(
        default=None, description="计划区间终点（含），YYYY-MM-DD；null 表示不限。"
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_integers(cls, data: object) -> object:
        """``True == 1``：JSON 布尔不得冒充数量上限（形状错误，不是范围错误）。"""

        if isinstance(data, dict):
            for key in ("maxTasks", "max_tasks", "maxMilestones", "max_milestones"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data

    @field_validator("start_date")
    @classmethod
    def _check_start_date(cls, value: str | None) -> str | None:
        return None if value is None else _validate_iso_date(value, "constraints.startDate")

    @field_validator("due_date")
    @classmethod
    def _check_due_date(cls, value: str | None) -> str | None:
        return None if value is None else _validate_iso_date(value, "constraints.dueDate")

    @model_validator(mode="after")
    def _check_window_order(self) -> Self:
        if (
            self.start_date is not None
            and self.due_date is not None
            and self.start_date > self.due_date
        ):
            msg = "constraints.startDate 不得晚于 constraints.dueDate"
            raise ValueError(msg)
        return self


class PlanningDraftRequest(CommonRequest):
    """``POST /v1/planning/draft`` 请求。

    ``deadline_ms`` 覆写通用请求的 10 分钟上限：planning 只允许 1–120000ms，默认 60000ms。
    """

    goal: str = Field(min_length=1, max_length=MAX_GOAL_CHARS)
    locale: str = Field(
        min_length=MIN_LOCALE_CHARS,
        max_length=MAX_LOCALE_CHARS,
        pattern=LOCALE_PATTERN,
        description="输出语言/地区，例如 zh-CN；Java 原样下发。",
    )
    project_facts: PlanningProjectFacts
    template: PlanningTemplate
    constraints: PlanningConstraints
    deadline_ms: int = Field(
        default=DEFAULT_PLANNING_DEADLINE_MS,
        ge=MIN_PLANNING_DEADLINE_MS,
        le=MAX_PLANNING_DEADLINE_MS,
        description="端到端硬 deadline（毫秒）；到点返回 DEADLINE_EXCEEDED，无半成品。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_schema_version(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version", "deadlineMs", "deadline_ms"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data


class PlanningMilestone(ContractModel):
    """草稿里程碑；``tempKey`` 在里程碑域内唯一，供任务引用。"""

    temp_key: TempKey
    title: Title
    objective: Objective = ""
    description: Description = ""
    target_date: str | None = Field(
        default=None, pattern=ISO_DATE_PATTERN, description="计划完成日（YYYY-MM-DD）；可空。"
    )
    source_refs: tuple[str, ...] = Field(
        default=(),
        max_length=MAX_SOURCE_REFS,
        description="来源引用；非派生集合内的元素由自校验丢弃（不是 422）。",
    )


class PlanningTask(ContractModel):
    """草稿任务；``milestoneTempKey`` 必须引用本次里程碑。"""

    temp_key: TempKey
    milestone_temp_key: TempKey
    title: Title
    objective: Objective = ""
    description: Description = ""
    priority: PlanningPriority = Field(description="LOW / MEDIUM / HIGH / URGENT。")
    estimated_hours: float | None = Field(
        default=None,
        ge=MIN_ESTIMATED_HOURS,
        le=MAX_ESTIMATED_HOURS,
        allow_inf_nan=False,
        description="预估工时；0.5–200 或 null。",
    )
    start_date: str | None = Field(default=None, pattern=ISO_DATE_PATTERN)
    due_date: str | None = Field(default=None, pattern=ISO_DATE_PATTERN)
    assignee_id: str | None = Field(
        default=None,
        max_length=64,
        description="成员 UUID 或 null；不在 members 内由自校验置 null 并 warning。",
    )
    dependency_temp_keys: tuple[str, ...] = Field(
        default=(),
        max_length=MAX_DEPENDENCIES,
        description="前置任务 tempKey；必须引用本次 tasks 且无环（自校验）。",
    )
    source_refs: tuple[str, ...] = Field(default=(), max_length=MAX_SOURCE_REFS)
    sort_order: int | None = Field(default=None, ge=0, le=MAX_SORT_ORDER)

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_numbers(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("estimatedHours", "estimated_hours", "sortOrder", "sort_order"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为数字"
                    raise ValueError(msg)
        return data


class PlanningPlan(ContractModel):
    """``plan``：1–8 个里程碑、1–40 个任务（本次请求的更小上限由自校验保证）。"""

    milestones: tuple[PlanningMilestone, ...] = Field(
        default=(), min_length=1, max_length=ABSOLUTE_MAX_MILESTONES
    )
    tasks: tuple[PlanningTask, ...] = Field(default=(), min_length=1, max_length=ABSOLUTE_MAX_TASKS)


class PlanningDraftOutput(ContractModel):
    """模型输出的形状（冻结 §1 第 9 条：单个 JSON object，字段缺失/多余/类型错都 422）。"""

    plan: PlanningPlan
    warnings: tuple[WarningText, ...] = Field(default=(), max_length=MAX_WARNINGS)


class PlanningUsage(ContractModel):
    """``usage``：来自 Java 网关；缺失标 ``UNKNOWN`` 且 token 为 ``null``，不填 0。"""

    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    source: PlanningUsageSource = PlanningUsageSource.UNKNOWN

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_tokens(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("inputTokens", "input_tokens", "outputTokens", "output_tokens"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数或 null"
                    raise ValueError(msg)
        return data


class PlanningDraftResponse(ContractModel):
    """``POST /v1/planning/draft`` 响应（冻结 §1 的四个键，多一个都 422）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    plan: PlanningPlan
    warnings: tuple[WarningText, ...] = Field(default=(), max_length=MAX_WARNINGS)
    usage: PlanningUsage = Field(default_factory=PlanningUsage)


__all__ = [
    "ABSOLUTE_MAX_MILESTONES",
    "ABSOLUTE_MAX_TASKS",
    "DEFAULT_MAX_MILESTONES",
    "DEFAULT_MAX_TASKS",
    "DEFAULT_PLANNING_DEADLINE_MS",
    "ISO_DATE_PATTERN",
    "LOCALE_PATTERN",
    "MAX_DEPENDENCIES",
    "MAX_DESCRIPTION_CHARS",
    "MAX_ESTIMATED_HOURS",
    "MAX_EXISTING_TITLES",
    "MAX_EXISTING_TITLE_CHARS",
    "MAX_GOAL_CHARS",
    "MAX_LOCALE_CHARS",
    "MAX_MAX_MILESTONES",
    "MAX_MAX_TASKS",
    "MAX_MEMBERS",
    "MAX_MEMBER_NAME_CHARS",
    "MAX_OBJECTIVE_CHARS",
    "MAX_PLANNING_DEADLINE_MS",
    "MAX_PROJECT_DESCRIPTION_CHARS",
    "MAX_PROJECT_NAME_CHARS",
    "MAX_SORT_ORDER",
    "MAX_SOURCE_REFS",
    "MAX_TEMP_KEY_CHARS",
    "MAX_TITLE_CHARS",
    "MAX_WARNINGS",
    "MAX_WARNING_CHARS",
    "MIN_ESTIMATED_HOURS",
    "MIN_LOCALE_CHARS",
    "MIN_MAX_MILESTONES",
    "MIN_MAX_TASKS",
    "MIN_PLANNING_DEADLINE_MS",
    "PlanningConstraints",
    "PlanningDraftOutput",
    "PlanningDraftRequest",
    "PlanningDraftResponse",
    "PlanningMember",
    "PlanningMilestone",
    "PlanningPlan",
    "PlanningPriority",
    "PlanningProjectFacts",
    "PlanningTask",
    "PlanningTemplate",
    "PlanningUsage",
    "PlanningUsageSource",
]
