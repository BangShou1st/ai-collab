#!/usr/bin/env python3
"""One real AGENT model preflight through the Java gateway."""

from __future__ import annotations

import json
import os
import urllib.request


def main() -> int:
    token = os.environ["AI_WORKER_SERVICE_TOKEN"]
    body = {
        "schemaVersion": 1,
        "purpose": "AGENT",
        "systemPrompt": "只输出 JSON object：{\"ok\":true}",
        "userPrompt": "ping",
        "outputFormat": "PROMPT_JSON",
        "deadlineMs": 30000,
        "callId": "w5c-model-smoke-1",
    }
    request = urllib.request.Request(
        "http://app:8080/internal/ai/v1/model-calls",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=70) as response:
        payload = json.loads(response.read().decode())
    print(
        json.dumps(
            {
                "status": 200,
                "modelAlias": payload.get("modelAlias"),
                "latencyMs": payload.get("latencyMs"),
                "usage": payload.get("usage"),
                "contentChars": len(str(payload.get("content", ""))),
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
