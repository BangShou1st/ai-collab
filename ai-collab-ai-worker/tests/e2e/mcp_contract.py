"""Shared helpers for the P1 MCP contract E2E.

The module is standard-library only. It starts the synthetic local MCP server
from ``tests/fixtures/mcp/local_readonly_server.py`` and either uses the local
contract stub or a real Java Gateway base URL supplied by the caller.
"""

from __future__ import annotations

import copy
import hashlib
import json
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURE_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "catalog-v1.json"
LOCAL_SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"

STUB_TOKEN = "test-token"
GATEWAY_PATH = "/internal/ai/v1/mcp"

SECRET_KEY = re.compile(
    r"(?i).*(authorization|token|cookie|secret|password|api[-_]?key|credential).*"
)
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")
INSTRUCTION_PATTERNS = (
    re.compile(
        r"(?:ignore|disregard|forget|override)\s+(?:all\s+)?"
        r"(?:previous|prior|above|system|developer)\s+"
        r"(?:instructions?|messages?|prompts?|rules?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:system|developer)\s*(?:prompt|message|instructions?)\s*:",
        re.IGNORECASE,
    ),
    re.compile(r"you\s+are\s+now\s+(?:the\s+)?system", re.IGNORECASE),
    re.compile(
        r"忽略|无视|忘记|覆盖|绕过"
        r"(?:之前|以上|系统|开发者|安全|审批)?(?:的)?(?:全部)?"
        r"(?:指令|提示词|规则|审批|限制)"
    ),
    re.compile(r"系统提示词|开发者指令|越权执行"),
)
INJECTION = re.compile(
    r"(?i)(ignore (all )?(previous|system)|忽略.{0,12}(系统|审批|规则)|system prompt)"
)
BEARER = re.compile(r"(?i)Bearer\s+[A-Za-z0-9._~+/=-]{8,}")
SAFE_TOOL_NAME = re.compile(r"[^a-z0-9_]")


class JsonRpcError(RuntimeError):
    def __init__(self, error: dict[str, Any]) -> None:
        super().__init__(str(error.get("message", "JSON-RPC error")))
        self.code = int(error.get("code", -32000))
        data = error.get("data")
        self.kind = data.get("kind", "INTERNAL") if isinstance(data, dict) else "INTERNAL"


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        _normalize_for_hash(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_prefixed(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value)).hexdigest()


def _normalize_for_hash(value: Any) -> Any:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, list):
        return [_normalize_for_hash(item) for item in value]
    if isinstance(value, dict):
        return {
            unicodedata.normalize("NFC", str(key)): _normalize_for_hash(item)
            for key, item in value.items()
        }
    if isinstance(value, float):
        raise ValueError("floating point values are forbidden in hash objects")
    return value


def normalize_text(value: str) -> str:
    text = unicodedata.normalize("NFC", value)
    text = text.replace("\r", " ").replace("\n", " ").replace("\t", " ")
    text = "".join(
        character for character in text if unicodedata.category(character) not in {"Cc", "Cf"}
    )
    return re.sub(r"\s+", " ", text).strip()


def normalize_description(value: str) -> str:
    text = normalize_text(value)
    for pattern in INSTRUCTION_PATTERNS:
        text = pattern.sub("[REMOVED_INSTRUCTION]", text)
    return text


def normalize_tool_name(value: str) -> str:
    return SAFE_TOOL_NAME.sub("_", value.lower())[:128]


def tool_schema_hash(
    exposed_name: str,
    description: str,
    input_schema: dict[str, Any],
    annotations: dict[str, Any] | None = None,
) -> str:
    payload = {
        "annotations": annotations or {"readOnlyHint": True},
        "description": description,
        "inputSchema": input_schema,
        "name": exposed_name,
    }
    return sha256_prefixed(payload)


