"""内部服务令牌验签（冻结契约 §2.1/§2.3）。

规则与 Java ``InternalTokenVerifier`` 逐条一致：

1. 三段结构、base64url 解码失败 → ``malformed``；
2. header ``alg`` 必须 ``EdDSA``、``typ`` 必须 ``JWT``（缺失容忍，``alg=none`` 拒绝）→ ``alg``；
3. ``kid`` 必须在已知公钥集合内 → ``unknown_kid``；
4. 签名对 ``header.payload`` 原始 ASCII 字节验签失败 → ``bad_signature``；
5. ``iss`` 必须 ``ai-collab-backend``、``sub`` 必须 ``backend`` → ``subject``；
6. ``aud`` 必须包含本端要求的 audience（worker: ``ai-worker``）→ ``audience``；
7. ``scope`` 必须包含本次操作要求的 scope（rerank: ``rerank``）→ ``scope``；
8. ``exp`` 必须 > now−60s、``iat`` 必须 ≤ now+60s → ``expired``；
9. 所有失败抛 :class:`AuthError`，**不携带令牌**；日志只记 reason。

Python 只持公钥；私钥与签发逻辑只在 Java（设计 §1.4）。
"""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import re
import time
from collections.abc import Callable, Iterable, Mapping
from enum import StrEnum
from types import MappingProxyType
from typing import Final

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_der_public_key

#: JWS 三段的最大长度（防超长输入消耗解析资源）。
MAX_TOKEN_CHARS: Final[int] = 8192

#: 契约固定的签发者/主体。
EXPECTED_ISSUER: Final[str] = "ai-collab-backend"
EXPECTED_SUBJECT: Final[str] = "backend"

#: JWS 固定的算法/类型。
EXPECTED_ALG: Final[str] = "EdDSA"
EXPECTED_TYP: Final[str] = "JWT"

#: 默认允许的时钟偏移（秒），两端必须一致。
DEFAULT_CLOCK_SKEW_SECONDS: Final[int] = 60

_B64URL = re.compile(r"[A-Za-z0-9_-]+")


class TokenMode(StrEnum):
    """令牌模式（与 Java ``InternalTokenMode`` 一一对应）。"""

    STATIC = "static"
    ED25519 = "ed25519"
    DUAL = "dual"


class AuthReason(StrEnum):
    """验签失败原因（与 Java ``InternalTokenVerifier.Reason`` 的 wire name 一致）。"""

    MALFORMED = "malformed"
    ALG = "alg"
    UNKNOWN_KID = "unknown_kid"
    BAD_SIGNATURE = "bad_signature"
    SUBJECT = "subject"
    AUDIENCE = "audience"
    SCOPE = "scope"
    EXPIRED = "expired"


class AuthError(Exception):
    """鉴权失败（401）；消息只含 reason，绝不含令牌。"""

    reason: Final[AuthReason]

    def __init__(self, reason: AuthReason) -> None:
        super().__init__(f"token verification failed: {reason.value}")
        self.reason = reason


class AuthUnavailableError(Exception):
    """鉴权材料未配置/非法（503 fail closed）；消息不含配置值。"""


class AuthConfigurationError(Exception):
    """公钥配置解析失败；消息只含 kid/格式，不含公钥内容。"""


class Ed25519KeyRing:
    """``kid -> Ed25519 公钥`` 的只读集合，支持多 kid 轮换。"""

    def __init__(self, keys: Mapping[str, Ed25519PublicKey]) -> None:
        self._keys: Final[Mapping[str, Ed25519PublicKey]] = MappingProxyType(dict(keys))

    @classmethod
    def parse(cls, raw: str) -> Ed25519KeyRing:
        """解析 ``kid=SPKI DER base64[,kid=...]``；空串返回空环。

        失败信息只描述 kid 与格式，绝不包含公钥值。
        """

        keys: dict[str, Ed25519PublicKey] = {}
        for entry in raw.split(","):
            item = entry.strip()
            if not item:
                continue
            kid, separator, encoded = item.partition("=")
            kid = kid.strip()
            encoded = encoded.strip()
            if not separator or not kid or not encoded:
                msg = "ed25519 公钥配置格式非法：应为 kid=SPKI-DER-base64[,kid=...]"
                raise AuthConfigurationError(msg)
            if kid in keys:
                msg = f"ed25519 公钥 kid={kid} 重复"
                raise AuthConfigurationError(msg)
            try:
                der = base64.b64decode(encoded, validate=True)
            except (binascii.Error, ValueError) as exc:
                msg = f"ed25519 公钥 kid={kid} 不是合法 base64"
                raise AuthConfigurationError(msg) from exc
            try:
                key = load_der_public_key(der)
            except ValueError as exc:
                msg = f"ed25519 公钥 kid={kid} 不是合法 SPKI DER"
                raise AuthConfigurationError(msg) from exc
            if not isinstance(key, Ed25519PublicKey):
                msg = f"ed25519 公钥 kid={kid} 不是 Ed25519 算法"
                raise AuthConfigurationError(msg)
            keys[kid] = key
        return cls(keys)

    def get(self, kid: str) -> Ed25519PublicKey | None:
        return self._keys.get(kid)

    @property
    def kids(self) -> frozenset[str]:
        return frozenset(self._keys)

    def __len__(self) -> int:
        return len(self._keys)


