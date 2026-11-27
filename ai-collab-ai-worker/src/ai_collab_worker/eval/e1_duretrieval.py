"""P2 规模基准 E1：DuRetrieval 10 万语料 HNSW vs 精确扫描 + ACL 10% 迭代扫描评测。

依据契约 §9.2 #6 与 §9.4：
- 语料与抽样清单：``docs/benchmark/2026-11-07-rag-v2-public-benchmark/sample-manifest.json``
  （300 条分层抽样查询）+ DuRetrieval ``corpus.jsonl``；
- 评估维度：
  1. 精确扫描（Exact Scan）基线 vs HNSW 随 ``hnsw.ef_search ∈ {40, 80, 160}`` 的对照；
  2. ACL 10% 可见文档过滤下，``hnsw.iterative_scan`` 关闭（``off``）vs 开启
     （``relaxed_order``）的 Recall@10 对照；
  3. 经典 A1–A6 检索变体汇总（Recall@5 / Recall@10 / MRR@10 / nDCG@10 / Precision@5 /
     SuccessRate@10 / P50 / P95 延迟）。
"""

from __future__ import annotations

import json
import math
import time
import uuid
from collections.abc import Awaitable, Callable, Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.eval.canonical import DatasetError
from ai_collab_worker.index.db import VECTOR_DIMENSION, vector_literal

DEFAULT_EF_SEARCH_VALUES: Final[tuple[int, ...]] = (40, 80, 160)
ACL_TEN_PERCENT_RATIO: Final[float] = 0.10


@dataclass(frozen=True)
class DuQuery:
    qid: str
    query: str
    relevant_pids: frozenset[str]


@dataclass(frozen=True)
class DuQueryScore:
    qid: str
    ranked_pids: tuple[str, ...]
    hits_at_5: int
    hits_at_10: int
    relevant_count: int
    recall_at_5: float
    recall_at_10: float
    precision_at_5: float
    reciprocal_rank: float
    ndcg_at_10: float
    success_at_10: bool
    latency_ms: float


def du_document_uuid(pid: str) -> uuid.UUID:
    """将 DuRetrieval passage ID 确定性映射为 UUIDv5，便于 ``ai_index`` 文档去重。"""

    return uuid.uuid5(uuid.NAMESPACE_URL, f"ai-collab:duretrieval:{pid}")


def load_sample_queries(
    manifest_path: Path,
    *,
    limit: int | None = None,
) -> list[DuQuery]:
    if not manifest_path.is_file():
        msg = f"sample-manifest 不存在: {manifest_path}"
        raise DatasetError(msg)
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    raw_samples = data.get("duretrieval", {}).get("samples")
    if not isinstance(raw_samples, list) or not raw_samples:
        msg = "sample-manifest 缺少 duretrieval.samples"
        raise DatasetError(msg)
    queries: list[DuQuery] = []
    for entry in raw_samples:
        queries.append(
            DuQuery(
                qid=str(entry["qid"]),
                query=str(entry["query"]),
                relevant_pids=frozenset(str(pid) for pid in entry.get("relevantPids", ())),
            )
        )
        if limit is not None and len(queries) >= limit:
            break
    return queries


def iter_corpus_passages(
    corpus_jsonl: Path,
    *,
    limit: int | None = None,
) -> Iterable[tuple[str, str]]:
    """流式读取 DuRetrieval ``corpus.jsonl``，按 passage id 去重。"""

    if not corpus_jsonl.is_file():
        msg = f"corpus.jsonl 不存在: {corpus_jsonl}"
        raise DatasetError(msg)
    seen: set[str] = set()
    emitted = 0
    with corpus_jsonl.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped:
                continue
            row = json.loads(stripped)
            pid = str(row.get("id", ""))
            text = str(row.get("text", ""))
            if not pid or not text.strip() or pid in seen:
                continue
            seen.add(pid)
            yield pid, text
            emitted += 1
            if limit is not None and emitted >= limit:
                return


def dedupe_ranked_pids(raw_pids: Sequence[str]) -> tuple[str, ...]:
    """按文档首次出现顺序去重，与 Java ``DuRetrievalBenchmarkPostgresTest`` 一致。"""

    seen: dict[str, None] = {}
    for pid in raw_pids:
        if pid not in seen:
            seen[pid] = None
    return tuple(seen)


