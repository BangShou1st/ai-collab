"""厂商契约测试（P1 契约 §5、§6）：四类厂商 × 非流式/流式/工具调用/用量/错误映射。

全部经 ``ModelRuntime.transport``（httpx2 ``MockTransport``）回放 ``tests/fixtures/providers/``
下的录制夹具，不访问任何真实网络。模型统一由 :func:`build_chat_model` 按 lease 构造，所以这里
同时验证了 lease → SDK 的端点换算（``baseUrl`` + ``apiPath``）与密钥下发位置。

重试次数设为 0：错误映射测试只关心第一次响应怎么被分类，不等 SDK 退避。
"""

from __future__ import annotations

import asyncio
import json
import secrets
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx2
import pytest
from langchain_core.messages import AIMessageChunk, BaseMessage, HumanMessage
from langchain_core.tools import tool

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.errors import (
    USAGE_PROVIDER_ERROR,
    USAGE_QUOTA_EXCEEDED,
    USAGE_TIMEOUT,
    classify,
    to_app_error,
)
from ai_collab_worker.models.factory import ModelRuntime, build_chat_model
from ai_collab_worker.models.lease import Lease
from ai_collab_worker.models.usage import UsageCallback

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "providers"

#: 高熵哨兵：每次进程不同，任何地方出现都只可能来自本测试的 lease。
SENTINEL = f"sk-sentinel-{secrets.token_hex(16)}"


@dataclass(frozen=True)
class Provider:
    name: str
    protocol: str
    base_url: str
    api_path: str
    model: str
    #: 请求里携带密钥的位置。
    key_header: str
    expected_path: str
    expected_stream_path: str


PROVIDERS: tuple[Provider, ...] = (
    Provider(
        "openai",
        "OPENAI_CHAT",
        "https://api.openai.test",
        "/v1/chat/completions",
        "gpt-4o-mini",
        "authorization",
        "/v1/chat/completions",
        "/v1/chat/completions",
    ),
    Provider(
        "openai_compatible",
        "OPENAI_CHAT",
        "http://ollama.test:11434",
        "/v1/chat/completions",
        "qwen2.5:7b",
        "authorization",
        "/v1/chat/completions",
        "/v1/chat/completions",
    ),
    Provider(
        "anthropic",
        "ANTHROPIC_MESSAGES",
        "https://api.anthropic.test",
        "/v1/messages",
        "claude-sonnet-4-6",
        # anthropic SDK 0.7x 起 api_key 走 ``Authorization: Bearer``（不再是 x-api-key）。
        "authorization",
        "/v1/messages",
        "/v1/messages",
    ),
    Provider(
        "gemini",
        "GEMINI_GENERATE_CONTENT",
        "https://gemini.test",
        "/v1beta/models/gemini-2.5-flash:generateContent",
        "gemini-2.5-flash",
        "x-goog-api-key",
        "/v1beta/models/gemini-2.5-flash:generateContent",
        "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
    ),
)
IDS = [p.name for p in PROVIDERS]


@dataclass
class Replay:
    """按场景回放夹具的 httpx2 handler，并记录请求。"""

    provider: Provider
    scenario: str = "text"
    requests: list[httpx2.Request] = field(default_factory=list)

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        if self.scenario == "timeout":
            raise httpx2.ReadTimeout("read timed out", request=request)
        if self.scenario.startswith("error_"):
            body = _fixture(self.provider, f"{self.scenario}.json").replace("{{API_KEY}}", SENTINEL)
            return httpx2.Response(
                int(self.scenario.removeprefix("error_")),
                content=body.encode("utf-8"),
                headers={"content-type": "application/json"},
            )
        if _is_stream(request):
            return httpx2.Response(
                200,
                content=_fixture(self.provider, f"{self.scenario}_stream.sse").encode("utf-8"),
                headers={"content-type": "text/event-stream"},
            )
        return httpx2.Response(
            200,
            content=_fixture(self.provider, f"{self.scenario}.json").encode("utf-8"),
            headers={"content-type": "application/json"},
        )


