"""内部端点共享鉴权入口（冻结契约 §2/§7.8）。

把 :class:`~ai_collab_worker.auth.token.TokenVerifier` 的失败原因翻译成统一错误信封：

- 配置缺失/非法（公钥未配置、static 未配置）→ 503 ``MODEL_UNAVAILABLE``（fail closed）；
- 令牌缺失/不匹配/过期/错 aud/错 scope → 401 ``MODEL_UNAVAILABLE``（对调用方不可区分）。

日志只记 scope 与 ``reason``，**绝不打印令牌**。返回值是入站 Bearer 原值，供端点原样
透传给 Java（设计 §2.4：不重新签发、不落盘、不进日志）。
"""

from __future__ import annotations

import logging
from typing import Final

from fastapi import Request

from ai_collab_worker.auth.token import AuthError, AuthUnavailableError, TokenVerifier
from ai_collab_worker.contract.common import AppError, ErrorCode

logger: Final[logging.Logger] = logging.getLogger(__name__)


def require_token(request: Request, *, scope: str, audience: str = "ai-worker") -> str:
    """校验 ``Authorization`` 并返回原始 Bearer 值（static 模式返回同一静态值）。

    ``token_verifier`` 由 ``main.create_app`` 放到 ``app.state``；缺失属于装配错误（500）。
    """

    verifier = getattr(request.app.state, "token_verifier", None)
    if not isinstance(verifier, TokenVerifier):
        msg = "token_verifier 未配置"
        raise RuntimeError(msg)
    try:
        return verifier.authorize(
            request.headers.get("authorization"),
            scope=scope,
            audience=audience,
        )
    except AuthUnavailableError:
        logger.error("内部服务鉴权配置缺失 scope=%s（fail closed）", scope)
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务令牌未配置",
            status_code=503,
            retryable=False,
        ) from None
    except AuthError as exc:
        logger.warning("内部服务鉴权失败 scope=%s reason=%s", scope, exc.reason.value)
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务鉴权失败",
            status_code=401,
            retryable=False,
        ) from None


__all__ = ["require_token"]
