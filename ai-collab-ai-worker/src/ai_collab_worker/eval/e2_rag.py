"""P2 质量基准 E2（39 题泛化评测：dev 21 题 + holdout 18 题，共 54 项必要事实）。

契约 §9.1 硬规则：
- 按 SHA-256 校验冻结语料清单与 23 篇文档（不复制、不修改语料）；
- QA 口径（``topK=8``、``maxSources=5``、``maxPerDoc=2``、``budget=8000`` code points）
  必要事实保留 **≥ 52/54** 为通过门槛，同时报告空上下文数；
- Agent 口径（``candidatePool=20``、``limit=8``、``quoteCap=600``）单独统计作参考，不作门槛；
- 报告显式标注全部方法学披露字段（自建合成题集、字符串匹配判定、embedding 模型、pgvector 版本、
  HNSW 参数、rerank 取值），绝不通过重跑挑选最好成绩。
"""

from __future__ import annotations

import hashlib
import json
import uuid
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.contract.knowledge_search import (
    ChunkType,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse,
    RerankMode,
    RetrievalStrategy,
    SearchHit,
    SearchIntent,
    parse_search_request,
)
from ai_collab_worker.eval.canonical import DatasetError
from ai_collab_worker.retrieval.javatext import is_blank

QA_TOP_K: Final[int] = 8
QA_CANDIDATE_POOL: Final[int] = 20
QA_MAX_SOURCES: Final[int] = 5
QA_MAX_PER_DOC: Final[int] = 2
QA_PROMPT_BUDGET: Final[int] = 8000
QA_MIN_SIMILARITY: Final[float] = 0.15

AGENT_TOP_K: Final[int] = 8
AGENT_CANDIDATE_POOL: Final[int] = 20
AGENT_MAX_SOURCES: Final[int] = 8
AGENT_QUOTE_CAP: Final[int] = 600

E2_GATE_PASS_COUNT: Final[int] = 52
E2_TOTAL_FACTS: Final[int] = 54
E2_TOTAL_QUESTIONS: Final[int] = 39

INJECTION_DOC_FILENAME: Final[str] = "F4-外部审计参考摘录-未审核来源.md"
TEMP_INDEX_QUESTIONS: Final[frozenset[str]] = frozenset(
    {"H-G07-NONE", "H-G07-SIMILAR", "H-G07-INJECT"}
)


@dataclass(frozen=True)
class CorpusDocument:
    filename: str
    family: str
    dataset_split: str
    sha256: str
    content: str
    document_id: uuid.UUID


@dataclass(frozen=True)
class ExpectedFact:
    document: str
    marker: str
    fact: str


@dataclass(frozen=True)
class EvalQuestion:
    id: str
    dataset_split: str
    family: str
    dimension: str
    query: str
    scope: tuple[str, ...]
    abstain: bool
    inject: bool
    facts: tuple[ExpectedFact, ...]
    distract_facts: tuple[ExpectedFact, ...]
    distractor_docs: tuple[str, ...]


@dataclass(frozen=True)
class E2Dataset:
    fixtures_dir: Path
    documents: tuple[CorpusDocument, ...]
    questions: tuple[EvalQuestion, ...]
    manifest_digests: dict[str, str]


@dataclass(frozen=True)
class AssembledSource:
    document_id: uuid.UUID
    chunk_no: int
    content: str
    quote: str
    score: float


