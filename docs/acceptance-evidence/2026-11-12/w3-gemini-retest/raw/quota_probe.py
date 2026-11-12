#!/usr/bin/env python3
"""单次调用，落盘完整响应/错误，用于查看 429 配额细节。"""
import json
import sys
import urllib.error
import urllib.request

MODEL = sys.argv[1] if len(sys.argv) > 1 else "gemini-3.1-flash-lite"
OUT = sys.argv[2] if len(sys.argv) > 2 else "/data/home/w3s15/raw/quota-detail.json"

with open("/data/.secrets/gemini.env", "r", encoding="utf-8") as handle:
    key = handle.read().strip().split("=", 1)[1]

body = {
    "contents": [{"role": "user", "parts": [{"text": "只回答数字：1+1=?"}]}],
    "generationConfig": {"temperature": 0, "maxOutputTokens": 64,
                          "thinkingConfig": {"thinkingBudget": 0}},
}
req = urllib.request.Request(
    f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
    data=json.dumps(body).encode(),
    method="POST",
    headers={"x-goog-api-key": key, "Content-Type": "application/json"},
)
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = {"http": resp.status, "body": json.loads(resp.read())}
except urllib.error.HTTPError as exc:
    payload = {"http": exc.code, "body": json.loads(exc.read())}
with open(OUT, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=1)
print(json.dumps(payload, ensure_ascii=False)[:2500])
