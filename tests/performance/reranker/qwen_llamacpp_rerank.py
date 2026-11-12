#!/usr/bin/env python3
"""Research-only preflight for Qwen3-Reranker via llama.cpp native /rerank.

This harness records per-request evidence but does not evaluate the W3 gate.
The production gate requires Java-side model latency and overhead through the
admin capability-test path. A raw llama.cpp preflight cannot supply those
fields, so the output is always marked NOT_EVALUATED.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
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
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_GATE_MS = 3000.0


class InvalidResponseError(ValueError):
    """The server returned a response that violates the rerank contract."""


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    return cases


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def pad_candidates(case: dict[str, Any], target: int) -> dict[str, Any]:
    """Pad to a minimum candidate count; never truncate the frozen case."""

    original = case["candidates"]
    if len(original) >= target:
        return case

    candidates = [dict(item) for item in original]
    labels = dict(case["labels"])
    supports = dict(case["supports"])
    used = {item["id"] for item in candidates}
    sources = [item["text"] for item in original]
    for index in range(1, target - len(original) + 1):
        candidate_id = f"pad-{index}"
        suffix = 0
        while candidate_id in used:
            suffix += 1
            candidate_id = f"pad-{index}-{suffix}"
        text = ""
        source_index = 0
        while len(text) < 2000:
            piece = sources[source_index % len(sources)]
            text += piece[: 2000 - len(text)]
            source_index += 1
        candidates.append(
            {
                "id": candidate_id,
                "title": f"补齐候选{index}",
                "text": text,
            }
        )
        labels[candidate_id] = 0
        supports[candidate_id] = False
        used.add(candidate_id)
    return {**case, "candidates": candidates, "labels": labels, "supports": supports}


def document_for(candidate: dict[str, Any]) -> str:
    title = candidate.get("title") or ""
    text = candidate.get("text") or ""
    return f"{title}\n{text}" if title else text


def _read_error_body(exc: urllib.error.HTTPError) -> str:
    try:
        return exc.read(2000).decode("utf-8", errors="replace")
    except OSError:
        return ""


def _parse_results(data: Any, expected_count: int) -> tuple[dict[int, float], int]:
    if not isinstance(data, dict):
        raise InvalidResponseError("response is not a JSON object")
    results = data.get("results")
    if not isinstance(results, list):
        raise InvalidResponseError("response.results is not an array")
    if len(results) != expected_count:
        raise InvalidResponseError(
            f"response.results has {len(results)} entries, expected {expected_count}"
        )

    scores: dict[int, float] = {}
    for result in results:
        if not isinstance(result, dict):
            raise InvalidResponseError("result entry is not an object")
        index = result.get("index")
        score = result.get("relevance_score")
        if isinstance(index, bool) or not isinstance(index, int):
            raise InvalidResponseError("result.index is not an integer")
        if index < 0 or index >= expected_count:
            raise InvalidResponseError(f"result.index {index} is out of range")
        if index in scores:
            raise InvalidResponseError(f"result.index {index} is duplicated")
        if isinstance(score, bool) or not isinstance(score, (int, float)):
            raise InvalidResponseError(f"result[{index}].relevance_score is not numeric")
        numeric_score = float(score)
        if not math.isfinite(numeric_score):
            raise InvalidResponseError(f"result[{index}].relevance_score is not finite")
        scores[index] = numeric_score

    prompt_tokens = int((data.get("usage") or {}).get("prompt_tokens") or 0)
    return scores, prompt_tokens


def request_rerank(
    server_url: str,
    query: str,
    documents: list[str],
    timeout: float,
) -> dict[str, Any]:
    payload = {"query": query, "documents": documents}
    request = urllib.request.Request(
        f"{server_url.rstrip('/')}/rerank",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise InvalidResponseError(f"invalid JSON response: {exc}") from exc
        scores, prompt_tokens = _parse_results(data, len(documents))
        return {
            "ok": True,
            "elapsedMs": elapsed_ms,
            "scoresByIndex": scores,
            "promptTokens": prompt_tokens,
            "failureType": None,
            "httpStatus": 200,
            "error": None,
        }
    except urllib.error.HTTPError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": False,
            "elapsedMs": elapsed_ms,
            "scoresByIndex": {},
            "promptTokens": 0,
            "failureType": f"http_{exc.code}",
            "httpStatus": exc.code,
            "error": _read_error_body(exc)[:500],
        }
    except TimeoutError:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": False,
            "elapsedMs": elapsed_ms,
            "scoresByIndex": {},
            "promptTokens": 0,
            "failureType": "timeout",
            "httpStatus": None,
            "error": "request timed out",
        }
    except urllib.error.URLError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        failure_type = "timeout" if isinstance(exc.reason, socket.timeout) else "connection_error"
        return {
            "ok": False,
            "elapsedMs": elapsed_ms,
            "scoresByIndex": {},
            "promptTokens": 0,
            "failureType": failure_type,
            "httpStatus": None,
            "error": str(exc.reason)[:500],
        }
    except (InvalidResponseError, OSError) as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return {
            "ok": False,
            "elapsedMs": elapsed_ms,
            "scoresByIndex": {},
            "promptTokens": 0,
            "failureType": (
                "invalid_response" if isinstance(exc, InvalidResponseError) else "io_error"
            ),
            "httpStatus": None,
            "error": str(exc)[:500],
        }


def ndcg_at_8(case: dict[str, Any], scores: dict[str, float]) -> float:
    original_ids = [item["id"] for item in case["candidates"] if not item["id"].startswith("pad-")]
    labels = case["labels"]
    ranked = sorted(
        original_ids,
        key=lambda candidate_id: (-scores.get(candidate_id, 0.0), candidate_id),
    )[:8]
    ideal = sorted(original_ids, key=lambda candidate_id: (-labels[candidate_id], candidate_id))[:8]

    def dcg(ids: list[str]) -> float:
        return sum(
            (2 ** labels[candidate_id] - 1) / math.log2(rank + 2)
            for rank, candidate_id in enumerate(ids)
        )

    denominator = dcg(ideal)
    return 1.0 if denominator == 0 else dcg(ranked) / denominator


def nearest_rank(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(percentile / 100.0 * len(ordered)) - 1)
    return ordered[index]


def gpu_memory() -> str:
    try:
        output = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=memory.used,memory.total",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )
        return output.stdout.strip() or output.stderr.strip()
    except Exception as exc:
        return f"unavailable: {exc}"


def summarize_latency(values: list[float]) -> dict[str, float | int | None]:
    return {
        "samples": len(values),
        "p50": round(nearest_rank(values, 50) or 0.0, 3) if values else None,
        "p95": round(nearest_rank(values, 95) or 0.0, 3) if values else None,
        "p99": round(nearest_rank(values, 99) or 0.0, 3) if values else None,
        "max": round(max(values), 3) if values else None,
    }


def confidence_for_samples(samples: int) -> str:
    if samples >= 500:
        return "HIGH"
    if samples >= 200:
        return "MEDIUM"
    return "LOW_CONFIDENCE"


def make_record(
    *,
    phase: str,
    tier: int,
    repetition: int | None,
    case: dict[str, Any],
    response: dict[str, Any],
    gate_ms: float,
) -> dict[str, Any]:
    candidate_ids = [candidate["id"] for candidate in case["candidates"]]
    result = {
        "phase": phase,
        "tier": tier,
        "repetition": repetition,
        "caseId": case["id"],
        "candidateCount": len(candidate_ids),
        "candidateIds": candidate_ids,
        "elapsedMs": round(response["elapsedMs"], 3),
        "overGateMs": response["elapsedMs"] > gate_ms,
        "ok": response["ok"],
        "failureType": response["failureType"],
        "httpStatus": response["httpStatus"],
        "promptTokens": response["promptTokens"],
        "error": response["error"],
    }
    if response["ok"]:
        result["scoresById"] = {
            candidate_ids[index]: score
            for index, score in sorted(response["scoresByIndex"].items())
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", type=Path, default=DEFAULT_SET, dest="set_path")
    parser.add_argument("--server-url", default=DEFAULT_SERVER)
    parser.add_argument("--targets", default="8,20,50")
    parser.add_argument("--warmup-passes", type=int, default=1)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--gate-ms", type=float, default=DEFAULT_GATE_MS)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--safetensors-sha256", default="")
    parser.add_argument("--gguf-sha256", default="")
    args = parser.parse_args()

    cases = load_cases(args.set_path)
    targets = [int(value) for value in args.targets.split(",") if value.strip()]
    if not targets:
        raise SystemExit("--targets must contain at least one integer")
    if args.repetitions < 1:
        raise SystemExit("--repetitions must be >= 1")
    if args.warmup_passes < 0:
        raise SystemExit("--warmup-passes must be >= 0")

    output: dict[str, Any] = {
        "schemaVersion": 2,
        "spike": "local-reranker-qwen3-0.6b",
        "status": "LOCAL_PREFLIGHT_ONLY",
        "generatedAt": datetime.now(UTC).isoformat(),
        "model": {
            "name": "Qwen3-Reranker-0.6B",
            "quantization": "Q8_0",
            "safetensorsSha256": args.safetensors_sha256,
            "ggufSha256": args.gguf_sha256,
            "ggufPath": "D:\\models\\qwen3-reranker-0.6b-q8_0.gguf",
        },
        "protocol": {
            "engine": "llama.cpp Vulkan /rerank",
            "serverUrl": args.server_url,
            "set": str(args.set_path),
            "setFileSha256": file_sha256(args.set_path),
            "cases": len(cases),
            "targets": targets,
            "warmupPasses": args.warmup_passes,
            "repetitions": args.repetitions,
            "gateMs": args.gate_ms,
            "topK": 8,
            "prompt": "official Qwen3 reranker prompt + yes/no logits (llama.cpp native rerank)",
            "padding": (
                "deterministic zero-label padding to a minimum target; frozen cases are never "
                "truncated"
            ),
        },
        "gateEvaluation": {
            "status": "NOT_EVALUATED",
            "reason": (
                "raw llama.cpp preflight has no Java model latency or overhead; it cannot "
                "prove the W3 gate"
            ),
            "modelLatencyKnownRatio": None,
            "overheadP95Ms": None,
            "overheadP99Ms": None,
        },
        "environment": {
            "hostname": socket.gethostname(),
            "platform": platform.platform(),
            "python": sys.version,
            "gpuBefore": gpu_memory(),
        },
        "tiers": [],
        "requests": [],
    }

    try:
        for target in targets:
            prepared = [pad_candidates(case, target) for case in cases]
            warmup_records: list[dict[str, Any]] = []
            for warmup_pass in range(args.warmup_passes):
                for case in prepared:
                    warmup_documents = [document_for(candidate) for candidate in case["candidates"]]
                    warmup = request_rerank(
                        args.server_url,
                        case["query"],
                        warmup_documents,
                        args.timeout,
                    )
                    record = make_record(
                        phase="warmup",
                        tier=target,
                        repetition=warmup_pass,
                        case=case,
                        response=warmup,
                        gate_ms=args.gate_ms,
                    )
                    warmup_records.append(record)
                    output["requests"].append(record)

            tier_records: list[dict[str, Any]] = []
            ndcgs: list[float] = []
            for repetition in range(args.repetitions):
                for case in prepared:
                    candidate_ids = [candidate["id"] for candidate in case["candidates"]]
                    documents = [document_for(candidate) for candidate in case["candidates"]]
                    response = request_rerank(
                        args.server_url,
                        case["query"],
                        documents,
                        args.timeout,
                    )
                    record = make_record(
                        phase="sample",
                        tier=target,
                        repetition=repetition,
                        case=case,
                        response=response,
                        gate_ms=args.gate_ms,
                    )
                    tier_records.append(record)
                    output["requests"].append(record)
                    if repetition == 0 and response["ok"]:
                        scores = {
                            candidate_ids[index]: score
                            for index, score in response["scoresByIndex"].items()
                        }
                        ndcgs.append(ndcg_at_8(case, scores))

            latencies = [record["elapsedMs"] for record in tier_records]
            success_latencies = [record["elapsedMs"] for record in tier_records if record["ok"]]
            failures = [record for record in tier_records if not record["ok"]]
            output["tiers"].append(
                {
                    "target": target,
                    "cases": len(cases),
                    "warmupRequests": len(warmup_records),
                    "warmupFailures": sum(1 for record in warmup_records if not record["ok"]),
                    "warmupOverGateMsCount": sum(
                        1 for record in warmup_records if record["overGateMs"]
                    ),
                    "requests": len(tier_records),
                    "candidateCounts": {
                        "min": min(record["candidateCount"] for record in tier_records),
                        "max": max(record["candidateCount"] for record in tier_records),
                        "total": sum(record["candidateCount"] for record in tier_records),
                    },
                    "failures": len(failures),
                    "failureRate": len(failures) / len(tier_records) if tier_records else None,
                    "failureTypes": {
                        failure_type: sum(
                            1 for record in failures if record["failureType"] == failure_type
                        )
                        for failure_type in sorted({record["failureType"] for record in failures})
                    },
                    "overGateMsCount": sum(1 for record in tier_records if record["overGateMs"]),
                    "wallLatencyMs": summarize_latency(latencies),
                    "successLatencyMs": summarize_latency(success_latencies),
                    "avgPromptTokens": (
                        round(
                            sum(record["promptTokens"] for record in tier_records)
                            / len(tier_records),
                            3,
                        )
                        if tier_records
                        else None
                    ),
                    "ndcgAt8": round(sum(ndcgs) / len(ndcgs), 6) if ndcgs else None,
                    "ndcgCases": len(ndcgs),
                    "confidence": confidence_for_samples(len(tier_records)),
                    "gateStatus": "NOT_EVALUATED",
                    "gpuAfter": gpu_memory(),
                }
            )
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(output, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    summary = {
        "status": output["status"],
        "gateEvaluation": output["gateEvaluation"]["status"],
        "setFileSha256": output["protocol"]["setFileSha256"],
        "tiers": [
            {
                key: tier[key]
                for key in (
                    "target",
                    "requests",
                    "candidateCounts",
                    "failures",
                    "failureRate",
                    "overGateMsCount",
                    "wallLatencyMs",
                    "ndcgAt8",
                    "confidence",
                    "gateStatus",
                )
            }
            for tier in output["tiers"]
        ],
        "output": str(args.output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
