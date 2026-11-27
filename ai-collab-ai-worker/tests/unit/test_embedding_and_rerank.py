"""P2 向量化（OPENAI_EMBEDDINGS / GEMINI_EMBEDDINGS）与 LLAMACPP_RERANK 单元测试与凭据哨兵。"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
import traceback
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx2
import pytest

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.errors import (
    REASON_AUTH,
    REASON_INVALID_OUTPUT,
    USAGE_INVALID_OUTPUT,
    USAGE_PROVIDER_ERROR,
    USAGE_SUCCESS,
    USAGE_TIMEOUT,
)
from ai_collab_worker.models.factory import (
    EmbeddingTaskType,
    ModelRuntime,
    RerankDocument,
)
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import Lease, LeaseCache, LeasePurpose, LeaseRequest
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.observability.metrics import (
    AI_INDEX_GENERATION_ROWS,
    AI_INDEX_TASK_DURATION,
    KNOWLEDGE_SEARCH_ERRORS,
    KNOWLEDGE_SEARCH_HITS,
    KNOWLEDGE_SEARCH_LATENCY,
    REGISTRY,
    increment_counter,
    observe_histogram,
    set_gauge,
)
from ai_collab_worker.security.redaction import SECRETS

USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
TASK_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
BEARER = "tok.p2.kernel.test"


def _lease(
    *,
    protocol: str,
    provider_type: str,
    base_url: str,
    api_path: str | None,
    model: str,
    api_key: str | None,
    params: dict[str, Any],
    capabilities: list[str],
) -> Lease:
    return Lease.model_validate(
        {
            "leaseId": str(uuid.uuid4()),
            "protocol": protocol,
            "providerType": provider_type,
            "baseUrl": base_url,
            "apiPath": api_path,
            "model": model,
            "apiKey": api_key,
            "params": params,
            "capabilities": capabilities,
            "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
        }
    )


def _kernel(
    lease_obj: Lease,
    transport: httpx2.AsyncBaseTransport,
    *,
    max_retries: int = 2,
) -> tuple[ModelKernel, list[dict[str, object]], LeaseCache, list[LeaseRequest]]:
    lease_calls: list[LeaseRequest] = []
    submitted: list[dict[str, object]] = []

    async def fetch_lease(req: LeaseRequest, _token: str) -> Lease:
        lease_calls.append(req)
        return lease_obj

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        for entry in items:
            submitted.append(dict(entry))
        return {str(entry["callId"]): True for entry in items}

    cache = LeaseCache(fetch_lease)
    reporter = UsageReporter(send_usage, max_batch=50, flush_interval=60.0)
    kernel = ModelKernel(
        leases=cache,
        usage=reporter,
        runtime=ModelRuntime(
            timeout_seconds=5.0,
            max_retries=max_retries,
            retry_backoff_seconds=0.0,
            transport=transport,
        ),
    )
    return kernel, submitted, cache, lease_calls


def test_openai_embeddings_keyed_document_and_query_trim_and_usage() -> None:
    sentinel = f"sk-live-{secrets.token_urlsafe(24)}"
    requests_seen: list[tuple[dict[str, str], dict[str, Any]]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        body = json.loads(request.content.decode("utf-8"))
        requests_seen.append((dict(request.headers), body))
        inputs = body["input"]
        # 故意逆序返回 index，验证按 index 还原顺序。
        data = [
            {"index": i, "embedding": [float(i + 1), 0.5]} for i in reversed(range(len(inputs)))
        ]
        return httpx2.Response(
            200,
            json={"data": data, "usage": {"prompt_tokens": len(inputs) * 3}},
        )

    lease = _lease(
        protocol="OPENAI_EMBEDDINGS",
        provider_type="OPENAI_COMPATIBLE",
        base_url="https://api.openai.test",
        api_path="/v1/embeddings",
        model="text-embedding-3-small",
        api_key=sentinel,
        params={"dimensions": 1024, "batchSize": 2},
        capabilities=["EMBEDDING"],
    )

    async def main() -> tuple[Any, Any, list[dict[str, object]]]:
        kernel, submitted, _cache, _calls = _kernel(lease, httpx2.MockTransport(handler))
        doc_scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=None,
            project_id=None,
            task_id=TASK_ID,
            bearer_token=BEARER,
        )
        # 文档侧保留首尾空白与换行，绝不 trim、绝不拼标题或前缀。
        doc_res = await kernel.embed_texts(
            doc_scope,
            ["  doc-one\n", "doc-two", "  doc-three  "],
            task_type=EmbeddingTaskType.DOCUMENT,
        )
        query_scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=USER_ID,
            project_id=PROJECT_ID,
            bearer_token=BEARER,
        )
        # 查询侧按 Java String.trim() 口径裁剪首尾控制字符，不裁剪内部空白也不加前缀。
        query_res = await kernel.embed_texts(
            query_scope,
            ["\x00  hello world \n\x1f"],
            task_type=EmbeddingTaskType.QUERY,
        )
        await kernel._usage.flush(ignore_backoff=True)
        return doc_res, query_res, submitted

    try:
        doc_res, query_res, submitted = asyncio.run(main())
    finally:
        SECRETS.discard(sentinel)

    assert len(requests_seen) == 3  # batchSize=2 -> 文档 2 批 + 查询 1 批
    hdr0, body0 = requests_seen[0]
    assert hdr0["authorization"] == f"Bearer {sentinel}"
    assert body0 == {
        "model": "text-embedding-3-small",
        "input": ["  doc-one\n", "doc-two"],
        "dimensions": 1024,
    }
    assert requests_seen[1][1]["input"] == ["  doc-three  "]
    assert requests_seen[2][1]["input"] == ["hello world"]
    assert doc_res.vectors == ((1.0, 0.5), (2.0, 0.5), (1.0, 0.5))
    assert doc_res.dimension == 2
    assert doc_res.input_tokens == 9
    assert query_res.input_tokens == 3
    assert [entry["status"] for entry in submitted] == [USAGE_SUCCESS, USAGE_SUCCESS]
    assert [entry["inputTokens"] for entry in submitted] == [9, 3]
    assert sentinel not in repr(submitted)


def test_ollama_embeddings_omits_auth_header_and_dimensions() -> None:
    seen_headers: list[dict[str, str]] = []
    seen_bodies: list[dict[str, Any]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen_headers.append(dict(request.headers))
        seen_bodies.append(json.loads(request.content.decode("utf-8")))
        return httpx2.Response(
            200,
            json={"data": [{"embedding": [0.1, 0.2, 0.3]}]},
        )

    lease = _lease(
        protocol="OPENAI_EMBEDDINGS",
        provider_type="OLLAMA",
        base_url="http://ollama.local:11434",
        api_path="/v1/embeddings",
        model="qwen3-embedding:0.6b",
        api_key=None,
        params={"dimensions": None, "batchSize": 16},
        capabilities=["EMBEDDING"],
    )

    async def main() -> None:
        kernel, submitted, _cache, _calls = _kernel(lease, httpx2.MockTransport(handler))
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=None,
            project_id=None,
            task_id=TASK_ID,
            bearer_token=BEARER,
        )
        res = await kernel.embed_texts(
            scope, ["chunk-content"], task_type=EmbeddingTaskType.DOCUMENT
        )
        await kernel._usage.flush(ignore_backoff=True)
        assert res.vectors == ((0.1, 0.2, 0.3),)
        assert submitted[0]["status"] == USAGE_SUCCESS

    asyncio.run(main())
    assert "authorization" not in seen_headers[0]
    assert "dimensions" not in seen_bodies[0]


def test_gemini_embeddings_protocol_batch_cap_100_and_400_invalid_key_invalidates_lease(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    sentinel = f"AIza-{secrets.token_urlsafe(24)}"
    seen_urls: list[str] = []
    seen_headers: list[dict[str, str]] = []
    seen_bodies: list[dict[str, Any]] = []

    def ok_handler(request: httpx2.Request) -> httpx2.Response:
        seen_urls.append(str(request.url))
        seen_headers.append(dict(request.headers))
        body = json.loads(request.content.decode("utf-8"))
        seen_bodies.append(body)
        n = len(body["requests"])
        return httpx2.Response(
            200,
            json={"embeddings": [{"values": [0.25, 0.75]} for _ in range(n)]},
        )

    lease = _lease(
        protocol="GEMINI_EMBEDDINGS",
        provider_type="GEMINI",
        base_url="https://generativelanguage.googleapis.test",
        api_path="/v1beta",
        model="gemini-embedding-001",
        api_key=sentinel,
        params={"dimensions": 1024, "batchSize": 256},
        capabilities=["EMBEDDING"],
    )

    async def run_ok() -> int:
        kernel, _submitted, _cache, _calls = _kernel(lease, httpx2.MockTransport(ok_handler))
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=None,
            project_id=None,
            task_id=TASK_ID,
            bearer_token=BEARER,
        )
        res = await kernel.embed_texts(
            scope,
            [f"chunk-{i}" for i in range(105)],
            task_type=EmbeddingTaskType.DOCUMENT,
        )
        return len(res.vectors)

    try:
        count = asyncio.run(run_ok())
        assert count == 105
        assert seen_urls == [
            "https://generativelanguage.googleapis.test/v1beta/models/gemini-embedding-001:batchEmbedContents",
            "https://generativelanguage.googleapis.test/v1beta/models/gemini-embedding-001:batchEmbedContents",
        ]
        assert len(seen_bodies[0]["requests"]) == 100
        assert len(seen_bodies[1]["requests"]) == 5
        assert seen_headers[0]["x-goog-api-key"] == sentinel
        assert "authorization" not in seen_headers[0]
        first_req = seen_bodies[0]["requests"][0]
        assert first_req == {
            "model": "models/gemini-embedding-001",
            "content": {"parts": [{"text": "chunk-0"}]},
            "taskType": "RETRIEVAL_DOCUMENT",
            "outputDimensionality": 1024,
        }

        # 400 "API key not valid" 判成 provider_auth 并失效 lease 缓存。
        def bad_key_handler(_request: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                400,
                json={"error": {"message": f"API key not valid. key={sentinel}"}},
            )

        async def run_bad_key() -> tuple[AppError, int, list[dict[str, object]]]:
            kernel, submitted, cache, calls = _kernel(lease, httpx2.MockTransport(bad_key_handler))
            scope = CallScope(
                purpose=LeasePurpose.EMBEDDING,
                user_id=USER_ID,
                project_id=PROJECT_ID,
                bearer_token=BEARER,
            )
            with pytest.raises(AppError) as err1:
                await kernel.embed_texts(scope, ["q"], task_type=EmbeddingTaskType.QUERY)
            # 缓存已失效，第二次会重新发起租借。
            with pytest.raises(AppError):
                await kernel.embed_texts(scope, ["q"], task_type=EmbeddingTaskType.QUERY)
            await kernel._usage.flush(ignore_backoff=True)
            assert len(cache) == 0
            return err1.value, len(calls), submitted

        err1, lease_call_count, submitted = asyncio.run(run_bad_key())
        assert err1.details is not None
        assert err1.details["reason"] == REASON_AUTH
        assert lease_call_count == 2
        assert submitted[0]["status"] == USAGE_PROVIDER_ERROR
        assert submitted[0]["errorCode"] == REASON_AUTH
        assert sentinel not in str(err1)
        assert sentinel not in "".join(traceback.format_exception(err1))
        assert sentinel not in caplog.text
    finally:
        SECRETS.discard(sentinel)


def test_openai_embeddings_retries_5xx_and_rejects_partial_index() -> None:
    attempts = 0

    def flaky_handler(_request: httpx2.Request) -> httpx2.Response:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            return httpx2.Response(503, json={"error": "busy"})
        return httpx2.Response(200, json={"data": [{"embedding": [1.0, 2.0]}]})

    lease = _lease(
        protocol="OPENAI_EMBEDDINGS",
        provider_type="OPENAI_COMPATIBLE",
        base_url="https://api.openai.test",
        api_path="/v1/embeddings",
        model="bge-m3",
        api_key="sk-retry-test",
        params={"dimensions": None, "batchSize": 8},
        capabilities=["EMBEDDING"],
    )

    async def run_retry() -> tuple[float, ...]:
        kernel, _sub, _cache, _calls = _kernel(lease, httpx2.MockTransport(flaky_handler))
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=USER_ID,
            project_id=PROJECT_ID,
            bearer_token=BEARER,
        )
        res = await kernel.embed_texts(scope, ["ok"], task_type=EmbeddingTaskType.QUERY)
        return res.vectors[0]

    assert asyncio.run(run_retry()) == (1.0, 2.0)
    assert attempts == 3

    def partial_index_handler(_request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json={"data": [{"index": 0, "embedding": [1.0]}, {"embedding": [2.0]}]},
        )

    async def run_partial() -> tuple[AppError, list[dict[str, object]]]:
        kernel, submitted, _cache, _calls = _kernel(
            lease, httpx2.MockTransport(partial_index_handler)
        )
        scope = CallScope(
            purpose=LeasePurpose.EMBEDDING,
            user_id=USER_ID,
            project_id=PROJECT_ID,
            bearer_token=BEARER,
        )
        with pytest.raises(AppError) as err:
            await kernel.embed_texts(scope, ["a", "b"], task_type=EmbeddingTaskType.DOCUMENT)
        await kernel._usage.flush(ignore_backoff=True)
        return err.value, submitted

    err, submitted = asyncio.run(run_partial())
    assert err.details is not None
    assert err.details["reason"] == REASON_INVALID_OUTPUT
    assert submitted[0]["status"] == USAGE_INVALID_OUTPUT
    assert submitted[0]["errorCode"] == REASON_INVALID_OUTPUT


def test_llamacpp_rerank_slicing_optional_auth_and_strict_failure_usage(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    sentinel = f"sk-rerank-{secrets.token_urlsafe(24)}"
    seen_headers: list[dict[str, str]] = []
    seen_bodies: list[dict[str, Any]] = []

    def ok_handler(request: httpx2.Request) -> httpx2.Response:
        seen_headers.append(dict(request.headers))
        seen_bodies.append(json.loads(request.content.decode("utf-8")))
        return httpx2.Response(
            200,
            json={
                "results": [
                    {"index": 1, "relevance_score": 0.92},
                    {"index": 0, "relevance_score": 0.41},
                ]
            },
        )

    keyless_lease = _lease(
        protocol="LLAMACPP_RERANK",
        provider_type="LLAMACPP_RERANK",
        base_url="http://rerank.local:8088",
        api_path="/rerank",
        model="bge-reranker-v2-m3",
        api_key=None,
        params={"maxCandidates": 2, "timeoutMs": 1500},
        capabilities=["RERANK"],
    )

    async def run_ok() -> tuple[Any, list[dict[str, object]]]:
        kernel, submitted, _cache, _calls = _kernel(keyless_lease, httpx2.MockTransport(ok_handler))
        scope = CallScope(
            purpose=LeasePurpose.RERANK,
            user_id=USER_ID,
            project_id=PROJECT_ID,
            bearer_token=BEARER,
        )
        docs = [
            RerankDocument(heading="标题一", text="第一段内容"),
            RerankDocument(heading=None, text="第二段内容"),
            RerankDocument(heading="第三", text="尾部不会被发往 reranker"),
        ]
        res = await kernel.rerank(scope, query="查询词", documents=docs)
        await kernel._usage.flush(ignore_backoff=True)
        return res, submitted

    res, submitted = asyncio.run(run_ok())
    assert "authorization" not in seen_headers[0]
    assert seen_bodies[0] == {
        "query": "查询词",
        "documents": ["标题一\n第一段内容", "第二段内容"],
        "top_n": 2,
    }
    assert res.scores == (0.41, 0.92)
    assert res.max_candidates == 2
    assert len(submitted) == 1
    assert submitted[0]["purpose"] == "RERANK"
    assert submitted[0]["inputTokens"] == 0
    assert submitted[0]["outputTokens"] == 0
    assert submitted[0]["status"] == USAGE_SUCCESS
    assert isinstance(submitted[0]["latencyMs"], int)

    # 超时与非法响应：只打一次（绝不重试），并同步上报失败用量且不漏哨兵密钥。
    keyed_lease = _lease(
        protocol="LLAMACPP_RERANK",
        provider_type="LLAMACPP_RERANK",
        base_url="http://rerank.local:8088",
        api_path="/rerank",
        model="bge-reranker-v2-m3",
        api_key=sentinel,
        params={"maxCandidates": 2, "timeoutMs": 500},
        capabilities=["RERANK"],
    )
    attempts = 0

    def timeout_handler(_request: httpx2.Request) -> httpx2.Response:
        nonlocal attempts
        attempts += 1
        raise httpx2.ReadTimeout(f"timeout leaking {sentinel}")

    async def run_timeout() -> tuple[AppError, list[dict[str, object]]]:
        kernel, submitted2, _cache, _calls = _kernel(
            keyed_lease, httpx2.MockTransport(timeout_handler), max_retries=2
        )
        scope = CallScope(
            purpose=LeasePurpose.RERANK,
            user_id=USER_ID,
            project_id=PROJECT_ID,
            bearer_token=BEARER,
        )
        with pytest.raises(AppError) as err:
            await kernel.rerank(
                scope,
                query="q",
                documents=[RerankDocument(heading=None, text="doc")],
            )
        await kernel._usage.flush(ignore_backoff=True)
        return err.value, submitted2

    try:
        err, submitted2 = asyncio.run(run_timeout())
        assert attempts == 1  # rerank 绝不重试
        assert err.code is ErrorCode.DEADLINE_EXCEEDED
        assert submitted2[0]["status"] == USAGE_TIMEOUT
        assert submitted2[0]["inputTokens"] == 0
        assert submitted2[0]["outputTokens"] == 0
        assert sentinel not in str(err)
        assert sentinel not in caplog.text
    finally:
        SECRETS.discard(sentinel)


def test_p2_prometheus_metrics_registered_and_recordable() -> None:
    observe_histogram(AI_INDEX_TASK_DURATION, 0.42, taskType="INDEX_DOCUMENT", caller_type="PYTHON")
    observe_histogram(
        KNOWLEDGE_SEARCH_LATENCY,
        0.15,
        intent="KNOWLEDGE_QA",
        strategy="HYBRID",
        caller_type="PYTHON",
    )
    observe_histogram(KNOWLEDGE_SEARCH_HITS, 5, intent="KNOWLEDGE_QA", caller_type="PYTHON")
    increment_counter(KNOWLEDGE_SEARCH_ERRORS, status="503", caller_type="PYTHON")
    set_gauge(AI_INDEX_GENERATION_ROWS, 128, status="ACTIVE", caller_type="PYTHON")

    assert (
        REGISTRY.get_sample_value(
            "ai_index_task_duration_seconds_count",
            {"taskType": "INDEX_DOCUMENT", "caller_type": "PYTHON"},
        )
        >= 1.0
    )
    assert (
        REGISTRY.get_sample_value(
            "knowledge_search_latency_seconds_count",
            {"intent": "KNOWLEDGE_QA", "strategy": "HYBRID", "caller_type": "PYTHON"},
        )
        >= 1.0
    )
    assert (
        REGISTRY.get_sample_value(
            "knowledge_search_hits_count",
            {"intent": "KNOWLEDGE_QA", "caller_type": "PYTHON"},
        )
        >= 1.0
    )
    assert (
        REGISTRY.get_sample_value(
            "knowledge_search_errors_total",
            {"status": "503", "caller_type": "PYTHON"},
        )
        >= 1.0
    )
    assert (
        REGISTRY.get_sample_value(
            "ai_index_generation_rows",
            {"status": "ACTIVE", "caller_type": "PYTHON"},
        )
        == 128.0
    )
