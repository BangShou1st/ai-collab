"""Java 内部模型网关客户端（unit，离线）：请求形状、失败分类与日志脱敏。"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from ai_collab_worker.clients.java_gateway import JavaGatewayClient, ModelCallResult
from ai_collab_worker.contract.common import AppError, ErrorCode

BASE_URL = "http://gateway.test"
TOKEN = "unit-test-service-token"
PROMPT_MARKER = "PROMPT-BODY-MARKER"
SYSTEM_MARKER = "SYSTEM-BODY-MARKER"

Handler = Callable[[httpx.Request], httpx.Response]


def _client(handler: Handler, *, token: str = TOKEN) -> JavaGatewayClient:
    return JavaGatewayClient(
        base_url=BASE_URL,
        service_token=token,
        transport=httpx.MockTransport(handler),
    )


async def _call_and_close(
    client: JavaGatewayClient,
    *,
    deadline_ms: int = 2500,
    traceparent: str | None = "00-trace-span-01",
) -> ModelCallResult:
    try:
        return await client.call_model(
            system_prompt=SYSTEM_MARKER,
            user_prompt=PROMPT_MARKER,
            deadline_ms=deadline_ms,
            call_id="call-1",
            traceparent=traceparent,
        )
    finally:
        await client.aclose()


def _success_payload() -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "content": '{"0": 0.9}',
        "usage": {"inputTokens": 11, "outputTokens": 3, "source": "PROVIDER"},
        "modelAlias": "rerank-default",
        "latencyMs": 812,
    }


def test_request_shape_and_headers() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=_success_payload())

    result = asyncio.run(_call_and_close(_client(handler)))

    assert len(captured) == 1
    request = captured[0]
    assert request.method == "POST"
    assert str(request.url) == f"{BASE_URL}/internal/ai/v1/model-calls"
    assert request.headers["authorization"] == f"Bearer {TOKEN}"
    assert request.headers["x-call-id"] == "call-1"
    assert request.headers["traceparent"] == "00-trace-span-01"
    body = json.loads(request.content)
    assert body == {
        "schemaVersion": 1,
        "purpose": "KNOWLEDGE_CHAT",
        "systemPrompt": SYSTEM_MARKER,
        "userPrompt": PROMPT_MARKER,
        "outputFormat": "PROMPT_JSON",
        "deadlineMs": 2500,
        "callId": "call-1",
    }
    assert result.content == '{"0": 0.9}'
    assert result.model_alias == "rerank-default"
    assert result.latency_ms == 812
    assert result.usage is not None
    assert result.usage.source.value == "PROVIDER"
    assert result.usage.input_tokens == 11
    assert result.usage.output_tokens == 3


def test_output_format_is_prompt_json_not_json_object() -> None:
    """Java 网关拒绝 JSON_OBJECT（provider 无 STRUCTURED_OUTPUT）；这里锁死偏差。"""

    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=_success_payload())

    asyncio.run(_call_and_close(_client(handler)))

    body = json.loads(captured[0].content)
    assert body["outputFormat"] == "PROMPT_JSON"
    assert body["outputFormat"] != "JSON_OBJECT"


def test_empty_token_omits_authorization_header() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=_success_payload())

    asyncio.run(_call_and_close(_client(handler, token="")))

    assert "authorization" not in captured[0].headers


def test_absent_traceparent_is_not_sent() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=_success_payload())

    asyncio.run(_call_and_close(_client(handler), traceparent=None))

    assert "traceparent" not in captured[0].headers


@pytest.mark.parametrize("exc_type", [httpx.ReadTimeout, httpx.ConnectTimeout])
def test_timeout_is_deadline_exceeded(exc_type: type[httpx.TimeoutException]) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise exc_type("timeout", request=request)

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.DEADLINE_EXCEEDED
    assert error.status_code == 504
    assert error.retryable is True


def test_connection_error_is_model_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.status_code == 503
    assert error.retryable is True


def test_rate_limited_maps_to_429() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, text="slow down")

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.MODEL_RATE_LIMITED
    assert error.status_code == 429
    assert error.retryable is True


@pytest.mark.parametrize("status", [500, 502, 503, 504])
def test_server_error_is_model_unavailable(status: int) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, text="upstream down")

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.status_code == 503
    assert error.retryable is True
    assert error.details == {"upstreamStatus": status}


@pytest.mark.parametrize("status", [400, 401, 403, 404, 422])
def test_client_error_is_model_unavailable_without_retry(status: int) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, text="bad request")

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.status_code == 503
    assert error.retryable is False


@pytest.mark.parametrize(
    "payload",
    [
        b"not-json",
        b'["array"]',
        b'{"schemaVersion":2,"content":"{}"}',
        b'{"content":"{}"}',
        b'{"schemaVersion":1}',
        b'{"schemaVersion":1,"content":123}',
    ],
)
def test_invalid_response_payload_is_model_unavailable(payload: bytes) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=payload, headers={"content-type": "application/json"})

    with pytest.raises(AppError) as excinfo:
        asyncio.run(_call_and_close(_client(handler)))

    error = excinfo.value
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.status_code == 503


def test_empty_content_is_passed_through_for_parser_to_reject() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"schemaVersion": 1, "content": ""})

    result = asyncio.run(_call_and_close(_client(handler)))

    assert result.content == ""
    assert result.usage is None


def test_missing_usage_is_reported_as_none_for_endpoint_to_mark_unknown() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"schemaVersion": 1, "content": "{}"})

    result = asyncio.run(_call_and_close(_client(handler)))

    assert result.usage is None


@pytest.mark.parametrize(
    ("usage", "expected_source", "expected_tokens"),
    [
        ({"inputTokens": 1, "outputTokens": 2, "source": "ESTIMATE"}, "ESTIMATE", (1, 2)),
        ({"inputTokens": 1, "outputTokens": 2, "source": "WEIRD"}, "UNKNOWN", (1, 2)),
        (
            {"inputTokens": -1, "outputTokens": "2", "source": "PROVIDER"},
            "PROVIDER",
            (None, None),
        ),
        (
            {"inputTokens": True, "outputTokens": 2.0, "source": "PROVIDER"},
            "PROVIDER",
            (None, None),
        ),
    ],
)
def test_usage_parsing_is_defensive(
    usage: object,
    expected_source: str,
    expected_tokens: tuple[int | None, int | None],
) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"schemaVersion": 1, "content": "{}", "usage": usage},
        )

    result = asyncio.run(_call_and_close(_client(handler)))

    assert result.usage is not None
    assert result.usage.source.value == expected_source
    assert (result.usage.input_tokens, result.usage.output_tokens) == expected_tokens


def test_non_object_usage_is_ignored() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"schemaVersion": 1, "content": "{}", "usage": "not-an-object"},
        )

    result = asyncio.run(_call_and_close(_client(handler)))

    assert result.usage is None


def test_logs_never_contain_token_or_prompt_text(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG, logger="ai_collab_worker.clients.java_gateway")

    def ok_handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_success_payload())

    def fail_handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="down")

    asyncio.run(_call_and_close(_client(ok_handler)))
    with pytest.raises(AppError):
        asyncio.run(_call_and_close(_client(fail_handler)))

    assert TOKEN not in caplog.text
    assert PROMPT_MARKER not in caplog.text
    assert SYSTEM_MARKER not in caplog.text
