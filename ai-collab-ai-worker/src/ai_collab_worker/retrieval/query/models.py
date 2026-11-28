"""查询理解模型类的逐行移植（P3 契约 §2.1 行 1-3，Q4=a）。

移植自 Java ``retrieval/query/`` 包：``StructuredQuery``、``QueryRouteDecision``、
``MetadataFilter``、``FilterOperator``、``RetrievalStrategy``、``QueryComplexity``、
``QueryIntentType``、``QueryUnderstandingResult``、``ConversationMessage``、
``ConversationTurn``。所有字符串语义（``isBlank``/``trim``/``strip``/``String.length``）
按 Java 口径复刻（``retrieval.javatext``），金标准夹具
``tests/fixtures/frozen_set/p3_*.v1.jsonl`` 逐字节钉住。

``MetadataFilter.matches`` 与 ``MetadataFilterParser.parseStructured*`` 在 QA 流程中无
消费者，不移植（Java 侧同样无 QA 之外的调用方，P3 删除）。
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Final

from ai_collab_worker.retrieval.javatext import is_blank, strip_ws, trim


class IllegalArgumentException(ValueError):  # noqa: N818 - 类名参与契约 reason 字符串
    """Java ``IllegalArgumentException`` 的同名移植。

    路由异常回退 reason 用 ``type(exc).__name__`` 拼接
    （``Fallback to HYBRID due to routing error: …``），类名必须逐字节等于 Java
    的简单类名，因此必须是真实类而不能是别名。
    """


class QueryComplexity(StrEnum):
    """Java ``QueryComplexity`` 的 4 个取值。"""

    FAST = "FAST"
    HYBRID = "HYBRID"
    COMPLEX = "COMPLEX"
    STRUCTURED = "STRUCTURED"


class RetrievalStrategy(StrEnum):
    """Java ``RetrievalStrategy`` 的 4 个取值（与 ``contract.knowledge_search`` 同名枚举对齐）。"""

    DENSE = "DENSE"
    HYBRID = "HYBRID"
    MULTI_QUERY_HYBRID = "MULTI_QUERY_HYBRID"
    DENSE_WITH_BOUNDED_FALLBACK = "DENSE_WITH_BOUNDED_FALLBACK"


class QueryIntentType(StrEnum):
    """Java ``QueryIntentType``：多轮对话意图三分类。"""

    STANDALONE = "STANDALONE"
    FOLLOW_UP = "FOLLOW_UP"
    TOPIC_SWITCH = "TOPIC_SWITCH"


class FilterOperator(StrEnum):
    """Java ``FilterOperator``。"""

    EQ = "EQ"
    NEQ = "NEQ"
    CONTAINS = "CONTAINS"
    IN = "IN"
    GT = "GT"
    GTE = "GTE"
    LT = "LT"
    LTE = "LTE"


#: ``MetadataFilter.ALLOWED_FIELDS``（Java 侧白名单，逐字一致）。
ALLOWED_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "originalFilename",
        "displayName",
        "mimeType",
        "createdAt",
        "updatedAt",
        "status",
        "tags",
        "sectionHeading",
    }
)

#: 字段 → 支持的算子（Java ``FIELD_OPERATORS``）。
FIELD_OPERATORS: Final[dict[str, frozenset[FilterOperator]]] = {
    "originalFilename": frozenset(
        {FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN}
    ),
    "displayName": frozenset(
        {FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN}
    ),
    "mimeType": frozenset({FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN}),
    "createdAt": frozenset(
        {
            FilterOperator.EQ,
            FilterOperator.NEQ,
            FilterOperator.GT,
            FilterOperator.GTE,
            FilterOperator.LT,
            FilterOperator.LTE,
        }
    ),
    "updatedAt": frozenset(
        {
            FilterOperator.EQ,
            FilterOperator.NEQ,
            FilterOperator.GT,
            FilterOperator.GTE,
            FilterOperator.LT,
            FilterOperator.LTE,
        }
    ),
    "status": frozenset({FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN}),
    "tags": frozenset({FilterOperator.EQ, FilterOperator.CONTAINS, FilterOperator.IN}),
    "sectionHeading": frozenset(
        {FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN}
    ),
}


@dataclass(frozen=True, slots=True)
class MetadataFilter:
    """受限的结构化元数据过滤（Java ``MetadataFilter`` record）。

    构造期校验与 Java 完全一致：字段白名单、算子支持集、非空字符串值、禁止 NUL、
    ``IN`` 必须非空集合。违反时抛 :data:`IllegalArgumentException`——路由层据此走
    "回退 HYBRID" 分支。
    """

    field: str
    operator: FilterOperator
    value: Any

    def __post_init__(self) -> None:
        if self.field is None or is_blank(self.field):
            msg = "Metadata filter field cannot be null or empty"
            raise IllegalArgumentException(msg)
        object.__setattr__(self, "field", trim(self.field))
        if self.field not in ALLOWED_FIELDS:
            msg = (
                f"Illegal metadata filter field: '{self.field}'. "
                f"Whitelisted fields are: {sorted(ALLOWED_FIELDS)}"
            )
            raise IllegalArgumentException(msg)
        if self.operator is None:
            msg = f"Metadata filter operator cannot be null for field '{self.field}'"
            raise IllegalArgumentException(msg)
        supported = FIELD_OPERATORS.get(self.field)
        if supported is None or self.operator not in supported:
            msg = (
                f"Operator '{self.operator.value}' is not supported for field '{self.field}'. "
                f"Supported: {sorted(op.value for op in supported) if supported else 'None'}"
            )
            raise IllegalArgumentException(msg)
        if self.value is None:
            msg = f"Metadata filter value cannot be null for field '{self.field}'"
            raise IllegalArgumentException(msg)
        if isinstance(self.value, str):
            if is_blank(self.value):
                msg = f"Metadata filter string value cannot be blank for field '{self.field}'"
                raise IllegalArgumentException(msg)
            if "\0" in self.value:
                msg = "Metadata filter string value contains null byte"
                raise IllegalArgumentException(msg)
        if (
            self.operator is FilterOperator.IN
            and isinstance(self.value, (list, tuple, set, frozenset))
            and len(self.value) == 0
        ):
            msg = "Filter value collection cannot be empty for operator IN"
            raise IllegalArgumentException(msg)

    # ---- Java 风格工厂（路由移植使用） --------------------------------------

    @staticmethod
    def eq(field: str, value: Any) -> MetadataFilter:
        return MetadataFilter(field, FilterOperator.EQ, value)

    @staticmethod
    def contains(field: str, value: Any) -> MetadataFilter:
        return MetadataFilter(field, FilterOperator.CONTAINS, value)

    @staticmethod
    def gte(field: str, value: Any) -> MetadataFilter:
        return MetadataFilter(field, FilterOperator.GTE, value)

    @staticmethod
    def lte(field: str, value: Any) -> MetadataFilter:
        return MetadataFilter(field, FilterOperator.LTE, value)

    def java_to_string(self) -> str:
        """Java record ``toString`` 逐字节复刻（进路由 reason 字符串，被金标准钉住）。"""

        return (
            f"MetadataFilter[field={self.field}, "
            f"operator={self.operator.value}, value={_java_value_string(self.value)}]"
        )


def _java_value_string(value: Any) -> str:
    """Java ``String.valueOf`` 的列表/标量形态（List→``[a, b]``，字符串原样）。"""

    if isinstance(value, (list, tuple)):
        return "[" + ", ".join(_java_value_string(item) for item in value) + "]"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


@dataclass(frozen=True, slots=True)
class StructuredQuery:
    """清洗后的语义查询 + 结构化过滤（Java ``StructuredQuery`` record）。"""

    semantic_query: str
    filters: tuple[MetadataFilter, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "semantic_query", strip_ws(self.semantic_query or ""))
        object.__setattr__(self, "filters", tuple(self.filters or ()))

    @property
    def semanticQuery(self) -> str:  # noqa: N802 - 供移植代码按 Java 命名读取
        return self.semantic_query

    def has_filters(self) -> bool:
        return len(self.filters) > 0

    @staticmethod
    def of(semantic_query: str, filters: tuple[MetadataFilter, ...] = ()) -> StructuredQuery:
        return StructuredQuery(semantic_query, filters)


def default_strategy(complexity: QueryComplexity) -> RetrievalStrategy:
    """Java ``QueryRouteDecision.defaultStrategy``：复杂度 → 默认执行策略。"""

    return {
        QueryComplexity.FAST: RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK,
        QueryComplexity.STRUCTURED: RetrievalStrategy.HYBRID,
        QueryComplexity.COMPLEX: RetrievalStrategy.MULTI_QUERY_HYBRID,
        QueryComplexity.HYBRID: RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK,
    }[complexity]


@dataclass(frozen=True, slots=True)
class QueryRouteDecision:
    """路由决策（Java ``QueryRouteDecision`` record）。

    ``strategy`` 缺省按复杂度映射；``document_ids`` 去重保序（仅 COMPLEX 采纳计划时非空）。
    """

    complexity: QueryComplexity
    reason: str
    structured_query: StructuredQuery
    sub_queries: tuple[str, ...]
    strategy: RetrievalStrategy | None
    document_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "complexity", self.complexity or QueryComplexity.HYBRID)
        object.__setattr__(self, "reason", self.reason or "")
        object.__setattr__(
            self,
            "structured_query",
            self.structured_query or StructuredQuery("", ()),
        )
        object.__setattr__(self, "sub_queries", tuple(self.sub_queries or ()))
        object.__setattr__(
            self,
            "strategy",
            self.strategy or default_strategy(self.complexity),
        )
        object.__setattr__(self, "document_ids", tuple(dict.fromkeys(self.document_ids or ())))

    @property
    def structuredQuery(self) -> StructuredQuery:  # noqa: N802
        return self.structured_query

    @property
    def subQueries(self) -> tuple[str, ...]:  # noqa: N802
        return self.sub_queries

    @property
    def documentIds(self) -> tuple[str, ...]:  # noqa: N802
        return self.document_ids

    # ---- Java 静态工厂（逐字节一致） ----------------------------------------

    @staticmethod
    def fast(reason: str, query: StructuredQuery) -> QueryRouteDecision:
        return QueryRouteDecision(
            QueryComplexity.FAST,
            reason,
            query,
            (query.semantic_query,),
            RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK,
        )

    @staticmethod
    def hybrid(reason: str, query: StructuredQuery) -> QueryRouteDecision:
        return QueryRouteDecision(
            QueryComplexity.HYBRID, reason, query, (query.semantic_query,), RetrievalStrategy.HYBRID
        )

    @staticmethod
    def hybrid_with_strategy(
        reason: str, query: StructuredQuery, strategy: RetrievalStrategy
    ) -> QueryRouteDecision:
        return QueryRouteDecision(
            QueryComplexity.HYBRID, reason, query, (query.semantic_query,), strategy
        )

    @staticmethod
    def structured(reason: str, query: StructuredQuery) -> QueryRouteDecision:
        return QueryRouteDecision(
            QueryComplexity.STRUCTURED,
            reason,
            query,
            (query.semantic_query,),
            RetrievalStrategy.HYBRID,
        )

    @staticmethod
    def complex(
        reason: str,
        query: StructuredQuery,
        sub_queries: tuple[str, ...],
        document_ids: tuple[str, ...] = (),
    ) -> QueryRouteDecision:
        return QueryRouteDecision(
            QueryComplexity.COMPLEX,
            reason,
            query,
            sub_queries,
            RetrievalStrategy.MULTI_QUERY_HYBRID,
            document_ids,
        )


@dataclass(frozen=True, slots=True)
class ConversationMessage:
    """会话历史消息（Java ``ConversationMessage`` record）。"""

    role: str
    content: str
    created_at: dt.datetime | None = None
    insufficient_evidence: bool = False

    def is_user(self) -> bool:
        return self.role.upper() == "USER"

    def is_assistant(self) -> bool:
        return self.role.upper() == "ASSISTANT"

    @property
    def createdAt(self) -> dt.datetime | None:  # noqa: N802
        return self.created_at


@dataclass(frozen=True, slots=True)
class ConversationTurn:
    """完整对话轮次（Java ``ConversationTurn`` record）。"""

    turn_index: int
    user_question: str | None
    assistant_answer: str | None
    timestamp: dt.datetime | None
    insufficient_evidence: bool

    def has_assistant_answer(self) -> bool:
        return self.assistant_answer is not None and not is_blank(self.assistant_answer)


@dataclass(frozen=True, slots=True)
class QueryUnderstandingResult:
    """多轮理解结果（Java ``QueryUnderstandingResult`` record）。"""

    original_question: str
    rewritten_query: str
    intent_type: QueryIntentType
    context_prompt: str
    managed_turns: tuple[ConversationTurn, ...] = field(default=())

    def __post_init__(self) -> None:
        object.__setattr__(self, "original_question", self.original_question or "")
        object.__setattr__(self, "rewritten_query", self.rewritten_query or self.original_question)
        object.__setattr__(self, "intent_type", self.intent_type or QueryIntentType.STANDALONE)
        object.__setattr__(self, "context_prompt", self.context_prompt or "")
        object.__setattr__(self, "managed_turns", tuple(self.managed_turns or ()))


@dataclass(frozen=True, slots=True)
class QueryPlanDateRange:
    """``/v1/rag/plan`` 计划结果的日期区间（Java ``QueryPlanResponse.DateRange``）。"""

    from_value: str | None = None
    to_value: str | None = None

    @property
    def from_(self) -> str | None:
        return self.from_value


@dataclass(frozen=True, slots=True)
class QueryPlanFilters:
    """``/v1/rag/plan`` 计划结果的过滤集合（Java ``QueryPlanResponse.Filters``）。"""

    document_ids: tuple[str, ...] = ()
    doc_types: tuple[str, ...] = ()
    date_range: QueryPlanDateRange = QueryPlanDateRange()


@dataclass(frozen=True, slots=True)
class QueryPlanOutcome:
    """路由 COMPLEX 分支可选采纳的计划结果（进程内调用 ``cognition.query`` 的产物）。"""

    rewritten_query: str
    sub_queries: tuple[str, ...]
    filters: QueryPlanFilters
    confidence: float


__all__ = [
    "ALLOWED_FIELDS",
    "FIELD_OPERATORS",
    "ConversationMessage",
    "ConversationTurn",
    "FilterOperator",
    "IllegalArgumentException",
    "MetadataFilter",
    "QueryComplexity",
    "QueryIntentType",
    "QueryPlanDateRange",
    "QueryPlanFilters",
    "QueryPlanOutcome",
    "QueryRouteDecision",
    "QueryUnderstandingResult",
    "RetrievalStrategy",
    "StructuredQuery",
    "default_strategy",
]
