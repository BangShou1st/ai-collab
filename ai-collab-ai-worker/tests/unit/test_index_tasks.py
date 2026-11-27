"""P2 三类索引任务处理器（INDEX_DOCUMENT / DELETE_DOCUMENT_INDEX / REINDEX_GENERATION）单元测试。"""

from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import logging
import secrets
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import httpx2
import pytest
from psycopg.errors import UniqueViolation

from ai_collab_worker.clients.java_internal import (
    CODE_INDEX_SNAPSHOT_STALE,
    JavaInternalClient,
)
from ai_collab_worker.index.db import VECTOR_DIMENSION
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import Lease, LeaseCache, LeaseRequest
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.observability.metrics import REGISTRY
from ai_collab_worker.security.redaction import SECRETS
from ai_collab_worker.tasks.index import (
    ERROR_BODY_NOT_FOUND,
    ERROR_EMBEDDING_DIMENSION_MISMATCH,
    ERROR_EMBEDDING_MODEL_MISMATCH,
    ERROR_INDEX_GENERATION_CONFLICT,
    ERROR_INDEX_GENERATION_STALE,
    ERROR_INDEX_GENERATION_UNAVAILABLE,
    ERROR_INDEX_SNAPSHOT_STALE,
    TASK_DELETE_DOCUMENT_INDEX,
    TASK_INDEX_DOCUMENT,
    TASK_REINDEX_GENERATION,
    build_index_handlers,
)
from ai_collab_worker.tasks.stream import TaskContext, TaskFailedError

PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
OTHER_PROJECT_ID = uuid.UUID("29999999-2999-4999-8999-299999999999")
DOC_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
OTHER_DOC_ID = uuid.UUID("49999999-4999-4999-8999-499999999999")
SNAPSHOT_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
TASK_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
ACTIVE_GEN = uuid.UUID("66666666-6666-4666-8666-666666666661")
BUILDING_GEN = uuid.UUID("66666666-6666-4666-8666-666666666662")
RETIRED_GEN = uuid.UUID("66666666-6666-4666-8666-666666666663")
BEARER = "tok.index.unit.test"


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _unit_vec(value: float = 0.01, dim: int = VECTOR_DIMENSION) -> list[float]:
    return [value] * dim


class _FakeCursor:
    def __init__(self, rows: list[tuple[Any, ...]] | None = None, rowcount: int = 0) -> None:
        self._rows = rows or []
        self.rowcount = rowcount

    async def fetchall(self) -> list[tuple[Any, ...]]:
        return list(self._rows)

    async def fetchone(self) -> tuple[Any, ...] | None:
        return self._rows[0] if self._rows else None


@dataclass
class _DbState:
    generations: dict[uuid.UUID, dict[str, Any]] = field(default_factory=dict)
    indexed_documents: dict[tuple[uuid.UUID, uuid.UUID], dict[str, Any]] = field(
        default_factory=dict
    )
    chunks: dict[tuple[uuid.UUID, uuid.UUID, int], dict[str, Any]] = field(default_factory=dict)
    pg_now: datetime = field(default_factory=lambda: datetime(2026, 11, 27, 9, 0, tzinfo=UTC))


