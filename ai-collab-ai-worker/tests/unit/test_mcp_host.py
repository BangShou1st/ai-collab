"""Python MCP Host 客户端：请求形状、Bearer、错误 kind、超时与 URL 边界。"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx
import pytest

from ai_collab_worker.clients.mcp_host import McpHostClient
from ai_collab_worker.contract.mcp import (
    MCP_GATEWAY_PATH,
    McpDiscoveredTool,
    McpErrorKind,
    McpHostError,
    McpOutcome,
    normalize_catalog,
)

BASE_URL = "http://gateway.test:8080"
TOKEN_A = "unit-token-a"
TOKEN_B = "unit-token-b"
PROJECT_ROOT = Path(__file__).resolve().parents[3]
FIXTURE = PROJECT_ROOT / "tests" / "fixtures" / "mcp" / "catalog-v1.json"

Handler = Callable[[httpx.Request], httpx.Response]


def _client(handler: Handler, *, base_url: str = BASE_URL) -> McpHostClient:
    return McpHostClient(
        base_url=base_url,
        timeout_seconds=2,
        transport=httpx.MockTransport(handler),
    )


def _fixture_catalog_payload() -> dict[str, Any]:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    tools = [McpDiscoveredTool.model_validate(tool) for tool in fixture["tools"]]
    catalog = normalize_catalog(connection_code=fixture["connectionCode"], tools=tools)
    return catalog.model_dump(by_alias=True, mode="json")


def _initialize_payload() -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "protocolVersion": "2025-03-26",
        "serverInfo": {"name": "ai-collab-mcp-gateway", "version": "p1"},
        "capabilities": {"tools": {"listChanged": False}},
    }


def _call_payload() -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "catalogVersion": _fixture_catalog_payload()["catalogVersion"],
        "outcome": "SUCCEEDED",
        "untrusted": True,
        "data": {"text": "hello"},
        "truncated": False,
        "latencyMs": 7,
    }


def test_initialize_request_shape_and_response() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "init-1", "result": _initialize_payload()},
        )

    async def scenario() -> Any:
        client = _client(handler)
        try:
            return await client.initialize(
                bearer_token=TOKEN_A,
                request_id="init-1",
                client_name="test-worker",
                client_version="9.9.9",
            )
        finally:
            await client.aclose()

    result = asyncio.run(scenario())

    assert result.server_info.name == "ai-collab-mcp-gateway"
    assert result.capabilities.tools.list_changed is False
    assert len(captured) == 1
    request = captured[0]
    assert str(request.url) == f"{BASE_URL}{MCP_GATEWAY_PATH}"
    assert request.headers["authorization"] == f"Bearer {TOKEN_A}"
    assert json.loads(request.content) == {
        "jsonrpc": "2.0",
        "id": "init-1",
        "method": "initialize",
        "params": {
            "schemaVersion": 1,
            "protocolVersion": "2025-03-26",
            "client": {"name": "test-worker", "version": "9.9.9"},
        },
    }


def test_initialize_protocol_mismatch_is_internal() -> None:
    payload = _initialize_payload()
    payload["protocolVersion"] = "2099-01-01"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "init-mismatch", "result": payload},
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.initialize(
                    bearer_token=TOKEN_A,
                    request_id="init-mismatch",
                )
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.INTERNAL

    asyncio.run(scenario())


def test_ping_uses_only_caller_injected_token_per_call() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        request_id = json.loads(request.content)["id"]
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"schemaVersion": 1, "status": "ok"},
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            first = await client.ping(bearer_token=TOKEN_A, request_id="ping-a")
            second = await client.ping(bearer_token=TOKEN_B, request_id="ping-b")
            assert first.status == "ok"
            assert second.status == "ok"
        finally:
            await client.aclose()

    asyncio.run(scenario())

    assert [request.headers["authorization"] for request in captured] == [
        f"Bearer {TOKEN_A}",
        f"Bearer {TOKEN_B}",
    ]


def test_missing_token_is_rejected_before_network() -> None:
    called = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal called
        called += 1
        return httpx.Response(500)

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(ValueError, match="Bearer"):
                await client.ping(bearer_token="", request_id="ping-empty")
        finally:
            await client.aclose()

    asyncio.run(scenario())
    assert called == 0


def test_tools_list_matches_frozen_fixture() -> None:
    catalog = _fixture_catalog_payload()
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": "list-1", "result": catalog})

    async def scenario() -> Any:
        client = _client(handler)
        try:
            return await client.list_tools(bearer_token=TOKEN_A, request_id="list-1")
        finally:
            await client.aclose()

    result = asyncio.run(scenario())

    assert result.catalog_version == catalog["catalogVersion"]
    assert result.tools[0].tool_schema_hash == catalog["tools"][0]["toolSchemaHash"]
    assert json.loads(captured[0].content) == {
        "jsonrpc": "2.0",
        "id": "list-1",
        "method": "tools/list",
        "params": {"schemaVersion": 1},
    }


def test_tools_list_preserves_java_error_kind() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": "list-error",
                "error": {
                    "code": -32000,
                    "message": "REMOTE-SECRET",
                    "data": {"kind": "NOT_EXECUTED"},
                },
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.list_tools(bearer_token=TOKEN_A, request_id="list-error")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.NOT_EXECUTED

    asyncio.run(scenario())


def test_tools_call_sends_arguments_verbatim_without_defaults() -> None:
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]
    arguments: dict[str, object] = {"text": "hello"}
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "call-rpc-1", "result": _call_payload()},
        )

    async def scenario() -> Any:
        client = _client(handler)
        try:
            return await client.call_tool(
                bearer_token=TOKEN_B,
                request_id="call-rpc-1",
                call_id="run-1:tool-1",
                name=tool["name"],
                catalog_version=catalog["catalogVersion"],
                tool_schema_hash=tool["toolSchemaHash"],
                arguments=arguments,
            )
        finally:
            await client.aclose()

    result = asyncio.run(scenario())

    assert result.outcome is McpOutcome.SUCCEEDED
    assert result.untrusted is True
    assert result.truncated is False
    assert result.data == {"text": "hello"}
    assert arguments == {"text": "hello"}
    body = json.loads(captured[0].content)
    assert body["method"] == "tools/call"
    assert body["params"] == {
        "schemaVersion": 1,
        "name": tool["name"],
        "catalogVersion": catalog["catalogVersion"],
        "toolSchemaHash": tool["toolSchemaHash"],
        "callId": "run-1:tool-1",
        "arguments": {"text": "hello"},
    }


@pytest.mark.parametrize(
    "kind",
    [
        McpErrorKind.TOOL_DISABLED,
        McpErrorKind.SCHEMA_DRIFT,
        McpErrorKind.PARAM_INVALID,
        McpErrorKind.NOT_EXECUTED,
        McpErrorKind.DEADLINE_EXCEEDED,
        McpErrorKind.RATE_LIMITED,
        McpErrorKind.TOOL_REVOKED,
        McpErrorKind.OUTCOME_UNKNOWN,
    ],
)
def test_java_error_kind_is_preserved_without_retry(kind: McpErrorKind) -> None:
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": "call-error",
                "error": {
                    "code": -32000,
                    "message": "REMOTE-SECRET",
                    "data": {"kind": kind.value},
                },
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.call_tool(
                    bearer_token=TOKEN_A,
                    request_id="call-error",
                    call_id="run-1:tool-1",
                    name=tool["name"],
                    catalog_version=catalog["catalogVersion"],
                    tool_schema_hash=tool["toolSchemaHash"],
                    arguments={"text": "hello"},
                )
        finally:
            await client.aclose()

        error = excinfo.value
        assert error.kind is kind
        assert error.rpc_code == -32000
        assert "REMOTE-SECRET" not in str(error)

    asyncio.run(scenario())
    assert calls == 1


def test_unknown_error_kind_becomes_internal() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": "ping-unknown",
                "error": {
                    "code": -32000,
                    "message": "REMOTE-SECRET",
                    "data": {"kind": "NOT_IN_FROZEN_ENUM"},
                },
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.ping(bearer_token=TOKEN_A, request_id="ping-unknown")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.INTERNAL
        assert "REMOTE-SECRET" not in str(excinfo.value)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("status", "kind"),
    [
        (401, McpErrorKind.UNAUTHORIZED),
        (403, McpErrorKind.UNAUTHORIZED),
        (429, McpErrorKind.RATE_LIMITED),
        (400, McpErrorKind.PARAM_INVALID),
        (504, McpErrorKind.DEADLINE_EXCEEDED),
        (500, McpErrorKind.INTERNAL),
    ],
)
def test_http_status_fallbacks(status: int, kind: McpErrorKind) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, text="REMOTE-SECRET")

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.ping(bearer_token=TOKEN_A, request_id="ping-http")
        finally:
            await client.aclose()
        assert excinfo.value.kind is kind
        assert "REMOTE-SECRET" not in str(excinfo.value)

    asyncio.run(scenario())


def test_call_http_timeout_is_outcome_unknown() -> None:
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(504, text="REMOTE-SECRET")

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.call_tool(
                    bearer_token=TOKEN_A,
                    request_id="call-timeout",
                    call_id="run-1:tool-1",
                    name=tool["name"],
                    catalog_version=catalog["catalogVersion"],
                    tool_schema_hash=tool["toolSchemaHash"],
                    arguments={"text": "hello"},
                )
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.OUTCOME_UNKNOWN

    asyncio.run(scenario())


def test_http_401_cannot_be_relabelled_by_json_rpc_body() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            401,
            json={
                "jsonrpc": "2.0",
                "id": "ping-auth",
                "error": {
                    "code": -32000,
                    "message": "REMOTE-SECRET",
                    "data": {"kind": "TOOL_DISABLED"},
                },
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.ping(bearer_token=TOKEN_A, request_id="ping-auth")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.UNAUTHORIZED

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("exception", "kind"),
    [
        (httpx.ReadTimeout("slow"), McpErrorKind.OUTCOME_UNKNOWN),
        (httpx.WriteTimeout("slow"), McpErrorKind.OUTCOME_UNKNOWN),
        (httpx.ConnectTimeout("slow"), McpErrorKind.NOT_EXECUTED),
        (httpx.ConnectError("refused"), McpErrorKind.NOT_EXECUTED),
    ],
)
def test_transport_failures_are_conservative(
    exception: httpx.HTTPError,
    kind: McpErrorKind,
) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise exception.__class__(str(exception), request=request)

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.ping(bearer_token=TOKEN_A, request_id="ping-transport")
        finally:
            await client.aclose()
        assert excinfo.value.kind is kind

    asyncio.run(scenario())
    assert calls == 1


def test_redirect_to_other_host_is_not_followed() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            302,
            headers={"Location": "https://evil.example/internal/ai/v1/mcp"},
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.ping(bearer_token=TOKEN_A, request_id="ping-redirect")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.INTERNAL

    asyncio.run(scenario())
    assert len(captured) == 1
    assert str(captured[0].url) == f"{BASE_URL}{MCP_GATEWAY_PATH}"


@pytest.mark.parametrize(
    "base_url",
    [
        "ftp://gateway.test",
        "file:///tmp/mcp",
        "gateway.test",
        "https://user:password@gateway.test",
        "http://gateway.test?target=evil",
        "http://gateway.test/#fragment",
        "http://gateway.test/custom/path",
        "http://gateway.test:bad",
    ],
)
def test_gateway_base_url_rejects_other_scheme_or_host_shape(base_url: str) -> None:
    with pytest.raises(ValueError, match=r"base URL|Java Gateway"):
        McpHostClient(base_url=base_url)


def test_tools_list_rejects_hash_or_version_drift() -> None:
    catalog = _fixture_catalog_payload()

    def hash_handler(_request: httpx.Request) -> httpx.Response:
        drifted = json.loads(json.dumps(catalog))
        drifted["tools"][0]["toolSchemaHash"] = "sha256:" + "0" * 64
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "list-hash", "result": drifted},
        )

    async def hash_scenario() -> None:
        client = _client(hash_handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.list_tools(bearer_token=TOKEN_A, request_id="list-hash")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.SCHEMA_DRIFT

    asyncio.run(hash_scenario())

    def version_handler(_request: httpx.Request) -> httpx.Response:
        drifted = json.loads(json.dumps(catalog))
        drifted["catalogVersion"] = "sha256:" + "0" * 64
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "list-version", "result": drifted},
        )

    async def version_scenario() -> None:
        client = _client(version_handler)
        try:
            with pytest.raises(McpHostError) as excinfo:
                await client.list_tools(bearer_token=TOKEN_A, request_id="list-version")
        finally:
            await client.aclose()
        assert excinfo.value.kind is McpErrorKind.SCHEMA_DRIFT

    asyncio.run(version_scenario())


def test_call_result_keeps_untrusted_and_truncation_fields() -> None:
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]
    payload = {
        "schemaVersion": 1,
        "catalogVersion": catalog["catalogVersion"],
        "outcome": "TRUNCATED",
        "untrusted": False,
        "data": {"text": "partial"},
        "truncated": True,
        "latencyMs": 5,
    }

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": "call-truncated", "result": payload},
        )

    async def scenario() -> Any:
        client = _client(handler)
        try:
            return await client.call_tool(
                bearer_token=TOKEN_A,
                request_id="call-truncated",
                call_id="run-1:tool-1",
                name=tool["name"],
                catalog_version=catalog["catalogVersion"],
                tool_schema_hash=tool["toolSchemaHash"],
                arguments={"text": "hello"},
            )
        finally:
            await client.aclose()

    result = asyncio.run(scenario())
    assert result.outcome is McpOutcome.TRUNCATED
    assert result.untrusted is False
    assert result.truncated is True


def test_logs_never_include_token_arguments_or_remote_message(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG, logger="ai_collab_worker.clients.mcp_host")
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": "call-log",
                "error": {
                    "code": -32000,
                    "message": "REMOTE-SECRET",
                    "data": {"kind": "TOOL_DISABLED"},
                },
            },
        )

    async def scenario() -> None:
        client = _client(handler)
        try:
            with pytest.raises(McpHostError):
                await client.call_tool(
                    bearer_token=TOKEN_A,
                    request_id="call-log",
                    call_id="run-1:tool-1",
                    name=tool["name"],
                    catalog_version=catalog["catalogVersion"],
                    tool_schema_hash=tool["toolSchemaHash"],
                    arguments={"text": "ARGUMENT-SECRET"},
                )
        finally:
            await client.aclose()

    asyncio.run(scenario())

    assert TOKEN_A not in caplog.text
    assert "ARGUMENT-SECRET" not in caplog.text
    assert "REMOTE-SECRET" not in caplog.text
