"""``QueryRouter`` 的逐行移植（P3 契约 §2.1 行 1、§2.4，Q4=a + Q3=a）。

路由优先级为契约：STRUCTURED → COMPLEX → EXACT_IDENTIFIER → FAST → 默认
``DENSE_WITH_BOUNDED_FALLBACK``；路由异常回退 HYBRID 且日志掩码（只留前 6 字符）。

与 Java 的唯一结构差异：COMPLEX 分支的可选 ``/v1/rag/plan`` 增强（原
``QueryPlanGatewayClient`` HTTP 包装）改为**进程内调用** ``cognition.query``
（Q3=a）。Java 现状五重前置检查等价改写：

1. ``query.plan.enabled=false`` → 本地规则（OUTCOME=DISABLED）；
2. 客户端缺失 → 本地规则（OUTCOME=UNAVAILABLE/CLIENT_MISSING，进程内化后由
   ``plan_enhancer is None`` 表达）；
3. 身份缺失（uid/pid）→ 本地规则（fail closed）；
4. 计划调用异常 → 本地规则；
5. ``confidence < 0.5`` → 本地规则。

前两类随进程内化消失为构造参数；后三类语义与现状一致。采纳时合并改写查询、
子查询与过滤（``dateRange`` → ``createdAt`` 的 gte/lte），全部字符串逐字节一致。
"""

from __future__ import annotations

import logging
import re
import uuid
from collections.abc import Awaitable, Callable
from typing import Final

from ai_collab_worker.retrieval.javatext import is_blank, utf16_length
from ai_collab_worker.retrieval.query.decompose import decompose
from ai_collab_worker.retrieval.query.filters import parse as parse_filters
from ai_collab_worker.retrieval.query.models import (
    MetadataFilter,
    QueryPlanFilters,
    QueryPlanOutcome,
    QueryRouteDecision,
    RetrievalStrategy,
    StructuredQuery,
)

logger: Final[logging.Logger] = logging.getLogger(__name__)

FAST_MAX_CODE_POINTS: Final[int] = 20
MASKED_LOG_CHARS: Final[int] = 6

COMPARATIVE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r".*(?:和|与|跟|及|以及|vs|versus).*?(?:区别|不同点|异同|对比|优缺点|相比|优劣).*",
    re.IGNORECASE,
)

COMPOSITE_CONJUNCTION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r".*(?:并且|而且|同时|不仅.+?而且|既要.+?又要|一方面.+?另一方面).*"
)

FAST_DEFINITION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"^(?:什么是|定义|请问什么是|简述)?[ \t\n\x0b\f\r]*"
    r"[a-zA-Z0-9_\-\u4e00-\u9fa5]{1,12}[ \t\n\x0b\f\r]*"
    r"(?:是什么|的定义|的端口|的版本|是谁|全称|缩写)?\??\Z"
)

REASONING_DISQUALIFIERS: Final[re.Pattern[str]] = re.compile(
    r".*(?:为什么|怎么做|如何设计|实施步骤|工作原理|流程分析|详细阐述|优缺点|架构设计).*"
)

_B: Final[str] = r"(?<![a-zA-Z0-9_])"
_B_END: Final[str] = r"(?![a-zA-Z0-9_])"

#: 精确标识符结构特征（Java ``\b`` 为 ASCII 词界，显式环视复刻；``\d``/``\w`` 为 ASCII）。
EXACT_IDENTIFIER_PATTERN: Final[re.Pattern[str]] = re.compile(
    _B
    + r"[A-Z]{2,}(?:[-_][A-Z0-9]+)+"
    + _B_END
    + r"|"
    + _B
    + r"[0-9]+(?:\.[0-9]+){1,3}"
    + _B_END
    + r"|"
    + _B
    + r"[a-zA-Z0-9_\-]+\.[A-Za-z]{1,6}"
    + _B_END
    + r"|"
    + _B
    + r"[A-Z][A-Z0-9_]{2,}"
    + _B_END
)

#: COMPLEX 计划增强的注入点： ``(semantic_query, document_ids, user_id, project_id) ->
#: QueryPlanOutcome | None``（``None`` = 未采纳）。异常由本类捕获回退本地规则。
QueryPlanEnhancer = Callable[
    [str, tuple[str, ...], uuid.UUID | None, uuid.UUID | None],
    Awaitable[QueryPlanOutcome | None],
]


def contains_exact_identifier(query: str | None) -> bool:
    return (
        query is not None
        and not is_blank(query)
        and EXACT_IDENTIFIER_PATTERN.search(query) is not None
    )


def mask(text: str | None) -> str:
    """日志掩码：仅保留固定前缀长度，防止用户查询全文进入日志。"""

    if text is None or is_blank(text):
        return ""
    return text[: min(MASKED_LOG_CHARS, len(text))] + "***"


