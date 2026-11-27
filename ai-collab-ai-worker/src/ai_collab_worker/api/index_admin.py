"""P2 Generation 管理接口（P2 契约 §4.6、§12.6 R4-3/R4-4）。

全部端点要求 scope ``index:admin``（Java → Python），响应禁止缓存：

- ``GET  /v1/index/generations``
- ``POST /v1/index/generations/{id}:reconcile``（只读校验与 R4-3 ``PRUNE`` 清理双模式）
- ``POST /v1/index/generations/{id}:activate``（``BUILDING → ACTIVE`` + 后台清理）
- ``POST /v1/index/generations/{id}:abandon``（``BUILDING → FAILED``，幂等）
- ``POST /v1/index/generations/{id}:reopen``（R4-4 ``RETIRED → BUILDING``，幂等与冲突 409）
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import Coroutine, Mapping
from datetime import datetime
from typing import Any, Final

from fastapi import APIRouter, Request
from psycopg.errors import Error as PsycopgError
from psycopg.errors import UniqueViolation
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from pydantic.alias_generators import to_camel

from ai_collab_worker.api.auth import require_token
from ai_collab_worker.api.errors import IndexAdminError
from ai_collab_worker.observability.metrics import AI_INDEX_GENERATION_ROWS, set_gauge

logger: Final[logging.Logger] = logging.getLogger(__name__)

router: Final[APIRouter] = APIRouter(tags=["index-admin"])

CALLER_TYPE: Final[str] = "PYTHON"
SCOPE_INDEX_ADMIN: Final[str] = "index:admin"
MAX_RECONCILE_DOCUMENT_IDS: Final[int] = 5000

CODE_VALIDATION_ERROR: Final[str] = "VALIDATION_ERROR"
CODE_GENERATION_NOT_FOUND: Final[str] = "GENERATION_NOT_FOUND"
CODE_GENERATION_CONFLICT: Final[str] = "INDEX_GENERATION_CONFLICT"
CODE_STORAGE_UNAVAILABLE: Final[str] = "INDEX_STORAGE_UNAVAILABLE"

_LOCK_KEY: Final[int] = 0x6169_5F69_6E64_6578  # 'ai_index' 事务级并发状态锁
_PRUNABLE_STATUSES: Final[frozenset[str]] = frozenset({"ACTIVE", "BUILDING", "RETIRED"})
_ALL_STATUSES: Final[tuple[str, ...]] = ("BUILDING", "ACTIVE", "RETIRED", "FAILED")


class _AdminModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )


class _ReadOnlyReconcileRequest(_AdminModel):
    document_ids: tuple[uuid.UUID, ...] = Field(default=())


class _PruneRange(_AdminModel):
    after_document_id: uuid.UUID | None = None
    through_document_id: uuid.UUID | None = None


class _PruneReconcileRequest(_AdminModel):
    mode: str
    as_of: datetime
    range: _PruneRange
    document_ids: tuple[uuid.UUID, ...] = Field(default=())

    @field_validator("as_of")
    @classmethod
    def _require_tz(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            msg = "asOf 必须带时区"
            raise ValueError(msg)
        return value


def _parse_generation_id(raw_id: str) -> uuid.UUID:
    try:
        return uuid.UUID(raw_id)
    except ValueError:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="generationId 非法",
            reason="invalid_generation_id",
        ) from None


def _get_index_db(request: Request) -> Any:
    db = getattr(request.app.state, "index_db", None)
    if db is None:
        raise IndexAdminError(
            status=503,
            code=CODE_STORAGE_UNAVAILABLE,
            message="索引数据库未配置",
            retryable=True,
            reason="index_db_unconfigured",
        )
    return db


def _schedule_retired_cleanup(request: Request, db: Any) -> None:
    """在响应返回后后台清理冗余 RETIRED generation 的索引行（只保留最近 1 个 RETIRED）。"""

    tasks = getattr(request.app.state, "background_tasks", None)
    if not isinstance(tasks, set):
        tasks = set()
        request.app.state.background_tasks = tasks

    coro: Coroutine[Any, Any, None] = cleanup_retired_generations(db)
    task = asyncio.create_task(coro, name="ai-index-retired-cleanup")
    tasks.add(task)
    task.add_done_callback(tasks.discard)


async def cleanup_retired_generations(db: Any) -> None:
    """清理除最近 1 个外的已退役 generation 的 chunk / indexed_document 行（§4.6）。"""

    try:
        async with db.pool.connection() as conn, conn.transaction():
            cur = await conn.execute(
                "SELECT id FROM ai_index.index_generation "
                "WHERE status = 'RETIRED' "
                "ORDER BY COALESCE(activated_at, created_at) DESC, id DESC"
            )
            retired_ids = [
                row[0] if isinstance(row[0], uuid.UUID) else uuid.UUID(str(row[0]))
                for row in await cur.fetchall()
            ]
            stale_ids = retired_ids[1:]
            if not stale_ids:
                return
            await conn.execute(
                "DELETE FROM ai_index.chunk WHERE generation_id = ANY(%s::uuid[])",
                (stale_ids,),
            )
            await conn.execute(
                "DELETE FROM ai_index.indexed_document WHERE generation_id = ANY(%s::uuid[])",
                (stale_ids,),
            )
            logger.info("cleaned retired generation index rows count=%d", len(stale_ids))
    except (PsycopgError, OSError, TimeoutError) as exc:
        logger.warning("retired generation cleanup failed error=%s", type(exc).__name__)


def _refresh_generation_row_metrics(rows: list[dict[str, Any]]) -> None:
    totals: dict[str, int] = dict.fromkeys(_ALL_STATUSES, 0)
    for item in rows:
        status = str(item["status"])
        if status in totals:
            totals[status] += int(item["rowCount"])
    for status, total in totals.items():
        set_gauge(AI_INDEX_GENERATION_ROWS, total, status=status, caller_type=CALLER_TYPE)


def _storage_error(exc: Exception) -> IndexAdminError:
    logger.warning("index admin database error type=%s", type(exc).__name__)
    return IndexAdminError(
        status=503,
        code=CODE_STORAGE_UNAVAILABLE,
        message="索引数据库暂不可用",
        retryable=True,
        reason="index_storage_unavailable",
    )


def _iso_or_none(value: object) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.isoformat()
    return str(value)


@router.get("/v1/index/generations")
async def list_generations(request: Request) -> dict[str, Any]:
    require_token(request, scope=SCOPE_INDEX_ADMIN)
    db = _get_index_db(request)
    try:
        async with db.pool.connection() as conn:
            cur = await conn.execute(
                "SELECT "
                "  g.id, g.embedding_model, g.dimension, g.status, "
                "  g.created_at, g.activated_at, "
                "  COALESCE(d.row_count, 0)::bigint AS row_count, "
                "  COALESCE(d.doc_count, 0)::bigint AS document_count "
                "FROM ai_index.index_generation g "
                "LEFT JOIN ("
                "  SELECT generation_id, "
                "         COALESCE(SUM(row_count), 0)::bigint AS row_count, "
                "         COUNT(*)::bigint AS doc_count "
                "  FROM ai_index.indexed_document "
                "  GROUP BY generation_id"
                ") d ON d.generation_id = g.id "
                "ORDER BY g.created_at DESC, g.id DESC"
            )
            raw_rows = await cur.fetchall()
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    generations = [
        {
            "generationId": str(row[0]),
            "embeddingModel": str(row[1]),
            "dimension": int(row[2]),
            "status": str(row[3]),
            "createdAt": _iso_or_none(row[4]),
            "activatedAt": _iso_or_none(row[5]),
            "rowCount": int(row[6]),
            "documentCount": int(row[7]),
        }
        for row in raw_rows
    ]
    _refresh_generation_row_metrics(generations)
    return {"generations": generations}


async def _read_json_mapping(request: Request) -> Mapping[str, Any]:
    try:
        raw = await request.json()
    except Exception:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="请求体必须是合法 JSON 对象",
            reason="invalid_json",
        ) from None
    if not isinstance(raw, Mapping):
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="请求体必须是 JSON 对象",
            reason="invalid_body_type",
        )
    return raw


def _validate_prune_request(raw: Mapping[str, Any]) -> _PruneReconcileRequest:
    try:
        parsed = _PruneReconcileRequest.model_validate(raw)
    except ValidationError:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="PRUNE 请求参数不合法",
            reason="invalid_prune_request",
        ) from None
    if parsed.mode != "PRUNE":
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="不支持的 reconcile mode",
            reason="unsupported_mode",
        )
    if len(parsed.document_ids) > MAX_RECONCILE_DOCUMENT_IDS:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message=f"documentIds 不能超过 {MAX_RECONCILE_DOCUMENT_IDS}",
            reason="document_ids_too_many",
        )
    after = parsed.range.after_document_id
    through = parsed.range.through_document_id
    if after is not None and through is not None and after.int >= through.int:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="range.afterDocumentId 必须严格小于 range.throughDocumentId",
            reason="invalid_range_order",
        )
    for doc_id in parsed.document_ids:
        if after is not None and doc_id.int <= after.int:
            raise IndexAdminError(
                status=400,
                code=CODE_VALIDATION_ERROR,
                message="documentIds 包含低于区间下界的 ID",
                reason="document_id_below_range",
            )
        if through is not None and doc_id.int > through.int:
            raise IndexAdminError(
                status=400,
                code=CODE_VALIDATION_ERROR,
                message="documentIds 包含超出区间上界的 ID",
                reason="document_id_above_range",
            )
    return parsed


@router.post("/v1/index/generations/{generation_id}:reconcile")
async def reconcile_generation(generation_id: str, request: Request) -> dict[str, Any]:
    require_token(request, scope=SCOPE_INDEX_ADMIN)
    gen_uuid = _parse_generation_id(generation_id)
    db = _get_index_db(request)
    raw = await _read_json_mapping(request)
    mode = raw.get("mode")
    if mode is None:
        return await _reconcile_read_only(db, gen_uuid, raw)
    if mode == "PRUNE":
        return await _reconcile_prune(db, gen_uuid, _validate_prune_request(raw))
    raise IndexAdminError(
        status=400,
        code=CODE_VALIDATION_ERROR,
        message="不支持的 reconcile mode",
        reason="unsupported_mode",
    )


async def _reconcile_read_only(
    db: Any, gen_uuid: uuid.UUID, raw: Mapping[str, Any]
) -> dict[str, Any]:
    try:
        parsed = _ReadOnlyReconcileRequest.model_validate(raw)
    except ValidationError:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message="reconcile 请求参数不合法",
            reason="invalid_reconcile_request",
        ) from None
    if len(parsed.document_ids) > MAX_RECONCILE_DOCUMENT_IDS:
        raise IndexAdminError(
            status=400,
            code=CODE_VALIDATION_ERROR,
            message=f"documentIds 不能超过 {MAX_RECONCILE_DOCUMENT_IDS}",
            reason="document_ids_too_many",
        )

    try:
        async with db.pool.connection() as conn:
            cur = await conn.execute(
                "SELECT id FROM ai_index.index_generation WHERE id = %s",
                (gen_uuid,),
            )
            if await cur.fetchone() is None:
                raise IndexAdminError(
                    status=404,
                    code=CODE_GENERATION_NOT_FOUND,
                    message="generation 不存在",
                    reason="generation_not_found",
                )
            if not parsed.document_ids:
                return {"missingDocumentIds": []}
            doc_cur = await conn.execute(
                "SELECT document_id FROM ai_index.indexed_document "
                "WHERE generation_id = %s AND document_id = ANY(%s::uuid[])",
                (gen_uuid, list(parsed.document_ids)),
            )
            present = {
                row[0] if isinstance(row[0], uuid.UUID) else uuid.UUID(str(row[0]))
                for row in await doc_cur.fetchall()
            }
    except IndexAdminError:
        raise
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    missing: list[str] = []
    seen: set[uuid.UUID] = set()
    for doc_id in parsed.document_ids:
        if doc_id not in present and doc_id not in seen:
            seen.add(doc_id)
            missing.append(str(doc_id))
    return {"missingDocumentIds": missing}


async def _reconcile_prune(
    db: Any,
    gen_uuid: uuid.UUID,
    req: _PruneReconcileRequest,
) -> dict[str, int]:
    after = req.range.after_document_id
    through = req.range.through_document_id
    keep_ids = list(req.document_ids)
    try:
        async with db.pool.connection() as conn, conn.transaction():
            cur = await conn.execute(
                "SELECT status FROM ai_index.index_generation WHERE id = %s FOR SHARE",
                (gen_uuid,),
            )
            row = await cur.fetchone()
            if row is None:
                raise IndexAdminError(
                    status=404,
                    code=CODE_GENERATION_NOT_FOUND,
                    message="generation 不存在",
                    reason="generation_not_found",
                )
            status = str(row[0])
            if status not in _PRUNABLE_STATUSES:
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="FAILED generation 不允许清理",
                    reason="generation_status_not_prunable",
                )
            prune_cur = await conn.execute(
                "WITH victims AS ("
                "  DELETE FROM ai_index.indexed_document d "
                "  WHERE d.generation_id = %s "
                "    AND d.indexed_at < %s "
                "    AND (%s::uuid IS NULL OR d.document_id > %s::uuid) "
                "    AND (%s::uuid IS NULL OR d.document_id <= %s::uuid) "
                "    AND NOT (d.document_id = ANY(%s::uuid[])) "
                "  RETURNING d.document_id"
                "), "
                "deleted_chunks AS ("
                "  DELETE FROM ai_index.chunk c "
                "  USING victims v "
                "  WHERE c.generation_id = %s AND c.document_id = v.document_id "
                "  RETURNING c.id"
                ") "
                "SELECT "
                "  (SELECT COUNT(*)::bigint FROM victims), "
                "  (SELECT COUNT(*)::bigint FROM deleted_chunks)",
                (
                    gen_uuid,
                    req.as_of,
                    after,
                    after,
                    through,
                    through,
                    keep_ids,
                    gen_uuid,
                ),
            )
            counts = await prune_cur.fetchone()
    except IndexAdminError:
        raise
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    deleted_docs = int(counts[0]) if counts else 0
    deleted_rows = int(counts[1]) if counts else 0
    logger.info(
        "index generation pruned generation_id=%s deleted_docs=%d deleted_rows=%d",
        gen_uuid,
        deleted_docs,
        deleted_rows,
    )
    return {"deletedDocuments": deleted_docs, "deletedRows": deleted_rows}


@router.post("/v1/index/generations/{generation_id}:activate")
async def activate_generation(generation_id: str, request: Request) -> dict[str, Any]:
    require_token(request, scope=SCOPE_INDEX_ADMIN)
    gen_uuid = _parse_generation_id(generation_id)
    db = _get_index_db(request)

    should_cleanup = False
    try:
        async with db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
            cur = await conn.execute(
                "SELECT id, status FROM ai_index.index_generation WHERE id = %s FOR UPDATE",
                (gen_uuid,),
            )
            row = await cur.fetchone()
            if row is None:
                raise IndexAdminError(
                    status=404,
                    code=CODE_GENERATION_NOT_FOUND,
                    message="generation 不存在",
                    reason="generation_not_found",
                )
            status = str(row[1])
            if status == "ACTIVE":
                return {"generationId": str(gen_uuid), "status": "ACTIVE"}
            if status != "BUILDING":
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="只有 BUILDING 状态的 generation 可以激活",
                    reason="generation_not_building",
                )
            await conn.execute(
                "UPDATE ai_index.index_generation "
                "SET status = 'RETIRED' "
                "WHERE status = 'ACTIVE' AND id <> %s",
                (gen_uuid,),
            )
            await conn.execute(
                "UPDATE ai_index.index_generation "
                "SET status = 'ACTIVE', activated_at = now() "
                "WHERE id = %s",
                (gen_uuid,),
            )
            should_cleanup = True
    except IndexAdminError:
        raise
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    if should_cleanup:
        _schedule_retired_cleanup(request, db)
    logger.info("index generation activated generation_id=%s", gen_uuid)
    return {"generationId": str(gen_uuid), "status": "ACTIVE"}


@router.post("/v1/index/generations/{generation_id}:abandon")
async def abandon_generation(generation_id: str, request: Request) -> dict[str, Any]:
    require_token(request, scope=SCOPE_INDEX_ADMIN)
    gen_uuid = _parse_generation_id(generation_id)
    db = _get_index_db(request)

    try:
        async with db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
            cur = await conn.execute(
                "SELECT id, status FROM ai_index.index_generation WHERE id = %s FOR UPDATE",
                (gen_uuid,),
            )
            row = await cur.fetchone()
            if row is None:
                raise IndexAdminError(
                    status=404,
                    code=CODE_GENERATION_NOT_FOUND,
                    message="generation 不存在",
                    reason="generation_not_found",
                )
            status = str(row[1])
            if status == "FAILED":
                return {"generationId": str(gen_uuid), "status": "FAILED"}
            if status != "BUILDING":
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="只有 BUILDING 状态的 generation 可以放弃",
                    reason="generation_not_building",
                )
            await conn.execute(
                "UPDATE ai_index.index_generation SET status = 'FAILED' WHERE id = %s",
                (gen_uuid,),
            )
    except IndexAdminError:
        raise
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    logger.info("index generation abandoned generation_id=%s", gen_uuid)
    return {"generationId": str(gen_uuid), "status": "FAILED"}


@router.post("/v1/index/generations/{generation_id}:reopen")
async def reopen_generation(generation_id: str, request: Request) -> dict[str, Any]:
    """R4-4：``RETIRED → BUILDING``；已有 BUILDING 或目标为 ACTIVE/FAILED 返回 409；幂等 200。"""

    require_token(request, scope=SCOPE_INDEX_ADMIN)
    gen_uuid = _parse_generation_id(generation_id)
    db = _get_index_db(request)

    try:
        async with db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
            cur = await conn.execute(
                "SELECT id, status FROM ai_index.index_generation WHERE id = %s FOR UPDATE",
                (gen_uuid,),
            )
            row = await cur.fetchone()
            if row is None:
                raise IndexAdminError(
                    status=404,
                    code=CODE_GENERATION_NOT_FOUND,
                    message="generation 不存在",
                    reason="generation_not_found",
                )
            status = str(row[1])
            if status == "BUILDING":
                return {"generationId": str(gen_uuid), "status": "BUILDING"}
            if status != "RETIRED":
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="只有 RETIRED 状态的 generation 可以重新打开",
                    reason="generation_not_retired",
                )
            other_cur = await conn.execute(
                "SELECT id FROM ai_index.index_generation WHERE status = 'BUILDING' AND id <> %s",
                (gen_uuid,),
            )
            if await other_cur.fetchone() is not None:
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="已有另一个 BUILDING generation",
                    reason="building_generation_exists",
                )
            try:
                await conn.execute(
                    "UPDATE ai_index.index_generation SET status = 'BUILDING' WHERE id = %s",
                    (gen_uuid,),
                )
            except UniqueViolation:
                raise IndexAdminError(
                    status=409,
                    code=CODE_GENERATION_CONFLICT,
                    message="已有另一个 BUILDING generation",
                    reason="building_generation_exists",
                ) from None
    except IndexAdminError:
        raise
    except (PsycopgError, OSError, TimeoutError) as exc:
        raise _storage_error(exc) from None

    logger.info("index generation reopened generation_id=%s", gen_uuid)
    return {"generationId": str(gen_uuid), "status": "BUILDING"}


__all__ = [
    "cleanup_retired_generations",
    "router",
]
