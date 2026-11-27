"""RRF 融合与多查询合并（逐行对照 Java ``ReciprocalRankFusion``、
``RetrievalPipeline.executeMultiQuery``）。

与 Java 唯一的差别是平局的最后一级：Java 按 ``document_chunk.id`` 升序，这里按
``(documentId, chunkNo)`` 升序（P2-R2 R2-5）。
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import dataclass, replace
from functools import cmp_to_key
from typing import Final

from ai_collab_worker.retrieval.candidate import (
    Candidate,
    compare_keys,
    java_double_compare,
    truncate,
)

#: Java ``ReciprocalRankFusion.DEFAULT_K``。
RRF_K: Final[int] = 60
DEFAULT_DENSE_WEIGHT: Final[float] = 1.0
DEFAULT_LEXICAL_WEIGHT: Final[float] = 1.0

#: Java ``RetrievalPipeline.executeMultiQuery``：``min(topK + 3*(n-1), 24)``。
MULTI_QUERY_EXTRA_PER_QUERY: Final[int] = 3
MULTI_QUERY_MAX_HITS: Final[int] = 24

_Key = tuple[uuid.UUID, int]


@dataclass
class _Accumulator:
    base: Candidate
    dense_rank: int | None = None
    dense: float | None = None
    dense_contribution: float = 0.0
    lexical_rank: int | None = None
    lexical: float | None = None
    lexical_contribution: float = 0.0


def rrf_fuse(
    dense: Sequence[Candidate],
    lexical: Sequence[Candidate],
    top_k: int,
    *,
    k: int = RRF_K,
    dense_weight: float = DEFAULT_DENSE_WEIGHT,
    lexical_weight: float = DEFAULT_LEXICAL_WEIGHT,
) -> list[Candidate]:
    """``ReciprocalRankFusion.fuse``：``Σ w / (k + rank)``，按身份去重，保留首次出现的实例。"""

    if k <= 0:
        msg = "RRF 常数 k 必须为正整数"
        raise ValueError(msg)
    if not dense and not lexical:
        return []

    accumulators: dict[_Key, _Accumulator] = {}
    for index, candidate in enumerate(dense):
        rank = index + 1
        acc = accumulators.setdefault(candidate.key, _Accumulator(candidate))
        acc.dense_rank = rank
        acc.dense = candidate.dense
        acc.dense_contribution = dense_weight / (k + rank)
    for index, candidate in enumerate(lexical):
        rank = index + 1
        acc = accumulators.setdefault(candidate.key, _Accumulator(candidate))
        acc.lexical_rank = rank
        acc.lexical = candidate.lexical
        acc.lexical_contribution = lexical_weight / (k + rank)

    fused = [
        replace(
            acc.base,
            dense_rank=acc.dense_rank,
            dense=acc.dense,
            lexical_rank=acc.lexical_rank,
            lexical=acc.lexical,
            rrf=acc.dense_contribution + acc.lexical_contribution,
        )
        for acc in accumulators.values()
    ]
    fused.sort(key=cmp_to_key(_fusion_order))
    return truncate(fused, top_k)


def _fusion_order(left: Candidate, right: Candidate) -> int:
    # rrf 降序 → 命中路数降序 → 最好名次升序 → (documentId, chunkNo) 升序。
    by_score = java_double_compare(right.rrf, left.rrf)
    if by_score != 0:
        return by_score
    routes_left = _routes(left)
    routes_right = _routes(right)
    if routes_left != routes_right:
        return -1 if routes_right < routes_left else 1
    best_left = _best_rank(left)
    best_right = _best_rank(right)
    if best_left != best_right:
        return -1 if best_left < best_right else 1
    return compare_keys(left, right)


def _routes(candidate: Candidate) -> int:
    return (candidate.dense_rank is not None) + (candidate.lexical_rank is not None)


def _best_rank(candidate: Candidate) -> int:
    big = 2**31 - 1  # Integer.MAX_VALUE
    dense = candidate.dense_rank if candidate.dense_rank is not None else big
    lexical = candidate.lexical_rank if candidate.lexical_rank is not None else big
    return min(dense, lexical)


def distinct_queries(queries: Sequence[str]) -> list[str]:
    """``filter(q -> q != null && !q.isBlank()).distinct()``（按原串去重，不 trim）。"""

    from ai_collab_worker.retrieval.javatext import is_blank

    seen: dict[str, None] = {}
    for query in queries:
        if not is_blank(query) and query not in seen:
            seen[query] = None
    return list(seen)


def multi_query_limit(top_k: int, query_count: int) -> int:
    """``min(topK + 3*(n-1), 24)``（P2-R2 R2-3）。"""

    return min(top_k + (query_count - 1) * MULTI_QUERY_EXTRA_PER_QUERY, MULTI_QUERY_MAX_HITS)


def merge_multi_query(per_query: Sequence[Sequence[Candidate]], top_k: int) -> list[Candidate]:
    """``executeMultiQuery`` 的合并段：主查询（第 0 条）保序在前，子查询独有的候选按跨查询
    RRF 之和降序追加，截断到 ``min(topK + 3*(n-1), 24)``。主查询无召回时等价于全局 RRF 之和排序。
    """

    fused_score: dict[_Key, float] = {}
    best: dict[_Key, Candidate] = {}
    for results in per_query:
        for candidate in results:
            rrf = candidate.rrf
            key = candidate.key
            fused_score[key] = fused_score[key] + rrf if key in fused_score else rrf
            current = best.get(key)
            if current is None or rrf > current.rrf:
                best[key] = candidate

    consolidated: list[Candidate] = []
    seen: set[_Key] = set()
    primary = per_query[0] if per_query else ()
    for candidate in primary:
        if candidate.key not in seen:
            seen.add(candidate.key)
            consolidated.append(candidate)
    extras = [candidate for key, candidate in best.items() if key not in seen]

    def _extra_order(left: Candidate, right: Candidate) -> int:
        by_score = java_double_compare(
            fused_score.get(right.key, 0.0), fused_score.get(left.key, 0.0)
        )
        return by_score if by_score != 0 else compare_keys(left, right)

    extras.sort(key=cmp_to_key(_extra_order))
    consolidated.extend(extras)
    return truncate(consolidated, multi_query_limit(top_k, len(per_query)))


__all__ = [
    "MULTI_QUERY_MAX_HITS",
    "RRF_K",
    "distinct_queries",
    "merge_multi_query",
    "multi_query_limit",
    "rrf_fuse",
]
