from __future__ import annotations

import socket
import sys
from pathlib import Path

from mcp_contract import load_fixture
from p2a_client import TlsJsonRpcClient, http_json
from p2a_process import ManagedProcess, find_free_port, wait_port, wait_port_closed
from p2a_seed import SERVER_PATH, expected_project_a_catalog
from p2a_tls import generate_tls_material


def test_static_catalog_keeps_frozen_echo_contract() -> None:
    fixture = load_fixture()
    catalog = expected_project_a_catalog()
    echo = next(
        tool for tool in catalog.tools if tool["name"] == fixture["expected"]["exposedName"]
    )

    assert echo["toolSchemaHash"] == fixture["expected"]["toolSchemaHash"]
    assert echo["description"] == "只读回显合成文本"


def test_tls_fixture_and_fault_statuses(tmp_path: Path) -> None:
    tls = generate_tls_material(tmp_path / "tls")
    port = find_free_port()
    with ManagedProcess(
        "fixture-tls",
        [
            sys.executable,
            str(SERVER_PATH),
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--tls-cert",
            str(tls.cert),
            "--tls-key",
            str(tls.key),
        ],
        SERVER_PATH.parents[3],
        tmp_path / "logs",
    ):
        wait_port(port, timeout=10)
        client = TlsJsonRpcClient(
            f"https://127.0.0.1:{port}/mcp",
            ca_file=tls.cert,
            timeout=5,
        )
        tools = client.call("tools/list", {})["tools"]
        by_name = {tool["name"]: tool for tool in tools}
        assert by_name["echo_text"]["annotations"]["readOnlyHint"] is True
        assert by_name["write_probe"]["annotations"]["readOnlyHint"] is False

        partial = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "partial",
                "method": "tools/call",
                "params": {"name": "partial_echo", "arguments": {"text": "p"}},
            },
            ca_file=tls.cert,
        )
        assert partial.status == 206

        rate = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "rate",
                "method": "tools/call",
                "params": {"name": "rate_limited", "arguments": {}},
            },
            ca_file=tls.cert,
        )
        assert rate.status == 429

        error = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "error",
                "method": "tools/call",
                "params": {"name": "server_error", "arguments": {}},
            },
            ca_file=tls.cert,
        )
        assert error.status == 500

        unsafe = client.call(
            "tools/call",
            {"name": "unsafe_payload", "arguments": {"text": "unsafe"}},
        )
        assert unsafe["structuredContent"]["binary_data"] == "AAECAwQ="
        assert unsafe["structuredContent"]["file_path"].endswith("secret.txt")

        changed = http_json(
            f"https://127.0.0.1:{port}/__control/schema?mode=changed",
            method="POST",
            ca_file=tls.cert,
        )
        assert changed.status == 200
        changed_tools = client.call("tools/list", {})["tools"]
        changed_echo = next(tool for tool in changed_tools if tool["name"] == "echo_text")
        assert changed_echo["description"] == "只读回显合成文本 v2"

    assert wait_port_closed(port, timeout=5)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.2)
        assert probe.connect_ex(("127.0.0.1", port)) != 0
