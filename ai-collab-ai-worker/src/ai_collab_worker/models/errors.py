"""厂商异常 → :class:`AppError` 的边界包装（P1 契约 §2.5、§6 错误映射）。

LangChain 1.x 的各厂商适配器把 HTTP 错误抛成 ``langchain_core.exceptions`` 的统一子类
（``ModelAuthenticationError``、``ModelRateLimitError``、``ModelAPIError``、``ModelTimeoutError``…），
同时继承厂商 SDK 的异常类型。本模块按这些统一类型分类，再以 ``status_code`` / ``code`` 属性兜底，
最后兜底 httpx/asyncio 超时。

**消息与 details 只放固定短语**：厂商异常的 ``str()`` 可能含请求 URL（Gemini 的查询串）或回显
的请求头，因此一律不拼进 ``AppError``，并用 ``raise ... from None`` 切断异常链，保证 traceback
里也看不到原异常文本。原异常的**类名**进 ``details.provider_error``，便于排障。

用量状态（契约 §3 的 ``status``）与错误映射在同一处给出，避免两套口径。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Final

import httpx
import httpx2
from langchain_core.exceptions import (
    ModelAPIError,
    ModelAuthenticationError,
    ModelConnectionError,
    ModelError,
    ModelPermissionDeniedError,
    ModelRateLimitError,
    ModelTimeoutError,
)

from ai_collab_worker.contract.common import AppError, ErrorCode

#: 用量状态（契约 §3）。
USAGE_SUCCESS: Final[str] = "SUCCESS"
USAGE_TIMEOUT: Final[str] = "TIMEOUT"
USAGE_QUOTA_EXCEEDED: Final[str] = "QUOTA_EXCEEDED"
USAGE_PROVIDER_ERROR: Final[str] = "PROVIDER_ERROR"
USAGE_INVALID_OUTPUT: Final[str] = "INVALID_OUTPUT"
USAGE_CANCELED: Final[str] = "CANCELED"

REASON_AUTH: Final[str] = "provider_auth"
REASON_RATE_LIMITED: Final[str] = "provider_rate_limited"
REASON_TIMEOUT: Final[str] = "provider_timeout"
REASON_UNAVAILABLE: Final[str] = "provider_unavailable"
REASON_REJECTED: Final[str] = "provider_rejected"
REASON_INVALID_OUTPUT: Final[str] = "provider_invalid_output"


class ProviderHttpError(Exception):
    """直连厂商 HTTP 返回非 2xx；异常消息只含状态码，绝不含 URL、响应体与凭据。"""

    def __init__(self, status_code: int, *, force_auth: bool = False) -> None:
        super().__init__(f"provider_http_{status_code}")
        self.status_code = status_code
        self.force_auth = force_auth


class ProviderInvalidOutputError(Exception):
    """直连厂商返回非法或结构不匹配的响应；消息只放低基数原因码。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.output_reason = reason


@dataclass(frozen=True)
class ErrorClass:
    """一次失败的分类：错误码、HTTP 状态、可重试、用量状态、固定 reason。"""

    code: ErrorCode
    status_code: int
    retryable: bool
    usage_status: str
    reason: str
    message: str


_AUTH = ErrorClass(
    ErrorCode.MODEL_UNAVAILABLE, 503, False, USAGE_PROVIDER_ERROR, REASON_AUTH, "模型厂商鉴权失败"
)
_RATE = ErrorClass(
    ErrorCode.MODEL_RATE_LIMITED,
    429,
    True,
    USAGE_QUOTA_EXCEEDED,
    REASON_RATE_LIMITED,
    "模型厂商限流",
)
_TIMEOUT = ErrorClass(
    ErrorCode.DEADLINE_EXCEEDED, 504, True, USAGE_TIMEOUT, REASON_TIMEOUT, "模型调用超时"
)
_UNAVAILABLE = ErrorClass(
    ErrorCode.MODEL_UNAVAILABLE,
    503,
    True,
    USAGE_PROVIDER_ERROR,
    REASON_UNAVAILABLE,
    "模型厂商暂不可用",
)
_REJECTED = ErrorClass(
    ErrorCode.MODEL_UNAVAILABLE,
    503,
    False,
    USAGE_PROVIDER_ERROR,
    REASON_REJECTED,
    "模型厂商拒绝请求",
)
_INVALID_OUTPUT = ErrorClass(
    ErrorCode.MODEL_UNAVAILABLE,
    503,
    False,
    USAGE_INVALID_OUTPUT,
    REASON_INVALID_OUTPUT,
    "模型厂商返回非法响应",
)


def classify(exc: BaseException) -> ErrorClass:
    """把任意模型调用异常归到固定的分类之一。"""

    if isinstance(exc, ProviderInvalidOutputError):
        return _INVALID_OUTPUT
    if isinstance(exc, ProviderHttpError) and exc.force_auth:
        return _AUTH
    if isinstance(exc, ModelAuthenticationError | ModelPermissionDeniedError):
        return _AUTH
    if isinstance(exc, ModelRateLimitError):
        return _RATE
    if isinstance(
        exc,
        ModelTimeoutError
        | TimeoutError
        | asyncio.TimeoutError
        | httpx.TimeoutException
        | httpx2.TimeoutException,
    ):
        return _TIMEOUT
    if isinstance(exc, ModelAPIError | ModelConnectionError):
        return _UNAVAILABLE
    status = _status_of(exc)
    if status is not None:
        if status in {401, 403}:
            return _AUTH
        if status == 429:
            return _RATE
        if status in {408, 504}:
            return _TIMEOUT
        if status >= 500:
            return _UNAVAILABLE
        if 400 <= status < 500:
            return _REJECTED
    if isinstance(exc, httpx.TransportError | httpx2.TransportError | ConnectionError):
        return _UNAVAILABLE
    if isinstance(exc, ModelError):
        return _UNAVAILABLE if type(exc).is_retryable else _REJECTED
    return _UNAVAILABLE


def to_app_error(exc: BaseException) -> AppError:
    """边界包装：只带固定消息与原异常类名，不含任何厂商返回文本。"""

    if isinstance(exc, AppError):
        return exc
    kind = classify(exc)
    return AppError(
        kind.code,
        kind.message,
        status_code=kind.status_code,
        retryable=kind.retryable,
        details={"reason": kind.reason, "providerError": type(exc).__name__},
    )


def _status_of(exc: BaseException) -> int | None:
    for attr in ("status_code", "code", "status"):
        value = getattr(exc, attr, None)
        if isinstance(value, int) and not isinstance(value, bool) and 100 <= value < 600:
            return value
    response = getattr(exc, "response", None)
    value = getattr(response, "status_code", None)
    if isinstance(value, int) and 100 <= value < 600:
        return value
    return None


__all__ = [
    "REASON_AUTH",
    "REASON_INVALID_OUTPUT",
    "REASON_RATE_LIMITED",
    "REASON_REJECTED",
    "REASON_TIMEOUT",
    "REASON_UNAVAILABLE",
    "USAGE_CANCELED",
    "USAGE_INVALID_OUTPUT",
    "USAGE_PROVIDER_ERROR",
    "USAGE_QUOTA_EXCEEDED",
    "USAGE_SUCCESS",
    "USAGE_TIMEOUT",
    "ErrorClass",
    "ProviderHttpError",
    "ProviderInvalidOutputError",
    "classify",
    "to_app_error",
]