SearchRunner = Callable[[KnowledgeSearchRequest], Awaitable[KnowledgeSearchResponse]]


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load_and_verify_e2_dataset(
    fixtures_dir: Path,
    *,
    source_manifest: Path | None = None,
) -> E2Dataset:
    """加载冻结的 23 篇语料与 39 题清单，并逐字节校验 SHA-256。"""

    if not fixtures_dir.is_dir():
        msg = f"冻结语料目录不存在: {fixtures_dir}"
        raise DatasetError(msg)

    corpus_path = fixtures_dir / "corpus-manifest.json"
    dev_path = fixtures_dir / "question-manifest.json"
    holdout_path = fixtures_dir / "question-manifest-holdout.json"
    for req_file in (corpus_path, dev_path, holdout_path):
        if not req_file.is_file():
            msg = f"缺少冻结清单文件: {req_file.name}"
            raise DatasetError(msg)

    manifest_digests: dict[str, str] = {
        corpus_path.name: sha256_bytes(corpus_path.read_bytes()),
        dev_path.name: sha256_bytes(dev_path.read_bytes()),
        holdout_path.name: sha256_bytes(holdout_path.read_bytes()),
    }
    if source_manifest is not None:
        if not source_manifest.is_file():
            msg = f"source-manifest 不存在: {source_manifest}"
            raise DatasetError(msg)
        src_obj = json.loads(source_manifest.read_text(encoding="utf-8"))
        for entry in src_obj.get("files", ()):
            rel = str(entry.get("file", "")).replace("\\", "/")
            expected = str(entry.get("sha256", "")).lower()
            name = Path(rel).name
            if name in manifest_digests and manifest_digests[name] != expected:
                msg = f"manifest SHA-256 校验失败: {name}"
                raise DatasetError(msg)

    corpus_data = json.loads(corpus_path.read_text(encoding="utf-8"))
    documents: list[CorpusDocument] = []
    for entry in corpus_data.get("documents", ()):
        filename = str(entry["filename"])
        expected_hash = str(entry["sha256"]).lower()
        doc_path = fixtures_dir / filename
        if not doc_path.is_file():
            msg = f"冻结语料缺失: {filename}"
            raise DatasetError(msg)
        raw = doc_path.read_bytes()
        actual_hash = sha256_bytes(raw)
        if actual_hash != expected_hash:
            msg = f"冻结语料 SHA-256 不一致: {filename} ({actual_hash} != {expected_hash})"
            raise DatasetError(msg)
        content = raw.decode("utf-8")
        doc_uuid = uuid.uuid5(uuid.NAMESPACE_URL, f"ai-collab:e2:{filename}")
        documents.append(
            CorpusDocument(
                filename=filename,
                family=str(entry.get("family", "")),
                dataset_split=str(entry.get("set", "")),
                sha256=actual_hash,
                content=content,
                document_id=doc_uuid,
            )
        )

    questions: list[EvalQuestion] = []
    for q_file in (dev_path, holdout_path):
        q_data = json.loads(q_file.read_text(encoding="utf-8"))
        for row in q_data.get("questions", ()):
            questions.append(
                EvalQuestion(
                    id=str(row["id"]),
                    dataset_split=str(row.get("set", "")),
                    family=str(row.get("family", "")),
                    dimension=str(row.get("dimension", "")),
                    query=str(row["query"]),
                    scope=tuple(str(s) for s in row.get("scope", ())),
                    abstain=bool(row.get("abstain", False)),
                    inject=bool(row.get("inject", False)),
                    facts=tuple(
                        ExpectedFact(
                            document=str(f["document"]),
                            marker=str(f["marker"]),
                            fact=str(f.get("fact", "")),
                        )
                        for f in row.get("facts", ())
                    ),
                    distract_facts=tuple(
                        ExpectedFact(
                            document=str(f["document"]),
                            marker=str(f["marker"]),
                            fact=str(f.get("note", "")),
                        )
                        for f in row.get("distractFacts", ())
                    ),
                    distractor_docs=tuple(str(d) for d in row.get("distractorDocs", ())),
                )
            )

    if len(questions) != E2_TOTAL_QUESTIONS:
        msg = f"冻结题目数期望 {E2_TOTAL_QUESTIONS}，实际 {len(questions)}"
        raise DatasetError(msg)
    total_facts = sum(len(q.facts) for q in questions)
    if total_facts != E2_TOTAL_FACTS:
        msg = f"冻结必要事实数期望 {E2_TOTAL_FACTS}，实际 {total_facts}"
        raise DatasetError(msg)

    return E2Dataset(
        fixtures_dir=fixtures_dir,
        documents=tuple(documents),
        questions=tuple(questions),
        manifest_digests=manifest_digests,
    )


def _hit_body(hit: SearchHit) -> str:
    if hit.context is not None and not is_blank(hit.context.text):
        return hit.context.text
    return hit.content


def _filter_suppressed_and_low(
    hits: Sequence[SearchHit],
    *,
    min_similarity: float,
) -> list[SearchHit]:
    suppressed = {
        (hit.document_id, hit.parent_chunk_no)
        for hit in hits
        if hit.chunk_type is ChunkType.SENTENCE and hit.parent_chunk_no is not None
    }
    eligible: list[SearchHit] = []
    for hit in hits:
        if (hit.document_id, hit.chunk_no) in suppressed:
            continue
        if hit.scores.final < min_similarity:
            continue
        if is_blank(hit.content):
            continue
        eligible.append(hit)
    return eligible


