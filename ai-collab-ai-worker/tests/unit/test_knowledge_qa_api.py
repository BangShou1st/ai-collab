"""``POST /v1/knowledge/qa:stream`` 端点测试（鉴权、校验、事件序、拒答与泄漏路径）。

检索管线用桩对象注入（``app.state.knowledge_search_pipeline``），chat 走真实 LangChain
适配器 + MockTransport 的 SSE 录制；越权/校验断言契约 §8.3 第 2 组。
"""

from __future__ import annotations

import asyncio
import base64
import json
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

from ai_collab_worker.api.knowledge_qa import KnowledgeQaService
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.knowledge_search import (
    KnowledgeSearchResponse,
    SearchDiagnostics,
    SearchHit,
    SearchScores,
    SourceRef,
)
from ai_collab_worker.contract.prompts import extract_variables, prompt_chars, prompt_sha256
from ai_collab_worker.main import create_app
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import (
    Lease,
    LeaseCache,
    LeasePurpose,
    LeaseRequest,
)
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.qa.constants import INSUFFICIENT_ANSWER, K_GRAM
from ai_collab_worker.security.redaction import SECRETS

_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("88" * 32))
_PUBLIC_SPKI = base64.b64encode(
    _KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")

USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
SESSION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
DOC_A = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
SNAP_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")

SYSTEM_BODY = "你是知识库助手。\n时间：{{ timeContext }}\n"
USER_BODY = "<QUESTION>{{ question }}</QUESTION>\n<SOURCES>{{ sources }}</SOURCES>"


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _token(
    *,
    scope: str = "knowledge:qa credentials:lease usage:report",
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
        "schemaVersion": 1,
        "requestId": str(uuid.uuid4()),
        "projectId": str(PROJECT_ID),
        "userId": str(USER_ID),
        "sessionId": str(SESSION_ID),
        "question": "设备巡检的周期是怎样规定的？",
        "documentIds": [],
        "visibleDocumentIds": [str(DOC_A)],
        "documentNames": {str(DOC_A): "A1-设备巡检维护简则.md"},
        "topK": 8,
        "history": [],
        "rerank": "HEURISTIC",
        "deadlineMs": 90_000,
    }
    payload.update(overrides)
    return payload


def _sse_chunks(text: str) -> str:
    """把整段回答拆成两个 SSE delta（带 usage 尾块），模拟 OpenAI 兼容流。"""

    mid = max(1, len(text) // 2)
    frames = []
    for part in (text[:mid], text[mid:]):
        frames.append(
            'data: {"choices": [{"index": 0, "delta": {"content": '
            + json.dumps(part, ensure_ascii=False)
            + '}, "finish_reason": null}]}\n\n'
        )
    frames.append(
        'data: {"choices": [], "usage": {"prompt_tokens": 42, "completion_tokens": 7}}\n\n'
    )
    frames.append("data: [DONE]\n\n")
    return "".join(frames)


class _StubSearchPipeline:
    """检索管线桩：返回构造好的 hits（引用身份齐全）。"""

    def __init__(self, hits: tuple[SearchHit, ...]) -> None:
        self._hits = hits

    async def execute(self, _request: Any, *, bearer_token: str) -> KnowledgeSearchResponse:
        assert bearer_token
        return KnowledgeSearchResponse(
            request_id=uuid.uuid4(),
            generation_id=None,
            strategy="DENSE_WITH_BOUNDED_FALLBACK",
            hits=self._hits,
            diagnostics=SearchDiagnostics(
                dense_candidates=1,
                lexical_candidates=0,
                iterative_scan=False,
                rerank="HEURISTIC",
                latency_ms=1,
            ),
        )


def _hit(content: str) -> SearchHit:
    return SearchHit(
        rank=1,
        document_id=DOC_A,
        chunk_no=3,
        content_hash="aa" * 32,
        chunk_type="STANDARD",
        parent_chunk_no=None,
        heading="第二条 巡检周期",
        content=content,
        body_snapshot_id=SNAP_ID,
        source_ref=SourceRef(page_number=2),
        context=None,
        scores=SearchScores(dense=0.83, lexical=None, rrf=None, rerank=None, final=0.83),
    )


def _write_prompts(root: Path) -> None:
    (root / "knowledge.system").mkdir(parents=True)
    (root / "knowledge.user").mkdir(parents=True)
    for key, body in (("knowledge.system", SYSTEM_BODY), ("knowledge.user", USER_BODY)):
        version = "2026-11-11.1"
        path = root / key / f"{version}.md"
        path.write_bytes((body + "\n").encode("utf-8"))
        manifest = root / "manifest.json"
        entries = (
            json.loads(manifest.read_text(encoding="utf-8"))["prompts"] if manifest.exists() else []
        )
        entries.append(
            {
                "key": key,
                "version": version,
                "sha256": prompt_sha256(body),
                "chars": prompt_chars(body),
                "variables": extract_variables(body),
            }
        )
        manifest.write_text(
            json.dumps({"schemaVersion": 1, "prompts": entries}, ensure_ascii=False),
            encoding="utf-8",
        )


def _app_with_stub(
    tmp_path: Path,
    *,
    hits: tuple[SearchHit, ...] | None = None,
    chat_body: str | None = None,
) -> tuple[FastAPI, list[LeaseRequest], list[dict[str, object]], UsageReporter]:
    prompts_dir = tmp_path / "prompts"
    _write_prompts(prompts_dir)
    app = create_app(Settings(prompts_dir=prompts_dir, ed25519_public_keys=f"k1={_PUBLIC_SPKI}"))
    lease_calls: list[LeaseRequest] = []
    submitted: list[dict[str, object]] = []
    secret = f"sk-qa-{uuid.uuid4().hex}"
    SECRETS.add(secret)

    async def fetch_lease(req: LeaseRequest, _token: str) -> Lease:
        lease_calls.append(req)
        return Lease.model_validate(
            {
                "leaseId": str(uuid.uuid4()),
                "protocol": "OPENAI_CHAT",
                "providerType": "OPENAI_COMPATIBLE",
                "baseUrl": "http://chat.test",
                "apiPath": "/v1/chat/completions",
                "model": "qwen2.5:7b",
                "apiKey": secret,
                "params": {},
                "capabilities": ["KNOWLEDGE_CHAT"],
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        )

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        for entry in items:
            submitted.append(dict(entry))
        return {str(entry["callId"]): True for entry in items}

    def transport(req: httpx2.Request) -> httpx2.Response:
        body = (
            chat_body
            if chat_body is not None
            else _sse_chunks("巡检周期为每七日一次。[S1] 应当记录台账。[S1]")
        )
        return httpx2.Response(
            200, content=body.encode(), headers={"content-type": "text/event-stream"}
        )

    reporter = UsageReporter(send_usage, max_batch=50, flush_interval=60.0)
    kernel = ModelKernel(
        leases=LeaseCache(fetch_lease),
        usage=reporter,
        runtime=ModelRuntime(
            timeout_seconds=5.0,
            max_retries=0,
            retry_backoff_seconds=0.0,
            transport=httpx2.MockTransport(transport),
        ),
    )
    # 用注入的 kernel 重建问答服务（create_app 装配的是指向真实 Java 端点的内核）。
    app.state.knowledge_qa_service = KnowledgeQaService(
        kernel=kernel,
        prompt_store=app.state.prompt_store,
        plan_service=None,
        plan_enabled=False,
    )
    app.state.knowledge_search_pipeline = _StubSearchPipeline(
        hits or (_hit("巡检周期为每七日一次，应当记录台账并按月归档。"),)
    )
    return app, lease_calls, submitted, reporter


def _events(raw: bytes) -> list[tuple[str, dict[str, Any]]]:
    parsed: list[tuple[str, dict[str, Any]]] = []
    for frame in raw.decode("utf-8").split("\n\n"):
        if not frame.strip():
            continue
        name = None
        data = ""
        for line in frame.split("\n"):
            if line.startswith("event: "):
                name = line[len("event: ") :]
            elif line.startswith("data: "):
                data = line[len("data: ") :]
        assert name is not None
        parsed.append((name, json.loads(data)))
    return parsed


def test_unauthorized_without_scope(tmp_path: Path) -> None:
    app, _calls, _submitted, _reporter = _app_with_stub(tmp_path)
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={
                "Authorization": "Bearer "
                + _token(scope="knowledge:search credentials:lease usage:report")
            },
            json=_body(),
        )
    assert res.status_code == 401
    assert res.json()["code"] == "UNAUTHORIZED"


def test_forbidden_when_uid_mismatch(tmp_path: Path) -> None:
    app, _calls, _submitted, _reporter = _app_with_stub(tmp_path)
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={"Authorization": f"Bearer {_token(uid=str(uuid.uuid4()))}"},
            json=_body(),
        )
    assert res.status_code == 403
    assert res.json()["code"] == "QA_FORBIDDEN"


def test_validation_error_on_overlong_question(tmp_path: Path) -> None:
    app, _calls, _submitted, _reporter = _app_with_stub(tmp_path)
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(question="问" * 2001),
        )
    assert res.status_code == 400
    assert res.json()["code"] == "VALIDATION_ERROR"


