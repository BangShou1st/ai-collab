#!/usr/bin/env python3
"""S3.2 planning 端点补充冒烟：请求 deadlineMs 改为 30000（贴合 Java 网关上限）。

首个 golden case 的默认 deadlineMs=60000 会被 Java 内部网关 422 拒绝（见
raw/planning-smoke.json）；本脚本只把请求体 deadlineMs 改成 30000，验证端点本身可用。
不替代 planning 指标。
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import httpx

from ai_collab_worker.eval.planning import load_planning_set

FIXTURE = Path("/repo/ai-collab-ai-worker/tests/fixtures/planning_set/planning_baseline.v1.jsonl")
RAW = Path("/out/raw")
WORKER_URL = "http://ai-worker-api:9100"
TOKEN = os.environ.get("AI_WORKER_SERVICE_TOKEN", "").strip()
DEADLINE_MS = 30000


def main() -> int:
    cases = sorted(
        (case for case in load_planning_set(FIXTURE) if case.subset == "golden"),
        key=lambda case: case.id,
    )
    case = cases[0]
    body = case.request.model_dump(by_alias=True, mode="json")
    body["schemaVersion"] = 1
    body["deadlineMs"] = DEADLINE_MS
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")

    started = time.monotonic()
    record: dict[str, object] = {"caseId": case.id, "deadlineMs": DEADLINE_MS}
    try:
        response = httpx.post(
            f"{WORKER_URL}/v1/planning/draft",
            content=payload,
            headers={
                "Authorization": f"Bearer {TOKEN}",
                "Content-Type": "application/json",
            },
            timeout=httpx.Timeout(DEADLINE_MS / 1000.0 + 15.0, connect=2.0),
        )
        elapsed = int((time.monotonic() - started) * 1000)
        try:
            response_body = response.json()
        except ValueError:
            response_body = {"raw": response.text[:1000]}
        record.update(httpStatus=response.status_code, elapsedMs=elapsed, body=response_body)
    except Exception as exc:  # noqa: BLE001
        record.update(
            httpStatus=None,
            elapsedMs=int((time.monotonic() - started) * 1000),
            errorType=type(exc).__name__,
        )

    (RAW / "planning-smoke-30000.json").write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    summary = {
        "caseId": record["caseId"],
        "httpStatus": record.get("httpStatus"),
        "elapsedMs": record.get("elapsedMs"),
        "usage": (record.get("body") or {}).get("usage")
        if isinstance(record.get("body"), dict)
        else None,
        "latencyMs": (record.get("body") or {}).get("latencyMs")
        if isinstance(record.get("body"), dict)
        else None,
        "warnings": len((record.get("body") or {}).get("warnings") or [])
        if isinstance(record.get("body"), dict)
        else None,
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
