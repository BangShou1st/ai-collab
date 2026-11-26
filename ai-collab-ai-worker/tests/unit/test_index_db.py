"""ai_index 迁移与连接辅助函数的单元测试（不连数据库）。

覆盖 P2-R1 第 1 条：版本连续、已发布迁移不可修改（校验和）、未知版本拒绝、换行归一；
契约 §5.1：DDL 锁死 1024 维、HNSW + 余弦、PARENT ⇔ embedding IS NULL、indexed_document；
pgvector ≥ 0.8 判定；向量文本字面量；连接串密码提取。
"""

from __future__ import annotations

import math
import re

import pytest
from pydantic import SecretStr

from ai_collab_worker.config import load_settings
from ai_collab_worker.index.db import (
    READY_PGVECTOR_MISSING,
    READY_PGVECTOR_TOO_OLD,
    VECTOR_DIMENSION,
    database_password,
    parse_extversion,
    pgvector_failure,
    vector_literal,
)
from ai_collab_worker.index.migrations import (
    ADVISORY_LOCK_KEY,
    AppliedMigration,
    MigrationError,
    checksum_of,
    load_migrations,
    parse_migrations,
    pending_migrations,
)

# ---- 迁移发现与计划 ------------------------------------------------------------


def test_bundled_migrations_load_and_are_contiguous() -> None:
    migrations = load_migrations()
    assert migrations, "包内必须至少有一个迁移"
    assert [m.version for m in migrations] == list(range(1, len(migrations) + 1))
    assert migrations[0].filename == "0001_ai_index_schema.sql"


def test_first_migration_matches_contract_ddl() -> None:
    sql = load_migrations()[0].sql
    normalized = re.sub(r"\s+", " ", sql)
    assert "CHECK (dimension = 1024)" in normalized
    assert "public.vector(1024)" in normalized
    assert "CREATE TABLE ai_index.indexed_document" in normalized
    assert "PRIMARY KEY (generation_id, document_id)" in normalized
    assert "CHECK ((chunk_type = 'PARENT') = (embedding IS NULL))" in normalized
    assert "USING hnsw (embedding public.vector_cosine_ops)" in normalized
    assert "WHERE status = 'ACTIVE'" in normalized
    assert "WHERE status = 'BUILDING'" in normalized
    assert "gin_trgm_ops" in normalized
    # 只碰 ai_index：迁移里不得出现业务 schema 或扩展创建。
    assert "public.project_document" not in normalized
    assert "CREATE EXTENSION" not in normalized.upper()


def test_parse_rejects_bad_names_and_gaps() -> None:
    with pytest.raises(MigrationError, match="文件名不合法"):
        parse_migrations([("1_init.sql", "SELECT 1")])
    with pytest.raises(MigrationError, match="连续"):
        parse_migrations([("0001_a.sql", "x"), ("0003_c.sql", "y")])
    with pytest.raises(MigrationError, match="连续"):
        parse_migrations([("0001_a.sql", "x"), ("0001_b.sql", "y")])


def test_parse_ignores_non_sql_and_sorts() -> None:
    parsed = parse_migrations([("0002_b.sql", "y"), ("README.md", "z"), ("0001_a.sql", "x")])
    assert [m.version for m in parsed] == [1, 2]


def test_checksum_is_line_ending_independent() -> None:
    assert checksum_of("A\r\nB\r\n") == checksum_of("A\nB\n")
    lf = parse_migrations([("0001_a.sql", "A\nB\n")])[0]
    crlf = parse_migrations([("0001_a.sql", "A\r\nB\r\n")])[0]
    assert lf.checksum == crlf.checksum


def test_pending_skips_applied_and_keeps_order() -> None:
    available = parse_migrations([("0001_a.sql", "x"), ("0002_b.sql", "y"), ("0003_c.sql", "z")])
    applied = [AppliedMigration(1, "a", available[0].checksum)]
    assert [m.version for m in pending_migrations(available, applied)] == [2, 3]
    assert pending_migrations(available, []) == available


def test_pending_rejects_modified_published_migration() -> None:
    available = parse_migrations([("0001_a.sql", "x changed")])
    applied = [AppliedMigration(1, "a", checksum_of("x"))]
    with pytest.raises(MigrationError, match="被修改"):
        pending_migrations(available, applied)


def test_pending_rejects_unknown_applied_version() -> None:
    available = parse_migrations([("0001_a.sql", "x")])
    applied = [
        AppliedMigration(1, "a", available[0].checksum),
        AppliedMigration(2, "future", "0" * 64),
    ]
    with pytest.raises(MigrationError, match="不存在的迁移版本"):
        pending_migrations(available, applied)


def test_advisory_lock_key_fits_bigint() -> None:
    assert 0 < ADVISORY_LOCK_KEY < 2**63


# ---- pgvector 版本 -------------------------------------------------------------


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("0.8.6", (0, 8)),
        ("0.10.0", (0, 10)),
        ("1.0", (1, 0)),
        (None, None),
        ("", None),
        ("x", None),
    ],
)
def test_parse_extversion(raw: str | None, expected: tuple[int, int] | None) -> None:
    assert parse_extversion(raw) == expected


def test_pgvector_failure_threshold() -> None:
    assert pgvector_failure("0.8.0") is None
    assert pgvector_failure("0.8.6") is None
    assert pgvector_failure("0.10.1") is None
    assert pgvector_failure("0.7.4") == READY_PGVECTOR_TOO_OLD
    assert pgvector_failure(None) == READY_PGVECTOR_MISSING


# ---- 向量字面量 ----------------------------------------------------------------


def test_vector_literal_roundtrips_values() -> None:
    values = [0.1 * i for i in range(VECTOR_DIMENSION)]
    literal = vector_literal(values)
    assert literal.startswith("[")
    assert literal.endswith("]")
    parsed = [float(part) for part in literal[1:-1].split(",")]
    assert parsed == values


def test_vector_literal_rejects_wrong_dimension_and_non_finite() -> None:
    with pytest.raises(ValueError, match="维度"):
        vector_literal([0.0] * 1023)
    with pytest.raises(ValueError, match="非有限"):
        vector_literal([math.nan] + [0.0] * (VECTOR_DIMENSION - 1))
    with pytest.raises(ValueError, match="非有限"):
        vector_literal([math.inf] * VECTOR_DIMENSION)


# ---- 连接串 --------------------------------------------------------------------


def test_database_password_extracted_for_redaction() -> None:
    url = SecretStr("postgresql://ai_worker:unit-DB-pw-123@db.test:5432/aicollab")
    assert database_password(url) == "unit-DB-pw-123"
    assert database_password(SecretStr("postgresql://ai_worker@db.test/aicollab")) is None
    assert database_password(None) is None


def test_settings_accepts_index_url_and_hides_it() -> None:
    settings = load_settings(
        {"AI_INDEX_DATABASE_URL": "postgresql://ai_worker:unit-DB-pw-123@db.test:5432/x"}
    )
    assert settings.index_database_url is not None
    assert "unit-DB-pw-123" not in repr(settings)
    assert load_settings({"AI_INDEX_DATABASE_URL": ""}).index_database_url is None


def test_settings_rejects_non_postgres_index_url_without_echo() -> None:
    with pytest.raises(ValueError, match="AI_INDEX_DATABASE_URL") as info:
        load_settings({"AI_INDEX_DATABASE_URL": "mysql://u:unit-DB-pw-123@h/x"})
    assert "unit-DB-pw-123" not in str(info.value)
