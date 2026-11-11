"""Agent 单轮决策端点契约（设计 §7.5，W4 施工冻结文档 §2 的落地版）。

请求固定为 camelCase：

```json
{
  "schemaVersion":1,
  "runId":"…","attempt":2,"turnNo":5,"snapshotHash":"sha256:…",
  "scenario":{"code":"PROJECT_RESEARCH","category":"SCENARIO","promptPack":"…"},
  "goal":"…","locale":"zh-CN",
  "context":{"facts":["…"],
             "messages":[{"role":"USER","content":"…"}],
             "observations":[{"tool":"search_project_knowledge","summary":"…"}]},
  "tools":[{"name":"search_project_knowledge","description":"…",
            "inputSchema":{"type":"object"},"risk":"READ_ONLY"}],
  "policy":{"allowWrite":false,"allowExternal":false},
  "budget":{"maxInputTokens":24000,"maxOutputTokens":2000,"deadlineMs":30000}
}
```

响应固定为：

```json
{"schemaVersion":1,"decision":"CALL_TOOL",
 "toolCall":{"name":"search_project_knowledge","arguments":{"query":"…"}},
 "usage":{"inputTokens":8123,"outputTokens":96,"source":"PROVIDER"},
 "promptVersion":"2026-12-01.1","modelAlias":"agent-main","latencyMs":2140}
```

边界（冻结表原文）：

- ``runId`` 1–128；``attempt`` 1–50；``turnNo`` 0–500；``snapshotHash`` 1–128；
- ``scenario.code`` 1–64（``^[A-Z][A-Z0-9_]{1,63}$``）；``scenario.category``
  ``SCENARIO``/``WORKFLOW``；``scenario.promptPack`` 0–160、可缺省（仅追溯用）；
- ``goal`` 1–4000；``locale`` 同 query plan；
- ``context.facts`` 0–20、每项 1–2000、总 ≤16000 字符；
  ``context.messages`` 0–20、``role ∈ USER/ASSISTANT/TOOL``、``content`` 1–4000、总 ≤24000；
  ``context.observations`` 0–20、``tool`` 1–128、``summary`` 1–2000、总 ≤16000；
  三段合计 ≤48 KiB 字符；
- ``tools`` 1–32，``name`` 1–128 且唯一、``description`` 1–1000、``inputSchema`` JSON object
  （每项 ≤8 KiB）、``risk ∈ READ_ONLY/WRITE``；
- ``policy`` 布尔；Python **不得**因 ``allowWrite=true`` 扩大工具集；
- ``budget.maxInputTokens``/``maxOutputTokens`` 1..2_000_000；``deadlineMs`` 1–600000。

语义约束（由服务层与解析层落实，不在模型里重复）：

- ``decision`` 只允许四个值；``CALL_TOOL`` 必须带 ``toolCall.name``（∈ 本次 ``tools``）与
  ``arguments``（JSON object）；其它决策 ``toolCall`` 必须缺省或 ``null``；
- Python 不接收、不解释 ``leaseEpoch``；**不返回 token 流**（避免与 Java 的流式通道双写），
  因此本文件不含任何 ``delta``/``stream`` 字段；
- ``usage`` 缺失标 ``UNKNOWN``、token 为 ``null``，不填 0（设计 §1.3 第 6 条"未知即未知"）；
- ``promptVersion`` 取本次实际使用的 decide 主 Prompt 的 manifest 版本；
- ``latencyMs`` 取 Java 网关返回的模型调用耗时，缺失为 ``null``。
"""

from __future__ import annotations

import json
from enum import StrEnum
from typing import Annotated, Any, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, ContractModel
from ai_collab_worker.contract.prompts import PROMPT_VERSION_PATTERN

#: 幂等键四元组的边界。
MAX_RUN_ID_CHARS: Final[int] = 128
MIN_ATTEMPT: Final[int] = 1
MAX_ATTEMPT: Final[int] = 50
MIN_TURN_NO: Final[int] = 0
MAX_TURN_NO: Final[int] = 500

#: scenario 形状与类别。
SCENARIO_CODE_PATTERN: Final[str] = r"^[A-Z][A-Z0-9_]{1,63}$"
MAX_SCENARIO_CODE_CHARS: Final[int] = 64
MAX_SCENARIO_PROMPT_PACK_CHARS: Final[int] = 160

#: goal / locale 与 query plan 同口径。
MAX_GOAL_CHARS: Final[int] = 4000
LOCALE_PATTERN: Final[str] = r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$"
MIN_LOCALE_CHARS: Final[int] = 2
MAX_LOCALE_CHARS: Final[int] = 35

