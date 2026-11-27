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

import asyncio
import math
import re
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
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
from ai_collab_worker.models.errors import ProviderHttpError, ProviderInvalidOutputError
from ai_collab_worker.models.lease import EMBEDDING_PROTOCOLS, Lease, LeaseProtocol
from ai_collab_worker.retrieval.javatext import is_blank, trim

#: 默认单次请求超时（秒）与重试次数（不含首次）。
DEFAULT_TIMEOUT_SECONDS: Final[float] = 60.0
DEFAULT_MAX_RETRIES: Final[int] = 2
DEFAULT_RETRY_BACKOFF_SECONDS: Final[float] = 0.25

#: Gemini ``batchEmbedContents`` 单批上限与默认批大小（P2-R1 R1-4 / Java 语义 §3）。
GEMINI_EMBEDDING_MAX_BATCH: Final[int] = 100
DEFAULT_EMBEDDING_BATCH_SIZE: Final[int] = 32
MAX_EMBEDDING_DIMENSION: Final[int] = 4096
DEFAULT_RERANK_TIMEOUT_MS: Final[int] = 5000

#: Anthropic 必须显式给 ``max_tokens``；lease 未给时的缺省值。
DEFAULT_ANTHROPIC_MAX_TOKENS: Final[int] = 4096

#: Gemini 缺省 API 版本。
DEFAULT_GEMINI_API_VERSION: Final[str] = "v1beta"

_OPENAI_SUFFIXES: Final[tuple[str, ...]] = ("/chat/completions", "/embeddings")
_ANTHROPIC_SUFFIXES: Final[tuple[str, ...]] = ("/v1/messages", "/messages")
_GEMINI_PATH: Final[re.Pattern[str]] = re.compile(
    r"^(?P<prefix>.*?)/(?P<version>v\d+(?:alpha|beta)?\d*)(?:/.*)?$"
)


class EmbeddingTaskType(StrEnum):
    """向量化输入方向（对应 Java ``EmbeddingTaskType`` 与 Gemini ``taskType``）。"""

    DOCUMENT = "DOCUMENT"
    QUERY = "QUERY"


@dataclass(frozen=True)
class EmbeddingCallOutput:
    """一次（可分批）向量化调用的结果与累计输入 token。"""

    vectors: tuple[tuple[float, ...], ...]
    dimension: int
    input_tokens: int


@dataclass(frozen=True)
class RerankDocument:
    """reranker 输入候选：只带标题与正文（不含密钥或内部 ID）。"""

    heading: str | None
    text: str


class EmbeddingDimensionMismatchError(Exception):
    """向量模型实际返回维度与契约期望维度（1024）不符。"""

    def __init__(self, *, actual: int, expected: int, input_tokens: int = 0) -> None:
        super().__init__(f"embedding_dimension_mismatch:{actual}!={expected}")
        self.actual = actual
        self.expected = expected
        self.input_tokens = input_tokens


@dataclass(frozen=True)
class ModelRuntime:
    """内核统一的调用参数。"""

    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    max_retries: int = DEFAULT_MAX_RETRIES
    connect_timeout_seconds: float = 5.0
    retry_backoff_seconds: float = DEFAULT_RETRY_BACKOFF_SECONDS
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
    """LangChain Embedding 构造；双协议批量/无鉴权路径请用 :func:`call_embeddings`。"""

    rt = runtime or ModelRuntime()
    if lease.protocol is not LeaseProtocol.OPENAI_EMBEDDINGS:
        raise _unsupported(lease)
    key = lease.reveal_key_or_none() or "ollama"
    kwargs: dict[str, Any] = {
        "model": lease.model,
        "api_key": SecretStr(key),
        "base_url": openai_base_url(lease),
        "timeout": rt.timeout_seconds,
        "max_retries": rt.max_retries,
        "http_async_client": _async_http_client(rt),
        # 兼容端点没有 tiktoken 词表；按原文发送，长度由调用方控制。
        "check_embedding_ctx_length": False,
        "tiktoken_enabled": False,
    }
    if (
        lease.params.dimensions is not None
        and lease.params.dimensions > 0
        and lease.provider_type.upper() != "OLLAMA"
    ):
        kwargs["dimensions"] = lease.params.dimensions
    return OpenAIEmbeddings(**kwargs)


