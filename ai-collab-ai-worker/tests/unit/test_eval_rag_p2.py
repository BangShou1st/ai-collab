"""P2 E2（39 题泛化评测）与 E1（DuRetrieval 10 万语料基准）脚本单元测试。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import uuid
from pathlib import Path
from typing import Any

import pytest

from ai_collab_worker.contract.knowledge_search import (
    ChunkType,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse,
    RerankMode,
    SearchDiagnostics,
    SearchHit,
    SearchScores,
)
from ai_collab_worker.eval.canonical import DatasetError
from ai_collab_worker.eval.cli import EXIT_OK, main
from ai_collab_worker.eval.e1_duretrieval import (
    compute_query_score,
    du_document_uuid,
    iter_corpus_passages,
    load_sample_queries,
    select_ten_percent_acl_subset,
    summarize_variant,
)
from ai_collab_worker.eval.e2_rag import (
    E2_TOTAL_FACTS,
    E2_TOTAL_QUESTIONS,
    INJECTION_DOC_FILENAME,
    load_and_verify_e2_dataset,
    run_e2_eval,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
E2_FIXTURES = REPO_ROOT / "tests" / "fixtures" / "documents" / "p9-rag-generalization-01"
E2_SOURCE_MANIFEST = (
    REPO_ROOT
    / "docs"
    / "acceptance-evidence"
    / "2026-10-26"
    / "p9-rag-generalization-01"
    / "source-manifest.json"
)
E1_SAMPLE_MANIFEST = (
    REPO_ROOT / "docs" / "benchmark" / "2026-11-07-rag-v2-public-benchmark" / "sample-manifest.json"
)


def test_e2_loads_frozen_39q_dataset_and_rejects_tamper(tmp_path: Path) -> None:
    dataset = load_and_verify_e2_dataset(E2_FIXTURES, source_manifest=E2_SOURCE_MANIFEST)
    assert len(dataset.documents) == 23
    assert len(dataset.questions) == E2_TOTAL_QUESTIONS
    assert sum(len(q.facts) for q in dataset.questions) == E2_TOTAL_FACTS

    # 篡改一份文档的字节后，SHA-256 校验必须拒绝。
    copied = tmp_path / "e2"
    copied.mkdir()
    for path in E2_FIXTURES.iterdir():
        (copied / path.name).write_bytes(path.read_bytes())
    target_doc = copied / dataset.documents[0].filename
    target_doc.write_text("tampered content", encoding="utf-8")
    with pytest.raises(DatasetError, match="SHA-256"):
        load_and_verify_e2_dataset(copied)


def test_e2_eval_runner_checks_gate_temp_index_and_disclosures() -> None:
    dataset = load_and_verify_e2_dataset(E2_FIXTURES)
    doc_by_id = {doc.document_id: doc for doc in dataset.documents}
    f4_id = next(
        doc.document_id for doc in dataset.documents if doc.filename == INJECTION_DOC_FILENAME
    )
    gen_id = uuid.uuid4()
    snap_id = uuid.uuid4()
    observed_f4: dict[str, bool] = {}

    async def fake_search(req: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
        # 校验 TEMP_INDEX_QUESTIONS 才把 F4 放进可见范围
        if req.intent.value == "KNOWLEDGE_QA":
            observed_f4[req.original_query] = f4_id in req.visible_document_ids
        hits: list[SearchHit] = []
        for idx, doc_id in enumerate(req.visible_document_ids[: req.top_k], start=1):
            doc = doc_by_id[doc_id]
            hits.append(
                SearchHit(
                    rank=idx,
                    document_id=doc.document_id,
                    chunk_no=0,
                    content_hash=hashlib.sha256(doc.content.encode("utf-8")).hexdigest(),
                    chunk_type=ChunkType.STANDARD,
                    parent_chunk_no=None,
                    heading=doc.filename,
                    content=doc.content,
                    body_snapshot_id=snap_id,
                    scores=SearchScores(dense=0.9, lexical=0.8, final=0.9),
                )
            )
        return KnowledgeSearchResponse(
            request_id=req.request_id,
            generation_id=gen_id,
            strategy=req.strategy,
            hits=tuple(hits),
            diagnostics=SearchDiagnostics(
                dense_candidates=len(hits),
                lexical_candidates=0,
                iterative_scan=True,
                rerank=RerankMode.HEURISTIC,
                latency_ms=1,
            ),
        )

    # 所有 visible docs 都返回全文时未必都在 topK=8；用命中匹配测试精确返回目标文档的情况
    facts_by_query = {q.query: q.facts for q in dataset.questions}

    async def oracle_search(req: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
        if req.intent.value == "KNOWLEDGE_QA":
            observed_f4[req.original_query] = f4_id in req.visible_document_ids
        wanted = {f.document for f in facts_by_query.get(req.original_query, ())}
        picked = [
            doc_id for doc_id in req.visible_document_ids if doc_by_id[doc_id].filename in wanted
        ][: req.top_k]
        hits = [
            SearchHit(
                rank=idx,
                document_id=doc_id,
                chunk_no=0,
                content_hash=hashlib.sha256(doc_by_id[doc_id].content.encode("utf-8")).hexdigest(),
                chunk_type=ChunkType.STANDARD,
                parent_chunk_no=None,
                heading=doc_by_id[doc_id].filename,
                content=doc_by_id[doc_id].content,
                body_snapshot_id=snap_id,
                scores=SearchScores(dense=0.9, lexical=0.8, final=0.9),
            )
            for idx, doc_id in enumerate(picked, start=1)
        ]
        return KnowledgeSearchResponse(
            request_id=req.request_id,
            generation_id=gen_id,
            strategy=req.strategy,
            hits=tuple(hits),
            diagnostics=SearchDiagnostics(
                dense_candidates=len(hits),
                lexical_candidates=0,
                iterative_scan=True,
                rerank=RerankMode.HEURISTIC,
                latency_ms=1,
            ),
        )

    report_pass = asyncio.run(
        run_e2_eval(
            dataset,
            search_runner=oracle_search,
            project_id=uuid.uuid4(),
            user_id=uuid.uuid4(),
            embedding_model="qwen3-embedding:0.6b",
            pgvector_version="0.8.6",
        )
    )
    assert report_pass["gate"]["passed"] is True
    assert report_pass["gate"]["retainedFacts"] == 54
    assert report_pass["disclosures"]["datasetKind"] == "synthetic_frozen_39"
    assert report_pass["disclosures"]["matcher"] == "substring_marker"
    assert report_pass["disclosures"]["noBestOfNRerun"] is True
    # 确认只有临时索引题才把 F4 纳入可见集合
    temp_q = next(q for q in dataset.questions if q.id == "H-G07-INJECT")
    normal_q = next(q for q in dataset.questions if q.id == "G01-A1-BEG")
    assert observed_f4[temp_q.query] is True
    assert observed_f4[normal_q.query] is False

    # 无命中时（空结果）：门禁不通过，且统计空上下文数量
    async def empty_search(req: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
        return KnowledgeSearchResponse(
            request_id=req.request_id,
            generation_id=gen_id,
            strategy=req.strategy,
            hits=(),
            diagnostics=SearchDiagnostics(
                dense_candidates=0,
                lexical_candidates=0,
                iterative_scan=True,
                rerank=RerankMode.HEURISTIC,
                latency_ms=1,
            ),
        )

    report_fail = asyncio.run(
        run_e2_eval(
            dataset,
            search_runner=empty_search,
            project_id=uuid.uuid4(),
            user_id=uuid.uuid4(),
        )
    )
    assert report_fail["gate"]["passed"] is False
    assert report_fail["summary"]["qaEmptyContextCount"] == 39
    _ = fake_search


def test_e1_duretrieval_metrics_manifest_and_cli_commands(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    queries = load_sample_queries(E1_SAMPLE_MANIFEST)
    assert len(queries) == 300

    corpus_file = tmp_path / "corpus.jsonl"
    corpus_file.write_text(
        "\n".join(
            [
                json.dumps({"id": "p1", "text": "段落一"}),
                json.dumps({"id": "p1", "text": "重复 id"}),
                json.dumps({"id": "p2", "text": "段落二"}),
                json.dumps({"id": "p3", "text": ""}),
            ]
        ),
        encoding="utf-8",
    )
    passages = list(iter_corpus_passages(corpus_file))
    assert passages == [("p1", "段落一"), ("p2", "段落二")]
    assert du_document_uuid("p1") == du_document_uuid("p1")

    q = queries[0]
    rel_list = sorted(q.relevant_pids)
    scored = compute_query_score(
        q,
        ["miss1", rel_list[0], rel_list[0], "miss2", rel_list[1]],
        latency_ms=12.5,
    )
    assert scored.hits_at_5 == 2
    assert scored.reciprocal_rank == pytest.approx(0.5)
    assert scored.success_at_10 is True
    summary = summarize_variant([scored])
    assert summary["recallAt5"] == pytest.approx(2 / len(q.relevant_pids))
    assert summary["precisionAt5"] == pytest.approx(0.4)

    subset = select_ten_percent_acl_subset(
        [f"p{i}" for i in range(100)],
        frozenset({"p42", "p88"}),
    )
    assert len(subset) == 10
    assert {"p42", "p88"}.issubset(subset)

    out_e2 = tmp_path / "e2_verify.json"
    assert (
        main(
            [
                "e2-rag",
                "--fixtures",
                str(E2_FIXTURES),
                "--source-manifest",
                str(E2_SOURCE_MANIFEST),
                "--verify-only",
                "-o",
                str(out_e2),
            ]
        )
        == EXIT_OK
    )
    e2_payload: dict[str, Any] = json.loads(out_e2.read_text(encoding="utf-8"))
    assert e2_payload == {
        **e2_payload,
        "verified": True,
        "documents": 23,
        "questions": 39,
        "facts": 54,
    }

    out_e1 = tmp_path / "e1_preflight.json"
    assert (
        main(
            [
                "e1-duretrieval",
                "--manifest",
                str(E1_SAMPLE_MANIFEST),
                "--corpus-jsonl",
                str(corpus_file),
                "-o",
                str(out_e1),
            ]
        )
        == EXIT_OK
    )
    e1_payload = json.loads(out_e1.read_text(encoding="utf-8"))
    assert e1_payload["sampledQueries"] == 300
    assert e1_payload["corpusPassages"] == 2
    _ = capsys.readouterr()
