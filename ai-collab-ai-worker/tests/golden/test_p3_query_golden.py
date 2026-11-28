"""P3 查询理解等价对拍（契约 §8.4.1/§8.4.2）。

对 Java 侧导出的金标准逐条比对：路由决策（strategy/effectiveQuery/subQueries/filters/
reason 逐字节）与多轮消歧（rewrittenQuery/contextPrompt/managedTurns 逐字节）。
任何字段不一致都说明移植漂移——39 题 E2 基线余量为 0，移植语义不允许任何自由发挥。
"""

from __future__ import annotations

import asyncio
import datetime as dt

from p3_golden import iter_routing, iter_understanding, messages_of

from ai_collab_worker.retrieval.query.models import (
    ConversationTurn,
    QueryComplexity,
    QueryRouteDecision,
    RetrievalStrategy,
    StructuredQuery,
    default_strategy,
)
from ai_collab_worker.retrieval.query.routing import QueryRouter
from ai_collab_worker.retrieval.query.understanding import QueryUnderstandingService


def test_routing_golden_matches_java() -> None:
    async def run() -> list[tuple[str, QueryRouteDecision, dict[str, object]]]:
        router = QueryRouter()  # 计划增强未启用 → 与导出时的本地分支一致
        outcomes: list[tuple[str, QueryRouteDecision, dict[str, object]]] = []
        for record in iter_routing():
            decision = await router.route(
                record["query"], tuple(record["decision"].get("documentIds") or ())
            )
            outcomes.append((record["id"], decision, record["decision"]))
        return outcomes

    for record_id, decision, expected in asyncio.run(run()):
        context = f"routing golden {record_id}"
        assert decision.complexity.value == expected["complexity"], context
        assert decision.strategy.value == expected["strategy"], context
        assert decision.reason == expected["reason"], context
        assert decision.structured_query.semantic_query == expected["semanticQuery"], context
        assert list(decision.sub_queries) == expected["subQueries"], context
        actual_filters = [
            {
                "field": item.field,
                "operator": item.operator.value,
                "value": item.value,
            }
            for item in decision.structured_query.filters
        ]
        assert actual_filters == expected["filters"], context
        assert list(decision.document_ids) == expected["documentIds"], context


def test_understanding_golden_matches_java() -> None:
    service = QueryUnderstandingService()
    for record in iter_understanding():
        messages = messages_of(record)
        result = service.understand(messages, record["currentQuestion"])
        expected = record["expected"]
        context = f"understanding golden {record['id']}"
        assert result.original_question == expected["originalQuestion"], context
        assert result.rewritten_query == expected["rewrittenQuery"], context
        assert result.intent_type.value == expected["intentType"], context
        assert result.context_prompt == expected["contextPrompt"], context
        actual_turns = [
            {
                "turnIndex": turn.turn_index,
                "userQuestion": turn.user_question,
                "assistantAnswer": turn.assistant_answer,
                "timestamp": turn.timestamp.isoformat() if turn.timestamp else None,
                "insufficientEvidence": turn.insufficient_evidence,
            }
            for turn in result.managed_turns
        ]
        assert len(actual_turns) == len(expected["managedTurns"]), context
        for actual, wanted in zip(actual_turns, expected["managedTurns"], strict=True):
            assert actual["turnIndex"] == wanted["turnIndex"], context
            assert actual["userQuestion"] == wanted["userQuestion"], context
            assert actual["assistantAnswer"] == wanted["assistantAnswer"], context
            assert actual["insufficientEvidence"] == wanted["insufficientEvidence"], context
            assert _same_instant(actual["timestamp"], wanted["timestamp"]), context


def _same_instant(actual: str | None, wanted: str | None) -> bool:
    if actual is None or wanted is None:
        return actual is None and wanted is None
    return _parse(actual) == _parse(wanted)


def _parse(raw: str) -> dt.datetime:
    return dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))


def test_routing_model_semantics_align_with_java() -> None:
    """模型类自身的 Java 语义锚点（默认策略映射 / 工厂形态 / 平凡回退）。"""

    assert QueryComplexity("FAST") is QueryComplexity.FAST
    assert default_strategy(QueryComplexity.FAST) is RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK
    assert default_strategy(QueryComplexity.STRUCTURED) is RetrievalStrategy.HYBRID
    assert default_strategy(QueryComplexity.COMPLEX) is RetrievalStrategy.MULTI_QUERY_HYBRID
    assert default_strategy(QueryComplexity.HYBRID) is RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK

    decision = QueryRouteDecision.hybrid("r", StructuredQuery("q"))
    assert decision.sub_queries == ("q",)
    assert decision.strategy is RetrievalStrategy.HYBRID

    empty_turns: list[ConversationTurn] = []
    service = QueryUnderstandingService()
    assert service.understand([], "   ") == service.understand(empty_turns, "   ")
