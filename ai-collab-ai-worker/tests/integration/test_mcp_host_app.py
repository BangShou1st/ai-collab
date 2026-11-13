"""MCP Host 真实进程与跨进程 Host→Java 契约测试。"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_collab_worker.contract.mcp import (
    MCP_GATEWAY_PATH,
    McpDiscoveredTool,
    McpErrorKind,
    normalize_catalog,
)
from ai_collab_worker.mcp_host_app import create_app

PROJECT_ROOT = Path(__file__).resolve().parents[3]
WORKER_ROOT = PROJECT_ROOT / "ai-collab-ai-worker"
FIXTURE = PROJECT_ROOT / "tests" / "fixtures" / "mcp" / "catalog-v1.json"
TOKEN = "synthetic-host-token"
REMOTE_SECRET = "REMOTE-SECRET-MUST-NOT-ECHO"

type GatewayHandler = Callable[[httpx.Request], httpx.Response]


def _fixture_catalog_payload() -> dict[str, Any]:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    catalog = normalize_catalog(
        connection_code=fixture["connectionCode"],
        tools=[McpDiscoveredTool.model_validate(tool) for tool in fixture["tools"]],
    )
    return catalog.model_dump(by_alias=True, mode="json")


def _initialize_payload() -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "protocolVersion": "2025-03-26",
        "serverInfo": {"name": "ai-collab-mcp-gateway", "version": "p1"},
        "capabilities": {"tools": {"listChanged": False}},
    }


def _call_payload(catalog: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "catalogVersion": catalog["catalogVersion"],
        "outcome": "SUCCEEDED",
        "untrusted": True,
        "data": {"text": "hello", "apiKey": "[REDACTED]"},
        "truncated": False,
        "latencyMs": 7,
    }


def _mock_gateway(
    captured: list[httpx.Request],
    *,
    error_kind: str | None = None,
) -> GatewayHandler:
    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        payload = json.loads(request.content)
        request_id = payload["id"]
        if error_kind is not None:
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {
                        "code": -32000,
                        "message": REMOTE_SECRET,
                        "data": {"kind": error_kind},
                    },
                },
            )
        method = payload["method"]
        if method == "initialize":
            result = _initialize_payload()
        elif method == "ping":
            result = {"schemaVersion": 1, "status": "ok"}
        elif method == "tools/list":
            result = _fixture_catalog_payload()
        elif method == "tools/call":
            result = _call_payload(_fixture_catalog_payload())
        else:
            raise AssertionError(method)
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": request_id, "result": result},
        )

    return handler


def _client(handler: GatewayHandler) -> TestClient:
    app = create_app(
        gateway_url="http://gateway.test:8080",
        transport=httpx.MockTransport(handler),
    )
    return TestClient(app)


def _bearer_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {TOKEN}"}


def test_tools_list_matches_frozen_fixture_and_forwards_bearer() -> None:
    captured: list[httpx.Request] = []
    with _client(_mock_gateway(captured)) as client:
        response = client.post(
            "/v1/mcp/list",
            json={"requestId": "host-list-1"},
            headers=_bearer_headers(),
        )

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == "host-list-1"
    assert body["operation"] == "tools/list"
    assert body["outcome"] == "SUCCEEDED"
    assert body["result"] == _fixture_catalog_payload()
    assert len(captured) == 1
    assert str(captured[0].url) == "http://gateway.test:8080" + MCP_GATEWAY_PATH
    assert captured[0].headers["authorization"] == f"Bearer {TOKEN}"
    assert json.loads(captured[0].content)["method"] == "tools/list"


def test_tools_call_preserves_outcome_untrusted_and_arguments() -> None:
    captured: list[httpx.Request] = []
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]
    arguments = {"text": "hello"}
    params = {
        "schemaVersion": 1,
        "name": tool["name"],
        "catalogVersion": catalog["catalogVersion"],
        "toolSchemaHash": tool["toolSchemaHash"],
        "callId": "run-1:tool-1",
        "arguments": arguments,
    }

    with _client(_mock_gateway(captured)) as client:
        response = client.post(
            "/v1/mcp/call",
            json={"requestId": "host-call-1", "params": params},
            headers=_bearer_headers(),
        )

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "SUCCEEDED"
    assert body["result"] == _call_payload(catalog)
    assert arguments == {"text": "hello"}

    forwarded = json.loads(captured[0].content)
    assert forwarded["params"]["arguments"] == {"text": "hello"}
    assert forwarded["params"]["callId"] == "run-1:tool-1"


def test_missing_host_bearer_is_rejected_without_gateway_call() -> None:
    captured: list[httpx.Request] = []
    with _client(_mock_gateway(captured)) as client:
        response = client.post("/v1/mcp/list", json={"requestId": "host-list-no-auth"})

    assert response.status_code == 401
    assert response.json()["error"]["kind"] == "UNAUTHORIZED"
    assert captured == []


@pytest.mark.parametrize(
    "body",
    [
        {"requestId": "forged-top", "projectRef": "00000000-0000-0000-0000-000000000001"},
        {
            "requestId": "forged-param",
            "params": {
                "schemaVersion": 1,
                "name": "mcp.local_readonly.echo_text",
                "catalogVersion": "sha256:" + "0" * 64,
                "toolSchemaHash": "sha256:" + "0" * 64,
                "callId": "run-1:tool-1",
                "projectRef": "00000000-0000-0000-0000-000000000001",
                "arguments": {"text": "must not run"},
            },
        },
        {
            "requestId": "forged-argument",
            "params": {
                "schemaVersion": 1,
                "name": "mcp.local_readonly.echo_text",
                "catalogVersion": "sha256:" + "0" * 64,
                "toolSchemaHash": "sha256:" + "0" * 64,
                "callId": "run-1:tool-1",
                "arguments": {"text": "must not run", "projectId": "forged"},
            },
        },
    ],
)
def test_forged_identity_is_rejected_before_gateway(body: dict[str, Any]) -> None:
    captured: list[httpx.Request] = []
    with _client(_mock_gateway(captured)) as client:
        response = client.post("/v1/mcp/call", json=body, headers=_bearer_headers())

    assert response.status_code == 422
    assert captured == []


def test_upstream_error_kind_is_preserved_without_remote_message() -> None:
    captured: list[httpx.Request] = []
    handler = _mock_gateway(captured, error_kind=McpErrorKind.TOOL_REVOKED.value)
    with _client(handler) as client:
        response = client.post(
            "/v1/mcp/list",
            json={"requestId": "host-list-revoked"},
            headers=_bearer_headers(),
        )

    assert response.status_code == 502
    body = response.json()
    assert body["error"] == {"kind": "TOOL_REVOKED", "retryable": False}
    assert REMOTE_SECRET not in response.text
    assert len(captured) == 1


def test_unknown_request_fields_cannot_change_gateway_url() -> None:
    captured: list[httpx.Request] = []
    with _client(_mock_gateway(captured)) as client:
        response = client.post(
            "/v1/mcp/list",
            json={
                "requestId": "host-list-extra",
                "gatewayUrl": "https://evil.example",
            },
            headers=_bearer_headers(),
        )

    assert response.status_code == 422
    assert captured == []


def test_invalid_startup_gateway_url_fails_fast() -> None:
    app = create_app(gateway_url="ftp://gateway.test")
    with pytest.raises(ValueError, match="base URL"), TestClient(app):
        pass


class _GatewayFixture:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.server.daemon_threads = True
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def __enter__(self) -> _GatewayFixture:
        self.thread.start()
        return self

    def __exit__(self, *_exc: object) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: object) -> None:
                return

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                payload = json.loads(raw.decode("utf-8"))
                fixture.calls.append(
                    {
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "body": payload,
                    }
                )
                request_id = payload["id"]
                method = payload["method"]
                if method == "initialize":
                    result = _initialize_payload()
                elif method == "ping":
                    result = {"schemaVersion": 1, "status": "ok"}
                elif method == "tools/list":
                    result = _fixture_catalog_payload()
                elif method == "tools/call":
                    result = _call_payload(_fixture_catalog_payload())
                else:
                    self._write_json(
                        404,
                        {
                            "jsonrpc": "2.0",
                            "id": request_id,
                            "error": {"code": -32601, "message": "method not found"},
                        },
                    )
                    return
                self._write_json(
                    200,
                    {"jsonrpc": "2.0", "id": request_id, "result": result},
                )

            def _write_json(self, status: int, payload: dict[str, Any]) -> None:
                body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        return Handler


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def _wait_health(url: str, process: subprocess.Popen[str], timeout: float = 12.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            stdout, stderr = process.communicate()
            raise AssertionError(f"Host exited early: {stdout}\n{stderr}")
        try:
            if httpx.get(url, timeout=0.5).status_code == 200:
                return
        except httpx.HTTPError:
            time.sleep(0.05)
    raise AssertionError("Host did not become healthy")


def _wait_port_closed(port: int, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex(("127.0.0.1", port)) != 0:
                return True
        time.sleep(0.05)
    return False


def test_real_uvicorn_process_serves_list_and_call_then_stops() -> None:
    catalog = _fixture_catalog_payload()
    tool = catalog["tools"][0]
    port = _free_port()
    with _GatewayFixture() as gateway:
        env = os.environ.copy()
        for key in list(env):
            if key.startswith("AI_WORKER_") or key.endswith("_API_KEY"):
                env.pop(key, None)
        env.update(
            {
                "MCP_HOST_GATEWAY_URL": gateway.url,
                "MCP_HOST_TIMEOUT_SECONDS": "5",
            }
        )
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "ai_collab_worker.mcp_host_app:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--log-level",
                "warning",
            ],
            cwd=WORKER_ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        try:
            host_url = f"http://127.0.0.1:{port}"
            _wait_health(f"{host_url}/healthz", process)
            with httpx.Client(timeout=5, trust_env=False) as client:
                listed = client.post(
                    f"{host_url}/v1/mcp/list",
                    json={"requestId": "process-list-1"},
                    headers=_bearer_headers(),
                )
                called = client.post(
                    f"{host_url}/v1/mcp/call",
                    json={
                        "requestId": "process-call-1",
                        "params": {
                            "schemaVersion": 1,
                            "name": tool["name"],
                            "catalogVersion": catalog["catalogVersion"],
                            "toolSchemaHash": tool["toolSchemaHash"],
                            "callId": "run-1:tool-1",
                            "arguments": {"text": "hello"},
                        },
                    },
                    headers=_bearer_headers(),
                )
            assert listed.status_code == 200, listed.text
            assert listed.json()["result"] == catalog
            assert called.status_code == 200, called.text
            assert called.json()["result"]["outcome"] == "SUCCEEDED"
            assert called.json()["result"]["untrusted"] is True
            assert [call["authorization"] for call in gateway.calls] == [
                f"Bearer {TOKEN}",
                f"Bearer {TOKEN}",
            ]
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            stdout = process.stdout.read() if process.stdout is not None else ""
            stderr = process.stderr.read() if process.stderr is not None else ""
            for stream in (process.stdout, process.stderr):
                if stream is not None:
                    stream.close()
        assert _wait_port_closed(port)
        assert TOKEN not in stdout + stderr
        assert "hello" not in stdout + stderr
