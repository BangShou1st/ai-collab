#!/usr/bin/env python3
"""Delete W5 C measurement projects via the public API (no direct DB delete)."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def main() -> int:
    projects_file = Path(sys.argv[1])
    output = Path(sys.argv[2])
    base = os.getenv("APP_BASE_URL", "http://app:8080").rstrip("/")
    token = Path(os.environ["W5_USER_JWT_FILE"]).read_text(encoding="utf-8").strip()
    projects = json.loads(projects_file.read_text(encoding="utf-8"))
    results = []
    for name in ("c", "cross"):
        project_id = projects[name]["id"]
        request = urllib.request.Request(
            f"{base}/api/v1/projects/{project_id}",
            method="DELETE",
            headers={"Authorization": "Bearer " + token},
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                results.append({"name": name, "id": project_id, "status": response.status})
        except urllib.error.HTTPError as error:
            results.append(
                {
                    "name": name,
                    "id": project_id,
                    "status": error.code,
                    "body": error.read().decode("utf-8", "replace")[:500],
                }
            )
    output.write_text(
        json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(results, ensure_ascii=False))
    return 0 if all(item["status"] == 204 for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
