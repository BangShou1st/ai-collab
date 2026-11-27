"""R2-5 金标准回放：Java 导出的夹具 vs Python RRF / 启发式重排（CI：p2-golden-fixture.yml）。

两层：

a. 夹具层（不依赖数据库 / Ollama）：把 Java 记录的 fuse / rerank **输入**原样喂给
   ``rrf_fuse`` / ``heuristic_rerank``，与 Java 输出按 R2-5 规则比对——这是算法差异的判据；
b. 端到端层（需要 ``P2_GOLDEN_DATABASE_URL``）：以 ``ai_worker``（非超级用户）走正式迁移路径
   建 ``ai_index``，按 Java 顺序 / 批大小 32 调 Ollama 建语料嵌入，用 ``PostgresRetrievalStore``
   重跑 dense / lexical 召回再融合；嵌入差异与排序/融合差异分开统计。

缺夹具 / 缺环境时一律 ``pytest.skip`` 并写明原因，绝不算通过。
报告写到 ``$P2_GOLDEN_REPORT_DIR/r2-5-compare.json``。
"""

from __future__ import annotations

import asyncio
import json
import os
import uuid
from collections import Counter
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Any

import pytest
from golden_compare import (
    BOUNDARY_TIE_RULE,
    EPSILON,
    CompareResult,
    GoldenFieldError,
    RankedItem,
    compare_ranked,
    compare_ranked_truncated,
    item_from_row,
    require_field,
    require_score,
)

from ai_collab_worker.retrieval.candidate import Candidate
from ai_collab_worker.retrieval.fusion import rrf_fuse
from ai_collab_worker.retrieval.heuristic import heuristic_rerank

pytestmark = pytest.mark.golden

QA_TOP_K = 8
AGENT_TOP_K = 20
QUESTION_COUNT = 39
EMBEDDING_MODEL = "qwen3-embedding:0.6b"
EMBEDDING_DIMENSION = 1024
EMBEDDING_BATCH_SIZE = 32
_ZERO_UUID = uuid.UUID(int=0)
_BIG = 2**31 - 1

FIXTURE_FILES = {
    "p9": "p9-39q-golden.json",
    "reranker": "reranker-golden.json",
    "rrf": "rrf-golden.json",
}


# ---------------------------------------------------------------- 夹具与报告


def _fixture_dir() -> Path:
    raw = os.environ.get("P2_GOLDEN_FIXTURE_DIR", "").strip()
    if not raw:
        pytest.skip("未设置 P2_GOLDEN_FIXTURE_DIR：没有 Java 导出的金标准夹具，R2-5 比对未执行")
    path = Path(raw)
    if not path.is_dir():
        pytest.skip(f"P2_GOLDEN_FIXTURE_DIR={raw} 不存在：Java 导出器未运行，R2-5 比对未执行")
    return path


def _load(name: str) -> dict[str, Any]:
    path = _fixture_dir() / FIXTURE_FILES[name]
    if not path.is_file():
        pytest.skip(f"缺少夹具 {path.name}：Java 导出器未产出该文件，R2-5 比对未执行")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        msg = f"{path.name} 顶层不是对象"
        raise GoldenFieldError(msg)
    return data


def _cases(bundle: Mapping[str, Any], name: str) -> list[dict[str, Any]]:
    cases = require_field(bundle, "cases")
    count = require_field(bundle, "caseCount")
    if not isinstance(cases, list) or count != len(cases) or not cases:
        size = len(cases) if isinstance(cases, list) else "?"
        msg = f"{name}: caseCount={count} 与 cases 数量 {size} 不符或为空"
        raise GoldenFieldError(msg)
    return cases


