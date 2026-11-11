"""Query plan 端点契约（设计 §7.4，W4 施工冻结文档 §1 的落地版）。

请求固定为 camelCase：

```json
{"schemaVersion":1,"query":"…","locale":"zh-CN","historySummary":"…",
 "documentIds":["doc-1","doc-2"],"deadlineMs":8000}
```

响应固定为：

```json
{"schemaVersion":1,"rewrittenQuery":"…","subQueries":["…"],
 "filters":{"documentIds":["doc-1"],"docTypes":["SPEC"],
            "dateRange":{"from":"2026-01-01","to":"2026-06-30"}},
 "confidence":0.78}
```

边界（冻结表原文）：

- ``query`` 1–4000 且去首尾空白非空；``locale`` 2–35；
- ``historySummary`` 0–8000，可缺省；
- ``documentIds`` 0–200，每项 1–128、请求内唯一；
- ``deadlineMs`` 1–30000，默认 8000（端到端硬 deadline）；
- ``rewrittenQuery`` 1–4000；``subQueries`` 0–8、每项 1–500、请求内不重复；
- ``filters`` **只允许** ``documentIds`` / ``docTypes`` / ``dateRange`` 三个键（``extra=forbid``）；
  ``docTypes`` 0–10、每项 1–64（大写白名单由 Java 侧再校验，Python 只做形状与长度）；
  ``dateRange`` 为 ``{from,to}``，ISO-8601 日期（``YYYY-MM-DD``），两端可空，``from<=to``；
- ``confidence ∈ [0,1]``。

``confidence < 0.5`` 的语义由 **Java** 决定（使用原查询）；Python 只如实返回，不在本侧做
任何降级或改写。``filters.documentIds`` 必须是请求 ``documentIds`` 的子集：模型编造的 id 由
解析层丢弃并计数，不报错（见 ``cognition/query/parser.py``）。
"""

from __future__ import annotations

import datetime as dt
import re
from typing import Annotated, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, CommonRequest, ContractModel

#: 查询长度上限（与 rerank 的 query 同口径）。
MAX_QUERY_CHARS: Final[int] = 4000

#: locale 形状：``zh-CN``、``en``、``pt-BR`` 等（与冻结表 §1 逐字一致）。
LOCALE_PATTERN: Final[str] = r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$"
MIN_LOCALE_CHARS: Final[int] = 2
MAX_LOCALE_CHARS: Final[int] = 35

#: 历史摘要上限与默认可缺省值。
MAX_HISTORY_SUMMARY_CHARS: Final[int] = 8000

#: 文档 id 数量与单条长度上限。
MAX_DOCUMENT_IDS: Final[int] = 200
MAX_DOCUMENT_ID_CHARS: Final[int] = 128

#: query plan 专用 deadline 默认值与区间（毫秒）。
DEFAULT_QUERY_PLAN_DEADLINE_MS: Final[int] = 8000
MAX_QUERY_PLAN_DEADLINE_MS: Final[int] = 30_000

#: 响应边界：改写后查询、子查询、文档类型。
MAX_REWRITTEN_QUERY_CHARS: Final[int] = 4000
MAX_SUB_QUERIES: Final[int] = 8
MAX_SUB_QUERY_CHARS: Final[int] = 500
MAX_DOC_TYPES: Final[int] = 10
MAX_DOC_TYPE_CHARS: Final[int] = 64

#: ``dateRange`` 的日期形状：只接受扩展格式 ``YYYY-MM-DD``（拒绝 ``20260101`` 等简写）。
ISO_DATE_PATTERN: Final[str] = r"^\d{4}-\d{2}-\d{2}$"

DocumentId = Annotated[str, Field(min_length=1, max_length=MAX_DOCUMENT_ID_CHARS)]
SubQuery = Annotated[str, Field(min_length=1, max_length=MAX_SUB_QUERY_CHARS)]
DocType = Annotated[str, Field(min_length=1, max_length=MAX_DOC_TYPE_CHARS)]

_ISO_DATE = re.compile(ISO_DATE_PATTERN)


def _validate_iso_date(value: str, field_name: str) -> str:
    """校验 ``YYYY-MM-DD``；形状对但日历上不存在（如 2026-02-30）同样拒绝。"""

    if _ISO_DATE.fullmatch(value) is None:
        msg = f"{field_name} 必须是 ISO-8601 日期（YYYY-MM-DD）"
        raise ValueError(msg)
    try:
        dt.date.fromisoformat(value)
    except ValueError as exc:
        msg = f"{field_name} 不是真实存在的日历日期"
        raise ValueError(msg) from exc
    return value


