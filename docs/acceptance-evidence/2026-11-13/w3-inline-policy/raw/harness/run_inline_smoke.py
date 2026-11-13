#!/usr/bin/env python3
"""Run an 8-candidate / 3s local llama.cpp rerank smoke."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
import urllib.request
from pathlib import Path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _request(server: str, query: str, documents: list[str], timeout: float) -> tuple[float, dict]:
    body = json.dumps({"query": query, "documents": documents}, ensure_ascii=False).encode()
    request = urllib.request.Request(
        f"{server.rstrip('/')}/rerank",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read())
    elapsed_ms = (time.perf_counter() - started) * 1000
    return elapsed_ms, payload


def _scores(payload: dict, expected: int) -> dict[int, float]:
    results = payload.get("results")
    if not isinstance(results, list) or len(results) != expected:
        raise ValueError(f"results count mismatch: {type(results).__name__}")
    scores: dict[int, float] = {}
    for item in results:
        index = item.get("index")
        score = item.get("relevance_score")
        if not isinstance(index, int) or isinstance(index, bool) or index in scores:
            raise ValueError("invalid or duplicate index")
        if not isinstance(score, (int, float)) or isinstance(score, bool):
            raise ValueError("non-numeric score")
        numeric = float(score)
        if not math.isfinite(numeric):
            raise ValueError("non-finite score")
        scores[index] = numeric
    if set(scores) != set(range(expected)):
        raise ValueError("score indices are incomplete")
    return scores


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--server", default="http://127.0.0.1:18081")
    parser.add_argument("--output", required=True)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--gate-ms", type=float, default=3000.0)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    fixture = Path(args.fixture)
    case = json.loads(fixture.read_text(encoding="utf-8").splitlines()[0])
    candidates = case["candidates"]
    if len(candidates) != 8:
        raise SystemExit(f"r01 must contain exactly 8 candidates, got {len(candidates)}")
    documents = [
        f"{item.get('title', '')}\n{item.get('text', '')}".strip()
        for item in candidates
    ]

    for _ in range(args.warmup):
        _request(args.server, case["query"], documents, args.timeout)

    samples: list[dict[str, object]] = []
    for run in range(1, args.runs + 1):
        elapsed_ms, payload = _request(args.server, case["query"], documents, args.timeout)
        scores = _scores(payload, 8)
        samples.append(
            {
                "run": run,
                "elapsedMs": round(elapsed_ms, 3),
                "under3s": elapsed_ms <= args.gate_ms,
                "resultCount": len(scores),
                "scores": [scores[index] for index in range(8)],
            }
        )

    sorted_ms = sorted(float(sample["elapsedMs"]) for sample in samples)
    p95_index = max(0, math.ceil(0.95 * len(sorted_ms)) - 1)
    report = {
        "schemaVersion": 1,
        "evidence": "TESTED (local llama.cpp, Qwen3-Reranker-0.6B)",
        "fixture": str(fixture),
        "fixtureSha256": _sha256(fixture),
        "caseId": case["id"],
        "query": case["query"],
        "candidateCount": 8,
        "warmup": args.warmup,
        "runs": args.runs,
        "gateMs": args.gate_ms,
        "server": args.server,
        "samples": samples,
        "p50Ms": sorted_ms[len(sorted_ms) // 2],
        "p95Ms": sorted_ms[p95_index],
        "allUnder3s": all(bool(sample["under3s"]) for sample in samples),
        "allResultCountsExact": all(int(sample["resultCount"]) == 8 for sample in samples),
        "pass": all(bool(sample["under3s"]) for sample in samples)
        and all(int(sample["resultCount"]) == 8 for sample in samples),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"case={case['id']} candidates=8 runs={args.runs} "
        f"p50={report['p50Ms']:.3f}ms p95={report['p95Ms']:.3f}ms "
        f"pass={report['pass']}"
    )
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
