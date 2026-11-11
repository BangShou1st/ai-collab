"""内部服务令牌校验（rerank / query plan / decide 三个认知端点共用）。

本波沿用现有静态令牌配置 ``settings.service_token``；语义与 W3 rerank 完全一致（行为不变）：

- 常量时间比较（``hmac.compare_digest``），令牌不写日志、不进错误信封；
- 未配置令牌时 **fail closed**（503 ``MODEL_UNAVAILABLE``，不可重试），除非显式打开
  ``AI_WORKER_ALLOW_INSECURE_INTERNAL``（仅测试）；
- 令牌不匹配返回 401（仍是 ``MODEL_UNAVAILABLE``）。

W3.2 之后会引入内部令牌 provider（Ed25519 短令牌），届时只需替换 :func:`_authorization`
的实现：取令牌的入口只此一处，端点、网关客户端与测试都不需要改。
"""

from __future__ import annotations

import hmac

from fastapi import Request

from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode


def _authorization(settings: Settings) -> str | None:
    """返回当前应校验的服务令牌；未配置返回 ``None``。

    这是**取令牌的唯一入口**：W3.2 接入内部令牌 provider 时替换本函数即可。
    """

    token: str = settings.service_token
    return token or None


def bearer_value(header: str | None) -> str | None:
    """从 ``Authorization: Bearer <token>`` 头里取出 token；形状不对返回 ``None``。"""

    if not header:
        return None
    scheme, separator, value = header.partition(" ")
    if not separator or scheme.lower() != "bearer":
        return None
    token = value.strip()
    return token or None


def require_service_token(request: Request) -> None:
    """校验服务令牌；失败抛 ``AppError``（503 未配置 / 401 不匹配）。"""

    settings: Settings = request.app.state.settings
    expected = _authorization(settings)
    if expected is None:
        if settings.allow_insecure_internal:
            return
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务令牌未配置",
            status_code=503,
            retryable=False,
        )
    provided = bearer_value(request.headers.get("authorization"))
    if provided is None or not hmac.compare_digest(
        provided.encode("utf-8"), expected.encode("utf-8")
    ):
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务鉴权失败",
            status_code=401,
            retryable=False,
        )


__all__ = ["bearer_value", "require_service_token"]
