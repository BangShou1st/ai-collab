#!/usr/bin/env python3
"""S3.1 decide：真实 ``POST /v1/agent/decide`` + 冻结评测核（ai_collab_worker.eval.decide）。

- 打分器：``WorkerScorer``（冻结核），外面加 5s 最小间隔与逐 case 失败收敛；
- 单 case 的 ``WorkerError``（超时/传输/429 以外的失败）→ 记为失败样本，不中断整轮；
- 429：记录 + ``sleep 60`` + 最多 2 次重试（即最多 3 次尝试）；
- 指标由 ``evaluate`` / ``report_document`` / ``seal_report`` 计算，不新造口径。
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

from ai_collab_worker.eval.decide import (
    CaseOutcome,
    WorkerError,
    WorkerScorer,
    agrees,
    canonical_report_bytes,
    evaluate,
    load_decide_set,
    report_document,
)

FIXTURE = Path("/repo/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl")
OUT = Path("/out")
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)

MIN_INTERVAL_SECONDS = 5.0
MAX_429_RETRIES = 2
RATE_LIMIT_SLEEP_SECONDS = 60.0
WORKER_URL = "http://ai-worker-api:9100"
TOKEN_ENV = "AI_WORKER_SERVICE_TOKEN"


class Pacer:
    """保证两次外部调用之间至少间隔 ``interval`` 秒（从上次结束算起）。"""

    def __init__(self, interval: float) -> None:
        self.interval = interval
        self._next = 0.0

    def before(self) -> None:
        delay = self._next - time.monotonic()
        if delay > 0:
            time.sleep(delay)

    def after(self) -> None:
        self._next = time.monotonic() + self.interval


class PacedWorkerScorer:
    """冻结 ``WorkerScorer`` 的节流 + 失败收敛包装（不改变 CaseOutcome 语义）。"""

    name = "worker"
    evidence = "TESTED (worker)"

    def __init__(self) -> None:
        self._inner = WorkerScorer(worker_url=WORKER_URL, token_env=TOKEN_ENV)
        self._pacer = Pacer(MIN_INTERVAL_SECONDS)
        self.records: list[dict[str, object]] = []
        self.rate_limited_total = 0

    def score(self, case) -> CaseOutcome:  # noqa: ANN001 - DecideCase
        attempts: list[dict[str, object]] = []
        started = time.time()
        outcome: CaseOutcome | None = None
        failure_code: str | None = None

        for attempt_no in range(1, MAX_429_RETRIES + 2):
            self._pacer.before()
            t0 = time.monotonic()
            try:
                outcome = self._inner.score(case)
                self._pacer.after()
                attempts.append(
                    {
                        "attempt": attempt_no,
                        "result": "ok",
                        "elapsedMs": int((time.monotonic() - t0) * 1000),
                    }
                )
                break
            except WorkerError as exc:
                self._pacer.after()
                is_rate_limited = exc.status == 429 or str(exc.code) == "MODEL_RATE_LIMITED"
                if is_rate_limited:
                    self.rate_limited_total += 1
                attempts.append(
                    {
                        "attempt": attempt_no,
                        "result": "error",
                        "code": str(exc.code),
                        "httpStatus": exc.status,
                        "error": exc.detail,
                        "rateLimited": is_rate_limited,
                        "elapsedMs": int((time.monotonic() - t0) * 1000),
                    }
                )
                if is_rate_limited and attempt_no <= MAX_429_RETRIES:
                    time.sleep(RATE_LIMIT_SLEEP_SECONDS)
                    continue
                failure_code = str(exc.code)
                outcome = CaseOutcome(
                    valid=False,
                    decision=None,
                    tool_name=None,
                    invalid_tool_call=False,
                    schema_violation=False,
                    reason=f"EVAL_ERROR:{exc.code}",
                )
                break

        if outcome is None:  # 理论上不可达
            failure_code = "EVAL_ERROR:UNKNOWN"
            outcome = CaseOutcome(
                valid=False,
                decision=None,
                tool_name=None,
                invalid_tool_call=False,
                schema_violation=False,
                reason="EVAL_ERROR:UNKNOWN",
            )

        self.records.append(
            {
                "id": case.id,
                "subset": case.subset,
                "expected": {
                    "decision": case.expected_decision.value,
                    "toolName": case.expected_tool_name,
                },
                "attempts": attempts,
                "rateLimited": sum(1 for item in attempts if item.get("rateLimited")),
                "finalErrorCode": failure_code,
                "actual": {
                    "valid": outcome.valid,
                    "decision": None if outcome.decision is None else outcome.decision.value,
                    "toolName": outcome.tool_name,
                    "invalidToolCall": outcome.invalid_tool_call,
                    "schemaViolation": outcome.schema_violation,
                    "reason": outcome.reason,
                },
                "agreement": agrees(case, outcome),
                "elapsedMs": int((time.time() - started) * 1000),
            }
        )
        return outcome

    def close(self) -> None:
        self._inner.close()


def main() -> int:
    if not os.environ.get(TOKEN_ENV, "").strip():
        print(f"FATAL: {TOKEN_ENV} missing", file=sys.stderr)
        return 2

    cases = load_decide_set(FIXTURE)
    golden = sum(1 for case in cases if case.subset == "golden")
    adversarial = sum(1 for case in cases if case.subset == "adversarial")
    print(f"decide cases={len(cases)} golden={golden} adversarial={adversarial}")

    scorer = PacedWorkerScorer()
    started = time.time()
    try:
        report = evaluate(cases, scorer)
    finally:
        scorer.close()

    document = report_document(report)
    payload = canonical_report_bytes(document)
    (OUT / "aggregate-decide.json").write_bytes(payload)

    with (RAW / "decide-cases.jsonl").open("w", encoding="utf-8") as handle:
        for record in scorer.records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    sealed = json.loads(payload)
    aggregate = sealed["aggregate"]
    summary = {
        "scorer": sealed["scorer"],
        "evidence": sealed["evidence"],
        "qualityClaim": sealed["qualityClaim"],
        "sha256": sealed["sha256"],
        "elapsedSeconds": round(time.time() - started, 1),
        "rateLimitedTotal": scorer.rate_limited_total,
        "aggregate": aggregate,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
