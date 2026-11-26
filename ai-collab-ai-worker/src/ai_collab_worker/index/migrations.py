"""``ai_index`` 的版本化 SQL 迁移（P2 契约 §5.1，P2-R1 第 1 条：不用 Alembic / SQLAlchemy）。

规则：

- 迁移文件放在本包 ``sql/`` 目录下，文件名 ``NNNN_<name>.sql``（``NNNN`` 为从 1 起连续的
  版本号）。**已发布的迁移只能新增，不能修改**：执行过的版本在 ``ai_index.schema_migrations``
  里记下 SHA-256 校验和，启动时校验和不一致即拒绝继续（:class:`MigrationError`）；
- 执行前在独立会话上取 ``pg_advisory_lock``，两个实例同时启动时只有一个执行，另一个等锁后
  看到全部已执行、直接返回；
- 每个迁移在一个事务里执行（DDL + 写 ``schema_migrations`` 同时提交或同时回滚）；
- 只以 ``ai_worker`` 身份执行（调用方先校验 ``current_user``，见 :mod:`.db`）。

纯函数部分（发现、校验和、待执行计划）不依赖数据库，单元测试直接覆盖；真正执行 SQL 的
:func:`apply_migrations` 由集成测试覆盖。
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from importlib import resources
from typing import TYPE_CHECKING, Final

if TYPE_CHECKING:
    from psycopg import AsyncConnection

#: 迁移文件所在的包内目录。
MIGRATIONS_PACKAGE: Final[str] = "ai_collab_worker.index"
MIGRATIONS_DIR: Final[str] = "sql"

#: ``pg_advisory_lock`` 的键：ASCII ``ai_index`` 的 8 字节大端整数（正数，落在 bigint 内）。
ADVISORY_LOCK_KEY: Final[int] = 0x61695F696E646578

_FILE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"^(?P<version>\d{4})_(?P<name>[a-z0-9_]+)\.sql$"
)

BOOTSTRAP_SQL: Final[bytes] = b"""
CREATE TABLE IF NOT EXISTS ai_index.schema_migrations (
  version    integer PRIMARY KEY CHECK (version > 0),
  name       varchar(200) NOT NULL,
  checksum   char(64) NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
)
"""


class MigrationError(RuntimeError):
    """迁移不能继续：文件命名非法、版本不连续、已执行迁移被改动或数据库里有未知版本。

    消息只含版本号与文件名，不含 SQL 正文或连接信息。
    """


@dataclass(frozen=True)
class Migration:
    """一个迁移文件。``sql`` 已统一为 LF 换行，``checksum`` 据此计算。"""

    version: int
    name: str
    sql: str

    @property
    def checksum(self) -> str:
        return checksum_of(self.sql)

    @property
    def filename(self) -> str:
        return f"{self.version:04d}_{self.name}.sql"


@dataclass(frozen=True)
class AppliedMigration:
    """``schema_migrations`` 里的一行。"""

    version: int
    name: str
    checksum: str


def normalize_sql(text: str) -> str:
    """统一换行（CRLF/CR → LF），保证 Windows 检出与 Linux 计算出同一个校验和。"""

    return text.replace("\r\n", "\n").replace("\r", "\n")


def checksum_of(sql: str) -> str:
    return hashlib.sha256(normalize_sql(sql).encode("utf-8")).hexdigest()


def parse_migrations(files: Iterable[tuple[str, str]]) -> tuple[Migration, ...]:
    """把 ``(文件名, 内容)`` 解析成按版本排序的迁移；非 ``.sql`` 文件忽略。

    版本必须从 1 起连续、不重复，文件名必须符合 ``NNNN_name.sql``。
    """

    migrations: list[Migration] = []
    for filename, text in files:
        if not filename.endswith(".sql"):
            continue
        match = _FILE_PATTERN.match(filename)
        if match is None:
            msg = f"迁移文件名不合法：{filename}（应为 NNNN_name.sql，name 为小写字母数字下划线）"
            raise MigrationError(msg)
        migrations.append(
            Migration(int(match.group("version")), match.group("name"), normalize_sql(text))
        )
    migrations.sort(key=lambda migration: migration.version)
    for expected, migration in enumerate(migrations, start=1):
        if migration.version != expected:
            msg = f"迁移版本必须从 1 起连续：期望 {expected:04d}，实际 {migration.filename}"
            raise MigrationError(msg)
    return tuple(migrations)


def load_migrations() -> tuple[Migration, ...]:
    """读取包内全部迁移文件。"""

    directory = resources.files(MIGRATIONS_PACKAGE).joinpath(MIGRATIONS_DIR)
    files = [
        (entry.name, entry.read_text(encoding="utf-8"))
        for entry in directory.iterdir()
        if entry.is_file()
    ]
    return parse_migrations(files)


def pending_migrations(
    available: Sequence[Migration], applied: Iterable[AppliedMigration]
) -> tuple[Migration, ...]:
    """返回尚未执行的迁移；已执行迁移的校验和与文件不一致、或数据库里有本地没有的版本时报错。"""

    by_version: Mapping[int, Migration] = {migration.version: migration for migration in available}
    done: set[int] = set()
    for row in applied:
        migration = by_version.get(row.version)
        if migration is None:
            msg = f"数据库里有本地不存在的迁移版本 {row.version:04d}（{row.name}），拒绝降级运行"
            raise MigrationError(msg)
        if row.checksum.strip() != migration.checksum:
            msg = f"已执行的迁移 {migration.filename} 被修改（校验和不一致）；已发布迁移只能新增"
            raise MigrationError(msg)
        done.add(row.version)
    return tuple(migration for migration in available if migration.version not in done)


async def apply_migrations(
    conn: AsyncConnection[tuple[object, ...]],
    migrations: Sequence[Migration] | None = None,
) -> tuple[int, ...]:
    """在 ``conn`` 上加锁执行待执行迁移，返回本次执行的版本号。

    ``conn`` 必须是 ``autocommit=True`` 的独立连接（advisory lock 是会话级的，事务由本函数
    按迁移逐个开启）。
    """

    available = tuple(migrations) if migrations is not None else load_migrations()
    if not conn.autocommit:
        msg = "迁移连接必须是 autocommit 模式"
        raise MigrationError(msg)
    await conn.execute("SELECT pg_advisory_lock(%s)", (ADVISORY_LOCK_KEY,))
    try:
        await conn.execute(BOOTSTRAP_SQL)
        cursor = await conn.execute(
            "SELECT version, name, checksum FROM ai_index.schema_migrations ORDER BY version"
        )
        rows = await cursor.fetchall()
        applied = [AppliedMigration(int(str(v)), str(n), str(c)) for v, n, c in rows]
        executed: list[int] = []
        for migration in pending_migrations(available, applied):
            async with conn.transaction():
                await conn.execute(migration.sql.encode("utf-8"))
                await conn.execute(
                    "INSERT INTO ai_index.schema_migrations (version, name, checksum) "
                    "VALUES (%s, %s, %s)",
                    (migration.version, migration.name, migration.checksum),
                )
            executed.append(migration.version)
        return tuple(executed)
    finally:
        await conn.execute("SELECT pg_advisory_unlock(%s)", (ADVISORY_LOCK_KEY,))


__all__ = [
    "ADVISORY_LOCK_KEY",
    "BOOTSTRAP_SQL",
    "AppliedMigration",
    "Migration",
    "MigrationError",
    "apply_migrations",
    "checksum_of",
    "load_migrations",
    "normalize_sql",
    "parse_migrations",
    "pending_migrations",
]
