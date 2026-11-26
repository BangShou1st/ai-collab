"""模型客户端工厂（P1 契约 §5）。

``chat_model(lease)`` 按 lease 的 ``protocol`` 选 LangChain 适配器：

| protocol | 适配器 | 说明 |
| --- | --- | --- |
| ``OPENAI_CHAT`` | ``ChatOpenAI`` | OpenAI 及兼容端点（Ollama、ModelBridge 等）；Chat Completions |
| ``ANTHROPIC_MESSAGES`` | ``ChatAnthropic`` | |
| ``GEMINI_GENERATE_CONTENT`` | ``ChatGoogleGenerativeAI`` | Gemini Developer API（不走 Vertex） |

``embedding_model(lease)`` 先为 P2 建好：``OPENAI_EMBEDDINGS`` → ``OpenAIEmbeddings``。

超时与重试由 :class:`ModelRuntime` 统一给出，不依赖各 SDK 的默认值。``transport`` 只给测试注入
录制夹具（``httpx2.MockTransport``）；生产路径为 ``None``，由 SDK 自建连接池。

端点换算（lease 的 ``baseUrl`` + ``apiPath`` → SDK 的 ``base_url``）：

- OpenAI：``apiPath`` 去掉 ``/chat/completions``（或 ``/embeddings``）后缀拼到 ``baseUrl``；
  ``apiPath`` 为空且 ``baseUrl`` 不带路径时补 ``/v1``；
- Anthropic：``apiPath`` 去掉 ``/v1/messages`` 后缀（SDK 自己拼 ``/v1/messages``）；
- Gemini：``apiPath`` 形如 ``{prefix}/{version}/models/{model}:generateContent``，取出 ``prefix``
  拼到 ``baseUrl``、``version`` 作为 ``api_version``；为空时用 ``v1beta``。

本模块只构造对象，不发请求；密钥只在构造时经 :meth:`Lease.reveal_key` 取一次，原样交给 SDK。
"""

from __future__ import annotations

import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Final
from urllib.parse import urlsplit

import httpx2
from langchain_anthropic import ChatAnthropic
from langchain_core.embeddings import Embeddings
from langchain_core.language_models import BaseChatModel
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from pydantic import SecretStr

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.lease import Lease, LeaseProtocol

#: 默认单次请求超时（秒）与重试次数（不含首次）。
DEFAULT_TIMEOUT_SECONDS: Final[float] = 60.0
DEFAULT_MAX_RETRIES: Final[int] = 2

#: Anthropic 必须显式给 ``max_tokens``；lease 未给时的缺省值。
DEFAULT_ANTHROPIC_MAX_TOKENS: Final[int] = 4096

#: Gemini 缺省 API 版本。
DEFAULT_GEMINI_API_VERSION: Final[str] = "v1beta"

_OPENAI_SUFFIXES: Final[tuple[str, ...]] = ("/chat/completions", "/embeddings")
_ANTHROPIC_SUFFIXES: Final[tuple[str, ...]] = ("/v1/messages", "/messages")
_GEMINI_PATH: Final[re.Pattern[str]] = re.compile(
    r"^(?P<prefix>.*?)/(?P<version>v\d+(?:alpha|beta)?\d*)(?:/.*)?$"
)


@dataclass(frozen=True)
class ModelRuntime:
    """内核统一的调用参数。"""

    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    max_retries: int = DEFAULT_MAX_RETRIES
    connect_timeout_seconds: float = 5.0
    #: 测试注入的 httpx2 传输层（录制夹具）；生产为 ``None``。
    transport: httpx2.AsyncBaseTransport | None = None


@dataclass(frozen=True)
class BuiltModel:
    """构造结果：模型 + 释放本侧自建连接池的回调（Gemini 由 SDK 自管，回调为空操作）。"""

    model: BaseChatModel
    aclose: Callable[[], Awaitable[None]]


async def _noop() -> None:
    return None


def build_chat_model(lease: Lease, runtime: ModelRuntime | None = None) -> BuiltModel:
    """按 lease 构造聊天模型；调用方负责在不再使用时 ``await built.aclose()``。"""

    rt = runtime or ModelRuntime()
    if lease.protocol is LeaseProtocol.OPENAI_CHAT:
        client = _async_http_client(rt)
        return BuiltModel(
            model=ChatOpenAI(**_openai_kwargs(lease, rt, client)), aclose=client.aclose
        )
    if lease.protocol is LeaseProtocol.ANTHROPIC_MESSAGES:
        client = _async_http_client(rt)
        return BuiltModel(
            model=ChatAnthropic(**_anthropic_kwargs(lease, rt, client)), aclose=client.aclose
        )
    if lease.protocol is LeaseProtocol.GEMINI_GENERATE_CONTENT:
        return BuiltModel(model=ChatGoogleGenerativeAI(**_gemini_kwargs(lease, rt)), aclose=_noop)
    raise _unsupported(lease)


def chat_model(lease: Lease, runtime: ModelRuntime | None = None) -> BaseChatModel:
    """契约 §5 的工厂入口：返回 ``BaseChatModel``（自建连接池随对象回收）。"""

    return build_chat_model(lease, runtime).model


