"""agent decide 契约模型的边界测试（W4 冻结文档 §2 的逐项 ±1 校验）。"""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.agent_decide import (
    MAX_CONTEXT_TOTAL_CHARS,
    MAX_FACTS_TOTAL_CHARS,
    MAX_INPUT_SCHEMA_BYTES,
    MAX_MESSAGES_TOTAL_CHARS,
    MAX_OBSERVATIONS_TOTAL_CHARS,
    MAX_TOOLS,
    AgentDecideRequest,
    AgentDecideResponse,
    DecideDecision,
    DecideUsage,
)


def _tool(name: str = "search_project_knowledge", **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "name": name,
        "description": "在项目知识库中检索",
        "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}},
        "risk": "READ_ONLY",
    }
    payload.update(overrides)
    return payload


def _request(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "runId": "run-1",
        "attempt": 1,
        "turnNo": 0,
        "snapshotHash": "sha256:abc",
        "scenario": {"code": "PROJECT_RESEARCH", "category": "SCENARIO"},
        "goal": "研究项目架构",
        "locale": "zh-CN",
        "context": {"facts": [], "messages": [], "observations": []},
        "tools": [_tool()],
        "policy": {"allowWrite": False, "allowExternal": False},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
    }
    payload.update(overrides)
    return payload


def _response(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "decision": "FINAL",
        "usage": {"inputTokens": 1, "outputTokens": 2, "source": "PROVIDER"},
        "promptVersion": "2026-11-12.1",
        "modelAlias": "agent-main",
        "latencyMs": 120,
    }
    payload.update(overrides)
    return payload


def test_request_defaults_and_wire_shape() -> None:
    request = AgentDecideRequest.model_validate(_request())
    payload = request.model_dump(by_alias=True, mode="json")

    assert request.schema_version == 1
    assert request.scenario.prompt_pack == ""
    assert set(payload) == {
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
    assert set(payload["scenario"]) == {"code", "category", "promptPack"}
    assert set(payload["budget"]) == {"maxInputTokens", "maxOutputTokens", "deadlineMs"}


def test_idempotency_key_field_boundaries() -> None:
    assert AgentDecideRequest.model_validate(_request(runId="r")).run_id == "r"
    assert AgentDecideRequest.model_validate(_request(runId="r" * 128)).run_id
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(runId="r" * 129))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(runId=""))

    assert AgentDecideRequest.model_validate(_request(attempt=1)).attempt == 1
    assert AgentDecideRequest.model_validate(_request(attempt=50)).attempt == 50
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(attempt=0))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(attempt=51))

    assert AgentDecideRequest.model_validate(_request(turnNo=0)).turn_no == 0
    assert AgentDecideRequest.model_validate(_request(turnNo=500)).turn_no == 500
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(turnNo=501))

    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(snapshotHash="h" * 129))


def test_scenario_code_and_category() -> None:
    for code in ("PROJECT_RESEARCH", "AB", "WEEKLY_REPORT_2"):
        assert (
            AgentDecideRequest.model_validate(
                _request(scenario={"code": code, "category": "SCENARIO"})
            ).scenario.code
            == code
        )
    for code in ("project_research", "P", "1PROJECT", "A" * 65):
        with pytest.raises(ValidationError):
            AgentDecideRequest.model_validate(
                _request(scenario={"code": code, "category": "SCENARIO"})
            )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(scenario={"code": "PROJECT_RESEARCH", "category": "OTHER"})
        )
    assert (
        AgentDecideRequest.model_validate(
            _request(scenario={"code": "WEEKLY_REPORT", "category": "WORKFLOW"})
        ).scenario.category.value
        == "WORKFLOW"
    )


def test_goal_and_locale_boundaries() -> None:
    assert len(AgentDecideRequest.model_validate(_request(goal="g" * 4000)).goal) == 4000
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(goal="g" * 4001))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(goal=""))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(locale="zh_CN"))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(locale="z"))
    at_limit = "en-" + "a" * 8 + "-" + "b" * 8 + "-" + "c" * 8 + "-" + "d" * 5
    assert len(at_limit) == 35
    assert AgentDecideRequest.model_validate(_request(locale=at_limit)).locale == at_limit
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(locale=at_limit + "e"))


def test_scenario_prompt_pack_boundary() -> None:
    assert (
        len(
            AgentDecideRequest.model_validate(
                _request(
                    scenario={
                        "code": "PROJECT_RESEARCH",
                        "category": "SCENARIO",
                        "promptPack": "p" * 160,
                    }
                )
            ).scenario.prompt_pack
        )
        == 160
    )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(
                scenario={
                    "code": "PROJECT_RESEARCH",
                    "category": "SCENARIO",
                    "promptPack": "p" * 161,
                }
            )
        )