#: context 三段边界（冻结表原文）。
MAX_CONTEXT_ITEMS: Final[int] = 20
MAX_FACT_CHARS: Final[int] = 2000
MAX_FACTS_TOTAL_CHARS: Final[int] = 16_000
MAX_MESSAGE_CHARS: Final[int] = 4000
MAX_MESSAGES_TOTAL_CHARS: Final[int] = 24_000
MAX_OBSERVATION_TOOL_CHARS: Final[int] = 128
MAX_OBSERVATION_SUMMARY_CHARS: Final[int] = 2000
MAX_OBSERVATIONS_TOTAL_CHARS: Final[int] = 16_000

#: 上下文总字符上限：48 KiB（同时须满足 Java 网关 64 KiB prompt 上限，后者由服务层按
#: 实际拼出的 system+user 字节数检查）。
MAX_CONTEXT_TOTAL_CHARS: Final[int] = 48 * 1024

#: tools 边界。
MAX_TOOLS: Final[int] = 32
MIN_TOOLS: Final[int] = 1
MAX_TOOL_NAME_CHARS: Final[int] = 128
MAX_TOOL_DESCRIPTION_CHARS: Final[int] = 1000
MAX_INPUT_SCHEMA_BYTES: Final[int] = 8 * 1024

#: budget 边界。
MIN_BUDGET_TOKENS: Final[int] = 1
MAX_BUDGET_TOKENS: Final[int] = 2_000_000
MIN_DECIDE_DEADLINE_MS: Final[int] = 1
MAX_DECIDE_DEADLINE_MS: Final[int] = 600_000

Fact = Annotated[str, Field(min_length=1, max_length=MAX_FACT_CHARS)]
MessageContent = Annotated[str, Field(min_length=1, max_length=MAX_MESSAGE_CHARS)]
ObservationTool = Annotated[str, Field(min_length=1, max_length=MAX_OBSERVATION_TOOL_CHARS)]
ObservationSummary = Annotated[str, Field(min_length=1, max_length=MAX_OBSERVATION_SUMMARY_CHARS)]
ToolName = Annotated[str, Field(min_length=1, max_length=MAX_TOOL_NAME_CHARS)]


class ScenarioCategory(StrEnum):
    """场景类别（冻结表只允许两个取值）。"""

    SCENARIO = "SCENARIO"
    WORKFLOW = "WORKFLOW"


class MessageRole(StrEnum):
    """上下文消息角色（冻结表只允许三个取值）。"""

    USER = "USER"
    ASSISTANT = "ASSISTANT"
    TOOL = "TOOL"


class ToolRisk(StrEnum):
    """工具风险等级；Python 只读校验，实际授权在 Java。"""

    READ_ONLY = "READ_ONLY"
    WRITE = "WRITE"


class DecideDecision(StrEnum):
    """四决策枚举（冻结表 §2 规则 1；其它值一律契约违规）。"""

    CALL_TOOL = "CALL_TOOL"
    FINAL = "FINAL"
    ASK_USER = "ASK_USER"
    NO_OP = "NO_OP"


class DecideUsageSource(StrEnum):
    """usage 来源；与 rerank 的取值一致（设计 §7.1 的 TokenMeasurement.source 子集）。"""

    PROVIDER = "PROVIDER"
    ESTIMATE = "ESTIMATE"
    UNKNOWN = "UNKNOWN"


def _json_object_size_bytes(value: dict[str, Any]) -> int:
    """``value`` 作为紧凑 UTF-8 JSON 的字节数；非有限数/不可序列化视为非法。"""

    encoded = json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return len(encoded.encode("utf-8"))


class DecideScenario(ContractModel):
    """``scenario``：场景标识；``promptPack`` 仅追溯用，Python 不解析其语义。"""

    code: str = Field(
        min_length=1,
        max_length=MAX_SCENARIO_CODE_CHARS,
        pattern=SCENARIO_CODE_PATTERN,
        description="大写下划线场景码，例如 PROJECT_RESEARCH。",
    )
    category: ScenarioCategory = Field(description="SCENARIO 或 WORKFLOW。")
    prompt_pack: str = Field(
        default="",
        max_length=MAX_SCENARIO_PROMPT_PACK_CHARS,
        description="Java 侧 PromptPack 引用的追溯串；Python 不据此拼接场景指令。",
    )


class DecideMessage(ContractModel):
    """一条上下文消息；``content`` 按 Unicode 码点计数。"""

    role: MessageRole
    content: MessageContent


class DecideObservation(ContractModel):
    """一条工具观测摘要（不含工具输出全文）。"""

    tool: ObservationTool
    summary: ObservationSummary


