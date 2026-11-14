"""Java MCP Gateway 的 Python Host 客户端（只读内部协议适配）。

安全与职责边界：

- 唯一目标是配置的 Java Gateway base URL + ``/internal/ai/v1/mcp``；不提供任意 URL 参数，
  拒绝非 HTTP(S)、带 user-info/query/fragment/路径的 base URL，且不跟随重定向；
- 每次调用只使用调用方注入的 Bearer 原值，不签发、不缓存、不落盘、不打日志；
- 只读、单次请求，不重试、不判定授权、不自动补工具参数、不自动确认 Schema；
- Java 的 JSON-RPC ``error.data.kind`` 原样映射为 :class:`McpHostError`，不拼接远端消息；
- ``tools/call`` 结果保留 ``outcome/untrusted/truncated``，不做 boolean 化或空对象成功。
"""

from __future__ import annotations

import logging
import math
import time
from collections.abc import Mapping
from typing import Final
from urllib.parse import urlsplit

import httpx
from pydantic import ValidationError

from ai_collab_worker import __version__
from ai_collab_worker.contract.mcp import (
    MCP_GATEWAY_PATH,
    MCP_PROTOCOL_VERSION,
    McpCatalogError,
    McpClientInfo,
    McpErrorKind,
    McpHostError,
    McpInitializeParams,
    McpInitializeRequest,
    McpInitializeResult,
    McpJsonRpcErrorResponse,
    McpJsonRpcSuccess,
    McpPingParams,
    McpPingRequest,
    McpPingResult,
    McpRequest,
    McpRequestId,
    McpToolCallResult,
    McpToolsCallParams,
    McpToolsCallRequest,
    McpToolsListParams,
    McpToolsListRequest,
    McpToolsListResult,
    normalize_tools_list_result,
    validate_tool_call_result,
)

logger: Final[logging.Logger] = logging.getLogger(__name__)

DEFAULT_TIMEOUT_SECONDS: Final[float] = 30.0


