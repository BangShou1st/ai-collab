"""COMPLEX 路由的进程内 query plan 增强等价测试（P3 契约 §2.4、§8.4.3）。

覆盖 Java ``routeComplexWithOptionalQueryPlan`` 五重前置检查的 P3 等价语义：

- 未启用（``plan_enabled=False``）→ 本地规则（原 DISABLED 分支）；
- 增强器缺失（原 CLIENT_MISSING 分支）→ 本地规则；
- 身份缺失（uid/pid）→ 本地规则（fail closed）；
- 增强调用异常（原 TIMEOUT/UNAVAILABLE/HTTP/UNEXPECTED 分类）→ 本地规则；
- ``confidence < 0.5`` → 本地规则；
- 采纳：改写查询 + 计划子查询（空则回退本地分解）+ 计划文档范围（UUID 过滤去重）
  + ``dateRange → createdAt`` gte/lte 合并，reason 逐字节一致。
"""

from __future__ import annotations

import asyncio

from ai_collab_worker.retrieval.query.models import (
    IllegalArgumentException,
    QueryPlanDateRange,
    QueryPlanFilters,
    QueryPlanOutcome,
    QueryRouteDecision,
    RetrievalStrategy,
    StructuredQuery,
)
from ai_collab_worker.retrieval.query.routing import QueryRouter

COMPLEX_QUERY = "A方案 和 B方案 的区别是什么"
LOCAL_SUBS: tuple[str, ...] = (
    "A方案 的定义与特性",
    "B方案 的定义与特性",
    "A方案 与 B方案 的区别与对比",
)


def _route(router: QueryRouter, *args: object) -> QueryRouteDecision:
    return asyncio.run(router.route(COMPLEX_QUERY, *args))  # type: ignore[arg-type]


def test_plan_disabled_uses_local_rules() -> None:
    router = QueryRouter(plan_enhancer=None, plan_enabled=False)
    decision = _route(
        router, (), "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"
    )
    assert decision.complexity.value == "COMPLEX"
    assert decision.strategy is RetrievalStrategy.MULTI_QUERY_HYBRID
    assert decision.sub_queries == LOCAL_SUBS
    assert decision.reason == "Comparative analysis query detected; decomposed into 3 subqueries"
    assert decision.document_ids == ()


def test_enhancer_missing_falls_back_locally() -> None:
    router = QueryRouter(plan_enhancer=None, plan_enabled=True)
    decision = _route(
        router, (), "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"
    )
    assert decision.sub_queries == LOCAL_SUBS


def test_identity_missing_fails_closed_to_local() -> None:
    captured: list[object] = []

    async def enhancer(*args: object) -> QueryPlanOutcome:
        captured.append(args)
        return QueryPlanOutcome(
            rewritten_query="改写后的查询",
            sub_queries=LOCAL_SUBS,
            filters=QueryPlanFilters(),
            confidence=0.9,
        )

    router = QueryRouter(plan_enhancer=enhancer, plan_enabled=True)
    decision = _route(router, (), None, None)
    assert captured == []
    assert decision.sub_queries == LOCAL_SUBS


def test_enhancer_exception_falls_back_locally() -> None:
    async def enhancer(*_args: object) -> QueryPlanOutcome:
        raise TimeoutError("plan timeout")

    router = QueryRouter(plan_enhancer=enhancer, plan_enabled=True)
    decision = _route(
        router, (), "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"
    )
    assert decision.sub_queries == LOCAL_SUBS


def test_low_confidence_uses_local_rules() -> None:
    async def enhancer(*_args: object) -> QueryPlanOutcome:
        return QueryPlanOutcome(
            rewritten_query="改写后的查询",
            sub_queries=("计划子查询",),
            filters=QueryPlanFilters(),
            confidence=0.49,
        )

    router = QueryRouter(plan_enhancer=enhancer, plan_enabled=True)
    decision = _route(
        router, (), "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"
    )
    assert decision.sub_queries == LOCAL_SUBS


def test_adopted_plan_merges_query_subqueries_filters_and_scope() -> None:
    async def enhancer(*_args: object) -> QueryPlanOutcome:
        return QueryPlanOutcome(
            rewritten_query="A方案 与 B方案 的优劣对比",
            sub_queries=(),
            filters=QueryPlanFilters(
                document_ids=(
                    "00000000-0000-4000-8000-000000000003",
                    "not-a-uuid",
                    "00000000-0000-4000-8000-000000000003",
                ),
                doc_types=("pdf",),
                date_range=QueryPlanDateRange(from_value="2026-01-01", to_value="2026-06-30"),
            ),
            confidence=0.9,
        )

    router = QueryRouter(plan_enhancer=enhancer, plan_enabled=True)
    decision = _route(
        router,
        ("00000000-0000-4000-8000-000000000009",),
        "00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002",
    )
    assert decision.complexity.value == "COMPLEX"
    assert decision.strategy is RetrievalStrategy.MULTI_QUERY_HYBRID
    assert decision.reason == (
        "Comparative analysis query detected; decomposed into 3 subqueries"
        " | QueryPlan adopted: rewritten query and bounded filters"
    )
    assert decision.structured_query.semantic_query == "A方案 与 B方案 的优劣对比"
    # 计划子查询为空 → 回退本地分解
    assert decision.sub_queries == LOCAL_SUBS
    # 计划文档范围：非法 UUID 丢弃、去重保序
    assert decision.document_ids == ("00000000-0000-4000-8000-000000000003",)
    # dateRange → createdAt gte/lte 合并到过滤器
    filters = decision.structured_query.filters
    assert [item.field for item in filters] == ["createdAt", "createdAt"]
    assert [item.operator.value for item in filters] == ["GTE", "LTE"]
    assert [item.value for item in filters] == ["2026-01-01", "2026-06-30"]


def test_routing_error_fallback_reason_uses_java_exception_name() -> None:
    """非法过滤字段触发 :class:`IllegalArgumentException`，回退 reason 类名逐字节一致。"""

    router = QueryRouter(plan_enhancer=None, plan_enabled=False)
    decision = asyncio.run(router.route("foo:bar 查询设备台账"))
    assert decision.complexity.value == "HYBRID"
    assert decision.strategy is RetrievalStrategy.HYBRID
    assert decision.reason == "Fallback to HYBRID due to routing error: IllegalArgumentException"
    assert decision.structured_query.semantic_query == "foo:bar 查询设备台账"
    assert isinstance(decision.structured_query.semantic_query, str)
    assert IllegalArgumentException.__name__ == "IllegalArgumentException"


_ = (StructuredQuery,)
