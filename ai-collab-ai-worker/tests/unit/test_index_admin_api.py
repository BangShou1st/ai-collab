"""P2 Generation 管理端点（``/v1/index/generations/**``）的单元测试。"""

from __future__ import annotations

import asyncio
import base64
import copy
import json
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.api.index_admin import (
    cleanup_retired_generations,
)
from ai_collab_worker.api.index_admin import (
    router as index_admin_router,
)
from ai_collab_worker.auth.token import Ed25519KeyRing, TokenVerifier
from ai_collab_worker.observability.metrics import REGISTRY

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("66" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")

GEN_ACTIVE = uuid.UUID("11111111-1111-4111-8111-111111111101")
GEN_BUILDING = uuid.UUID("11111111-1111-4111-8111-111111111102")
GEN_RETIRED_NEW = uuid.UUID("11111111-1111-4111-8111-111111111103")
GEN_RETIRED_OLD = uuid.UUID("11111111-1111-4111-8111-111111111104")
GEN_FAILED = uuid.UUID("11111111-1111-4111-8111-111111111105")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
DOC_1 = uuid.UUID("00000000-0000-4000-8000-000000000001")
DOC_2 = uuid.UUID("00000000-0000-4000-8000-000000000002")
DOC_3 = uuid.UUID("00000000-0000-4000-8000-000000000003")
DOC_4 = uuid.UUID("00000000-0000-4000-8000-000000000004")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _token(scope: str = "index:admin") -> str:
    now = int(time.time())
    header = _b64(json.dumps({"alg": "EdDSA", "typ": "JWT", "kid": "k1"}).encode())
    payload = _b64(
        json.dumps(
            {
                "iss": "ai-collab-backend",
                "sub": "backend",
                "aud": ["ai-worker"],
                "scope": scope,
                "iat": now,
                "exp": now + 120,
            }
        ).encode()
    )
    signing = f"{header}.{payload}".encode("ascii")
    return f"{header}.{payload}.{_b64(_KEY.sign(signing))}"


@dataclass
class _State:
    generations: dict[uuid.UUID, dict[str, Any]] = field(default_factory=dict)
    indexed_documents: dict[tuple[uuid.UUID, uuid.UUID], dict[str, Any]] = field(
        default_factory=dict
    )
    chunks: list[dict[str, Any]] = field(default_factory=list)
    pg_now: datetime = field(default_factory=lambda: datetime(2026, 11, 27, 10, 0, tzinfo=UTC))


class _Conn:
    def __init__(self, db: FakeAdminDb) -> None:
        self._db = db
        self._working: _State | None = None

    def _st(self) -> _State:
        return self._working if self._working is not None else self._db.state

    @asynccontextmanager
    async def transaction(self) -> Any:
        snapshot = copy.deepcopy(self._db.state)
        self._working = snapshot
        try:
            yield self
        except Exception:
            self._working = None
            raise
        else:
            self._db.state = snapshot
            self._working = None

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        st = self._st()
        norm = " ".join(sql.split())

        class _Res:
            def __init__(self, rows: list[tuple[Any, ...]]) -> None:
                self._rows = rows

            async def fetchall(self) -> list[tuple[Any, ...]]:
                return list(self._rows)

            async def fetchone(self) -> tuple[Any, ...] | None:
                return self._rows[0] if self._rows else None

        if norm.startswith("SELECT pg_advisory_xact_lock"):
            return _Res([(None,)])

        if "FROM ai_index.index_generation g" in norm:
            rows = []
            for gen in sorted(
                st.generations.values(),
                key=lambda x: (x["created_at"], x["id"].int),
                reverse=True,
            ):
                docs = [d for (g_id, _), d in st.indexed_documents.items() if g_id == gen["id"]]
                rows.append(
                    (
                        gen["id"],
                        gen["embedding_model"],
                        gen["dimension"],
                        gen["status"],
                        gen["created_at"],
                        gen["activated_at"],
                        sum(d["row_count"] for d in docs),
                        len(docs),
                    )
                )
            return _Res(rows)

        if norm.startswith("SELECT id FROM ai_index.index_generation WHERE id = %s"):
            gen = st.generations.get(params[0])
            return _Res([(gen["id"],)] if gen else [])

        if norm.startswith("SELECT id, status FROM ai_index.index_generation WHERE id = %s"):
            gen = st.generations.get(params[0])
            return _Res([(gen["id"], gen["status"])] if gen else [])

        if norm.startswith("SELECT status FROM ai_index.index_generation WHERE id = %s"):
            gen = st.generations.get(params[0])
            return _Res([(gen["status"],)] if gen else [])

        if norm.startswith("SELECT id FROM ai_index.index_generation WHERE status = 'BUILDING'"):
            other_id = params[0]
            rows = [
                (g["id"],)
                for g in st.generations.values()
                if g["status"] == "BUILDING" and g["id"] != other_id
            ]
            return _Res(rows)

        if norm.startswith("SELECT id FROM ai_index.index_generation WHERE status = 'RETIRED'"):
            retired = [g for g in st.generations.values() if g["status"] == "RETIRED"]
            retired.sort(
                key=lambda g: (g["activated_at"] or g["created_at"], g["id"].int),
                reverse=True,
            )
            return _Res([(g["id"],) for g in retired])

        if norm.startswith("SELECT document_id FROM ai_index.indexed_document"):
            gen_id, doc_ids = params
            id_set = set(doc_ids)
            return _Res(
                [
                    (doc_id,)
                    for (g_id, doc_id) in st.indexed_documents
                    if g_id == gen_id and doc_id in id_set
                ]
            )

        if norm.startswith("WITH victims AS"):
            gen_id, as_of, after, _, through, _, keep_ids, _ = params
            keep_set = set(keep_ids)
            victims: set[uuid.UUID] = set()
            for (g_id, doc_id), entry in list(st.indexed_documents.items()):
                if g_id != gen_id:
                    continue
                if not entry["indexed_at"] < as_of:
                    continue
                if after is not None and doc_id.int <= after.int:
                    continue
                if through is not None and doc_id.int > through.int:
                    continue
                if doc_id in keep_set:
                    continue
                victims.add(doc_id)
                del st.indexed_documents[(g_id, doc_id)]
            before = len(st.chunks)
            st.chunks = [
                c
                for c in st.chunks
                if not (c["generation_id"] == gen_id and c["document_id"] in victims)
            ]
            return _Res([(len(victims), before - len(st.chunks))])

        if norm.startswith("UPDATE ai_index.index_generation SET status = 'RETIRED'"):
            exclude_id = params[0]
            for gen in st.generations.values():
                if gen["status"] == "ACTIVE" and gen["id"] != exclude_id:
                    gen["status"] = "RETIRED"
            return _Res([])

        if norm.startswith("UPDATE ai_index.index_generation SET status = 'ACTIVE'"):
            gen_id = params[0]
            st.generations[gen_id]["status"] = "ACTIVE"
            st.generations[gen_id]["activated_at"] = st.pg_now
            return _Res([])

        if norm.startswith("UPDATE ai_index.index_generation SET status = 'FAILED'"):
            st.generations[params[0]]["status"] = "FAILED"
            return _Res([])

        if norm.startswith("UPDATE ai_index.index_generation SET status = 'BUILDING'"):
            st.generations[params[0]]["status"] = "BUILDING"
            return _Res([])

        if norm.startswith("DELETE FROM ai_index.chunk WHERE generation_id = ANY"):
            target_ids = set(params[0])
            st.chunks = [c for c in st.chunks if c["generation_id"] not in target_ids]
            return _Res([])

        if norm.startswith("DELETE FROM ai_index.indexed_document WHERE generation_id = ANY"):
            target_ids = set(params[0])
            for key in [k for k in st.indexed_documents if k[0] in target_ids]:
                del st.indexed_documents[key]
            return _Res([])

        raise AssertionError(f"unexpected SQL: {norm}")


class _Pool:
    def __init__(self, db: FakeAdminDb) -> None:
        self._db = db

    @asynccontextmanager
    async def connection(self) -> Any:
        yield _Conn(self._db)


class FakeAdminDb:
    def __init__(self) -> None:
        self.state = _State()
        self.pool = _Pool(self)


def _app(db: FakeAdminDb | None) -> FastAPI:
    app = FastAPI()
    app.state.token_verifier = TokenVerifier(key_ring=Ed25519KeyRing.parse(f"k1={_PUBLIC_SPKI}"))
    app.state.index_db = db
    install_error_handlers(app)
    app.include_router(index_admin_router)
    return app


def _seed_db() -> FakeAdminDb:
    db = FakeAdminDb()
    t0 = datetime(2026, 11, 27, 8, 0, tzinfo=UTC)
    db.state.generations = {
        GEN_ACTIVE: {
            "id": GEN_ACTIVE,
            "embedding_model": "qwen3-embedding:0.6b",
            "dimension": 1024,
            "status": "ACTIVE",
            "created_at": t0 + timedelta(minutes=20),
            "activated_at": t0 + timedelta(minutes=25),
        },
        GEN_BUILDING: {
            "id": GEN_BUILDING,
            "embedding_model": "qwen3-embedding:0.6b",
            "dimension": 1024,
            "status": "BUILDING",
            "created_at": t0 + timedelta(minutes=30),
            "activated_at": None,
        },
        GEN_RETIRED_NEW: {
            "id": GEN_RETIRED_NEW,
            "embedding_model": "old-a",
            "dimension": 1024,
            "status": "RETIRED",
            "created_at": t0 + timedelta(minutes=10),
            "activated_at": t0 + timedelta(minutes=15),
        },
        GEN_RETIRED_OLD: {
            "id": GEN_RETIRED_OLD,
            "embedding_model": "old-b",
            "dimension": 1024,
            "status": "RETIRED",
            "created_at": t0,
            "activated_at": t0 + timedelta(minutes=5),
        },
        GEN_FAILED: {
            "id": GEN_FAILED,
            "embedding_model": "bad",
            "dimension": 1024,
            "status": "FAILED",
            "created_at": t0 + timedelta(minutes=1),
            "activated_at": None,
        },
    }
    db.state.indexed_documents = {
        (GEN_ACTIVE, DOC_1): {
            "row_count": 3,
            "indexed_at": t0 + timedelta(minutes=10),
        },
        (GEN_ACTIVE, DOC_2): {
            "row_count": 4,
            "indexed_at": t0 + timedelta(minutes=10),
        },
        (GEN_ACTIVE, DOC_3): {
            "row_count": 5,
            "indexed_at": t0 + timedelta(minutes=40),  # 晚于 asOf，PRUNE 不得误删
        },
        (GEN_RETIRED_NEW, DOC_1): {"row_count": 2, "indexed_at": t0},
        (GEN_RETIRED_OLD, DOC_1): {"row_count": 9, "indexed_at": t0},
    }
    for gen_id, doc_id in ((GEN_ACTIVE, DOC_1), (GEN_ACTIVE, DOC_2), (GEN_ACTIVE, DOC_3)):
        db.state.chunks.append({"generation_id": gen_id, "document_id": doc_id})
    db.state.chunks.append({"generation_id": GEN_RETIRED_NEW, "document_id": DOC_1})
    db.state.chunks.append({"generation_id": GEN_RETIRED_OLD, "document_id": DOC_1})
    return db


def test_list_generations_enforces_scope_and_updates_gauge() -> None:
    db = _seed_db()
    with TestClient(_app(db)) as client:
        assert client.get("/v1/index/generations").status_code == 401
        wrong_scope = _token("knowledge:search")
        assert (
            client.get(
                "/v1/index/generations",
                headers={"Authorization": f"Bearer {wrong_scope}"},
            ).status_code
            == 401
        )
        res = client.get(
            "/v1/index/generations",
            headers={"Authorization": f"Bearer {_token()}"},
        )
        assert res.status_code == 200
        gens = {item["generationId"]: item for item in res.json()["generations"]}
        assert gens[str(GEN_ACTIVE)]["rowCount"] == 12
        assert gens[str(GEN_ACTIVE)]["documentCount"] == 3
        assert gens[str(GEN_RETIRED_NEW)]["rowCount"] == 2
        assert (
            REGISTRY.get_sample_value(
                "ai_index_generation_rows",
                {"status": "ACTIVE", "caller_type": "PYTHON"},
            )
            == 12.0
        )


def test_reconcile_read_only_and_prune_modes() -> None:
    db = _seed_db()
    hdrs = {"Authorization": f"Bearer {_token()}"}
    as_of = datetime(2026, 11, 27, 8, 30, tzinfo=UTC).isoformat()

    with TestClient(_app(db)) as client:
        # 只读模式
        res = client.post(
            f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
            headers=hdrs,
            json={"documentIds": [str(DOC_1), str(DOC_4)]},
        )
        assert res.status_code == 200
        assert res.json() == {"missingDocumentIds": [str(DOC_4)]}

        # 超过 5000 个 ID 返回 400
        too_many = [str(uuid.uuid4()) for _ in range(5001)]
        assert (
            client.post(
                f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
                headers=hdrs,
                json={"documentIds": too_many},
            ).status_code
            == 400
        )

        # 不存在的 generation 返回 404
        assert (
            client.post(
                f"/v1/index/generations/{uuid.uuid4()}:reconcile",
                headers=hdrs,
                json={"documentIds": []},
            ).status_code
            == 404
        )

        # PRUNE：after >= through → 400
        assert (
            client.post(
                f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
                headers=hdrs,
                json={
                    "mode": "PRUNE",
                    "asOf": as_of,
                    "range": {"afterDocumentId": str(DOC_2), "throughDocumentId": str(DOC_2)},
                    "documentIds": [],
                },
            ).status_code
            == 400
        )

        # PRUNE：documentIds 越界 → 400
        assert (
            client.post(
                f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
                headers=hdrs,
                json={
                    "mode": "PRUNE",
                    "asOf": as_of,
                    "range": {"afterDocumentId": str(DOC_1), "throughDocumentId": str(DOC_3)},
                    "documentIds": [str(DOC_1)],  # 下界是开区间，DOC_1 不在 (DOC_1, DOC_3]
                },
            ).status_code
            == 400
        )

        # PRUNE FAILED generation → 409
        assert (
            client.post(
                f"/v1/index/generations/{GEN_FAILED}:reconcile",
                headers=hdrs,
                json={
                    "mode": "PRUNE",
                    "asOf": as_of,
                    "range": {"afterDocumentId": None, "throughDocumentId": None},
                    "documentIds": [],
                },
            ).status_code
            == 409
        )

        # PRUNE 正常执行：保留 DOC_1；DOC_2 被删；DOC_3 因 indexed_at >= asOf 保留
        prune_res = client.post(
            f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
            headers=hdrs,
            json={
                "mode": "PRUNE",
                "asOf": as_of,
                "range": {"afterDocumentId": None, "throughDocumentId": str(DOC_3)},
                "documentIds": [str(DOC_1)],
            },
        )
        assert prune_res.status_code == 200
        assert prune_res.json() == {"deletedDocuments": 1, "deletedRows": 1}
        assert (GEN_ACTIVE, DOC_1) in db.state.indexed_documents
        assert (GEN_ACTIVE, DOC_2) not in db.state.indexed_documents
        assert (GEN_ACTIVE, DOC_3) in db.state.indexed_documents

        # 幂等重跑
        prune_again = client.post(
            f"/v1/index/generations/{GEN_ACTIVE}:reconcile",
            headers=hdrs,
            json={
                "mode": "PRUNE",
                "asOf": as_of,
                "range": {"afterDocumentId": None, "throughDocumentId": str(DOC_3)},
                "documentIds": [str(DOC_1)],
            },
        )
        assert prune_again.status_code == 200
        assert prune_again.json() == {"deletedDocuments": 0, "deletedRows": 0}

        # PRUNE 也支持 RETIRED generation（R4-3 / R4-4 回滚前清理）
        retired_prune = client.post(
            f"/v1/index/generations/{GEN_RETIRED_NEW}:reconcile",
            headers=hdrs,
            json={
                "mode": "PRUNE",
                "asOf": as_of,
                "range": {"afterDocumentId": None, "throughDocumentId": None},
                "documentIds": [],
            },
        )
        assert retired_prune.status_code == 200
        assert retired_prune.json() == {"deletedDocuments": 1, "deletedRows": 1}


def test_activate_abandon_and_reopen_lifecycle_and_background_cleanup() -> None:
    db = _seed_db()
    hdrs = {"Authorization": f"Bearer {_token()}"}

    with TestClient(_app(db)) as client:
        # 1. RETIRED 时已有 BUILDING → reopen 409；ACTIVE/FAILED → reopen 409
        assert (
            client.post(f"/v1/index/generations/{GEN_RETIRED_NEW}:reopen", headers=hdrs).status_code
            == 409
        )
        assert (
            client.post(f"/v1/index/generations/{GEN_ACTIVE}:reopen", headers=hdrs).status_code
            == 409
        )
        assert (
            client.post(f"/v1/index/generations/{GEN_FAILED}:reopen", headers=hdrs).status_code
            == 409
        )
        # 已是 BUILDING 时 reopen 幂等 200
        assert (
            client.post(f"/v1/index/generations/{GEN_BUILDING}:reopen", headers=hdrs).status_code
            == 200
        )

        # 2. activate：BUILDING -> ACTIVE，原 ACTIVE -> RETIRED；幂等 200
        act = client.post(f"/v1/index/generations/{GEN_BUILDING}:activate", headers=hdrs)
        assert act.status_code == 200
        assert act.json() == {"generationId": str(GEN_BUILDING), "status": "ACTIVE"}
        assert db.state.generations[GEN_BUILDING]["status"] == "ACTIVE"
        assert db.state.generations[GEN_BUILDING]["activated_at"] is not None
        assert db.state.generations[GEN_ACTIVE]["status"] == "RETIRED"

        act_again = client.post(f"/v1/index/generations/{GEN_BUILDING}:activate", headers=hdrs)
        assert act_again.status_code == 200

        # 激活后清理：只保留最近 1 个 RETIRED（GEN_ACTIVE），其余较早的 RETIRED 行被清掉
        asyncio.run(cleanup_retired_generations(db))
        assert (GEN_ACTIVE, DOC_1) in db.state.indexed_documents
        assert (GEN_RETIRED_NEW, DOC_1) not in db.state.indexed_documents
        assert (GEN_RETIRED_OLD, DOC_1) not in db.state.indexed_documents

        # 3. 此时没有 BUILDING，reopen 最近一个 RETIRED 成功 -> BUILDING
        reopen_ok = client.post(f"/v1/index/generations/{GEN_ACTIVE}:reopen", headers=hdrs)
        assert reopen_ok.status_code == 200
        assert reopen_ok.json() == {"generationId": str(GEN_ACTIVE), "status": "BUILDING"}
        assert db.state.generations[GEN_ACTIVE]["status"] == "BUILDING"

        # 4. abandon：BUILDING -> FAILED，幂等 200；不可 abandon ACTIVE
        assert (
            client.post(f"/v1/index/generations/{GEN_BUILDING}:abandon", headers=hdrs).status_code
            == 409
        )
        ab = client.post(f"/v1/index/generations/{GEN_ACTIVE}:abandon", headers=hdrs)
        assert ab.status_code == 200
        assert ab.json() == {"generationId": str(GEN_ACTIVE), "status": "FAILED"}
        assert (
            client.post(f"/v1/index/generations/{GEN_ACTIVE}:abandon", headers=hdrs).status_code
            == 200
        )
