#!/usr/bin/env python3
"""把 raw 证据组装成 aggregate-{8,20,50}.json（门禁 + 观测）。"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"

GATE = {"overheadP95Ms": 800, "overheadP99Ms": 1500, "failureRate": 0.01, "knownRatio": 0.99}


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
        reasons.append(
            f"failureRate={d.get('failureRate')} > {GATE['failureRate']}"
        )
    if d.get("modelLatencyKnownRatio") is None or d["modelLatencyKnownRatio"] < GATE["knownRatio"]:
        reasons.append(
            f"modelLatencyKnownRatio={d.get('modelLatencyKnownRatio')} < {GATE['knownRatio']}"
        )
    for key in ("overheadP95Ms", "overheadP99Ms"):
        value = d.get(key)
        if value is None:
            reasons.append(f"{key}=null（无 known 样本，无法证明门禁）")
        elif value > GATE[key]:
            reasons.append(f"{key}={value} > {GATE[key]}")
    return ("FAIL" if reasons else "PASS"), reasons


def diag_verdict(d: dict | None) -> tuple[str, list[str]]:
    if d is None:
        return "NOT_RUN", ["no diagnostic sample"]
    reasons: list[str] = []
    if d.get("failureRate", 1) > GATE["failureRate"]:
        reasons.append(f"failureRate={d.get('failureRate')} > {GATE['failureRate']}")
    if d.get("modelLatencyKnownRatio", 0) < GATE["knownRatio"]:
        reasons.append(f"knownRatio={d.get('modelLatencyKnownRatio')} < {GATE['knownRatio']}")
    for key in ("overheadP95Ms", "overheadP99Ms"):
        value = d.get(key)
        if value is None:
            reasons.append(f"{key}=null")
        elif value > GATE[key]:
            reasons.append(f"{key}={value} > {GATE[key]}")
    return ("PASS" if not reasons else "FAIL"), reasons


COMMON = [
    "tier",
    "deadlineMs",
    "runsPerCase",
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
        diag = load(f"diag-{tier}-30000-key2.json") or load(f"diag-{tier}-30000.json")
        gv, gr = gate_verdict(gate)
        dv, dr = diag_verdict(diag)
        aggregate = {
            "schemaVersion": 1,
            "measurement": "W3 measurement B (staging, real model)",
            "evidenceGrade": "LIVE_VERIFIED (staging)",
            "tier": tier,
            "model": "deepseek-ai/DeepSeek-V4.1-Flash-1017",
            "thinking": "ON (adapter 不下发 enable_thinking；渠道侧支持关闭)",
            "gate": {
                "config": {"deadlineMs": 3000, **GATE},
                "observed": pick(gate, COMMON),
                "verdict": gv,
                "reasons": gr,
            },
            "diagnostic": {
                "config": {"deadlineMs": 30000, "note": "放宽 deadline 仅为观测三行口径，不参与门禁"},
                "observed": pick(diag, COMMON),
                "verdict": dv,
                "reasons": dr,
            },
            "tierVerdict": gv,
            "note": (
                "门禁按生产默认 deadline 3000ms 判定；diagnostic 是放宽 deadline 的观测，"
                "不得替代门禁结论。"
            ),
        }
        if tier == 20:
            aborted = load("diag-20-30000.json")
            if aborted:
                aggregate["diagnostic"]["abortedRunKey1"] = pick(
                    aborted,
                    ["attempted", "samples", "failures", "failureRate", "429Count", "errorBreakdown", "wallSeconds"],
                )
        if tier == 50 and diag is None:
            aggregate["diagnostic"]["note"] = "未完成（受沙箱运行时/额度约束），门禁结论不受影响"
        out = ROOT / f"aggregate-{tier}.json"
        out.write_text(json.dumps(aggregate, ensure_ascii=False, indent=1), encoding="utf-8")
        print(out.name, "gate=", gv, gr, "diag=", dv, dr)


if __name__ == "__main__":
    main()