def catalog_version(tools: list[dict[str, Any]]) -> str:
    payload = {
        "schemaVersion": 1,
        "tools": [
            {"name": tool["name"], "toolSchemaHash": tool["toolSchemaHash"]}
            for tool in sorted(tools, key=lambda item: item["name"])
        ],
    }
    return sha256_prefixed(payload)


def load_fixture() -> dict[str, Any]:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def fixture_exposed_tools() -> list[dict[str, Any]]:
    fixture = load_fixture()
    connection_code = fixture["connectionCode"]
    exposed = []
    for raw in fixture["tools"]:
        normalized_name = normalize_tool_name(raw["name"])
        exposed_name = f"mcp.{connection_code}.{normalized_name}"
        description = normalize_description(raw["description"])
        exposed.append(
            {
                "name": exposed_name,
                "description": description,
                "inputSchema": raw["inputSchema"],
                "toolSchemaHash": tool_schema_hash(
                    exposed_name, description, raw["inputSchema"], raw.get("annotations")
                ),
                "annotations": raw.get("annotations", {"readOnlyHint": True}),
            }
        )
    return sorted(exposed, key=lambda item: item["name"])


def fixture_contract() -> tuple[list[dict[str, Any]], str]:
    tools = fixture_exposed_tools()
    return tools, catalog_version(tools)


def _schema_stats(value: Any, depth: int = 0) -> tuple[int, int]:
    if depth > 16:
        raise ValueError("schema depth exceeds 16")
    nodes = 1
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "$ref" and isinstance(item, str) and not item.startswith("#"):
                raise ValueError("external $ref is forbidden")
            child_nodes, child_depth = _schema_stats(item, depth + 1)
            nodes += child_nodes
            depth = max(depth, child_depth)
    elif isinstance(value, list):
        for item in value:
            child_nodes, child_depth = _schema_stats(item, depth + 1)
            nodes += child_nodes
            depth = max(depth, child_depth)
    return nodes, depth


def validate_schema(schema: Any) -> bool:
    if not isinstance(schema, dict):
        return False
    try:
        nodes, _ = _schema_stats(schema)
        if nodes > 2000:
            return False
        return len(canonical_json(schema)) <= 32 * 1024
    except (TypeError, ValueError):
        return False


@dataclass
class GatewayStubConfig:
    upstream_url: str | None = None
    allowlist: tuple[str, ...] = ("echo_text",)
    connection_code: str = "local_readonly"
    max_result_bytes: int = 4096
    deadline_ms: int = 2000
    fixture_tools: list[dict[str, Any]] | None = None

    def __post_init__(self) -> None:
        if self.fixture_tools is None:
            self.fixture_tools = copy.deepcopy(load_fixture()["tools"])


def _upstream_tools(url: str, timeout: float = 3.0) -> list[dict[str, Any]]:
    client = JsonRpcClient(url, STUB_TOKEN, timeout=timeout)
    client.call(
        "initialize",
        {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "contract-stub", "version": "1"},
        },
    )
    result = client.call("tools/list", {})
    tools = result.get("tools") if isinstance(result, dict) else None
    if not isinstance(tools, list):
        raise ValueError("upstream tools/list returned no tools")
    return [tool for tool in tools if isinstance(tool, dict)]


def build_gateway_catalog(config: GatewayStubConfig) -> tuple[list[dict[str, Any]], str]:
    if config.upstream_url:
        raw_tools = _upstream_tools(config.upstream_url)
    else:
        raw_tools = config.fixture_tools or []

    exposed: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    for raw in raw_tools:
        server_name = raw.get("name")
        annotations = raw.get("annotations")
        if not isinstance(server_name, str):
            continue
        if server_name not in config.allowlist:
            continue
        if not isinstance(annotations, dict) or annotations.get("readOnlyHint") is not True:
            continue
        normalized = normalize_tool_name(server_name)
        if not normalized:
            continue
        exposed_name = f"mcp.{config.connection_code}.{normalized}"
        if len(exposed_name) > 160 or exposed_name in seen_names:
            continue
        description = normalize_description(str(raw.get("description", "")))
        if len(description) > 512:
            continue
        input_schema = raw.get("inputSchema")
        if not validate_schema(input_schema):
            continue
        clean_annotations = {"readOnlyHint": True}
        exposed.append(
            {
                "name": exposed_name,
                "description": description,
                "inputSchema": input_schema,
                "toolSchemaHash": tool_schema_hash(
                    exposed_name, description, input_schema, clean_annotations
                ),
                "annotations": clean_annotations,
            }
        )
        seen_names.add(exposed_name)

    exposed.sort(key=lambda item: item["name"])
    return exposed, catalog_version(exposed)