def _fixture(provider: Provider, name: str) -> str:
    return (FIXTURES / provider.name / name).read_text(encoding="utf-8")


def _is_stream(request: httpx2.Request) -> bool:
    if ":streamGenerateContent" in request.url.path:
        return True
    if not request.content:
        return False
    try:
        body = json.loads(request.content)
    except ValueError:
        return False
    return isinstance(body, dict) and body.get("stream") is True


def _lease(provider: Provider) -> Lease:
    return Lease.model_validate(
        {
            "leaseId": str(uuid.uuid4()),
            "protocol": provider.protocol,
            "providerType": provider.name.upper(),
            "baseUrl": provider.base_url,
            "apiPath": provider.api_path,
            "model": provider.model,
            "apiKey": SENTINEL,
            "params": {"temperature": 0.0, "maxOutputTokens": 256},
            "capabilities": ["STREAMING", "TOOLS", "JSON"],
            "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
        }
    )


def _run[T](provider: Provider, scenario: str, body: Callable[[Any], Any]) -> tuple[T, Replay]:
    replay = Replay(provider, scenario)
    runtime = ModelRuntime(
        timeout_seconds=5.0, max_retries=0, transport=httpx2.MockTransport(replay)
    )

    async def main() -> T:
        built = build_chat_model(_lease(provider), runtime)
        try:
            result: T = await body(built.model)
            return result
        finally:
            await built.aclose()

    return asyncio.run(main()), replay


@tool
def get_weather(city: str) -> str:
    """查询城市天气。"""

    return f"sunny in {city}"


PROMPT: list[BaseMessage] = [HumanMessage(content="hi")]


# --------------------------------------------------------------------------- #
# 非流式 / 流式文本 + 用量
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_non_streaming_text_and_usage(provider: Provider) -> None:
    callback = UsageCallback()

    async def body(model: Any) -> Any:
        return await model.ainvoke(PROMPT, config={"callbacks": [callback]})

    message, replay = _run(provider, "text", body)

    assert message.text == "Hello world"
    assert message.usage_metadata["input_tokens"] == 12
    assert message.usage_metadata["output_tokens"] == 3
    assert (callback.input_tokens, callback.output_tokens) == (12, 3)
    _assert_endpoint_and_key(provider, replay, stream=False)


@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_streaming_text_token_by_token_and_usage(provider: Provider) -> None:
    callback = UsageCallback()

    async def body(model: Any) -> Any:
        chunks: list[AIMessageChunk] = []
        async for chunk in model.astream(PROMPT, config={"callbacks": [callback]}):
            chunks.append(chunk)
        return chunks

    chunks, replay = _run(provider, "text", body)

    pieces = [c.text for c in chunks if c.text]
    assert pieces == ["Hello", " world"]
    merged = chunks[0]
    for chunk in chunks[1:]:
        merged = merged + chunk
    assert merged.text == "Hello world"
    assert merged.usage_metadata is not None
    assert merged.usage_metadata["input_tokens"] == 12
    assert merged.usage_metadata["output_tokens"] == 3
    assert (callback.input_tokens, callback.output_tokens) == (12, 3)
    _assert_endpoint_and_key(provider, replay, stream=True)


# --------------------------------------------------------------------------- #
# 工具调用（非流式 / 流式分片合并）+ 结构化输出
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_tool_call_non_streaming(provider: Provider) -> None:
    async def body(model: Any) -> Any:
        return await model.bind_tools([get_weather]).ainvoke(PROMPT)

    message, replay = _run(provider, "tool", body)

    assert len(message.tool_calls) == 1
    call = message.tool_calls[0]
    assert call["name"] == "get_weather"
    assert call["args"] == {"city": "Paris"}
    assert message.usage_metadata["input_tokens"] == 40
    assert message.usage_metadata["output_tokens"] == 9
    sent = json.loads(replay.requests[0].content)
    assert "get_weather" in json.dumps(sent)


