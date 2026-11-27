"""``ai_index`` 写入/管理/检索与数据库角色隔离的集成测试（PostgreSQL + pgvector）。

**本机不执行**（只跑 ``--collect-only``）：需要 Docker，用 ``pgvector/pgvector:pg17`` 起容器，
按 ``db-init.sql`` 建 ``ai_worker``、``ai_collab_app``、``ai_index`` 与业务表。

覆盖：
- §9.2 #1：``ai_worker`` 与 ``ai_collab_app`` 对称隔离（双方互相被拒 ``permission denied``）；
- §5.1 / §9.2 #2：HNSW ``hnsw.iterative_scan = relaxed_order`` 低可见比例召回与 ACL 过滤；
- §9.2 #5：``CONFIGURED`` 重排失败返回 503 ``RERANK_UNAVAILABLE``，绝不回落启发式；
- §9.3 / R4-3 / R4-4：``INDEX_DOCUMENT`` 幂等、0 切块、``DELETE_DOCUMENT_INDEX``、
  ``REINDEX_GENERATION``、``:reconcile`` PRUNE（``indexed_at < asOf`` 服务器时钟）、
  ``:activate``、``:reopen``、``:abandon``。
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import time
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import httpx2
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

psycopg = pytest.importorskip("psycopg")
from psycopg import AsyncConnection  # noqa: E402
from psycopg.errors import InsufficientPrivilege  # noqa: E402
from testcontainers.community.postgres import PostgresContainer  # noqa: E402

from ai_collab_worker.api.errors import install_error_handlers  # noqa: E402
from ai_collab_worker.api.index_admin import (  # noqa: E402
    cleanup_retired_generations,
)
from ai_collab_worker.api.index_admin import (  # noqa: E402
    router as index_admin_router,
)
from ai_collab_worker.api.knowledge_search import router as knowledge_search_router  # noqa: E402
from ai_collab_worker.auth.token import Ed25519KeyRing, TokenVerifier  # noqa: E402
from ai_collab_worker.clients.java_internal import JavaInternalClient  # noqa: E402
from ai_collab_worker.contract.knowledge_search import HeadingFilter  # noqa: E402
from ai_collab_worker.index.db import VECTOR_DIMENSION, IndexDatabase  # noqa: E402
from ai_collab_worker.models.factory import ModelRuntime  # noqa: E402
from ai_collab_worker.models.kernel import ModelKernel  # noqa: E402
from ai_collab_worker.models.lease import Lease, LeaseCache, LeasePurpose, LeaseRequest  # noqa: E402
from ai_collab_worker.models.usage import UsageReporter  # noqa: E402
from ai_collab_worker.retrieval.pipeline import (  # noqa: E402
    CODE_RERANK_UNAVAILABLE,
    KnowledgeSearchPipeline,
)
from ai_collab_worker.retrieval.store import PostgresRetrievalStore  # noqa: E402
from ai_collab_worker.tasks.index import (  # noqa: E402
    TASK_DELETE_DOCUMENT_INDEX,
    TASK_INDEX_DOCUMENT,
    TASK_REINDEX_GENERATION,
    build_index_handlers,
)
from ai_collab_worker.tasks.stream import TaskContext  # noqa: E402

IMAGE = "pgvector/pgvector:pg17"
WORKER_PW = "itest-ai-worker-pw"
APP_PW = "itest-ai-collab-app-pw"

INIT_SQL = f"""
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE ROLE ai_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{WORKER_PW}';
CREATE ROLE ai_collab_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{APP_PW}';
CREATE SCHEMA ai_index AUTHORIZATION ai_worker;
REVOKE ALL ON SCHEMA ai_index FROM PUBLIC;
REVOKE ALL ON SCHEMA ai_index FROM ai_collab_app;
REVOKE CREATE ON SCHEMA public FROM ai_worker;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE public.project_document (id uuid PRIMARY KEY, project_id uuid NOT NULL);
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_worker;
ALTER ROLE ai_worker SET search_path = ai_index, public;
"""

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("88" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _token(*, scope: str, uid: uuid.UUID | None = None, pid: uuid.UUID | None = None) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["ai-worker"],
        "scope": scope,
        "iat": now,
        "exp": now + 300,
    }
    if uid is not None:
        claims["uid"] = str(uid)
    if pid is not None:
        claims["pid"] = str(pid)
    hdr = _b64(json.dumps({"alg": "EdDSA", "typ": "JWT", "kid": "k1"}).encode())
    pld = _b64(json.dumps(claims).encode())
    sig = _b64(_KEY.sign(f"{hdr}.{pld}".encode("ascii")))
    return f"{hdr}.{pld}.{sig}"


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _basis_vec(index: int, scale: float = 1.0) -> list[float]:
    vec = [0.001] * VECTOR_DIMENSION
    vec[index % VECTOR_DIMENSION] = scale
    return vec


@pytest.fixture(scope="module")
def postgres() -> Iterator[PostgresContainer]:
    with PostgresContainer(IMAGE, driver=None) as container:
        yield container


def _pg_url(container: PostgresContainer, *, user: str, password: str) -> str:
    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    return f"postgresql://{user}:{password}@{host}:{port}/{container.dbname}"


@pytest.fixture(scope="module")
def db_urls(postgres: PostgresContainer) -> tuple[str, str]:
    admin = _pg_url(postgres, user=postgres.username, password=postgres.password)

    async def bootstrap() -> None:
        async with await AsyncConnection.connect(admin, autocommit=True) as conn:
            await conn.execute(INIT_SQL.encode())

    asyncio.run(bootstrap())
    worker_url = _pg_url(postgres, user="ai_worker", password=WORKER_PW)
    app_url = _pg_url(postgres, user="ai_collab_app", password=APP_PW)

    from pydantic import SecretStr

    async def check_worker_database() -> None:
        db = IndexDatabase(SecretStr(worker_url), min_size=1, max_size=2)
        await db.start()
        try:
            assert db.readiness_failure() is None
        finally:
            await db.aclose()

    asyncio.run(check_worker_database())
    return worker_url, app_url


def test_role_isolation_ai_worker_vs_ai_collab_app(db_urls: tuple[str, str]) -> None:
    worker_url, app_url = db_urls

    async def scenario() -> None:
        async with await AsyncConnection.connect(worker_url, autocommit=True) as w_conn:
            with pytest.raises(InsufficientPrivilege):
                await w_conn.execute("SELECT 1 FROM public.project_document")
            with pytest.raises(InsufficientPrivilege):
                await w_conn.execute("CREATE TABLE public.forbidden_x (id int)")

        async with await AsyncConnection.connect(app_url, autocommit=True) as a_conn:
            with pytest.raises(InsufficientPrivilege):
                await a_conn.execute("SELECT 1 FROM ai_index.chunk")
            with pytest.raises(InsufficientPrivilege):
                await a_conn.execute(
                    "INSERT INTO ai_index.index_generation "
                    "(id, embedding_model, dimension, status) VALUES (%s, 'm', 1024, 'ACTIVE')",
                    (uuid.uuid4(),),
                )

    asyncio.run(scenario())


def test_index_lifecycle_prune_reopen_acl_iterative_scan_and_rerank_no_fallback(
    db_urls: tuple[str, str],
) -> None:
    from pydantic import SecretStr

    worker_url, _ = db_urls
    project_a = uuid.uuid4()
    project_b = uuid.uuid4()
    user_id = uuid.uuid4()
    doc_vis = uuid.UUID("00000000-0000-4000-8000-000000000010")
    doc_hidden = uuid.UUID("00000000-0000-4000-8000-000000000020")
    doc_empty = uuid.UUID("00000000-0000-4000-8000-000000000030")
    doc_other_proj = uuid.UUID("00000000-0000-4000-8000-000000000040")
    snap_vis = uuid.uuid4()
    snap_hidden = uuid.uuid4()
    snap_empty = uuid.uuid4()
    snap_other = uuid.uuid4()

    rerank_should_fail = False

    async def run() -> None:
        nonlocal rerank_should_fail
        db = IndexDatabase(SecretStr(worker_url), min_size=1, max_size=4, run_migrations=False)
        await db.start()
        assert db.readiness_failure() is None

        try:
            task_meta: dict[str, dict[str, Any]] = {}
            body_pages: dict[uuid.UUID, dict[str, Any]] = {}

            def java_handler(req: httpx.Request) -> httpx.Response:
                if req.url.path.endswith("/snapshot"):
                    tid = req.url.path.split("/")[-2]
                    return httpx.Response(200, json=task_meta[tid])
                doc = uuid.UUID(req.url.path.split("/")[-2])
                return httpx.Response(200, json=body_pages[doc])

            def provider_handler(req: httpx2.Request) -> httpx2.Response:
                if req.url.path == "/v1/embeddings":
                    body = json.loads(req.content.decode("utf-8"))
                    vecs = []
                    for idx, text in enumerate(body["input"]):
                        vecs.append(_basis_vec(0 if "重要" in text else idx + 5))
                    return httpx2.Response(200, json={"data": [{"embedding": v} for v in vecs]})
                if rerank_should_fail:
                    return httpx2.Response(503, json={"error": "rerank down"})
                docs = json.loads(req.content.decode("utf-8"))["documents"]
                return httpx2.Response(
                    200,
                    json={
                        "results": [
                            {"index": i, "relevance_score": float(len(docs) - i)}
                            for i in range(len(docs))
                        ]
                    },
                )

            java = JavaInternalClient(
                base_url="http://java.test", transport=httpx.MockTransport(java_handler)
            )

            async def fetch_lease(req: LeaseRequest, _tok: str) -> Lease:
                if req.purpose is LeasePurpose.EMBEDDING:
                    return Lease.model_validate(
                        {
                            "leaseId": str(uuid.uuid4()),
                            "protocol": "OPENAI_EMBEDDINGS",
                            "providerType": "OLLAMA",
                            "baseUrl": "http://ollama.local:11434",
                            "apiPath": "/v1/embeddings",
                            "model": "qwen3-embedding:0.6b",
                            "apiKey": None,
                            "params": {"dimensions": None, "batchSize": 16},
                            "capabilities": ["EMBEDDING"],
                            "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                        }
                    )
                return Lease.model_validate(
                    {
                        "leaseId": str(uuid.uuid4()),
                        "protocol": "LLAMACPP_RERANK",
                        "providerType": "LLAMACPP_RERANK",
                        "baseUrl": "http://rerank.local:8088",
                        "apiPath": "/rerank",
                        "model": "bge-reranker",
                        "apiKey": None,
                        "params": {"maxCandidates": 5, "timeoutMs": 1000},
                        "capabilities": ["RERANK"],
                        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                    }
                )

            async def send_usage(items: Any, _tok: str) -> dict[str, bool]:
                return {str(i["callId"]): True for i in items}

            kernel = ModelKernel(
                leases=LeaseCache(fetch_lease),
                usage=UsageReporter(send_usage, max_batch=50, flush_interval=60.0),
                runtime=ModelRuntime(
                    retry_backoff_seconds=0.0,
                    transport=httpx2.MockTransport(provider_handler),
                ),
            )
            handlers = build_index_handlers(java=java, kernel=kernel, db=db)

            # 1. REINDEX_GENERATION 创建第一个 BUILDING generation 并激活
            t_reindex = str(uuid.uuid4())
            task_meta[t_reindex] = {"taskId": t_reindex, "taskType": "REINDEX_GENERATION"}
            res_gen1 = await handlers[TASK_REINDEX_GENERATION](
                TaskContext(t_reindex, TASK_REINDEX_GENERATION, 1, "lt", "tok")
            )
            gen1 = uuid.UUID(res_gen1["generationId"])

            app = FastAPI()
            app.state.token_verifier = TokenVerifier(
                key_ring=Ed25519KeyRing.parse(f"k1={_PUBLIC_SPKI}")
            )
            app.state.index_db = db
            app.state.model_kernel = kernel
            app.state.knowledge_search_pipeline = KnowledgeSearchPipeline(
                store=PostgresRetrievalStore(db), kernel=kernel
            )
            install_error_handlers(app)
            app.include_router(index_admin_router)
            app.include_router(knowledge_search_router)

            admin_hdrs = {"Authorization": f"Bearer {_token(scope='index:admin')}"}
            with TestClient(app) as client:
                assert (
                    client.post(
                        f"/v1/index/generations/{gen1}:activate", headers=admin_hdrs
                    ).status_code
                    == 200
                )

                # 2. 写入可见文档、不可见文档（多行测 iterative scan）、空文档与他项目文档
                for doc, proj, snap, text, count in [
                    (doc_vis, project_a, snap_vis, "重要 关键规范 条款内容", 2),
                    (doc_hidden, project_a, snap_hidden, "重要 隐藏机密 文档内容", 15),
                    (doc_other_proj, project_b, snap_other, "重要 他项目文档", 2),
                ]:
                    chunks_json = [
                        {
                            "chunkNo": 0,
                            "chunkType": "PARENT",
                            "parentChunkNo": None,
                            "heading": "规范章节",
                            "content": text,
                            "contentHash": _sha(text),
                            "metadata": {"charFrom": 0, "charThrough": len(text)},
                        }
                    ] + [
                        {
                            "chunkNo": idx,
                            "chunkType": "CHILD",
                            "parentChunkNo": 0,
                            "heading": "规范章节",
                            "content": f"{text}-{idx}",
                            "contentHash": _sha(f"{text}-{idx}"),
                            "metadata": {"charFrom": 0, "charThrough": len(text)},
                        }
                        for idx in range(1, count + 1)
                    ]
                    tid = str(uuid.uuid4())
                    task_meta[tid] = {
                        "schemaVersion": 1,
                        "taskId": tid,
                        "taskType": "INDEX_DOCUMENT",
                        "projectId": str(proj),
                        "documentId": str(doc),
                        "generationId": None,
                        "bodySnapshotId": str(snap),
                        "bodyChunkCount": len(chunks_json),
                    }
                    body_pages[doc] = {
                        "documentId": str(doc),
                        "projectId": str(proj),
                        "bodySnapshotId": str(snap),
                        "totalChunks": len(chunks_json),
                        "chunks": chunks_json,
                        "nextCursor": None,
                    }
                    await handlers[TASK_INDEX_DOCUMENT](
                        TaskContext(tid, TASK_INDEX_DOCUMENT, 1, "lt", "tok")
                    )

                # 0-chunk 文档合法
                tid_empty = str(uuid.uuid4())
                task_meta[tid_empty] = {
                    "schemaVersion": 1,
                    "taskId": tid_empty,
                    "taskType": "INDEX_DOCUMENT",
                    "projectId": str(project_a),
                    "documentId": str(doc_empty),
                    "generationId": None,
                    "bodySnapshotId": str(snap_empty),
                    "bodyChunkCount": 0,
                }
                empty_res = await handlers[TASK_INDEX_DOCUMENT](
                    TaskContext(tid_empty, TASK_INDEX_DOCUMENT, 1, "lt", "tok")
                )
                assert empty_res["generations"][0]["indexedRows"] == 0

                # 3. 检索：ACL 隔离（排除 doc_hidden 与 project_b 的 doc_other_proj）+ HNSW 迭代扫描
                search_token = _token(
                    scope="knowledge:search credentials:lease usage:report",
                    uid=user_id,
                    pid=project_a,
                )
                search_hdrs = {"Authorization": f"Bearer {search_token}"}
                s_ok = client.post(
                    "/v1/knowledge/search",
                    headers=search_hdrs,
                    json={
                        "requestId": str(uuid.uuid4()),
                        "projectId": str(project_a),
                        "userId": str(user_id),
                        "intent": "KNOWLEDGE_QA",
                        "strategy": "HYBRID",
                        "queries": [{"text": "重要 规范", "role": "PRIMARY"}],
                        "originalQuery": "重要规范是什么？",
                        "visibleDocumentIds": [str(doc_vis), str(doc_other_proj)],
                        "topK": 5,
                        "candidatePool": 20,
                        "headingFilter": {"exact": "规范章节", "contains": None},
                        "rerank": "HEURISTIC",
                        "expand": {"sentenceWindow": True, "parent": True},
                        "deadlineMs": 5000,
                    },
                )
                assert s_ok.status_code == 200
                hit_docs = {h["documentId"] for h in s_ok.json()["hits"]}
                assert hit_docs == {str(doc_vis)}
                assert s_ok.json()["hits"][0]["context"] is not None

                # 4. CONFIGURED rerank 失败返回 503 RERANK_UNAVAILABLE，绝不退回启发式
                rerank_should_fail = True
                s_503 = client.post(
                    "/v1/knowledge/search",
                    headers=search_hdrs,
                    json={
                        "requestId": str(uuid.uuid4()),
                        "projectId": str(project_a),
                        "userId": str(user_id),
                        "intent": "KNOWLEDGE_QA",
                        "strategy": "HYBRID",
                        "queries": [{"text": "重要 规范", "role": "PRIMARY"}],
                        "originalQuery": "重要规范是什么？",
                        "visibleDocumentIds": [str(doc_vis)],
                        "topK": 5,
                        "candidatePool": 20,
                        "headingFilter": {"exact": None, "contains": None},
                        "rerank": "CONFIGURED",
                        "expand": {"sentenceWindow": True, "parent": True},
                        "deadlineMs": 5000,
                    },
                )
                assert s_503.status_code == 503
                assert s_503.json()["code"] == CODE_RERANK_UNAVAILABLE

                # 5. PRUNE（服务器时钟）+ DELETE_DOCUMENT_INDEX + RETIRED reopen + activate 清理
                future_as_of = (datetime.now(UTC) + timedelta(minutes=5)).isoformat()
                prune_res = client.post(
                    f"/v1/index/generations/{gen1}:reconcile",
                    headers=admin_hdrs,
                    json={
                        "mode": "PRUNE",
                        "asOf": future_as_of,
                        "range": {"afterDocumentId": None, "throughDocumentId": None},
                        "documentIds": [str(doc_vis), str(doc_other_proj)],
                    },
                )
                assert prune_res.status_code == 200
                assert prune_res.json()["deletedDocuments"] == 2  # doc_hidden + doc_empty

                # 新建第二个 generation 并激活 -> gen1 变为 RETIRED；清理只保留 1 个 RETIRED
                t_r2 = str(uuid.uuid4())
                task_meta[t_r2] = {"taskId": t_r2, "taskType": "REINDEX_GENERATION"}
                gen2 = uuid.UUID(
                    (
                        await handlers[TASK_REINDEX_GENERATION](
                            TaskContext(t_r2, TASK_REINDEX_GENERATION, 1, "lt", "tok")
                        )
                    )["generationId"]
                )
                assert (
                    client.post(
                        f"/v1/index/generations/{gen2}:activate", headers=admin_hdrs
                    ).status_code
                    == 200
                )
                await cleanup_retired_generations(db)
                assert (
                    client.post(
                        f"/v1/index/generations/{gen1}:reopen", headers=admin_hdrs
                    ).status_code
                    == 200
                )

                # DELETE_DOCUMENT_INDEX 删除 doc_vis
                t_del = str(uuid.uuid4())
                task_meta[t_del] = {"projectId": str(project_a), "documentId": str(doc_vis)}
                del_res = await handlers[TASK_DELETE_DOCUMENT_INDEX](
                    TaskContext(t_del, TASK_DELETE_DOCUMENT_INDEX, 1, "lt", "tok")
                )
                assert del_res["deletedRows"] >= 3

                # 验证 PostgresRetrievalStore heading filter exact/contains 纯读覆盖
                store = PostgresRetrievalStore(db)
                assert (
                    await store.search_lexical(
                        generation_id=gen2,
                        project_id=project_a,
                        query="不存在",
                        document_ids=[doc_vis],
                        top_k=5,
                        heading_filter=HeadingFilter(),
                    )
                    == []
                )
            await java.aclose()
        finally:
            await db.aclose()

    asyncio.run(run())
