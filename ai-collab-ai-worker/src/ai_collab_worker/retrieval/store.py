"""``ai_index`` PostgreSQL 检索仓储（P2 契约 §5.1、§5.2、R2-1）。

严格遵循：
- **只查 ``ai_index``**：绝不访问业务表（``project_document`` 等）；
- **向量文本字面量**：通过 ``'%s::public.vector'`` 绑定，不引入 ``pgvector``/``numpy``；
- **HNSW 迭代扫描**：每个 Dense 检索事务执行 ``SET LOCAL hnsw.iterative_scan = relaxed_order``，
  并用 ``WITH c AS MATERIALIZED (...) SELECT * FROM c ORDER BY distance`` 修正乱序；
- **pg_trgm 词法扫描**：检索事务内执行 ``SET LOCAL pg_trgm.similarity_threshold = 0.03``，
  通过 ``%%``（psycopg 3 转义）走 GIN ``gin_trgm_ops`` 索引；
- **固定范围过滤**：``generation_id = $g AND project_id = $p AND document_id = ANY($ids)``
  且排除 ``chunk_type = 'PARENT'``。
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Final, Protocol

from psycopg.errors import Error as PsycopgError

from ai_collab_worker.contract.knowledge_search import HeadingFilter
from ai_collab_worker.index.db import VECTOR_DIMENSION, vector_literal
from ai_collab_worker.retrieval.candidate import Candidate
from ai_collab_worker.retrieval.javatext import is_blank, trim

logger: Final[logging.Logger] = logging.getLogger(__name__)

PG_TRGM_SIMILARITY_THRESHOLD: Final[float] = 0.03


class RetrievalStorageError(Exception):
    """检索仓储底层数据库失败（由管线严格映射为 503，绝不静默降级）。"""

    def __init__(self, channel: str) -> None:
        super().__init__(f"retrieval_storage_error:{channel}")
        self.channel = channel


@dataclass(frozen=True)
class ActiveGeneration:
    """当前生效的 ``ACTIVE`` generation 元信息。"""

    generation_id: uuid.UUID
    embedding_model: str
    dimension: int


@dataclass(frozen=True)
class SectionChunk:
    """用于句子窗口或父块扩展的 ``ai_index.chunk`` 只读行（仅含非密钥字段与指纹）。"""

    document_id: uuid.UUID
    body_snapshot_id: uuid.UUID
    chunk_no: int
    chunk_type: str
    heading: str | None
    content: str
    content_hash: str


class RetrievalStore(Protocol):
    """检索仓储协议，便于单元测试替换 PostgreSQL 实现。"""

    async def get_active_generation(self) -> ActiveGeneration | None: ...

    async def search_dense(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query_vector: Sequence[float],
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: HeadingFilter,
    ) -> list[Candidate]: ...

    async def search_lexical(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query: str,
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: HeadingFilter,
    ) -> list[Candidate]: ...

    async def fetch_section_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        document_id: uuid.UUID,
        body_snapshot_id: uuid.UUID,
        heading: str | None,
    ) -> list[SectionChunk]: ...

    async def fetch_parent_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        targets: Sequence[tuple[uuid.UUID, int]],
    ) -> dict[tuple[uuid.UUID, int], SectionChunk]: ...


class _PoolProvider(Protocol):
    @property
    def pool(self) -> Any: ...


def escape_like(value: str) -> str:
    """转义 ``ILIKE ... ESCAPE '\\'`` 的通配符，防止用户输入干扰匹配语义。"""

    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _heading_clauses(heading_filter: HeadingFilter) -> tuple[str, list[Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if heading_filter.exact is not None:
        clauses.append("AND heading = %s")
        params.append(heading_filter.exact)
    if heading_filter.contains is not None:
        clauses.append("AND heading ILIKE %s ESCAPE '\\'")
        params.append(f"%{escape_like(heading_filter.contains)}%")
    return " ".join(clauses), params


def _clamp_score(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        return 0.0
    num = float(value)
    return min(1.0, max(0.0, num))


def _as_uuid(value: object) -> uuid.UUID:
    return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))


def _as_metadata(raw: object) -> MappingProxyType[str, object]:
    if isinstance(raw, dict):
        return MappingProxyType(dict(raw))
    return MappingProxyType({})


class PostgresRetrievalStore:
    """基于 ``IndexDatabase`` 连接池的只读检索仓储。"""

    def __init__(self, db: _PoolProvider) -> None:
        self._db: Final[_PoolProvider] = db

    async def get_active_generation(self) -> ActiveGeneration | None:
        try:
            async with self._db.pool.connection() as conn:
                cur = await conn.execute(
                    "SELECT id, embedding_model, dimension "
                    "FROM ai_index.index_generation "
                    "WHERE status = 'ACTIVE' "
                    "LIMIT 1"
                )
                row = await cur.fetchone()
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("active generation lookup failed error=%s", type(exc).__name__)
            raise RetrievalStorageError("generation") from None
        if row is None:
            return None
        return ActiveGeneration(
            generation_id=_as_uuid(row[0]),
            embedding_model=str(row[1]),
            dimension=int(row[2]),
        )

    async def search_dense(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query_vector: Sequence[float],
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: HeadingFilter,
    ) -> list[Candidate]:
        if not document_ids or top_k <= 0:
            return []
        vec_lit = vector_literal(query_vector, dimension=VECTOR_DIMENSION)
        heading_sql, heading_params = _heading_clauses(heading_filter)
        doc_ids = list(document_ids)

        try:
            async with self._db.pool.connection() as conn, conn.transaction():
                await conn.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
                sql = (
                    "WITH c AS MATERIALIZED ("
                    "  SELECT "
                    "    document_id, body_snapshot_id, chunk_no, chunk_type, parent_chunk_no, "
                    "    heading, content, content_hash, metadata, "
                    "    (embedding <=> %s::public.vector) AS distance "
                    "  FROM ai_index.chunk "
                    "  WHERE generation_id = %s "
                    "    AND project_id = %s "
                    "    AND document_id = ANY(%s::uuid[]) "
                    "    AND chunk_type <> 'PARENT' "
                    f"    {heading_sql} "
                    "  ORDER BY embedding <=> %s::public.vector "
                    "  LIMIT %s"
                    ") "
                    "SELECT "
                    "  document_id, body_snapshot_id, chunk_no, chunk_type, parent_chunk_no, "
                    "  heading, content, content_hash, metadata, "
                    "  GREATEST(0.0, LEAST(1.0, 1.0 - distance))::double precision AS similarity "
                    "FROM c "
                    "ORDER BY distance ASC, document_id ASC, chunk_no ASC"
                )
                params: list[Any] = [
                    vec_lit,
                    generation_id,
                    project_id,
                    doc_ids,
                    *heading_params,
                    vec_lit,
                    top_k,
                ]
                cur = await conn.execute(sql, tuple(params))
                rows = await cur.fetchall()
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("dense search failed error=%s", type(exc).__name__)
            raise RetrievalStorageError("dense") from None

        return [
            Candidate(
                document_id=_as_uuid(row[0]),
                body_snapshot_id=_as_uuid(row[1]),
                chunk_no=int(row[2]),
                chunk_type=str(row[3]),
                parent_chunk_no=int(row[4]) if row[4] is not None else None,
                heading=str(row[5]) if row[5] is not None else None,
                content=str(row[6]),
                content_hash=str(row[7]).lower(),
                metadata=_as_metadata(row[8]),
                dense_rank=rank,
                dense=_clamp_score(row[9]),
            )
            for rank, row in enumerate(rows, start=1)
        ]

    async def search_lexical(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        query: str,
        document_ids: Sequence[uuid.UUID],
        top_k: int,
        heading_filter: HeadingFilter,
    ) -> list[Candidate]:
        normalized = trim(query)
        if not document_ids or top_k <= 0 or is_blank(normalized):
            return []

        pattern = f"%{escape_like(normalized)}%"
        heading_sql, heading_params = _heading_clauses(heading_filter)
        doc_ids = list(document_ids)

        try:
            async with self._db.pool.connection() as conn, conn.transaction():
                await conn.execute(
                    f"SET LOCAL pg_trgm.similarity_threshold = {PG_TRGM_SIMILARITY_THRESHOLD}"
                )
                sql = (
                    "SELECT "
                    "  document_id, body_snapshot_id, chunk_no, chunk_type, parent_chunk_no, "
                    "  heading, content, content_hash, metadata, "
                    "  GREATEST("
                    "    COALESCE(public.similarity(content, %s), 0), "
                    "    CASE WHEN heading IS NOT NULL AND heading ILIKE %s ESCAPE '\\' "
                    "         THEN 0.8 ELSE 0 END, "
                    "    CASE WHEN content ILIKE %s ESCAPE '\\' "
                    "         THEN 0.6 ELSE 0 END"
                    "  )::double precision AS similarity "
                    "FROM ai_index.chunk "
                    "WHERE generation_id = %s "
                    "  AND project_id = %s "
                    "  AND document_id = ANY(%s::uuid[]) "
                    "  AND chunk_type <> 'PARENT' "
                    f"  {heading_sql} "
                    "  AND ("
                    "    content %% %s "
                    "    OR content ILIKE %s ESCAPE '\\' "
                    "    OR (heading IS NOT NULL AND ("
                    "      heading %% %s OR heading ILIKE %s ESCAPE '\\'"
                    "    ))"
                    "  ) "
                    "ORDER BY similarity DESC, document_id ASC, chunk_no ASC "
                    "LIMIT %s"
                )
                params: list[Any] = [
                    normalized,
                    pattern,
                    pattern,
                    generation_id,
                    project_id,
                    doc_ids,
                    *heading_params,
                    normalized,
                    pattern,
                    normalized,
                    pattern,
                    top_k,
                ]
                cur = await conn.execute(sql, tuple(params))
                rows = await cur.fetchall()
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("lexical search failed error=%s", type(exc).__name__)
            raise RetrievalStorageError("lexical") from None

        return [
            Candidate(
                document_id=_as_uuid(row[0]),
                body_snapshot_id=_as_uuid(row[1]),
                chunk_no=int(row[2]),
                chunk_type=str(row[3]),
                parent_chunk_no=int(row[4]) if row[4] is not None else None,
                heading=str(row[5]) if row[5] is not None else None,
                content=str(row[6]),
                content_hash=str(row[7]).lower(),
                metadata=_as_metadata(row[8]),
                lexical_rank=rank,
                lexical=_clamp_score(row[9]),
            )
            for rank, row in enumerate(rows, start=1)
        ]

    async def fetch_section_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        document_id: uuid.UUID,
        body_snapshot_id: uuid.UUID,
        heading: str | None,
    ) -> list[SectionChunk]:
        try:
            async with self._db.pool.connection() as conn:
                has_heading = heading is not None and not is_blank(heading)
                if has_heading:
                    parent_cur = await conn.execute(
                        "SELECT document_id, body_snapshot_id, chunk_no, chunk_type, "
                        "       heading, content, content_hash "
                        "FROM ai_index.chunk "
                        "WHERE generation_id = %s AND project_id = %s AND document_id = %s "
                        "  AND body_snapshot_id = %s AND chunk_type = 'PARENT' AND heading = %s "
                        "ORDER BY chunk_no ASC LIMIT 1",
                        (generation_id, project_id, document_id, body_snapshot_id, heading),
                    )
                else:
                    parent_cur = await conn.execute(
                        "SELECT document_id, body_snapshot_id, chunk_no, chunk_type, "
                        "       heading, content, content_hash "
                        "FROM ai_index.chunk "
                        "WHERE generation_id = %s AND project_id = %s AND document_id = %s "
                        "  AND body_snapshot_id = %s AND chunk_type = 'PARENT' "
                        "  AND (heading IS NULL OR heading = '') "
                        "ORDER BY chunk_no ASC LIMIT 1",
                        (generation_id, project_id, document_id, body_snapshot_id),
                    )
                parent_row = await parent_cur.fetchone()
                if parent_row is not None and not is_blank(str(parent_row[5])):
                    return [_row_to_section_chunk(parent_row)]

                if has_heading:
                    cur = await conn.execute(
                        "SELECT document_id, body_snapshot_id, chunk_no, chunk_type, "
                        "       heading, content, content_hash "
                        "FROM ai_index.chunk "
                        "WHERE generation_id = %s AND project_id = %s AND document_id = %s "
                        "  AND body_snapshot_id = %s AND heading = %s "
                        "  AND chunk_type NOT IN ('PARENT', 'SENTENCE') "
                        "ORDER BY chunk_no ASC",
                        (generation_id, project_id, document_id, body_snapshot_id, heading),
                    )
                else:
                    cur = await conn.execute(
                        "SELECT document_id, body_snapshot_id, chunk_no, chunk_type, "
                        "       heading, content, content_hash "
                        "FROM ai_index.chunk "
                        "WHERE generation_id = %s AND project_id = %s AND document_id = %s "
                        "  AND body_snapshot_id = %s AND (heading IS NULL OR heading = '') "
                        "  AND chunk_type NOT IN ('PARENT', 'SENTENCE') "
                        "ORDER BY chunk_no ASC",
                        (generation_id, project_id, document_id, body_snapshot_id),
                    )
                rows = await cur.fetchall()
                return [_row_to_section_chunk(row) for row in rows if not is_blank(str(row[5]))]
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("section chunks lookup failed error=%s", type(exc).__name__)
            raise RetrievalStorageError("expansion") from None

    async def fetch_parent_chunks(
        self,
        *,
        generation_id: uuid.UUID,
        project_id: uuid.UUID,
        targets: Sequence[tuple[uuid.UUID, int]],
    ) -> dict[tuple[uuid.UUID, int], SectionChunk]:
        if not targets:
            return {}
        doc_ids = list({doc_id for doc_id, _ in targets})
        chunk_nos = list({chunk_no for _, chunk_no in targets})
        target_set = set(targets)
        try:
            async with self._db.pool.connection() as conn:
                cur = await conn.execute(
                    "SELECT document_id, body_snapshot_id, chunk_no, chunk_type, "
                    "       heading, content, content_hash "
                    "FROM ai_index.chunk "
                    "WHERE generation_id = %s AND project_id = %s "
                    "  AND document_id = ANY(%s::uuid[]) "
                    "  AND chunk_no = ANY(%s::int[])",
                    (generation_id, project_id, doc_ids, chunk_nos),
                )
                rows = await cur.fetchall()
        except (PsycopgError, OSError, TimeoutError) as exc:
            logger.warning("parent chunks lookup failed error=%s", type(exc).__name__)
            raise RetrievalStorageError("expansion") from None

        result: dict[tuple[uuid.UUID, int], SectionChunk] = {}
        for row in rows:
            chunk = _row_to_section_chunk(row)
            key = (chunk.document_id, chunk.chunk_no)
            if key in target_set:
                result[key] = chunk
        return result


def _row_to_section_chunk(row: Sequence[Any]) -> SectionChunk:
    return SectionChunk(
        document_id=_as_uuid(row[0]),
        body_snapshot_id=_as_uuid(row[1]),
        chunk_no=int(row[2]),
        chunk_type=str(row[3]),
        heading=str(row[4]) if row[4] is not None else None,
        content=str(row[5]),
        content_hash=str(row[6]).lower(),
    )


__all__ = [
    "PG_TRGM_SIMILARITY_THRESHOLD",
    "ActiveGeneration",
    "PostgresRetrievalStore",
    "RetrievalStorageError",
    "RetrievalStore",
    "SectionChunk",
    "escape_like",
]
