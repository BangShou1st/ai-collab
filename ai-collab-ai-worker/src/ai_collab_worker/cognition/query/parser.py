"""解析模型返回的 query plan JSON（设计 §7.4 / W4 冻结文档 §1）。

兼容 Markdown 围栏包裹的**单个 JSON object**（与 rerank 解析器的剥围栏顺序一致）。字段缺失、
类型错误、越界、``filters`` 出现三个白名单以外的键，统一 ``SCHEMA_VIOLATION``(422)；
错误信封只放固定短语，**绝不回显模型原文**（可能含用户查询或注入内容）。

``filters.documentIds`` 的"模型编造 id" 是**可恢复**的：不在请求 ``documentIds`` 白名单内的
id 被丢弃并作为 ``unknown_document_ids`` 返回，由端点记
``ai_worker_query_plan_unknown_document_ids_total``，不报错（冻结文档 §1）。

顶层多余键（例如模型自作主张加的 ``reasoning``）被忽略；``filters`` 内部的多余键则严格拒绝，
因为冻结契约把"只允许三个键"写成了硬约束。
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.query_plan import QueryPlanResponse

#: 对外可见的失败原因（固定短语，不含模型输出）。
REASON_EMPTY_OUTPUT: Final[str] = "模型输出为空"
REASON_NOT_JSON: Final[str] = "模型输出不是合法 JSON"
REASON_NOT_OBJECT: Final[str] = "模型输出不是单个 JSON 对象"
REASON_INVALID: Final[str] = "模型输出字段不符合契约"

#: 响应必填键（冻结契约的四个字段；顶层多余键忽略，缺任何一个都 422）。
_REQUIRED_KEYS: Final[tuple[str, ...]] = (
    "rewrittenQuery",
    "subQueries",
    "filters",
    "confidence",
)

#: ``filters`` 允许的三个键（冻结表原文）。
_FILTER_KEYS: Final[tuple[str, ...]] = ("documentIds", "docTypes", "dateRange")


@dataclass(frozen=True)
class ParsedQueryPlan:
    """解析结果：``response`` 是已过滤的契约响应，``unknown_document_ids`` 是被丢弃的编造 id。"""

    response: QueryPlanResponse
    unknown_document_ids: tuple[str, ...]


def parse_query_plan(content: str, *, allowed_document_ids: Sequence[str] = ()) -> ParsedQueryPlan:
    """把模型输出解析成 :class:`QueryPlanResponse`；失败抛 ``SCHEMA_VIOLATION``。"""

    cleaned = _strip_code_fence(content).strip()
    if not cleaned:
        raise _schema_violation(REASON_EMPTY_OUTPUT)
    try:
        root: object = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise _schema_violation(REASON_NOT_JSON) from exc
    if not isinstance(root, dict):
        raise _schema_violation(REASON_NOT_OBJECT)

    if any(key not in root for key in _REQUIRED_KEYS):
        raise _schema_violation(REASON_INVALID)

    filters, unknown_ids = _filter_document_ids(root.get("filters"), allowed_document_ids)
    payload = {
        "schemaVersion": root.get("schemaVersion", 1),
        "rewrittenQuery": root.get("rewrittenQuery"),
        "subQueries": root.get("subQueries"),
        "filters": filters,
        "confidence": root.get("confidence"),
    }
    try:
        response = QueryPlanResponse.model_validate(payload)
    except ValidationError as exc:
        raise _schema_violation(REASON_INVALID) from exc
    return ParsedQueryPlan(response=response, unknown_document_ids=unknown_ids)


def _filter_document_ids(
    raw_filters: object, allowed_document_ids: Sequence[str]
) -> tuple[dict[str, object], tuple[str, ...]]:
    """校验 ``filters`` 形状并过滤 ``documentIds``；返回 (filters payload, 未知 id)。"""

    if not isinstance(raw_filters, Mapping):
        raise _schema_violation(REASON_INVALID)
    extra_keys = sorted(str(key) for key in raw_filters if key not in _FILTER_KEYS)
    if extra_keys:
        raise _schema_violation(REASON_INVALID)

    raw_ids = raw_filters.get("documentIds", [])
    if not isinstance(raw_ids, list):
        raise _schema_violation(REASON_INVALID)

    allowed = set(allowed_document_ids)
    kept: list[str] = []
    unknown: list[str] = []
    for raw_id in raw_ids:
        if not isinstance(raw_id, str) or not raw_id:
            raise _schema_violation(REASON_INVALID)
        if raw_id in allowed:
            if raw_id not in kept:
                kept.append(raw_id)
        elif raw_id not in unknown:
            unknown.append(raw_id)

    filters: dict[str, object] = {"documentIds": kept}
    if "docTypes" in raw_filters:
        filters["docTypes"] = raw_filters["docTypes"]
    if "dateRange" in raw_filters:
        filters["dateRange"] = raw_filters["dateRange"]
    return filters, tuple(unknown)


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


def _schema_violation(reason: str) -> AppError:
    """固定的 422 错误；``details`` 只含 reason 短语。"""

    return AppError(
        ErrorCode.SCHEMA_VIOLATION,
        "模型查询规划输出不符合契约",
        status_code=422,
        details={"reason": reason},
    )


__all__ = [
    "REASON_EMPTY_OUTPUT",
    "REASON_INVALID",
    "REASON_NOT_JSON",
    "REASON_NOT_OBJECT",
    "ParsedQueryPlan",
    "parse_query_plan",
]
