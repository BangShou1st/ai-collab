"""Run the frozen corrected blind review against local Ollama models only."""
from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[5]
EVIDENCE = ROOT / "docs/acceptance-evidence/2026-10-27/p10-real-generation-03"
INPUTS_PATH = EVIDENCE / "judge/corrected-review-inputs.json"
OUTPUT_PATH = EVIDENCE / "judge/corrected-review-results.json"
ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
MODELS = ["qwen2.5:7b-instruct", "qwen3.5:4b"]
REQUEST_TIMEOUT_SECONDS = 180
MAX_OUTPUT_TOKENS = 2048


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def load_output() -> dict:
    if OUTPUT_PATH.is_file():
        return json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
    inputs = json.loads(INPUTS_PATH.read_text(encoding="utf-8"))
    return {
        "batch": inputs["batch"],
        "supplement": inputs["supplement"],
        "transport": "LOCAL_LOOPBACK_OLLAMA",
        "externalModelApiCalls": 0,
        "plannedCalls": inputs["plannedCalls"],
        "requestTimeoutSeconds": REQUEST_TIMEOUT_SECONDS,
        "maxOutputTokens": MAX_OUTPUT_TOKENS,
        "models": MODELS,
        "calls": [],
    }


def persist(data: dict) -> None:
    temp_path = OUTPUT_PATH.with_suffix(".json.tmp")
    temp_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp_path.replace(OUTPUT_PATH)


def parse_json_content(content: str):
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            value = json.loads(content[start:end + 1])
        except json.JSONDecodeError:
            return None
    if not isinstance(value, dict):
        return None
    required = {"factVerdicts", "abstention", "unsupportedClaims", "injectionCompliance", "citationSupport", "uncertainty", "summary"}
    return value if required.issubset(value) else None


def invoke(model: str, prompt: str) -> dict:
    body = json.dumps({
        "model": model,
        "temperature": 0,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": prompt}],
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.monotonic()
    started_at = utc_now()
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            status = response.status
            raw = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read().decode("utf-8", errors="replace")
    except Exception as error:  # bounded, recorded once, never retried
        return {
            "httpStatus": None,
            "status": "TRANSPORT_FAILED",
            "error": f"{type(error).__name__}: {str(error)[:500]}",
            "startedAt": started_at,
            "endedAt": utc_now(),
            "latencyMs": round((time.monotonic() - started) * 1000),
        }

    latency = round((time.monotonic() - started) * 1000)
    if status != 200:
        return {
            "httpStatus": status,
            "status": "HTTP_FAILED",
            "error": raw[:1500],
            "startedAt": started_at,
            "endedAt": utc_now(),
            "latencyMs": latency,
        }
    try:
        response_data = json.loads(raw)
        choice = response_data["choices"][0]["message"]["content"]
    except Exception as error:
        return {
            "httpStatus": status,
            "status": "RESPONSE_SHAPE_FAILED",
            "error": f"{type(error).__name__}: {str(error)[:300]}",
            "rawResponsePreview": raw[:1500],
            "startedAt": started_at,
            "endedAt": utc_now(),
            "latencyMs": latency,
        }
    verdict = parse_json_content(choice)
    return {
        "httpStatus": status,
        "status": "JUDGED" if verdict is not None else "PARSE_FAILED",
        "rawResponse": choice,
        "verdict": verdict,
        "usage": response_data.get("usage"),
        "finishReason": response_data["choices"][0].get("finish_reason"),
        "startedAt": started_at,
        "endedAt": utc_now(),
        "latencyMs": latency,
    }


def main() -> None:
    inputs = json.loads(INPUTS_PATH.read_text(encoding="utf-8"))
    if inputs["models"] != MODELS:
        raise SystemExit("frozen model list does not match runner")
    output = load_output()
    done = {(row["model"], row["runId"]) for row in output["calls"]}
    count = 0
    for model in MODELS:
        for item in inputs["inputs"]:
            key = (model, item["runId"])
            if key in done:
                continue
            result = invoke(model, item["prompt"])
            row = {
                "model": model,
                "kind": item["kind"],
                "runId": item["runId"],
                "promptSha256": item["promptSha256"],
                "promptChars": item["promptChars"],
                "attempt": 1,
                **result,
            }
            output["calls"].append(row)
            persist(output)
            done.add(key)
            count += 1
            summary = (result.get("verdict") or {}).get("summary", result.get("error", ""))
            print(f"{model} {item['kind']} {item['runId']}: {result['status']} {row.get('latencyMs')}ms {summary[:160]}", flush=True)
    print(f"corrected local judge calls completed: {len(output['calls'])}/{output['plannedCalls']} (new this run: {count})")


if __name__ == "__main__":
    main()
