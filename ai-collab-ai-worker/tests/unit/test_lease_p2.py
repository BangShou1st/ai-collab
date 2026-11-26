"""P2 对凭据租借模型的扩展（P1-R4 / P1-R5、P2-R1 第 3/4 条）的单元测试。

- ``LeaseRequest``：只有"带 taskId 的 EMBEDDING 租借"允许 userId/projectId 为空；
- ``Lease``：``RERANK`` 用途、``LLAMACPP_RERANK`` / ``GEMINI_EMBEDDINGS`` 协议、``apiKey = null``、
  向量化 params ``{dimensions, batchSize}``、rerank params ``{maxCandidates, timeoutMs}``；
- 无凭据 lease 不进脱敏登记表，也不会被 ``reveal_key`` 偷偷当成空串用；
- 校验错误不回显密钥。
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from pydantic import ValidationError

from ai_collab_worker.models.lease import (
    Lease,
    LeaseCache,
    LeaseHandle,
    LeaseProtocol,
    LeasePurpose,
    LeaseRequest,
)
from ai_collab_worker.security.redaction import SecretRegistry

TASK_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
USER_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
PROJECT_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
SENTINEL = "sk-p2-lease-SENTINEL-7f3a9c"


def _lease_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "leaseId": str(uuid.uuid4()),
        "protocol": "OPENAI_EMBEDDINGS",
        "providerType": "OLLAMA",
        "baseUrl": "http://ollama.test:11434/",
        "apiPath": "/v1/embeddings",
        "model": "qwen3-embedding:0.6b",
        "apiKey": None,
        "params": {"dimensions": None, "batchSize": 32},
        "capabilities": ["EMBEDDING"],
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
    }
    payload.update(overrides)
    return payload


# ---- LeaseRequest --------------------------------------------------------------


def test_system_embedding_lease_allows_null_principal_with_task() -> None:
    request = LeaseRequest(
        purpose=LeasePurpose.EMBEDDING, user_id=None, project_id=None, task_id=TASK_ID
    )
    body = request.model_dump(by_alias=True, mode="json")
    assert body == {
        "purpose": "EMBEDDING",
        "userId": None,
        "projectId": None,
        "taskId": str(TASK_ID),
        "runId": None,
    }
    assert request.cache_key() == ("EMBEDDING", "-", "-", str(TASK_ID))


def test_index_document_lease_may_carry_project_only() -> None:
    request = LeaseRequest(
        purpose=LeasePurpose.EMBEDDING, user_id=None, project_id=PROJECT_ID, task_id=TASK_ID
    )
    assert request.cache_key() == ("EMBEDDING", "-", str(PROJECT_ID), str(TASK_ID))


@pytest.mark.parametrize(
    ("purpose", "task_id"),
    [
        (LeasePurpose.EMBEDDING, None),  # 不带 taskId：照旧必填
        (LeasePurpose.RERANK, TASK_ID),  # 只放宽 EMBEDDING
        (LeasePurpose.MEMORY_EXTRACTION, TASK_ID),
        (LeasePurpose.QUERY_PLAN, None),
    ],
)
def test_null_principal_rejected_outside_system_embedding(
    purpose: LeasePurpose, task_id: uuid.UUID | None
) -> None:
    with pytest.raises(ValidationError):
        LeaseRequest(purpose=purpose, user_id=None, project_id=None, task_id=task_id)
    with pytest.raises(ValidationError):
        LeaseRequest(purpose=purpose, user_id=USER_ID, project_id=None, task_id=task_id)


def test_search_rerank_lease_request_requires_principal() -> None:
    request = LeaseRequest(purpose=LeasePurpose.RERANK, user_id=USER_ID, project_id=PROJECT_ID)
    assert request.cache_key() == ("RERANK", str(USER_ID), str(PROJECT_ID), "-")


# ---- Lease ---------------------------------------------------------------------


def test_keyless_openai_embedding_lease_parses() -> None:
    lease = Lease.model_validate(_lease_payload())
    assert lease.protocol is LeaseProtocol.OPENAI_EMBEDDINGS
    assert not lease.has_key
    assert lease.reveal_key_or_none() is None
    assert lease.params.dimensions is None
    assert lease.params.batch_size == 32
    assert lease.base_url == "http://ollama.test:11434"
    with pytest.raises(RuntimeError):
        lease.reveal_key()


def test_blank_api_key_is_treated_as_missing() -> None:
    lease = Lease.model_validate(_lease_payload(apiKey=""))
    assert lease.api_key is None


def test_gemini_embedding_lease_requires_key_and_batch_size() -> None:
    lease = Lease.model_validate(
        _lease_payload(
            protocol="GEMINI_EMBEDDINGS",
            providerType="GEMINI",
            apiPath="/v1beta",
            apiKey=SENTINEL,
            params={"dimensions": 1024, "batchSize": 100},
        )
    )
    assert lease.params.dimensions == 1024
    assert lease.reveal_key() == SENTINEL
    with pytest.raises(ValidationError) as missing_key:
        Lease.model_validate(_lease_payload(protocol="GEMINI_EMBEDDINGS", apiKey=None))
    assert SENTINEL not in str(missing_key.value)
    with pytest.raises(ValidationError) as missing_batch:
        Lease.model_validate(
            _lease_payload(protocol="GEMINI_EMBEDDINGS", apiKey=SENTINEL, params={})
        )
    assert SENTINEL not in str(missing_batch.value)


def test_embedding_lease_rejects_out_of_range_params() -> None:
    for params in ({"batchSize": 0}, {"batchSize": 257}, {"batchSize": 8, "dimensions": 0}):
        with pytest.raises(ValidationError):
            Lease.model_validate(_lease_payload(params=params))


def test_llamacpp_rerank_lease_without_credentials() -> None:
    lease = Lease.model_validate(
        _lease_payload(
            protocol="LLAMACPP_RERANK",
            providerType="LLAMA_CPP",
            apiPath="/v1/rerank",
            model="bge-reranker",
            apiKey=None,
            params={"maxCandidates": 8, "timeoutMs": 3000},
            capabilities=["RERANK"],
        )
    )
    assert lease.protocol is LeaseProtocol.LLAMACPP_RERANK
    assert lease.params.max_candidates == 8
    assert lease.params.timeout_ms == 3000
    assert lease.has_capability("RERANK")
    assert lease.reveal_key_or_none() is None


def test_rerank_lease_requires_its_params() -> None:
    with pytest.raises(ValidationError):
        Lease.model_validate(
            _lease_payload(protocol="LLAMACPP_RERANK", params={"maxCandidates": 8})
        )


def test_chat_lease_still_requires_key() -> None:
    with pytest.raises(ValidationError):
        Lease.model_validate(_lease_payload(protocol="OPENAI_CHAT", apiKey=None, params={}))


def test_rerank_purpose_and_protocol_values_are_frozen() -> None:
    assert LeasePurpose.RERANK.value == "RERANK"
    assert {p.value for p in LeaseProtocol} == {
        "OPENAI_CHAT",
        "ANTHROPIC_MESSAGES",
        "GEMINI_GENERATE_CONTENT",
        "OPENAI_EMBEDDINGS",
        "GEMINI_EMBEDDINGS",
        "LLAMACPP_RERANK",
    }


# ---- 脱敏登记 ------------------------------------------------------------------


def test_keyless_handle_does_not_touch_registry() -> None:
    registry = SecretRegistry()
    handle = LeaseHandle(Lease.model_validate(_lease_payload()), registry)
    assert len(registry) == 0
    handle.release()
    assert len(registry) == 0


def test_keyed_handle_registers_and_releases() -> None:
    registry = SecretRegistry()
    lease = Lease.model_validate(_lease_payload(protocol="GEMINI_EMBEDDINGS", apiKey=SENTINEL))
    handle = LeaseHandle(lease, registry)
    assert SENTINEL in registry
    handle.release()
    assert SENTINEL not in registry


def test_cache_handles_keyless_lease_and_system_request() -> None:
    registry = SecretRegistry()
    calls: list[LeaseRequest] = []

    async def fetch(request: LeaseRequest, _token: str) -> Lease:
        calls.append(request)
        return Lease.model_validate(_lease_payload())

    cache = LeaseCache(fetch, registry=registry)
    request = LeaseRequest(
        purpose=LeasePurpose.EMBEDDING, user_id=None, project_id=None, task_id=TASK_ID
    )

    async def scenario() -> None:
        async with await cache.acquire(request, bearer_token="t") as first:
            assert first.reveal_key_or_none() is None
        async with await cache.acquire(request, bearer_token="t"):
            pass

    asyncio.run(scenario())
    assert len(calls) == 1
    assert len(registry) == 0
    cache.clear()
    assert len(registry) == 0
