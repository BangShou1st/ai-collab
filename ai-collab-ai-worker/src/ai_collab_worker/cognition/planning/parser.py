"""解析模型返回的 Planning 草稿 JSON（W5 冻结文档 §1 第 9 条）。

本模块是 **HTTP 端点与 ``ai-eval planning`` replay 打分器共用的唯一解析核心**：离线评测直接
在进程内调用 :func:`parse_draft`，不经过 HTTP，从而保证"离线复算"与"在线行为"是同一段代码。

规则：

- 允许 Markdown 围栏包裹的单个 JSON object；
- 顶层只允许 ``plan`` 与 ``warnings``（``extra=forbid``）：字段缺失/多余/类型错一律非法；
- 嵌套边界（title ≤100、objective ≤500、description ≤2000、priority 白名单、
  estimatedHours 0.5–200、日期形状）由 ``contract/planning.py`` 的模型保证；
- 跨条目规则（数量/tempKey/引用/依赖环/日期区间/成员/来源）由
  ``cognition/planning/validator.py`` 保证。

错误统一抛 :class:`DraftValidationError`，``reason`` 是固定短语，**绝不回显模型原文**。
"""

from __future__ import annotations

import json
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.cognition.planning.validator import DraftValidationError
from ai_collab_worker.contract.planning import PlanningDraftOutput

#: 对外可见的固定失败原因。
REASON_EMPTY_OUTPUT: Final[str] = "模型输出为空"
REASON_NOT_JSON: Final[str] = "模型输出不是合法 JSON"
REASON_NOT_OBJECT: Final[str] = "模型输出不是单个 JSON 对象"
REASON_MISSING_PLAN: Final[str] = "缺少 plan 字段"
REASON_SCHEMA_INVALID: Final[str] = "草稿字段缺失、多余或类型/范围非法"


def parse_draft(content: str) -> PlanningDraftOutput:
    """解析模型输出；不合法时抛 :class:`DraftValidationError`。"""

    cleaned = _strip_code_fence(content).strip()
    if not cleaned:
        raise DraftValidationError(REASON_EMPTY_OUTPUT)
    try:
        root: object = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise DraftValidationError(REASON_NOT_JSON) from exc
    if not isinstance(root, dict):
        raise DraftValidationError(REASON_NOT_OBJECT)
    if "plan" not in root:
        raise DraftValidationError(REASON_MISSING_PLAN)
    try:
        return PlanningDraftOutput.model_validate(root)
    except ValidationError as exc:
        raise DraftValidationError(REASON_SCHEMA_INVALID) from exc


def _strip_code_fence(content: str) -> str:
    """按 decide/query 解析器的顺序去掉 ```json / ``` 围栏，返回去空白后的文本。"""

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
    "REASON_MISSING_PLAN",
    "REASON_NOT_JSON",
    "REASON_NOT_OBJECT",
    "REASON_SCHEMA_INVALID",
    "DraftValidationError",
    "parse_draft",
]