class McpHostClient:
    """``POST /internal/ai/v1/mcp`` 的异步客户端；生命周期由调用方管理。"""

    def __init__(
        self,
        *,
        base_url: str,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("MCP Host 超时必须是正有限秒数")
        self._endpoint: Final[str] = _mcp_endpoint(base_url)
        self._client: Final[httpx.AsyncClient] = httpx.AsyncClient(
            transport=transport,
            timeout=httpx.Timeout(timeout_seconds),
            follow_redirects=False,
            # 不读取 HTTP(S)_PROXY 等环境，避免把内部调用改道到环境指定 host。
            trust_env=False,
        )

    async def aclose(self) -> None:
        """关闭底层连接池。"""

        await self._client.aclose()

    async def initialize(
        self,
        *,
        bearer_token: str,
        request_id: McpRequestId,
        client_name: str = "ai-collab-ai-worker",
        client_version: str = __version__,
        protocol_version: str = MCP_PROTOCOL_VERSION,
    ) -> McpInitializeResult:
        request = McpInitializeRequest(
            id=request_id,
            params=McpInitializeParams(
                protocol_version=protocol_version,
                client=McpClientInfo(name=client_name, version=client_version),
            ),
        )
        payload = await self._post(
            request,
            bearer_token=bearer_token,
            operation="initialize",
        )
        try:
            result = McpInitializeResult.model_validate(payload)
        except ValidationError as exc:
            raise McpHostError(McpErrorKind.INTERNAL) from exc
        if result.protocol_version != protocol_version:
            raise McpHostError(McpErrorKind.INTERNAL)
        return result

    async def ping(
        self,
        *,
        bearer_token: str,
        request_id: McpRequestId,
    ) -> McpPingResult:
        request = McpPingRequest(id=request_id, params=McpPingParams())
        payload = await self._post(
            request,
            bearer_token=bearer_token,
            operation="ping",
        )
        try:
            return McpPingResult.model_validate(payload)
        except ValidationError as exc:
            raise McpHostError(McpErrorKind.INTERNAL) from exc

    async def list_tools(
        self,
        *,
        bearer_token: str,
        request_id: McpRequestId,
    ) -> McpToolsListResult:
        request = McpToolsListRequest(id=request_id, params=McpToolsListParams())
        payload = await self._post(
            request,
            bearer_token=bearer_token,
            operation="tools/list",
        )
        try:
            result = McpToolsListResult.model_validate(payload)
        except ValidationError as exc:
            raise McpHostError(McpErrorKind.SCHEMA_DRIFT) from exc
        try:
            return normalize_tools_list_result(result)
        except McpCatalogError as exc:
            raise McpHostError(McpErrorKind.SCHEMA_DRIFT) from exc

    async def call_tool(
        self,
        *,
        bearer_token: str,
        request_id: McpRequestId,
        call_id: str,
        name: str,
        catalog_version: str,
        tool_schema_hash: str,
        arguments: Mapping[str, object],
    ) -> McpToolCallResult:
        """执行一次只读工具调用；``arguments`` 原样发送，不合并/补默认值。"""

        request = McpToolsCallRequest(
            id=request_id,
            params=McpToolsCallParams(
                name=name,
                catalog_version=catalog_version,
                tool_schema_hash=tool_schema_hash,
                call_id=call_id,
                arguments=dict(arguments),
            ),
        )
        payload = await self._post(
            request,
            bearer_token=bearer_token,
            operation="tools/call",
        )
        try:
            result = McpToolCallResult.model_validate(payload)
        except ValidationError as exc:
            raise McpHostError(McpErrorKind.INTERNAL) from exc
        return validate_tool_call_result(result)

    async def _post(
        self,
        request: McpRequest,
        *,
        bearer_token: str,
        operation: str,
    ) -> dict[str, object]:
        headers = _auth_headers(bearer_token)
        body = request.model_dump(by_alias=True, mode="json")
        started = time.perf_counter()
        try:
            response = await self._client.post(
                self._endpoint,
                json=body,
                headers=headers,
            )
        except httpx.ConnectTimeout as exc:
            self._log_failure(operation, request.id, None, McpErrorKind.NOT_EXECUTED, started)
            raise McpHostError(McpErrorKind.NOT_EXECUTED) from exc
        except httpx.ConnectError as exc:
            self._log_failure(operation, request.id, None, McpErrorKind.NOT_EXECUTED, started)
            raise McpHostError(McpErrorKind.NOT_EXECUTED) from exc
        except httpx.TimeoutException as exc:
            # 写/读/连接池超时不能证明请求未发出，也不能证明远端未执行。
            self._log_failure(
                operation,
                request.id,
                None,
                McpErrorKind.OUTCOME_UNKNOWN,
                started,
            )
            raise McpHostError(McpErrorKind.OUTCOME_UNKNOWN) from exc
        except httpx.HTTPError as exc:
            self._log_failure(
                operation,
                request.id,
                None,
                McpErrorKind.OUTCOME_UNKNOWN,
                started,
            )
            raise McpHostError(McpErrorKind.OUTCOME_UNKNOWN) from exc

        if 300 <= response.status_code < 400:
            self._log_failure(
                operation,
                request.id,
                response.status_code,
                McpErrorKind.INTERNAL,
                started,
            )
            raise McpHostError(McpErrorKind.INTERNAL)
        if response.status_code == 401:
            self._log_failure(
                operation,
                request.id,
                response.status_code,
                McpErrorKind.UNAUTHORIZED,
                started,
            )
            raise McpHostError(McpErrorKind.UNAUTHORIZED)

        payload = _json_payload(response)
        if response.status_code >= 300:
            error = _typed_rpc_error(payload, expected_id=request.id)
            if error is not None:
                self._log_rpc_failure(operation, request.id, response, error, started)
                raise error
            mapped = _http_error(response.status_code, operation=operation)
            self._log_failure(operation, request.id, response.status_code, mapped.kind, started)
            raise mapped

        error = _typed_rpc_error(payload, expected_id=request.id)
        if error is not None:
            self._log_rpc_failure(operation, request.id, response, error, started)
            raise error
        if payload is None:
            self._log_failure(
                operation,
                request.id,
                response.status_code,
                McpErrorKind.INTERNAL,
                started,
            )
            raise McpHostError(McpErrorKind.INTERNAL)

        try:
            envelope = McpJsonRpcSuccess.model_validate(payload)
        except ValidationError as exc:
            self._log_failure(
                operation,
                request.id,
                response.status_code,
                McpErrorKind.INTERNAL,
                started,
            )
            raise McpHostError(McpErrorKind.INTERNAL) from exc
        if envelope.id != request.id:
            self._log_failure(
                operation,
                request.id,
                response.status_code,
                McpErrorKind.INTERNAL,
                started,
            )
            raise McpHostError(McpErrorKind.INTERNAL)

        logger.info(
            "mcp host ok operation=%s request_id=%s status=%s elapsed_ms=%d",
            operation,
            request.id,
            response.status_code,
            _elapsed_ms(started),
        )
        return envelope.result

    def _log_rpc_failure(
        self,
        operation: str,
        request_id: McpRequestId,
        response: httpx.Response,
        error: McpHostError,
        started: float,
    ) -> None:
        logger.warning(
            "mcp host rpc failure operation=%s request_id=%s status=%s kind=%s elapsed_ms=%d",
            operation,
            request_id,
            response.status_code,
            error.kind.value,
            _elapsed_ms(started),
        )

    def _log_failure(
        self,
        operation: str,
        request_id: McpRequestId,
        status: int | None,
        kind: McpErrorKind,
        started: float,
    ) -> None:
        logger.warning(
            "mcp host failure operation=%s request_id=%s status=%s kind=%s elapsed_ms=%d",
            operation,
            request_id,
            status,
            kind.value,
            _elapsed_ms(started),
        )


def _mcp_endpoint(base_url: str) -> str:
    """校验并构造唯一内部端点；不接受任意路径或 URL 覆盖。"""

    if not base_url or base_url != base_url.strip():
        raise ValueError("Java Gateway base URL 不得为空或含首尾空白")
    if any(ord(character) < 0x20 for character in base_url):
        raise ValueError("Java Gateway base URL 不得含控制字符")

    parsed = urlsplit(base_url)
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise ValueError("Java Gateway base URL 仅允许 http/https")
    if not parsed.hostname:
        raise ValueError("Java Gateway base URL 缺少 host")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("Java Gateway base URL 不得含 user-info")
    if parsed.query or parsed.fragment:
        raise ValueError("Java Gateway base URL 不得含 query/fragment")
    if parsed.path not in {"", "/"}:
        raise ValueError("Java Gateway base URL 不得带路径；MCP 路径由 Host 固定")
    try:
        _ = parsed.port
    except ValueError as exc:
        raise ValueError("Java Gateway base URL 端口非法") from exc

    return f"{scheme}://{parsed.netloc}{MCP_GATEWAY_PATH}"


def _auth_headers(bearer_token: str) -> dict[str, str]:
    if not bearer_token or bearer_token != bearer_token.strip():
        raise ValueError("调用方必须注入非空 Bearer token（不得含首尾空白）")
    if "\r" in bearer_token or "\n" in bearer_token:
        raise ValueError("Bearer token 不得含换行")
    return {
        "Accept": "application/json",
        "Authorization": f"Bearer {bearer_token}",
        "Content-Type": "application/json",
    }


def _json_payload(response: httpx.Response) -> object | None:
    try:
        payload: object = response.json()
    except ValueError:
        return None
    return payload


def _typed_rpc_error(payload: object, *, expected_id: McpRequestId) -> McpHostError | None:
    if not isinstance(payload, dict) or "error" not in payload:
        return None
    try:
        parsed = McpJsonRpcErrorResponse.model_validate(payload)
    except ValidationError:
        return McpHostError(McpErrorKind.INTERNAL)
    if parsed.id != expected_id:
        return McpHostError(McpErrorKind.INTERNAL)
    return McpHostError(parsed.error.data.kind, rpc_code=parsed.error.code)


def _http_error(status: int, *, operation: str) -> McpHostError:
    if status == 401 or status == 403:
        return McpHostError(McpErrorKind.UNAUTHORIZED)
    if status == 429:
        return McpHostError(McpErrorKind.RATE_LIMITED)
    if status in {400, 413, 422}:
        return McpHostError(McpErrorKind.PARAM_INVALID)
    if status in {408, 504}:
        if operation == "tools/call":
            return McpHostError(McpErrorKind.OUTCOME_UNKNOWN)
        return McpHostError(McpErrorKind.DEADLINE_EXCEEDED)
    return McpHostError(McpErrorKind.INTERNAL)


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


__all__ = ["DEFAULT_TIMEOUT_SECONDS", "McpHostClient"]