def compute_query_score(
    query: DuQuery,
    ranked_pids: Sequence[str],
    *,
    latency_ms: float,
) -> DuQueryScore:
    deduped = dedupe_ranked_pids(ranked_pids)
    top10 = deduped[:10]
    top5 = top10[:5]
    rel = query.relevant_pids
    rel_count = len(rel)

    hits_at_5 = sum(1 for pid in top5 if pid in rel)
    hits_at_10 = sum(1 for pid in top10 if pid in rel)
    first_rank = next((idx + 1 for idx, pid in enumerate(top10) if pid in rel), 0)
    mrr = 1.0 / first_rank if first_rank > 0 else 0.0

    dcg = sum(1.0 / math.log2(idx + 2) for idx, pid in enumerate(top10) if pid in rel)
    ideal = min(rel_count, 10)
    idcg = sum(1.0 / math.log2(idx + 2) for idx in range(ideal))
    ndcg = (dcg / idcg) if idcg > 0.0 else 0.0

    recall5 = (hits_at_5 / rel_count) if rel_count > 0 else 0.0
    recall10 = (hits_at_10 / rel_count) if rel_count > 0 else 0.0

    return DuQueryScore(
        qid=query.qid,
        ranked_pids=top10,
        hits_at_5=hits_at_5,
        hits_at_10=hits_at_10,
        relevant_count=rel_count,
        recall_at_5=recall5,
        recall_at_10=recall10,
        precision_at_5=hits_at_5 / 5.0,
        reciprocal_rank=mrr,
        ndcg_at_10=ndcg,
        success_at_10=hits_at_10 > 0,
        latency_ms=latency_ms,
    )


def percentile(sorted_values: Sequence[float], pct: int) -> float:
    if not sorted_values:
        return 0.0
    rank = (pct / 100.0) * (len(sorted_values) - 1)
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return float(sorted_values[low])
    frac = rank - low
    return float(sorted_values[low] * (1.0 - frac) + sorted_values[high] * frac)


def summarize_variant(records: Sequence[DuQueryScore]) -> dict[str, Any]:
    n = len(records)
    if n == 0:
        return {"evaluatedQueries": 0}
    latencies = sorted(r.latency_ms for r in records)
    return {
        "evaluatedQueries": n,
        "recallAt5": sum(r.recall_at_5 for r in records) / n,
        "recallAt10": sum(r.recall_at_10 for r in records) / n,
        "mrrAt10": sum(r.reciprocal_rank for r in records) / n,
        "ndcgAt10": sum(r.ndcg_at_10 for r in records) / n,
        "precisionAt5": sum(r.precision_at_5 for r in records) / n,
        "successRateAt10": sum(1.0 for r in records if r.success_at_10) / n,
        "latencyP50Ms": percentile(latencies, 50),
        "latencyP95Ms": percentile(latencies, 95),
    }


def select_ten_percent_acl_subset(
    all_pids: Sequence[str],
    relevant_pids: frozenset[str],
    *,
    ratio: float = ACL_TEN_PERCENT_RATIO,
) -> list[str]:
    """构造占语料约 10% 的可见集合（包含目标查询相关文档以便对比 iterative_scan 召回）。"""

    target_size = max(1, round(len(all_pids) * ratio))
    selected: list[str] = []
    seen: set[str] = set()
    for pid in all_pids:
        if pid in relevant_pids and pid not in seen:
            selected.append(pid)
            seen.add(pid)
    for pid in all_pids:
        if len(selected) >= target_size:
            break
        if pid not in seen:
            selected.append(pid)
            seen.add(pid)
    return selected


PgDenseVariantFn = Callable[
    [Sequence[float], Sequence[uuid.UUID] | None, int],
    Awaitable[list[str]],
]


