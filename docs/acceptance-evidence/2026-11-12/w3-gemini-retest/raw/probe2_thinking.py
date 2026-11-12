#!/usr/bin/env python3
"""选中模型 gemini-3.1-flash-lite 的 thinking 关闭证据：3 种模式 × 5 次。

模式：
  think0            generationConfig.thinkingConfig.thinkingBudget=0
  default           不发送 thinkingConfig（模型默认思考）
  default_thoughts  不发送 budget，但 includeThoughts=true（暴露 thought 部分/计数）
"""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

KEY = os.environ["GEMINI_API_KEY"]
MODEL = "gemini-3.1-flash-lite"
BASE = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
OUT = Path("/data/home/w3s15/raw")
PROMPT = (
    "一个篮子里有 17 个苹果，先拿走 5 个，又放回 3 个，再拿走一半，"
    "最后篮子里还剩几个？只回答数字。"
)
RUNS = 5


def call(mode, timeout=60):
    body = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 1024},
    }
    if mode == "think0":
        body["generationConfig"]["thinkingConfig"] = {"thinkingBudget": 0}
    elif mode == "default_thoughts":
        body["generationConfig"]["thinkingConfig"] = {"includeThoughts": True}
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        BASE,
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
    except Exception as exc:  # noqa: BLE001
        elapsed = (time.perf_counter() - started) * 1000
        return {"mode": mode, "http": None, "error": f"{type(exc).__name__}: {str(exc)[:200]}",
                "elapsedMs": round(elapsed, 1)}
    elapsed = (time.perf_counter() - started) * 1000
    try:
        parsed = json.loads(raw)
    except Exception:  # noqa: BLE001
        parsed = {"_raw": raw.decode("utf-8", "replace")[:500]}
    row = {"mode": mode, "http": status, "elapsedMs": round(elapsed, 1)}
    if status != 200:
        row["errorMessage"] = str(parsed.get("error", {}).get("message", ""))[:300]
        return row
    usage = parsed.get("usageMetadata", {})
    cand = (parsed.get("candidates") or [{}])[0]
    parts = cand.get("content", {}).get("parts", [])
    texts = [p.get("text", "") for p in parts if p.get("text") and not p.get("thought")]
    thoughts = [p.get("text", "") for p in parts if p.get("thought")]
    row.update(
        {
            "thoughtsTokenCount": usage.get("thoughtsTokenCount"),
            "candidatesTokenCount": usage.get("candidatesTokenCount"),
            "promptTokenCount": usage.get("promptTokenCount"),
            "totalTokenCount": usage.get("totalTokenCount"),
            "finishReason": cand.get("finishReason"),
            "answer": "".join(texts)[:100],
            "textLen": len("".join(texts)),
            "thoughtPartCount": len(thoughts),
            "thoughtChars": sum(len(t) for t in thoughts),
        }
    )
    return row


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for mode in ("think0", "default", "default_thoughts"):
        for _ in range(RUNS):
            row = call(mode)
            row["model"] = MODEL
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False))
    (OUT / "thought-evidence-3.1-flash-lite.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
