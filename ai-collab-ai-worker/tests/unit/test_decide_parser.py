"""decide 解析/校验核心：四决策、工具白名单、arguments 形状与错误分类。"""

from __future__ import annotations

import json

import pytest

from ai_collab_worker.cognition.decide.parser import (
    REASON_EMPTY_OUTPUT,
    REASON_INVALID_TOOL_CALL,
    REASON_MISSING_DECISION,
    REASON_MISSING_TOOL_CALL,
    REASON_NOT_JSON,
    REASON_UNEXPECTED_TOOL_CALL,
    REASON_UNKNOWN_DECISION,
    REASON_UNKNOWN_TOOL,
    DecisionResult,
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.contract.agent_decide import (
    AgentDecideRequest,
    DecideDecision,
    DecideTool,
)


def _tools() -> tuple[DecideTool, ...]:
    request = AgentDecideRequest.model_validate(
        {
            "schemaVersion": 1,
            "runId": "run-1",
            "attempt": 1,
            "turnNo": 0,
            "snapshotHash": "sha256:abc",
            "scenario": {"code": "PROJECT_RESEARCH", "category": "SCENARIO"},
            "goal": "研究项目",
            "locale": "zh-CN",
            "context": {"facts": [], "messages": [], "observations": []},
            "tools": [
                {
                    "name": "search_project_knowledge",
                    "description": "检索",
                    "inputSchema": {"type": "object"},
                    "risk": "READ_ONLY",
                },
                {
                    "name": "get_project_snapshot",
                    "description": "快照",
                    "inputSchema": {"type": "object"},
                    "risk": "READ_ONLY",
                },
            ],
            "policy": {"allowWrite": False, "allowExternal": False},
            "budget": {"maxInputTokens": 100, "maxOutputTokens": 100, "deadlineMs": 1000},
        }
    )
    return request.tools


TOOLS = _tools()


def _parse(content: str) -> DecisionResult:
    return parse_decision(content, TOOLS)


def _error(content: str) -> DecisionValidationError:
    with pytest.raises(DecisionValidationError) as excinfo:
        _parse(content)
    return excinfo.value


def test_parses_all_four_decisions() -> None:
    for decision in DecideDecision:
        payload: dict[str, object] = {"decision": decision.value}
        if decision is DecideDecision.CALL_TOOL:
            payload["toolCall"] = {"name": "search_project_knowledge", "arguments": {}}
        result = _parse(json.dumps(payload, ensure_ascii=False))
        assert result.decision is decision
        if decision is not DecideDecision.CALL_TOOL:
            assert result.tool_call is None


def test_parses_valid_call_tool() -> None:
    content = json.dumps(
        {
            "decision": "CALL_TOOL",
            "toolCall": {
                "name": "search_project_knowledge",
                "arguments": {"query": "架构", "topK": 5},
            },
        },
        ensure_ascii=False,
    )
    result = _parse(content)

    assert result.decision is DecideDecision.CALL_TOOL
    assert result.tool_call.name == "search_project_knowledge"
    assert result.tool_call.arguments == {"query": "架构", "topK": 5}


def test_allows_markdown_fence_and_top_level_extra_keys() -> None:
    payload = {
        "decision": "CALL_TOOL",
        "toolCall": {"name": "get_project_snapshot", "arguments": {}},
        "reasoning": "需要快照",
    }
    result = _parse("```json\n" + json.dumps(payload, ensure_ascii=False) + "\n```")

    assert result.decision is DecideDecision.CALL_TOOL


@pytest.mark.parametrize(
    "content",
    ["not json", '{"decision": "CALL_TOOL",'],
)
def test_invalid_json_is_classified(content: str) -> None:
    error = _error(content)

    assert error.reason == REASON_NOT_JSON
    assert error.json_parsed is False
    assert error.invalid_tool_call is False


@pytest.mark.parametrize("content", ["", "   "])
def test_empty_output_is_classified(content: str) -> None:
    error = _error(content)

    assert error.reason == REASON_EMPTY_OUTPUT
    assert error.json_parsed is False


@pytest.mark.parametrize("content", ["[]", '"FINAL"', "42", "null"])
def test_non_object_is_not_json_parsed(content: str) -> None:
    error = _error(content)

    assert error.json_parsed is False
    assert error.reason.startswith("模型输出不是单个 JSON 对象")


def test_missing_decision_is_schema_violation() -> None:
    error = _error(json.dumps({"toolCall": {"name": "x", "arguments": {}}}))

    assert error.reason == REASON_MISSING_DECISION
    assert error.json_parsed is True
    assert error.invalid_tool_call is False


def test_unknown_decision_is_schema_violation() -> None:
    for value in ("DELEGATE", "call_tool", "", 1):
        error = _error(json.dumps({"decision": value}))
        assert error.reason == REASON_UNKNOWN_DECISION
        assert error.json_parsed is True


def test_call_tool_missing_tool_call() -> None:
    error = _error(json.dumps({"decision": "CALL_TOOL"}))

    assert error.reason == REASON_MISSING_TOOL_CALL
    assert error.invalid_tool_call is True
    assert error.invalid_tool_name is None


@pytest.mark.parametrize("tool_call", [[], "x", 1])
def test_call_tool_non_object_tool_call(tool_call: object) -> None:
    error = _error(json.dumps({"decision": "CALL_TOOL", "toolCall": tool_call}))

    assert error.reason == REASON_INVALID_TOOL_CALL
    assert error.invalid_tool_call is True


def test_call_tool_missing_or_empty_name() -> None:
    for tool_call in (
        {"arguments": {}},
        {"name": "", "arguments": {}},
        {"name": 3, "arguments": {}},
    ):
        error = _error(json.dumps({"decision": "CALL_TOOL", "toolCall": tool_call}))
        assert error.reason == REASON_INVALID_TOOL_CALL
        assert error.invalid_tool_call is True


def test_unknown_tool_name_is_reported_without_echoing_it_in_reason() -> None:
    error = _error(
        json.dumps(
            {
                "decision": "CALL_TOOL",
                "toolCall": {"name": "delete_all_tasks", "arguments": {}},
            }
        )
    )

    assert error.reason == REASON_UNKNOWN_TOOL
    assert error.invalid_tool_call is True
    assert error.invalid_tool_name == "delete_all_tasks"
    assert "delete_all_tasks" not in error.reason


@pytest.mark.parametrize("arguments", [[], "query=x", None, 1, True])
def test_call_tool_arguments_must_be_object(arguments: object) -> None:
    error = _error(
        json.dumps(
            {
                "decision": "CALL_TOOL",
                "toolCall": {"name": "search_project_knowledge", "arguments": arguments},
            }
        )
    )

    assert error.reason == REASON_INVALID_TOOL_CALL
    assert error.invalid_tool_call is True


def test_non_call_tool_with_tool_call_is_invalid() -> None:
    for decision in ("FINAL", "ASK_USER", "NO_OP"):
        error = _error(
            json.dumps(
                {
                    "decision": decision,
                    "toolCall": {"name": "search_project_knowledge", "arguments": {}},
                }
            )
        )
        assert error.reason == REASON_UNEXPECTED_TOOL_CALL
        assert error.json_parsed is True
        assert error.invalid_tool_call is False


def test_null_tool_call_is_allowed_for_non_call_tool() -> None:
    result = _parse(json.dumps({"decision": "FINAL", "toolCall": None}))

    assert result.decision is DecideDecision.FINAL


def test_tool_call_extra_keys_are_rejected() -> None:
    error = _error(
        json.dumps(
            {
                "decision": "CALL_TOOL",
                "toolCall": {
                    "name": "search_project_knowledge",
                    "arguments": {},
                    "reason": "x",
                },
            }
        )
    )

    assert error.reason == REASON_INVALID_TOOL_CALL
    assert error.invalid_tool_call is True


def test_error_never_contains_model_content() -> None:
    secret = "SECRET-MODEL-OUTPUT-7f"
    error = _error(
        json.dumps(
            {
                "decision": "CALL_TOOL",
                "toolCall": {"name": secret, "arguments": {}},
            }
        )
    )

    assert secret not in str(error)
    assert secret not in error.reason
    assert error.invalid_tool_name == secret
