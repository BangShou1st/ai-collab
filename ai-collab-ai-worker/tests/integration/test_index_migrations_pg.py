"""ai_index 迁移与角色隔离的集成测试（真实 PostgreSQL + pgvector）。

**本机不执行**（只跑 ``--collect-only``）：需要 Docker，用 ``pgvector/pgvector:pg17`` 起一个容器，
先按 ``ai-collab-deploy/postgres/db-init.sql`` 的口径建 ``ai_worker`` 与 ``ai_index``，再以
``ai_worker`` 身份执行迁移。

覆盖：
- 契约 §5.2：``ai_worker`` 只靠 PUBLIC 默认 USAGE 就能建 ``vector_cosine_ops`` / ``gin_trgm_ops``
  索引；读写 ``public`` 业务表被拒；
- P2-R1 第 1 条：两个连接并发执行迁移只执行一次；重复执行幂等；已发布迁移被改动时拒绝；
- §5.1：``CHECK (dimension = 1024)``、PARENT ⇔ embedding IS NULL、唯一 ACTIVE/BUILDING。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Iterator
from typing import Any

import pytest

psycopg = pytest.importorskip("psycopg")
from psycopg import AsyncConnection  # noqa: E402
from testcontainers.community.postgres import PostgresContainer  # noqa: E402

from ai_collab_worker.index.db import IndexDatabase, vector_literal  # noqa: E402
from ai_collab_worker.index.migrations import (  # noqa: E402
    Migration,
    MigrationError,
    apply_migrations,
    load_migrations,
)

IMAGE = "pgvector/pgvector:pg17"
WORKER_PASSWORD = "itest-ai-worker-password"  # 仅测试容器内使用

#: 与 db-init.sql 同口径的最小初始化（超级用户执行）。
INIT_SQL = f"""
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE ROLE ai_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{WORKER_PASSWORD}';
CREATE SCHEMA ai_index AUTHORIZATION ai_worker;
REVOKE ALL ON SCHEMA ai_index FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM ai_worker;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE public.project_document (id uuid PRIMARY KEY);
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_worker;
ALTER ROLE ai_worker SET search_path = ai_index, public;
"""


@pytest.fixture(scope="module")
def postgres() -> Iterator[PostgresContainer]:
    with PostgresContainer(IMAGE, driver=None) as container:
        yield container


def _url(container: PostgresContainer, *, user: str, password: str) -> str:
    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    return f"postgresql://{user}:{password}@{host}:{port}/{container.dbname}"


@pytest.fixture(scope="module")
def worker_url(postgres: PostgresContainer) -> str:
    admin = _url(postgres, user=postgres.username, password=postgres.password)

    async def init() -> None:
        async with await AsyncConnection.connect(admin, autocommit=True) as conn:
            await conn.execute(INIT_SQL.encode())

    asyncio.run(init())
    return _url(postgres, user="ai_worker", password=WORKER_PASSWORD)


def _run(coro: Any) -> Any:
    return asyncio.run(coro)


def test_concurrent_migrations_execute_once(worker_url: str) -> None:
    async def scenario() -> tuple[tuple[int, ...], tuple[int, ...]]:
        a = await AsyncConnection.connect(worker_url, autocommit=True)
        b = await AsyncConnection.connect(worker_url, autocommit=True)
        try:
            first, second = await asyncio.gather(apply_migrations(a), apply_migrations(b))
        finally:
            await a.close()
            await b.close()
        return first, second

    first, second = _run(scenario())
    everything = tuple(m.version for m in load_migrations())
    assert sorted([first, second], key=len) == [(), everything]


def test_rerun_is_idempotent_and_modified_migration_refused(worker_url: str) -> None:
    async def scenario() -> None:
        async with await AsyncConnection.connect(worker_url, autocommit=True) as conn:
            assert await apply_migrations(conn) == ()
            tampered = [
                Migration(m.version, m.name, m.sql + "\n-- edit") for m in load_migrations()
            ]
            with pytest.raises(MigrationError):
                await apply_migrations(conn, tampered)

    _run(scenario())


def test_worker_role_is_isolated_from_business_schema(worker_url: str) -> None:
    async def scenario() -> None:
        async with await AsyncConnection.connect(worker_url, autocommit=True) as conn:
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                await conn.execute("SELECT 1 FROM public.project_document")
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                await conn.execute("CREATE TABLE public.x (id int)")

    _run(scenario())


def test_ddl_constraints_hold(worker_url: str) -> None:
    async def scenario() -> None:
        async with await AsyncConnection.connect(worker_url, autocommit=True) as conn:
            with pytest.raises(psycopg.errors.CheckViolation):
                await conn.execute(
                    "INSERT INTO ai_index.index_generation (id, embedding_model, dimension, status)"
                    " VALUES (%s, 'm', 768, 'BUILDING')",
                    (uuid.uuid4(),),
                )
            gen = uuid.uuid4()
            await conn.execute(
                "INSERT INTO ai_index.index_generation (id, embedding_model, dimension, status)"
                " VALUES (%s, 'm', 1024, 'ACTIVE')",
                (gen,),
            )
            with pytest.raises(psycopg.errors.UniqueViolation):
                await conn.execute(
                    "INSERT INTO ai_index.index_generation"
                    " (id, embedding_model, dimension, status) VALUES (%s, 'm', 1024, 'ACTIVE')",
                    (uuid.uuid4(),),
                )
            insert = (
                "INSERT INTO ai_index.chunk (id, generation_id, project_id, document_id,"
                " body_snapshot_id, chunk_no, chunk_type, content, content_hash, embedding)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s, 'c', %s, %s::vector)"
            )
            ids = (uuid.uuid4(), uuid.uuid4(), uuid.uuid4())
            vec = vector_literal([0.0] * 1023 + [1.0])
            with pytest.raises(psycopg.errors.CheckViolation):
                await conn.execute(insert, (uuid.uuid4(), gen, *ids, 0, "PARENT", "0" * 64, vec))
            with pytest.raises(psycopg.errors.CheckViolation):
                await conn.execute(insert, (uuid.uuid4(), gen, *ids, 1, "STANDARD", "0" * 64, None))
            await conn.execute(insert, (uuid.uuid4(), gen, *ids, 2, "STANDARD", "0" * 64, vec))

    _run(scenario())


def test_index_database_reports_ready(worker_url: str) -> None:
    from pydantic import SecretStr

    async def scenario() -> str | None:
        db = IndexDatabase(SecretStr(worker_url))
        try:
            await db.start()
            return db.readiness_failure()
        finally:
            await db.aclose()

    assert _run(scenario()) is None
