"""可独立启动的 Python MCP Host 进程（P2-A 真实进程 E2E）。

该进程只把内部请求适配到固定的 Java Gateway ``/internal/ai/v1/mcp``：

- Gateway base URL 来自进程启动配置 ``MCP_HOST_GATEWAY_URL``，请求体不能改道；
- Bearer 由调用方逐请求注入并原样转给 Java，进程不读取、不缓存、不落盘令牌；
- 不直连外部 MCP、不访问业务库/Redis/MinIO、不重试、不判断授权、不补默认参数；
- Java 的 ``outcome/untrusted/truncated`` 与错误 ``kind`` 原样保留。

这不是生产主路径。默认 FastAPI app 不装配本模块；P2-A 用 Uvicorn 单独启动它，
验证跨进程 HTTP 契约。生产拓扑若要把 Host 插入 Agent 主路径，必须另立设计。
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Final, Literal

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from ai_collab_worker import __version__
from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.clients.mcp_host import McpHostClient
from ai_collab_worker.contract.common import SCHEMA_VERSION, ContractModel
from ai_collab_worker.contract.mcp import (
    McpErrorKind,
    McpHostError,
    McpRequestId,
    McpToolsCallParams,
)

DEFAULT_GATEWAY_URL: Final[str] = "http://app:8080"
DEFAULT_TIMEOUT_SECONDS: Final[float] = 30.0
HEALTH_PATH: Final[str] = "/healthz"
READY_PATH: Final[str] = "/readyz"

type HostOperation = Literal["initialize", "ping", "tools/list", "tools/call"]
type HostOutcome = Literal["SUCCEEDED", "ERROR"]


class HostInitializeRequest(ContractModel):
    """Host 测试端点请求；``requestId`` 只用于 Host 日志/证据关联。"""

    request_id: McpRequestId


class HostSimpleRequest(ContractModel):
    """``ping`` / ``tools/list`` 的 Host 测试端点请求。"""

    request_id: McpRequestId


class HostToolCallRequest(ContractModel):
    """``tools/call`` 的 Host 测试端点请求。

    直接复用冻结契约的 :class:`McpToolsCallParams`，因此请求体或 arguments 中自报
    ``projectRef/userRef`` 等身份字段会在 Host 侧被 422 拒绝，且不会触达 Java。
    """

    request_id: McpRequestId
    params: McpToolsCallParams


class HostErrorView(ContractModel):
    kind: McpErrorKind
    retryable: bool


class HostResponse(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION
    request_id: McpRequestId
    operation: HostOperation
    outcome: HostOutcome
    result: dict[str, Any] | None = None
    error: HostErrorView | None = None


def create_app(
    *,
    gateway_url: str | None = None,
    timeout_seconds: float | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    """装配 Host 进程。

    ``gateway_url`` 缺省读 ``MCP_HOST_GATEWAY_URL``。URL 形状由
    :class:`McpHostClient` 在启动期校验；传入非法 URL 时进程启动失败，不静默回退。
    """

    resolved_url = (
        gateway_url
        if gateway_url is not None
        else os.environ.get("MCP_HOST_GATEWAY_URL", DEFAULT_GATEWAY_URL)
    )
    resolved_timeout = (
        timeout_seconds
        if timeout_seconds is not None
        else float(os.environ.get("MCP_HOST_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)))
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        client = McpHostClient(
            base_url=resolved_url,
            timeout_seconds=resolved_timeout,
            transport=transport,
        )
        app.state.mcp_host_client = client
        try:
            yield
        finally:
            await client.aclose()

    app = FastAPI(
        title="AI Collab MCP Host",
        version=__version__,
        lifespan=lifespan,
    )
    install_error_handlers(app)

    @app.get(HEALTH_PATH)
    async def healthz() -> dict[str, str]:
        return {
            "status": "ok",
            "service": "ai-collab-mcp-host",
            "version": __version__,
        }

    @app.get(READY_PATH)
    async def readyz(request: Request) -> dict[str, str]:
        if not isinstance(getattr(request.app.state, "mcp_host_client", None), McpHostClient):
            return {"status": "not-ready"}
        return {"status": "ready"}

    @app.post("/v1/mcp/initialize", response_model=None)
    async def initialize_host(
        body: HostInitializeRequest,
        request: Request,
    ) -> JSONResponse:
        token = _bearer_token(request)
        if token is None:
            return _host_auth_error("initialize", body.request_id)
        try:
            result = await _client(request).initialize(
                bearer_token=token,
                request_id=body.request_id,
                client_name="ai-collab-mcp-host",
                client_version=__version__,
            )
        except McpHostError as error:
            return _upstream_error("initialize", body.request_id, error)
        return _success(
            "initialize",
            body.request_id,
            result.model_dump(by_alias=True, mode="json"),
        )

    @app.post("/v1/mcp/ping", response_model=None)
    async def ping_host(body: HostSimpleRequest, request: Request) -> JSONResponse:
        token = _bearer_token(request)
        if token is None:
            return _host_auth_error("ping", body.request_id)
        try:
            result = await _client(request).ping(
                bearer_token=token,
                request_id=body.request_id,
            )
        except McpHostError as error:
            return _upstream_error("ping", body.request_id, error)
        return _success(
            "ping",
            body.request_id,
            result.model_dump(by_alias=True, mode="json"),
        )

    @app.post("/v1/mcp/tools/list", response_model=None)
    @app.post("/v1/mcp/list", response_model=None)
    async def list_host_tools(body: HostSimpleRequest, request: Request) -> JSONResponse:
        token = _bearer_token(request)
        if token is None:
            return _host_auth_error("tools/list", body.request_id)
        try:
            result = await _client(request).list_tools(
                bearer_token=token,
                request_id=body.request_id,
            )
        except McpHostError as error:
            return _upstream_error("tools/list", body.request_id, error)
        return _success(
            "tools/list",
            body.request_id,
            result.model_dump(by_alias=True, mode="json"),
        )

    @app.post("/v1/mcp/tools/call", response_model=None)
    @app.post("/v1/mcp/call", response_model=None)
    async def call_host_tool(body: HostToolCallRequest, request: Request) -> JSONResponse:
        token = _bearer_token(request)
        if token is None:
            return _host_auth_error("tools/call", body.request_id)
        params = body.params
        try:
            result = await _client(request).call_tool(
                bearer_token=token,
                request_id=body.request_id,
                call_id=params.call_id,
                name=params.name,
                catalog_version=params.catalog_version,
                tool_schema_hash=params.tool_schema_hash,
                arguments=params.arguments,
            )
        except McpHostError as error:
            return _upstream_error("tools/call", body.request_id, error)
        return _success(
            "tools/call",
            body.request_id,
            result.model_dump(by_alias=True, mode="json"),
        )

    return app


def _client(request: Request) -> McpHostClient:
    client = getattr(request.app.state, "mcp_host_client", None)
    if not isinstance(client, McpHostClient):
        raise RuntimeError("MCP Host client 未初始化")
    return client


def _bearer_token(request: Request) -> str | None:
    value = request.headers.get("Authorization")
    if value is None or not value.startswith("Bearer "):
        return None
    token = value[7:].strip()
    return token or None


def _success(
    operation: HostOperation,
    request_id: McpRequestId,
    result: dict[str, Any],
) -> JSONResponse:
    response = HostResponse(
        request_id=request_id,
        operation=operation,
        outcome="SUCCEEDED",
        result=result,
    )
    return _json_response(200, response)


def _host_auth_error(operation: HostOperation, request_id: McpRequestId) -> JSONResponse:
    response = HostResponse(
        request_id=request_id,
        operation=operation,
        outcome="ERROR",
        error=HostErrorView(kind=McpErrorKind.UNAUTHORIZED, retryable=False),
    )
    return _json_response(401, response)


def _upstream_error(
    operation: HostOperation,
    request_id: McpRequestId,
    error: McpHostError,
) -> JSONResponse:
    response = HostResponse(
        request_id=request_id,
        operation=operation,
        outcome="ERROR",
        error=HostErrorView(kind=error.kind, retryable=error.retryable),
    )
    return _json_response(502, response)


def _json_response(status_code: int, response: HostResponse) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=response.model_dump(by_alias=True, mode="json", exclude_none=True),
        headers={"Cache-Control": "no-store"},
    )


app = create_app()


__all__ = ["app", "create_app"]
