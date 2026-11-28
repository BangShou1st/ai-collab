"""P3 金标准夹具加载器（``tests/fixtures/frozen_set/p3_*.v1.jsonl``）。

夹具由 Java 侧 ``P3GoldenQueryUnderstandingExportTest`` 导出（P3 契约 §8.4/§8.5），
冻结后不得改动；本模块只读。
"""

from __future__ import annotations

import datetime as dt
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.retrieval.query.models import ConversationMessage

FIXTURES_DIR: Final[Path] = Path(__file__).resolve().parents[1] / "fixtures" / "frozen_set"

ROUTING_FILE = "p3_routing_golden.v1.jsonl"
UNDERSTANDING_FILE = "p3_understanding_golden.v1.jsonl"
SAFETY_FILE = "p3_safety_golden.v1.jsonl"
CONTEXT_FILE = "p3_context_golden.v1.jsonl"


def read_jsonl(name: str) -> list[dict[str, Any]]:
    path = FIXTURES_DIR / name
    if not path.is_file():
        msg = f"P3 金标准夹具缺失: {path}"
        raise AssertionError(msg)
    records: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                records.append(json.loads(line))
    return records


def messages_of(record: dict[str, Any]) -> list[ConversationMessage]:
    """把夹具里的 history 行还原成 :class:`ConversationMessage`。"""

    return [
        ConversationMessage(
            role=entry["role"],
            content=entry["content"],
            created_at=_parse_datetime(entry.get("createdAt")),
            insufficient_evidence=bool(entry.get("insufficientEvidence", False)),
        )
        for entry in record["messages"]
    ]


def _parse_datetime(raw: str | None) -> dt.datetime | None:
    if raw is None:
        return None
    return dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))


def iter_routing() -> Iterator[dict[str, Any]]:
    yield from read_jsonl(ROUTING_FILE)


def iter_understanding() -> Iterator[dict[str, Any]]:
    yield from read_jsonl(UNDERSTANDING_FILE)


def iter_safety(kind: str) -> Iterator[dict[str, Any]]:
    for record in read_jsonl(SAFETY_FILE):
        if record["kind"] == kind:
            yield record


def iter_context(kind: str) -> Iterator[dict[str, Any]]:
    for record in read_jsonl(CONTEXT_FILE):
        if record["kind"] == kind:
            yield record


__all__ = [
    "CONTEXT_FILE",
    "FIXTURES_DIR",
    "ROUTING_FILE",
    "SAFETY_FILE",
    "UNDERSTANDING_FILE",
    "iter_context",
    "iter_routing",
    "iter_safety",
    "iter_understanding",
    "messages_of",
    "read_jsonl",
]
