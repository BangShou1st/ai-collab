#!/usr/bin/env python3
"""快速连发以暴露 RPM 配额限制，落盘完整 429 响应（用于确定限速间隔）。"""
import json
import time
import urllib.error
import urllib.request

with open("/data/.secrets/gemini.env", "r", encoding="utf-8") as handle:
    key = handle.read().strip().split("=", 1)[1]

MODEL = "gemini-3.1-flash-lite"
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
body = {
    "contents": [{"role": "user", "parts": [{"text": "只回答数字：2+2=?"}]}],
    "generationConfig": {"temperature": 0, "maxOutputTokens": 32,
                          "thinkingConfig": {"thinkingBudget": 0}},
}
data = json.dumps(body).encode()

results = []
first_429_full = None
for i in range(25):
    req = urllib.request.Request(
        URL, data=data, method="POST",
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
    )
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read())
            results.append({"i": i + 1, "http": resp.status,
                            "elapsedMs": round((time.time() - started) * 1000)})
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            payload = json.loads(raw)
        except Exception:  # noqa: BLE001
            payload = {"_raw": raw.decode("utf-8", "replace")[:1000]}
        results.append({"i": i + 1, "http": exc.code,
                        "elapsedMs": round((time.time() - started) * 1000),
                        "body": payload})
        if exc.code == 429 and first_429_full is None:
            first_429_full = payload

with open("/data/home/w3s15/raw/burst-40.json", "w", encoding="utf-8") as handle:
    json.dump({"results": results, "first429": first_429_full}, handle,
              ensure_ascii=False, indent=1)
print(json.dumps({"codes": [r["http"] for r in results]}))
if first_429_full is not None:
    print(json.dumps(first_429_full, ensure_ascii=False, indent=1)[:3000])