def sanitize_result(value: Any) -> tuple[Any, bool]:
    if isinstance(value, dict):
        cleaned: dict[str, Any] = {}
        injection = False
        for key, item in value.items():
            if SECRET_KEY.match(key):
                cleaned[key] = "[REDACTED]"
                continue
            lower = key.lower()
            if "binary" in lower or lower in {"blob", "file_path", "filepath"}:
                cleaned[key] = "[UNSUPPORTED_BINARY_OR_PATH]"
                continue
            cleaned[key], detected = sanitize_result(item)
            injection = injection or detected
        return cleaned, injection
    if isinstance(value, list):
        cleaned_items = []
        injection = False
        for item in value[:100]:
            cleaned, detected = sanitize_result(item)
            cleaned_items.append(cleaned)
            injection = injection or detected
        return cleaned_items, injection
    if isinstance(value, str):
        text = CONTROL.sub("", value)
        text = BEARER.sub("Bearer [REDACTED]", text)
        detected = bool(INJECTION.search(text))
        if detected:
            text = INJECTION.sub("[REMOVED_INSTRUCTION]", text)
        return text, detected
    return value, False


def _rpc_ok(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _rpc_error(request_id: Any, kind: str, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": -32000,
            "message": message,
            "data": {"kind": kind},
        },
    }