async def run_pg_hnsw_benchmark(
    *,
    conn: Any,
    generation_id: uuid.UUID,
    project_id: uuid.UUID,
    queries: Sequence[DuQuery],
    query_vectors: Sequence[Sequence[float]],
    pid_by_doc_uuid: dict[uuid.UUID, str],
    all_doc_uuids: Sequence[uuid.UUID],
    acl_ten_percent_uuids_fn: Callable[[DuQuery], list[uuid.UUID]] | None = None,
    ef_search_values: Sequence[int] = DEFAULT_EF_SEARCH_VALUES,
    top_k: int = 10,
) -> dict[str, Any]:
    """在真实 ``ai_index.chunk`` 上跑 E1 精确扫描 vs HNSW(ef_search) + ACL 10% iterative scan。"""

    async def _run_sql_dense(
        *,
        vector: Sequence[float],
        visible_uuids: Sequence[uuid.UUID] | None,
        exact_scan: bool,
        ef_search: int | None,
        iterative_mode: str,
    ) -> list[str]:
        vec_lit = vector_literal(vector, dimension=VECTOR_DIMENSION)
        async with conn.transaction():
            if exact_scan:
                await conn.execute("SET LOCAL enable_indexscan = off")
                await conn.execute("SET LOCAL enable_bitmapscan = off")
            else:
                if ef_search is not None:
                    await conn.execute(f"SET LOCAL hnsw.ef_search = {int(ef_search)}")
                await conn.execute(f"SET LOCAL hnsw.iterative_scan = {iterative_mode}")

            if visible_uuids is None:
                cur = await conn.execute(
                    "WITH c AS MATERIALIZED ("
                    "  SELECT document_id, (embedding <=> %s::public.vector) AS distance "
                    "  FROM ai_index.chunk "
                    "  WHERE generation_id = %s AND project_id = %s AND chunk_type <> 'PARENT' "
                    "  ORDER BY embedding <=> %s::public.vector LIMIT %s"
                    ") SELECT document_id FROM c ORDER BY distance ASC",
                    (vec_lit, generation_id, project_id, vec_lit, top_k * 2),
                )
            else:
                cur = await conn.execute(
                    "WITH c AS MATERIALIZED ("
                    "  SELECT document_id, (embedding <=> %s::public.vector) AS distance "
                    "  FROM ai_index.chunk "
                    "  WHERE generation_id = %s AND project_id = %s "
                    "    AND document_id = ANY(%s::uuid[]) AND chunk_type <> 'PARENT' "
                    "  ORDER BY embedding <=> %s::public.vector LIMIT %s"
                    ") SELECT document_id FROM c ORDER BY distance ASC",
                    (
                        vec_lit,
                        generation_id,
                        project_id,
                        list(visible_uuids),
                        vec_lit,
                        top_k * 2,
                    ),
                )
            rows = await cur.fetchall()
        return [
            pid_by_doc_uuid[uid if isinstance(uid, uuid.UUID) else uuid.UUID(str(uid))]
            for (uid,) in rows
            if (uid if isinstance(uid, uuid.UUID) else uuid.UUID(str(uid))) in pid_by_doc_uuid
        ]

    variants: dict[str, dict[str, Any]] = {}

    async def _eval_variant(
        *,
        exact_scan: bool,
        ef_search: int | None,
        iterative_mode: str,
        use_acl: bool,
    ) -> dict[str, Any]:
        scores: list[DuQueryScore] = []
        for q, vec in zip(queries, query_vectors, strict=True):
            vis = (
                acl_ten_percent_uuids_fn(q)
                if use_acl and acl_ten_percent_uuids_fn is not None
                else all_doc_uuids
            )
            t0 = time.perf_counter()
            pids = await _run_sql_dense(
                vector=vec,
                visible_uuids=vis,
                exact_scan=exact_scan,
                ef_search=ef_search,
                iterative_mode=iterative_mode,
            )
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            scores.append(compute_query_score(q, pids, latency_ms=elapsed_ms))
        return summarize_variant(scores)

    variants["EXACT_SCAN"] = await _eval_variant(
        exact_scan=True, ef_search=None, iterative_mode="off", use_acl=False
    )
    for ef in ef_search_values:
        variants[f"HNSW_EF_{ef}"] = await _eval_variant(
            exact_scan=False,
            ef_search=ef,
            iterative_mode="relaxed_order",
            use_acl=False,
        )
    variants["ACL_10PCT_ITERATIVE_OFF"] = await _eval_variant(
        exact_scan=False,
        ef_search=40,
        iterative_mode="off",
        use_acl=True,
    )
    variants["ACL_10PCT_ITERATIVE_RELAXED"] = await _eval_variant(
        exact_scan=False,
        ef_search=40,
        iterative_mode="relaxed_order",
        use_acl=True,
    )
    return {
        "benchmark": "P2-E1-DURETRIEVAL-HNSW-VS-EXACT",
        "sampleSize": len(queries),
        "efSearchValues": list(ef_search_values),
        "variants": variants,
    }


__all__ = [
    "ACL_TEN_PERCENT_RATIO",
    "DEFAULT_EF_SEARCH_VALUES",
    "DuQuery",
    "DuQueryScore",
    "compute_query_score",
    "dedupe_ranked_pids",
    "du_document_uuid",
    "iter_corpus_passages",
    "load_sample_queries",
    "percentile",
    "run_pg_hnsw_benchmark",
    "select_ten_percent_acl_subset",
    "summarize_variant",
]