def prepare_embedding_inputs(texts: Sequence[str], *, task_type: EmbeddingTaskType) -> list[str]:
    """按 Java 现行规则构造向量化输入：文档侧用正文原文，查询侧只做 ``trim()``。"""

    if task_type is EmbeddingTaskType.DOCUMENT:
        return [str(text) for text in texts]
    return [trim(str(text)) for text in texts]


def effective_embedding_batch_size(lease: Lease) -> int:
    """单批上限：OpenAI 按 ``batchSize``，Gemini 按 ``min(batchSize, 100)``。"""

    configured = lease.params.batch_size or DEFAULT_EMBEDDING_BATCH_SIZE
    if lease.protocol is LeaseProtocol.GEMINI_EMBEDDINGS:
        return min(configured, GEMINI_EMBEDDING_MAX_BATCH)
    return configured


def openai_embeddings_url(lease: Lease) -> str:
    path = _normalized_path(lease.api_path)
    if path:
        return lease.base_url + path
    return openai_base_url(lease) + "/embeddings"


def gemini_batch_embeddings_url(lease: Lease) -> str:
    model_id = lease.model.removeprefix("models/")
    path = _normalized_path(lease.api_path)
    if path.endswith(":batchEmbedContents"):
        return lease.base_url + path
    if path.endswith(f"/models/{model_id}"):
        return f"{lease.base_url}{path}:batchEmbedContents"
    if ":generateContent" in path or ":embedContent" in path:
        base, version = gemini_endpoint(lease)
        return f"{base}/{version}/models/{model_id}:batchEmbedContents"
    prefix_path = path or f"/{DEFAULT_GEMINI_API_VERSION}"
    return f"{lease.base_url}{prefix_path}/models/{model_id}:batchEmbedContents"


def rerank_url(lease: Lease) -> str:
    path = _normalized_path(lease.api_path) or "/rerank"
    return lease.base_url + path


async def call_embeddings(
    lease: Lease,
    texts: Sequence[str],
    *,
    task_type: EmbeddingTaskType,
    expected_dimension: int | None = None,
    runtime: ModelRuntime | None = None,
) -> EmbeddingCallOutput:
    """执行 ``OPENAI_EMBEDDINGS`` 或原生 ``GEMINI_EMBEDDINGS`` 批量向量化。"""

    if lease.protocol not in EMBEDDING_PROTOCOLS:
        raise _unsupported(lease)
    inputs = prepare_embedding_inputs(texts, task_type=task_type)
    if not inputs:
        msg = "texts 不能为空"
        raise ValueError(msg)

    rt = runtime or ModelRuntime()
    batch_size = effective_embedding_batch_size(lease)
    vectors: list[tuple[float, ...]] = []
    expected_dim: int | None = None
    total_tokens = 0

    client = _async_http_client(rt)
    try:
        for start in range(0, len(inputs), batch_size):
            batch = inputs[start : start + batch_size]
            batch_vecs, batch_tokens = await _embed_batch_with_retry(
                client, lease, batch, task_type=task_type, runtime=rt
            )
            total_tokens += batch_tokens
            for vec in batch_vecs:
                if expected_dimension is not None and len(vec) != expected_dimension:
                    raise EmbeddingDimensionMismatchError(
                        actual=len(vec),
                        expected=expected_dimension,
                        input_tokens=total_tokens,
                    )
                if expected_dim is None:
                    expected_dim = len(vec)
                elif len(vec) != expected_dim:
                    raise ProviderInvalidOutputError("embedding_dimension_inconsistent")
                vectors.append(vec)
    finally:
        await client.aclose()

    assert expected_dim is not None
    return EmbeddingCallOutput(
        vectors=tuple(vectors),
        dimension=expected_dim,
        input_tokens=total_tokens,
    )