@pytest.fixture(scope="session")
def golden_report() -> Iterator[dict[str, Any]]:
    report: dict[str, Any] = {
        "rule": {
            "tieEpsilon": EPSILON,
            "identity": "(documentId, chunkNo)",
            "boundaryTieGroup": BOUNDARY_TIE_RULE,
        },
        "layers": {},
    }
    yield report
    target = os.environ.get("P2_GOLDEN_REPORT_DIR", "").strip()
    if not target:
        return
    out = Path(target)
    out.mkdir(parents=True, exist_ok=True)
    (out / "r2-5-compare.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
    )


# ---------------------------------------------------------------- 候选构造与排序分量


def _rows(row: Mapping[str, Any], name: str) -> list[Mapping[str, Any]]:
    value = require_field(row, name)
    if not isinstance(value, list) or not all(isinstance(v, Mapping) for v in value):
        msg = f"字段 {name!r} 不是对象数组"
        raise GoldenFieldError(msg)
    return value


def _int(row: Mapping[str, Any], name: str) -> int:
    value = require_field(row, name)
    if isinstance(value, bool) or not isinstance(value, int):
        msg = f"字段 {name!r} 不是整数：{value!r}"
        raise GoldenFieldError(msg)
    return value


def _optional_score(row: Mapping[str, Any], name: str) -> float | None:
    scores = require_field(row, "scores")
    if not isinstance(scores, Mapping) or name not in scores:
        msg = f"夹具行 scores 缺少键 {name!r}"
        raise GoldenFieldError(msg)
    value = scores[name]
    return None if value is None else float(value)


def _candidate(row: Mapping[str, Any], *, with_content: bool = False) -> Candidate:
    item = item_from_row(row, (0.0,))
    content = ""
    if with_content:
        raw = require_field(row, "content")
        content = str(raw)
    return Candidate(
        document_id=uuid.UUID(item.document_id),
        chunk_no=item.chunk_no,
        content_hash=str(require_field(row, "contentHash")).lower(),
        chunk_type="CHILD",
        heading=row.get("heading"),
        content=content,
        body_snapshot_id=_ZERO_UUID,
        dense_rank=row.get("denseRank"),
        dense=_optional_score(row, "dense"),
        lexical_rank=row.get("lexicalRank"),
        lexical=_optional_score(row, "lexical"),
        # Java SemanticHeuristicReranker：rrfScore 为空按 0.0。
        rrf=_optional_score(row, "rrf") or 0.0,
    )


def _routes_best(dense_rank: object, lexical_rank: object) -> tuple[float, float]:
    routes = (dense_rank is not None) + (lexical_rank is not None)
    best = min(
        int(dense_rank) if isinstance(dense_rank, int) else _BIG,
        int(lexical_rank) if isinstance(lexical_rank, int) else _BIG,
    )
    # 命中路数降序、最好名次升序：取负 / 原值，仅用于“是否平局”判定。
    return (-float(routes), float(best))


def _java_rrf_items(rows: Sequence[Mapping[str, Any]]) -> list[RankedItem]:
    return [
        item_from_row(
            r, (require_score(r, "rrf"), *_routes_best(r.get("denseRank"), r.get("lexicalRank")))
        )
        for r in rows
    ]


def _py_rrf_items(candidates: Sequence[Candidate]) -> list[RankedItem]:
    return [
        RankedItem(
            str(c.document_id), c.chunk_no, (c.rrf, *_routes_best(c.dense_rank, c.lexical_rank))
        )
        for c in candidates
    ]


def _java_rerank_items(rows: Sequence[Mapping[str, Any]]) -> list[RankedItem]:
    return [
        item_from_row(r, (require_score(r, "rerank"), _optional_score(r, "rrf") or 0.0))
        for r in rows
    ]


def _py_rerank_items(candidates: Sequence[Candidate]) -> list[RankedItem]:
    out = []
    for c in candidates:
        if c.rerank is None:
            msg = f"Python 重排输出缺 rerank 分：{c.key}"
            raise GoldenFieldError(msg)
        out.append(RankedItem(str(c.document_id), c.chunk_no, (c.rerank, c.rrf)))
    return out


