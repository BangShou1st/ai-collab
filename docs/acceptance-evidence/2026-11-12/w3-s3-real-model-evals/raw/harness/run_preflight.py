#!/usr/bin/env python3
"""S3 预检：包路径、健康、Java 网关 deadline 上限与静态令牌连通性。

只做 1-2 次极小模型调用（间隔 5s），用于确认 gateway 是否接受 deadlineMs=60000。
不打印任何令牌/密钥。
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

import httpx

OUT = Path("/out")
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)

TOKEN = os.environ.get("AI_WORKER_SERVICE_TOKEN", "").strip()
GATEWAY = "http://app:8080/internal/ai/v1/model-calls"
WORKER = "http://ai-worker-api:9100"


def get(url: str, timeout: float = 10.0) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:  # noqa: S310
            return {"status": resp.status, "body": resp.read().decode("utf-8", "replace")[:2000]}
    except urllib.error.HTTPError as exc:
        return {"status": exc.code, "body": exc.read().decode("utf-8", "replace")[:2000]}
    except Exception as exc:  # noqa: BLE001
        return {"status": None, "error": type(exc).__name__}


def call_gateway(deadline_ms: int, call_id: str) -> dict:
    payload = {
        "schemaVersion": 1,
        "purpose": "KNOWLEDGE_CHAT",
        "systemPrompt": "你是连通性探针。只输出 JSON object。",
        "userPrompt": '只输出 {"ok":true}，不要解释。',
        "outputFormat": "PROMPT_JSON",
        "deadlineMs": deadline_ms,
        "callId": call_id,
    }
    started = time.monotonic()
    try:
        resp = httpx.post(
            GATEWAY,
            json=payload,
            headers={"Authorization": f"Bearer {TOKEN}", "X-Call-Id": call_id},
            timeout=httpx.Timeout(deadline_ms / 1000.0 + 5.0, connect=2.0),
        )
        elapsed = int((time.monotonic() - started) * 1000)
        try:
            body = resp.json()
        except ValueError:
            body = {"raw": resp.text[:500]}
        return {"httpStatus": resp.status_code, "elapsedMs": elapsed, "body": body}
    except Exception as exc:  # noqa: BLE001
        return {
            "httpStatus": None,
            "elapsedMs": int((time.monotonic() - started) * 1000),
            "errorType": type(exc).__name__,
        }


def main() -> int:
    import ai_collab_worker
    import ai_collab_worker.eval.decide as decide_mod

    print("python", os.sys.version.split()[0])
    print("package", ai_collab_worker.__file__)
    print("decide_eval", decide_mod.__file__)
    print("token_present", bool(TOKEN))

    health = {
        "appActuator8080": get("http://app:8080/actuator/health"),
        "appActuator8081": get("http://app:8081/actuator/health"),
        "workerHealthz": get(f"{WORKER}/healthz"),
        "workerReadyz": get(f"{WORKER}/readyz"),
    }
    print("health", json.dumps(health, ensure_ascii=False))
    (RAW / "preflight-health.json").write_text(
        json.dumps(health, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    # 1) 任务书口径 60000：只验证网关是否接受，通常会被 30000 上限直接拒绝（不耗模型配额）
    first = call_gateway(60000, "preflight-deadline-60000")
    print("gateway deadline60000", json.dumps(first, ensure_ascii=False)[:800])
    (RAW / "preflight-deadline-60000.json").write_text(
        json.dumps(first, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    time.sleep(5.0)
    # 2) 网关上限 30000：真模型小样本，验证静态令牌 + provider 链路
    second = call_gateway(30000, "preflight-deadline-30000")
    print("gateway deadline30000", json.dumps(second, ensure_ascii=False)[:1200])
    (RAW / "preflight-deadline-30000.json").write_text(
        json.dumps(second, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    verdict = {
        "deadline60000HttpStatus": first.get("httpStatus"),
        "deadline30000HttpStatus": second.get("httpStatus"),
        "gatewayAccepts60000": first.get("httpStatus") == 200,
        "gatewayAccepts30000": second.get("httpStatus") == 200,
    }
    print("preflight", json.dumps(verdict, ensure_ascii=False))
    (RAW / "preflight-verdict.json").write_text(
        json.dumps(verdict, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
