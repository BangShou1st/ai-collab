"""Run corrected source-covered reviews using local Ollama with isolated system instructions."""
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
INPUTS_PATH = EVIDENCE / "judge/corrected-review-inputs-02.json"
OUTPUT_PATH = EVIDENCE / "judge/corrected-review-results-02.json"
ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
REQUEST_TIMEOUT_SECONDS = 180
MAX_OUTPUT_TOKENS = 2048


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def parse_verdict(content: str):
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        start, end = content.find("{"), content.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            value = json.loads(content[start:end + 1])
        except json.JSONDecodeError:
            return None
    required = {
        "factVerdicts", "versionJudgment", "regionJudgment", "conditionFidelity",
        "abstention", "unsupportedClaims", "injectionCompliance", "citationSupport",
        "uncertainty", "summary",
    }
    return value if isinstance(value, dict) and required.issubset(value) else None


def invoke(model: str, system_prompt: str, user_prompt: str) -> dict:
    body = json.dumps({
        "model": model,
        "temperature": 0,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
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
            status, raw = response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read().decode("utf-8", errors="replace")
    except Exception as error:  # recorded once; no retry within the frozen budget
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
        data = json.loads(raw)
        choice = data["choices"][0]
        content = choice["message"]["content"]
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
    verdict = parse_verdict(content)
    return {
        "httpStatus": status,
        "status": "JUDGED" if verdict is not None else "PARSE_FAILED",
        "rawResponse": content,
        "verdict": verdict,
        "usage": data.get("usage"),
        "finishReason": choice.get("finish_reason"),
        "startedAt": started_at,
        "endedAt": utc_now(),
        "latencyMs": latency,
    }


def persist(path: Path, data: dict) -> None:
    temp = path.with_suffix(".json.tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def main() -> None:
    inputs = json.loads(INPUTS_PATH.read_text(encoding="utf-8"))
    models = inputs["models"]
    if len(models) != 2 or len(inputs["inputs"]) * len(models) != inputs["plannedCalls"]:
        raise SystemExit("corrected input manifest failed its frozen call-budget check")
    if OUTPUT_PATH.is_file():
        output = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
    else:
        output = {
            "batch": inputs["batch"],
            "supplement": inputs["supplement"],
            "protocolVersion": inputs["protocolVersion"],
            "inputsSha256": hashlib.sha256(INPUTS_PATH.read_bytes()).hexdigest(),
            "transport": "LOCAL_LOOPBACK_OLLAMA",
            "externalModelApiCalls": 0,
            "plannedCalls": inputs["plannedCalls"],
            "requestTimeoutSeconds": REQUEST_TIMEOUT_SECONDS,
            "maxOutputTokens": MAX_OUTPUT_TOKENS,
            "models": models,
            "calls": [],
        }
    done = {(row["model"], row["runId"]) for row in output["calls"]}
    new_count = 0
    for model in models:
        for item in inputs["inputs"]:
            key = (model, item["runId"])
            if key in done:
                continue
            result = invoke(model, inputs["systemPrompt"], item["userPrompt"])
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
            persist(OUTPUT_PATH, output)
            done.add(key)
            new_count += 1
            summary = (result.get("verdict") or {}).get("summary", result.get("error", ""))
            print(f"{model} {item['kind']} {item['runId']}: {result['status']} {row.get('latencyMs')}ms {summary[:150]}", flush=True)
    print(f"corrected local judge-02 requests: {len(output['calls'])}/{output['plannedCalls']} (new this run: {new_count})")


if __name__ == "__main__":
    main()
