#!/usr/bin/env python3
"""Formal W3 admin capability-test measurement harness.

This script creates and publishes a RERANK provider/model/profile through the
staging admin API, then measures one admin test request at a time. It never
changes RERANK_MODE and never writes provider credentials or the admin token.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from qwen_llamacpp_rerank import load_cases, ndcg_at_8, pad_candidates

DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_CREDENTIALS = Path("/data/.secrets/admin.env")
DEFAULT_FIXTURE = Path(
    "/data/home/ai-collab/ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl"
)
DEFAULT_PROVIDER_BASE_URL = "http://w3-rerank-relay:18081"
DEFAULT_GGUF_SHA256 = "22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48"
DEFAULT_SAFETENSORS_SHA256 = "27cd75a405b9c1b46b59abfd88aaa209e6fed2a1972cde9b70e7659537c5e65b"
DEFAULT_LLAMA_BUILD = "b12318 (commit 7021f7e09)"


class ApiError(RuntimeError):
    """The admin API rejected a request or returned an invalid envelope."""


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def load_credentials(path: Path) -> tuple[str, str]:
    values: dict[str, str] = {}
    raw_text = path.read_text(encoding="utf-8")
    try:
        parsed = json.loads(raw_text)
        if isinstance(parsed, dict):
            values.update({str(key): str(value) for key, value in parsed.items()})
    except json.JSONDecodeError:
        pass
    for raw_line in raw_text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key.strip().upper()] = value

    username_keys = (
        "ADMIN_USERNAME",
        "ADMIN_USER",
        "AI_COLLAB_ADMIN_USERNAME",
        "USERNAME",
        "USER",
    )
    password_keys = (
        "ADMIN_PASSWORD",
        "ADMIN_PASS",
        "AI_COLLAB_ADMIN_PASSWORD",
        "PASSWORD",
        "PASS",
    )
    username = next((values[key] for key in username_keys if values.get(key)), None)
    password = next((values[key] for key in password_keys if values.get(key)), None)
    if username is None or password is None:
        raise ApiError(
            f"credentials file {path} does not contain a supported username/password pair"
        )
    return username, password


class AdminApi:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout: float = 15.0,
    ) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            data=data,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body_text = exc.read(1000).decode("utf-8", errors="replace")
            raise ApiError(f"HTTP {exc.code} on {path}: {body_text}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise ApiError(f"transport failure on {path}: {exc}") from exc
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(f"invalid JSON on {path}: {exc}") from exc
        if not isinstance(payload, dict) or payload.get("code") != "SUCCESS":
            raise ApiError(f"API failure on {path}: {payload}")
        return payload.get("data")


def login(base_url: str, credentials_file: Path) -> str:
    username, password = load_credentials(credentials_file)
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/v1/auth/login",
        data=json.dumps({"username": username, "password": password}).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body_text = exc.read(1000).decode("utf-8", errors="replace")
        raise ApiError(f"login failed with HTTP {exc.code}: {body_text}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise ApiError(f"login transport failure: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("code") != "SUCCESS":
        raise ApiError("login did not return SUCCESS")
    token = (payload.get("data") or {}).get("accessToken")
    if not isinstance(token, str) or not token:
        raise ApiError("login response has no accessToken")
    return token


def token_from_env(name: str) -> str:
    token = os.environ.get(name)
    if not token:
        raise ApiError(f"environment variable {name} is empty")
    return token


def post_json(
    url: str,
    body: dict[str, Any],
    *,
    token: str | None = None,
    timeout: float = 15.0,
) -> tuple[int, Any]:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return 0, {"transportError": str(exc)}
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return status, {"invalidJson": raw[:500].decode("utf-8", errors="replace")}
    return status, payload


def get_json(url: str, timeout: float = 5.0) -> Any:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def proxy_stats(url: str | None) -> int | None:
    if not url:
        return None
    payload = get_json(url.rstrip("/") + "/stats")
    value = payload.get("requests") if isinstance(payload, dict) else None
    return int(value) if isinstance(value, int) else None


def proxy_reset(url: str | None) -> None:
    if not url:
        return
    request = urllib.request.Request(url.rstrip("/") + "/reset", method="POST")
    with urllib.request.urlopen(request, timeout=5):
        return


def create_resources(
    api: AdminApi,
    *,
    run_id: str,
    provider_base_url: str,
    gguf_sha256: str,
    model_name: str,
) -> dict[str, Any]:
    provider_name = f"w3-lane-b-qwen3-reranker-{run_id}"
    provider = api.request(
        "POST",
        "/api/v1/admin/ai/capability-providers",
        {
            "name": provider_name,
            "providerType": "LOCAL_CUSTOM",
            "protocol": "LLAMACPP_RERANK",
            "baseUrl": provider_base_url,
            "apiPath": "/rerank",
            "authType": "NONE",
            "networkZone": "local",
            "healthPath": "/health",
            "enabled": True,
            "reason": "Lane B W3 formal admin capability test",
        },
    )
    try:
        model_profile = api.request(
            "POST",
            "/api/v1/admin/ai/model-profiles",
            {
                "providerType": "LOCAL_CUSTOM",
                "baseUrl": provider_base_url,
                "apiPath": "/rerank",
                "modelName": model_name,
                "contextWindowTokens": 8192,
                "maxOutputTokens": None,
                "supportsUsage": True,
                "tokenizerKind": None,
                "pricingVersion": None,
                "source": "LOCAL_FILE",
                "modelKey": "Qwen/Qwen3-Reranker-0.6B",
                "displayName": "Qwen3-Reranker-0.6B Q8_0",
                "modelVersion": "2026-11-12",
                "sha256": gguf_sha256,
                "license": "Apache-2.0",
            },
        )
    except ApiError as exc:
        if "MODEL_PROFILE_EXISTS" not in str(exc):
            raise
        profiles = api.request("GET", "/api/v1/admin/ai/model-profiles")
        model_profile = next(
            (
                profile
                for profile in profiles
                if profile.get("baseUrl") == provider_base_url
                and profile.get("apiPath") == "/rerank"
                and profile.get("modelName") == model_name
            ),
            None,
        )
        if model_profile is None:
            raise
    capability_profile = api.request(
        "POST",
        "/api/v1/admin/ai/capability-profiles",
        {
            "modelProfileId": model_profile["id"],
            "providerId": provider["id"],
            "capability": "RERANK",
            "protocol": "LLAMACPP_RERANK",
            "settings": {
                "protocol": "LLAMACPP_RERANK",
                "timeoutMs": 3000,
                "maxCandidates": 50,
                "batchSize": 8,
                "maxLength": 2048,
                "scoreType": "sigmoid",
                "topK": 8,
                "healthPath": "/health",
                "fallback": "java-heuristic",
            },
            "source": "ADMIN_VERIFIED",
            "reason": "Lane B W3 formal admin capability test",
        },
    )
    return {
        "provider": provider,
        "modelProfile": model_profile,
        "capabilityProfile": capability_profile,
    }


def validate_test_response(
    payload: Any,
    expected_count: int,
    expected_ids: list[str],
) -> tuple[list[dict[str, Any]], str | None]:
    if not isinstance(payload, dict):
        return [], "response_not_object"
    scores = payload.get("scores")
    if not isinstance(scores, list):
        return [], "scores_missing"
    if len(scores) != expected_count:
        return [], "scores_count_mismatch"
    seen: set[int] = set()
    normalized: list[dict[str, Any]] = []
    for item in scores:
        if not isinstance(item, dict):
            return [], "score_not_object"
        index = item.get("index")
        candidate_id = item.get("id")
        score = item.get("score")
        if isinstance(index, bool) or not isinstance(index, int):
            return [], "score_index_invalid"
        if index < 0 or index >= expected_count or index in seen:
            return [], "score_index_invalid"
        if candidate_id != expected_ids[index]:
            return [], "score_id_order_mismatch"
        if isinstance(score, bool) or not isinstance(score, (int, float)):
            return [], "score_not_numeric"
        numeric_score = float(score)
        if not math.isfinite(numeric_score):
            return [], "score_not_finite"
        seen.add(index)
        normalized.append({"index": index, "id": candidate_id, "score": numeric_score})
    if len(seen) != expected_count:
        return [], "score_index_missing"
    return normalized, None


def classify_failure(
    *,
    http_status: int,
    wall_ms: float,
    payload: Any,
    validation_error: str | None,
    transport_error: str | None,
    gate_ms: float,
) -> str | None:
    if transport_error:
        return "transport_error"
    if http_status < 200 or http_status >= 300:
        return f"http_{http_status}"
    if not isinstance(payload, dict) or payload.get("code") != "SUCCESS":
        return "invalid_envelope"
    data = payload.get("data")
    if not isinstance(data, dict):
        return "invalid_response"
    if validation_error:
        return validation_error
    outcome = data.get("outcome")
    if outcome != "SUCCESS":
        return f"adapter_{outcome or 'UNKNOWN'}"
    if wall_ms > gate_ms:
        return "over_gate"
    return None


def run_admin_test(
    api: AdminApi,
    profile_id: str,
    case: dict[str, Any],
    *,
    phase: str,
    tier: int,
    pass_index: int,
    top_k: int,
    gate_ms: float,
    timeout: float,
) -> dict[str, Any]:
    candidate_ids = [candidate["id"] for candidate in case["candidates"]]
    documents = [
        {
            "id": candidate["id"],
            "title": candidate.get("title") or "",
            "text": candidate["text"],
        }
        for candidate in case["candidates"]
    ]
    body = {
        "query": case["query"],
        "documents": documents,
        "topK": top_k,
        "reason": f"lane-b tier={tier} phase={phase} pass={pass_index} case={case['id']}",
    }
    started = time.perf_counter()
    http_status, payload = post_json(
        f"{api.base_url}/api/v1/admin/ai/capability-profiles/{profile_id}/test",
        body,
        token=api.token,
        timeout=timeout,
    )
    wall_ms = (time.perf_counter() - started) * 1000.0
    transport_error = None
    if isinstance(payload, dict) and payload.get("transportError"):
        transport_error = str(payload["transportError"])
    data = payload.get("data") if isinstance(payload, dict) else None
    scores: list[dict[str, Any]] = []
    validation_error = None
    if isinstance(data, dict) and data.get("outcome") == "SUCCESS":
        scores, validation_error = validate_test_response(data, len(documents), candidate_ids)
    model_latency_ms = data.get("modelLatencyMs") if isinstance(data, dict) else None
    known = isinstance(model_latency_ms, int) and not isinstance(model_latency_ms, bool)
    computed_overhead_ms = max(0.0, wall_ms - float(model_latency_ms)) if known else None
    failure_type = classify_failure(
        http_status=http_status,
        wall_ms=wall_ms,
        payload=payload,
        validation_error=validation_error,
        transport_error=transport_error,
        gate_ms=gate_ms,
    )
    outcome = data.get("outcome") if isinstance(data, dict) else None
    ndcg = None
    if not failure_type and scores:
        score_map = {item["id"]: item["score"] for item in scores}
        ndcg = ndcg_at_8(case, score_map)
    return {
        "phase": phase,
        "tier": tier,
        "pass": pass_index,
        "caseId": case["id"],
        "candidateCount": len(documents),
        "topK": top_k,
        "httpStatus": http_status,
        "wallE2eMs": round(wall_ms, 3),
        "outcome": outcome,
        "reason": data.get("reason") if isinstance(data, dict) else None,
        "modelLatencyMs": model_latency_ms,
        "adapterTotalMs": data.get("totalMs") if isinstance(data, dict) else None,
        "adapterOverheadMs": data.get("overheadMs") if isinstance(data, dict) else None,
        "overheadMs": round(computed_overhead_ms, 3) if computed_overhead_ms is not None else None,
        "modelLatencyKnown": known,
        "scores": scores if not failure_type else [],
        "ndcgAt8": round(ndcg, 6) if ndcg is not None else None,
        "failureType": failure_type,
        "transportError": transport_error,
        "rawEnvelope": payload if failure_type and not transport_error else None,
    }


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(p / 100.0 * len(ordered)) - 1)
    return round(ordered[index], 3)


def evaluate_records(
    records: list[dict[str, Any]],
    *,
    overhead_p95_limit: float,
    overhead_p99_limit: float,
    expected_proxy_calls: int | None,
    proxy_delta: int | None,
) -> dict[str, Any]:
    attempts = len(records)
    failures = [record for record in records if record["failureType"]]
    successful = [record for record in records if not record["failureType"]]
    known = [record for record in records if record["modelLatencyKnown"]]
    overhead_values = [
        float(record["overheadMs"]) for record in successful if record["overheadMs"] is not None
    ]
    failure_rate = len(failures) / attempts if attempts else 1.0
    known_ratio = len(known) / attempts if attempts else 0.0
    overhead_p95 = percentile(overhead_values, 95)
    overhead_p99 = percentile(overhead_values, 99)
    one_to_one = proxy_delta == expected_proxy_calls if expected_proxy_calls is not None else None
    reasons: list[str] = []
    if failure_rate > 0.01:
        reasons.append("failure_rate_over_1pct")
    if known_ratio < 0.99:
        reasons.append("model_latency_known_ratio_below_99pct")
    if overhead_p95 is None or overhead_p95 > overhead_p95_limit:
        reasons.append("overhead_p95_over_limit_or_unknown")
    if overhead_p99 is None or overhead_p99 > overhead_p99_limit:
        reasons.append("overhead_p99_over_limit_or_unknown")
    if one_to_one is None:
        reasons.append("reranker_call_count_not_instrumented")
    elif not one_to_one:
        reasons.append("reranker_call_count_not_one_to_one")
    return {
        "attempts": attempts,
        "failures": len(failures),
        "failureRate": round(failure_rate, 6),
        "failureTypes": {
            failure_type: sum(1 for record in failures if record["failureType"] == failure_type)
            for failure_type in sorted(
                {record["failureType"] for record in failures if record["failureType"]}
            )
        },
        "modelLatencyKnownSamples": len(known),
        "modelLatencyKnownRatio": round(known_ratio, 6),
        "modelLatencyMs": {
            "p50": percentile([float(record["modelLatencyMs"]) for record in known], 50),
            "p95": percentile([float(record["modelLatencyMs"]) for record in known], 95),
            "p99": percentile([float(record["modelLatencyMs"]) for record in known], 99),
        },
        "e2eLatencyMs": {
            "p50": percentile([float(record["wallE2eMs"]) for record in records], 50),
            "p95": percentile([float(record["wallE2eMs"]) for record in records], 95),
            "p99": percentile([float(record["wallE2eMs"]) for record in records], 99),
        },
        "overheadMs": {
            "samples": len(overhead_values),
            "p50": percentile(overhead_values, 50),
            "p95": overhead_p95,
            "p99": overhead_p99,
        },
        "expectedProxyCalls": expected_proxy_calls,
        "proxyCallDelta": proxy_delta,
        "oneToOne": one_to_one,
        "gateStatus": "PASS" if not reasons else "FAIL",
        "gateReasons": reasons,
        "confidence": "LOW_CONFIDENCE" if attempts < 200 else "MEDIUM",
        "failuresDetail": failures,
    }


def write_jsonl(path: Path, record: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        stream.flush()


def run_tier(
    api: AdminApi,
    profile_id: str,
    cases: list[dict[str, Any]],
    *,
    tier: int,
    gate_runs: int,
    warmup_passes: int,
    top_k: int,
    gate_ms: float,
    timeout: float,
    proxy_stats_url: str | None,
    raw_path: Path,
) -> dict[str, Any]:
    prepared = [pad_candidates(case, tier) for case in cases]
    before = proxy_stats(proxy_stats_url)
    warmups: list[dict[str, Any]] = []
    for warmup_pass in range(warmup_passes):
        for case in prepared:
            record = run_admin_test(
                api,
                profile_id,
                case,
                phase="warmup",
                tier=tier,
                pass_index=warmup_pass,
                top_k=top_k,
                gate_ms=gate_ms,
                timeout=timeout,
            )
            warmups.append(record)
            write_jsonl(raw_path, record)
    gate_records: list[dict[str, Any]] = []
    for pass_index in range(gate_runs):
        for case in prepared:
            record = run_admin_test(
                api,
                profile_id,
                case,
                phase="gate",
                tier=tier,
                pass_index=pass_index,
                top_k=top_k,
                gate_ms=gate_ms,
                timeout=timeout,
            )
            gate_records.append(record)
            write_jsonl(raw_path, record)
    after = proxy_stats(proxy_stats_url)
    proxy_delta = None if before is None or after is None else after - before
    expected_calls = len(warmups) + len(gate_records) if proxy_delta is not None else None
    evaluation = evaluate_records(
        gate_records,
        overhead_p95_limit=800.0,
        overhead_p99_limit=1500.0,
        expected_proxy_calls=expected_calls,
        proxy_delta=proxy_delta,
    )
    evaluation.update(
        {
            "tier": tier,
            "warmupRequests": len(warmups),
            "warmupFailures": sum(1 for record in warmups if record["failureType"]),
            "gateRequests": len(gate_records),
        }
    )
    return evaluation


def run_observations(
    api: AdminApi,
    profile_id: str,
    cases: list[dict[str, Any]],
    *,
    tier: int,
    observation_runs: int,
    top_k: int,
    gate_ms: float,
    timeout: float,
    proxy_stats_url: str | None,
    raw_path: Path,
) -> dict[str, Any]:
    prepared = [pad_candidates(case, tier) for case in cases]
    before = proxy_stats(proxy_stats_url)
    records: list[dict[str, Any]] = []
    for pass_index in range(observation_runs):
        for case in prepared:
            record = run_admin_test(
                api,
                profile_id,
                case,
                phase="observation",
                tier=tier,
                pass_index=pass_index,
                top_k=top_k,
                gate_ms=gate_ms,
                timeout=timeout,
            )
            records.append(record)
            write_jsonl(raw_path, record)
    after = proxy_stats(proxy_stats_url)
    proxy_delta = None if before is None or after is None else after - before
    return evaluate_records(
        records,
        overhead_p95_limit=800.0,
        overhead_p99_limit=1500.0,
        expected_proxy_calls=len(records) if proxy_delta is not None else None,
        proxy_delta=proxy_delta,
    ) | {"tier": tier, "observationRequests": len(records)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--credentials-file", type=Path, default=DEFAULT_CREDENTIALS)
    parser.add_argument("--token-env", default="")
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-id", default=datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ"))
    parser.add_argument("--tiers", default="8,20,50")
    parser.add_argument("--gate-runs", type=int, default=1)
    parser.add_argument("--warmup-passes", type=int, default=1)
    parser.add_argument("--observation-runs", type=int, default=4)
    parser.add_argument("--gate-ms", type=float, default=3000.0)
    parser.add_argument("--request-timeout", type=float, default=10.0)
    parser.add_argument("--top-k", type=int, default=8)
    parser.add_argument("--provider-base-url", default=DEFAULT_PROVIDER_BASE_URL)
    parser.add_argument("--model-name", default="qwen3-reranker-0.6b-q8_0")
    parser.add_argument("--gguf-sha256", default=DEFAULT_GGUF_SHA256)
    parser.add_argument("--safetensors-sha256", default=DEFAULT_SAFETENSORS_SHA256)
    parser.add_argument("--llama-build", default=DEFAULT_LLAMA_BUILD)
    parser.add_argument("--commit", default="")
    parser.add_argument("--proxy-url", default="")
    parser.add_argument("--skip-resource-creation", action="store_true")
    parser.add_argument("--provider-id", default="")
    parser.add_argument("--model-profile-id", default="")
    parser.add_argument("--profile-id", default="")
    args = parser.parse_args()

    if args.gate_runs < 1 or args.warmup_passes < 0 or args.observation_runs < 0:
        raise SystemExit("runs/passes must be non-negative and gate-runs >= 1")
    tiers = [int(value) for value in args.tiers.split(",") if value.strip()]
    if not tiers:
        raise SystemExit("--tiers must not be empty")

    started_at = now_iso()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    raw_path = args.output.parent / f"admin-requests-{args.run_id}.jsonl"
    raw_path.write_text("", encoding="utf-8")
    proxy_reset(args.proxy_url or None)
    token = (
        token_from_env(args.token_env)
        if args.token_env
        else login(args.base_url, args.credentials_file)
    )
    api = AdminApi(args.base_url, token)

    resources: dict[str, Any] = {
        "provider": {"id": args.provider_id} if args.provider_id else None,
        "modelProfile": {"id": args.model_profile_id} if args.model_profile_id else None,
        "capabilityProfile": {"id": args.profile_id} if args.profile_id else None,
    }
    if not args.skip_resource_creation:
        resources = create_resources(
            api,
            run_id=args.run_id,
            provider_base_url=args.provider_base_url,
            gguf_sha256=args.gguf_sha256,
            model_name=args.model_name,
        )
    profile_id = resources["capabilityProfile"]["id"]
    profile_path = f"/api/v1/admin/ai/capability-profiles/{profile_id}"

    default_test = api.request(
        "POST",
        profile_path + "/test",
        {"reason": "Lane B default relevance smoke before publish"},
        timeout=args.request_timeout,
    )
    published = api.request(
        "POST",
        profile_path + "/publish",
        {"reason": "Lane B W3 formal measurement"},
    )

    cases = load_cases(args.fixture)
    summary: dict[str, Any] = {
        "schemaVersion": 1,
        "measurement": "W3 Lane B formal admin capability test",
        "startedAt": started_at,
        "finishedAt": None,
        "runId": args.run_id,
        "admin": {
            "authenticated": True,
            "baseUrl": args.base_url,
            "proxyUrl": args.proxy_url or None,
        },
        "resources": resources,
        "adminTestEndpoint": profile_path + "/test",
        "model": {
            "name": "Qwen3-Reranker-0.6B",
            "quantization": "Q8_0",
            "ggufSha256": args.gguf_sha256,
            "safetensorsSha256": args.safetensors_sha256,
            "llamaBuild": args.llama_build,
            "commit": args.commit,
        },
        "fixture": {
            "path": str(args.fixture),
            "cases": len(cases),
            "caseIds": [case["id"] for case in cases],
        },
        "defaultTest": default_test,
        "publishedProfile": published,
        "gate": {
            "tiers": tiers,
            "gateRuns": args.gate_runs,
            "warmupPasses": args.warmup_passes,
            "gateMs": args.gate_ms,
            "topK": args.top_k,
            "tierResults": [],
            "overallStatus": None,
            "failedTier": None,
        },
        "observations": [],
        "rawJsonl": str(raw_path),
        "productionUserPath": "NOT_VERIFIED (RERANK_MODE=java unchanged)",
    }

    for tier in tiers:
        result = run_tier(
            api,
            profile_id,
            cases,
            tier=tier,
            gate_runs=args.gate_runs,
            warmup_passes=args.warmup_passes,
            top_k=args.top_k,
            gate_ms=args.gate_ms,
            timeout=args.request_timeout,
            proxy_stats_url=args.proxy_url or None,
            raw_path=raw_path,
        )
        summary["gate"]["tierResults"].append(result)
        if result["gateStatus"] != "PASS":
            summary["gate"]["overallStatus"] = "FAIL"
            summary["gate"]["failedTier"] = tier
            break
    else:
        summary["gate"]["overallStatus"] = "PASS"
        for tier in tiers:
            summary["observations"].append(
                run_observations(
                    api,
                    profile_id,
                    cases,
                    tier=tier,
                    observation_runs=args.observation_runs,
                    top_k=args.top_k,
                    gate_ms=args.gate_ms,
                    timeout=args.request_timeout,
                    proxy_stats_url=args.proxy_url or None,
                    raw_path=raw_path,
                )
            )

    if summary["gate"]["overallStatus"] == "PASS":
        summary["evidenceGrade"] = "LIVE_VERIFIED (staging, local reranker, admin capability test)"
    else:
        summary["evidenceGrade"] = "FAIL"
    summary["finishedAt"] = now_iso()
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "overallStatus": summary["gate"]["overallStatus"],
                "failedTier": summary["gate"]["failedTier"],
                "profileId": profile_id,
                "output": str(args.output),
                "rawJsonl": str(raw_path),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
