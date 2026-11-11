"""Memory Job 契约（冻结 §3.4/§3.5）：请求/响应、候选、快照与内部端点形状。"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.memory_job import (
    DEDUPE_KEY_PATTERN,
    MAX_CANDIDATE_CONTENT_CHARS,
    MAX_CANDIDATE_TITLE_CHARS,
    MAX_MEMORY_CANDIDATES,
    MAX_MEMORY_JOB_DEADLINE_MS,
    MAX_SNAPSHOT_CHARS,
    ExistingMemory,
    JobHeartbeatRequest,
    JobHeartbeatResponse,
    JobResultsRequest,
    JobResultsResponse,
    MemoryCandidate,
    MemoryJobRequest,
    MemoryJobResponse,
    MemoryJobSnapshot,
    MemoryJobStatus,
    MemoryKind,
    SnapshotFact,
)

JOB_ID = uuid.UUID("00000000-0000-4000-8000-000000000001")
MEMORY_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
DEDUPE = "a" * 64


def _candidate(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "kind": "DECISION",
        "title": "统一使用 Java 网关",
        "content": "所有模型调用都经 Java 内部模型网关。",
        "confidence": 0.8,
        "sourceRef": "fact-1",
        "dedupeKey": DEDUPE,
    }
    payload.update(overrides)
    return payload


def _snapshot(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "jobId": str(JOB_ID),
        "projectId": "project-1",
        "attempt": 1,
        "leaseEpoch": 2,
        "snapshotHash": "sha256:abc",
        "facts": [{"ref": "fact-1", "kind": "DECISION", "text": "事实一。"}],
        "existingMemories": [
            {
                "id": str(MEMORY_ID),
                "type": "CONSTRAINT",
                "title": "不写业务表",
                "content": "Python 不写业务表。",
            }
        ],
    }
    payload.update(overrides)
    return payload


def test_job_request_matches_frozen_shape() -> None:
    request = MemoryJobRequest.model_validate(
        {"schemaVersion": 1, "jobId": str(JOB_ID), "type": "MEMORY_EXTRACTION"}
    )

    assert request.deadline_ms == 60000
    dumped = request.model_dump(by_alias=True, mode="json")
    assert set(dumped) == {"schemaVersion", "jobId", "type", "deadlineMs"}
    assert dumped["type"] == "MEMORY_EXTRACTION"


@pytest.mark.parametrize("job_type", ["MEMORY_DREAMING", "memory_extraction", ""])
def test_job_type_is_frozen_to_memory_extraction(job_type: str) -> None:
    with pytest.raises(ValidationError):
        MemoryJobRequest.model_validate(
            {"jobId": str(JOB_ID), "type": job_type, "deadlineMs": 1000}
        )


@pytest.mark.parametrize(
    ("value", "valid"),
    [
        (1, True),
        (MAX_MEMORY_JOB_DEADLINE_MS, True),
        (0, False),
        (MAX_MEMORY_JOB_DEADLINE_MS + 1, False),
    ],
)
def test_job_deadline_bounds(value: int, valid: bool) -> None:
    payload = {"jobId": str(JOB_ID), "type": "MEMORY_EXTRACTION", "deadlineMs": value}
    if valid:
        assert MemoryJobRequest.model_validate(payload).deadline_ms == value
    else:
        with pytest.raises(ValidationError):
            MemoryJobRequest.model_validate(payload)


def test_job_request_rejects_booleans_and_extra_fields() -> None:
    with pytest.raises(ValidationError):
        MemoryJobRequest.model_validate(
            {"jobId": str(JOB_ID), "type": "MEMORY_EXTRACTION", "deadlineMs": True}
        )
    with pytest.raises(ValidationError):
        MemoryJobRequest.model_validate(
            {"jobId": str(JOB_ID), "type": "MEMORY_EXTRACTION", "snapshot": {}}
        )


def test_job_response_shape_and_status_enum() -> None:
    response = MemoryJobResponse(jobId=JOB_ID, status="SUCCEEDED", candidateCount=3, latencyMs=120)

    assert set(response.model_dump(by_alias=True, mode="json")) == {
        "schemaVersion",
        "jobId",
        "status",
        "candidateCount",
        "latencyMs",
    }
    for status in ("SUCCEEDED", "FAILED_RETRYABLE", "FAILED"):
        assert (
            MemoryJobResponse(
                jobId=JOB_ID, status=status, candidateCount=0, latencyMs=1
            ).status.value
            == status
        )
    with pytest.raises(ValidationError):
        MemoryJobResponse(jobId=JOB_ID, status="RUNNING", candidateCount=0, latencyMs=1)


@pytest.mark.parametrize("count", [0, MAX_MEMORY_CANDIDATES, MAX_MEMORY_CANDIDATES + 1, -1])
def test_job_response_candidate_count_bounds(count: int) -> None:
    if 0 <= count <= MAX_MEMORY_CANDIDATES:
        assert (
            MemoryJobResponse(
                jobId=JOB_ID, status="SUCCEEDED", candidateCount=count, latencyMs=1
            ).candidate_count
            == count
        )
    else:
        with pytest.raises(ValidationError):
            MemoryJobResponse(jobId=JOB_ID, status="SUCCEEDED", candidateCount=count, latencyMs=1)


def test_candidate_bounds() -> None:
    assert MemoryCandidate.model_validate(_candidate(title="x" * MAX_CANDIDATE_TITLE_CHARS))
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(title="x" * (MAX_CANDIDATE_TITLE_CHARS + 1)))
    assert MemoryCandidate.model_validate(_candidate(content="x" * MAX_CANDIDATE_CONTENT_CHARS))
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(content="x" * (MAX_CANDIDATE_CONTENT_CHARS + 1)))
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(title=""))
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(content=""))


@pytest.mark.parametrize("confidence", [0.0, 1.0, 0.5, -0.01, 1.01, True])
def test_candidate_confidence_bounds(confidence: float) -> None:
    if 0.0 <= confidence <= 1.0 and not isinstance(confidence, bool):
        assert MemoryCandidate.model_validate(_candidate(confidence=confidence)).confidence == (
            confidence
        )
    else:
        with pytest.raises(ValidationError):
            MemoryCandidate.model_validate(_candidate(confidence=confidence))


@pytest.mark.parametrize(
    "dedupe",
    ["A" * 64, "a" * 63, "a" * 65, "a" * 63 + "-", "z" * 64],
)
def test_dedupe_key_must_be_lowercase_sha256(dedupe: str) -> None:
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(dedupeKey=dedupe))


def test_dedupe_key_pattern_constant_matches_contract() -> None:
    assert DEDUPE_KEY_PATTERN == r"^[0-9a-f]{64}$"


def test_candidate_kind_enum() -> None:
    for kind in ("DECISION", "PREFERENCE", "CONSTRAINT", "LESSON"):
        assert MemoryCandidate.model_validate(_candidate(kind=kind)).kind.value == kind
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(_candidate(kind="FACT"))


def test_snapshot_shape_and_source_refs() -> None:
    snapshot = MemoryJobSnapshot.model_validate(_snapshot())

    assert snapshot.job_id == JOB_ID
    assert snapshot.source_refs() == frozenset({"fact-1", f"memory:{MEMORY_ID}"})
    assert isinstance(snapshot.facts[0], SnapshotFact)
    assert isinstance(snapshot.existing_memories[0], ExistingMemory)
    assert snapshot.existing_memories[0].type is MemoryKind.CONSTRAINT


def test_snapshot_rejects_boolean_numbers_and_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        MemoryJobSnapshot.model_validate(_snapshot(attempt=True))
    with pytest.raises(ValidationError):
        MemoryJobSnapshot.model_validate(_snapshot(extra=1))
    with pytest.raises(ValidationError):
        MemoryJobSnapshot.model_validate(_snapshot(facts=[{"ref": "f", "kind": "k"}]))
    with pytest.raises(ValidationError):
        MemoryJobSnapshot.model_validate(_snapshot(existingMemories=[{"id": str(MEMORY_ID)}]))


def test_snapshot_size_limit_is_enforced() -> None:
    big = "x" * (MAX_SNAPSHOT_CHARS // 2)
    facts = [
        {"ref": "fact-1", "kind": "DECISION", "text": big},
        {"ref": "fact-2", "kind": "DECISION", "text": big},
    ]
    with pytest.raises(ValidationError):
        MemoryJobSnapshot.model_validate(_snapshot(facts=facts))


def test_internal_endpoint_shapes() -> None:
    heartbeat = JobHeartbeatRequest(attempt=2, leaseEpoch=5)
    assert heartbeat.model_dump(by_alias=True, mode="json") == {
        "schemaVersion": 1,
        "attempt": 2,
        "leaseEpoch": 5,
    }
    ack = JobHeartbeatResponse.model_validate(
        {"schemaVersion": 1, "status": "RUNNING", "leaseExpiresAt": "2026-11-12T10:00:00Z"}
    )
    assert ack.status.value == "RUNNING"

    results = JobResultsRequest(
        attempt=1, leaseEpoch=1, candidates=(MemoryCandidate.model_validate(_candidate()),)
    )
    assert len(results.candidates) == 1
    with pytest.raises(ValidationError):
        JobResultsRequest(attempt=1, leaseEpoch=1, candidates=[_candidate()] * 51)

    response = JobResultsResponse.model_validate(
        {
            "schemaVersion": 1,
            "jobId": str(JOB_ID),
            "status": "SUCCEEDED",
            "accepted": 2,
            "duplicates": 1,
        }
    )
    assert response.accepted == 2
    assert response.duplicates == 1


def test_job_status_values_follow_frozen_set() -> None:
    assert {status.value for status in MemoryJobStatus} == {
        "SUCCEEDED",
        "FAILED_RETRYABLE",
        "FAILED",
    }