def select_qa_sources(
    hits: Sequence[SearchHit],
    *,
    min_similarity: float = QA_MIN_SIMILARITY,
    max_sources: int = QA_MAX_SOURCES,
    max_per_doc: int = QA_MAX_PER_DOC,
    budget_code_points: int = QA_PROMPT_BUDGET,
) -> list[AssembledSource]:
    """复刻 Java ``KnowledgeContextBuilder.buildBalancedWithDiagnostics`` 的来源选择。"""

    eligible = _filter_suppressed_and_low(hits, min_similarity=min_similarity)
    if not eligible:
        return []

    selected: list[SearchHit] = []
    per_doc: dict[uuid.UUID, int] = {}
    deferred: list[SearchHit] = []
    seen_keys: set[tuple[uuid.UUID, int]] = set()

    for hit in eligible:
        if len(selected) >= max_sources:
            break
        key = (hit.document_id, hit.chunk_no)
        if key in seen_keys:
            continue
        count = per_doc.get(hit.document_id, 0)
        if count < max_per_doc:
            seen_keys.add(key)
            selected.append(hit)
            per_doc[hit.document_id] = count + 1
        else:
            deferred.append(hit)

    for hit in deferred:
        if len(selected) >= max_sources:
            break
        key = (hit.document_id, hit.chunk_no)
        if key not in seen_keys:
            seen_keys.add(key)
            selected.append(hit)

    assembled: list[AssembledSource] = []
    used_len = 0
    for hit in selected:
        body = _hit_body(hit)
        block_len = len(body) + len(hit.heading or "") + 32
        if assembled and used_len + block_len > budget_code_points:
            break
        used_len += block_len
        assembled.append(
            AssembledSource(
                document_id=hit.document_id,
                chunk_no=hit.chunk_no,
                content=body,
                quote=hit.content[:AGENT_QUOTE_CAP],
                score=hit.scores.final,
            )
        )
    return assembled


def select_agent_sources(
    hits: Sequence[SearchHit],
    *,
    max_sources: int = AGENT_MAX_SOURCES,
    quote_cap: int = AGENT_QUOTE_CAP,
) -> list[AssembledSource]:
    """复刻 Agent 工具参考口径：最多 8 条来源，每条截取 600 code points 引文。"""

    eligible = _filter_suppressed_and_low(hits, min_similarity=QA_MIN_SIMILARITY)
    selected: list[AssembledSource] = []
    seen_keys: set[tuple[uuid.UUID, int]] = set()
    for hit in eligible:
        if len(selected) >= max_sources:
            break
        key = (hit.document_id, hit.chunk_no)
        if key in seen_keys:
            continue
        seen_keys.add(key)
        body = _hit_body(hit)
        selected.append(
            AssembledSource(
                document_id=hit.document_id,
                chunk_no=hit.chunk_no,
                content=body,
                quote=body[:quote_cap],
                score=hit.scores.final,
            )
        )
    return selected


def _classify_fact_stage(
    fact: ExpectedFact,
    doc_id: uuid.UUID,
    *,
    hits: Sequence[SearchHit],
    sources: Sequence[AssembledSource],
    use_quote: bool,
) -> str:
    for src in sources:
        if src.document_id == doc_id:
            haystack = src.quote if use_quote else src.content
            if fact.marker in haystack:
                return "RETAINED"
    for src in sources:
        if src.document_id == doc_id:
            return "BODY_TRUNCATION"
    if any(hit.document_id == doc_id and fact.marker in _hit_body(hit) for hit in hits):
        return "SELECTION_MISS"
    return "RETRIEVAL_MISS"


