"""内部服务鉴权（W3.2 Ed25519 短令牌；冻结契约 §2）。

Java 控制面持有私钥并签发 JWS，Python 只持公钥做验签（设计 §1.4：密钥只在 Java）。
默认 ``static`` 模式与 W3 完全一致；``ed25519`` 只接受短令牌；``dual`` 仅用于轮换过渡。
"""

from __future__ import annotations

from ai_collab_worker.auth.token import (
    AuthConfigurationError,
    AuthError,
    AuthReason,
    AuthUnavailableError,
    Ed25519KeyRing,
    TokenMode,
    TokenVerifier,
)

__all__ = [
    "AuthConfigurationError",
    "AuthError",
    "AuthReason",
    "AuthUnavailableError",
    "Ed25519KeyRing",
    "TokenMode",
    "TokenVerifier",
]
