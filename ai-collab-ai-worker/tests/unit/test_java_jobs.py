"""``clients/java_jobs.py``：三个内部端点的形状、令牌透传与 §7.1 错误分类（respx，离线）。"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

import httpx
import pytest
import respx

from ai_collab_worker.clients.java_jobs import (
    HEARTBEAT_SUFFIX,
    REASON_CONTRACT,
    REASON_NOT_FOUND,
    REASON_RATE_LIMITED,
    REASON_REJECTED,
    REASON_STALE_LEASE,
    REASON_TIMEOUT,
    REASON_UNAVAILABLE,
    RESULTS_SUFFIX,
    SNAPSHOT_SUFFIX,
    JavaJobClient,
    JobGatewayError,
)
from ai_collab_worker.contract.memory_job import MemoryCandidate

BASE = "http://jobs.test"
JOB_ID = "00000000-0000-4000-8000-000000000001"
MEMORY_ID = "44444444-4444-4444-8444-444444444444"
TOKEN = "job-scoped-token-not-real"

SNAPSHOT = {
    "schemaVersion": 1,
    "jobId": JOB_ID,
    "projectId": "project-1",
    "attempt": 2,
    "leaseEpoch": 7,
    "snapshotHash": "sha256:abc",
    "facts": [{"ref": "fact-1", "kind": "DECISION", "text": "事实一。"}],
    "existingMemories": [
        {
            "id": MEMORY_ID,
            "type": "CONSTRAINT",
            "title": "不写业务表",
            "content": "Python 不写业务表。",
        }
    ],
}


def _candidate() -> MemoryCandidate:
    return MemoryCandidate.model_validate(
        {
            "kind": "DECISION",
            "title": "统一网关",
            "content": "所有模型调用经 Java 网关。",
            "confidence": 0.8,
            "sourceRef": "fact-1",
            "dedupeKey": "a" * 64,
        }
    )


def _client(**kwargs: object) -> JavaJobClient:
    return JavaJobClient(base_url=BASE, service_token="", **kwargs)


def test_fetch_snapshot_passes_token_and_parses_contract() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                route = router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(200, json=SNAPSHOT)
                )
                snapshot = await client.fetch_snapshot(
                    JOB_ID,
                    deadline_ms=5000,
                    bearer_token=TOKEN,
                    traceparent="00-trace-01",
                )
            assert snapshot.job_id == uuid.UUID(JOB_ID)
            assert snapshot.attempt == 2
            assert snapshot.source_refs() == frozenset({"fact-1", f"memory:{MEMORY_ID}"})
            request = route.calls[0].request
            assert request.headers["authorization"] == f"Bearer {TOKEN}"
            assert request.headers["traceparent"] == "00-trace-01"
            assert request.content == b""
        finally:
            await client.aclose()

    asyncio.run(run())


def test_heartbeat_posts_attempt_and_lease_epoch() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                route = router.post(f"/internal/ai/v1/jobs/{JOB_ID}{HEARTBEAT_SUFFIX}").mock(
                    return_value=httpx.Response(
                        200,
                        json={
                            "schemaVersion": 1,
                            "status": "RUNNING",
                            "leaseExpiresAt": "2026-11-12T10:00:00Z",
                        },
                    )
                )
                ack = await client.heartbeat(
                    JOB_ID, attempt=2, lease_epoch=7, deadline_ms=5000, bearer_token=TOKEN
                )
            assert ack.status.value == "RUNNING"
            assert json.loads(route.calls[0].request.content) == {
                "schemaVersion": 1,
                "attempt": 2,
                "leaseEpoch": 7,
            }
        finally:
            await client.aclose()

    asyncio.run(run())


def test_post_results_sends_candidates() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                route = router.post(f"/internal/ai/v1/jobs/{JOB_ID}{RESULTS_SUFFIX}").mock(
                    return_value=httpx.Response(
                        200,
                        json={
                            "schemaVersion": 1,
                            "jobId": JOB_ID,
                            "status": "SUCCEEDED",
                            "accepted": 1,
                            "duplicates": 0,
                        },
                    )
                )
                ack = await client.post_results(
                    JOB_ID,
                    attempt=2,
                    lease_epoch=7,
                    candidates=[_candidate()],
                    deadline_ms=5000,
                    bearer_token=TOKEN,
                )
            assert ack.accepted == 1
            body = json.loads(route.calls[0].request.content)
            assert body["attempt"] == 2
            assert body["leaseEpoch"] == 7
            assert body["candidates"][0]["dedupeKey"] == "a" * 64
            assert route.calls[0].request.headers["authorization"] == f"Bearer {TOKEN}"
        finally:
            await client.aclose()

    asyncio.run(run())


@pytest.mark.parametrize(
    ("status", "reason", "code", "retryable"),
    [
        (404, REASON_NOT_FOUND, "MODEL_UNAVAILABLE", False),
        (409, REASON_STALE_LEASE, "MODEL_UNAVAILABLE", False),
        (400, REASON_REJECTED, "MODEL_UNAVAILABLE", False),
        (429, REASON_RATE_LIMITED, "MODEL_RATE_LIMITED", True),
        (500, REASON_UNAVAILABLE, "MODEL_UNAVAILABLE", True),
        (503, REASON_UNAVAILABLE, "MODEL_UNAVAILABLE", True),
    ],
)
def test_status_classification(status: int, reason: str, code: str, retryable: bool) -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(status, text="upstream detail")
                )
                with pytest.raises(JobGatewayError) as excinfo:
                    await client.fetch_snapshot(JOB_ID, deadline_ms=1000, bearer_token=TOKEN)
            error = excinfo.value
            assert error.reason == reason
            assert error.code.value == code
            assert error.retryable is retryable
            assert error.status == status
            assert "upstream detail" not in str(error)
            assert TOKEN not in str(error)
        finally:
            await client.aclose()

    asyncio.run(run())


def test_timeout_maps_to_deadline_exceeded() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    side_effect=httpx.ReadTimeout("slow")
                )
                with pytest.raises(JobGatewayError) as excinfo:
                    await client.fetch_snapshot(JOB_ID, deadline_ms=10, bearer_token=TOKEN)
            assert excinfo.value.reason == REASON_TIMEOUT
            assert excinfo.value.code.value == "DEADLINE_EXCEEDED"
            assert excinfo.value.retryable is True
        finally:
            await client.aclose()

    asyncio.run(run())


@pytest.mark.parametrize(
    "payload",
    [
        {"schemaVersion": 1},
        {"schemaVersion": 1, "jobId": "not-a-uuid", "projectId": "p"},
        {"schemaVersion": 2, "jobId": JOB_ID, "projectId": "p"},
    ],
)
def test_contract_mismatch_is_rejected(payload: dict[str, object]) -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(200, json=payload)
                )
                with pytest.raises(JobGatewayError) as excinfo:
                    await client.fetch_snapshot(JOB_ID, deadline_ms=1000, bearer_token=TOKEN)
            assert excinfo.value.reason == REASON_CONTRACT
        finally:
            await client.aclose()

    asyncio.run(run())


def test_invalid_json_is_rejected_as_contract() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(200, text="<html>oops</html>")
                )
                with pytest.raises(JobGatewayError) as excinfo:
                    await client.fetch_snapshot(JOB_ID, deadline_ms=1000, bearer_token=TOKEN)
            assert excinfo.value.reason == REASON_CONTRACT
        finally:
            await client.aclose()

    asyncio.run(run())


def test_no_token_means_no_authorization_header() -> None:
    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                route = router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(200, json=SNAPSHOT)
                )
                await client.fetch_snapshot(JOB_ID, deadline_ms=1000, bearer_token="")
            assert "authorization" not in route.calls[0].request.headers
        finally:
            await client.aclose()

    asyncio.run(run())


def test_logs_never_contain_token_or_payload(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)

    async def run() -> None:
        client = _client()
        try:
            with respx.mock(base_url=BASE) as router:
                router.get(f"/internal/ai/v1/jobs/{JOB_ID}{SNAPSHOT_SUFFIX}").mock(
                    return_value=httpx.Response(200, json=SNAPSHOT)
                )
                await client.fetch_snapshot(JOB_ID, deadline_ms=1000, bearer_token=TOKEN)
        finally:
            await client.aclose()

    asyncio.run(run())

    assert TOKEN not in caplog.text
    assert "事实一" not in caplog.text
    assert "job_id=" in caplog.text
