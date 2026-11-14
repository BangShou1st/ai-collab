#!/usr/bin/env python3
"""S1.5 限速版 W3 采样器。

与 /data/home/w3m/measure.py 的唯一差别：
  1) 增加 --min-interval-ms，在两次 worker 调用之间强制休眠（Gemini 免费档 15 RPM）；
  2) 增加 --max-attempts，用于 20 次预飞这类小样本。
其余（fixture 加载、pad_candidates、total/model 拆分、P50/P95/P99 最近秩法、confidence）
全部直接复用 ai_collab_worker.eval.rerank 的冻结实现，不新造指标、不改门禁阈值。
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from ai_collab_worker.eval.rerank import (
    WorkerError,
    WorkerScorer,
    load_rerank_set,
    pad_candidates,
    p50,
    p95,
    p99,
    summarize_latency,
)

FIXTURE = "/repo/ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tier", type=int, required=True)
    parser.add_argument("--runs", type=int, default=4)
    parser.add_argument("--deadline-ms", type=int, default=30000)
    parser.add_argument("--worker-url", default="http://ai-worker-api:9100")
    parser.add_argument("--out", required=True)
    parser.add_argument("--tag", default="")
    parser.add_argument("--min-interval-ms", type=int, default=0)
    parser.add_argument("--max-attempts", type=int, default=0)
    args = parser.parse_args()

    cases = load_rerank_set(Path(FIXTURE))
    scorer = WorkerScorer(args.worker_url, deadline_ms=args.deadline_ms)

    pairs: list[tuple[float, int | None]] = []
    failures: list[dict] = []
    raw_samples: list[dict] = []
    attempted = 0
    interval = max(0, args.min_interval_ms) / 1000.0
    last_start = 0.0
    started_all = time.perf_counter()
    try:
        for case in sorted(cases, key=lambda c: c.id):
            padded = pad_candidates(case, args.tier)
            for index in range(args.runs):
                if args.max_attempts and attempted >= args.max_attempts:
                    break
                if interval:
                    wait = interval - (time.perf_counter() - last_start)
                    if wait > 0:
                        time.sleep(wait)
                attempted += 1
                last_start = time.perf_counter()
                started = time.perf_counter_ns()
                try:
                    scorer.rank(padded)
                except WorkerError as exc:
                    elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
                    failures.append(
                        {
                            "case": case.id,
                            "run": index + 1,
                            "elapsedMs": round(elapsed, 1),
                            "code": exc.code,
                            "status": exc.status,
                        }
                    )
                    continue
                except Exception as exc:  # noqa: BLE001 - 传输层异常同样计失败
                    elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
                    failures.append(
                        {
                            "case": case.id,
                            "run": index + 1,
                            "elapsedMs": round(elapsed, 1),
                            "code": f"{type(exc).__name__}",
                            "status": None,
                        }
                    )
                    continue
                elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
                model_ms = scorer.model_latency_ms()
                pairs.append((elapsed, model_ms))
                raw_samples.append(
                    {
                        "case": case.id,
                        "run": index + 1,
                        "totalMs": round(elapsed, 1),
                        "modelLatencyMs": model_ms,
                        "overheadMs": round(max(0.0, elapsed - model_ms), 1) if model_ms is not None else None,
                    }
                )
            if args.max_attempts and attempted >= args.max_attempts:
                break
    finally:
        scorer.close()

    totals = [t for t, _ in pairs]
    models = [m for _, m in pairs if m is not None]
    overheads = [max(0.0, t - m) for t, m in pairs if m is not None]
    latency = summarize_latency(measured=True, runs=args.runs, warmup=0, pairs=pairs)
    summary = {
        "tag": args.tag,
        "tier": args.tier,
        "deadlineMs": args.deadline_ms,
        "runsPerCase": args.runs,
        "minIntervalMs": args.min_interval_ms,
        "cases": len(cases),
        "attempted": attempted,
        "samples": len(pairs),
        "failures": len(failures),
        "failureRate": round(len(failures) / attempted, 6) if attempted else None,
        "modelKnownSamples": len(models),
        "modelLatencyKnownRatio": round(len(models) / attempted, 6) if attempted else None,
        "429Count": sum(1 for f in failures if f["code"] == "MODEL_RATE_LIMITED"),
        "deadlineExceededCount": sum(1 for f in failures if f["code"] == "DEADLINE_EXCEEDED"),
        "errorBreakdown": {},
        "overheadP50Ms": p50(overheads) if overheads else None,
        "overheadP95Ms": p95(overheads) if overheads else None,
        "overheadP99Ms": p99(overheads) if overheads else None,
        "modelP50Ms": p50(models) if models else None,
        "modelP95Ms": p95(models) if models else None,
        "modelP99Ms": p99(models) if models else None,
        "totalP50Ms": p50(totals) if totals else None,
        "totalP95Ms": p95(totals) if totals else None,
        "totalP99Ms": p99(totals) if totals else None,
        "confidence": latency.confidence,
        "wallSeconds": round(time.perf_counter() - started_all, 1),
        "rawSamples": raw_samples,
        "failuresDetail": failures[:200],
    }
    for failure in failures:
        code = str(failure["code"])
        summary["errorBreakdown"][code] = summary["errorBreakdown"].get(code, 0) + 1
    Path(args.out).write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("failuresDetail", "rawSamples")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