def _result_row(case: Mapping[str, Any], result: CompareResult, **extra: Any) -> dict[str, Any]:
    return {
        "questionId": require_field(case, "questionId"),
        "mode": require_field(case, "mode"),
        "equal": result.equal,
        "kind": result.kind,
        "reason": result.reason,
        "javaGroups": result.expected_groups,
        "pythonGroups": result.actual_groups,
        "maxScoreAbsDiff": result.max_score_diff,
        "boundaryTieResolved": result.boundary_tie_resolved,
        "boundaryGroupSize": result.boundary_group_size,
        "boundaryGroupStart": result.boundary_group_start,
        **extra,
    }


# ---------------------------------------------------------------- a. 夹具层


def test_rrf_fixture_replay(golden_report: dict[str, Any]) -> None:
    cases = _cases(_load("rrf"), "rrf-golden.json")
    rows: list[dict[str, Any]] = []
    for case in cases:
        dense = [_candidate(r) for r in _rows(case, "denseInputs")]
        lexical = [_candidate(r) for r in _rows(case, "lexicalInputs")]
        top_k = _int(case, "topK")
        # 不截断地融合（上限取两路候选总数），再按 R2-5 + R2-5a（截断边界平局组）比对。
        full = rrf_fuse(dense, lexical, len(dense) + len(lexical))
        result = compare_ranked_truncated(
            _java_rrf_items(_rows(case, "output")), _py_rrf_items(full), top_k
        )
        rows.append(_result_row(case, result, topK=top_k))
    _record_stage(golden_report, "fixture", "rrf", rows)
    _assert_all_equal("RRF 融合（夹具层：排序/融合算法差异）", rows)


def test_reranker_fixture_replay(golden_report: dict[str, Any]) -> None:
    cases = _cases(_load("reranker"), "reranker-golden.json")
    rows: list[dict[str, Any]] = []
    for case in cases:
        inputs = [_candidate(r, with_content=True) for r in _rows(case, "inputs")]
        query = str(require_field(case, "query"))
        top_n = _int(case, "topN")
        # 不截断地重排（topN 取输入总数），再按 R2-5 + R2-5a（截断边界平局组）比对。
        full = heuristic_rerank(query, inputs, len(inputs))
        result = compare_ranked_truncated(
            _java_rerank_items(_rows(case, "output")), _py_rerank_items(full), top_n
        )
        rows.append(_result_row(case, result, topN=top_n))
    _record_stage(golden_report, "fixture", "heuristicRerank", rows)
    _assert_all_equal("启发式重排（夹具层：排序算法差异）", rows)


def test_p9_fixture_per_question_mode(golden_report: dict[str, Any]) -> None:
    """39 题 × QA/AGENT：校验夹具结构，并把阶段比对结果按题、按模式汇总进报告。"""

    bundle = _load("p9")
    questions = require_field(bundle, "questions")
    assert isinstance(questions, list)
    assert require_field(bundle, "questionCount") == QUESTION_COUNT == len(questions)
    assert require_field(bundle, "embeddingModel") == EMBEDDING_MODEL
    assert require_field(bundle, "embeddingDimension") == EMBEDDING_DIMENSION

    fixture = golden_report["layers"].get("fixture", {})
    stage_rows: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for stage, rows in fixture.items():
        for row in rows.get("cases", []):
            stage_rows.setdefault((row["questionId"], row["mode"]), []).append(
                {
                    "stage": stage,
                    "equal": row["equal"],
                    "kind": row["kind"],
                    "boundaryTieResolved": row.get("boundaryTieResolved", False),
                }
            )

    per_question = []
    for q in questions:
        qid = str(require_field(q, "id"))
        modes = {}
        for label, key, top_k in (("QA", "qa", QA_TOP_K), ("AGENT", "agent", AGENT_TOP_K)):
            run = require_field(q, key)
            assert isinstance(run, Mapping)
            assert require_field(run, "topK") == top_k, f"{qid}/{label} topK"
            hits = require_field(run, "hits")
            assert isinstance(hits, list)
            for hit in hits:
                item_from_row(hit, (require_score(hit, "final"),))
            stages = stage_rows.get((qid, label), [])
            modes[label] = {
                "javaHitCount": len(hits),
                "appliedStrategy": run.get("appliedStrategy"),
                "stageCases": len(stages),
                "stageMismatches": sum(1 for s in stages if not s["equal"]),
                "stages": stages,
            }
        per_question.append({"id": qid, "set": q.get("set"), "modes": modes})
    golden_report["perQuestion"] = per_question