async def run_e2_eval(
    dataset: E2Dataset,
    *,
    search_runner: SearchRunner,
    project_id: uuid.UUID,
    user_id: uuid.UUID,
    strategy: RetrievalStrategy = RetrievalStrategy.HYBRID,
    rerank: RerankMode = RerankMode.HEURISTIC,
    embedding_model: str = "unspecified",
    pgvector_version: str = "unspecified",
    hnsw_params: str = "default (m=16, ef_construction=64) + iterative_scan=relaxed_order",
) -> dict[str, Any]:
    """执行完整 39 题单轮评测并返回合规报告（绝不重跑挑高分）。"""

    doc_by_name = {doc.filename: doc for doc in dataset.documents}
    permanent_ids = [
        doc.document_id for doc in dataset.documents if doc.filename != INJECTION_DOC_FILENAME
    ]
    all_ids = [doc.document_id for doc in dataset.documents]

    qa_retained = 0
    qa_total = 0
    qa_empty_contexts = 0
    agent_retained = 0
    question_reports: list[dict[str, Any]] = []

    for question in dataset.questions:
        active_pool = all_ids if question.id in TEMP_INDEX_QUESTIONS else permanent_ids
        if question.scope:
            visible_ids = [
                doc_by_name[name].document_id for name in question.scope if name in doc_by_name
            ]
        else:
            visible_ids = active_pool

        qa_req = parse_search_request(
            {
                "requestId": str(uuid.uuid4()),
                "projectId": str(project_id),
                "userId": str(user_id),
                "intent": SearchIntent.KNOWLEDGE_QA.value,
                "strategy": strategy.value,
                "queries": [{"text": question.query, "role": "PRIMARY"}],
                "originalQuery": question.query,
                "visibleDocumentIds": [str(uid) for uid in visible_ids],
                "topK": QA_TOP_K,
                "candidatePool": QA_CANDIDATE_POOL,
                "headingFilter": {"exact": None, "contains": None},
                "rerank": rerank.value,
                "expand": {"sentenceWindow": True, "parent": True},
                "deadlineMs": 15000,
            }
        )
        qa_resp = await search_runner(qa_req)
        qa_sources = select_qa_sources(qa_resp.hits)
        if not qa_sources:
            qa_empty_contexts += 1

        agent_req = parse_search_request(
            {
                "requestId": str(uuid.uuid4()),
                "projectId": str(project_id),
                "userId": str(user_id),
                "intent": SearchIntent.AGENT_RESEARCH.value,
                "strategy": strategy.value,
                "queries": [{"text": question.query, "role": "PRIMARY"}],
                "originalQuery": question.query,
                "visibleDocumentIds": [str(uid) for uid in visible_ids],
                "topK": AGENT_TOP_K,
                "candidatePool": AGENT_CANDIDATE_POOL,
                "headingFilter": {"exact": None, "contains": None},
                "rerank": rerank.value,
                "expand": {"sentenceWindow": True, "parent": True},
                "deadlineMs": 15000,
            }
        )
        agent_resp = await search_runner(agent_req)
        agent_sources = select_agent_sources(agent_resp.hits)

        fact_results: list[dict[str, Any]] = []
        for fact in question.facts:
            qa_total += 1
            doc_id = doc_by_name[fact.document].document_id
            qa_stage = _classify_fact_stage(
                fact, doc_id, hits=qa_resp.hits, sources=qa_sources, use_quote=False
            )
            agent_stage = _classify_fact_stage(
                fact, doc_id, hits=agent_resp.hits, sources=agent_sources, use_quote=True
            )
            if qa_stage == "RETAINED":
                qa_retained += 1
            if agent_stage == "RETAINED":
                agent_retained += 1
            fact_results.append(
                {
                    "document": fact.document,
                    "marker": fact.marker,
                    "qaStage": qa_stage,
                    "agentStage": agent_stage,
                }
            )

        false_evidence = [
            {
                "document": name,
                "inQaContext": any(
                    src.document_id == doc_by_name[name].document_id for src in qa_sources
                ),
            }
            for name in question.distractor_docs
            if name in doc_by_name
        ]
        question_reports.append(
            {
                "id": question.id,
                "set": question.dataset_split,
                "dimension": question.dimension,
                "abstain": question.abstain,
                "qaSourceCount": len(qa_sources),
                "agentSourceCount": len(agent_sources),
                "facts": fact_results,
                "falseEvidence": false_evidence,
            }
        )

    passed = qa_total == E2_TOTAL_FACTS and qa_retained >= E2_GATE_PASS_COUNT
    return {
        "evaluation": "P2-E2-39Q-RAG-QUALITY",
        "gate": {
            "metric": "qaRetainedFacts",
            "threshold": E2_GATE_PASS_COUNT,
            "totalFacts": qa_total,
            "retainedFacts": qa_retained,
            "passed": passed,
        },
        "summary": {
            "questionCount": len(dataset.questions),
            "qaRetainedFacts": qa_retained,
            "qaTotalFacts": qa_total,
            "qaEmptyContextCount": qa_empty_contexts,
            "agentReferenceRetainedFacts": agent_retained,
            "agentReferenceNonGating": True,
        },
        "disclosures": {
            "datasetKind": "synthetic_frozen_39",
            "matcher": "substring_marker",
            "embeddingModel": embedding_model,
            "pgvectorVersion": pgvector_version,
            "hnswParams": hnsw_params,
            "rerank": rerank.value,
            "strategy": strategy.value,
            "noBestOfNRerun": True,
            "manifestSha256": dataset.manifest_digests,
        },
        "questions": question_reports,
    }


__all__ = [
    "AGENT_CANDIDATE_POOL",
    "AGENT_MAX_SOURCES",
    "AGENT_QUOTE_CAP",
    "AGENT_TOP_K",
    "E2_GATE_PASS_COUNT",
    "E2_TOTAL_FACTS",
    "E2_TOTAL_QUESTIONS",
    "INJECTION_DOC_FILENAME",
    "QA_CANDIDATE_POOL",
    "QA_MAX_PER_DOC",
    "QA_MAX_SOURCES",
    "QA_MIN_SIMILARITY",
    "QA_PROMPT_BUDGET",
    "QA_TOP_K",
    "AssembledSource",
    "CorpusDocument",
    "E2Dataset",
    "EvalQuestion",
    "ExpectedFact",
    "load_and_verify_e2_dataset",
    "run_e2_eval",
    "select_agent_sources",
    "select_qa_sources",
]
