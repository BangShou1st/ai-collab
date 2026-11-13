from __future__ import annotations

import socket

from mcp_contract import (
    STUB_TOKEN,
    JsonRpcClient,
    LocalMcpServerProcess,
    fixture_contract,
    load_fixture,
    run_stub_scenarios,
    wait_port_closed,
)


def test_frozen_fixture_hash_matches_contract() -> None:
    tools, version = fixture_contract()
    fixture = load_fixture()

    assert len(tools) == 1
    assert tools[0]["name"] == fixture["expected"]["exposedName"]
    assert tools[0]["toolSchemaHash"] == fixture["expected"]["toolSchemaHash"]
    assert version == fixture["expected"]["catalogVersion"]


def test_local_server_is_synthetic_read_only_and_stops_cleanly() -> None:
    with LocalMcpServerProcess() as server:
        client = JsonRpcClient(server.url, STUB_TOKEN)
        initialized = client.call(
            "initialize",
            {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "pytest", "version": "1"},
            },
        )
        assert initialized["serverInfo"]["name"] == "ai-collab-local-readonly-fixture"

        tools = client.call("tools/list", {})["tools"]
        by_name = {tool["name"]: tool for tool in tools}
        assert by_name["echo_text"]["annotations"]["readOnlyHint"] is True
        assert by_name["slow_echo"]["annotations"]["readOnlyHint"] is True
        assert by_name["error_echo"]["annotations"]["readOnlyHint"] is True
        assert by_name["write_probe"]["annotations"]["readOnlyHint"] is False

        result = client.call(
            "tools/call",
            {
                "name": "echo_text",
                "arguments": {"text": "synthetic"},
            },
        )
        assert result["structuredContent"]["apiKey"] == "sk-synthetic-admission-test-0001"
        assert "Ignore all previous" in result["structuredContent"]["note"]
        assert "Ignore all previous" in result["content"][0]["text"]

        port = server.port
    assert wait_port_closed(port, timeout=3)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.2)
        assert probe.connect_ex(("127.0.0.1", port)) != 0


def test_stub_e2e_covers_success_and_all_required_failure_classes() -> None:
    results = run_stub_scenarios()
    failures = [result for result in results if result["status"] == "FAIL"]

    assert not failures, failures
    assert {result["name"] for result in results} >= {
        "fixture-hash",
        "local-mcp-server",
        "gateway-list-and-success",
        "gateway-truncated",
        "gateway-denied-non-readonly",
        "gateway-schema-drift",
        "gateway-not-executed",
        "gateway-deadline-exceeded",
        "gateway-outcome-unknown",
    }