def _record_stage(
    report: dict[str, Any], layer: str, stage: str, rows: list[dict[str, Any]]
) -> None:
    kinds = Counter(r["kind"] for r in rows if not r["equal"])
    diffs = [r["maxScoreAbsDiff"] for r in rows if r.get("maxScoreAbsDiff") is not None]
    boundary = [r for r in rows if r["equal"] and r.get("boundaryTieResolved")]
    report["layers"].setdefault(layer, {})[stage] = {
        "caseCount": len(rows),
        # equal = strictEqual + boundaryTieResolved；两者分开统计，绝不混为“逐条一致”。
        "equal": sum(1 for r in rows if r["equal"]),
        "strictEqual": sum(1 for r in rows if r["equal"] and not r.get("boundaryTieResolved")),
        "boundaryTieResolved": len(boundary),
        "boundaryTieResolvedCases": [
            {
                "questionId": r["questionId"],
                "mode": r["mode"],
                "boundaryGroupStart": r.get("boundaryGroupStart"),
                "boundaryGroupSize": r.get("boundaryGroupSize"),
            }
            for r in boundary
        ],
        "mismatch": sum(1 for r in rows if not r["equal"]),
        "mismatchByKind": dict(kinds),
        "maxScoreAbsDiff": max(diffs) if diffs else None,
        "cases": rows,
    }


def _assert_all_equal(label: str, rows: Sequence[Mapping[str, Any]]) -> None:
    bad = [r for r in rows if not r["equal"]]
    assert not bad, f"{label}：{len(bad)}/{len(rows)} 个用例不一致，首例 " + json.dumps(
        bad[0], ensure_ascii=False, default=str
    )


# ---------------------------------------------------------------- b. 端到端层


def test_end_to_end_pg_ollama(golden_report: dict[str, Any]) -> None:
    worker_url = os.environ.get("P2_GOLDEN_DATABASE_URL", "").strip()
    if not worker_url:
        pytest.skip("未设置 P2_GOLDEN_DATABASE_URL：端到端层（ai_index + Ollama）未执行")
    p9 = _load("p9")
    rrf_cases = _cases(_load("rrf"), "rrf-golden.json")
    corpus = require_field(p9, "corpus")
    assert isinstance(corpus, list)
    missing = [
        f"{d.get('filename')}#{c.get('chunkNo')}"
        for d in corpus
        for c in d.get("chunks", [])
        if c.get("content") is None
    ]
    if missing:
        pytest.skip(
            "夹具 corpus[].chunks[] 未导出 content（共缺 "
            f"{len(missing)} 块），Python 侧无法按 Java 相同文本与顺序重建语料嵌入；端到端层未执行"
        )
    pytest.importorskip("psycopg")
    rows = asyncio.run(_run_end_to_end(worker_url, p9, rrf_cases))
    golden_report["layers"]["endToEnd"] = _summarize_e2e(rows)
    # 多查询用例的夹具未记录每次 fuse 对应的输入查询串，无法重建输入；
    # 报告里按 status 单独计数，不计入失败。
    bad = [r for r in rows if r["status"] not in ("equal", "not_compared_multi_query")]
    assert not bad, f"端到端层 {len(bad)}/{len(rows)} 个用例不一致，首例 " + json.dumps(
        bad[0], ensure_ascii=False, default=str
    )


