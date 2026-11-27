"""``POST /v1/knowledge/search`` API 端点的单元测试（ACL、R2-1、deadline、指标与凭据哨兵）。"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import secrets
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx2
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.contract.knowledge_search import (
    CODE_SEARCH_FORBIDDEN,
    CODE_UNAUTHORIZED,
    CODE_VALIDATION_ERROR,
)
from ai_collab_worker.index.db import VECTOR_DIMENSION
from ai_collab_worker.main import create_app
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import Lease, LeaseCache, LeasePurpose, LeaseRequest
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.observability.metrics import REGISTRY
from ai_collab_worker.retrieval.candidate import Candidate
from ai_collab_worker.retrieval.pipeline import (
    CODE_RERANK_UNAVAILABLE,
    KnowledgeSearchPipeline,
)
from ai_collab_worker.retrieval.store import ActiveGeneration
from ai_collab_worker.security.redaction import SECRETS

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("77" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")

USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
GEN_ID = uuid.UUID("66666666-6666-4666-8666-666666666661")
SNAP_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
DOC_A = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
DOC_B = uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _token(
    *,
    scope: str = "knowledge:search credentials:lease usage:report",
    uid: str | None = str(USER_ID),
    pid: str | None = str(PROJECT_ID),
) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["ai-worker"],
        "scope": scope,
        "iat": now,
        "exp": now + 120,
    }
    if uid is not None:
        claims["uid"] = uid
    if pid is not None:
        claims["pid"] = pid
    header = _b64(json.dumps({"alg": "EdDSA", "typ": "JWT", "kid": "k1"}).encode())
    payload = _b64(json.dumps(claims).encode())
    signing = f"{header}.{payload}".encode("ascii")
    return f"{header}.{payload}.{_b64(_KEY.sign(signing))}"


def _body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "requestId": str(uuid.uuid4()),
        "projectId": str(PROJECT_ID),
        "userId": str(USER_ID),
        "intent": "KNOWLEDGE_QA",
        "strategy": "HYBRID",
        "queries": [{"text": "SECRET_QUERY_PHRASE", "role": "PRIMARY"}],
        "originalQuery": "SECRET_ORIGINAL_QUERY",
        "visibleDocumentIds": [str(DOC_A)],
        "topK": 5,
        "candidatePool": 20,
        "headingFilter": {"exact": None, "contains": None},
        "rerank": "HEURISTIC",
        "expand": {"sentenceWindow": False, "parent": False},
        "deadlineMs": 5000,
    }
    payload.update(overrides)
    return payload


class _StubStore:
    def __init__(self, *, delay_seconds: float = 0.0, leak_doc_b: bool = False) -> None:
        self.delay_seconds = delay_seconds
        self.leak_doc_b = leak_doc_b
        self.calls = 0

    async def get_active_generation(self) -> ActiveGeneration | None:
        self.calls += 1
        return ActiveGeneration(GEN_ID, "qwen3-embedding:0.6b", VECTOR_DIMENSION)

    async def search_dense(self, **_kwargs: Any) -> list[Candidate]:
        self.calls += 1
        if self.delay_seconds > 0:
            await asyncio.sleep(self.delay_seconds)
        cands = [
            Candidate(
                document_id=DOC_A,
                chunk_no=0,
                content_hash=hashlib.sha256(b"chunk-a").hexdigest(),
                chunk_type="STANDARD",
                heading="概述",
                content="SECRET_CHUNK_BODY_TEXT",
                body_snapshot_id=SNAP_ID,
                dense_rank=1,
                dense=0.88,
            )
        ]
        if self.leak_doc_b:
            cands.append(
                Candidate(
                    document_id=DOC_B,
                    chunk_no=0,
                    content_hash=hashlib.sha256(b"chunk-b").hexdigest(),
                    chunk_type="STANDARD",
                    heading="机密",
                    content="NOT_VISIBLE",
                    body_snapshot_id=SNAP_ID,
                    dense_rank=2,
                    dense=0.99,
                )
            )
        return cands

    async def search_lexical(self, **_kwargs: Any) -> list[Candidate]:
        self.calls += 1
        return []

    async def fetch_section_chunks(self, **_kwargs: Any) -> list[Any]:
        return []

    async def fetch_parent_chunks(self, **_kwargs: Any) -> dict[Any, Any]:
        return {}


def _app_with_stub(
    tmp_path: Path,
    *,
    store: _StubStore,
    rerank_status: int = 200,
    sentinel_key: str | None = None,
) -> tuple[FastAPI, list[LeaseRequest]]:
    prompts_dir = tmp_path / "prompts"
    prompts_dir.mkdir(exist_ok=True)
    app = create_app(Settings(prompts_dir=prompts_dir, ed25519_public_keys=f"k1={_PUBLIC_SPKI}"))
    lease_calls: list[LeaseRequest] = []
    secret = sentinel_key or f"sk-search-{secrets.token_urlsafe(16)}"
    SECRETS.add(secret)

    async def fetch_lease(req: LeaseRequest, _token: str) -> Lease:
        lease_calls.append(req)
        if req.purpose is LeasePurpose.EMBEDDING:
            return Lease.model_validate(
                {
                    "leaseId": str(uuid.uuid4()),
                    "protocol": "OPENAI_EMBEDDINGS",
                    "providerType": "OPENAI_COMPATIBLE",
                    "baseUrl": "https://embed.test",
                    "apiPath": "/v1/embeddings",
                    "model": "qwen3-embedding:0.6b",
                    "apiKey": secret,
                    "params": {"dimensions": VECTOR_DIMENSION, "batchSize": 8},
                    "capabilities": ["EMBEDDING"],
                    "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                }
            )
        return Lease.model_validate(
            {
                "leaseId": str(uuid.uuid4()),
                "protocol": "LLAMACPP_RERANK",
                "providerType": "LLAMACPP_RERANK",
                "baseUrl": "http://rerank.test",
                "apiPath": "/rerank",
                "model": "bge-reranker",
                "apiKey": secret,
                "params": {"maxCandidates": 8, "timeoutMs": 1000},
                "capabilities": ["RERANK"],
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        )

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        return {str(entry["callId"]): True for entry in items}

    def transport(req: httpx2.Request) -> httpx2.Response:
        if req.url.path == "/v1/embeddings":
            body = json.loads(req.content.decode("utf-8"))
            return httpx2.Response(
                200,
                json={"data": [{"embedding": [0.02] * VECTOR_DIMENSION} for _ in body["input"]]},
            )
        if rerank_status != 200:
            return httpx2.Response(rerank_status, text=f"error {secret}")
        return httpx2.Response(200, json={"results": [{"index": 0, "relevance_score": 0.9}]})

    kernel = ModelKernel(
        leases=LeaseCache(fetch_lease),
        usage=UsageReporter(send_usage, max_batch=50, flush_interval=60.0),
        runtime=ModelRuntime(retry_backoff_seconds=0.0, transport=httpx2.MockTransport(transport)),
    )
    app.state.knowledge_search_pipeline = KnowledgeSearchPipeline(store=store, kernel=kernel)
    return app, lease_calls


def test_empty_visible_docs_returns_empty_without_db_or_lease(tmp_path: Path) -> None:
    store = _StubStore()
    app, lease_calls = _app_with_stub(tmp_path, store=store)
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(visibleDocumentIds=[]),
        )
    assert res.status_code == 200
    body = res.json()
    assert body["hits"] == []
    assert body["generationId"] is None
    assert store.calls == 0
    assert lease_calls == []


def test_acl_auth_validation_and_defense_in_depth_filtering(tmp_path: Path) -> None:
    store = _StubStore(leak_doc_b=True)
    app, lease_calls = _app_with_stub(tmp_path, store=store)
    with TestClient(app) as client:
        # 401 无令牌 / 错 scope / 缺 uid
        assert client.post("/v1/knowledge/search", json=_body()).status_code == 401
        bad_scope = _token(scope="index:admin")
        r_scope = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {bad_scope}"},
            json=_body(),
        )
        assert r_scope.status_code == 401
        assert r_scope.json()["code"] == CODE_UNAUTHORIZED

        no_uid = _token(uid=None)
        assert (
            client.post(
                "/v1/knowledge/search",
                headers={"Authorization": f"Bearer {no_uid}"},
                json=_body(),
            ).status_code
            == 401
        )

        # 403 uid/pid 与请求体不符
        wrong_pid = _token(pid=str(uuid.uuid4()))
        r_forbid = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {wrong_pid}"},
            json=_body(),
        )
        assert r_forbid.status_code == 403
        assert r_forbid.json()["code"] == CODE_SEARCH_FORBIDDEN

        # 400 超过 5000 个 ID / 未知 strategy
        r_5001 = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(visibleDocumentIds=[str(uuid.uuid4()) for _ in range(5001)]),
        )
        assert r_5001.status_code == 400
        assert r_5001.json()["code"] == CODE_VALIDATION_ERROR

        r_strat = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(strategy="MAGIC_V9"),
        )
        assert r_strat.status_code == 400
        assert r_strat.json()["code"] == CODE_VALIDATION_ERROR

        # 200 且纵深防御剔除 visibleDocumentIds 外的 DOC_B；HEURISTIC 不租 RERANK
        r_ok = client.post(
            "/v1/knowledge/search",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(),
        )
        assert r_ok.status_code == 200
        hits = r_ok.json()["hits"]
        assert [h["documentId"] for h in hits] == [str(DOC_A)]
        assert all(req.purpose is LeasePurpose.EMBEDDING for req in lease_calls)


def test_configured_rerank_503_deadline_504_and_no_secret_or_query_logs(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    sentinel = f"sk-sentinel-{secrets.token_urlsafe(16)}"
    tok = _token()
    try:
        # 1. CONFIGURED rerank 失败返回 503 RERANK_UNAVAILABLE 并记错误指标，不漏哨兵
        store_err = _StubStore()
        app_err, _ = _app_with_stub(
            tmp_path, store=store_err, rerank_status=503, sentinel_key=sentinel
        )
        with TestClient(app_err) as client:
            res_503 = client.post(
                "/v1/knowledge/search",
                headers={"Authorization": f"Bearer {tok}"},
                json=_body(rerank="CONFIGURED"),
            )
        assert res_503.status_code == 503
        assert res_503.json()["code"] == CODE_RERANK_UNAVAILABLE
        assert sentinel not in res_503.text
        assert (
            REGISTRY.get_sample_value(
                "knowledge_search_errors_total", {"status": "503", "caller_type": "PYTHON"}
            )
            >= 1.0
        )

        # 2. 超时返回 504 SEARCH_DEADLINE_EXCEEDED
        store_slow = _StubStore(delay_seconds=0.3)
        app_slow, _ = _app_with_stub(tmp_path, store=store_slow, sentinel_key=sentinel)
        with TestClient(app_slow) as client:
            res_504 = client.post(
                "/v1/knowledge/search",
                headers={"Authorization": f"Bearer {tok}"},
                json=_body(deadlineMs=100),
            )
        assert res_504.status_code == 504
        assert res_504.json()["code"] == "SEARCH_DEADLINE_EXCEEDED"
        assert (
            REGISTRY.get_sample_value(
                "knowledge_search_errors_total", {"status": "504", "caller_type": "PYTHON"}
            )
            >= 1.0
        )
    finally:
        SECRETS.discard(sentinel)

    # 日志红线（§10）：严禁出现查询原文、chunk 正文、令牌与 API Key
    assert "SECRET_QUERY_PHRASE" not in caplog.text
    assert "SECRET_ORIGINAL_QUERY" not in caplog.text
    assert "SECRET_CHUNK_BODY_TEXT" not in caplog.text
    assert sentinel not in caplog.text
    assert tok not in caplog.text
