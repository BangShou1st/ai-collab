"""decide Prompt：模板变量渲染、四决策形状、纠错指令与版本来源。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.cognition.decide.prompt import (
    DECIDE_SYSTEM_KEY,
    DECIDE_USER_KEY,
    MAX_CORRECTION_BYTES,
    build_correction_prompt,
    build_decide_prompt,
    prompt_size_bytes,
)
from ai_collab_worker.contract.agent_decide import AgentDecideRequest
from ai_collab_worker.prompts.render import load_current
from ai_collab_worker.prompts.store import PromptStore

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
VERSION = "2026-11-12.2"


@pytest.fixture
def store() -> PromptStore:
    return PromptStore(PROMPTS_DIR)


def _request(**overrides: object) -> AgentDecideRequest:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "runId": "run-1",
        "attempt": 2,
        "turnNo": 5,
        "snapshotHash": "sha256:abc",
        "scenario": {"code": "PROJECT_RESEARCH", "category": "SCENARIO", "promptPack": "pp@1"},
        "goal": "研究目标-UNIQUE",
        "locale": "zh-CN",
        "context": {
            "facts": ["事实一"],
            "messages": [{"role": "USER", "content": "用户消息"}],
            "observations": [{"tool": "search_project_knowledge", "summary": "摘要"}],
        },
        "tools": [
            {
                "name": "search_project_knowledge",
                "description": "检索",
                "inputSchema": {"type": "object"},
                "risk": "READ_ONLY",
            }
        ],
        "policy": {"allowWrite": False, "allowExternal": True},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
    }
    payload.update(overrides)
    return AgentDecideRequest.model_validate(payload)


def test_version_comes_from_system_prompt_manifest(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    assert prompt.version == VERSION
    assert store.get(DECIDE_SYSTEM_KEY, VERSION).version == prompt.version


def test_historical_system_prompt_remains_version_readable(store: PromptStore) -> None:
    """发布 .2 后，.1 必须仍可按版本读取；当前 Prompt 仍由最高版本 .2 提供。"""

    historical = store.get(DECIDE_SYSTEM_KEY, "2026-11-12.1")
    current = load_current(store, DECIDE_SYSTEM_KEY)
    versions = [
        entry.version for entry in store.manifest().prompts if entry.key == DECIDE_SYSTEM_KEY
    ]

    assert historical.version == "2026-11-12.1"
    assert "CALL_TOOL：需要工具补充事实时使用" in historical.text
    assert "先判断 NO_OP 与 ASK_USER" not in historical.text
    assert current.version == VERSION
    assert "先判断 NO_OP 与 ASK_USER" in current.text
    assert versions == ["2026-11-12.1", VERSION]


def test_system_prompt_declares_json_only_and_four_decisions(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    assert "只输出单个 JSON object" in prompt.system
    assert "Markdown" in prompt.system
    assert "不要解释" in prompt.system
    for decision in ("CALL_TOOL", "FINAL", "ASK_USER", "NO_OP"):
        assert decision in prompt.system
    assert "toolCall" in prompt.system
    assert "arguments" in prompt.system
    assert "{{" not in prompt.system


def test_system_prompt_puts_no_op_and_ask_user_before_tool_choice(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    no_op = prompt.system.index("NO_OP：")
    ask_user = prompt.system.index("ASK_USER：")
    call_tool = prompt.system.index("CALL_TOOL：")
    final = prompt.system.index("FINAL：")
    assert no_op < ask_user < call_tool < final
    assert "工具可用" in prompt.system
    assert "不要用工具替用户猜范围" in prompt.system
    assert "不要用 get_project_snapshot 等工具制造动作" in prompt.system
    assert "缺少工具必填参数" in prompt.system


def test_system_prompt_contains_all_four_json_shapes(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    for shape in (
        '{"decision":"CALL_TOOL","toolCall":{"name":"工具名","arguments":{}}}',
        '{"decision":"FINAL"}',
        '{"decision":"ASK_USER"}',
        '{"decision":"NO_OP"}',
    ):
        assert shape in prompt.system


def test_system_prompt_does_not_hardcode_frozen_case_text(store: PromptStore) -> None:
    """Policy 必须是通用规则，不能把冻结集 id / goal 写死进 Prompt。"""

    prompt = build_decide_prompt(store, _request())
    for marker in (
        "d-007",
        "d-008",
        "d-015",
        "d-016",
        "帮我整理一下会议纪要。",
        "Which project should I summarize?",
        "生成周报前需要确认范围。",
        "本轮没有新的用户输入。",
    ):
        assert marker not in prompt.system


def test_system_prompt_renders_all_six_variables(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    assert "PROJECT_RESEARCH" in prompt.system
    assert "研究目标-UNIQUE" in prompt.system
    assert "zh-CN" in prompt.system
    assert "false" in prompt.system
    assert "true" in prompt.system
    assert "search_project_knowledge" in prompt.system


def test_user_prompt_renders_context_and_tools_json(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    assert "事实一" in prompt.user
    assert "用户消息" in prompt.user
    assert "search_project_knowledge" in prompt.user
    assert "{{" not in prompt.user


def test_allow_flags_are_rendered_as_json_booleans(store: PromptStore) -> None:
    allow = build_decide_prompt(store, _request(policy={"allowWrite": True, "allowExternal": True}))
    deny = build_decide_prompt(
        store, _request(policy={"allowWrite": False, "allowExternal": False})
    )

    markers = ("allowWrite", "allowExternal")
    for marker in markers:
        assert f"{marker}：true" in allow.system
        assert f"{marker}：false" in deny.system


def test_correction_prompt_is_fixed_and_bounded(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())
    corrected = build_correction_prompt(prompt.user, "toolCall.name 不在本次下发的 tools 内")

    assert corrected.startswith(prompt.user)
    assert "<CORRECTION>" in corrected
    assert "toolCall.name 不在本次下发的 tools 内" in corrected
    assert "CALL_TOOL" in corrected
    assert "search_project_knowledge" not in corrected.split("<CORRECTION>")[1]
    assert len(corrected.encode("utf-8")) - len(prompt.user.encode("utf-8")) <= MAX_CORRECTION_BYTES


def test_prompt_size_counts_utf8_bytes(store: PromptStore) -> None:
    prompt = build_decide_prompt(store, _request())

    assert prompt_size_bytes(prompt) == len(prompt.system.encode("utf-8")) + len(
        prompt.user.encode("utf-8")
    )
    assert prompt_size_bytes(prompt) > len(prompt.system) + len(prompt.user)


def test_tools_json_is_compact_and_contains_input_schema(store: PromptStore) -> None:
    request = _request()
    prompt = build_decide_prompt(store, request)
    expected = json.dumps(
        [tool.model_dump(by_alias=True, mode="json") for tool in request.tools],
        ensure_ascii=False,
        separators=(",", ":"),
    )

    assert expected in prompt.user


def test_template_variables_match_manifest(store: PromptStore) -> None:
    system = store.get(DECIDE_SYSTEM_KEY, VERSION)
    user = load_current(store, DECIDE_USER_KEY)

    assert system.variables == (
        "allowExternal",
        "allowWrite",
        "goal",
        "locale",
        "scenarioCode",
        "toolsJson",
    )
    assert user.variables == ("contextJson", "toolsJson")
