#!/usr/bin/env python3
"""Validate the frozen rerank fixture and deterministic 8/20/50 padding."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from ai_collab_worker.eval.rerank import load_rerank_set, pad_candidates

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SET = (
    PROJECT_ROOT
    / "ai-collab-ai-worker"
    / "tests"
    / "fixtures"
    / "rerank_set"
    / "rerank_baseline.v1.jsonl"
)


def validate(payload_path: Path, targets: list[int]) -> dict[str, Any]:
    cases = load_rerank_set(payload_path)
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "set": str(payload_path),
        "cases": len(cases),
        "caseIds": [case.id for case in cases],
        "originalCandidateCounts": {case.id: len(case.candidates) for case in cases},
        "tiers": [],
    }
    for target in targets:
        tier_cases: list[dict[str, Any]] = []
        for case in cases:
            padded = pad_candidates(case, target)
            candidate_ids = [candidate.id for candidate in padded.candidates]
            if len(candidate_ids) != len(set(candidate_ids)):
                raise ValueError(f"{case.id} target={target}: duplicate candidate id")
            if set(padded.labels) != set(candidate_ids):
                raise ValueError(f"{case.id} target={target}: labels do not cover candidates")
            if set(padded.supports) != set(candidate_ids):
                raise ValueError(f"{case.id} target={target}: supports do not cover candidates")
            for candidate in padded.candidates:
                if not candidate.text or len(candidate.text) > 2000:
                    raise ValueError(
                        f"{case.id} target={target}: invalid text length {len(candidate.text)}"
                    )
                if candidate.id.startswith("pad-"):
                    if padded.labels[candidate.id] != 0:
                        raise ValueError(f"{case.id} target={target}: padded label is not 0")
                    if padded.supports[candidate.id] is not False:
                        raise ValueError(f"{case.id} target={target}: padded support is not false")
            tier_cases.append(
                {
                    "caseId": case.id,
                    "original": len(case.candidates),
                    "padded": len(candidate_ids),
                    "padIds": [
                        candidate.id
                        for candidate in padded.candidates
                        if candidate.id.startswith("pad-")
                    ],
                }
            )
        counts = [item["padded"] for item in tier_cases]
        result["tiers"].append(
            {
                "target": target,
                "min": min(counts),
                "max": max(counts),
                "exactCountCases": sum(1 for count in counts if count == target),
                "cases": tier_cases,
            }
        )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", type=Path, default=DEFAULT_SET, dest="set_path")
    parser.add_argument("--targets", default="8,20,50")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    targets = [int(value) for value in args.targets.split(",") if value.strip()]
    result = validate(args.set_path, targets)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
