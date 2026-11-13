"""Run the frozen decide set against the staging worker with evidence preserved.

This is an evaluation-only harness.  It uses the production WorkerScorer and the
same decide.evaluate/report_document/seal_report functions as ``ai-eval``; the
only wrapper is a 10s pause between case requests and fail-soft recording of
429/timeout/provider failures so the original 32-case denominator is retained.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.eval import decide as decide_eval
from ai_collab_worker.eval.canonical import canonicalize_document

DEFAULT_SET: Final[str] = (
    "/repo/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl"
)
DEFAULT_OUT: Final[str] = "/out"
DEFAULT_WORKER: Final[str] = "http://ai-worker-api:9100"
DEFAULT_EVIDENCE: Final[str] = "LIVE_VERIFIED (staging, real model)"


def _error_class(code: str, status: int | None) -> str:
    if code == "MODEL_RATE_LIMITED" or status == 429:
        return "429"
    if code == "DEADLINE_EXCEEDED" or status == 504:
        return "timeout"
    if code == "MODEL_UNAVAILABLE" or (status is not None and status >= 500):
        return "provider_5xx"
    return "other"


class PacedScorer:
    """Original WorkerScorer + 10s spacing; convert per-case WorkerError to outcome."""

    name: Final[str] = "worker"
    evidence: Final[str] = DEFAULT_EVIDENCE

    def __init__(self, inner: decide_eval.WorkerScorer, interval_ms: int) -> None:
        self._inner = inner
        self._interval_s = interval_ms / 1000.0
        self._last_finished: float | None = None
        self.records: dict[str, dict[str, Any]] = {}
        self.failures: list[dict[str, Any]] = []

    def score(self, case: decide_eval.DecideCase) -> decide_eval.CaseOutcome:
        if self._last_finished is not None:
            wait = self._interval_s - (time.monotonic() - self._last_finished)
            if wait > 0:
                time.sleep(wait)

        started = time.monotonic()
        error_code: str | None = None
        http_status: int | None = None
        try:
            outcome = self._inner.score(case)
        except decide_eval.WorkerError as exc:
            error_code = exc.code
            http_status = exc.status
            outcome = decide_eval.CaseOutcome(
                valid=False,
                decision=None,
                tool_name=None,
                invalid_tool_call=False,
                schema_violation=False,
                reason=f"{exc.code} (HTTP {exc.status})" if exc.status else exc.code,
            )
            self.failures.append(
                {
                    "id": case.id,
                    "code": exc.code,
                    "status": exc.status,
                    "class": _error_class(exc.code, exc.status),
                }
            )
        finally:
            self._last_finished = time.monotonic()

        elapsed_ms = round((self._last_finished - started) * 1000, 1)
        self.records[case.id] = {
            "errorCode": error_code,
            "httpStatus": http_status,
            "failureClass": _error_class(error_code, http_status) if error_code else None,
            "elapsedMs": elapsed_ms,
            "rawOutput": outcome.raw_output,
        }
        return outcome

    def close(self) -> None:
        self._inner.close()


def _write_json(path: Path, payload: Any) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", default=DEFAULT_SET)
    parser.add_argument("--out-dir", default=DEFAULT_OUT)
    parser.add_argument("--worker-url", default=DEFAULT_WORKER)
    parser.add_argument("--interval-ms", type=int, default=10000)
    parser.add_argument("--evidence", default=DEFAULT_EVIDENCE)
    parser.add_argument("--model", default="gemini-3.5-flash-lite")
    parser.add_argument("--subject", default="w4/decide-final @ 87450efecfa3")
    parser.add_argument("--tag", default="v1")
    args = parser.parse_args()

    if not os.environ.get("AI_WORKER_SERVICE_TOKEN", "").strip():
        raise SystemExit("AI_WORKER_SERVICE_TOKEN is not set")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cases = decide_eval.load_decide_set(Path(args.set))
    inner = decide_eval.WorkerScorer(args.worker_url)
    scorer = PacedScorer(inner, args.interval_ms)
    try:
        report = decide_eval.evaluate(cases, scorer)
    finally:
        scorer.close()

    document = decide_eval.seal_report(
        decide_eval.report_document(
            report,
            evidence=args.evidence,
            subject=args.subject,
            model=args.model,
        )
    )
    (out_dir / f"aggregate-decide-live-{args.tag}.json").write_bytes(
        canonicalize_document(document)
    )

    rows: list[dict[str, Any]] = []
    for seq, (case, outcome) in enumerate(report.cases, start=1):
        record = scorer.records.get(case.id, {})
        rows.append(
            {
                "seq": seq,
                "id": case.id,
                "subset": case.subset,
                "expectedDecision": case.expected_decision.value,
                "expectedToolName": case.expected_tool_name,
                "actualDecision": None if outcome.decision is None else outcome.decision.value,
                "actualToolName": outcome.tool_name,
                "valid": outcome.valid,
                "invalidToolCall": outcome.invalid_tool_call,
                "schemaViolation": outcome.schema_violation,
                "agreement": decide_eval.agrees(case, outcome),
                "reason": outcome.reason,
                "errorCode": record.get("errorCode"),
                "httpStatus": record.get("httpStatus"),
                "failureClass": record.get("failureClass"),
                "elapsedMs": record.get("elapsedMs"),
                "rawOutput": record.get("rawOutput"),
            }
        )
    (out_dir / f"decide-cases-live-{args.tag}.jsonl").write_text(
        "".join(
            json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
            for row in rows
        ),
        encoding="utf-8",
    )

    failure_counts: dict[str, int] = {}
    for failure in scorer.failures:
        key = str(failure["class"])
        failure_counts[key] = failure_counts.get(key, 0) + 1
    meta = {
        "set": args.set,
        "workerUrl": args.worker_url,
        "intervalMs": args.interval_ms,
        "attempts": len(rows),
        "goldenCases": document["aggregate"]["bySubset"]["golden"]["cases"],
        "adversarialCases": document["aggregate"]["bySubset"]["adversarial"]["cases"],
        "failureCounts": failure_counts,
        "failures": scorer.failures,
        "evidence": args.evidence,
        "model": args.model,
        "subject": args.subject,
    }
    _write_json(out_dir / f"run-meta-live-{args.tag}.json", meta)
    print(
        "attempts={attempts} golden={golden} adversarial={adversarial} failures={failures}".format(
            attempts=meta["attempts"],
            golden=meta["goldenCases"],
            adversarial=meta["adversarialCases"],
            failures=json.dumps(failure_counts, sort_keys=True),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
