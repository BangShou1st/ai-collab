"""agent decide 的跨语言线缆契约：字段名、四决策、无 token 流字段与快照一致性。"""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.agent_decide import AgentDecideRequest, AgentDecideResponse
from ai_collab_worker.contract.schema import SCHEMA_MODELS, render_schema


def _schema(name: str) -> dict[str, object]:
    return json.loads(render_schema(SCHEMA_MODELS[name]))


def _request() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "runId": "run-1",
        "attempt": 2,
        "turnNo": 5,
        "snapshotHash": "sha256:abc",
        "scenario": {
            "code": "PROJECT_RESEARCH",
            "category": "SCENARIO",
            "promptPack": "scenario.project_research@2026-11-11.1",
        },
        "goal": "研究项目架构",
        "locale": "zh-CN",
        "context": {
            "facts": ["事实"],
            "messages": [{"role": "USER", "content": "问题"}],
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
        "policy": {"allowWrite": False, "allowExternal": False},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
    }


REQUEST_FIELDS = {
    "schemaVersion",
    "runId",
    "attempt",
    "turnNo",
    "snapshotHash",
    "scenario",
    "goal",
    "locale",
    "context",
    "tools",
    "policy",
    "budget",
}

RESPONSE_FIELDS = {
    "schemaVersion",
    "decision",
    "toolCall",
    "usage",
    "promptVersion",
    "modelAlias",
    "latencyMs",
}


def test_request_wire_fields_are_exact() -> None:
    schema = _schema("agent-decide-request")

    assert set(schema["properties"]) == REQUEST_FIELDS
    assert schema["additionalProperties"] is False
    assert "deadlineMs" not in schema["properties"], "deadline 只在 budget 内，不接受根级字段"
    dumped = AgentDecideRequest.model_validate(_request()).model_dump(by_alias=True, mode="json")
    assert set(dumped) == REQUEST_FIELDS


def test_request_nested_shapes_match_frozen_table() -> None:
    definitions = _schema("agent-decide-request")["$defs"]

    assert set(definitions["DecideScenario"]["properties"]) == {"code", "category", "promptPack"}
    assert set(definitions["DecideContext"]["properties"]) == {
        "facts",
        "messages",
        "observations",
    }
    assert set(definitions["DecideMessage"]["properties"]) == {"role", "content"}
    assert set(definitions["DecideObservation"]["properties"]) == {"tool", "summary"}
    assert set(definitions["DecideTool"]["properties"]) == {
        "name",
        "description",
        "inputSchema",
        "risk",
    }
    assert set(definitions["DecidePolicy"]["properties"]) == {"allowWrite", "allowExternal"}
    assert set(definitions["DecideBudget"]["properties"]) == {
        "maxInputTokens",
        "maxOutputTokens",
        "deadlineMs",
    }
    assert set(definitions["MessageRole"]["enum"]) == {"USER", "ASSISTANT", "TOOL"}
    assert set(definitions["ToolRisk"]["enum"]) == {"READ_ONLY", "WRITE"}
    assert set(definitions["ScenarioCategory"]["enum"]) == {"SCENARIO", "WORKFLOW"}


def test_response_wire_fields_and_decision_enum() -> None:
    schema = _schema("agent-decide-response")

    assert set(schema["properties"]) == RESPONSE_FIELDS
    assert schema["additionalProperties"] is False
    assert set(schema["$defs"]["DecideDecision"]["enum"]) == {
        "CALL_TOOL",
        "FINAL",
        "ASK_USER",
        "NO_OP",
    }
    assert set(schema["$defs"]["ToolCall"]["properties"]) == {"name", "arguments"}
    assert set(schema["$defs"]["ToolCall"]["required"]) == {"name", "arguments"}
    assert set(schema["$defs"]["DecideUsage"]["properties"]) == {
        "inputTokens",
        "outputTokens",
        "source",
    }


def test_no_streaming_fields_in_response_schema() -> None:
    schema = _schema("agent-decide-response")
    serialized = json.dumps(schema, ensure_ascii=False).lower()

    for forbidden in ('"delta"', '"stream"', '"streaming"', '"chunk"', '"token"'):
        assert forbidden not in serialized


def test_request_rejects_root_deadline_and_unknown_fields() -> None:
    payload = _request()
    payload["deadlineMs"] = 30000
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(payload)

    payload = _request()
    payload["leaseEpoch"] = 3
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(payload)


def test_response_requires_tool_call_only_for_call_tool() -> None:
    base = {
        "schemaVersion": 1,
        "usage": {"inputTokens": 1, "outputTokens": 2, "source": "PROVIDER"},
        "promptVersion": "2026-11-12.1",
        "modelAlias": "agent-main",
        "latencyMs": 10,
    }
    call = {"name": "search_project_knowledge", "arguments": {"query": "q"}}

    assert AgentDecideResponse.model_validate({**base, "decision": "CALL_TOOL", "toolCall": call})
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate({**base, "decision": "CALL_TOOL"})
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate({**base, "decision": "FINAL", "toolCall": call})

    dumped = AgentDecideResponse.model_validate({**base, "decision": "FINAL"}).model_dump(
        by_alias=True, mode="json"
    )
    assert set(dumped) == RESPONSE_FIELDS
    assert dumped["toolCall"] is None
