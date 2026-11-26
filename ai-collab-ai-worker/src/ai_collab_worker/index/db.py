"""``ai_index`` 数据库连接与启动检查（P2 契约 §5.1、§5.2，P2-R1 第 1 条）。

- 连接串只来自 ``AI_INDEX_DATABASE_URL``（敏感值：``SecretStr``，密码登记进日志脱敏）；
- 启动时依次：校验 ``current_user = 'ai_worker'`` → 检查 pgvector 版本 ≥ 0.8 → 执行迁移。
  任意一步不满足，:meth:`IndexDatabase.readiness_failure` 返回固定原因，``/readyz`` 503、
  不接索引任务；原因文本不含连接串或异常正文；
- 向量以文本字面量 ``'[x,y,...]'::vector`` 写入与查询（不引入 ``pgvector`` Python 包，
  避免带进 numpy）。
"""

from __future__ import annotations

import logging
import math
import re
from collections.abc import Sequence
from typing import Final

from psycopg import AsyncConnection
from psycopg.errors import Error as PsycopgError
from psycopg_pool import AsyncConnectionPool
from pydantic import SecretStr

from ai_collab_worker.index.migrations import MigrationError, apply_migrations

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 允许的连接角色（契约 §5.2）。
EXPECTED_ROLE: Final[str] = "ai_worker"

#: pgvector 最低版本（契约 §5.1 / §12 D7：iterative scan 需要 0.8）。
MIN_PGVECTOR_VERSION: Final[tuple[int, int]] = (0, 8)

#: 向量列维度（Q7：DDL 固定 1024）。
VECTOR_DIMENSION: Final[int] = 1024

#: 就绪失败原因（固定文本，不含任何值）。
READY_NOT_STARTED: Final[str] = "ai_index_not_started"
READY_UNREACHABLE: Final[str] = "ai_index_unreachable"
READY_WRONG_ROLE: Final[str] = "ai_index_wrong_role"
READY_PGVECTOR_MISSING: Final[str] = "pgvector_missing"
READY_PGVECTOR_TOO_OLD: Final[str] = "pgvector_too_old"
READY_MIGRATION_FAILED: Final[str] = "ai_index_migration_failed"

_VERSION_PATTERN: Final[re.Pattern[str]] = re.compile(r"^(\d+)\.(\d+)")


def parse_extversion(raw: str | None) -> tuple[int, int] | None:
    """``'0.8.6'`` → ``(0, 8)``；无法解析返回 ``None``。"""

    if not raw:
        return None
    match = _VERSION_PATTERN.match(raw.strip())
    if match is None:
        return None
    return int(match.group(1)), int(match.group(2))


def pgvector_failure(raw: str | None) -> str | None:
    """按 ``pg_extension.extversion`` 给出就绪失败原因；满足 ≥ 0.8 时返回 ``None``。"""

    version = parse_extversion(raw)
    if version is None:
        return READY_PGVECTOR_MISSING
    if version < MIN_PGVECTOR_VERSION:
        return READY_PGVECTOR_TOO_OLD
    return None


def vector_literal(values: Sequence[float], *, dimension: int = VECTOR_DIMENSION) -> str:
    """把向量写成 pgvector 文本字面量；维度不符或含非有限值时抛 ``ValueError``。

    用 ``repr(float)`` 保证往返精度（最短可还原表示）。
    """

    if len(values) != dimension:
        msg = f"向量维度 {len(values)} 与列维度 {dimension} 不一致"
        raise ValueError(msg)
    parts: list[str] = []
    for value in values:
        number = float(value)
        if not math.isfinite(number):
            msg = "向量含非有限值"
            raise ValueError(msg)
        parts.append(repr(number))
    return "[" + ",".join(parts) + "]"


class IndexDatabase:
    """``ai_index`` 连接池 + 启动检查；生命周期跟随 app。"""

    def __init__(
        self,
        url: SecretStr,
        *,
        min_size: int = 1,
        max_size: int = 8,
        run_migrations: bool = True,
    ) -> None:
        self._url = url
        self._run_migrations = run_migrations
        self._pool: AsyncConnectionPool[AsyncConnection[tuple[object, ...]]] = AsyncConnectionPool(
            conninfo=url.get_secret_value(),
            min_size=min_size,
            max_size=max_size,
            open=False,
            kwargs={"autocommit": False},
        )
        self._failure: str | None = READY_NOT_STARTED

    @property
    def pool(self) -> AsyncConnectionPool[AsyncConnection[tuple[object, ...]]]:
        return self._pool

    def readiness_failure(self) -> str | None:
        return self._failure

    async def start(self) -> None:
        """打开连接池并执行启动检查；失败只记录固定原因，不抛出（由 ``/readyz`` 暴露）。"""

        self._failure = await self._startup_failure()
        if self._failure is None:
            logger.info("ai_index ready")
        else:
            logger.warning("ai_index not ready reason=%s", self._failure)

    async def aclose(self) -> None:
        await self._pool.close()

    async def _startup_failure(self) -> str | None:
        try:
            async with await AsyncConnection.connect(
                self._url.get_secret_value(), autocommit=True
            ) as conn:
                cursor = await conn.execute(
                    "SELECT current_user, "
                    "(SELECT extversion FROM pg_extension WHERE extname = 'vector')"
                )
                row = await cursor.fetchone()
                role = str(row[0]) if row else None
                if role != EXPECTED_ROLE:
                    return READY_WRONG_ROLE
                extversion = row[1] if row else None
                failure = pgvector_failure(str(extversion) if extversion is not None else None)
                if failure is not None:
                    return failure
                if self._run_migrations:
                    try:
                        executed = await apply_migrations(conn)
                    except MigrationError as exc:
                        logger.error("ai_index migration refused detail=%s", exc)
                        return READY_MIGRATION_FAILED
                    if executed:
                        logger.info("ai_index migrations applied versions=%s", list(executed))
            await self._pool.open(wait=True, timeout=10.0)
        except (PsycopgError, OSError, TimeoutError) as exc:
            # 异常文本可能带主机名或用户名；只记类型。
            logger.warning("ai_index connect failed error=%s", type(exc).__name__)
            return READY_UNREACHABLE
        return None


def database_password(url: SecretStr | None) -> str | None:
    """连接串里的密码（登记进日志脱敏）；支持 URL 与 ``key=value`` 两种写法。"""

    if url is None:
        return None
    from psycopg.conninfo import conninfo_to_dict

    try:
        params = conninfo_to_dict(url.get_secret_value())
    except PsycopgError:
        return None
    password = params.get("password")
    return str(password) if password else None


__all__ = [
    "EXPECTED_ROLE",
    "MIN_PGVECTOR_VERSION",
    "READY_MIGRATION_FAILED",
    "READY_NOT_STARTED",
    "READY_PGVECTOR_MISSING",
    "READY_PGVECTOR_TOO_OLD",
    "READY_UNREACHABLE",
    "READY_WRONG_ROLE",
    "VECTOR_DIMENSION",
    "IndexDatabase",
    "database_password",
    "parse_extversion",
    "pgvector_failure",
    "vector_literal",
]