class DecideContext(ContractModel):
    """三段有界上下文；总量与单条上限在 :class:`AgentDecideRequest` 里统一校验。"""

    facts: tuple[Fact, ...] = Field(
        default=(),
        max_length=MAX_CONTEXT_ITEMS,
        description="结构化事实片段，最多 20 条。",
    )
    messages: tuple[DecideMessage, ...] = Field(
        default=(),
        max_length=MAX_CONTEXT_ITEMS,
        description="对话消息，最多 20 条。",
    )
    observations: tuple[DecideObservation, ...] = Field(
        default=(),
        max_length=MAX_CONTEXT_ITEMS,
        description="工具观测摘要，最多 20 条。",
    )


class DecideTool(ContractModel):
    """本次下发的工具定义；``inputSchema`` 必须是 JSON object 且 ≤8 KiB。"""

    name: ToolName
    description: str = Field(min_length=1, max_length=MAX_TOOL_DESCRIPTION_CHARS)
    input_schema: dict[str, Any] = Field(
        description="JSON Schema 对象；Python 只做形状与体积校验，不解释语义。",
    )
    risk: ToolRisk = Field(description="READ_ONLY 或 WRITE；Python 不得据此扩权。")

    @field_validator("input_schema")
    @classmethod
    def _check_input_schema(cls, value: dict[str, Any]) -> dict[str, Any]:
        try:
            size = _json_object_size_bytes(value)
        except (TypeError, ValueError) as exc:
            msg = "inputSchema 必须是可序列化的 JSON object"
            raise ValueError(msg) from exc
        if size > MAX_INPUT_SCHEMA_BYTES:
            msg = f"inputSchema 超过 {MAX_INPUT_SCHEMA_BYTES} 字节上限"
            raise ValueError(msg)
        return value


class DecidePolicy(ContractModel):
    """权限提示；Python 只用它渲染 Prompt，不据此扩大或缩小工具集。"""

    allow_write: bool
    allow_external: bool


class DecideBudget(ContractModel):
    """本轮预算；``deadlineMs`` 是硬 deadline，服务端只取 min(请求值, 配置上限)。"""

    max_input_tokens: int = Field(ge=MIN_BUDGET_TOKENS, le=MAX_BUDGET_TOKENS)
    max_output_tokens: int = Field(ge=MIN_BUDGET_TOKENS, le=MAX_BUDGET_TOKENS)
    deadline_ms: int = Field(ge=MIN_DECIDE_DEADLINE_MS, le=MAX_DECIDE_DEADLINE_MS)

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_integers(cls, data: object) -> object:
        """``True == 1``：JSON 布尔不得冒充 token/deadline 整数。"""

        if isinstance(data, dict):
            for key in (
                "maxInputTokens",
                "max_input_tokens",
                "maxOutputTokens",
                "max_output_tokens",
                "deadlineMs",
                "deadline_ms",
            ):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data


class AgentDecideRequest(ContractModel):
    """``POST /v1/agent/decide`` 请求。

    ``deadlineMs`` 位于 ``budget`` 内（与设计 §7.5 一致）；根级不接受 ``deadlineMs``，
    多传即 422（``extra=forbid``），避免出现两套 deadline 语义。
    """

    schema_version: Literal[1] = SCHEMA_VERSION
    run_id: str = Field(min_length=1, max_length=MAX_RUN_ID_CHARS)
    attempt: int = Field(ge=MIN_ATTEMPT, le=MAX_ATTEMPT)
    turn_no: int = Field(ge=MIN_TURN_NO, le=MAX_TURN_NO)
    snapshot_hash: str = Field(min_length=1, max_length=MAX_RUN_ID_CHARS)
    scenario: DecideScenario
    goal: str = Field(min_length=1, max_length=MAX_GOAL_CHARS)
    locale: str = Field(
        min_length=MIN_LOCALE_CHARS,
        max_length=MAX_LOCALE_CHARS,
        pattern=LOCALE_PATTERN,
    )
    context: DecideContext
    tools: tuple[DecideTool, ...] = Field(
        min_length=MIN_TOOLS,
        max_length=MAX_TOOLS,
        description="本次暴露的工具；name 必须唯一。",
    )
    policy: DecidePolicy
    budget: DecideBudget

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_schema_version(cls, data: object) -> object:
        """``True == 1`` 在 Python 里成立；JSON 的 ``true`` 不得冒充整数边界字段。"""

        if isinstance(data, dict):
            for key in (
                "schemaVersion",
                "schema_version",
                "attempt",
                "turnNo",
                "turn_no",
            ):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data

    @model_validator(mode="after")
    def _check_semantics(self) -> Self:
        # tools.name 唯一：否则"工具名合法"会变成不确定语义。
        names = [tool.name for tool in self.tools]
        if len(set(names)) != len(names):
            msg = "tools.name 必须在请求内唯一"
            raise ValueError(msg)

        # context 三段的总字符数与 48 KiB 总上限（按正文/摘要计数）。
        facts_total = sum(len(fact) for fact in self.context.facts)
        if facts_total > MAX_FACTS_TOTAL_CHARS:
            msg = f"context.facts 总字符数超过 {MAX_FACTS_TOTAL_CHARS}"
            raise ValueError(msg)
        messages_total = sum(len(message.content) for message in self.context.messages)
        if messages_total > MAX_MESSAGES_TOTAL_CHARS:
            msg = f"context.messages 总字符数超过 {MAX_MESSAGES_TOTAL_CHARS}"
            raise ValueError(msg)
        observations_total = sum(len(item.summary) for item in self.context.observations)
        if observations_total > MAX_OBSERVATIONS_TOTAL_CHARS:
            msg = f"context.observations 总字符数超过 {MAX_OBSERVATIONS_TOTAL_CHARS}"
            raise ValueError(msg)
        context_total = facts_total + messages_total + observations_total
        if context_total > MAX_CONTEXT_TOTAL_CHARS:
            msg = f"context 总字符数超过 {MAX_CONTEXT_TOTAL_CHARS}（48 KiB）"
            raise ValueError(msg)
        return self


