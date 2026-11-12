#!/usr/bin/env python3
"""候选 Gemini 模型探测：thinkingBudget=0 是否被接受、thoughtsTokenCount、耗时。

只依赖标准库；API Key 由环境变量 GEMINI_API_KEY 提供（不落盘、不打印）。
"""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

KEY = os.environ["GEMINI_API_KEY"]
BASE = "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent"
OUT = Path("/data/home/w3s15/raw")
PROMPT = "请用一句话说明北京是哪个国家的首都。"
MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash",
]


def call(model, think0, timeout=40):
    body = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 256},
    }
    if think0:
        body["generationConfig"]["thinkingConfig"] = {"thinkingBudget": 0}
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        BASE.format(model),
        data=data,
        method="POST",
        headers={"x-goog-api-key": KEY, "Content-Type": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    except Exception as exc:  # noqa: BLE001 - 传输层异常如实记录
        elapsed = (time.perf_counter() - started) * 1000
        return {
            "httpStatus": None,
            "error": f"{type(exc).__name__}: {str(exc)[:200]}",
            "elapsedMs": round(elapsed, 1),
        }
    elapsed = (time.perf_counter() - started) * 1000
    try:
        parsed = json.loads(raw)
    except Exception:  # noqa: BLE001
        parsed = {"_raw": raw.decode("utf-8", "replace")[:500]}
    return {"httpStatus": status, "elapsedMs": round(elapsed, 1), "response": parsed}


def summarize(model, think0):
    res = call(model, think0)
    path = OUT / f"probe-{model}-{'think0' if think0 else 'default'}.json"
    path.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    row = {
        "model": model,
        "mode": "think0" if think0 else "default",
        "http": res.get("httpStatus"),
        "elapsedMs": res.get("elapsedMs"),
    }
    if "error" in res:
        row["error"] = res["error"]
        return row
    resp = res["response"]
    if res["httpStatus"] != 200:
        row["errorMessage"] = str(resp.get("error", {}).get("message", ""))[:300]
        return row
    usage = resp.get("usageMetadata", {})
    cand = (resp.get("candidates") or [{}])[0]
    parts = cand.get("content", {}).get("parts", [])
    texts = [p.get("text", "") for p in parts if p.get("text") and not p.get("thought")]
    thoughts = [p for p in parts if p.get("thought")]
    row.update(
        {
            "thoughtsTokenCount": usage.get("thoughtsTokenCount"),
            "candidatesTokenCount": usage.get("candidatesTokenCount"),
            "promptTokenCount": usage.get("promptTokenCount"),
            "totalTokenCount": usage.get("totalTokenCount"),
            "finishReason": cand.get("finishReason"),
            "textLen": len("".join(texts)),
            "thoughtParts": len(thoughts),
        }
    )
    return row


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for model in MODELS:
        rows.append(summarize(model, True))
        rows.append(summarize(model, False))
    (OUT / "probe-summary.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    for row in rows:
        print(json.dumps(row, ensure_ascii=False))


if __name__ == "__main__":
    main()
