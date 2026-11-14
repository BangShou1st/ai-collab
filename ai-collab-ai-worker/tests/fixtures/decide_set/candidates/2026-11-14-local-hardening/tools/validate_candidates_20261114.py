"""Validate pending-review decide candidates without treating them as frozen."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Final

SET_DIR = Path(__file__).resolve().parent.parent
DECIDE_SET_DIR = SET_DIR.parents[1]
WORKER_ROOT = DECIDE_SET_DIR.parents[2]
REPO_ROOT = WORKER_ROOT.parent

sys.path.insert(0, str(WORKER_ROOT / "src"))

from ai_collab_worker.cognition.decide.parser import (  # noqa: E402
    REASON_INVALID_TOOL_CALL,
    REASON_MISSING_DECISION,
    REASON_MISSING_TOOL_CALL,
    REASON_NOT_JSON,
    REASON_UNEXPECTED_TOOL_CALL,
    REASON_UNKNOWN_DECISION,
    REASON_UNKNOWN_TOOL,
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.contract.agent_decide import (  # noqa: E402
    AgentDecideRequest,
    DecideDecision,
    ToolRisk,
)

REVIEW_PENDING: Final[str] = "PENDING_REVIEW"
GOLDEN_FILE: Final[str] = "golden.v1.jsonl"
ADVERSARIAL_FILE: Final[str] = "adversarial.v1.jsonl"
MANIFEST_FILE: Final[str] = "manifest.json"

HISTORICAL_HASHES: Final[dict[str, str]] = {
    "decide_baseline.v1.jsonl": "144439dcb0998cceef549c13bb87720ebe0d5fa16072649f2738444a00a91413",
    "decide_baseline.v2.jsonl": "6b74c9325964d9a0c5a5f18e995da73483683fbb89b8ff1f683972909998fd2b",
    "decide_baseline.v3.jsonl": "68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a",
    "decide_real_gemini31.v1.jsonl": (
        "ec35ac978446b898b87c6e1566efe9799f4132c76126845e8292e6ed66fec0ba"
    ),
}

ALLOWED_SOURCE_KINDS: Final[frozenset[str]] = frozenset(
    {
        "FROZEN_V1_VARIANT",
        "LIVE_ROUND5_REFERENCE",
        "SYNTHETIC_COVERAGE_GAP",
        "DESIGN_REFERENCE",
    }
)
ALLOWED_REJECTION_REASONS: Final[frozenset[str]] = frozenset(
    {
        REASON_UNKNOWN_TOOL,
        REASON_UNKNOWN_DECISION,
        REASON_MISSING_DECISION,
        REASON_MISSING_TOOL_CALL,
        REASON_INVALID_TOOL_CALL,
        REASON_UNEXPECTED_TOOL_CALL,
        REASON_NOT_JSON,
    }
)

GOLDEN_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
        "reviewStatus",
        "suggestedSubset",
        "suggestedLabel",
        "request",
        "source",
        "coverage",
        "review",
    }
)
ADVERSARIAL_FIELDS: Final[frozenset[str]] = GOLDEN_FIELDS | {"probe"}
REQUEST_FIELDS: Final[frozenset[str]] = frozenset(
    {"scenario", "goal", "locale", "context", "tools", "policy", "budget"}
)


class CandidateError(ValueError):
    """Candidate corpus is not reviewable; details stay in operator evidence."""


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for position, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line.strip():
            raise CandidateError(f"{path.name}:{position}: blank line")
        try:
            record = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise CandidateError(f"{path.name}:{position}: invalid JSON: {exc.msg}") from exc
        if not isinstance(record, dict):
            raise CandidateError(f"{path.name}:{position}: JSONL line must be an object")
        records.append(record)
    if not records:
        raise CandidateError(f"{path.name}: empty dataset")
    return records


def _require_keys(record: dict[str, Any], expected: frozenset[str], where: str) -> None:
    actual = frozenset(record)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing:
        raise CandidateError(f"{where}: missing fields: {', '.join(missing)}")
    if unknown:
        raise CandidateError(f"{where}: unknown fields: {', '.join(unknown)}")


def _validate_request(raw_request: object, where: str) -> AgentDecideRequest:
    if not isinstance(raw_request, dict):
        raise CandidateError(f"{where}: request must be an object")
    actual = frozenset(raw_request)
    missing = sorted(REQUEST_FIELDS - actual)
    unknown = sorted(actual - REQUEST_FIELDS)
    if missing:
        raise CandidateError(f"{where}: request missing fields: {', '.join(missing)}")
    if unknown:
        raise CandidateError(f"{where}: request unknown fields: {', '.join(unknown)}")
    payload = {
        "schemaVersion": 1,
        "runId": "candidate-review-placeholder",
        "attempt": 1,
        "turnNo": 0,
        "snapshotHash": "sha256:candidate-review",
        **raw_request,
    }
    try:
        return AgentDecideRequest.model_validate(payload)
    except Exception as exc:  # pydantic error details are stable enough for evidence
        raise CandidateError(f"{where}: request contract violation: {exc}") from exc


def _validate_source(raw_source: object, where: str) -> None:
    if not isinstance(raw_source, dict):
        raise CandidateError(f"{where}: source must be an object")
    if frozenset(raw_source) != frozenset({"kind", "references", "rationale"}):
        raise CandidateError(f"{where}: source fields are not exact")
    kind = raw_source.get("kind")
    if kind not in ALLOWED_SOURCE_KINDS:
        raise CandidateError(f"{where}: unknown source kind: {kind!r}")
    refs = raw_source.get("references")
    if not isinstance(refs, list) or not refs:
        raise CandidateError(f"{where}: source.references must be a non-empty list")
    for reference in refs:
        if not isinstance(reference, str) or not reference.strip():
            raise CandidateError(f"{where}: source reference must be a non-empty string")
        file_part = reference.split("#", 1)[0]
        if not (REPO_ROOT / file_part).exists() and not (WORKER_ROOT / file_part).exists():
            raise CandidateError(f"{where}: source reference does not exist: {file_part}")
    rationale = raw_source.get("rationale")
    if not isinstance(rationale, str) or not rationale.strip():
        raise CandidateError(f"{where}: source.rationale must be non-empty")


def _validate_coverage(raw_coverage: object, where: str) -> list[str]:
    if not isinstance(raw_coverage, dict) or frozenset(raw_coverage) != frozenset({"tags", "note"}):
        raise CandidateError(f"{where}: coverage fields are not exact")
    tags = raw_coverage.get("tags")
    note = raw_coverage.get("note")
    if not isinstance(tags, list) or len(tags) < 2 or not all(isinstance(tag, str) for tag in tags):
        raise CandidateError(f"{where}: coverage.tags must contain at least two strings")
    if not isinstance(note, str) or not note.strip():
        raise CandidateError(f"{where}: coverage.note must be non-empty")
    return tags


def _validate_review(raw_review: object, where: str) -> None:
    if not isinstance(raw_review, dict) or frozenset(raw_review) != frozenset(
        {"reviewer", "reviewedAt", "notes"}
    ):
        raise CandidateError(f"{where}: review fields are not exact")
    if any(value is not None for value in raw_review.values()):
        raise CandidateError(f"{where}: pending candidates must have empty review fields")


def _validate_golden_label(
    candidate: dict[str, Any], request: AgentDecideRequest, where: str
) -> DecideDecision:
    label = candidate["suggestedLabel"]
    if not isinstance(label, dict) or label.get("kind") != "DECISION":
        raise CandidateError(f"{where}: golden suggestedLabel.kind must be DECISION")
    expected_fields = {"kind", "decision"}
    if "toolName" in label:
        expected_fields.add("toolName")
    if frozenset(label) != expected_fields:
        raise CandidateError(f"{where}: golden suggestedLabel fields are not exact")
    try:
        decision = DecideDecision(label["decision"])
    except (KeyError, ValueError) as exc:
        raise CandidateError(f"{where}: invalid suggested decision") from exc
    tools = {tool.name: tool for tool in request.tools}
    tool_name = label.get("toolName")
    if decision is DecideDecision.CALL_TOOL:
        if not isinstance(tool_name, str) or tool_name not in tools:
            raise CandidateError(f"{where}: CALL_TOOL must name an offered tool")
        if tools[tool_name].risk is ToolRisk.WRITE and not request.policy.allow_write:
            raise CandidateError(f"{where}: write-tool suggestion requires allowWrite=true")
    elif tool_name is not None:
        raise CandidateError(f"{where}: non-CALL_TOOL must not provide toolName")
    return decision


def _validate_adversarial_label(
    candidate: dict[str, Any], request: AgentDecideRequest, where: str
) -> str:
    label = candidate["suggestedLabel"]
    if not isinstance(label, dict) or frozenset(label) != frozenset({"kind", "rejectReason"}):
        raise CandidateError(f"{where}: adversarial suggestedLabel fields are not exact")
    if label.get("kind") != "REJECTION":
        raise CandidateError(f"{where}: adversarial suggestedLabel.kind must be REJECTION")
    reason = label.get("rejectReason")
    if reason not in ALLOWED_REJECTION_REASONS:
        raise CandidateError(f"{where}: unknown reject reason: {reason!r}")
    probe = candidate["probe"]
    if not isinstance(probe, dict) or frozenset(probe) != frozenset(
        {"candidateOutput", "expectedRejectReason"}
    ):
        raise CandidateError(f"{where}: probe fields are not exact")
    if probe.get("expectedRejectReason") != reason:
        raise CandidateError(f"{where}: probe reason differs from suggestedLabel")
    candidate_output = probe.get("candidateOutput")
    if not isinstance(candidate_output, str):
        raise CandidateError(f"{where}: probe.candidateOutput must be a string")
    try:
        parse_decision(candidate_output, request.tools)
    except DecisionValidationError as exc:
        if exc.reason != reason:
            raise CandidateError(
                f"{where}: probe rejected for {exc.reason!r}, expected {reason!r}"
            ) from exc
    else:
        raise CandidateError(f"{where}: adversarial probe unexpectedly parsed as valid")
    return reason


def _validate_candidate(
    candidate: dict[str, Any],
    *,
    expected_subset: str,
    position: int,
    seen_ids: set[str],
) -> tuple[dict[str, Any], list[str]]:
    fields = GOLDEN_FIELDS if expected_subset == "golden" else ADVERSARIAL_FIELDS
    where = f"{expected_subset}[{position}]"
    _require_keys(candidate, fields, where)
    case_id = candidate["id"]
    if not isinstance(case_id, str) or not case_id.startswith("cand-20261114-"):
        raise CandidateError(f"{where}: id must use cand-20261114- prefix")
    if case_id in seen_ids:
        raise CandidateError(f"{where}: duplicate id {case_id}")
    seen_ids.add(case_id)
    where = case_id
    if candidate["reviewStatus"] != REVIEW_PENDING:
        raise CandidateError(f"{where}: reviewStatus must be {REVIEW_PENDING}")
    if candidate["suggestedSubset"] != expected_subset:
        raise CandidateError(f"{where}: suggestedSubset must be {expected_subset}")
    if "expected" in candidate or "modelOutput" in candidate:
        raise CandidateError(f"{where}: pending candidate must not carry frozen fields")
    request = _validate_request(candidate["request"], where)
    _validate_source(candidate["source"], where)
    tags = _validate_coverage(candidate["coverage"], where)
    _validate_review(candidate["review"], where)
    if expected_subset == "golden":
        decision = _validate_golden_label(candidate, request, where)
        tags = [*tags, f"computed:decision:{decision.value}"]
    else:
        reason = _validate_adversarial_label(candidate, request, where)
        tags = [*tags, f"computed:rejection:{reason}"]
    return candidate, tags


def _raw_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_dataset() -> dict[str, Any]:
    golden = _load_jsonl(SET_DIR / GOLDEN_FILE)
    adversarial = _load_jsonl(SET_DIR / ADVERSARIAL_FILE)
    total = len(golden) + len(adversarial)
    if not 50 <= total <= 100:
        raise CandidateError(f"candidate total must be 50..100, got {total}")
    seen_ids: set[str] = set()
    all_tags: list[str] = []
    decision_counts: dict[str, int] = {}
    rejection_counts: dict[str, int] = {}
    for position, candidate in enumerate(golden, start=1):
        _, tags = _validate_candidate(
            candidate,
            expected_subset="golden",
            position=position,
            seen_ids=seen_ids,
        )
        all_tags.extend(tags)
        decision = candidate["suggestedLabel"]["decision"]
        decision_counts[decision] = decision_counts.get(decision, 0) + 1
    for position, candidate in enumerate(adversarial, start=1):
        _, tags = _validate_candidate(
            candidate,
            expected_subset="adversarial",
            position=position,
            seen_ids=seen_ids,
        )
        all_tags.extend(tags)
        reason = candidate["suggestedLabel"]["rejectReason"]
        rejection_counts[reason] = rejection_counts.get(reason, 0) + 1

    required_tags = {
        "decision:CALL_TOOL",
        "decision:FINAL",
        "decision:ASK_USER",
        "decision:NO_OP",
        "tool:search_project_knowledge",
        "tool:get_document_metadata",
        "tool:get_project_snapshot",
        "tool:create_task",
        "context:empty",
        "boundary:context-facts-20",
        "boundary:context-messages-20",
        "boundary:context-observations-20",
        "security:prompt-injection",
        "security:write-tool-present-policy-false",
        "rejection:unknown-tool",
        "rejection:invalid-decision",
        "rejection:missing-decision",
        "rejection:missing-tool-call",
        "rejection:unexpected-tool-call",
        "rejection:invalid-tool-call",
        "rejection:invalid-json",
    }
    missing_tags = sorted(required_tags - set(all_tags))
    if missing_tags:
        raise CandidateError(f"missing required coverage tags: {', '.join(missing_tags)}")

    locales = {case["request"]["locale"] for case in golden}
    if len(locales) < 8:
        raise CandidateError(f"need at least 8 locales, got {len(locales)}: {sorted(locales)}")
    scenarios = {case["request"]["scenario"]["code"] for case in golden}
    if len(scenarios) < 8:
        raise CandidateError(f"need at least 8 scenarios, got {len(scenarios)}")

    historical_hashes = {name: _raw_sha256(DECIDE_SET_DIR / name) for name in HISTORICAL_HASHES}
    if historical_hashes != HISTORICAL_HASHES:
        raise CandidateError(f"historical fixture hashes changed: {historical_hashes}")

    manifest = json.loads((SET_DIR / MANIFEST_FILE).read_text(encoding="utf-8"))
    expected_counts = {"total": total, "golden": len(golden), "adversarial": len(adversarial)}
    if manifest.get("counts") != expected_counts:
        raise CandidateError(
            f"manifest counts mismatch: {manifest.get('counts')} != {expected_counts}"
        )
    if manifest.get("status") != REVIEW_PENDING:
        raise CandidateError("manifest status must remain PENDING_REVIEW")
    if manifest.get("decisionCounts") != decision_counts:
        raise CandidateError("manifest decisionCounts mismatch")
    if manifest.get("rejectionCounts") != rejection_counts:
        raise CandidateError("manifest rejectionCounts mismatch")
    if manifest.get("historicalFileHashes") != historical_hashes:
        raise CandidateError("manifest historical hashes mismatch")
    review_policy = manifest.get("reviewPolicy")
    if review_policy != {
        "defaultStatus": REVIEW_PENDING,
        "reviewerRequired": True,
        "freezeRequiresSeparateApproval": True,
        "qualityGateEligible": False,
    }:
        raise CandidateError("manifest reviewPolicy is not pending-review only")

    return {
        "status": REVIEW_PENDING,
        "counts": expected_counts,
        "decisionCounts": dict(sorted(decision_counts.items())),
        "rejectionCounts": dict(sorted(rejection_counts.items())),
        "locales": sorted(locales),
        "scenarios": sorted(scenarios),
        "historicalFileHashes": historical_hashes,
        "requiredTagCount": len(required_tags),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        summary = validate_dataset()
    except CandidateError as exc:
        print(f"invalid candidate dataset: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(
            "status={status} total={total} golden={golden} adversarial={adversarial}".format(
                **summary["counts"], status=summary["status"]
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