def embedding_model(lease: Lease, runtime: ModelRuntime | None = None) -> Embeddings:
    """P2 用的 embedding 工厂；P1 只支持 ``OPENAI_EMBEDDINGS``。"""

    rt = runtime or ModelRuntime()
    if lease.protocol is not LeaseProtocol.OPENAI_EMBEDDINGS:
        raise _unsupported(lease)
    kwargs: dict[str, Any] = {
        "model": lease.model,
        "api_key": SecretStr(lease.reveal_key()),
        "base_url": openai_base_url(lease),
        "timeout": rt.timeout_seconds,
        "max_retries": rt.max_retries,
        "http_async_client": _async_http_client(rt),
        # 兼容端点没有 tiktoken 词表；按原文发送，长度由调用方控制。
        "check_embedding_ctx_length": False,
        "tiktoken_enabled": False,
    }
    return OpenAIEmbeddings(**kwargs)


# --------------------------------------------------------------------------- #
# 端点换算
# --------------------------------------------------------------------------- #


def _normalized_path(path: str | None) -> str:
    raw = (path or "").strip()
    if not raw:
        return ""
    if not raw.startswith("/"):
        raw = "/" + raw
    return raw.rstrip("/")


def _strip_suffix(path: str, suffixes: tuple[str, ...]) -> str:
    for suffix in suffixes:
        if path.endswith(suffix):
            return path[: -len(suffix)]
    return path


def openai_base_url(lease: Lease) -> str:
    path = _normalized_path(lease.api_path)
    if path:
        return lease.base_url + _strip_suffix(path, _OPENAI_SUFFIXES)
    if not urlsplit(lease.base_url).path.strip("/"):
        return lease.base_url + "/v1"
    return lease.base_url


def anthropic_base_url(lease: Lease) -> str:
    path = _normalized_path(lease.api_path)
    prefix = _strip_suffix(path, _ANTHROPIC_SUFFIXES)
    prefix = prefix.removesuffix("/v1")
    return lease.base_url + prefix


def gemini_endpoint(lease: Lease) -> tuple[str, str]:
    """返回 ``(base_url, api_version)``。"""

    path = _normalized_path(lease.api_path)
    match = _GEMINI_PATH.match(path) if path else None
    if match is None:
        return lease.base_url, DEFAULT_GEMINI_API_VERSION
    return lease.base_url + match.group("prefix"), match.group("version")


# --------------------------------------------------------------------------- #
# 各厂商参数
# --------------------------------------------------------------------------- #


def _async_http_client(rt: ModelRuntime) -> httpx2.AsyncClient:
    timeout = httpx2.Timeout(rt.timeout_seconds, connect=rt.connect_timeout_seconds)
    if rt.transport is not None:
        return httpx2.AsyncClient(transport=rt.transport, timeout=timeout)
    return httpx2.AsyncClient(timeout=timeout)


def _openai_kwargs(lease: Lease, rt: ModelRuntime, client: httpx2.AsyncClient) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "model": lease.model,
        "api_key": SecretStr(lease.reveal_key()),
        "base_url": openai_base_url(lease),
        "timeout": rt.timeout_seconds,
        "max_retries": rt.max_retries,
        "http_async_client": client,
        # 流式也要拿到 usage（stream_options.include_usage）。
        "stream_usage": True,
        # lease 协议是 Chat Completions；不让适配器自动切到 Responses API。
        "use_responses_api": False,
    }
    if lease.params.temperature is not None:
        kwargs["temperature"] = lease.params.temperature
    if lease.params.max_output_tokens is not None:
        kwargs["max_tokens"] = lease.params.max_output_tokens
    return kwargs


def _anthropic_kwargs(lease: Lease, rt: ModelRuntime, client: httpx2.AsyncClient) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "model": lease.model,
        "api_key": SecretStr(lease.reveal_key()),
        "base_url": anthropic_base_url(lease),
        "default_request_timeout": rt.timeout_seconds,
        "max_retries": rt.max_retries,
        "http_async_client": client,
        "max_tokens": lease.params.max_output_tokens or DEFAULT_ANTHROPIC_MAX_TOKENS,
        "stream_usage": True,
    }
    if lease.params.temperature is not None:
        kwargs["temperature"] = lease.params.temperature
    return kwargs


def _gemini_kwargs(lease: Lease, rt: ModelRuntime) -> dict[str, Any]:
    base_url, api_version = gemini_endpoint(lease)
    kwargs: dict[str, Any] = {
        "model": lease.model,
        "google_api_key": SecretStr(lease.reveal_key()),
        "base_url": base_url,
        "api_version": api_version,
        "timeout": rt.timeout_seconds,
        # Gemini 的 max_retries 是"总尝试次数"，且 0 表示 SDK 默认值：统一换算成 1 + 重试次数。
        "max_retries": rt.max_retries + 1,
        "vertexai": False,
    }
    if rt.transport is not None:
        kwargs["client_args"] = {"transport": rt.transport}
    if lease.params.temperature is not None:
        kwargs["temperature"] = lease.params.temperature
    if lease.params.max_output_tokens is not None:
        kwargs["max_output_tokens"] = lease.params.max_output_tokens
    return kwargs


def _unsupported(lease: Lease) -> AppError:
    return AppError(
        ErrorCode.MODEL_UNAVAILABLE,
        "lease 协议不受支持",
        status_code=503,
        retryable=False,
        details={"reason": "unsupported_protocol", "protocol": lease.protocol.value},
    )


__all__ = [
    "DEFAULT_ANTHROPIC_MAX_TOKENS",
    "DEFAULT_GEMINI_API_VERSION",
    "DEFAULT_MAX_RETRIES",
    "DEFAULT_TIMEOUT_SECONDS",
    "BuiltModel",
    "ModelRuntime",
    "anthropic_base_url",
    "build_chat_model",
    "chat_model",
    "embedding_model",
    "gemini_endpoint",
    "openai_base_url",
]
