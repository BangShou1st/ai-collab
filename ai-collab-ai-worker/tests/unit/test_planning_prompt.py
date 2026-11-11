"""Planning Prompt 渲染：只经 PromptStore、变量正确替换、版本取 manifest 最高版本。"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.cognition.planning.prompt import (
    PLANNING_SYSTEM_KEY,
    PLANNING_USER_KEY,
    build_planning_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.contract.planning import PlanningDraftRequest
from ai_collab_worker.prompts.store import PromptStore

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
VERSION = "2026-11-12.1"
MEMBER_ID = "11111111-1111-4111-8111-111111111111"


@pytest.fixture(scope="module")
def store() -> PromptStore:
    return PromptStore(PROMPTS_DIR)


def _request(**overrides: object) -> PlanningDraftRequest:
    payload: dict[str, object] = {
        "goal": "为 Q4 迭代排出里程碑与任务。",
        "locale": "zh-CN",
        "projectFacts": {
            "projectName": "AI Collab",
            "description": "协作平台",
            "members": [{"id": MEMBER_ID, "name": "张伟"}],
            "existingMilestoneTitles": ["需求冻结"],
            "existingTaskTitles": ["接口联调"],
        },
        "template": "ITERATION",
        "constraints": {"maxTasks": 7, "maxMilestones": 3, "startDate": "2026-11-20"},
    }
    payload.update(overrides)
    return PlanningDraftRequest.model_validate(payload)


def test_prompt_is_rendered_from_store(store: PromptStore) -> None:
    prompt = build_planning_prompt(store, _request())

    assert prompt.version == VERSION
    for text in (prompt.system, prompt.user):
        assert "{{" not in text
        assert "}}" not in text
    assert "为 Q4 迭代排出里程碑与任务。" in prompt.user
    assert "AI Collab" in prompt.user
    assert "fact:project" in prompt.user
    assert f"member:{MEMBER_ID}" in prompt.user
    assert "milestone:0" in prompt.user
    assert "task:0" in prompt.user
    assert "1–3" in prompt.system  # maxMilestones
    assert "1–7" in prompt.system  # maxTasks
    assert "2026-11-20" in prompt.system
    assert "ITERATION" in prompt.system
    assert "zh-CN" in prompt.system


def test_prompt_hides_absent_window_as_empty(store: PromptStore) -> None:
    prompt = build_planning_prompt(store, _request(constraints={"maxTasks": 2, "maxMilestones": 1}))

    assert "起点 ，终点 ；" in prompt.system


def test_prompt_size_stays_below_gateway_limit(store: PromptStore) -> None:
    prompt = build_planning_prompt(store, _request())

    assert 0 < prompt_size_bytes(prompt) < 64 * 1024


def test_missing_prompt_key_fails_closed_with_prompt_unavailable(tmp_path: Path) -> None:
    empty = PromptStore(tmp_path / "absent")

    with pytest.raises(AppError) as excinfo:
        build_planning_prompt(empty, _request())

    assert excinfo.value.code.value == "PROMPT_UNAVAILABLE"


def test_prompt_keys_are_registered_in_manifest(store: PromptStore) -> None:
    keys = {entry.key for entry in store.manifest().prompts}

    assert PLANNING_SYSTEM_KEY in keys
    assert PLANNING_USER_KEY in keys
