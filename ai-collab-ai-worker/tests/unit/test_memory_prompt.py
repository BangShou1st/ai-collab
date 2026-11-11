"""Memory Prompt 渲染：只经 PromptStore、快照只暴露 facts/existingMemories、版本正确。"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.cognition.memory.prompt import (
    MEMORY_SYSTEM_KEY,
    MEMORY_USER_KEY,
    build_memory_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.memory_job import MemoryJobSnapshot
from ai_collab_worker.prompts.store import PromptStore

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
VERSION = "2026-11-12.1"


@pytest.fixture(scope="module")
def store() -> PromptStore:
    return PromptStore(PROMPTS_DIR)


def _snapshot() -> MemoryJobSnapshot:
    return MemoryJobSnapshot.model_validate(
        {
            "schemaVersion": 1,
            "jobId": "00000000-0000-4000-8000-000000000001",
            "projectId": "project-secret-ref",
            "attempt": 3,
            "leaseEpoch": 77,
            "snapshotHash": "sha256:snapshot-hash-value",
            "facts": [{"ref": "fact-1", "kind": "DECISION", "text": "团队决定统一网关。"}],
            "existingMemories": [
                {
                    "id": "44444444-4444-4444-8444-444444444444",
                    "type": "CONSTRAINT",
                    "title": "不写业务表",
                    "content": "Python 不写业务表。",
                }
            ],
        }
    )


def test_prompt_is_rendered_from_store(store: PromptStore) -> None:
    prompt = build_memory_prompt(store, _snapshot(), max_candidates=7)

    assert prompt.version == VERSION
    for text in (prompt.system, prompt.user):
        assert "{{" not in text
        assert "}}" not in text
    assert "团队决定统一网关。" in prompt.user
    assert "不写业务表" in prompt.user
    assert "fact-1" in prompt.user
    assert "memory:44444444-4444-4444-8444-444444444444" in prompt.user
    assert "7" in prompt.system
    assert "dedupeKey" in prompt.system


def test_prompt_does_not_expose_lease_metadata(store: PromptStore) -> None:
    snapshot = _snapshot()
    prompt = build_memory_prompt(store, snapshot, max_candidates=50)

    assert "leaseEpoch" not in prompt.user
    assert "snapshotHash" not in prompt.user
    assert "project-secret-ref" not in prompt.user
    assert str(snapshot.job_id) not in prompt.user


def test_prompt_size_stays_below_gateway_limit(store: PromptStore) -> None:
    assert (
        0
        < prompt_size_bytes(build_memory_prompt(store, _snapshot(), max_candidates=50))
        < (64 * 1024)
    )


def test_missing_prompt_key_fails_closed(tmp_path: Path) -> None:
    empty = PromptStore(tmp_path / "absent")

    with pytest.raises(AppError) as excinfo:
        build_memory_prompt(empty, _snapshot(), max_candidates=1)

    assert excinfo.value.code.value == "PROMPT_UNAVAILABLE"


def test_prompt_keys_are_registered(store: PromptStore) -> None:
    keys = {entry.key for entry in store.manifest().prompts}

    assert MEMORY_SYSTEM_KEY in keys
    assert MEMORY_USER_KEY in keys
