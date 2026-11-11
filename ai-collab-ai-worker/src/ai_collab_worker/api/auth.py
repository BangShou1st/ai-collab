"""内部端点共享鉴权入口（冻结契约 §2/§7.8）。

把 :class:`~ai_collab_worker.auth.token.TokenVerifier` 的失败原因翻译成统一错误信封：

- 配置缺失/非法（公钥未配置、static 未配置）→ 503 ``MODEL_UNAVAILABLE``（fail closed）；
- 令牌缺失/不匹配/过期/错 aud/错 scope → 401 ``MODEL_UNAVAILABLE``（对调用方不可区分）；
- **job 端点需要 claims**：``static`` 模式没有 claims，直接 503 fail closed（冻结 §3.4 要求
  ``POST /v1/jobs`` 只接受 job-scoped token，静态服务令牌不能冒充 job 身份）。

日志只记 scope 与 ``reason``，**绝不打印令牌**。返回值是入站 Bearer 原值，供端点原样
透传给 Java（设计 §2.4：不重新签发、不落盘、不进日志）。
"""

from __future__ import annotations

import logging
from collections.abc import Mapping
from typing import Final

from fastapi import Request

from ai_collab_worker.auth.token import (
    AuthError,
    AuthUnavailableError,
    TokenMode,
    TokenVerifier,
)
from ai_collab_worker.contract.common import AppError, ErrorCode

logger: Final[logging.Logger] = logging.getLogger(__name__)


def require_token(request: Request, *, scope: str, audience: str = "ai-worker") -> str:
    """校验 ``Authorization`` 并返回原始 Bearer 值（static 模式返回同一静态值）。

    ``token_verifier`` 由 ``main.create_app`` 放到 ``app.state``；缺失属于装配错误（500）。
    """

    verifier = _verifier(request)
    return _authorize(verifier, request, scope=scope, audience=audience)


def require_claims(
    request: Request, *, scope: str, audience: str = "ai-worker"
) -> tuple[str, Mapping[str, object]]:
    """校验 ``Authorization`` 并**同时返回 claims**（job 端点专用）。

    复用 :meth:`TokenVerifier.authorize` 的模式语义（static / ed25519 / dual），再用
    :meth:`TokenVerifier.verify` 取一次 claims。``static`` 模式没有 claims，按冻结 §3.4
    fail closed（503）：job 端点必须由 Java 签发带 ``jobId/attempt/leaseEpoch`` 的短令牌。

    ``dual`` 模式下用静态令牌通过 ``authorize`` 的请求会在 ``verify`` 处失败（401）——
    静态令牌不是 job token，这是预期行为。
    """

    verifier = _verifier(request)
    if verifier.mode is TokenMode.STATIC:
        logger.error("job 端点要求 claims，但令牌模式为 static（fail closed）scope=%s", scope)
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务令牌模式不支持 job claims",
            status_code=503,
            retryable=False,
        )
    token = _authorize(verifier, request, scope=scope, audience=audience)
    try:
        return token, verifier.verify(token, audience=audience, scopes=(scope,))
    except AuthUnavailableError:
        logger.error("内部服务鉴权配置缺失 scope=%s（fail closed）", scope)
        raise _unavailable() from None
    except AuthError as exc:
        logger.warning("内部服务鉴权失败 scope=%s reason=%s", scope, exc.reason.value)
        raise _unauthorized() from None


def _verifier(request: Request) -> TokenVerifier:
    verifier = getattr(request.app.state, "token_verifier", None)
    if not isinstance(verifier, TokenVerifier):
        msg = "token_verifier 未配置"
        raise RuntimeError(msg)
    return verifier


def _authorize(
    verifier: TokenVerifier,
    request: Request,
    *,
    scope: str,
    audience: str,
) -> str:
    try:
        return verifier.authorize(
            request.headers.get("authorization"),
            scope=scope,
            audience=audience,
        )
    except AuthUnavailableError:
        logger.error("内部服务鉴权配置缺失 scope=%s（fail closed）", scope)
        raise _unavailable() from None
    except AuthError as exc:
        logger.warning("内部服务鉴权失败 scope=%s reason=%s", scope, exc.reason.value)
        raise _unauthorized() from None


def _unavailable() -> AppError:
    return AppError(
        ErrorCode.MODEL_UNAVAILABLE,
        "内部服务令牌未配置",
        status_code=503,
        retryable=False,
    )


def _unauthorized() -> AppError:
    return AppError(
        ErrorCode.MODEL_UNAVAILABLE,
        "内部服务鉴权失败",
        status_code=401,
        retryable=False,
    )


__all__ = ["require_claims", "require_token"]
