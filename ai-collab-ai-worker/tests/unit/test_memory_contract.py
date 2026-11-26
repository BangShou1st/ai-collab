"""记忆抽取任务的数据形状：候选（result payload）与快照（P1 契约 §4.3）。

``POST /v1/jobs`` 请求/响应与旧 jobs 心跳/结果形状已随 P1 删除（契约 §7），对应测试一并删除。
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.memory_job import (
    DEDUPE_KEY_PATTERN,
    MAX_CANDIDATE_CONTENT_CHARS,
    MAX_CANDIDATE_TITLE_CHARS,
    MAX_SNAPSHOT_CHARS,
    ExistingMemory,
    MemoryCandidate,
    MemoryJobSnapshot,
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