async def call_rerank(
    lease: Lease,
    *,
    query: str,
    documents: Sequence[RerankDocument],
    runtime: ModelRuntime | None = None,
) -> tuple[float, ...]:
    """执行 ``LLAMACPP_RERANK``（单次 HTTP、无重试，严格校验全部返回分数）。"""

    if lease.protocol is not LeaseProtocol.LLAMACPP_RERANK:
        raise _unsupported(lease)
    if is_blank(query):
        raise ProviderInvalidOutputError("rerank_query_blank")
    if not documents:
        msg = "documents 不能为空"
        raise ValueError(msg)
    max_candidates = lease.params.max_candidates
    if max_candidates is not None and len(documents) > max_candidates:
        msg = f"rerank 候选数 {len(documents)} 超过 maxCandidates={max_candidates}"
        raise ValueError(msg)

    payload_docs: list[str] = []
    for doc in documents:
        text = doc.text.strip()
        if not text:
            raise ProviderInvalidOutputError("rerank_document_blank")
        title = (doc.heading or "").strip()
        payload_docs.append(text if not title else f"{title}\n{text}")

    rt = runtime or ModelRuntime()
    timeout_seconds = (lease.params.timeout_ms or DEFAULT_RERANK_TIMEOUT_MS) / 1000.0
    timeout = httpx2.Timeout(
        timeout_seconds,
        connect=min(rt.connect_timeout_seconds, timeout_seconds),
    )
    client = (
        httpx2.AsyncClient(transport=rt.transport, timeout=timeout)
        if rt.transport is not None
        else httpx2.AsyncClient(timeout=timeout)
    )
    headers: dict[str, str] = {"Content-Type": "application/json", "Accept": "application/json"}
    if api_key := lease.reveal_key_or_none():
        headers["Authorization"] = f"Bearer {api_key}"

    try:
        response = await client.post(
            rerank_url(lease),
            json={"query": query, "documents": payload_docs, "top_n": len(payload_docs)},
            headers=headers,
        )
    finally:
        await client.aclose()

    if response.status_code < 200 or response.status_code >= 300:
        raise ProviderHttpError(response.status_code)
    return _parse_rerank_response(response, len(payload_docs))


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


async def _embed_batch_with_retry(
    client: httpx2.AsyncClient,
    lease: Lease,
    batch: Sequence[str],
    *,
    task_type: EmbeddingTaskType,
    runtime: ModelRuntime,
) -> tuple[list[tuple[float, ...]], int]:
    attempts = max(1, runtime.max_retries + 1)
    for attempt in range(attempts):
        try:
            if lease.protocol is LeaseProtocol.OPENAI_EMBEDDINGS:
                return await _embed_openai_batch(client, lease, batch)
            return await _embed_gemini_batch(client, lease, batch, task_type=task_type)
        except (httpx2.TimeoutException, httpx2.TransportError, OSError):
            if attempt + 1 >= attempts:
                raise
        except ProviderHttpError as exc:
            if exc.status_code < 500 or attempt + 1 >= attempts:
                raise
        delay = runtime.retry_backoff_seconds * (2**attempt)
        if delay > 0:
            await asyncio.sleep(delay)
    raise AssertionError("unreachable")  # pragma: no cover


