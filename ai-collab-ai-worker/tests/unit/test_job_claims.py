"""job token claims 校验：缺失/形状非法/与 body 不一致 → 403 固定短语。"""

from __future__ import annotations

import uuid
from typing import Any

import pytest

from ai_collab_worker.api.jobs import REASON_CLAIMS_MISMATCH, validate_job_claims
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.memory_job import MemoryJobRequest

JOB_ID = "00000000-0000-4000-8000-000000000001"
BODY = MemoryJobRequest.model_validate(
    {"schemaVersion": 1, "jobId": JOB_ID, "type": "MEMORY_EXTRACTION", "deadlineMs": 5000}
)


def _claims(**overrides: Any) -> dict[str, object]:
    claims: dict[str, object] = {
        "jobId": JOB_ID,
        "attempt": 2,
        "leaseEpoch": 7,
        "snapshotHash": "sha256:abc",
        "projectRef": "project-1",
    }
    claims.update(overrides)
    return claims


def _assert_forbidden(claims: dict[str, object]) -> AppError:
    with pytest.raises(AppError) as excinfo:
        validate_job_claims(claims, BODY)
    error = excinfo.value
    assert error.status_code == 403
    assert error.code.value == "INVALID_REQUEST"
    assert error.retryable is False
    assert error.details == {"reason": REASON_CLAIMS_MISMATCH}
    return error


def test_valid_claims_are_bound_to_body() -> None:
    claims = validate_job_claims(_claims(), BODY)

    assert claims.job_id == JOB_ID
    assert claims.attempt == 2
    assert claims.lease_epoch == 7
    assert claims.snapshot_hash == "sha256:abc"
    assert claims.project_ref == "project-1"


def test_job_id_comparison_is_case_insensitive() -> None:
    assert validate_job_claims(_claims(jobId=JOB_ID.upper()), BODY).job_id == JOB_ID.upper()


@pytest.mark.parametrize("name", ["jobId", "attempt", "leaseEpoch", "snapshotHash", "projectRef"])
def test_each_claim_is_required(name: str) -> None:
    claims = _claims()
    del claims[name]
    error = _assert_forbidden(claims)
    assert name not in str(error.message)


@pytest.mark.parametrize(
    "overrides",
    [
        {"jobId": "00000000-0000-4000-8000-000000000002"},
        {"jobId": ""},
        {"jobId": 123},
        {"attempt": 0},
        {"attempt": -1},
        {"attempt": True},
        {"attempt": "2"},
        {"leaseEpoch": -1},
        {"leaseEpoch": True},
        {"leaseEpoch": "7"},
        {"snapshotHash": ""},
        {"snapshotHash": 7},
        {"projectRef": "   "},
        {"projectRef": None},
    ],
)
def test_invalid_claim_shapes_are_forbidden(overrides: dict[str, object]) -> None:
    _assert_forbidden(_claims(**overrides))


def test_mismatch_message_does_not_echo_claim_values() -> None:
    secret = "job-secret-claim-value"
    error = _assert_forbidden(_claims(jobId=secret))
    assert secret not in error.message


def test_body_job_id_is_a_uuid() -> None:
    assert isinstance(BODY.job_id, uuid.UUID)
