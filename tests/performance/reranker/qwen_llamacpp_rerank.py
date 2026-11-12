#!/usr/bin/env python3
"""Local Qwen3-Reranker spike via llama.cpp's native /rerank endpoint.

This is a research harness, not product code. It uses the official Qwen3
reranker prompt/yes-no scoring path through llama.cpp, not the
sentence-transformers default CrossEncoder, and changes no product default.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SET = (
    PROJECT_ROOT
    / "ai-collab-ai-worker"
    / "tests"
    / "fixtures"
    / "rerank_set"
    / "rerank_baseline.v1.jsonl"
)
DEFAULT_SERVER = "http://127.0.0.1:18081"


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    return cases


def pad_candidates(case: dict[str, Any], target: int) -> dict[str, Any]:
    original = case["candidates"]
    if target <= len(original):
        return case
    candidates = [dict(item) for item in original]
    labels = dict(case["labels"])
    supports = dict(case["supports"])
    used = {item["id"] for item in candidates}
    index = 0
    while len(candidates) < target:
        source = original[index % len(original)]
        candidate_id = f"pad-{index + 1}"
        while candidate_id in used:
            index += 1
            candidate_id = f"pad-{index + 1}"
        candidates.append({
            "id": candidate_id,
            "title": f"补齐候选{index + 1}",
            "text": source["text"][:2000],
        })
        labels[candidate_id] = 0
        supports[candidate_id] = False
        used.add(candidate_id)
        index += 1
    return {**case, "candidates": candidates, "labels": labels, "supports": supports}


def document_for(candidate: dict[str, Any]) -> str:
    title = candidate.get("title") or ""
    text = candidate.get("text") or ""
    return f"{title}\n{text}" if title else text


def rerank_case(
    case: dict[str, Any], server_url: str, timeout: float
) -> tuple[dict[str, float], float, int, int]:
    documents = [document_for(candidate) for candidate in case["candidates"]]
    payload = {"query": case["query"], "documents": documents}
    request = urllib.request.Request(
        f"{server_url.rstrip('/')}/rerank",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        results = data.get("results") or []
        if len(results) != len(documents):
            return {}, elapsed_ms, 1, len(documents)
        scores: dict[str, float] = {}
        for result in results:
            index = result.get("index")
            score = result.get("relevance_score")
            if not isinstance(index, int) or not isinstance(score, (int, float)):
                return {}, elapsed_ms, 1, len(documents)
            scores[case["candidates"][index]["id"]] = float(score)
        prompt_tokens = int((data.get("usage") or {}).get("prompt_tokens") or 0)
        return scores, elapsed_ms, 0, prompt_tokens
    except (urllib.error.URLError, TimeoutError, ValueError, OSError):
        return {}, (time.perf_counter() - started) * 1000.0, 1, 0


def ndcg_at_8(case: dict[str, Any], scores: dict[str, float]) -> float:
    original_ids = [item["id"] for item in case["candidates"] if not item["id"].startswith("pad-")]
    labels = case["labels"]
    ranked = sorted(original_ids, key=lambda candidate_id: (-scores.get(candidate_id, 0.0), candidate_id))[:8]
    ideal = sorted(original_ids, key=lambda candidate_id: (-labels[candidate_id], candidate_id))[:8]

    def dcg(ids: list[str]) -> float:
        return sum(
            (2 ** labels[candidate_id] - 1) / math.log2(rank + 2)
            for rank, candidate_id in enumerate(ids)
        )

    denominator = dcg(ideal)
    return 1.0 if denominator == 0 else dcg(ranked) / denominator


def nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(percentile / 100.0 * len(ordered)) - 1)
    return ordered[index]


def gpu_memory() -> str:
    try:
        output = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )
        return output.stdout.strip() or output.stderr.strip()
    except Exception as exc:  # noqa: BLE001 - diagnostics must not break the run
        return f"unavailable: {exc}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", type=Path, default=DEFAULT_SET, dest="set_path")
    parser.add_argument("--server-url", default=DEFAULT_SERVER)
    parser.add_argument("--targets", default="8,20,50")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--safetensors-sha256", default="")
    parser.add_argument("--gguf-sha256", default="")
    args = parser.parse_args()

    cases = load_cases(args.set_path)
    targets = [int(value) for value in args.targets.split(",") if value.strip()]
    output: dict[str, Any] = {
        "schemaVersion": 1,
        "spike": "local-reranker-qwen3-0.6b",
        "model": {
            "name": "Qwen3-Reranker-0.6B",
            "quantization": "Q8_0",
            "safetensorsSha256": args.safetensors_sha256,
            "ggufSha256": args.gguf_sha256,
        },
        "protocol": {
            "engine": "llama.cpp Vulkan /rerank",
            "set": str(args.set_path),
            "cases": len(cases),
            "targets": targets,
            "repetitions": args.repetitions,
            "topK": 8,
            "prompt": "official Qwen3 reranker prompt + yes/no logits (llama.cpp native rerank)",
            "padding": "synthetic zero-label pad candidates for latency; NDCG uses original 8",
        },
        "environment": {
            "gpuBefore": gpu_memory(),
        },
        "tiers": [],
    }

    try:
        for target in targets:
            prepared = [pad_candidates(case, target) for case in cases]
            # One full query warms the Vulkan backend and prompt cache; discard it.
            rerank_case(prepared[0], args.server_url, args.timeout)
            latencies: list[float] = []
            failures = 0
            total_prompt_tokens = 0
            ndcgs: list[float] = []
            for repetition in range(args.repetitions):
                for case in prepared:
                    scores, elapsed_ms, failed, prompt_tokens = rerank_case(
                        case, args.server_url, args.timeout
                    )
                    latencies.append(elapsed_ms)
                    failures += failed
                    total_prompt_tokens += prompt_tokens
                    if repetition == 0:
                        ndcgs.append(ndcg_at_8(case, scores))
            requests = len(cases) * args.repetitions
            output["tiers"].append({
                "target": target,
                "queries": len(latencies),
                "requests": requests,
                "candidateScores": requests * target,
                "failures": failures,
                "failureRate": failures / requests if requests else None,
                "avgPromptTokens": total_prompt_tokens / len(latencies) if latencies else None,
                "latencyMs": {
                    "p50": round(nearest_rank(latencies, 50), 3),
                    "p95": round(nearest_rank(latencies, 95), 3),
                    "p99": round(nearest_rank(latencies, 99), 3),
                    "max": round(max(latencies), 3),
                },
                "ndcgAt8": round(sum(ndcgs) / len(ndcgs), 6) if ndcgs else None,
                "gpuAfter": gpu_memory(),
            })
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