async def _embed_openai_batch(
    client: httpx2.AsyncClient,
    lease: Lease,
    batch: Sequence[str],
) -> tuple[list[tuple[float, ...]], int]:
    headers: dict[str, str] = {"Content-Type": "application/json", "Accept": "application/json"}
    if api_key := lease.reveal_key_or_none():
        headers["Authorization"] = f"Bearer {api_key}"
    body: dict[str, Any] = {"model": lease.model, "input": list(batch)}
    if (
        lease.params.dimensions is not None
        and lease.params.dimensions > 0
        and lease.provider_type.upper() != "OLLAMA"
    ):
        body["dimensions"] = lease.params.dimensions

    response = await client.post(openai_embeddings_url(lease), json=body, headers=headers)
    if response.status_code < 200 or response.status_code >= 300:
        raise ProviderHttpError(response.status_code)
    payload = _decode_json_object(response)
    raw_data = payload.get("data")
    if not isinstance(raw_data, list) or len(raw_data) != len(batch):
        raise ProviderInvalidOutputError("openai_embedding_data_mismatch")
    if any(not isinstance(entry, Mapping) for entry in raw_data):
        raise ProviderInvalidOutputError("openai_embedding_entry_invalid")

    has_index = [entry.get("index") is not None for entry in raw_data]
    ordered: list[tuple[float, ...] | None] = [None] * len(batch)
    if any(has_index):
        if not all(has_index):
            raise ProviderInvalidOutputError("openai_embedding_index_partial")
        for entry in raw_data:
            idx = entry.get("index")
            if isinstance(idx, bool) or not isinstance(idx, int) or idx < 0 or idx >= len(batch):
                raise ProviderInvalidOutputError("openai_embedding_index_invalid")
            if ordered[idx] is not None:
                raise ProviderInvalidOutputError("openai_embedding_index_duplicate")
            ordered[idx] = _parse_vector(entry.get("embedding"))
    else:
        for idx, entry in enumerate(raw_data):
            ordered[idx] = _parse_vector(entry.get("embedding"))

    vectors = [vec for vec in ordered if vec is not None]
    if len(vectors) != len(batch):
        raise ProviderInvalidOutputError("openai_embedding_incomplete")

    tokens = 0
    usage = payload.get("usage")
    if isinstance(usage, Mapping):
        raw_tokens = usage.get("prompt_tokens", usage.get("total_tokens", 0))
        if isinstance(raw_tokens, int) and not isinstance(raw_tokens, bool) and raw_tokens > 0:
            tokens = raw_tokens
    return vectors, tokens


async def _embed_gemini_batch(
    client: httpx2.AsyncClient,
    lease: Lease,
    batch: Sequence[str],
    *,
    task_type: EmbeddingTaskType,
) -> tuple[list[tuple[float, ...]], int]:
    headers: dict[str, str] = {"Content-Type": "application/json", "Accept": "application/json"}
    if api_key := lease.reveal_key_or_none():
        headers["x-goog-api-key"] = api_key

    model_id = lease.model.removeprefix("models/")
    gemini_task = (
        "RETRIEVAL_DOCUMENT" if task_type is EmbeddingTaskType.DOCUMENT else "RETRIEVAL_QUERY"
    )
    requests: list[dict[str, Any]] = []
    for text in batch:
        item: dict[str, Any] = {
            "model": f"models/{model_id}",
            "content": {"parts": [{"text": text}]},
            "taskType": gemini_task,
        }
        if lease.params.dimensions is not None and lease.params.dimensions > 0:
            item["outputDimensionality"] = lease.params.dimensions
        requests.append(item)

    response = await client.post(
        gemini_batch_embeddings_url(lease),
        json={"requests": requests},
        headers=headers,
    )
    if response.status_code < 200 or response.status_code >= 300:
        raise ProviderHttpError(
            response.status_code,
            force_auth=_looks_like_gemini_invalid_key(response),
        )
    payload = _decode_json_object(response)
    raw_embeddings = payload.get("embeddings")
    if not isinstance(raw_embeddings, list) or len(raw_embeddings) != len(batch):
        raise ProviderInvalidOutputError("gemini_embedding_count_mismatch")

    vectors: list[tuple[float, ...]] = []
    for entry in raw_embeddings:
        if not isinstance(entry, Mapping):
            raise ProviderInvalidOutputError("gemini_embedding_entry_invalid")
        vectors.append(_parse_vector(entry.get("values")))

    tokens = 0
    usage = payload.get("usageMetadata")
    if isinstance(usage, Mapping):
        raw_tokens = usage.get("promptTokenCount", usage.get("totalTokenCount", 0))
        if isinstance(raw_tokens, int) and not isinstance(raw_tokens, bool) and raw_tokens > 0:
            tokens = raw_tokens
    return vectors, tokens