class QueryRouter:
    """启发式与规则路由（Java ``QueryRouter`` 的等价实现）。"""

    def __init__(
        self,
        filter_parser: Callable[[str], StructuredQuery] | None = None,
        decomposer: Callable[[str], list[str]] | None = None,
        plan_enhancer: QueryPlanEnhancer | None = None,
        *,
        plan_enabled: bool = False,
    ) -> None:
        self._parse = filter_parser or parse_filters
        self._decompose = decomposer or decompose
        self._plan_enhancer = plan_enhancer
        self._plan_enabled = plan_enabled

    async def route(
        self,
        raw_query: str | None,
        document_ids: tuple[str, ...] | list[str] = (),
        user_id: uuid.UUID | None = None,
        project_id: uuid.UUID | None = None,
    ) -> QueryRouteDecision:
        if raw_query is None or is_blank(raw_query):
            return QueryRouteDecision.hybrid(
                "Empty query defaulted to HYBRID",
                StructuredQuery("", ()),
            )

        try:
            # 1. 结构化元数据过滤抽取
            structured_query = self._parse(raw_query)
            if structured_query.has_filters():
                reason = (
                    "User explicitly expressed structured metadata filters: "
                    + _java_list_string(structured_query.filters)
                )
                return QueryRouteDecision.structured(reason, structured_query)

            semantic_query = structured_query.semantic_query

            # 2. 复杂查询（多问句 / 比较 / 多意图连词）
            if _is_complex_query(semantic_query):
                local_sub_queries = tuple(self._decompose(semantic_query))
                local_reason = _determine_complex_reason(semantic_query, local_sub_queries)
                return await self._route_complex_with_optional_query_plan(
                    semantic_query,
                    local_reason,
                    structured_query,
                    local_sub_queries,
                    tuple(document_ids or ()),
                    user_id,
                    project_id,
                )

            # 3. 精确标识符识别先于 FAST（如 "JWT" 不会被密集快路拦截）
            if contains_exact_identifier(semantic_query):
                return QueryRouteDecision.hybrid_with_strategy(
                    "Exact identifier structure detected (error code/version/file name); "
                    "lexical exact matching retained in HYBRID",
                    structured_query,
                    RetrievalStrategy.HYBRID,
                )

            # 4. FAST：短且单一意图的直接查询
            if _is_fast_query(semantic_query):
                reason = "Short direct single-intent lookup suitable for fast single-path retrieval"
                return QueryRouteDecision.fast(reason, structured_query)

            # 5. 默认：单一意图语义问题 → dense 优先 + 有界证据回退
            return QueryRouteDecision.hybrid_with_strategy(
                "Standard semantic question routed dense-first with bounded evidence fallback "
                "(lexical skipped unless assembled evidence is insufficient)",
                structured_query,
                RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK,
            )
        except Exception as exc:
            # 日志脱敏：不输出原始用户查询；异常消息可能内嵌输入片段，只记异常类型。
            logger.warning(
                "Query routing failed, safely falling back to HYBRID: "
                "queryMasked=%s queryLen=%d errorType=%s",
                mask(raw_query),
                utf16_length(raw_query),
                type(exc).__name__,
            )
            fallback_query = StructuredQuery(raw_query, ())
            return QueryRouteDecision.hybrid(
                "Fallback to HYBRID due to routing error: " + type(exc).__name__,
                fallback_query,
            )

    async def _route_complex_with_optional_query_plan(
        self,
        semantic_query: str,
        local_reason: str,
        structured_query: StructuredQuery,
        local_sub_queries: tuple[str, ...],
        document_ids: tuple[str, ...],
        user_id: uuid.UUID | None,
        project_id: uuid.UUID | None,
    ) -> QueryRouteDecision:
        local_decision = QueryRouteDecision.complex(
            local_reason, structured_query, local_sub_queries
        )
        if not self._plan_enabled:
            logger.debug("rag.query_plan.outcome outcome=DISABLED")
            return local_decision
        if self._plan_enhancer is None:
            logger.warning("rag.query_plan.outcome outcome=UNAVAILABLE reason=CLIENT_MISSING")
            return local_decision
        if user_id is None or project_id is None:
            logger.warning("rag.query_plan.outcome outcome=UNAVAILABLE reason=IDENTITY_MISSING")
            return local_decision

        try:
            plan = await self._plan_enhancer(semantic_query, document_ids, user_id, project_id)
        except Exception as failure:
            logger.warning(
                "rag.query_plan.outcome outcome=%s reason=%s",
                getattr(failure, "reason_name", "UNEXPECTED"),
                type(failure).__name__,
            )
            return local_decision

        if plan is None:
            # 进程内增强未产出计划（如端点不可用被 enhancer 内部归一）→ 回本地规则。
            logger.warning("rag.query_plan.outcome outcome=UNAVAILABLE reason=CLIENT_MISSING")
            return local_decision

        if plan.confidence < 0.5:
            logger.info(
                "rag.query_plan.outcome outcome=LOW_CONFIDENCE confidence=%s subQueryCount=%d",
                plan.confidence,
                len(plan.sub_queries),
            )
            return local_decision

        planned_sub_queries = plan.sub_queries if plan.sub_queries else local_sub_queries
        planned_document_ids = _valid_document_ids(plan.filters.document_ids)
        adopted_filters = _merge_plan_filters(structured_query.filters, plan.filters)
        adopted_query = plan.rewritten_query
        reason = local_reason + " | QueryPlan adopted: rewritten query and bounded filters"
        logger.info(
            "rag.query_plan.outcome outcome=ADOPTED confidence=%s subQueryCount=%d "
            "documentIdCount=%d docTypeCountIgnored=%d dateRangePresent=%s",
            plan.confidence,
            len(planned_sub_queries),
            len(planned_document_ids),
            len(plan.filters.doc_types),
            plan.filters.date_range.from_value is not None
            or plan.filters.date_range.to_value is not None,
        )
        return QueryRouteDecision.complex(
            reason,
            StructuredQuery(adopted_query, tuple(adopted_filters)),
            planned_sub_queries,
            planned_document_ids,
        )


