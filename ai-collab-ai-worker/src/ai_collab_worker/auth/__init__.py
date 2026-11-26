"""内部服务鉴权（Ed25519 短令牌；冻结契约 §2，设计 2026-11-25 §4.1）。

Java 控制面持有私钥并签发 JWS，Python 只持公钥做验签（设计 §1.4：密钥只在 Java）。
只有 Ed25519 一种模式；公钥集合缺失或非法时进程启动失败。
"""

from __future__ import annotations

from ai_collab_worker.auth.token import (
    AuthConfigurationError,
    AuthError,
    AuthReason,
    Ed25519KeyRing,
    TokenVerifier,
)

__all__ = [
    "AuthConfigurationError",
    "AuthError",
    "AuthReason",
    "Ed25519KeyRing",
    "TokenVerifier",
]