@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_tool_call_streaming_chunks_are_merged(provider: Provider) -> None:
    async def body(model: Any) -> Any:
        return [chunk async for chunk in model.bind_tools([get_weather]).astream(PROMPT)]

    chunks, _replay = _run(provider, "tool", body)

    if provider.name != "gemini":
        # OpenAI / 兼容端点 / Anthropic 的参数是分片下发的，必须真的经历了合并。
        arg_pieces = [tc.get("args") for c in chunks for tc in c.tool_call_chunks if tc.get("args")]
        assert len(arg_pieces) >= 3
    merged = chunks[0]
    for chunk in chunks[1:]:
        merged = merged + chunk
    assert len(merged.tool_calls) == 1
    assert merged.tool_calls[0]["name"] == "get_weather"
    assert merged.tool_calls[0]["args"] == {"city": "Paris"}
    assert merged.usage_metadata is not None
    assert merged.usage_metadata["input_tokens"] == 40
    assert merged.usage_metadata["output_tokens"] == 9


@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_structured_json_output(provider: Provider) -> None:
    schema = {
        "title": "get_weather",
        "description": "查询城市天气。",
        "type": "object",
        "properties": {"city": {"type": "string"}},
        "required": ["city"],
    }

    async def body(model: Any) -> Any:
        structured = model.with_structured_output(schema, method="function_calling")
        return await structured.ainvoke(PROMPT)

    result, _replay = _run(provider, "tool", body)

    assert result == {"city": "Paris"}


# --------------------------------------------------------------------------- #
# 错误映射：401 / 429 / 5xx / 超时（非流式与流式）
# --------------------------------------------------------------------------- #

ERROR_CASES = [
    ("error_401", ErrorCode.MODEL_UNAVAILABLE, 503, False, USAGE_PROVIDER_ERROR),
    ("error_429", ErrorCode.MODEL_RATE_LIMITED, 429, True, USAGE_QUOTA_EXCEEDED),
    ("error_500", ErrorCode.MODEL_UNAVAILABLE, 503, True, USAGE_PROVIDER_ERROR),
    ("timeout", ErrorCode.DEADLINE_EXCEEDED, 504, True, USAGE_TIMEOUT),
]


@pytest.mark.parametrize("stream", [False, True], ids=["invoke", "stream"])
@pytest.mark.parametrize(
    ("scenario", "code", "status", "retryable", "usage_status"),
    ERROR_CASES,
    ids=[case[0] for case in ERROR_CASES],
)
@pytest.mark.parametrize("provider", PROVIDERS, ids=IDS)
def test_error_mapping(
    provider: Provider,
    scenario: str,
    code: ErrorCode,
    status: int,
    retryable: bool,
    usage_status: str,
    stream: bool,
) -> None:
    async def body(model: Any) -> Any:
        try:
            if stream:
                async for _chunk in model.astream(PROMPT):
                    pass
            else:
                await model.ainvoke(PROMPT)
        except Exception as exc:  # 交给分类器
            return exc
        msg = "预期模型调用失败"
        raise AssertionError(msg)

    raised, _replay = _run(provider, scenario, body)

    kind = classify(raised)
    assert kind.usage_status == usage_status
    wrapped = to_app_error(raised)
    assert isinstance(wrapped, AppError)
    assert (wrapped.code, wrapped.status_code, wrapped.retryable) == (code, status, retryable)
    # 包装后的错误只带固定消息与类名，厂商回显的密钥不会被带出边界。
    envelope = json.dumps(wrapped.to_envelope().model_dump(mode="json"), ensure_ascii=False)
    for text in (str(wrapped), repr(wrapped), envelope):
        assert SENTINEL not in text


# --------------------------------------------------------------------------- #
# 工具
# --------------------------------------------------------------------------- #


def _assert_endpoint_and_key(provider: Provider, replay: Replay, *, stream: bool) -> None:
    assert len(replay.requests) == 1
    request = replay.requests[0]
    assert request.url.path == (provider.expected_stream_path if stream else provider.expected_path)
    assert request.url.host == httpx2.URL(provider.base_url).host
    assert SENTINEL in request.headers.get(provider.key_header, "")
    # 密钥只在约定的请求头里，绝不出现在 URL 查询串（Gemini 的旧式 ?key=）。
    assert SENTINEL not in str(request.url)
