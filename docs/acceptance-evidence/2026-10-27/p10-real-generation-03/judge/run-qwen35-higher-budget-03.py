"""Bounded Qwen3.5 retry-as-new-batch with a larger frozen output budget."""
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
OUTPUT_PATH = EVIDENCE / "judge/corrected-review-results-03.json"
INPUTS_SHA256 = "c70f5d9c42d3f5fbdd821c387df6194cf11f54cb0e6e04ef7b192449d788885d"
MODEL = "qwen3.5:4b"
ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
REQUEST_TIMEOUT_SECONDS = 180
MAX_OUTPUT_TOKENS = 8192
STOP_AFTER_CONSECUTIVE_EMPTY_TRUNCATIONS = 3


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


def call(system_prompt: str, user_prompt: str) -> dict:
    body = json.dumps({
        "model": MODEL,
        "temperature": 0,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers={"Content-Type": "application/json"}, method="POST")
    started = time.monotonic()
    started_at = utc_now()
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            status, raw = response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read().decode("utf-8", errors="replace")
    except Exception as error:
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
        return {"httpStatus": status, "status": "HTTP_FAILED", "error": raw[:1500],
                "startedAt": started_at, "endedAt": utc_now(), "latencyMs": latency}
    try:
        data = json.loads(raw)
        choice = data["choices"][0]
        content = choice["message"].get("content") or ""
    except Exception as error:
        return {"httpStatus": status, "status": "RESPONSE_SHAPE_FAILED",
                "error": f"{type(error).__name__}: {str(error)[:300]}", "rawResponsePreview": raw[:1500],
                "startedAt": started_at, "endedAt": utc_now(), "latencyMs": latency}
    verdict = parse_verdict(content) if content else None
    finish_reason = choice.get("finish_reason")
    result_status = "JUDGED" if verdict is not None else "TRUNCATED_EMPTY" if finish_reason == "length" and not content.strip() else "PARSE_FAILED"
    return {
        "httpStatus": status,
        "status": result_status,
        "rawResponse": content,
        "verdict": verdict,
        "usage": data.get("usage"),
        "finishReason": finish_reason,
        "startedAt": started_at,
        "endedAt": utc_now(),
        "latencyMs": latency,
    }


def persist(data: dict) -> None:
    temp = OUTPUT_PATH.with_suffix(".json.tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(OUTPUT_PATH)


def main() -> None:
    raw_inputs = INPUTS_PATH.read_bytes()
    if hashlib.sha256(raw_inputs).hexdigest() != INPUTS_SHA256:
        raise SystemExit("corrected input SHA-256 mismatch; refusing to send prompts")
    inputs = json.loads(raw_inputs.decode("utf-8"))
    calls = inputs["inputs"]
    if OUTPUT_PATH.exists():
        output = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
    else:
        output = {
            "batch": inputs["batch"],
            "supplement": "judge-source-coverage-correction-03",
            "protocolVersion": inputs["protocolVersion"],
            "inputsSha256": INPUTS_SHA256,
            "transport": "LOCAL_LOOPBACK_OLLAMA",
            "externalModelApiCalls": 0,
            "plannedCalls": len(calls),
            "requestTimeoutSeconds": REQUEST_TIMEOUT_SECONDS,
            "maxOutputTokens": MAX_OUTPUT_TOKENS,
            "model": MODEL,
            "calls": [],
            "stoppedAfterConsecutiveEmptyTruncations": False,
            "notRunRunIds": [],
        }
    done = {row["runId"] for row in output["calls"]}
    consecutive_empty = 0
    stop_index = None
    for index, item in enumerate(calls):
        if item["runId"] in done:
            continue
        result = call(inputs["systemPrompt"], item["userPrompt"])
        row = {
            "model": MODEL,
            "kind": item["kind"],
            "runId": item["runId"],
            "promptSha256": item["promptSha256"],
            "promptChars": item["promptChars"],
            "attempt": 1,
            **result,
        }
        output["calls"].append(row)
        done.add(item["runId"])
        if result["status"] == "TRUNCATED_EMPTY":
            consecutive_empty += 1
        else:
            consecutive_empty = 0
        if consecutive_empty >= STOP_AFTER_CONSECUTIVE_EMPTY_TRUNCATIONS:
            stop_index = index + 1
            output["stoppedAfterConsecutiveEmptyTruncations"] = True
            output["notRunRunIds"] = [x["runId"] for x in calls[stop_index:] if x["runId"] not in done]
        persist(output)
        summary = (result.get("verdict") or {}).get("summary", result.get("error", ""))
        print(f"{item['kind']} {item['runId']}: {result['status']} {row.get('latencyMs')}ms {summary[:150]}", flush=True)
        if stop_index is not None:
            break
    persist(output)
    print(f"qwen3.5 higher-budget calls: {len(output['calls'])}/{output['plannedCalls']}; not run: {len(output['notRunRunIds'])}")


if __name__ == "__main__":
    main()