def test_context_fact_boundaries() -> None:
    at_limit = [f"f{index}" for index in range(20)]
    assert (
        len(AgentDecideRequest.model_validate(_request(context={"facts": at_limit})).context.facts)
        == 20
    )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"facts": [*at_limit, "extra"]}))
    assert AgentDecideRequest.model_validate(
        _request(context={"facts": ["f" * 2000]})
    ).context.facts
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"facts": ["f" * 2001]}))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"facts": [""]}))


def test_context_facts_total_boundary() -> None:
    ok = ["f" * 2000] * 7 + ["f" * 2000]
    assert sum(len(item) for item in ok) == MAX_FACTS_TOTAL_CHARS
    assert AgentDecideRequest.model_validate(_request(context={"facts": ok})).context.facts

    over = ["f" * 2000] * 7 + ["f" * 2000, "f"]
    assert sum(len(item) for item in over) == MAX_FACTS_TOTAL_CHARS + 1
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"facts": over}))


def test_context_message_boundaries_and_roles() -> None:
    assert (
        len(
            AgentDecideRequest.model_validate(
                _request(
                    context={
                        "messages": [
                            {"role": "USER", "content": "u"},
                            {"role": "ASSISTANT", "content": "a"},
                            {"role": "TOOL", "content": "t"},
                        ]
                    }
                )
            ).context.messages
        )
        == 3
    )
    at_limit = [
        {"role": "USER" if index % 2 == 0 else "ASSISTANT", "content": "m"} for index in range(20)
    ]
    assert (
        len(
            AgentDecideRequest.model_validate(
                _request(context={"messages": at_limit})
            ).context.messages
        )
        == 20
    )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"messages": [*at_limit, {"role": "USER", "content": "m"}]})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"messages": [{"role": "SYSTEM", "content": "x"}]})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"messages": [{"role": "USER", "content": ""}]})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"messages": [{"role": "USER", "content": "m" * 4001}]})
        )
    assert AgentDecideRequest.model_validate(
        _request(context={"messages": [{"role": "USER", "content": "m" * 4000}]})
    ).context.messages


def test_context_messages_total_boundary() -> None:
    ok = [
        {"role": "USER", "content": "m" * 4000},
        {"role": "ASSISTANT", "content": "m" * 4000},
        {"role": "TOOL", "content": "m" * 4000},
        {"role": "USER", "content": "m" * 4000},
        {"role": "ASSISTANT", "content": "m" * 4000},
        {"role": "TOOL", "content": "m" * 4000},
    ]
    assert AgentDecideRequest.model_validate(_request(context={"messages": ok})).context.messages

    over = [*ok, {"role": "USER", "content": "m"}]
    assert sum(len(item["content"]) for item in over) == MAX_MESSAGES_TOTAL_CHARS + 1
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"messages": over}))


def test_context_observation_boundaries() -> None:
    observations = [
        {"tool": "search_project_knowledge", "summary": f"summary-{index}"} for index in range(20)
    ]
    assert (
        len(
            AgentDecideRequest.model_validate(
                _request(context={"observations": observations})
            ).context.observations
        )
        == 20
    )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"observations": [*observations, observations[0]]})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"observations": [{"tool": "t" * 129, "summary": "s"}]})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"observations": [{"tool": "t", "summary": "s" * 2001}]})
        )


def test_context_observations_total_boundary() -> None:
    ok = [{"tool": "t", "summary": "s" * 2000} for _ in range(8)]
    assert sum(len(item["summary"]) for item in ok) == MAX_OBSERVATIONS_TOTAL_CHARS
    assert AgentDecideRequest.model_validate(_request(context={"observations": ok}))

    over = [*ok, {"tool": "t", "summary": "s"}]
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(context={"observations": over}))


def test_context_total_48kib_boundary() -> None:
    facts = ["f" * 2000 for _ in range(8)]
    messages = [
        {"role": "USER" if index % 2 == 0 else "ASSISTANT", "content": "m" * 4000}
        for index in range(6)
    ]
    observations = [{"tool": "t", "summary": "s" * 2000} for _ in range(4)] + [
        {"tool": "t", "summary": "s" * 1152}
    ]
    assert (
        sum(len(item) for item in facts)
        + sum(len(item["content"]) for item in messages)
        + sum(len(item["summary"]) for item in observations)
        == MAX_CONTEXT_TOTAL_CHARS
    )
    assert AgentDecideRequest.model_validate(
        _request(context={"facts": facts, "messages": messages, "observations": observations})
    )

    observations[-1] = {"tool": "t", "summary": "s" * 1153}
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(context={"facts": facts, "messages": messages, "observations": observations})
        )


def test_tools_boundaries_and_uniqueness() -> None:
    tools = [_tool(f"tool_{index}") for index in range(MAX_TOOLS)]
    assert len(AgentDecideRequest.model_validate(_request(tools=tools)).tools) == MAX_TOOLS
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[*tools, _tool("tool_extra")]))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[]))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(), _tool()]))
    assert AgentDecideRequest.model_validate(_request(tools=[_tool("t" * 128)])).tools
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool("t" * 129)]))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(description="d" * 1001)]))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(risk="ADMIN")]))


