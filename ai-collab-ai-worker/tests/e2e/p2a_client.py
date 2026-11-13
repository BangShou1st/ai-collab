"""P2-A 真实进程 E2E 的 HTTP/JSON-RPC 客户端。

只使用标准库；TLS 证书通过显式 cafile 校验，不做证书验证关闭。
"""

from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp_contract import JsonRpcError


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: Any
    text: str


def http_json(
    url: str,
    *,
    method: str = "POST",
    payload: Any = None,
    token: str | None = None,
    ca_file: Path | None = None,
    timeout: float = 30.0,
    headers: dict[str, str] | None = None,
) -> HttpResult:
    request_headers = {"Accept": "application/json", **(headers or {})}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    context = ssl.create_default_context(cafile=str(ca_file)) if ca_file else None
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
            return _result(response.status, response.read())
    except urllib.error.HTTPError as failure:
        return _result(failure.code, failure.read())


def _result(status: int, raw: bytes) -> HttpResult:
    text = raw.decode("utf-8", errors="replace")
    try:
        body = json.loads(text) if text else None
    except json.JSONDecodeError:
        body = None
    return HttpResult(status, body, text)


class TlsJsonRpcClient:
    def __init__(
        self,
        url: str,
        *,
        token: str | None = None,
        ca_file: Path | None = None,
        timeout: float = 10.0,
    ) -> None:
        self.url = url
        self.token = token
        self.ca_file = ca_file
        self.timeout = timeout
        self._ids = 0

    def call(self, method: str, params: dict[str, Any], timeout: float | None = None) -> Any:
        self._ids += 1
        result = http_json(
            self.url,
            payload={
                "jsonrpc": "2.0",
                "id": f"p2a-{self._ids}",
                "method": method,
                "params": params,
            },
            token=self.token,
            ca_file=self.ca_file,
            timeout=timeout or self.timeout,
        )
        if result.status >= 400:
            raise RuntimeError(f"HTTP {result.status}: {result.text[:500]}")
        if not isinstance(result.body, dict):
            raise RuntimeError("JSON-RPC response is not an object")
        if "error" in result.body:
            raise JsonRpcError(result.body["error"])
        return result.body.get("result")


class GatewayClient:
    """Java Gateway 内部 JSON-RPC 的薄客户端。"""

    def __init__(self, base_url: str, token: str, timeout: float = 10.0) -> None:
        self.url = base_url.rstrip("/") + "/internal/ai/v1/mcp"
        self.token = token
        self._client = TlsJsonRpcClient(self.url, token=token, timeout=timeout)

    def call(self, method: str, params: dict[str, Any], timeout: float | None = None) -> Any:
        return self._client.call(method, params, timeout=timeout)


class HostClient:
    """Python Host 的测试端点客户端；Bearer 逐请求透传。"""

    def __init__(self, base_url: str, token: str, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        self._ids = 0

    def list_tools(self) -> HttpResult:
        self._ids += 1
        return http_json(
            f"{self.base_url}/v1/mcp/list",
            payload={"requestId": f"p2a-host-list-{self._ids}"},
            token=self.token,
            timeout=self.timeout,
        )

    def call_tool(
        self,
        *,
        name: str,
        catalog_version: str,
        tool_schema_hash: str,
        arguments: dict[str, Any],
        call_id: str,
    ) -> HttpResult:
        self._ids += 1
        return http_json(
            f"{self.base_url}/v1/mcp/call",
            payload={
                "requestId": f"p2a-host-call-{self._ids}",
                "params": {
                    "schemaVersion": 1,
                    "name": name,
                    "catalogVersion": catalog_version,
                    "toolSchemaHash": tool_schema_hash,
                    "callId": call_id,
                    "arguments": arguments,
                },
            },
            token=self.token,
            timeout=self.timeout,
        )


def host_result(result: HttpResult) -> Any:
    if result.status != 200 or not isinstance(result.body, dict):
        raise RuntimeError(f"Host HTTP {result.status}: {result.text[:500]}")
    return result.body.get("result")


def host_error_kind(result: HttpResult) -> str:
    body = result.body
    if not isinstance(body, dict):
        return f"HTTP_{result.status}"
    error = body.get("error")
    if isinstance(error, dict):
        data = error.get("data")
        if isinstance(data, dict) and isinstance(data.get("kind"), str):
            return str(data["kind"])
        if isinstance(error.get("kind"), str):
            return str(error["kind"])
    if isinstance(body.get("kind"), str):
        return str(body["kind"])
    return f"HTTP_{result.status}"


__all__ = [
    "GatewayClient",
    "HostClient",
    "HttpResult",
    "TlsJsonRpcClient",
    "host_error_kind",
    "host_result",
    "http_json",
]
