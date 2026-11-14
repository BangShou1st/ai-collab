#!/usr/bin/env python3
"""S3 环境复核：健康、PromptPack 版本、/v1/jobs 在 static 模式下的 fail-closed 证据。"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

OUT = Path("/out")
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)
TOKEN = os.environ.get("AI_WORKER_SERVICE_TOKEN", "").strip()


def get(url: str, timeout: float = 10.0) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:  # noqa: S310
            return {"status": resp.status, "body": resp.read().decode("utf-8", "replace")[:2000]}
    except urllib.error.HTTPError as exc:
        return {"status": exc.code, "body": exc.read().decode("utf-8", "replace")[:2000]}
    except Exception as exc:  # noqa: BLE001
        return {"status": None, "error": type(exc).__name__}


def post(url: str, payload: dict, timeout: float = 10.0) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(  # noqa: S310
        url,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return {
                "status": resp.status,
                "elapsedMs": int((time.monotonic() - started) * 1000),
                "body": resp.read().decode("utf-8", "replace")[:2000],
            }
    except urllib.error.HTTPError as exc:
        return {
            "status": exc.code,
            "elapsedMs": int((time.monotonic() - started) * 1000),
            "body": exc.read().decode("utf-8", "replace")[:2000],
        }
    except Exception as exc:  # noqa: BLE001
        return {
            "status": None,
            "elapsedMs": int((time.monotonic() - started) * 1000),
            "error": type(exc).__name__,
        }


def main() -> int:
    from ai_collab_worker.prompts.render import load_current
    from ai_collab_worker.prompts.store import PromptStore

    store = PromptStore(Path("/app/prompts"))
    prompt_versions = {
        key: load_current(store, key).version
        for key in (
            "decide.system",
            "planning.draft.system",
            "memory.extract.system",
        )
    }

    health = {
        "appActuator8080": get("http://app:8080/actuator/health"),
        "appActuator8081": get("http://app:8081/actuator/health"),
        "workerHealthz": get("http://ai-worker-api:9100/healthz"),
        "workerReadyz": get("http://ai-worker-api:9100/readyz"),
    }

    jobs_payload = {
        "schemaVersion": 1,
        "jobId": str(uuid.uuid4()),
        "type": "MEMORY_EXTRACTION",
        "deadlineMs": 60000,
    }
    jobs_probe = post("http://ai-worker-api:9100/v1/jobs", jobs_payload)
    jobs_probe["note"] = (
        "static 令牌模式没有 job claims，require_claims 直接 503 fail-closed；"
        "这不是业务失败，而是本泳道不切 Ed25519 的预期行为。"
    )

    report = {
        "promptVersions": prompt_versions,
        "health": health,
        "jobsEndpointProbe": jobs_probe,
    }
    (RAW / "env-check.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