class GatewayStub:
    def __init__(self, config: GatewayStubConfig) -> None:
        self.config = config
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self.url = ""

    def __enter__(self) -> GatewayStub:
        stub = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: object) -> None:
                return

            def do_POST(self) -> None:
                if self.path != GATEWAY_PATH:
                    self._write(404, {"error": "not found"})
                    return
                if self.headers.get("Authorization") != f"Bearer {STUB_TOKEN}":
                    self._write(401, {"error": "unauthorized"})
                    return
                length = int(self.headers.get("Content-Length", "0"))
                try:
                    request = json.loads(self.rfile.read(length).decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self._write(400, {"error": "parse error"})
                    return
                self._write(200, stub._handle(request))

            def _write(self, status: int, payload: Any) -> None:
                body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
                    "utf-8"
                )
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        port = self._server.server_address[1]
        self.url = f"http://127.0.0.1:{port}{GATEWAY_PATH}"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_exc: object) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=3)

    def _handle(self, request: dict[str, Any]) -> dict[str, Any]:
        request_id = request.get("id")
        method = request.get("method")
        params = request.get("params") or {}
        if method == "initialize":
            return _rpc_ok(
                request_id,
                {
                    "schemaVersion": 1,
                    "protocolVersion": "2025-03-26",
                    "serverInfo": {"name": "ai-collab-mcp-gateway", "version": "p1"},
                    "capabilities": {"tools": {"listChanged": False}},
                },
            )
        if method == "ping":
            return _rpc_ok(request_id, {"schemaVersion": 1, "status": "ok"})
        if method == "tools/list":
            tools, version = build_gateway_catalog(self.config)
            return _rpc_ok(
                request_id,
                {
                    "schemaVersion": 1,
                    "catalogVersion": version,
                    "tools": tools,
                },
            )
        if method == "tools/call":
            return self._tools_call(request_id, params)
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": -32601, "message": "method not found", "data": {"kind": "INTERNAL"}},
        }

    def _tools_call(self, request_id: Any, params: dict[str, Any]) -> dict[str, Any]:
        name = params.get("name")
        catalog = params.get("catalogVersion")
        schema_hash = params.get("toolSchemaHash")
        arguments = params.get("arguments")
        if not isinstance(name, str) or not isinstance(arguments, dict):
            return _rpc_error(request_id, "PARAM_INVALID", "invalid tools/call params")
        if len(canonical_json(arguments)) > 64 * 1024:
            return _rpc_error(request_id, "PARAM_INVALID", "arguments exceed 64 KiB")

        tools, version = build_gateway_catalog(self.config)
        by_name = {tool["name"]: tool for tool in tools}
        tool = by_name.get(name)
        if tool is None:
            return _rpc_error(request_id, "TOOL_DISABLED", "tool is not enabled")
        if catalog != version or schema_hash != tool["toolSchemaHash"]:
            return _rpc_error(request_id, "SCHEMA_DRIFT", "catalog or tool hash changed")
        if not self.config.upstream_url:
            return _rpc_error(request_id, "NOT_EXECUTED", "upstream connection was not attempted")

        server_name = name.rsplit(".", 1)[-1]
        started = time.monotonic()
        try:
            upstream = JsonRpcClient(
                self.config.upstream_url,
                STUB_TOKEN,
                timeout=self.config.deadline_ms / 1000.0,
            )
            result = upstream.call(
                "tools/call",
                {
                    "name": server_name,
                    "arguments": arguments,
                },
            )
        except TimeoutError:
            return _rpc_error(request_id, "DEADLINE_EXCEEDED", "upstream deadline exceeded")
        except urllib.error.URLError as failure:
            if isinstance(failure.reason, (TimeoutError, socket.timeout)):
                return _rpc_error(request_id, "DEADLINE_EXCEEDED", "upstream deadline exceeded")
            return _rpc_error(request_id, "NOT_EXECUTED", "upstream connection failed")
        except JsonRpcError:
            return _rpc_error(request_id, "OUTCOME_UNKNOWN", "upstream result is unknown")

        latency_ms = max(0, int((time.monotonic() - started) * 1000))
        if not isinstance(result, dict):
            return _rpc_error(request_id, "OUTCOME_UNKNOWN", "upstream result is unknown")
        if result.get("isError") is True:
            return _rpc_error(request_id, "OUTCOME_UNKNOWN", "upstream reported isError")

        raw_data = result.get("structuredContent", result.get("content"))
        cleaned, injection = sanitize_result(raw_data)
        encoded = canonical_json(cleaned)
        if len(encoded) > self.config.max_result_bytes:
            preview = encoded[: self.config.max_result_bytes].decode("utf-8", errors="ignore")
            return _rpc_ok(
                request_id,
                {
                    "schemaVersion": 1,
                    "catalogVersion": version,
                    "outcome": "TRUNCATED",
                    "untrusted": True,
                    "preview": CONTROL.sub("", preview),
                    "truncated": True,
                    "warnings": ["检测到外部指令文本，已作为不可信数据保留"] if injection else [],
                    "latencyMs": latency_ms,
                },
            )
        return _rpc_ok(
            request_id,
            {
                "schemaVersion": 1,
                "catalogVersion": version,
                "outcome": "SUCCEEDED",
                "untrusted": True,
                "data": cleaned,
                "truncated": False,
                "warnings": ["检测到外部指令文本，已作为不可信数据保留"] if injection else [],
                "latencyMs": latency_ms,
            },
        )


