"""解析并校验模型返回的单轮决策 JSON（设计 §7.5 / W4 冻结文档 §2）。

本模块是 **eval replay 打分器与 HTTP 端点共用的唯一校验核心**：``ai-eval decide --scorer
replay`` 直接在进程内调用 :func:`parse_decision`，不经过 HTTP，从而保证"离线复算"与
"在线行为"用的是同一段代码（冻结文档 §5）。

规则：

- 允许 Markdown 围栏包裹的单个 JSON object；
- ``decision`` 只允许 ``CALL_TOOL`` / ``FINAL`` / ``ASK_USER`` / ``NO_OP``；
- ``CALL_TOOL`` 必须带 ``toolCall.name``（∈ 本次 tools）与 ``arguments``（JSON object）；
- 其它决策不得带 ``toolCall``（缺省或 ``null`` 均合法）；
- 顶层多余键（如 ``reasoning``）被忽略；``toolCall`` 内部多余键严格拒绝（契约模型
  ``extra=forbid``）。

错误统一抛 :class:`DecisionValidationError`，由调用方决定 HTTP 映射（422 SCHEMA_VIOLATION）
与指标；错误文本只含固定短语，**绝不回显模型原文**。
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.contract.agent_decide import DecideDecision, DecideTool, ToolCall

#: 对外可见的失败原因（固定短语）。
REASON_EMPTY_OUTPUT: Final[str] = "模型输出为空"
REASON_NOT_JSON: Final[str] = "模型输出不是合法 JSON"
REASON_NOT_OBJECT: Final[str] = "模型输出不是单个 JSON 对象"
REASON_MISSING_DECISION: Final[str] = "缺少 decision 字段"
REASON_UNKNOWN_DECISION: Final[str] = "decision 不在允许的四个取值内"
REASON_MISSING_TOOL_CALL: Final[str] = "CALL_TOOL 缺少 toolCall"
REASON_INVALID_TOOL_CALL: Final[str] = "toolCall 形状非法"
REASON_UNKNOWN_TOOL: Final[str] = "toolCall.name 不在本次下发的 tools 内"
REASON_UNEXPECTED_TOOL_CALL: Final[str] = "非 CALL_TOOL 决策不得带 toolCall"


class DecisionValidationError(Exception):
    """模型决策输出不合法；携带可安全对外暴露的 reason 与分类标记。

    - ``json_parsed``：是否解析出了 JSON object（用于区分"非法 JSON"与"契约违规"）；
    - ``invalid_tool_call``：是否属于 CALL_TOOL 相关的契约违规（缺 toolCall / 形状错 /
      工具名不在 tools 内），端点据此记 ``invalid_tool_call_total``；
    - ``invalid_tool_name``：模型给出的越界工具名（仅"不在 tools 内"时有值，用于 eval 的
      ``invalidToolCalls`` 计数）；绝不进入错误信封。
    """

    def __init__(
        self,
        reason: str,
        *,
        json_parsed: bool,
        invalid_tool_call: bool = False,
        invalid_tool_name: str | None = None,
    ) -> None:
        super().__init__(reason)
        self.reason = reason
        self.json_parsed = json_parsed
        self.invalid_tool_call = invalid_tool_call
        self.invalid_tool_name = invalid_tool_name


@dataclass(frozen=True)
class DecisionResult:
    """一次合法的决策：``tool_call`` 仅在 ``CALL_TOOL`` 时非空。"""

    decision: DecideDecision
    tool_call: ToolCall | None


def parse_decision(content: str, tools: Sequence[DecideTool]) -> DecisionResult:
    """解析模型输出；不合法时抛 :class:`DecisionValidationError`。"""

    cleaned = _strip_code_fence(content).strip()
    if not cleaned:
        raise DecisionValidationError(REASON_EMPTY_OUTPUT, json_parsed=False)
    try:
        root: object = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise DecisionValidationError(REASON_NOT_JSON, json_parsed=False) from exc
    if not isinstance(root, dict):
        raise DecisionValidationError(REASON_NOT_OBJECT, json_parsed=False)

    raw_decision = root.get("decision")
    if raw_decision is None:
        raise DecisionValidationError(REASON_MISSING_DECISION, json_parsed=True)
    if not isinstance(raw_decision, str):
        raise DecisionValidationError(REASON_UNKNOWN_DECISION, json_parsed=True)
    try:
        decision = DecideDecision(raw_decision)
    except ValueError as exc:
        raise DecisionValidationError(REASON_UNKNOWN_DECISION, json_parsed=True) from exc

    raw_tool_call = root.get("toolCall")
    if decision is not DecideDecision.CALL_TOOL:
        if raw_tool_call is not None:
            raise DecisionValidationError(REASON_UNEXPECTED_TOOL_CALL, json_parsed=True)
        return DecisionResult(decision=decision, tool_call=None)

    if raw_tool_call is None:
        raise DecisionValidationError(
            REASON_MISSING_TOOL_CALL, json_parsed=True, invalid_tool_call=True
        )
    if not isinstance(raw_tool_call, dict):
        raise DecisionValidationError(
            REASON_INVALID_TOOL_CALL, json_parsed=True, invalid_tool_call=True
        )

    raw_name = raw_tool_call.get("name")
    if not isinstance(raw_name, str) or not raw_name:
        raise DecisionValidationError(
            REASON_INVALID_TOOL_CALL, json_parsed=True, invalid_tool_call=True
        )
    if raw_name not in {tool.name for tool in tools}:
        raise DecisionValidationError(
            REASON_UNKNOWN_TOOL,
            json_parsed=True,
            invalid_tool_call=True,
            invalid_tool_name=raw_name,
        )
    if not isinstance(raw_tool_call.get("arguments"), dict):
        raise DecisionValidationError(
            REASON_INVALID_TOOL_CALL, json_parsed=True, invalid_tool_call=True
        )

    try:
        tool_call = ToolCall.model_validate(raw_tool_call)
    except ValidationError as exc:
        raise DecisionValidationError(
            REASON_INVALID_TOOL_CALL, json_parsed=True, invalid_tool_call=True
        ) from exc
    return DecisionResult(decision=decision, tool_call=tool_call)


def _strip_code_fence(content: str) -> str:
    """按 rerank 解析器的顺序去掉 ```json / ``` 围栏，返回去空白后的文本。"""

    cleaned = content.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[len("```json") :]
    elif cleaned.startswith("```"):
        cleaned = cleaned[len("```") :]
    if cleaned.endswith("```"):
        cleaned = cleaned[: -len("```")]
    return cleaned.strip()


__all__ = [
    "REASON_EMPTY_OUTPUT",
    "REASON_INVALID_TOOL_CALL",
    "REASON_MISSING_DECISION",
    "REASON_MISSING_TOOL_CALL",
    "REASON_NOT_JSON",
    "REASON_NOT_OBJECT",
    "REASON_UNEXPECTED_TOOL_CALL",
    "REASON_UNKNOWN_DECISION",
    "REASON_UNKNOWN_TOOL",
    "DecisionResult",
    "DecisionValidationError",
    "parse_decision",
]