class _FakeConn:
    def __init__(self, pool: FakeIndexDatabase) -> None:
        self._pool = pool
        self._working: _DbState | None = None

    def _active_state(self) -> _DbState:
        return self._working if self._working is not None else self._pool.state

    @asynccontextmanager
    async def transaction(self) -> Any:
        snapshot = copy.deepcopy(self._pool.state)
        self._working = snapshot
        try:
            yield self
        except Exception:
            self._working = None
            raise
        else:
            self._pool.state = snapshot
            self._working = None

    @asynccontextmanager
    async def cursor(self) -> Any:
        yield self

    async def executemany(self, sql: str, params_seq: list[tuple[Any, ...]]) -> None:
        for params in params_seq:
            await self.execute(sql, params)

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> _FakeCursor:
        st = self._active_state()
        normalized = " ".join(sql.split())
        if normalized.startswith("SELECT id, embedding_model, dimension, status"):
            if "WHERE id = %s" in normalized:
                gen = st.generations.get(params[0])
                if not gen:
                    return _FakeCursor([])
                return _FakeCursor(
                    [(gen["id"], gen["embedding_model"], gen["dimension"], gen["status"])]
                )
            rows = [
                (g["id"], g["embedding_model"], g["dimension"], g["status"])
                for g in st.generations.values()
                if g["status"] in ("ACTIVE", "BUILDING")
            ]
            rows.sort(key=lambda item: item[3])
            return _FakeCursor(rows)

        if normalized.startswith("INSERT INTO ai_index.index_generation"):
            gen_id, model, dim, status = params[0], params[1], params[2], "BUILDING"
            if any(g["status"] == "BUILDING" for g in st.generations.values()):
                raise UniqueViolation("uk_index_generation_building")
            st.generations[gen_id] = {
                "id": gen_id,
                "embedding_model": model,
                "dimension": dim,
                "status": status,
                "created_at": st.pg_now,
                "activated_at": None,
            }
            return _FakeCursor(rowcount=1)

        if normalized.startswith("DELETE FROM ai_index.chunk WHERE generation_id = %s"):
            gen_id, doc_id = params
            keys = [k for k in st.chunks if k[0] == gen_id and k[1] == doc_id]
            for key in keys:
                del st.chunks[key]
            return _FakeCursor(rowcount=len(keys))

        if normalized.startswith("INSERT INTO ai_index.chunk"):
            (
                row_id,
                gen_id,
                project_id,
                doc_id,
                snap_id,
                chunk_no,
                chunk_type,
                parent_chunk_no,
                heading,
                content,
                content_hash,
                metadata_json,
                embedding,
            ) = params
            if (chunk_type == "PARENT") != (embedding is None):
                raise AssertionError("ck_chunk_parent_embedding violated")
            st.chunks[(gen_id, doc_id, chunk_no)] = {
                "id": row_id,
                "generation_id": gen_id,
                "project_id": project_id,
                "document_id": doc_id,
                "body_snapshot_id": snap_id,
                "chunk_no": chunk_no,
                "chunk_type": chunk_type,
                "parent_chunk_no": parent_chunk_no,
                "heading": heading,
                "content": content,
                "content_hash": content_hash,
                "metadata": json.loads(metadata_json),
                "embedding": embedding,
            }
            return _FakeCursor(rowcount=1)

        if normalized.startswith("INSERT INTO ai_index.indexed_document"):
            gen_id, project_id, doc_id, snap_id, row_count, body_version = params
            previous = st.indexed_documents.get((gen_id, doc_id))
            if previous and (
                previous["body_version"] > body_version
                or (
                    previous["body_version"] == body_version
                    and previous["body_snapshot_id"] != snap_id
                )
            ):
                return _FakeCursor(rowcount=0)
            st.indexed_documents[(gen_id, doc_id)] = {
                "generation_id": gen_id,
                "project_id": project_id,
                "document_id": doc_id,
                "body_snapshot_id": snap_id,
                "row_count": row_count,
                "body_version": body_version,
                "indexed_at": st.pg_now,
                "used_db_now": "indexed_at, now()" in normalized or "now()" in normalized,
            }
            return _FakeCursor(rowcount=1)

        if normalized.startswith(
            "DELETE FROM ai_index.chunk WHERE project_id = %s AND document_id = %s"
        ):
            project_id, doc_id = params
            keys = [
                k
                for k, row in st.chunks.items()
                if row["project_id"] == project_id and row["document_id"] == doc_id
            ]
            for key in keys:
                del st.chunks[key]
            return _FakeCursor(rowcount=len(keys))

        if normalized.startswith("DELETE FROM ai_index.indexed_document WHERE project_id = %s"):
            project_id, doc_id = params
            keys = [
                k
                for k, row in st.indexed_documents.items()
                if row["project_id"] == project_id and row["document_id"] == doc_id
            ]
            for key in keys:
                del st.indexed_documents[key]
            return _FakeCursor(rowcount=len(keys))

        raise AssertionError(f"unexpected SQL: {normalized}")


class FakePool:
    def __init__(self, db: FakeIndexDatabase) -> None:
        self._db = db

    @asynccontextmanager
    async def connection(self) -> Any:
        yield _FakeConn(self._db)


