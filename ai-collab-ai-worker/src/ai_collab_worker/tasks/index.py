"""P2 索引任务处理器（P2 契约 §4.2、§4.3、§5.1、§9.3、§12.3/§12.6）。

覆盖 Redis Stream ``aicollab:ai:tasks`` 的三类索引任务：

- ``INDEX_DOCUMENT``：拉取快照与分页正文块 → 非 ``PARENT`` 行批量向量化 → 事务内按
  ``(generation_id, document_id)`` 删旧插新并 upsert ``indexed_document(indexed_at=now())``；
- ``DELETE_DOCUMENT_INDEX``：删除所有 generation 下目标文档的 ``chunk`` 与 ``indexed_document``；
- ``REINDEX_GENERATION``：租借 ``EMBEDDING`` 并探测 1024 维 → 创建 ``BUILDING`` generation。

安全与正确性红线：

- 严禁记录正文内容、查询文本、令牌或密钥；日志只记 ``task_id`` / 固定 reason / 行数；
- ``indexed_document.indexed_at`` 一律由数据库 ``now()`` 生成，绝不用进程本地时钟（R4-3）；
- 409 ``INDEX_SNAPSHOT_STALE`` 不可重试，交由消费框架直接 ``XACK`` 放弃。
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Final, Protocol

from psycopg.errors import Error as PsycopgError
from psycopg.errors import UniqueViolation

from ai_collab_worker.clients.java_internal import (
    CODE_INDEX_SNAPSHOT_STALE,
    DeleteDocumentIndexSnapshot,
    DocumentBodyChunk,
    IndexDocumentSnapshot,
    JavaInternalClient,
    JavaInternalError,
)
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.knowledge_search import ChunkType
from ai_collab_worker.index.db import VECTOR_DIMENSION, vector_literal
from ai_collab_worker.models.factory import EmbeddingDimensionMismatchError, EmbeddingTaskType
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import Lease, LeasePurpose
from ai_collab_worker.observability.metrics import AI_INDEX_TASK_DURATION, observe_histogram
from ai_collab_worker.tasks.stream import TaskContext, TaskFailedError, TaskHandler

logger: Final[logging.Logger] = logging.getLogger(__name__)

CALLER_TYPE: Final[str] = "PYTHON"

TASK_INDEX_DOCUMENT: Final[str] = "INDEX_DOCUMENT"
TASK_DELETE_DOCUMENT_INDEX: Final[str] = "DELETE_DOCUMENT_INDEX"
TASK_REINDEX_GENERATION: Final[str] = "REINDEX_GENERATION"

ERROR_EMBEDDING_DIMENSION_MISMATCH: Final[str] = "EMBEDDING_DIMENSION_MISMATCH"
ERROR_EMBEDDING_MODEL_MISMATCH: Final[str] = "EMBEDDING_MODEL_MISMATCH"
ERROR_BODY_NOT_FOUND: Final[str] = "BODY_NOT_FOUND"
ERROR_INDEX_GENERATION_STALE: Final[str] = "INDEX_GENERATION_STALE"
ERROR_INDEX_GENERATION_UNAVAILABLE: Final[str] = "INDEX_GENERATION_UNAVAILABLE"
ERROR_INDEX_GENERATION_CONFLICT: Final[str] = "INDEX_GENERATION_CONFLICT"
ERROR_INDEX_SNAPSHOT_STALE: Final[str] = CODE_INDEX_SNAPSHOT_STALE
ERROR_SNAPSHOT_UNAVAILABLE: Final[str] = "SNAPSHOT_UNAVAILABLE"
ERROR_SNAPSHOT_INVALID: Final[str] = "SNAPSHOT_INVALID"
ERROR_TASK_TOKEN_INVALID: Final[str] = "TASK_TOKEN_INVALID"
ERROR_INDEX_STORAGE_UNAVAILABLE: Final[str] = "INDEX_STORAGE_UNAVAILABLE"

_WRITABLE_STATUSES: Final[frozenset[str]] = frozenset({"BUILDING", "ACTIVE"})


class PoolProvider(Protocol):
    """持有 ``ai_index`` 连接池的对象（:class:`IndexDatabase` 或测试替身）。"""

    @property
    def pool(self) -> Any: ...


@dataclass(frozen=True)
class _GenerationRow:
    generation_id: uuid.UUID
    embedding_model: str
    dimension: int
    status: str


def _parse_generation_rows(rows: Sequence[tuple[Any, ...]]) -> list[_GenerationRow]:
    result: list[_GenerationRow] = []
    for row in rows:
        gen_id = row[0] if isinstance(row[0], uuid.UUID) else uuid.UUID(str(row[0]))
        result.append(
            _GenerationRow(
                generation_id=gen_id,
                embedding_model=str(row[1]),
                dimension=int(row[2]),
                status=str(row[3]),
            )
        )
    return result


def _validate_project_claim(context: TaskContext, expected_project_id: uuid.UUID) -> None:
    raw_pid = context.claims().get("pid")
    if raw_pid is None:
        return
    try:
        token_pid = uuid.UUID(str(raw_pid))
    except ValueError:
        raise TaskFailedError(ERROR_TASK_TOKEN_INVALID, retryable=False) from None
    if token_pid != expected_project_id:
        raise TaskFailedError(ERROR_TASK_TOKEN_INVALID, retryable=False)


def _map_java_snapshot_error(exc: JavaInternalError) -> TaskFailedError:
    if exc.is_snapshot_stale:
        return TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False)
    if exc.status == 404:
        return TaskFailedError(ERROR_BODY_NOT_FOUND, retryable=False)
    if exc.status in {400, 401, 403, 409, 422}:
        code = exc.code or ERROR_SNAPSHOT_INVALID
        return TaskFailedError(code[:64], retryable=False)
    return TaskFailedError(ERROR_SNAPSHOT_UNAVAILABLE, retryable=exc.retryable)


def _map_app_error(exc: AppError) -> TaskFailedError:
    reason = str((exc.details or {}).get("reason") or exc.code.value)
    return TaskFailedError(reason[:64], retryable=exc.retryable)


class IndexDocumentHandler:
    """``INDEX_DOCUMENT`` 任务处理器。"""

    def __init__(
        self,
        *,
        java: JavaInternalClient,
        kernel: ModelKernel,
        db: PoolProvider,
    ) -> None:
        self._java: Final[JavaInternalClient] = java
        self._kernel: Final[ModelKernel] = kernel
        self._db: Final[PoolProvider] = db

    async def __call__(self, context: TaskContext) -> Mapping[str, object]:
        started = time.perf_counter()
        try:
            return await self._run(context)
        finally:
            observe_histogram(
                AI_INDEX_TASK_DURATION,
                time.perf_counter() - started,
                taskType=TASK_INDEX_DOCUMENT,
                caller_type=CALLER_TYPE,
            )

    async def _run(self, context: TaskContext) -> Mapping[str, object]:
        try:
            snap = await self._java.snapshot(
                context.task_id,
                bearer_token=context.token,
                task_type="INDEX_DOCUMENT",
            )
        except JavaInternalError as exc:
            raise _map_java_snapshot_error(exc) from None
        if not isinstance(snap, IndexDocumentSnapshot):
            raise TaskFailedError(ERROR_SNAPSHOT_INVALID, retryable=False)
        _validate_project_claim(context, snap.project_id)

        chunks = await self._fetch_all_chunks(context, snap)
        targets = await self._resolve_initial_targets(snap.generation_id)

        non_parents = [c for c in chunks if c.chunk_type is not ChunkType.PARENT]
        vectors_by_generation: dict[uuid.UUID, dict[int, str]] = {}
        if non_parents:
            for target in targets:
                vectors_by_generation[target.generation_id] = await self._embed_target(
                    context, target, non_parents
                )

        written = await self._persist(
            snap=snap,
            chunks=chunks,
            vectors_by_generation=vectors_by_generation,
            has_embedding_rows=bool(non_parents),
        )

        logger.info(
            "index document succeeded task_id=%s chunks=%d embedded=%d generations=%d",
            context.task_id,
            len(chunks),
            len(non_parents),
            len(written),
        )
        return {
            "documentId": str(snap.document_id),
            "bodySnapshotId": str(snap.body_snapshot_id),
            "generations": [
                {
                    "generationId": str(gen.generation_id),
                    "indexedRows": len(chunks),
                    "embeddedRows": len(non_parents),
                }
                for gen in written
            ],
            "embeddingModel": written[0].embedding_model,
            "dimension": written[0].dimension,
        }

    async def _embed_target(
        self,
        context: TaskContext,
        target: _GenerationRow,
        chunks: Sequence[DocumentBodyChunk],
    ) -> dict[int, str]:
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=None,
            project_id=None,
            task_id=uuid.UUID(context.task_id),
            bearer_token=context.token,
            generation_id=target.generation_id,
        )

        def check_lease(lease: Lease) -> None:
            if target.embedding_model != lease.model:
                raise TaskFailedError(ERROR_EMBEDDING_MODEL_MISMATCH, retryable=False)
            if lease.params.dimensions not in {None, VECTOR_DIMENSION}:
                raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False)

        try:
            embedded = await self._kernel.embed_texts(
                scope,
                [chunk.content for chunk in chunks],
                task_type=EmbeddingTaskType.DOCUMENT,
                expected_dimension=VECTOR_DIMENSION,
                on_lease=check_lease,
            )
        except EmbeddingDimensionMismatchError:
            raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False) from None
        except AppError as exc:
            raise _map_app_error(exc) from None

        if embedded.dimension != VECTOR_DIMENSION or len(embedded.vectors) != len(chunks):
            raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False)
        return {
            chunk.chunk_no: vector_literal(vector, dimension=VECTOR_DIMENSION)
            for chunk, vector in zip(chunks, embedded.vectors, strict=True)
        }

    async def _fetch_all_chunks(
        self,
        context: TaskContext,
        snap: IndexDocumentSnapshot,
    ) -> list[DocumentBodyChunk]:
        if snap.body_chunk_count == 0:
            return []
        chunks: list[DocumentBodyChunk] = []
        cursor: str | None = None
        seen_cursors: set[str] = set()
        expected_total: int | None = None
        while True:
            try:
                page = await self._java.get_document_body_page(
                    snap.document_id,
                    cursor=cursor,
                    bearer_token=context.token,
                )
            except JavaInternalError as exc:
                if exc.is_snapshot_stale or exc.status == 409:
                    raise TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False) from None
                if exc.status == 404:
                    raise TaskFailedError(ERROR_BODY_NOT_FOUND, retryable=False) from None
                if exc.status in {400, 401, 403, 422}:
                    raise TaskFailedError(
                        (exc.code or ERROR_SNAPSHOT_INVALID)[:64], retryable=False
                    ) from None
                raise TaskFailedError(ERROR_SNAPSHOT_UNAVAILABLE, retryable=exc.retryable) from None

            if page.body_snapshot_id != snap.body_snapshot_id:
                raise TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False)
            if page.project_id != snap.project_id:
                raise TaskFailedError(ERROR_SNAPSHOT_INVALID, retryable=False)
            if expected_total is None:
                expected_total = page.total_chunks
            elif page.total_chunks != expected_total:
                raise TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False)

            chunks.extend(page.chunks)
            if page.next_cursor is None:
                break
            if page.next_cursor in seen_cursors:
                raise TaskFailedError(ERROR_SNAPSHOT_INVALID, retryable=False)
            seen_cursors.add(page.next_cursor)
            cursor = page.next_cursor

        if expected_total == 0 and not chunks:
            return []
        if len(chunks) != snap.body_chunk_count or (
            expected_total is not None and len(chunks) != expected_total
        ):
            raise TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False)
        return chunks

    async def _resolve_initial_targets(
        self, requested_generation_id: uuid.UUID | None
    ) -> list[_GenerationRow]:
        try:
            async with self._db.pool.connection() as conn:
                if requested_generation_id is not None:
                    cur = await conn.execute(
                        "SELECT id, embedding_model, dimension, status "
                        "FROM ai_index.index_generation WHERE id = %s",
                        (requested_generation_id,),
                    )
                    rows = _parse_generation_rows(await cur.fetchall())
                    if not rows or rows[0].status not in _WRITABLE_STATUSES:
                        raise TaskFailedError(ERROR_INDEX_GENERATION_STALE, retryable=False)
                    return rows

                cur = await conn.execute(
                    "SELECT id, embedding_model, dimension, status "
                    "FROM ai_index.index_generation "
                    "WHERE status IN ('ACTIVE', 'BUILDING') "
                    "ORDER BY status"
                )
                rows = _parse_generation_rows(await cur.fetchall())
                if not rows:
                    raise TaskFailedError(ERROR_INDEX_GENERATION_UNAVAILABLE, retryable=True)
                return rows
        except TaskFailedError:
            raise
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("index generation lookup failed error=%s", type(exc).__name__)
            raise TaskFailedError(ERROR_INDEX_STORAGE_UNAVAILABLE, retryable=True) from None

    async def _persist(
        self,
        *,
        snap: IndexDocumentSnapshot,
        chunks: Sequence[DocumentBodyChunk],
        vectors_by_generation: Mapping[uuid.UUID, Mapping[int, str]],
        has_embedding_rows: bool,
    ) -> list[_GenerationRow]:
        try:
            async with self._db.pool.connection() as conn, conn.transaction():
                if snap.generation_id is not None:
                    cur = await conn.execute(
                        "SELECT id, embedding_model, dimension, status "
                        "FROM ai_index.index_generation WHERE id = %s FOR SHARE",
                        (snap.generation_id,),
                    )
                    targets = _parse_generation_rows(await cur.fetchall())
                    if not targets or targets[0].status not in _WRITABLE_STATUSES:
                        raise TaskFailedError(ERROR_INDEX_GENERATION_STALE, retryable=False)
                else:
                    cur = await conn.execute(
                        "SELECT id, embedding_model, dimension, status "
                        "FROM ai_index.index_generation "
                        "WHERE status IN ('ACTIVE', 'BUILDING') "
                        "ORDER BY status FOR SHARE"
                    )
                    targets = _parse_generation_rows(await cur.fetchall())
                    if not targets:
                        raise TaskFailedError(ERROR_INDEX_GENERATION_UNAVAILABLE, retryable=True)

                if has_embedding_rows and any(
                    gen.generation_id not in vectors_by_generation for gen in targets
                ):
                    # A generation started after the initial read. Retry with its own model.
                    raise TaskFailedError(ERROR_INDEX_GENERATION_STALE, retryable=True)

                for gen in targets:
                    # Claim this document version before replacing its chunks. The upsert
                    # serializes concurrent writes to the same generation/document pair.
                    fenced = await conn.execute(
                        "INSERT INTO ai_index.indexed_document AS current ("
                        "  generation_id, project_id, document_id, body_snapshot_id,"
                        "  row_count, indexed_at, body_version"
                        ") VALUES (%s, %s, %s, %s, %s, now(), %s) "
                        "ON CONFLICT (generation_id, document_id) DO UPDATE SET "
                        "  project_id = EXCLUDED.project_id, "
                        "  body_snapshot_id = EXCLUDED.body_snapshot_id, "
                        "  row_count = EXCLUDED.row_count, "
                        "  indexed_at = now(), "
                        "  body_version = EXCLUDED.body_version "
                        "WHERE current.body_version < EXCLUDED.body_version "
                        "   OR (current.body_version = EXCLUDED.body_version "
                        "       AND current.body_snapshot_id = EXCLUDED.body_snapshot_id)",
                        (
                            gen.generation_id,
                            snap.project_id,
                            snap.document_id,
                            snap.body_snapshot_id,
                            len(chunks),
                            snap.body_version,
                        ),
                    )
                    if fenced.rowcount != 1:
                        raise TaskFailedError(ERROR_INDEX_SNAPSHOT_STALE, retryable=False)
                    vector_by_chunk_no = vectors_by_generation.get(gen.generation_id, {})
                    await conn.execute(
                        "DELETE FROM ai_index.chunk WHERE generation_id = %s AND document_id = %s",
                        (gen.generation_id, snap.document_id),
                    )
                    if chunks:
                        rows = [
                            (
                                uuid.uuid4(),
                                gen.generation_id,
                                snap.project_id,
                                snap.document_id,
                                snap.body_snapshot_id,
                                chunk.chunk_no,
                                chunk.chunk_type.value,
                                chunk.parent_chunk_no,
                                chunk.heading[:300] if chunk.heading is not None else None,
                                chunk.content,
                                chunk.content_hash,
                                json.dumps(chunk.metadata, ensure_ascii=False),
                                None
                                if chunk.chunk_type is ChunkType.PARENT
                                else vector_by_chunk_no[chunk.chunk_no],
                            )
                            for chunk in chunks
                        ]
                        async with conn.cursor() as insert_cur:
                            await insert_cur.executemany(
                                "INSERT INTO ai_index.chunk ("
                                "  id, generation_id, project_id, document_id, body_snapshot_id, "
                                "  chunk_no, chunk_type, parent_chunk_no, heading, content, "
                                "  content_hash, metadata, embedding"
                                ") VALUES ("
                                "  %s, %s, %s, %s, %s, "
                                "  %s, %s, %s, %s, %s, "
                                "  %s, %s::jsonb, %s::public.vector"
                                ")",
                                rows,
                            )
                return targets
        except TaskFailedError:
            raise
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("index document persist failed error=%s", type(exc).__name__)
            raise TaskFailedError(ERROR_INDEX_STORAGE_UNAVAILABLE, retryable=True) from None


class DeleteDocumentIndexHandler:
    """``DELETE_DOCUMENT_INDEX`` 任务处理器。"""

    def __init__(self, *, java: JavaInternalClient, db: PoolProvider) -> None:
        self._java: Final[JavaInternalClient] = java
        self._db: Final[PoolProvider] = db

    async def __call__(self, context: TaskContext) -> Mapping[str, object]:
        started = time.perf_counter()
        try:
            return await self._run(context)
        finally:
            observe_histogram(
                AI_INDEX_TASK_DURATION,
                time.perf_counter() - started,
                taskType=TASK_DELETE_DOCUMENT_INDEX,
                caller_type=CALLER_TYPE,
            )

    async def _run(self, context: TaskContext) -> Mapping[str, object]:
        try:
            snap = await self._java.snapshot(
                context.task_id,
                bearer_token=context.token,
                task_type="DELETE_DOCUMENT_INDEX",
            )
        except JavaInternalError as exc:
            raise _map_java_snapshot_error(exc) from None
        if not isinstance(snap, DeleteDocumentIndexSnapshot):
            raise TaskFailedError(ERROR_SNAPSHOT_INVALID, retryable=False)
        _validate_project_claim(context, snap.project_id)

        try:
            async with self._db.pool.connection() as conn, conn.transaction():
                cur = await conn.execute(
                    "DELETE FROM ai_index.chunk WHERE project_id = %s AND document_id = %s",
                    (snap.project_id, snap.document_id),
                )
                deleted_rows = max(0, cur.rowcount or 0)
                await conn.execute(
                    "DELETE FROM ai_index.indexed_document "
                    "WHERE project_id = %s AND document_id = %s",
                    (snap.project_id, snap.document_id),
                )
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("delete document index failed error=%s", type(exc).__name__)
            raise TaskFailedError(ERROR_INDEX_STORAGE_UNAVAILABLE, retryable=True) from None

        logger.info(
            "delete document index succeeded task_id=%s deleted_rows=%d",
            context.task_id,
            deleted_rows,
        )
        return {
            "documentId": str(snap.document_id),
            "deletedRows": deleted_rows,
        }


class ReindexGenerationHandler:
    """``REINDEX_GENERATION`` 处理器：租借 EMBEDDING → 探测 1024 维 → 建 generation。"""

    def __init__(self, *, java: JavaInternalClient, kernel: ModelKernel, db: PoolProvider) -> None:
        self._java: Final[JavaInternalClient] = java
        self._kernel: Final[ModelKernel] = kernel
        self._db: Final[PoolProvider] = db

    async def __call__(self, context: TaskContext) -> Mapping[str, object]:
        started = time.perf_counter()
        try:
            return await self._run(context)
        finally:
            observe_histogram(
                AI_INDEX_TASK_DURATION,
                time.perf_counter() - started,
                taskType=TASK_REINDEX_GENERATION,
                caller_type=CALLER_TYPE,
            )

    async def _run(self, context: TaskContext) -> Mapping[str, object]:
        try:
            await self._java.snapshot(
                context.task_id,
                bearer_token=context.token,
                task_type="REINDEX_GENERATION",
            )
        except JavaInternalError as exc:
            raise _map_java_snapshot_error(exc) from None

        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=None,
            project_id=None,
            task_id=uuid.UUID(context.task_id),
            bearer_token=context.token,
        )

        def _check_config_dim(lease: Lease) -> None:
            if lease.params.dimensions is not None and lease.params.dimensions != VECTOR_DIMENSION:
                raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False)

        try:
            probe = await self._kernel.embed_texts(
                scope,
                ["dimension probe"],
                task_type=EmbeddingTaskType.DOCUMENT,
                expected_dimension=VECTOR_DIMENSION,
                on_lease=_check_config_dim,
            )
        except EmbeddingDimensionMismatchError:
            raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False) from None
        except AppError as exc:
            raise _map_app_error(exc) from None

        if probe.dimension != VECTOR_DIMENSION:
            raise TaskFailedError(ERROR_EMBEDDING_DIMENSION_MISMATCH, retryable=False)

        # The task ID is stable across worker retries and lets Java resolve its model
        # before the generation result has been delivered.
        generation_id = uuid.UUID(context.task_id)
        try:
            async with self._db.pool.connection() as conn, conn.transaction():
                cursor = await conn.execute(
                    "INSERT INTO ai_index.index_generation "
                    "(id, embedding_model, dimension, status) "
                    "VALUES (%s, %s, %s, 'BUILDING') "
                    "ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id "
                    "WHERE ai_index.index_generation.embedding_model = EXCLUDED.embedding_model "
                    "AND ai_index.index_generation.dimension = EXCLUDED.dimension "
                    "AND ai_index.index_generation.status IN ('ACTIVE', 'BUILDING')",
                    (generation_id, probe.model, VECTOR_DIMENSION),
                )
                if cursor.rowcount == 0:
                    raise TaskFailedError(ERROR_INDEX_GENERATION_CONFLICT, retryable=False)
        except UniqueViolation:
            raise TaskFailedError(ERROR_INDEX_GENERATION_CONFLICT, retryable=False) from None
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("reindex create generation failed error=%s", type(exc).__name__)
            raise TaskFailedError(ERROR_INDEX_STORAGE_UNAVAILABLE, retryable=True) from None

        logger.info(
            "reindex generation created task_id=%s generation_id=%s model=%s",
            context.task_id,
            generation_id,
            probe.model,
        )
        return {
            "generationId": str(generation_id),
            "embeddingModel": probe.model,
            "dimension": VECTOR_DIMENSION,
        }


def build_index_handlers(
    *,
    java: JavaInternalClient,
    kernel: ModelKernel,
    db: PoolProvider,
) -> dict[str, TaskHandler]:
    """构造三类索引任务处理器映射，用于注册进 ``StreamConsumer``。"""

    return {
        TASK_INDEX_DOCUMENT: IndexDocumentHandler(java=java, kernel=kernel, db=db),
        TASK_DELETE_DOCUMENT_INDEX: DeleteDocumentIndexHandler(java=java, db=db),
        TASK_REINDEX_GENERATION: ReindexGenerationHandler(java=java, kernel=kernel, db=db),
    }


__all__ = [
    "ERROR_BODY_NOT_FOUND",
    "ERROR_EMBEDDING_DIMENSION_MISMATCH",
    "ERROR_EMBEDDING_MODEL_MISMATCH",
    "ERROR_INDEX_GENERATION_CONFLICT",
    "ERROR_INDEX_GENERATION_STALE",
    "ERROR_INDEX_GENERATION_UNAVAILABLE",
    "ERROR_INDEX_SNAPSHOT_STALE",
    "ERROR_INDEX_STORAGE_UNAVAILABLE",
    "ERROR_SNAPSHOT_INVALID",
    "ERROR_SNAPSHOT_UNAVAILABLE",
    "ERROR_TASK_TOKEN_INVALID",
    "TASK_DELETE_DOCUMENT_INDEX",
    "TASK_INDEX_DOCUMENT",
    "TASK_REINDEX_GENERATION",
    "DeleteDocumentIndexHandler",
    "IndexDocumentHandler",
    "ReindexGenerationHandler",
    "build_index_handlers",
]