class TokenVerifier:
    """静态令牌常量时间比较 + Ed25519 JWS 验签。

    ``dual`` 语义（runbook §4）：先验 JWT，失败再按静态令牌常量时间比较；两条都失败时抛出
    JWT 的失败原因（更利于定位），但响应体对调用方仍不可区分。
    """

    def __init__(
        self,
        *,
        mode: TokenMode,
        static_token: str,
        key_ring: Ed25519KeyRing | None = None,
        key_ring_error: str | None = None,
        expected_audience: str = "ai-worker",
        clock_skew_seconds: int = DEFAULT_CLOCK_SKEW_SECONDS,
        allow_insecure_internal: bool = False,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._mode = mode
        self._static_token = static_token
        self._key_ring = key_ring
        self._key_ring_error = key_ring_error
        self._expected_audience = expected_audience
        self._clock_skew = max(0, clock_skew_seconds)
        self._allow_insecure_internal = allow_insecure_internal
        self._clock = clock

    # ---------- 端点入口 ----------

    @property
    def mode(self) -> TokenMode:
        """当前令牌模式；``static`` 模式不产生 claims（job 端点据此 fail closed）。"""

        return self._mode

    def authorize(
        self,
        authorization: str | None,
        *,
        scope: str,
        audience: str | None = None,
    ) -> str:
        """校验 ``Authorization`` 头并返回**原始** Bearer 值（透传用）。

        ``static`` 模式下返回值与配置值相同；``ed25519``/``dual`` 返回调用方带来的同一令牌，
        不重新签发（设计 §2.4）。``allow_insecure_internal`` 仅对 static 生效，返回空串表示
        不发送 Authorization 头。
        """

        token = _bearer_value(authorization)
        required_audience = audience or self._expected_audience
        if self._mode is TokenMode.STATIC:
            return self._authorize_static(token)
        if self._mode is TokenMode.ED25519:
            self._require_key_ring()
            if token is None:
                raise AuthError(AuthReason.MALFORMED)
            self.verify(token, audience=required_audience, scopes=(scope,))
            return token

        # dual：先验 JWT，失败再常量时间比较静态令牌。
        jwt_error: AuthError | None = None
        if self._key_ring is not None and len(self._key_ring) > 0:
            if token is None:
                jwt_error = AuthError(AuthReason.MALFORMED)
            else:
                try:
                    self.verify(token, audience=required_audience, scopes=(scope,))
                except AuthError as exc:
                    jwt_error = exc
                else:
                    return token
        if self._static_token:
            if token is not None and _constant_time_equals(self._static_token, token):
                return token
        elif self._key_ring is None or len(self._key_ring) == 0:
            raise AuthUnavailableError("内部服务令牌未配置")
        raise jwt_error or AuthError(AuthReason.MALFORMED)

    def readiness_failure(self) -> str | None:
        """``/readyz`` 检查：``ed25519`` 模式公钥缺失/非法 → 固定短语（503）。

        ``static`` 模式保持既有语义：未配置令牌时端点逐请求 fail closed（503），但就绪探针
        仍为 200（P1 发布口径不变）。
        """

        if self._mode is TokenMode.ED25519 and (self._key_ring is None or len(self._key_ring) == 0):
            return "ed25519 公钥未配置或非法"
        return None

    # ---------- JWS 验签 ----------

    def verify(
        self,
        token: str,
        *,
        audience: str | None = None,
        scopes: Iterable[str] = (),
    ) -> Mapping[str, object]:
        """按冻结 §2.3 逐条验签；失败抛 :class:`AuthError`（不携带令牌）。"""

        if self._key_ring is None or len(self._key_ring) == 0:
            raise AuthUnavailableError(self._key_ring_error or "Ed25519 公钥未配置或非法")
        if not token or len(token) > MAX_TOKEN_CHARS:
            raise AuthError(AuthReason.MALFORMED)
        parts = token.split(".")
        if len(parts) != 3:
            raise AuthError(AuthReason.MALFORMED)

        header = _decode_json_object(parts[0])
        if header is None:
            raise AuthError(AuthReason.MALFORMED)
        if header.get("alg") != EXPECTED_ALG:
            raise AuthError(AuthReason.ALG)
        if "typ" in header and header.get("typ") != EXPECTED_TYP:
            raise AuthError(AuthReason.ALG)
        kid = header.get("kid")
        if not isinstance(kid, str) or not kid:
            raise AuthError(AuthReason.UNKNOWN_KID)
        public_key = self._key_ring.get(kid)
        if public_key is None:
            raise AuthError(AuthReason.UNKNOWN_KID)

        signature = _decode_b64url(parts[2])
        if signature is None or len(signature) != 64:
            raise AuthError(AuthReason.BAD_SIGNATURE)
        signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
        try:
            public_key.verify(signature, signing_input)
        except InvalidSignature as exc:
            raise AuthError(AuthReason.BAD_SIGNATURE) from exc

        payload = _decode_json_object(parts[1])
        if payload is None:
            raise AuthError(AuthReason.MALFORMED)
        if payload.get("iss") != EXPECTED_ISSUER or payload.get("sub") != EXPECTED_SUBJECT:
            raise AuthError(AuthReason.SUBJECT)
        required_audience = audience or self._expected_audience
        if required_audience not in _audience_values(payload.get("aud")):
            raise AuthError(AuthReason.AUDIENCE)

        granted_scope = payload.get("scope")
        if not isinstance(granted_scope, str):
            raise AuthError(AuthReason.MALFORMED)
        granted = set(granted_scope.split())
        for required in scopes:
            if required not in granted:
                raise AuthError(AuthReason.SCOPE)

        expires_at = _integer_claim(payload, "exp")
        issued_at = _integer_claim(payload, "iat")
        if expires_at is None or issued_at is None:
            raise AuthError(AuthReason.MALFORMED)
        now = self._clock()
        if expires_at <= now - self._clock_skew:
            raise AuthError(AuthReason.EXPIRED)
        if issued_at > now + self._clock_skew:
            raise AuthError(AuthReason.EXPIRED)

        return dict(payload)

    # ---------- 内部 ----------

    def _authorize_static(self, token: str | None) -> str:
        if not self._static_token:
            if self._allow_insecure_internal:
                return ""
            raise AuthUnavailableError("内部服务令牌未配置")
        if token is None:
            raise AuthError(AuthReason.MALFORMED)
        if not _constant_time_equals(self._static_token, token):
            raise AuthError(AuthReason.BAD_SIGNATURE)
        return token

    def _require_key_ring(self) -> None:
        if self._key_ring is None or len(self._key_ring) == 0:
            raise AuthUnavailableError(self._key_ring_error or "Ed25519 公钥未配置或非法")


def _bearer_value(header: str | None) -> str | None:
    if not header:
        return None
    scheme, separator, value = header.partition(" ")
    if not separator or scheme.lower() != "bearer":
        return None
    token = value.strip()
    return token or None


def _constant_time_equals(expected: str, provided: str) -> bool:
    return hmac.compare_digest(expected.encode("utf-8"), provided.encode("utf-8"))


def _decode_b64url(segment: str) -> bytes | None:
    if not segment or _B64URL.fullmatch(segment) is None:
        return None
    padding = "=" * (-len(segment) % 4)
    try:
        return base64.urlsafe_b64decode(segment + padding)
    except (binascii.Error, ValueError):
        return None


def _decode_json_object(segment: str) -> dict[str, object] | None:
    raw = _decode_b64url(segment)
    if raw is None:
        return None
    try:
        decoded: object = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(decoded, dict):
        return None
    parsed: dict[str, object] = {}
    for key, value in decoded.items():
        if not isinstance(key, str):
            return None
        parsed[key] = value
    return parsed


def _audience_values(raw: object) -> frozenset[str]:
    if isinstance(raw, str):
        return frozenset({raw})
    if isinstance(raw, list):
        return frozenset(item for item in raw if isinstance(item, str))
    return frozenset()


def _integer_claim(payload: Mapping[str, object], name: str) -> int | None:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


__all__ = [
    "DEFAULT_CLOCK_SKEW_SECONDS",
    "EXPECTED_ALG",
    "EXPECTED_ISSUER",
    "EXPECTED_SUBJECT",
    "EXPECTED_TYP",
    "MAX_TOKEN_CHARS",
    "AuthConfigurationError",
    "AuthError",
    "AuthReason",
    "AuthUnavailableError",
    "Ed25519KeyRing",
    "TokenMode",
    "TokenVerifier",
]