class QueryPlanRequest(CommonRequest):
    """``POST /v1/rag/plan`` 请求。

    ``deadline_ms`` 覆写通用请求的 10 分钟上限：query plan 只允许 1–30000ms，默认 8000ms。
    ``query`` 去首尾空白非空与 ``documentIds`` 唯一性放在模型内，保证直接构造模型与经由
    HTTP 两条路径得到同样的 422。
    """

    query: str = Field(
        min_length=1,
        max_length=MAX_QUERY_CHARS,
        description="用户原始查询；去首尾空白后必须非空。",
    )
    locale: str = Field(
        min_length=MIN_LOCALE_CHARS,
        max_length=MAX_LOCALE_CHARS,
        pattern=LOCALE_PATTERN,
        description="回复语言/地区，例如 zh-CN；Java 原样下发。",
    )
    history_summary: str = Field(
        default="",
        max_length=MAX_HISTORY_SUMMARY_CHARS,
        description="可缺省的历史摘要；不包含令牌与隐私字段。",
    )
    document_ids: tuple[DocumentId, ...] = Field(
        default=(),
        max_length=MAX_DOCUMENT_IDS,
        description="本次可见文档 id 白名单；请求内唯一，用于约束模型编造过滤条件。",
    )
    deadline_ms: int = Field(
        default=DEFAULT_QUERY_PLAN_DEADLINE_MS,
        ge=1,
        le=MAX_QUERY_PLAN_DEADLINE_MS,
        description="端到端硬 deadline（毫秒）；到点返回 DEADLINE_EXCEEDED，无半成品。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_schema_version(cls, data: object) -> object:
        """``True == 1`` 在 Python 里成立；JSON 的 ``true`` 不得冒充整数边界字段。"""

        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version", "deadlineMs", "deadline_ms"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data

    @field_validator("document_ids")
    @classmethod
    def _check_document_ids(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if len(set(values)) != len(values):
            msg = "documentIds 必须在请求内唯一"
            raise ValueError(msg)
        return values

    @model_validator(mode="after")
    def _check_query_not_blank(self) -> Self:
        if not self.query.strip():
            msg = "query 去首尾空白后不能为空"
            raise ValueError(msg)
        return self


class QueryPlanDateRange(ContractModel):
    """``filters.dateRange``：ISO-8601 日期区间，两端可空；``from<=to``。"""

    from_: str | None = Field(
        default=None,
        alias="from",
        description="起始日期（含），YYYY-MM-DD；null 表示不限。",
    )
    to: str | None = Field(
        default=None,
        description="结束日期（含），YYYY-MM-DD；null 表示不限。",
    )

    @field_validator("from_")
    @classmethod
    def _check_from(cls, value: str | None) -> str | None:
        return None if value is None else _validate_iso_date(value, "dateRange.from")

    @field_validator("to")
    @classmethod
    def _check_to(cls, value: str | None) -> str | None:
        return None if value is None else _validate_iso_date(value, "dateRange.to")

    @model_validator(mode="after")
    def _check_order(self) -> Self:
        if self.from_ is not None and self.to is not None and self.from_ > self.to:
            msg = "dateRange.from 不得晚于 dateRange.to"
            raise ValueError(msg)
        return self


class QueryPlanFilters(ContractModel):
    """``filters`` 只允许三个键；其它键由 ``extra=forbid`` 直接拒绝。"""

    document_ids: tuple[DocumentId, ...] = Field(
        default=(),
        max_length=MAX_DOCUMENT_IDS,
        description="文档 id 过滤；必须是请求 documentIds 的子集（解析层强制）。",
    )
    doc_types: tuple[DocType, ...] = Field(
        default=(),
        max_length=MAX_DOC_TYPES,
        description="文档类型过滤；大写白名单由 Java 侧再校验。",
    )
    date_range: QueryPlanDateRange | None = Field(
        default=None,
        description="时间范围过滤；两端可空。",
    )


class QueryPlanResponse(ContractModel):
    """``POST /v1/rag/plan`` 响应。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    rewritten_query: str = Field(
        min_length=1,
        max_length=MAX_REWRITTEN_QUERY_CHARS,
        description="改写后的检索查询；不得为空。",
    )
    sub_queries: tuple[SubQuery, ...] = Field(
        default=(),
        max_length=MAX_SUB_QUERIES,
        description="拆分的子查询，0–8 条，请求内不重复。",
    )
    filters: QueryPlanFilters = Field(
        default_factory=QueryPlanFilters,
        description="结构化过滤条件；只允许 documentIds/docTypes/dateRange 三个键。",
    )
    confidence: float = Field(
        ge=0.0,
        le=1.0,
        allow_inf_nan=False,
        description="置信度；<0.5 时由 Java 决定使用原查询。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_numbers(cls, data: object) -> object:
        """``True``/``False`` 不得冒充 ``schemaVersion`` 或 ``confidence``（JSON 类型错）。"""

        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version", "confidence"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 不得为布尔值"
                    raise ValueError(msg)
        return data

    @model_validator(mode="after")
    def _check_unique_sub_queries(self) -> Self:
        if len(set(self.sub_queries)) != len(self.sub_queries):
            msg = "subQueries 在响应内不得重复"
            raise ValueError(msg)
        return self


__all__ = [
    "DEFAULT_QUERY_PLAN_DEADLINE_MS",
    "ISO_DATE_PATTERN",
    "LOCALE_PATTERN",
    "MAX_DOCUMENT_IDS",
    "MAX_DOCUMENT_ID_CHARS",
    "MAX_DOC_TYPES",
    "MAX_DOC_TYPE_CHARS",
    "MAX_HISTORY_SUMMARY_CHARS",
    "MAX_LOCALE_CHARS",
    "MAX_QUERY_CHARS",
    "MAX_QUERY_PLAN_DEADLINE_MS",
    "MAX_REWRITTEN_QUERY_CHARS",
    "MAX_SUB_QUERIES",
    "MAX_SUB_QUERY_CHARS",
    "MIN_LOCALE_CHARS",
    "QueryPlanDateRange",
    "QueryPlanFilters",
    "QueryPlanRequest",
    "QueryPlanResponse",
]