def test_empty_visible_documents_finalizes_insufficient_without_chat(tmp_path: Path) -> None:
    app, lease_calls, submitted, reporter = _app_with_stub(tmp_path, hits=())
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(visibleDocumentIds=[]),
        )
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/event-stream")
    assert res.headers.get("x-accel-buffering") == "no"
    events = _events(res.content)
    assert [name for name, _payload in events] == ["finalize"]
    payload = events[0][1]
    assert payload["insufficientEvidence"] is True
    assert payload["citations"] == []
    assert payload["model"] is None
    assert payload["answer"] == INSUFFICIENT_ANSWER
    assert payload["prompt"] is None
    assert payload["retrieval"]["strategy"] == "DENSE_WITH_BOUNDED_FALLBACK"
    # 空可见集合：不发起检索/生成租借
    assert lease_calls == []
    asyncio.run(reporter.flush())
    assert submitted == []


def test_happy_path_streams_tokens_then_finalize(tmp_path: Path) -> None:
    app, lease_calls, submitted, reporter = _app_with_stub(tmp_path)
    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(),
        )
    assert res.status_code == 200
    events = _events(res.content)
    names = [name for name, _payload in events]
    assert names[-1] == "finalize"
    assert "error" not in names
    tokens = [payload["text"] for name, payload in events if name == "token"]
    finalize = events[-1][1]
    # finalize.answer 与 token 拼接逐字节一致（§8.2.5 不变式）
    assert "".join(tokens) == finalize["answer"]
    assert "巡检周期为每七日一次" in finalize["answer"]
    assert finalize["insufficientEvidence"] is False
    assert len(finalize["citations"]) == 1
    citation = finalize["citations"][0]
    assert citation["documentId"] == str(DOC_A)
    assert citation["chunkNo"] == 3
    assert citation["contentHash"] == "aa" * 32
    assert citation["bodySnapshotId"] == str(SNAP_ID)
    assert citation["rank"] == 1
    assert finalize["prompt"]["key"] == "knowledge.system"
    assert finalize["model"]["provider"] == "OPENAI_COMPATIBLE"
    assert finalize["model"]["promptTokens"] == 42
    assert finalize["model"]["completionTokens"] == 7
    # 生成租借一次（KNOWLEDGE_CHAT），用量 SUCCESS 上报
    assert len(lease_calls) == 1
    assert lease_calls[0].purpose is LeasePurpose.KNOWLEDGE_CHAT
    asyncio.run(reporter.flush())
    assert submitted, "usage item must be reported"
    assert submitted[0]["status"] == "SUCCESS"


