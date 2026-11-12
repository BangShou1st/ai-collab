#!/usr/bin/env python3
"""把 raw 证据组装成 aggregate-{8,20,50}.json（门禁 + 观测）。

门禁阈值与 S1 完全一致，不得放宽。
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"

GATE = {"overheadP95Ms": 800, "overheadP99Ms": 1500, "failureRate": 0.01, "knownRatio": 0.99}
MODEL = "gemini-3.1-flash-lite"
PROVIDER = "GEMINI (generativelanguage.googleapis.com)"


def load(name: str) -> dict | None:
    path = RAW / name
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def pick(d: dict | None, keys: list[str]) -> dict | None:
    if d is None:
        return None
    return {k: d.get(k) for k in keys}


def gate_verdict(d: dict | None) -> tuple[str, list[str]]:
    if d is None:
        return "NOT_RUN", ["no gate sample"]
    reasons: list[str] = []
    if d.get("failureRate") is None or d["failureRate"] > GATE["failureRate"]:
        reasons.append(f"failureRate={d.get('failureRate')} > {GATE['failureRate']}")
    if d.get("modelLatencyKnownRatio") is None or d["modelLatencyKnownRatio"] < GATE["knownRatio"]:
        reasons.append(f"modelLatencyKnownRatio={d.get('modelLatencyKnownRatio')} < {GATE['knownRatio']}")
    for key in ("overheadP95Ms", "overheadP99Ms"):
        value = d.get(key)
        if value is None:
            reasons.append(f"{key}=null（无 known 样本，无法证明门禁）")
        elif value > GATE[key]:
            reasons.append(f"{key}={value} > {GATE[key]}")
    return ("FAIL" if reasons else "PASS"), reasons


COMMON = [
    "tier",
    "deadlineMs",
    "runsPerCase",
    "minIntervalMs",
    "cases",
    "attempted",
    "samples",
    "failures",
    "failureRate",
    "modelKnownSamples",
    "modelLatencyKnownRatio",
    "429Count",
    "deadlineExceededCount",
    "errorBreakdown",
    "overheadP50Ms",
    "overheadP95Ms",
    "overheadP99Ms",
    "modelP50Ms",
    "modelP95Ms",
    "modelP99Ms",
    "totalP50Ms",
    "totalP95Ms",
    "totalP99Ms",
    "confidence",
    "wallSeconds",
]


def main() -> None:
    for tier in (8, 20, 50):
        gate = load(f"gate-{tier}-3000.json")
        obs = load(f"obs-{tier}-3000-60.json")
        diag = load(f"diag-{tier}-30000.json")
        if diag is None and tier == 8:
            diag = load("preflight-20.json")
        gv, gr = gate_verdict(gate)
        aggregate = {
            "schemaVersion": 1,
            "measurement": "S1.5 Gemini retest (staging, real provider, thinking disabled)",
            "evidenceGrade": "LIVE_VERIFIED (staging)",
            "tier": tier,
            "provider": PROVIDER,
            "model": MODEL,
            "thinking": "OFF (capabilities 不含 THINKING -> adapter 下发 thinkingConfig.thinkingBudget=0)",
            "gate": {
                "config": {"deadlineMs": 3000, **GATE},
                "observed": pick(gate, COMMON),
                "rawSamples": (gate or {}).get("rawSamples"),
                "verdict": gv,
                "reasons": gr,
            },
            "observation": {
                "config": {
                    "deadlineMs": 3000,
                    "note": "仅当三档门禁全部 <=1% 失败时追加，同 3s 生产门禁口径",
                },
                "observed": pick(obs, COMMON),
                "rawSamples": (obs or {}).get("rawSamples"),
                "verdict": gate_verdict(obs)[0] if obs else "NOT_RUN",
                "reasons": gate_verdict(obs)[1] if obs else ["no observation sample"],
            },
            "diagnostic": {
                "config": {
                    "deadlineMs": 30000,
                    "note": "放宽 deadline 的少量观测，仅用于三行口径与根因归因，不替代门禁",
                },
                "observed": pick(diag, COMMON),
                "rawSamples": (diag or {}).get("rawSamples"),
                "failuresDetail": (diag or {}).get("failuresDetail"),
            },
            "tierVerdict": gv,
            "note": "门禁按生产默认 deadline 3000ms 判定；429/超时/空响应均计入失败。",
        }
        out = ROOT / f"aggregate-{tier}.json"
        out.write_text(json.dumps(aggregate, ensure_ascii=False, indent=1), encoding="utf-8")
        print(out.name, "gate=", gv, gr, "obs=", aggregate["observation"]["verdict"])


if __name__ == "__main__":
    main()
