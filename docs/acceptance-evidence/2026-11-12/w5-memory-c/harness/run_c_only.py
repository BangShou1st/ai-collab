#!/usr/bin/env python3
"""Run only the real C arm after a transient provider failure in attempt 1."""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import live_memory_eval as live  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--base-measurement", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://app:8080")
    parser.add_argument("--model-base-url", default="http://app:8080")
    parser.add_argument("--interval-seconds", type=float, default=10.0)
    args = parser.parse_args()

    fixture = live._read_json(args.fixture)
    base = live._read_json(args.base_measurement)
    arm_a = base["arms"][live.ARM_A]
    arm_b = base["arms"][live.ARM_B]
    fixture_cases = live._fixture_cases(fixture)
    sessions = live._fixture_sessions(fixture)
    expected_facts = list(
        dict.fromkeys(fact for case in fixture_cases for fact in case["expectedAnswerFacts"])
    )
    description = "项目决策事实：" + "；".join(expected_facts)

    jwt = os.getenv("W5_USER_JWT", "")
    if not jwt:
        jwt = Path(os.environ["W5_USER_JWT_FILE"]).read_text(encoding="utf-8").strip()
    client = live.Client(args.base_url, jwt, args.model_base_url)
    raw_dir = args.output_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    started_at = live._now()

    c_project = live._create_project(
        client, f"W5 C DREAMING retry {uuid.uuid4().hex[:8]}", description
    )
    cross_project = live._create_project(
        client, f"W5 C cross-retry {uuid.uuid4().hex[:8]}", "跨项目泄露控制"
    )
    live._write_json(raw_dir / "projects.json", {"c": c_project, "cross": cross_project})
    cross_memory = live._create_memory(
        client,
        cross_project["id"],
        memory_type="CONSTRAINT",
        title="跨项目控制记忆",
        content="该项目允许 Python 持有 Provider 密钥。",
    )

    job = live._data(
        client.api("POST", f"/api/v1/projects/{c_project['id']}/agent/memory-jobs")
    )
    live._write_json(raw_dir / "job-create.json", job)
    job_status = live._wait_for_job(client, c_project["id"], job["id"], raw_dir)
    live._write_json(raw_dir / "job-status.json", job_status)

    candidates: list[dict] = []
    approvals: list[dict] = []
    memories_after: list[dict] = []
    if job_status.get("status") == "SUCCEEDED":
        candidates, approvals, memories_after = live._approve_candidates(
            client, c_project["id"], raw_dir
        )

    before_raw = live._read_json(raw_dir / "memories-before-approval.json") if (
        raw_dir / "memories-before-approval.json"
    ).exists() else []
    auto_published = sum(1 for memory in before_raw if memory.get("sourceType") == "AI_JOB_RESULT")
    controls = live._create_c_controls(client, c_project["id"], approvals, raw_dir)
    c_memories = [_memory_shape(memory, c_project["id"]) for memory in approvals]
    c_memories += list(controls.values())
    c_memories.append(_memory_shape(cross_memory, cross_project["id"]))
    c_cases = live._build_c_cases(fixture_cases, approvals, controls, c_project["id"])
    live._write_json(raw_dir / "c-cases.json", c_cases)
    arm_c = live._run_arm_tasks(
        arm=live.ARM_C,
        cases=c_cases,
        sessions=sessions,
        memories=c_memories,
        client=client,
        raw_dir=raw_dir,
        interval_seconds=args.interval_seconds,
    )

    approval_rate = len(approvals) / len(candidates) if candidates else 0.0
    candidate_all_approved = bool(candidates) and len(approvals) == len(candidates)
    checks = {
        "jobSucceeded": job_status.get("status") == "SUCCEEDED",
        "candidateCountPositive": len(candidates) > 0,
        "allCandidatesApproved": candidate_all_approved,
        "humanApprovalRateEquals1": approval_rate == 1.0,
        "autoPublishedEquals0": auto_published == 0,
        "bRevocationLeaksZero": arm_b["revocationLeaks"] == 0,
        "bCrossProjectLeaksZero": arm_b["crossProjectLeaks"] == 0,
        "bConflictMisuseZero": arm_b["conflictMisuseRate"] == 0,
        "bCitationValidityOne": arm_b["citationValidityRate"] == 1.0,
        "cRevocationLeaksZero": arm_c["revocationLeaks"] == 0,
        "cCrossProjectLeaksZero": arm_c["crossProjectLeaks"] == 0,
        "cConflictMisuseZero": arm_c["conflictMisuseRate"] == 0,
        "cCitationValidityOne": arm_c["citationValidityRate"] == 1.0,
        "cNotBelowB": arm_c["taskAccuracy"] >= arm_b["taskAccuracy"],
        "cNotBelowA": arm_c["taskAccuracy"] >= arm_a["taskAccuracy"],
    }
    incomplete = not all(
        checks[key]
        for key in (
            "jobSucceeded",
            "candidateCountPositive",
            "allCandidatesApproved",
            "humanApprovalRateEquals1",
        )
    )
    gate = "NOT_EVALUATED" if incomplete else ("PASS" if all(checks.values()) else "FAIL")
    measurement = {
        "schemaVersion": 1,
        "taskPromptVersion": live.TASK_PROMPT_VERSION,
        "judgeVersion": live.JUDGE_VERSION,
        "evidence": "LIVE_VERIFIED (staging, real Job, real model, explicit approval)",
        "startedAt": started_at,
        "finishedAt": live._now(),
        "gate": {"status": gate, "checks": checks},
        "job": {
            "id": job["id"],
            "projectId": c_project["id"],
            "status": job_status.get("status"),
            "attemptCount": job_status.get("attemptCount"),
            "leaseEpoch": job_status.get("leaseEpoch"),
        },
        "approval": {
            "candidates": len(candidates),
            "approved": len(approvals),
            "humanApprovalRate": approval_rate,
            "autoPublished": auto_published,
            "publishedAfterApproval": sum(
                1 for memory in memories_after if memory.get("sourceType") == "AI_JOB_RESULT"
            ) if memories_after else 0,
        },
        "arms": {
            live.ARM_A: arm_a,
            live.ARM_B: arm_b,
            live.ARM_C: {
                **arm_c,
                "definition": "approved real Job memories + frozen governed selector",
            },
        },
        "limits": base["limits"],
        "baseMeasurement": str(args.base_measurement),
    }
    live._write_json(raw_dir / "measurement.json", measurement)
    print(
        json.dumps(
            {
                "gate": gate,
                "job": measurement["job"],
                "approval": measurement["approval"],
                "taskAccuracy": {
                    live.ARM_A: arm_a["taskAccuracy"],
                    live.ARM_B: arm_b["taskAccuracy"],
                    live.ARM_C: arm_c["taskAccuracy"],
                },
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if gate == "PASS" else 1


def _memory_shape(memory: dict, project_id: str) -> dict:
    return live._memory_shape(memory, project_id)


if __name__ == "__main__":
    raise SystemExit(main())