def _is_complex_query(query: str | None) -> bool:
    if query is None or is_blank(query):
        return False
    return (
        _count_question_marks(query) >= 2
        or COMPARATIVE_PATTERN.fullmatch(query) is not None
        or COMPOSITE_CONJUNCTION_PATTERN.fullmatch(query) is not None
    )


def _is_fast_query(query: str | None) -> bool:
    if query is None or is_blank(query):
        return False
    code_points = len(query)
    if code_points > FAST_MAX_CODE_POINTS:
        return False
    disqualified = REASONING_DISQUALIFIERS.fullmatch(query) is not None
    return not disqualified and (
        FAST_DEFINITION_PATTERN.fullmatch(query) is not None or code_points <= 8
    )


def _count_question_marks(query: str) -> int:
    return sum(1 for char in query if char in ("?", "？"))


def _determine_complex_reason(query: str, sub_queries: tuple[str, ...]) -> str:
    if _count_question_marks(query) >= 2:
        return (
            "Multiple distinct questions detected in query; decomposed into "
            + str(len(sub_queries))
            + " subqueries"
        )
    if COMPARATIVE_PATTERN.fullmatch(query):
        return (
            "Comparative analysis query detected; decomposed into "
            + str(len(sub_queries))
            + " subqueries"
        )
    if COMPOSITE_CONJUNCTION_PATTERN.fullmatch(query):
        return (
            "Multi-condition conjunction query detected; decomposed into "
            + str(len(sub_queries))
            + " subqueries"
        )
    return "Composite complex query decomposed into " + str(len(sub_queries)) + " subqueries"


def _valid_document_ids(raw_document_ids: tuple[str, ...] | list[str] | None) -> tuple[str, ...]:
    if not raw_document_ids:
        return ()
    unique: dict[str, None] = {}
    for raw_document_id in raw_document_ids:
        if raw_document_id is None or is_blank(raw_document_id):
            continue
        try:
            unique[str(uuid.UUID(raw_document_id.strip()))] = None
        except ValueError:
            logger.debug("rag.query_plan.outcome outcome=DOCUMENT_ID_IGNORED")
    return tuple(unique)


def _merge_plan_filters(
    local_filters: tuple[MetadataFilter, ...] | None,
    plan_filters: QueryPlanFilters | None,
) -> list[MetadataFilter]:
    filters = plan_filters or QueryPlanFilters()
    merged: list[MetadataFilter] = list(local_filters or ())
    if filters.date_range.from_value:
        merged.append(MetadataFilter.gte("createdAt", filters.date_range.from_value))
    if filters.date_range.to_value:
        merged.append(MetadataFilter.lte("createdAt", filters.date_range.to_value))
    return merged


def _java_list_string(items: tuple[MetadataFilter, ...] | list[MetadataFilter]) -> str:
    """Java ``List.toString()`` 逐字节复刻（进 STRUCTURED reason，被金标准钉住）。"""

    return "[" + ", ".join(item.java_to_string() for item in items) + "]"


__all__ = [
    "EXACT_IDENTIFIER_PATTERN",
    "FAST_MAX_CODE_POINTS",
    "QueryPlanEnhancer",
    "QueryRouter",
    "contains_exact_identifier",
    "mask",
]