def test_capabilities_exposes_frozen_constants(tmp_path: Path) -> None:
    app, _calls, _submitted, _reporter = _app_with_stub(tmp_path)
    with TestClient(app) as client:
        res = client.get("/v1/knowledge/qa/capabilities")
    assert res.status_code == 200
    body = res.json()
    assert body["constants"]["kGram"] == K_GRAM
    assert body["texts"]["insufficientAnswer"] == INSUFFICIENT_ANSWER
    assert body["eventSequence"] == ["token", "finalize", "error"]


def test_leak_mid_stream_aborts_with_content_blocked(tmp_path: Path) -> None:
    leak_fragment = "巡检周期为每七日一次，应当记录台账并按月归档"
    assert len(leak_fragment) >= K_GRAM
    prompts_dir = tmp_path / "prompts"
    _write_prompts(prompts_dir)
    # 把系统提示词改成包含将要泄漏的片段
    leak_body = "你是知识库助手。规则原文：" + leak_fragment + "。{{ timeContext }}"
    (prompts_dir / "knowledge.system" / "2026-11-11.1.md").write_bytes(
        (leak_body + "\n").encode("utf-8")
    )
    manifest_path = prompts_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for entry in manifest["prompts"]:
        if entry["key"] == "knowledge.system":
            entry["sha256"] = prompt_sha256(leak_body)
            entry["chars"] = prompt_chars(leak_body)
            entry["variables"] = extract_variables(leak_body)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

    prompts_dir_res = prompts_dir
    app = create_app(
        Settings(prompts_dir=prompts_dir_res, ed25519_public_keys=f"k1={_PUBLIC_SPKI}")
    )
    secret = f"sk-qa-{uuid.uuid4().hex}"
    SECRETS.add(secret)

    async def fetch_lease(_req: LeaseRequest, _token: str) -> Lease:
        return Lease.model_validate(
            {
                "leaseId": str(uuid.uuid4()),
                "protocol": "OPENAI_CHAT",
                "providerType": "OPENAI_COMPATIBLE",
                "baseUrl": "http://chat.test",
                "apiPath": "/v1/chat/completions",
                "model": "qwen2.5:7b",
                "apiKey": secret,
                "params": {},
                "capabilities": ["KNOWLEDGE_CHAT"],
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        )

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        return {str(entry["callId"]): True for entry in items}

    kernel = ModelKernel(
        leases=LeaseCache(fetch_lease),
        usage=UsageReporter(send_usage, max_batch=50, flush_interval=60.0),
        runtime=ModelRuntime(
            timeout_seconds=5.0,
            max_retries=0,
            retry_backoff_seconds=0.0,
            transport=httpx2.MockTransport(
                lambda _req: httpx2.Response(
                    200,
                    content=_sse_chunks(leak_fragment + "。").encode(),
                    headers={"content-type": "text/event-stream"},
                )
            ),
        ),
    )
    app.state.knowledge_qa_service = KnowledgeQaService(
        kernel=kernel,
        prompt_store=app.state.prompt_store,
        plan_service=None,
        plan_enabled=False,
    )
    app.state.knowledge_search_pipeline = _StubSearchPipeline(
        (_hit("巡检周期为每七日一次，应当记录台账并按月归档。"),)
    )

    with TestClient(app) as client:
        res = client.post(
            "/v1/knowledge/qa:stream",
            headers={"Authorization": f"Bearer {_token()}"},
            json=_body(),
        )
    events = _events(res.content)
    names = [name for name, _payload in events]
    assert names[-1] == "error"
    assert "finalize" not in names
    assert events[-1][1]["code"] == "CONTENT_BLOCKED"


_ = pytest