async def _run_end_to_end(
    worker_url: str, p9: Mapping[str, Any], rrf_cases: Sequence[Mapping[str, Any]]
) -> list[dict[str, Any]]:
    import httpx
    from psycopg import AsyncConnection
    from pydantic import SecretStr

    from ai_collab_worker.contract.knowledge_search import HeadingFilter
    from ai_collab_worker.eval.e2_rag import TEMP_INDEX_QUESTIONS
    from ai_collab_worker.index.db import IndexDatabase, vector_literal
    from ai_collab_worker.retrieval.store import PostgresRetrievalStore

    admin_url = os.environ.get("P2_GOLDEN_ADMIN_DATABASE_URL", "").strip()
    base_url = os.environ.get("AI_OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
    await _admin_reset(admin_url, worker_url)

    # 正式迁移路径：IndexDatabase.start() → apply_migrations()（ai_worker 身份）。
    db = IndexDatabase(SecretStr(worker_url), min_size=1, max_size=2, run_migrations=True)
    await db.start()
    try:
        assert db.readiness_failure() is None, db.readiness_failure()
        async with await AsyncConnection.connect(worker_url, autocommit=True) as conn:
            cur = await conn.execute("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
            row = await cur.fetchone()
            assert row is not None
            assert row[0] is False, "被测检索路径必须用非超级用户 ai_worker"

        async with httpx.AsyncClient(base_url=base_url, timeout=300.0) as http:

            async def embed(texts: Sequence[str]) -> list[list[float]]:
                resp = await http.post(
                    "/v1/embeddings", json={"model": EMBEDDING_MODEL, "input": list(texts)}
                )
                resp.raise_for_status()
                data = sorted(resp.json()["data"], key=lambda d: d["index"])
                vectors = [list(map(float, d["embedding"])) for d in data]
                assert len(vectors) == len(texts)
                assert all(len(v) == EMBEDDING_DIMENSION for v in vectors)
                return vectors

            project_id = uuid.uuid4()
            generation_id = uuid.uuid4()
            async with await AsyncConnection.connect(worker_url) as conn, conn.transaction():
                await conn.execute(
                    "INSERT INTO ai_index.index_generation (id, embedding_model, dimension, status,"
                    " activated_at) VALUES (%s, %s, %s, 'ACTIVE', now())",
                    (generation_id, EMBEDDING_MODEL, EMBEDDING_DIMENSION),
                )
                for doc in p9["corpus"]:
                    await _index_document(
                        conn, embed, vector_literal, doc, project_id, generation_id
                    )

            store = PostgresRetrievalStore(db)
            questions = {q["id"]: q for q in p9["questions"]}
            # 临时索引口径与导出器/e2_rag 一致：temporary=true 的注入诱饵文档
            # （F4）只在 TEMP_INDEX_QUESTIONS 三题执行时存在于索引中。
            all_doc_ids = [uuid.UUID(str(require_field(doc, "documentId"))) for doc in p9["corpus"]]
            permanent_doc_ids = [
                did
                for doc, did in zip(p9["corpus"], all_doc_ids, strict=True)
                if doc.get("temporary") is not True
            ]
            # Java 基线的 dense/lexical 列表在并列分数处保持小表物理扫描序（≈语料
            # 创建序）；RRF 按位置计分，输入顺序直接影响融合分数。Python 侧生产
            # 口径是 (documentId, chunkNo) 升序，进入融合前先把候选按分数降序 +
            # 语料数组序稳定排序，保证两侧 RRF 输入顺序一致。
            corpus_rank = {
                (str(require_field(doc, "documentId")), int(chunk["chunkNo"])): (doc_pos, chunk_pos)
                for doc_pos, doc in enumerate(p9["corpus"])
                for chunk_pos, chunk in enumerate(doc["chunks"])
            }

            def in_corpus_order(candidates: Sequence[Any], score: Callable[[Any], float | None]):
                return sorted(
                    candidates,
                    key=lambda c: (
                        -(score(c) or 0.0),
                        corpus_rank.get((str(c.document_id), c.chunk_no), (10**9, 10**9)),
                    ),
                )

            per_pair = Counter((c["questionId"], c["mode"]) for c in rrf_cases)
            results: list[dict[str, Any]] = []
            for case in rrf_cases:
                qid, mode = case["questionId"], case["mode"]
                base = {"questionId": qid, "mode": mode}
                if per_pair[(qid, mode)] != 1:
                    # 多查询（子查询）下夹具未记录每次 fuse 对应的查询串，无法确定输入查询。
                    results.append({**base, "status": "not_compared_multi_query"})
                    continue
                q = questions[qid]
                run = q["qa" if mode == "QA" else "agent"]
                query = run.get("effectiveQuery") or q["query"]
                # 契约 §3-2：P2 接口取消"空集合=全项目"语义，Java 门面对全局问题
                # 下发全部可见文档；temporary 文档的可见性按导出器的临时索引口径收窄。
                scope = [uuid.UUID(d) for d in q["scopeDocumentIds"]]
                doc_ids = scope or (
                    all_doc_ids if qid in TEMP_INDEX_QUESTIONS else permanent_doc_ids
                )
                java_dense, java_lexical = case["denseInputs"], case["lexicalInputs"]
                (qvec,) = await embed([query])
                # Java 基线的 dense 是小表顺序扫描的精确排序；生产 HNSW 在 LIMIT 截断处
                # 是近似召回，边界候选可能被换出（契约 Q10 接受该近似，R2-5 验证的是
                # 算法等价而非索引召回率）。取作用域内全部非父块做完整排序后，按
                # R2-5/R2-5a 与 Java 截断输出比对；融合池取完整排序的精确 top-N 切片。
                scope_set = set(doc_ids)
                dense_pool = sum(
                    1
                    for doc in p9["corpus"]
                    if uuid.UUID(str(require_field(doc, "documentId"))) in scope_set
                    for chunk in doc["chunks"]
                    if str(chunk.get("chunkType") or "STANDARD") != "PARENT"
                )
                py_dense_full = in_corpus_order(
                    await store.search_dense(
                        generation_id=generation_id,
                        project_id=project_id,
                        query_vector=qvec,
                        document_ids=doc_ids,
                        top_k=dense_pool,
                        heading_filter=HeadingFilter(),
                    ),
                    lambda c: c.dense,
                )
                py_dense = py_dense_full[: len(java_dense)]
                py_lexical = in_corpus_order(
                    await store.search_lexical(
                        generation_id=generation_id,
                        project_id=project_id,
                        query=query,
                        document_ids=doc_ids,
                        top_k=len(java_lexical),
                        heading_filter=HeadingFilter(),
                    ),
                    lambda c: c.lexical,
                )
                dense_cmp = compare_ranked_truncated(
                    [item_from_row(r, (require_score(r, "dense"),)) for r in java_dense],
                    [
                        RankedItem(str(c.document_id), c.chunk_no, (c.dense or 0.0,))
                        for c in py_dense_full
                    ],
                    len(java_dense),
                )
                lexical_cmp = compare_ranked(
                    [item_from_row(r, (require_score(r, "lexical"),)) for r in java_lexical],
                    [
                        RankedItem(str(c.document_id), c.chunk_no, (c.lexical or 0.0,))
                        for c in py_lexical
                    ],
                )
                fused = rrf_fuse(py_dense, py_lexical, len(py_dense) + len(py_lexical))
                fusion_cmp = compare_ranked_truncated(
                    _java_rrf_items(_rows(case, "output")), _py_rrf_items(fused), int(case["topK"])
                )
                if fusion_cmp.equal and dense_cmp.equal and lexical_cmp.equal:
                    status = "equal"
                elif not dense_cmp.equal:
                    status = "embedding_diff"
                elif not lexical_cmp.equal:
                    status = "lexical_retrieval_diff"
                else:
                    status = "fusion_algorithm_diff"
                results.append(
                    {
                        **base,
                        "status": status,
                        "denseMaxAbsDiff": dense_cmp.max_score_diff,
                        "dense": dense_cmp.kind,
                        "lexical": lexical_cmp.kind,
                        "fusion": fusion_cmp.kind,
                        "fusionReason": fusion_cmp.reason,
                    }
                )
            return results
    finally:
        await db.aclose()
        await _admin_reset(admin_url, worker_url)


async def _index_document(
    conn: Any,
    embed: Any,
    vector_literal: Any,
    doc: Mapping[str, Any],
    project_id: uuid.UUID,
    generation_id: uuid.UUID,
) -> None:
    document_id = uuid.UUID(str(require_field(doc, "documentId")))
    snapshot_id = uuid.uuid4()
    chunks = _rows(doc, "chunks")
    # Java：每个文档一次 embed(chunks.content)，网关按 batch_size=32 切批，顺序与切块顺序一致。
    texts = [str(c["content"]) for c in chunks]
    vectors: list[list[float]] = []
    for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
        vectors.extend(await embed(texts[start : start + EMBEDDING_BATCH_SIZE]))
    for chunk, vector in zip(chunks, vectors, strict=True):
        chunk_type = str(chunk.get("chunkType") or "STANDARD")
        embedding = None if chunk_type == "PARENT" else vector_literal(vector)
        await conn.execute(
            "INSERT INTO ai_index.chunk (id, generation_id, project_id, document_id,"
            " body_snapshot_id, chunk_no, chunk_type, parent_chunk_no, heading, content,"
            " content_hash, metadata, embedding) VALUES (%s, %s, %s, %s, %s, %s, %s, NULL, %s, %s,"
            " %s, '{}'::jsonb, %s::public.vector)",
            (
                uuid.uuid4(), generation_id, project_id, document_id, snapshot_id,
                int(chunk["chunkNo"]), chunk_type, chunk.get("heading"), str(chunk["content"]),
                str(chunk["contentHash"]).lower(), embedding,
            ),
        )  # fmt: skip
    await conn.execute(
        "INSERT INTO ai_index.indexed_document (generation_id, project_id, document_id,"
        " body_snapshot_id, row_count, indexed_at) VALUES (%s, %s, %s, %s, %s, now())",
        (generation_id, project_id, document_id, snapshot_id, len(chunks)),
    )


async def _admin_reset(admin_url: str, worker_url: str) -> None:
    """仅用于清理：以管理员身份重建空的 ``ai_index``（属主仍是 ai_worker）。不参与被测检索。"""

    if not admin_url:
        return
    from psycopg import AsyncConnection, sql
    from psycopg.conninfo import conninfo_to_dict

    role = str(conninfo_to_dict(worker_url).get("user") or "ai_worker")
    async with await AsyncConnection.connect(admin_url, autocommit=True) as conn:
        await conn.execute("DROP SCHEMA IF EXISTS ai_index CASCADE")
        await conn.execute(
            sql.SQL("CREATE SCHEMA ai_index AUTHORIZATION {}").format(sql.Identifier(role))
        )
        await conn.execute("REVOKE ALL ON SCHEMA ai_index FROM PUBLIC")


def _summarize_e2e(rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    status = Counter(r["status"] for r in rows)
    dense_diffs = [r["denseMaxAbsDiff"] for r in rows if r.get("denseMaxAbsDiff") is not None]
    return {
        "caseCount": len(rows),
        "statusCount": dict(status),
        "embeddingDiff": {
            "cases": status.get("embedding_diff", 0),
            "denseMaxAbsDiff": max(dense_diffs) if dense_diffs else None,
        },
        "algorithmDiff": {
            "fusionCases": status.get("fusion_algorithm_diff", 0),
            "lexicalRetrievalCases": status.get("lexical_retrieval_diff", 0),
        },
        "cases": list(rows),
    }
