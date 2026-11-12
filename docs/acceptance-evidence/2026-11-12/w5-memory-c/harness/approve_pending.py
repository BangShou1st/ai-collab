#!/usr/bin/env python3
"""Explicitly approve every pending W5 C candidate for one project."""

from __future__ import annotations

import json
import os
import sys
import urllib.request
from pathlib import Path


def main() -> int:
    project_id = sys.argv[1]
    output = Path(sys.argv[2])
    base = os.getenv("APP_BASE_URL", "http://app:8080").rstrip("/")
    jwt_file = Path(os.environ["W5_USER_JWT_FILE"])
    token = jwt_file.read_text(encoding="utf-8").strip()

    def call(method: str, path: str, body: dict | None = None):
        request = urllib.request.Request(
            base + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode())

    pending = call("GET", f"/api/v1/projects/{project_id}/agent/memory-candidates"
                         "?reviewStatus=PENDING_REVIEW")["data"]
    approvals = []
    for candidate in pending:
        approvals.append(
            call(
                "POST",
                f"/api/v1/projects/{project_id}/agent/memory-candidates/"
                f"{candidate['id']}/approve",
                {"reason": "W5 C explicit approval"},
            )["data"]
        )
    output.write_text(
        json.dumps({"pending": pending, "approvals": approvals}, ensure_ascii=False, indent=2)
        + "\n",
        encoding="utf-8",
    )
    print(f"approved={len(approvals)} pending={len(pending)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