class FakeIndexDatabase:
    def __init__(self, *, generations: list[dict[str, Any]] | None = None) -> None:
        self.state = _DbState()
        for gen in generations or []:
            self.state.generations[gen["id"]] = dict(gen)
        self.pool = FakePool(self)


def _make_deps(
    *,
    java_handler: Any,
    embed_handler: Any | None = None,
    lease_model: str = "qwen3-embedding:0.6b",
    generation_models: dict[uuid.UUID, str] | None = None,
    lease_dims: int | None = None,
    lease_batch: int = 2,
    lease_error: Exception | None = None,
    db: FakeIndexDatabase | None = None,
) -> tuple[JavaInternalClient, ModelKernel, FakeIndexDatabase, list[LeaseRequest]]:
    api_key = f"sk-idx-{secrets.token_urlsafe(12)}"
    SECRETS.add(api_key)
    lease_calls: list[LeaseRequest] = []
    java = JavaInternalClient(
        base_url="http://java.test", transport=httpx.MockTransport(java_handler)
    )

    async def fetch_lease(req: LeaseRequest, _token: str) -> Lease:
        lease_calls.append(req)
        if lease_error is not None:
            raise lease_error
        return Lease.model_validate(
            {
                "leaseId": str(uuid.uuid4()),
                "protocol": "OPENAI_EMBEDDINGS",
                "providerType": "OPENAI_COMPATIBLE",
                "baseUrl": "https://embed.test",
                "apiPath": "/v1/embeddings",
                "model": (generation_models or {}).get(req.generation_id, lease_model)
                if req.generation_id is not None
                else lease_model,
                "apiKey": api_key,
                "params": {"dimensions": lease_dims, "batchSize": lease_batch},
                "capabilities": ["EMBEDDING"],
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        )

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        return {str(entry["callId"]): True for entry in items}

    transport = (
        httpx2.MockTransport(embed_handler)
        if embed_handler is not None
        else httpx2.MockTransport(
            lambda req: httpx2.Response(
                200,
                json={
                    "data": [
                        {"embedding": _unit_vec(0.1)}
                        for _ in json.loads(req.content.decode("utf-8"))["input"]
                    ]
                },
            )
        )
    )
    kernel = ModelKernel(
        leases=LeaseCache(fetch_lease),
        usage=UsageReporter(send_usage, max_batch=50, flush_interval=60.0),
        runtime=ModelRuntime(retry_backoff_seconds=0.0, transport=transport),
    )
    database = db or FakeIndexDatabase()
    return java, kernel, database, lease_calls


def _context(task_type: str, task_id: uuid.UUID = TASK_ID) -> TaskContext:
    return TaskContext(
        task_id=str(task_id),
        task_type=task_type,
        epoch=1,
        lease_token="lt-index",
        token=BEARER,
    )


def test_index_document_paginates_embeds_non_parent_writes_active_and_building_idempotently(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    secret_content = "SECRET-CHUNK-正文原文-01"
    embed_inputs_seen: list[list[str]] = []
    source_version = 2

    def java_handler(req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/snapshot"):
            return httpx.Response(
                200,
                json={
                    "schemaVersion": 1,
                    "taskId": str(TASK_ID),
                    "taskType": "INDEX_DOCUMENT",
                    "projectId": str(PROJECT_ID),
                    "documentId": str(DOC_ID),
                    "generationId": None,
                    "bodySnapshotId": str(SNAPSHOT_ID),
                    "parseVersion": "tika-page-clean-v2/chunk-offset-v2",
                    "bodyChunkCount": 3,
                    "bodyVersion": source_version,
                },
            )
        if req.url.path == f"/internal/ai/documents/{DOC_ID}/body":
            cursor = req.url.params.get("cursor")
            if cursor is None:
                return httpx.Response(
                    200,
                    json={
                        "documentId": str(DOC_ID),
                        "projectId": str(PROJECT_ID),
                        "bodySnapshotId": str(SNAPSHOT_ID),
                        "totalChunks": 3,
                        "chunks": [
                            {
                                "chunkNo": 0,
                                "chunkType": "PARENT",
                                "parentChunkNo": None,
                                "heading": "章节标题",
                                "content": "父块不被向量化",
                                "contentHash": _sha("父块不被向量化"),
                                "metadata": {"charFrom": 0, "charThrough": 8},
                            },
                            {
                                "chunkNo": 1,
                                "chunkType": "CHILD",
                                "parentChunkNo": 0,
                                "heading": "章节标题",
                                "content": f"  {secret_content}\n",
                                "contentHash": _sha(f"  {secret_content}\n"),
                                "metadata": {"charFrom": 0, "charThrough": 25},
                            },
                        ],
                        "nextCursor": "page-2",
                    },
                )
            return httpx.Response(
                200,
                json={
                    "documentId": str(DOC_ID),
                    "projectId": str(PROJECT_ID),
                    "bodySnapshotId": str(SNAPSHOT_ID),
                    "totalChunks": 3,
                    "chunks": [
                        {
                            "chunkNo": 2,
                            "chunkType": "STANDARD",
                            "parentChunkNo": None,
                            "heading": "第二节",
                            "content": "标准块二",
                            "contentHash": _sha("标准块二"),
                            "metadata": {"pageNumber": 2},
                        }
                    ],
                    "nextCursor": None,
                },
            )
        raise AssertionError(f"unexpected URL {req.url}")

    def embed_handler(req: httpx2.Request) -> httpx2.Response:
        body = json.loads(req.content.decode("utf-8"))
        embed_inputs_seen.append(body["input"])
        return httpx2.Response(
            200,
            json={
                "data": [
                    {"embedding": _unit_vec(0.8 if body["model"] == "new-embedding" else 0.2)}
                    for _ in body["input"]
                ]
            },
        )

    db = FakeIndexDatabase(
        generations=[
            {
                "id": ACTIVE_GEN,
                "embedding_model": "qwen3-embedding:0.6b",
                "dimension": 1024,
                "status": "ACTIVE",
            },
            {
                "id": BUILDING_GEN,
                "embedding_model": "new-embedding",
                "dimension": 1024,
                "status": "BUILDING",
            },
        ]
    )
    java, kernel, db, lease_calls = _make_deps(
        java_handler=java_handler,
        embed_handler=embed_handler,
        generation_models={BUILDING_GEN: "new-embedding"},
        db=db,
    )
    handlers = build_index_handlers(java=java, kernel=kernel, db=db)

    async def main() -> tuple[Any, Any]:
        nonlocal source_version
        try:
            r1 = await handlers[TASK_INDEX_DOCUMENT](_context(TASK_INDEX_DOCUMENT))
            # 幂等重跑一次
            r2 = await handlers[TASK_INDEX_DOCUMENT](_context(TASK_INDEX_DOCUMENT))
            source_version = 1
            with pytest.raises(TaskFailedError) as stale:
                await handlers[TASK_INDEX_DOCUMENT](_context(TASK_INDEX_DOCUMENT))
            assert stale.value.error_code == ERROR_INDEX_SNAPSHOT_STALE
            assert not stale.value.retryable
            return r1, r2
        finally:
            await java.aclose()

    r1, r2 = asyncio.run(main())
    assert r1 == r2
    assert r1["documentId"] == str(DOC_ID)
    assert r1["bodySnapshotId"] == str(SNAPSHOT_ID)
    assert r1["embeddingModel"] == "qwen3-embedding:0.6b"
    assert r1["dimension"] == 1024
    assert len(r1["generations"]) == 2
    assert all(g["indexedRows"] == 3 and g["embeddedRows"] == 2 for g in r1["generations"])
    # 文档侧按原文发送（保留首尾空格、不拼 heading）
    assert embed_inputs_seen[0] == [f"  {secret_content}\n", "标准块二"]
    assert len(db.state.chunks) == 6
    assert db.state.chunks[(ACTIVE_GEN, DOC_ID, 0)]["embedding"] is None
    assert db.state.chunks[(ACTIVE_GEN, DOC_ID, 1)]["embedding"] is not None
    assert (
        db.state.chunks[(ACTIVE_GEN, DOC_ID, 1)]["embedding"]
        != db.state.chunks[(BUILDING_GEN, DOC_ID, 1)]["embedding"]
    )
    assert [call.generation_id for call in lease_calls] == [ACTIVE_GEN, BUILDING_GEN]
    assert db.state.indexed_documents[(ACTIVE_GEN, DOC_ID)]["row_count"] == 3
    assert db.state.indexed_documents[(ACTIVE_GEN, DOC_ID)]["used_db_now"] is True
    assert secret_content not in caplog.text
    assert BEARER not in caplog.text
    assert (
        REGISTRY.get_sample_value(
            "ai_index_task_duration_seconds_count",
            {"taskType": TASK_INDEX_DOCUMENT, "caller_type": "PYTHON"},
        )
        >= 2.0
    )


def test_index_document_zero_chunks_succeeds_without_embedding_lease() -> None:
    def java_handler(_req: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "schemaVersion": 1,
                "taskId": str(TASK_ID),
                "taskType": "INDEX_DOCUMENT",
                "projectId": str(PROJECT_ID),
                "documentId": str(DOC_ID),
                "generationId": str(BUILDING_GEN),
                "bodySnapshotId": str(SNAPSHOT_ID),
                "bodyChunkCount": 0,
            },
        )

    db = FakeIndexDatabase(
        generations=[
            {
                "id": ACTIVE_GEN,
                "embedding_model": "qwen3-embedding:0.6b",
                "dimension": 1024,
                "status": "ACTIVE",
            },
            {
                "id": BUILDING_GEN,
                "embedding_model": "qwen3-embedding:0.6b",
                "dimension": 1024,
                "status": "BUILDING",
            },
        ]
    )
    java, kernel, db, lease_calls = _make_deps(java_handler=java_handler, db=db)
    handlers = build_index_handlers(java=java, kernel=kernel, db=db)

    async def main() -> Any:
        try:
            return await handlers[TASK_INDEX_DOCUMENT](_context(TASK_INDEX_DOCUMENT))
        finally:
            await java.aclose()

    result = asyncio.run(main())
    assert lease_calls == []
    assert result["generations"] == [
        {"generationId": str(BUILDING_GEN), "indexedRows": 0, "embeddedRows": 0}
    ]
    assert (BUILDING_GEN, DOC_ID) in db.state.indexed_documents
    assert db.state.indexed_documents[(BUILDING_GEN, DOC_ID)]["row_count"] == 0
    assert (ACTIVE_GEN, DOC_ID) not in db.state.indexed_documents
    assert db.state.chunks == {}


def test_index_document_generation_and_model_and_dimension_and_stale_failures() -> None:
    def body_snap_handler(gen_id: uuid.UUID | None) -> Any:
        def handler(req: httpx.Request) -> httpx.Response:
            if req.url.path.endswith("/snapshot"):
                return httpx.Response(
                    200,
                    json={
                        "taskId": str(TASK_ID),
                        "taskType": "INDEX_DOCUMENT",
                        "projectId": str(PROJECT_ID),
                        "documentId": str(DOC_ID),
                        "generationId": str(gen_id) if gen_id else None,
                        "bodySnapshotId": str(SNAPSHOT_ID),
                        "bodyChunkCount": 1,
                    },
                )
            return httpx.Response(
                200,
                json={
                    "documentId": str(DOC_ID),
                    "projectId": str(PROJECT_ID),
                    "bodySnapshotId": str(SNAPSHOT_ID),
                    "totalChunks": 1,
                    "chunks": [
                        {
                            "chunkNo": 0,
                            "chunkType": "STANDARD",
                            "content": "hi",
                            "contentHash": _sha("hi"),
                        }
                    ],
                    "nextCursor": None,
                },
            )

        return handler

    # 1. 无 ACTIVE 也无 BUILDING → INDEX_GENERATION_UNAVAILABLE(retryable=True)
    db_empty = FakeIndexDatabase(
        generations=[
            {
                "id": RETIRED_GEN,
                "embedding_model": "qwen3-embedding:0.6b",
                "dimension": 1024,
                "status": "RETIRED",
            }
        ]
    )
    j1, k1, db1, _ = _make_deps(java_handler=body_snap_handler(None), db=db_empty)
    with pytest.raises(TaskFailedError) as e_unavail:
        asyncio.run(
            build_index_handlers(java=j1, kernel=k1, db=db1)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j1.aclose())
    assert (e_unavail.value.error_code, e_unavail.value.retryable) == (
        ERROR_INDEX_GENERATION_UNAVAILABLE,
        True,
    )

    # 2. 指定 RETIRED generation → INDEX_GENERATION_STALE(retryable=False)
    j2, k2, db2, _ = _make_deps(java_handler=body_snap_handler(RETIRED_GEN), db=db_empty)
    with pytest.raises(TaskFailedError) as e_stale_gen:
        asyncio.run(
            build_index_handlers(java=j2, kernel=k2, db=db2)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j2.aclose())
    assert (e_stale_gen.value.error_code, e_stale_gen.value.retryable) == (
        ERROR_INDEX_GENERATION_STALE,
        False,
    )

    # 3. 模型不匹配 → EMBEDDING_MODEL_MISMATCH(retryable=False)
    db_active = FakeIndexDatabase(
        generations=[
            {
                "id": ACTIVE_GEN,
                "embedding_model": "old-model",
                "dimension": 1024,
                "status": "ACTIVE",
            }
        ]
    )
    j3, k3, db3, _ = _make_deps(
        java_handler=body_snap_handler(ACTIVE_GEN),
        lease_model="new-model",
        db=db_active,
    )
    with pytest.raises(TaskFailedError) as e_model:
        asyncio.run(
            build_index_handlers(java=j3, kernel=k3, db=db3)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j3.aclose())
    assert (e_model.value.error_code, e_model.value.retryable) == (
        ERROR_EMBEDDING_MODEL_MISMATCH,
        False,
    )

    # 4. 首批维度不等于 1024 → EMBEDDING_DIMENSION_MISMATCH(retryable=False)，不写库
    db_768 = FakeIndexDatabase(
        generations=[
            {
                "id": ACTIVE_GEN,
                "embedding_model": "qwen3-embedding:0.6b",
                "dimension": 1024,
                "status": "ACTIVE",
            }
        ]
    )
    j4, k4, db4, _ = _make_deps(
        java_handler=body_snap_handler(ACTIVE_GEN),
        embed_handler=lambda _req: httpx2.Response(
            200, json={"data": [{"embedding": [0.1] * 768}]}
        ),
        db=db_768,
    )
    with pytest.raises(TaskFailedError) as e_dim:
        asyncio.run(
            build_index_handlers(java=j4, kernel=k4, db=db4)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j4.aclose())
    assert (e_dim.value.error_code, e_dim.value.retryable) == (
        ERROR_EMBEDDING_DIMENSION_MISMATCH,
        False,
    )
    assert db4.state.chunks == {}
    assert db4.state.indexed_documents == {}

    # 5. /body 返回 409 INDEX_SNAPSHOT_STALE 与 404 → 各自不可重试
    def stale_body_handler(req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/snapshot"):
            return body_snap_handler(ACTIVE_GEN)(req)
        return httpx.Response(409, json={"code": CODE_INDEX_SNAPSHOT_STALE})

    j5, k5, db5, _ = _make_deps(java_handler=stale_body_handler, db=db_768)
    with pytest.raises(TaskFailedError) as e_stale_body:
        asyncio.run(
            build_index_handlers(java=j5, kernel=k5, db=db5)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j5.aclose())
    assert (e_stale_body.value.error_code, e_stale_body.value.retryable) == (
        ERROR_INDEX_SNAPSHOT_STALE,
        False,
    )

    def missing_body_handler(req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/snapshot"):
            return body_snap_handler(ACTIVE_GEN)(req)
        return httpx.Response(404, json={"code": "NOT_FOUND"})

    j6, k6, db6, _ = _make_deps(java_handler=missing_body_handler, db=db_768)
    with pytest.raises(TaskFailedError) as e_404:
        asyncio.run(
            build_index_handlers(java=j6, kernel=k6, db=db6)[TASK_INDEX_DOCUMENT](
                _context(TASK_INDEX_DOCUMENT)
            )
        )
    asyncio.run(j6.aclose())
    assert (e_404.value.error_code, e_404.value.retryable) == (ERROR_BODY_NOT_FOUND, False)


def test_delete_document_index_removes_across_all_generations_and_preserves_others() -> None:
    db = FakeIndexDatabase()
    for gen_id in (ACTIVE_GEN, BUILDING_GEN, RETIRED_GEN):
        db.state.chunks[(gen_id, DOC_ID, 0)] = {
            "project_id": PROJECT_ID,
            "document_id": DOC_ID,
        }
        db.state.indexed_documents[(gen_id, DOC_ID)] = {
            "project_id": PROJECT_ID,
            "document_id": DOC_ID,
        }
    db.state.chunks[(ACTIVE_GEN, OTHER_DOC_ID, 0)] = {
        "project_id": PROJECT_ID,
        "document_id": OTHER_DOC_ID,
    }
    db.state.chunks[(ACTIVE_GEN, DOC_ID, 1)] = {
        "project_id": OTHER_PROJECT_ID,
        "document_id": DOC_ID,
    }

    def java_handler(_req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"projectId": str(PROJECT_ID), "documentId": str(DOC_ID)})

    java, kernel, db, _ = _make_deps(java_handler=java_handler, db=db)
    handler = build_index_handlers(java=java, kernel=kernel, db=db)[TASK_DELETE_DOCUMENT_INDEX]

    async def main() -> Any:
        try:
            return await handler(_context(TASK_DELETE_DOCUMENT_INDEX))
        finally:
            await java.aclose()

    result = asyncio.run(main())
    assert result == {"documentId": str(DOC_ID), "deletedRows": 3}
    assert all(
        not (row["project_id"] == PROJECT_ID and row["document_id"] == DOC_ID)
        for row in db.state.chunks.values()
    )
    assert all(
        not (row["project_id"] == PROJECT_ID and row["document_id"] == DOC_ID)
        for row in db.state.indexed_documents.values()
    )
    assert (ACTIVE_GEN, OTHER_DOC_ID, 0) in db.state.chunks
    assert (ACTIVE_GEN, DOC_ID, 1) in db.state.chunks


def test_reindex_generation_creates_building_and_guards_dimension_and_conflict() -> None:
    def java_handler(_req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"taskId": str(TASK_ID), "taskType": "REINDEX_GENERATION"})

    # 1. 成功创建 BUILDING generation
    java, kernel, db, lease_calls = _make_deps(java_handler=java_handler)
    handler = build_index_handlers(java=java, kernel=kernel, db=db)[TASK_REINDEX_GENERATION]

    async def run_ok() -> Any:
        try:
            return await handler(_context(TASK_REINDEX_GENERATION))
        finally:
            await java.aclose()

    res = asyncio.run(run_ok())
    gen_id = uuid.UUID(res["generationId"])
    assert res["embeddingModel"] == "qwen3-embedding:0.6b"
    assert res["dimension"] == 1024
    assert db.state.generations[gen_id]["status"] == "BUILDING"
    assert len(lease_calls) == 1
    assert lease_calls[0].user_id is None
    assert lease_calls[0].project_id is None
    assert lease_calls[0].task_id == TASK_ID

    # 2. 已有 BUILDING 时冲突 → INDEX_GENERATION_CONFLICT
    j_conf, k_conf, db_conf, _ = _make_deps(java_handler=java_handler, db=db)
    with pytest.raises(TaskFailedError) as e_conf:
        asyncio.run(
            build_index_handlers(java=j_conf, kernel=k_conf, db=db_conf)[TASK_REINDEX_GENERATION](
                _context(TASK_REINDEX_GENERATION)
            )
        )
    asyncio.run(j_conf.aclose())
    assert (e_conf.value.error_code, e_conf.value.retryable) == (
        ERROR_INDEX_GENERATION_CONFLICT,
        False,
    )

    # 3. 探测返回非 1024 维 → EMBEDDING_DIMENSION_MISMATCH 且不建 generation
    j_dim, k_dim, db_dim, _ = _make_deps(
        java_handler=java_handler,
        embed_handler=lambda _req: httpx2.Response(
            200, json={"data": [{"embedding": [0.1] * 512}]}
        ),
    )
    with pytest.raises(TaskFailedError) as e_dim:
        asyncio.run(
            build_index_handlers(java=j_dim, kernel=k_dim, db=db_dim)[TASK_REINDEX_GENERATION](
                _context(TASK_REINDEX_GENERATION)
            )
        )
    asyncio.run(j_dim.aclose())
    assert (e_dim.value.error_code, e_dim.value.retryable) == (
        ERROR_EMBEDDING_DIMENSION_MISMATCH,
        False,
    )
    assert db_dim.state.generations == {}
