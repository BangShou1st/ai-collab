#!/usr/bin/env python3
"""复核三个 aggregate 的 sha256 封印与证据标签（只读）。"""
from __future__ import annotations

import json

from ai_collab_worker.eval.canonical import canonicalize_document, digest

for name in ("decide", "planning", "memory"):
    path = f"/out/aggregate-{name}.json"
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    payload = {key: value for key, value in document.items() if key != "sha256"}
    calculated = digest(canonicalize_document(payload))
    print(
        name,
        "sha256_ok=",
        calculated == document["sha256"],
        document["sha256"][:16],
        "evidence=",
        document.get("evidence"),
        "qualityClaim=",
        document.get("qualityClaim"),
        "confidence=",
        document["aggregate"]["confidence"],
    )