def _looks_like_gemini_invalid_key(response: httpx2.Response) -> bool:
    if response.status_code != 400:
        return False
    try:
        snippet = response.text[:1024].lower()
    except Exception:
        return False
    return "api key not valid" in snippet


def _decode_json_object(response: httpx2.Response) -> Mapping[str, Any]:
    try:
        payload = response.json()
    except ValueError:
        raise ProviderInvalidOutputError("invalid_json") from None
    if not isinstance(payload, Mapping):
        raise ProviderInvalidOutputError("response_not_object")
    return payload


def _parse_vector(raw: object) -> tuple[float, ...]:
    if not isinstance(raw, list) or not raw or len(raw) > MAX_EMBEDDING_DIMENSION:
        raise ProviderInvalidOutputError("embedding_vector_invalid")
    result: list[float] = []
    for value in raw:
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise ProviderInvalidOutputError("embedding_element_invalid")
        num = float(value)
        if not math.isfinite(num):
            raise ProviderInvalidOutputError("embedding_element_not_finite")
        result.append(num)
    return tuple(result)


def _parse_rerank_response(response: httpx2.Response, expected_count: int) -> tuple[float, ...]:
    payload = _decode_json_object(response)
    raw_results = payload.get("results")
    if not isinstance(raw_results, list) or len(raw_results) != expected_count:
        raise ProviderInvalidOutputError("rerank_results_mismatch")
    scores: list[float | None] = [None] * expected_count
    for entry in raw_results:
        if not isinstance(entry, Mapping):
            raise ProviderInvalidOutputError("rerank_entry_invalid")
        idx = entry.get("index")
        if isinstance(idx, bool) or not isinstance(idx, int) or idx < 0 or idx >= expected_count:
            raise ProviderInvalidOutputError("rerank_index_invalid")
        if scores[idx] is not None:
            raise ProviderInvalidOutputError("rerank_index_duplicate")
        raw_score = entry.get("relevance_score")
        if isinstance(raw_score, bool) or not isinstance(raw_score, int | float):
            raise ProviderInvalidOutputError("rerank_score_invalid")
        score = float(raw_score)
        if not math.isfinite(score):
            raise ProviderInvalidOutputError("rerank_score_not_finite")
        scores[idx] = score
    return tuple(score for score in scores if score is not None)


__all__ = [
    "DEFAULT_ANTHROPIC_MAX_TOKENS",
    "DEFAULT_EMBEDDING_BATCH_SIZE",
    "DEFAULT_GEMINI_API_VERSION",
    "DEFAULT_MAX_RETRIES",
    "DEFAULT_RERANK_TIMEOUT_MS",
    "DEFAULT_RETRY_BACKOFF_SECONDS",
    "DEFAULT_TIMEOUT_SECONDS",
    "GEMINI_EMBEDDING_MAX_BATCH",
    "MAX_EMBEDDING_DIMENSION",
    "BuiltModel",
    "EmbeddingCallOutput",
    "EmbeddingDimensionMismatchError",
    "EmbeddingTaskType",
    "ModelRuntime",
    "RerankDocument",
    "anthropic_base_url",
    "build_chat_model",
    "call_embeddings",
    "call_rerank",
    "chat_model",
    "effective_embedding_batch_size",
    "embedding_model",
    "gemini_batch_embeddings_url",
    "gemini_endpoint",
    "openai_base_url",
    "openai_embeddings_url",
    "prepare_embedding_inputs",
    "rerank_url",
]
