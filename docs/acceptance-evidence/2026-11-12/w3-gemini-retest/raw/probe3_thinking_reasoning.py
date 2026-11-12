#!/usr/bin/env python3
"""gemini-3.1-flash-lite thinking 行为证据：推理型提示 + includeThoughts。

节奏：每次调用间隔 6s（10 RPM），低于免费档 15 RPM 上限，避免 429 污染证据。
"""
import json
import time
import urllib.error
import urllib.request

with open("/data/.secrets/gemini.env", "r", encoding="utf-8") as handle:
    KEY = handle.read().strip().split("=", 1)[1]

MODEL = "gemini-3.1-flash-lite"
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
PROMPT = (
    "请逐步推理并只给出最终答案：某公司有三个部门，A 部门人数是 B 部门的 2 倍，"
    "C 部门比 A 部门少 7 人，三个部门共 83 人。求 A、B、C 各部门人数，"
    "按 A,B,C 顺序用逗号分隔输出三个数字。"
)
MODES = ("think0", "default", "default_thoughts", "think0_thoughts")
RUNS = 3
SPACING_S = 6.0


def call(mode):
    body = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 1024},
    }
    if mode.startswith("think0"):
        body["generationConfig"]["thinkingConfig"] = {"thinkingBudget": 0}
    if mode.endswith("thoughts"):
        body["generationConfig"].setdefault("thinkingConfig", {})["includeThoughts"] = True
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        URL, data=data, method="POST",
        headers={"x-goog-api-key": KEY, "Content-Type": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    except Exception as exc:  # noqa: BLE001
        return {"mode": mode, "http": None,
                "error": f"{type(exc).__name__}: {str(exc)[:200]}",
                "elapsedMs": round((time.perf_counter() - started) * 1000, 1)}
    elapsed = round((time.perf_counter() - started) * 1000, 1)
    payload = json.loads(raw)
    row = {"mode": mode, "http": status, "elapsedMs": elapsed}
    if status != 200:
        row["errorMessage"] = str(payload.get("error", {}).get("message", ""))[:300]
        return row
    usage = payload.get("usageMetadata", {})
    cand = (payload.get("candidates") or [{}])[0]
    parts = cand.get("content", {}).get("parts", [])
    texts = [p.get("text", "") for p in parts if p.get("text") and not p.get("thought")]
    thoughts = [p.get("text", "") for p in parts if p.get("thought")]
    row.update({
        "thoughtsTokenCount": usage.get("thoughtsTokenCount"),
        "candidatesTokenCount": usage.get("candidatesTokenCount"),
        "promptTokenCount": usage.get("promptTokenCount"),
        "totalTokenCount": usage.get("totalTokenCount"),
        "finishReason": cand.get("finishReason"),
        "answer": "".join(texts)[:80],
        "thoughtPartCount": len(thoughts),
        "thoughtChars": sum(len(t) for t in thoughts),
    })
    return row


rows = []
for run in range(RUNS):
    for mode in MODES:
        if rows:
            time.sleep(SPACING_S)
        row = call(mode)
        row.update({"run": run + 1, "model": MODEL})
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)

with open("/data/home/w3s15/raw/thought-evidence-reasoning.json", "w", encoding="utf-8") as handle:
    json.dump(rows, handle, ensure_ascii=False, indent=1)