class JsonRpcClient:
    def __init__(self, url: str, token: str | None = None, timeout: float = 5.0) -> None:
        self.url = url
        self.token = token
        self.timeout = timeout
        self._ids = 0

    def call(self, method: str, params: dict[str, Any], timeout: float | None = None) -> Any:
        self._ids += 1
        payload = {
            "jsonrpc": "2.0",
            "id": f"e2e-{self._ids}",
            "method": method,
            "params": params,
        }
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                body = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as failure:
            body = json.loads(failure.read().decode("utf-8"))
            raise JsonRpcError(body.get("error", {"message": "HTTP error"})) from failure
        if "error" in body:
            raise JsonRpcError(body["error"])
        return body.get("result")


class LocalMcpServerProcess:
    def __init__(self, startup_timeout: float = 10.0) -> None:
        self.url = ""
        self.port = 0
        self._process: subprocess.Popen[str] | None = None
        self._startup_timeout = startup_timeout

    def __enter__(self) -> LocalMcpServerProcess:
        self._process = subprocess.Popen(
            [sys.executable, str(LOCAL_SERVER_PATH), "--port", "0"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        lines: queue.Queue[str] = queue.Queue()

        def read_ready_line() -> None:
            assert self._process is not None
            assert self._process.stdout is not None
            try:
                lines.put(self._process.stdout.readline())
            except (OSError, ValueError):
                lines.put("")

        threading.Thread(target=read_ready_line, daemon=True).start()
        try:
            line = lines.get(timeout=self._startup_timeout)
        except queue.Empty:
            self.__exit__()
            raise RuntimeError("local MCP server did not start in time") from None
        if not line.strip():
            stderr = ""
            if self._process.stderr is not None:
                stderr = self._process.stderr.read()
            self.__exit__()
            raise RuntimeError(f"local MCP server failed to start: {stderr.strip()}")
        ready = json.loads(line)
        self.url = str(ready["url"])
        self.port = int(ready["port"])
        return self

    def __exit__(self, *_exc: object) -> None:
        process = self._process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()
        wait_port_closed(self.port, timeout=5)
        self._process = None


def wait_port_closed(port: int, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex(("127.0.0.1", port)) != 0:
                return True
        time.sleep(0.05)
    return False


def scenario_result(name: str, evidence: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"name": name, "status": "PASS", "evidence": evidence or {}}


def _rpc_kind(call: Callable[[], Any], expected: str) -> str:
    observed = ""
    try:
        call()
    except JsonRpcError as failure:
        observed = failure.kind
    assert observed == expected, observed
    return observed


def skipped_result(name: str, reason: str) -> dict[str, Any]:
    return {"name": name, "status": "SKIP", "reason": reason}


def failed_result(name: str, error: BaseException) -> dict[str, Any]:
    return {"name": name, "status": "FAIL", "error": f"{type(error).__name__}: {error}"}


def _call_success_text(result: dict[str, Any]) -> None:
    assert result["outcome"] == "SUCCEEDED", result
    assert result["untrusted"] is True, result
    assert result["truncated"] is False, result
    data = result["data"]
    serialized = json.dumps(data, ensure_ascii=False)
    assert "sk-synthetic-admission-test-0001" not in serialized, data
    assert "synthetic-bearer-token-0001" not in serialized, data
    if isinstance(data, dict) and "apiKey" in data:
        assert data["apiKey"] == "[REDACTED]", data
        assert data["authorization"] == "[REDACTED]", data
    if isinstance(data, dict) and "note" in data:
        assert "[REMOVED_INSTRUCTION]" in data["note"], data
    assert result["warnings"], result


def run_stub_scenarios() -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    fixture_tools, fixture_version = fixture_contract()
    fixture = load_fixture()
    try:
        assert fixture["expected"]["toolSchemaHash"] == fixture_tools[0]["toolSchemaHash"]
        assert fixture["expected"]["catalogVersion"] == fixture_version
        results.append(
            scenario_result(
                "fixture-hash",
                {
                    "toolSchemaHash": fixture_tools[0]["toolSchemaHash"],
                    "catalogVersion": fixture_version,
                },
            )
        )
    except BaseException as failure:
        results.append(failed_result("fixture-hash", failure))

    with LocalMcpServerProcess() as local:
        try:
            raw = JsonRpcClient(local.url, STUB_TOKEN)
            raw.call(
                "initialize",
                {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "e2e", "version": "1"},
                },
            )
            listed = raw.call("tools/list", {})
            names = {tool["name"]: tool for tool in listed["tools"]}
            assert names["echo_text"]["annotations"]["readOnlyHint"] is True
            assert names["write_probe"]["annotations"]["readOnlyHint"] is False
            called = raw.call(
                "tools/call",
                {
                    "name": "echo_text",
                    "arguments": {"text": "raw"},
                },
            )
            assert called["structuredContent"]["apiKey"].startswith("sk-synthetic-")
            assert "Ignore all previous" in called["structuredContent"]["note"]
            results.append(
                scenario_result(
                    "local-mcp-server",
                    {
                        "tools": sorted(names),
                        "readOnly": names["echo_text"]["annotations"]["readOnlyHint"],
                        "writeProbeReadOnly": names["write_probe"]["annotations"]["readOnlyHint"],
                    },
                )
            )
        except BaseException as failure:
            results.append(failed_result("local-mcp-server", failure))

        results.append(_stub_list_and_success(local.url))
        results.append(_stub_truncation(local.url))
        results.append(_stub_denial(local.url))
        results.append(_stub_schema_drift(local.url))
        results.append(_stub_not_executed())
        results.append(_stub_deadline(local.url))
        results.append(_stub_outcome_unknown(local.url))
    return results


def _stub_list_and_success(upstream_url: str) -> dict[str, Any]:
    name = "gateway-list-and-success"
    try:
        with GatewayStub(GatewayStubConfig(upstream_url=upstream_url)) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            initialized = client.call(
                "initialize",
                {
                    "schemaVersion": 1,
                    "protocolVersion": "2025-03-26",
                    "client": {"name": "ai-collab-ai-worker", "version": "0.1.0"},
                },
            )
            assert initialized["serverInfo"]["name"] == "ai-collab-mcp-gateway"
            assert client.call("ping", {"schemaVersion": 1})["status"] == "ok"
            listed = client.call("tools/list", {"schemaVersion": 1})
            expected_tools, expected_version = fixture_contract()
            assert listed["catalogVersion"] == expected_version, listed
            assert listed["tools"] == expected_tools, listed
            assert [tool["name"] for tool in listed["tools"]] == ["mcp.local_readonly.echo_text"]
            assert "write_probe" not in json.dumps(listed, ensure_ascii=False)
            tool = listed["tools"][0]
            result = client.call(
                "tools/call",
                {
                    "schemaVersion": 1,
                    "name": tool["name"],
                    "catalogVersion": listed["catalogVersion"],
                    "toolSchemaHash": tool["toolSchemaHash"],
                    "callId": "e2e-success-1",
                    "arguments": {"text": "hello"},
                },
            )
            _call_success_text(result)
            return scenario_result(
                name,
                {
                    "catalogVersion": listed["catalogVersion"],
                    "toolSchemaHash": tool["toolSchemaHash"],
                    "outcome": result["outcome"],
                    "untrusted": result["untrusted"],
                    "truncated": result["truncated"],
                    "warnings": result["warnings"],
                },
            )
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_truncation(upstream_url: str) -> dict[str, Any]:
    name = "gateway-truncated"
    try:
        with GatewayStub(
            GatewayStubConfig(upstream_url=upstream_url, max_result_bytes=128)
        ) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            tool = listed["tools"][0]
            result = client.call(
                "tools/call",
                {
                    "schemaVersion": 1,
                    "name": tool["name"],
                    "catalogVersion": listed["catalogVersion"],
                    "toolSchemaHash": tool["toolSchemaHash"],
                    "callId": "e2e-truncated-1",
                    "arguments": {"text": "x" * 200},
                },
            )
            assert result["outcome"] == "TRUNCATED", result
            assert result["untrusted"] is True, result
            assert result["truncated"] is True, result
            preview = result["preview"]
            assert "sk-synthetic-admission-test-0001" not in preview, preview
            assert "synthetic-bearer-token-0001" not in preview, preview
            return scenario_result(
                name,
                {
                    "outcome": result["outcome"],
                    "truncated": result["truncated"],
                    "untrusted": result["untrusted"],
                },
            )
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_denial(upstream_url: str) -> dict[str, Any]:
    name = "gateway-denied-non-readonly"
    try:
        with GatewayStub(GatewayStubConfig(upstream_url=upstream_url)) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            observed = _rpc_kind(
                lambda: client.call(
                    "tools/call",
                    {
                        "schemaVersion": 1,
                        "name": "mcp.local_readonly.write_probe",
                        "catalogVersion": listed["catalogVersion"],
                        "toolSchemaHash": "sha256:" + "0" * 64,
                        "callId": "e2e-denied-1",
                        "arguments": {"text": "must not run"},
                    },
                ),
                "TOOL_DISABLED",
            )
            return scenario_result(name, {"kind": observed})
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_schema_drift(upstream_url: str) -> dict[str, Any]:
    name = "gateway-schema-drift"
    try:
        with GatewayStub(GatewayStubConfig(upstream_url=upstream_url)) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            tool = listed["tools"][0]
            observed = _rpc_kind(
                lambda: client.call(
                    "tools/call",
                    {
                        "schemaVersion": 1,
                        "name": tool["name"],
                        "catalogVersion": "sha256:" + "0" * 64,
                        "toolSchemaHash": tool["toolSchemaHash"],
                        "callId": "e2e-drift-1",
                        "arguments": {"text": "hello"},
                    },
                ),
                "SCHEMA_DRIFT",
            )
            return scenario_result(name, {"kind": observed})
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_not_executed() -> dict[str, Any]:
    name = "gateway-not-executed"
    try:
        with GatewayStub(GatewayStubConfig(upstream_url=None)) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            tool = listed["tools"][0]
            observed = _rpc_kind(
                lambda: client.call(
                    "tools/call",
                    {
                        "schemaVersion": 1,
                        "name": tool["name"],
                        "catalogVersion": listed["catalogVersion"],
                        "toolSchemaHash": tool["toolSchemaHash"],
                        "callId": "e2e-not-executed-1",
                        "arguments": {"text": "hello"},
                    },
                ),
                "NOT_EXECUTED",
            )
            return scenario_result(name, {"kind": observed})
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_deadline(upstream_url: str) -> dict[str, Any]:
    name = "gateway-deadline-exceeded"
    try:
        with GatewayStub(
            GatewayStubConfig(
                upstream_url=upstream_url,
                allowlist=("slow_echo",),
                deadline_ms=150,
            )
        ) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            tool = listed["tools"][0]
            assert tool["name"].endswith(".slow_echo"), listed
            observed = _rpc_kind(
                lambda: client.call(
                    "tools/call",
                    {
                        "schemaVersion": 1,
                        "name": tool["name"],
                        "catalogVersion": listed["catalogVersion"],
                        "toolSchemaHash": tool["toolSchemaHash"],
                        "callId": "e2e-deadline-1",
                        "arguments": {"text": "slow", "delayMs": 1000},
                    },
                    timeout=5,
                ),
                "DEADLINE_EXCEEDED",
            )
            return scenario_result(name, {"kind": observed})
    except BaseException as failure:
        return failed_result(name, failure)


def _stub_outcome_unknown(upstream_url: str) -> dict[str, Any]:
    name = "gateway-outcome-unknown"
    try:
        with GatewayStub(
            GatewayStubConfig(
                upstream_url=upstream_url,
                allowlist=("error_echo",),
            )
        ) as stub:
            client = JsonRpcClient(stub.url, STUB_TOKEN)
            listed = client.call("tools/list", {"schemaVersion": 1})
            tool = listed["tools"][0]
            observed = _rpc_kind(
                lambda: client.call(
                    "tools/call",
                    {
                        "schemaVersion": 1,
                        "name": tool["name"],
                        "catalogVersion": listed["catalogVersion"],
                        "toolSchemaHash": tool["toolSchemaHash"],
                        "callId": "e2e-unknown-1",
                        "arguments": {},
                    },
                ),
                "OUTCOME_UNKNOWN",
            )
            return scenario_result(name, {"kind": observed})
    except BaseException as failure:
        return failed_result(name, failure)


def run_real_gateway_scenarios(gateway_url: str, token: str | None) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    client = JsonRpcClient(gateway_url, token)
    try:
        initialized = client.call(
            "initialize",
            {
                "schemaVersion": 1,
                "protocolVersion": "2025-03-26",
                "client": {"name": "ai-collab-ai-worker", "version": "0.1.0"},
            },
        )
        assert client.call("ping", {"schemaVersion": 1})["status"] == "ok"
        listed = client.call("tools/list", {"schemaVersion": 1})
        tools = listed.get("tools", [])
        assert tools, "real Gateway returned no tools"
        tool = next(
            (item for item in tools if item["name"] == "mcp.local_readonly.echo_text"),
            None,
        )
        assert tool is not None, [item["name"] for item in tools]
        expected_tools, expected_version = fixture_contract()
        assert listed["catalogVersion"] == expected_version, listed["catalogVersion"]
        assert tool["toolSchemaHash"] == expected_tools[0]["toolSchemaHash"], tool
        results.append(
            scenario_result(
                "real-gateway-list",
                {
                    "server": initialized["serverInfo"]["name"],
                    "catalogVersion": listed["catalogVersion"],
                    "toolSchemaHash": tool["toolSchemaHash"],
                },
            )
        )
        result = client.call(
            "tools/call",
            {
                "schemaVersion": 1,
                "name": tool["name"],
                "catalogVersion": listed["catalogVersion"],
                "toolSchemaHash": tool["toolSchemaHash"],
                "callId": "e2e-real-call-1",
                "arguments": {"text": "hello"},
            },
        )
        _call_success_text(result)
        results.append(
            scenario_result(
                "real-gateway-success",
                {
                    "outcome": result["outcome"],
                    "untrusted": result["untrusted"],
                    "truncated": result["truncated"],
                },
            )
        )
    except BaseException as failure:
        results.append(failed_result("real-gateway-list-and-success", failure))
        return results

    try:
        client.call(
            "tools/call",
            {
                "schemaVersion": 1,
                "name": "mcp.local_readonly.write_probe",
                "catalogVersion": "sha256:" + "0" * 64,
                "toolSchemaHash": "sha256:" + "0" * 64,
                "callId": "e2e-real-denied-1",
                "arguments": {"text": "must not run"},
            },
        )
        results.append(
            failed_result("real-gateway-denied", AssertionError("non-readonly tool was not denied"))
        )
    except JsonRpcError as failure:
        if failure.kind == "TOOL_DISABLED":
            results.append(scenario_result("real-gateway-denied", {"kind": failure.kind}))
        else:
            results.append(
                skipped_result(
                    "real-gateway-denied",
                    f"real Gateway returned {failure.kind}; fixture project binding is required",
                )
            )
    except BaseException as failure:
        results.append(failed_result("real-gateway-denied", failure))

    for name, reason in (
        ("real-gateway-not-executed", "requires an unreachable upstream in Gateway config"),
        ("real-gateway-deadline", "requires a slow upstream or configured deadline"),
        ("real-gateway-outcome-unknown", "requires an upstream isError fixture"),
        ("real-gateway-truncated", "requires Gateway maxResultBytes fault injection"),
    ):
        results.append(skipped_result(name, reason))
    return results
