"""Java 内部模型网关客户端（设计 §6.1 ``clients/``，冻结文档 §2）。

调用面只有一个：``POST {base_url}/internal/ai/v1/model-calls``。Python 不持任何 Provider
密钥、不直连模型；出网策略、密钥、预算与账本都在 Java 控制面（设计 §1.4）。

固定口径：

- body：``{schemaVersion, purpose:"KNOWLEDGE_CHAT", systemPrompt, userPrompt,
  outputFormat, deadlineMs, callId}``。``outputFormat`` 固定 ``PROMPT_JSON``：Java 网关的
  Provider 能力策略不接受 ``JSON_OBJECT``（会直接拒绝），rerank 输出由本侧解析，不需要
  Provider 原生 JSON 模式；
- 头：``Authorization: Bearer <入站已验证令牌>``（透传；``bearer_token`` 缺省时回退到配置的
  静态令牌，空串则不发送，由端点先按 fail-closed 拒绝）、``X-Call-Id`` 与入参 ``callId``
  相同、``traceparent`` 仅在调用方带时透传；
- 超时：connect 2s、read ``deadlineMs + 5s``；写与连接池给固定短上限；
- 失败分类：连接/读超时 → ``DEADLINE_EXCEEDED``(504, retryable)；429 →
  ``MODEL_RATE_LIMITED``(429)；5xx / 非 JSON / 契约不符 → ``MODEL_UNAVAILABLE``(503)；
  4xx → ``MODEL_UNAVAILABLE``(503, retryable=False，不重试)。重试只在 Java 侧发生
  （设计 §7.1），本客户端**不重试**；
- 日志只记 callId、状态码与错误类型，绝不打印 token、prompt 全文或候选正文。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Final

import httpx

from ai_collab_worker.contract.common import SCHEMA_VERSION, AppError, ErrorCode
from ai_collab_worker.contract.rerank import RerankUsage, RerankUsageSource

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: Java 内部模型网关路径。
MODEL_CALL_PATH: Final[str] = "/internal/ai/v1/model-calls"

#: 连接超时（设计 §7.1：connect 2s）。
CONNECT_TIMEOUT_SECONDS: Final[float] = 2.0

#: read 超时在调用方 deadline 上的宽限（设计 §7.1：read = deadlineMs + 5s）。
READ_TIMEOUT_GRACE_SECONDS: Final[float] = 5.0

#: 写超时：请求体很小，给固定 10s 足够。
WRITE_TIMEOUT_SECONDS: Final[float] = 10.0

#: 连接池等待超时：与 connect 一致，避免排队把端到端 deadline 耗尽。
POOL_TIMEOUT_SECONDS: Final[float] = 2.0

#: 固定 purpose 取值（Java ``ModelPurpose`` 枚举的合法子集）。
PURPOSE_KNOWLEDGE_CHAT: Final[str] = "KNOWLEDGE_CHAT"
PURPOSE_AGENT: Final[str] = "AGENT"
PURPOSE_PLANNING: Final[str] = "PLANNING"

#: 固定输出格式：应用侧解析 JSON，不依赖 Provider 的 STRUCTURED_OUTPUT 能力。
OUTPUT_FORMAT_PROMPT_JSON: Final[str] = "PROMPT_JSON"


@dataclass(frozen=True)
class ModelCallResult:
    """Java 网关返回的一次补全结果。"""

    content: str
    usage: RerankUsage | None
    model_alias: str | None
    latency_ms: int | None


class JavaGatewayClient:
    """``/internal/ai/v1/model-calls`` 的异步客户端；生命周期跟随 app。"""

    def __init__(
        self,
        *,
        base_url: str,
        service_token: str = "",
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url: Final[str] = base_url.rstrip("/")
        self._service_token: Final[str] = service_token
        self._client: Final[httpx.AsyncClient] = httpx.AsyncClient(transport=transport)

    async def aclose(self) -> None:
        """关闭底层连接池（lifespan 退出时调用）。"""

        await self._client.aclose()

    async def call_model(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        deadline_ms: int,
        call_id: str,
        traceparent: str | None = None,
        purpose: str = PURPOSE_KNOWLEDGE_CHAT,
        bearer_token: str | None = None,
    ) -> ModelCallResult:
        """发起一次模型调用；失败一律转换成 :class:`AppError`。

        ``purpose`` 默认 ``KNOWLEDGE_CHAT``（rerank 的既有行为不变）；decide 传
        ``AGENT``，让 Java 侧按 Agent 模型档位选路与记账。取值必须是 Java
        ``ModelPurpose`` 的合法枚举，Java 网关对未知值返回 400。

        ``bearer_token`` 是入站请求已验证的 Bearer 原值（Ed25519 短令牌或静态令牌）：原样
        透传给 Java，不重新签发、不落盘、不打印。``None`` 时回退到构造时配置的静态令牌，
        以保持既有调用方/单测兼容；空串表示显式不发送 Authorization 头（仅测试放行模式）。
        """

        payload = {
            "schemaVersion": SCHEMA_VERSION,
            "purpose": purpose,
            "systemPrompt": system_prompt,
            "userPrompt": user_prompt,
            "outputFormat": OUTPUT_FORMAT_PROMPT_JSON,
            "deadlineMs": deadline_ms,
            "callId": call_id,
        }
        headers = self._headers(
            call_id=call_id,
            traceparent=traceparent,
            bearer_token=bearer_token,
        )
        timeout = httpx.Timeout(
            connect=CONNECT_TIMEOUT_SECONDS,
            read=deadline_ms / 1000.0 + READ_TIMEOUT_GRACE_SECONDS,
            write=WRITE_TIMEOUT_SECONDS,
            pool=POOL_TIMEOUT_SECONDS,
        )
        started = time.perf_counter()
        try:
            response = await self._client.post(
                f"{self._base_url}{MODEL_CALL_PATH}",
                json=payload,
                headers=headers,
                timeout=timeout,
            )
        except httpx.TimeoutException as exc:
            logger.warning(
                "java gateway timeout call_id=%s type=%s",
                call_id,
                type(exc).__name__,
            )
            raise _deadline_exceeded() from exc
        except httpx.HTTPError as exc:
            logger.warning(
                "java gateway transport error call_id=%s type=%s",
                call_id,
                type(exc).__name__,
            )
            raise _unavailable(retryable=True) from exc

        elapsed_ms = int((time.perf_counter() - started) * 1000)
        self._raise_for_status(response.status_code, call_id=call_id, elapsed_ms=elapsed_ms)
        return self._parse_success(response, call_id=call_id, elapsed_ms=elapsed_ms)

    def _headers(
        self,
        *,
        call_id: str,
        traceparent: str | None,
        bearer_token: str | None,
    ) -> dict[str, str]:
        headers = {"X-Call-Id": call_id}
        token = self._service_token if bearer_token is None else bearer_token
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if traceparent:
            headers["traceparent"] = traceparent
        return headers

    def _raise_for_status(self, status: int, *, call_id: str, elapsed_ms: int) -> None:
        if status == 200:
            return
        logger.warning(
            "java gateway rejected call_id=%s status=%s elapsed_ms=%d",
            call_id,
            status,
            elapsed_ms,
        )
        if status == 429:
            raise AppError(
                ErrorCode.MODEL_RATE_LIMITED,
                "模型网关限流",
                details={"reason": "rate_limited"},
            )
        # 4xx：鉴权/参数/路径问题，重试没有意义；对上游仍统一报告模型不可用。
        raise _unavailable(retryable=not 400 <= status < 500, status=status)

    def _parse_success(
        self,
        response: httpx.Response,
        *,
        call_id: str,
        elapsed_ms: int,
    ) -> ModelCallResult:
        try:
            payload = response.json()
        except ValueError as exc:
            logger.warning(
                "java gateway invalid json call_id=%s elapsed_ms=%d", call_id, elapsed_ms
            )
            raise _unavailable(retryable=True) from exc
        if not isinstance(payload, dict):
            logger.warning(
                "java gateway invalid payload call_id=%s elapsed_ms=%d", call_id, elapsed_ms
            )
            raise _unavailable(retryable=True)

        version = payload.get("schemaVersion")
        content = payload.get("content")
        if not _is_schema_version_one(version) or not isinstance(content, str):
            logger.warning(
                "java gateway contract mismatch call_id=%s elapsed_ms=%d", call_id, elapsed_ms
            )
            raise _unavailable(retryable=True)

        logger.info(
            "java gateway model call ok call_id=%s status=200 elapsed_ms=%d",
            call_id,
            elapsed_ms,
        )
        return ModelCallResult(
            content=content,
            usage=_parse_usage(payload.get("usage")),
            model_alias=_optional_str(payload.get("modelAlias")),
            latency_ms=_optional_int(payload.get("latencyMs")),
        )


def _is_schema_version_one(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value == SCHEMA_VERSION


def _parse_usage(raw: object) -> RerankUsage | None:
    """解析 Java 侧 usage；缺失/形状非法都收敛成 ``None``（调用方标 UNKNOWN）。"""

    if not isinstance(raw, dict):
        return None
    source_raw = raw.get("source")
    source = RerankUsageSource.UNKNOWN
    if isinstance(source_raw, str):
        try:
            source = RerankUsageSource(source_raw)
        except ValueError:
            source = RerankUsageSource.UNKNOWN
    return RerankUsage(
        input_tokens=_optional_non_negative_int(raw.get("inputTokens")),
        output_tokens=_optional_non_negative_int(raw.get("outputTokens")),
        source=source,
    )


def _optional_non_negative_int(value: object) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    return None


def _optional_int(value: object) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return None


def _optional_str(value: object) -> str | None:
    return value if isinstance(value, str) else None


def _deadline_exceeded() -> AppError:
    return AppError(
        ErrorCode.DEADLINE_EXCEEDED,
        "模型网关超时",
        status_code=504,
        retryable=True,
    )


def _unavailable(*, retryable: bool, status: int | None = None) -> AppError:
    details = None if status is None else {"upstreamStatus": status}
    return AppError(
        ErrorCode.MODEL_UNAVAILABLE,
        "模型网关不可用",
        status_code=503,
        retryable=retryable,
        details=details,
    )


__all__ = [
    "CONNECT_TIMEOUT_SECONDS",
    "MODEL_CALL_PATH",
    "OUTPUT_FORMAT_PROMPT_JSON",
    "PURPOSE_AGENT",
    "PURPOSE_KNOWLEDGE_CHAT",
    "PURPOSE_PLANNING",
    "READ_TIMEOUT_GRACE_SECONDS",
    "JavaGatewayClient",
    "ModelCallResult",
]