def test_input_schema_must_be_object_and_bounded() -> None:
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(inputSchema=[])]))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(inputSchema="{}")]))

    base = {"type": "object", "padding": ""}
    over = dict(base, padding="x" * MAX_INPUT_SCHEMA_BYTES)
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(tools=[_tool(inputSchema=over)]))

    padding = "x" * (MAX_INPUT_SCHEMA_BYTES - len(json.dumps(base, separators=(",", ":"))))
    at_limit = dict(base, padding=padding)
    encoded = json.dumps(at_limit, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    assert len(encoded.encode("utf-8")) == MAX_INPUT_SCHEMA_BYTES
    assert AgentDecideRequest.model_validate(_request(tools=[_tool(inputSchema=at_limit)])).tools


def test_policy_and_budget_boundaries() -> None:
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(policy={"allowWrite": False}))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(policy={"allowWrite": "maybe", "allowExternal": False})
        )

    assert AgentDecideRequest.model_validate(
        _request(budget={"maxInputTokens": 1, "maxOutputTokens": 1, "deadlineMs": 1})
    ).budget
    assert AgentDecideRequest.model_validate(
        _request(
            budget={
                "maxInputTokens": 2_000_000,
                "maxOutputTokens": 2_000_000,
                "deadlineMs": 600_000,
            }
        )
    ).budget
    for budget in (
        {"maxInputTokens": 0, "maxOutputTokens": 1, "deadlineMs": 1},
        {"maxInputTokens": 2_000_001, "maxOutputTokens": 1, "deadlineMs": 1},
        {"maxInputTokens": 1, "maxOutputTokens": 0, "deadlineMs": 1},
        {"maxInputTokens": 1, "maxOutputTokens": 1, "deadlineMs": 0},
        {"maxInputTokens": 1, "maxOutputTokens": 1, "deadlineMs": 600_001},
    ):
        with pytest.raises(ValidationError):
            AgentDecideRequest.model_validate(_request(budget=budget))


def test_root_deadline_and_schema_version_are_strict() -> None:
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(deadlineMs=1000))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(schemaVersion=True))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(schemaVersion=2))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(attempt=True))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(turnNo=True))
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(
            _request(budget={"maxInputTokens": True, "maxOutputTokens": 1, "deadlineMs": 1})
        )
    with pytest.raises(ValidationError):
        AgentDecideRequest.model_validate(_request(extra="x"))


def test_response_defaults_and_wire_shape() -> None:
    response = AgentDecideResponse.model_validate(_response())
    payload = response.model_dump(by_alias=True, mode="json")

    assert set(payload) == {
        "schemaVersion",
        "decision",
        "toolCall",
        "usage",
        "promptVersion",
        "modelAlias",
        "latencyMs",
    }
    assert payload["toolCall"] is None
    assert payload["usage"] == {"inputTokens": 1, "outputTokens": 2, "source": "PROVIDER"}
    # 明确：不存在任何 token 流字段。
    for forbidden in ("delta", "stream", "streaming", "token", "chunk"):
        assert forbidden not in payload


def test_response_decision_tool_call_invariants() -> None:
    call = {"name": "search_project_knowledge", "arguments": {"query": "q"}}
    assert AgentDecideResponse.model_validate(_response(decision="CALL_TOOL", toolCall=call))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(decision="CALL_TOOL"))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(decision="FINAL", toolCall=call))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(decision="ASK_USER", toolCall=call))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(decision="UNKNOWN"))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(
            _response(decision="CALL_TOOL", toolCall={"name": "x", "arguments": []})
        )


def test_response_usage_unknown_and_latency_null() -> None:
    payload = _response(latencyMs=None)
    del payload["usage"]
    response = AgentDecideResponse.model_validate(payload)
    assert response.usage == DecideUsage()
    assert response.usage.input_tokens is None
    assert response.usage.output_tokens is None
    assert response.usage.source.value == "UNKNOWN"
    assert response.latency_ms is None

    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(latencyMs=-1))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(latencyMs=True))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(_response(promptVersion="v1"))
    with pytest.raises(ValidationError):
        AgentDecideResponse.model_validate(
            _response(usage={"inputTokens": True, "outputTokens": 1, "source": "PROVIDER"})
        )


def test_all_four_decisions_are_accepted() -> None:
    for decision in DecideDecision:
        extra = (
            {"toolCall": {"name": "search_project_knowledge", "arguments": {}}}
            if decision is DecideDecision.CALL_TOOL
            else {}
        )
        response = AgentDecideResponse.model_validate(_response(decision=decision.value, **extra))
        assert response.decision is decision