class ToolCall(ContractModel):
    """模型建议的一次工具调用；``arguments`` 必须是 JSON object（必填）。"""

    name: ToolName
    arguments: dict[str, Any] = Field(
        description="工具入参对象；非 object 一律契约违规。",
    )


class DecideUsage(ContractModel):
    """模型用量；``source=UNKNOWN`` 时 token 保持 ``None``，不填 0。"""

    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    source: DecideUsageSource = DecideUsageSource.UNKNOWN

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_tokens(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("inputTokens", "input_tokens", "outputTokens", "output_tokens"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数或 null"
                    raise ValueError(msg)
        return data


class AgentDecideResponse(ContractModel):
    """``POST /v1/agent/decide`` 响应（不含任何 token 流字段）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    decision: DecideDecision
    tool_call: ToolCall | None = Field(
        default=None,
        description="仅 CALL_TOOL 允许且必须出现；其它决策必须缺省或 null。",
    )
    usage: DecideUsage = Field(default_factory=DecideUsage)
    prompt_version: str = Field(
        min_length=1,
        max_length=32,
        pattern=PROMPT_VERSION_PATTERN,
        description="本次 decide 主 Prompt（decide.system）的 manifest 版本。",
    )
    model_alias: str = Field(min_length=1, max_length=128)
    latency_ms: int | None = Field(
        default=None,
        ge=0,
        description="Java 网关返回的模型调用耗时；缺失为 null。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_latency(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("latencyMs", "latency_ms"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数或 null"
                    raise ValueError(msg)
        return data

    @model_validator(mode="after")
    def _check_decision_tool_call(self) -> Self:
        if self.decision is DecideDecision.CALL_TOOL:
            if self.tool_call is None:
                msg = "CALL_TOOL 必须带 toolCall"
                raise ValueError(msg)
        elif self.tool_call is not None:
            msg = "非 CALL_TOOL 决策不得带 toolCall"
            raise ValueError(msg)
        return self


__all__ = [
    "MAX_ATTEMPT",
    "MAX_CONTEXT_ITEMS",
    "MAX_CONTEXT_TOTAL_CHARS",
    "MAX_DECIDE_DEADLINE_MS",
    "MAX_FACTS_TOTAL_CHARS",
    "MAX_FACT_CHARS",
    "MAX_GOAL_CHARS",
    "MAX_INPUT_SCHEMA_BYTES",
    "MAX_MESSAGES_TOTAL_CHARS",
    "MAX_MESSAGE_CHARS",
    "MAX_OBSERVATIONS_TOTAL_CHARS",
    "MAX_OBSERVATION_SUMMARY_CHARS",
    "MAX_OBSERVATION_TOOL_CHARS",
    "MAX_RUN_ID_CHARS",
    "MAX_TOOLS",
    "MAX_TOOL_DESCRIPTION_CHARS",
    "MAX_TOOL_NAME_CHARS",
    "MAX_TURN_NO",
    "MIN_ATTEMPT",
    "MIN_TOOLS",
    "MIN_TURN_NO",
    "SCENARIO_CODE_PATTERN",
    "AgentDecideRequest",
    "AgentDecideResponse",
    "DecideBudget",
    "DecideContext",
    "DecideDecision",
    "DecideMessage",
    "DecideObservation",
    "DecidePolicy",
    "DecideScenario",
    "DecideTool",
    "DecideUsage",
    "DecideUsageSource",
    "MessageRole",
    "ScenarioCategory",
    "ToolCall",
    "ToolRisk",
]
