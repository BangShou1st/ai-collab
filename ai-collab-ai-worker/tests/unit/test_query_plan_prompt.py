"""query plan Prompt：从 PromptStore 读取、四个变量渲染、版本跟随 manifest。"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.cognition.query.prompt import (
    QUERY_PLAN_SYSTEM_KEY,
    QUERY_PLAN_USER_KEY,
    build_query_plan_prompt,
)
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.query_plan import QueryPlanRequest
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
VERSION = "2026-11-12.1"


@pytest.fixture
def store() -> PromptStore:
    return PromptStore(PROMPTS_DIR)


def _request(**overrides: object) -> QueryPlanRequest:
    payload: dict[str, object] = {
        "query": "检索链路改了哪些地方？",
        "locale": "zh-CN",
        "historySummary": "上一轮讨论了 W3 rerank。",
        "documentIds": ["doc-1", "doc-2"],
    }
    payload.update(overrides)
    return QueryPlanRequest.model_validate(payload)


def test_prompt_versions_follow_manifest(store: PromptStore) -> None:
    prompt = build_query_plan_prompt(store, _request())

    assert prompt.system_version == VERSION
    assert prompt.user_version == VERSION


def test_system_prompt_declares_json_only_contract(store: PromptStore) -> None:
    prompt = build_query_plan_prompt(store, _request())

    assert "JSON" in prompt.system
    assert "只输出单个 JSON object" in prompt.system
    assert "Markdown" in prompt.system
    assert "不要" in prompt.system
    # 形状必须与契约字段一致（camelCase 与三个 filter 键）。
    for token in (
        "rewrittenQuery",
        "subQueries",
        "documentIds",
        "docTypes",
        "dateRange",
        "confidence",
    ):
        assert token in prompt.system
    assert "{{" not in prompt.system


def test_user_prompt_renders_all_four_variables(store: PromptStore) -> None:
    request = _request(
        query="查询-UNIQUE-1",
        locale="en-US",
        historySummary="摘要-UNIQUE-2",
        documentIds=["doc-a", "doc-b"],
    )
    prompt = build_query_plan_prompt(store, request)

    assert "查询-UNIQUE-1" in prompt.user
    assert "en-US" in prompt.user
    assert "摘要-UNIQUE-2" in prompt.user
    assert '["doc-a","doc-b"]' in prompt.user
    assert "{{" not in prompt.user


def test_empty_document_ids_render_as_empty_array(store: PromptStore) -> None:
    prompt = build_query_plan_prompt(store, _request(documentIds=[]))

    assert "[]" in prompt.user


def test_load_current_returns_highest_version(store: PromptStore) -> None:
    document = load_current(store, QUERY_PLAN_SYSTEM_KEY)

    assert document.key == QUERY_PLAN_SYSTEM_KEY
    assert document.version == VERSION
    assert document.text


def test_load_current_unknown_key_is_prompt_unavailable(store: PromptStore) -> None:
    with pytest.raises(AppError) as excinfo:
        load_current(store, "query_plan.missing")

    error = excinfo.value
    assert error.code is ErrorCode.PROMPT_UNAVAILABLE
    assert error.status_code == 503
    assert error.retryable is False
    assert error.details is not None
    assert error.details["key"] == "query_plan.missing"


def test_render_template_replaces_once_and_rejects_missing_variable() -> None:
    assert render_template("A {{ a }} B {{b}}", {"a": "{{ b }}", "b": "x"}) == "A {{ b }} B x"
    with pytest.raises(ValueError, match="missing"):
        render_template("{{ missing }}", {})


def test_prompt_templates_are_only_read_through_store() -> None:
    """模板变量集合必须与 manifest 一致（PromptStore 已在取用时校验）。"""

    store = PromptStore(PROMPTS_DIR)
    system = load_current(store, QUERY_PLAN_SYSTEM_KEY)
    user = load_current(store, QUERY_PLAN_USER_KEY)

    assert system.variables == ()
    assert user.variables == ("documentIds", "historySummary", "locale", "query")
