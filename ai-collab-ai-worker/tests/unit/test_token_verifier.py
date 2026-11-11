"""Ed25519 短令牌验签矩阵（冻结 §2.3/§2.5 的 #1–#11，Python 侧）。

测试密钥由 ``cryptography`` 在内存中生成/加载，不读取任何真实密钥；断言失败消息与日志
不含令牌。
"""

from __future__ import annotations

import base64
import json
import time
from typing import Any

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from ai_collab_worker.auth.token import (
    AuthConfigurationError,
    AuthError,
    AuthReason,
    AuthUnavailableError,
    Ed25519KeyRing,
    TokenMode,
    TokenVerifier,
)

NOW = 1_800_000_000.0
SKEW_SECONDS = 60
ISS = "ai-collab-backend"
SUB = "backend"

PRIMARY_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("11" * 32))
SECONDARY_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("22" * 32))
RING = Ed25519KeyRing({"k1": PRIMARY_KEY.public_key()})


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _unb64(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def _mint(
    key: Ed25519PrivateKey = PRIMARY_KEY,
    *,
    kid: str = "k1",
    aud: tuple[str, ...] = ("ai-worker", "backend"),
    scope: str = "rerank model-call",
    iat: int | None = None,
    exp: int | None = None,
    iss: str = ISS,
    sub: str = SUB,
    typ: str | None = "JWT",
    extra_header: dict[str, object] | None = None,
) -> str:
    header: dict[str, object] = {"alg": "EdDSA", "kid": kid}
    if typ is not None:
        header["typ"] = typ
    header.update(extra_header or {})
    payload: dict[str, object] = {
        "iss": iss,
        "sub": sub,
        "aud": list(aud),
        "scope": scope,
        "jti": "unit-jti",
        "iat": int(NOW) if iat is None else iat,
        "exp": int(NOW) + 300 if exp is None else exp,
    }
    header_segment = _b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_segment = _b64(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_segment}.{payload_segment}"
    return f"{signing_input}.{_b64(key.sign(signing_input.encode('ascii')))}"


def _verifier(**overrides: Any) -> TokenVerifier:
    kwargs: dict[str, Any] = {
        "mode": TokenMode.ED25519,
        "static_token": "",
        "key_ring": RING,
        "clock": lambda: NOW,
    }
    kwargs.update(overrides)
    return TokenVerifier(**kwargs)


def _reason(token: str, **verify_kwargs: Any) -> AuthReason:
    with pytest.raises(AuthError) as excinfo:
        _verifier().verify(token, **verify_kwargs)
    return excinfo.value.reason


# ---------- #1 合法令牌 ----------


def test_valid_token_passes_and_exposes_claims() -> None:
    token = _mint()

    claims = _verifier().verify(token, audience="ai-worker", scopes=("rerank",))

    assert claims["iss"] == ISS
    assert claims["sub"] == SUB
    assert claims["aud"] == ["ai-worker", "backend"]
    assert claims["scope"] == "rerank model-call"
    assert claims["jti"] == "unit-jti"
    assert claims["iat"] == int(NOW)
    assert claims["exp"] == int(NOW) + 300


def test_authorize_returns_the_original_bearer_token() -> None:
    token = _mint()
    assert _verifier().authorize(f"Bearer {token}", scope="rerank") == token


# ---------- #2 过期 ----------


def test_expired_token_is_rejected() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 120)
    assert _reason(token, scopes=("rerank",)) is AuthReason.EXPIRED


# ---------- #3 签名篡改 ----------


def test_tampered_signature_is_rejected() -> None:
    token = _mint()
    start = token.rindex(".") + 1
    replacement = "B" if token[start] != "B" else "A"
    tampered = token[:start] + replacement + token[start + 1 :]
    assert _reason(tampered, scopes=("rerank",)) is AuthReason.BAD_SIGNATURE


# ---------- #4 audience ----------


def test_wrong_audience_is_rejected() -> None:
    token = _mint(aud=("ai-worker",))
    assert _reason(token, audience="backend", scopes=("rerank",)) is AuthReason.AUDIENCE


# ---------- #5 subject ----------


def test_wrong_subject_is_rejected() -> None:
    token = _mint(sub="worker")
    assert _reason(token, scopes=("rerank",)) is AuthReason.SUBJECT


def test_wrong_issuer_is_rejected_as_subject() -> None:
    token = _mint(iss="other-backend")
    assert _reason(token, scopes=("rerank",)) is AuthReason.SUBJECT


# ---------- #6 未知 kid ----------


def test_unknown_kid_is_rejected() -> None:
    token = _mint(SECONDARY_KEY, kid="k2")
    assert _reason(token, scopes=("rerank",)) is AuthReason.UNKNOWN_KID


# ---------- #7/#8 时钟偏移 ----------


def test_minus_fifty_nine_seconds_still_passes() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 59)
    assert _verifier().verify(token, scopes=("rerank",))["exp"] == int(NOW) - 59


def test_minus_sixty_one_seconds_is_expired() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 61)
    assert _reason(token, scopes=("rerank",)) is AuthReason.EXPIRED


def test_plus_fifty_nine_seconds_future_iat_passes() -> None:
    token = _mint(iat=int(NOW) + 59)
    assert _verifier().verify(token, scopes=("rerank",))["iat"] == int(NOW) + 59


def test_plus_sixty_one_seconds_future_iat_is_rejected() -> None:
    token = _mint(iat=int(NOW) + 61)
    assert _reason(token, scopes=("rerank",)) is AuthReason.EXPIRED


# ---------- #9/#10 轮换 ----------


def test_old_kid_passes_while_public_key_present() -> None:
    ring = Ed25519KeyRing({"k1": PRIMARY_KEY.public_key(), "k2": SECONDARY_KEY.public_key()})
    verifier = _verifier(key_ring=ring)
    assert verifier.verify(_mint(PRIMARY_KEY, kid="k1"), scopes=("rerank",))["jti"] == "unit-jti"


def test_old_kid_rejected_after_key_removed() -> None:
    ring = Ed25519KeyRing({"k2": SECONDARY_KEY.public_key()})
    verifier = _verifier(key_ring=ring)
    with pytest.raises(AuthError) as excinfo:
        verifier.verify(_mint(PRIMARY_KEY, kid="k1"), scopes=("rerank",))
    assert excinfo.value.reason is AuthReason.UNKNOWN_KID


# ---------- #11 alg=none / 篡改 header / payload ----------


def test_alg_none_is_rejected() -> None:
    header = _b64(b'{"alg":"none","typ":"JWT","kid":"k1"}')
    payload = _b64(b'{"iss":"ai-collab-backend","sub":"backend"}')
    assert _reason(f"{header}.{payload}.", scopes=("rerank",)) is AuthReason.ALG


def test_missing_typ_is_tolerated_but_wrong_typ_is_rejected() -> None:
    assert _verifier().verify(_mint(typ=None), scopes=("rerank",))["iss"] == ISS
    assert _reason(_mint(typ="JWT2"), scopes=("rerank",)) is AuthReason.ALG


def test_tampered_header_is_rejected() -> None:
    token = _mint()
    parts = token.split(".")
    header = json.loads(_unb64(parts[0]))
    header["x-extra"] = "1"
    tampered_header = _b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    assert (
        _reason(f"{tampered_header}.{parts[1]}.{parts[2]}", scopes=("rerank",))
        is AuthReason.BAD_SIGNATURE
    )


def test_tampered_payload_is_rejected() -> None:
    token = _mint()
    parts = token.split(".")
    payload = json.loads(_unb64(parts[1]))
    payload["scope"] = "rerank"
    tampered_payload = _b64(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    assert (
        _reason(f"{parts[0]}.{tampered_payload}.{parts[2]}", scopes=("rerank",))
        is AuthReason.BAD_SIGNATURE
    )


# ---------- scope ----------


def test_missing_scope_is_rejected() -> None:
    assert _reason(_mint(scope="rerank"), scopes=("rerank", "model-call")) is AuthReason.SCOPE


def test_all_scopes_are_required() -> None:
    assert _reason(_mint(scope="rerank"), scopes=("model-call",)) is AuthReason.SCOPE


# ---------- 结构畸形与卫生 ----------


@pytest.mark.parametrize(
    "token",
    [None, "", "   ", "abc", "a.b", "!!!.???.###", "a.b.c", "x" * 9000],
)
def test_malformed_tokens_are_rejected(token: str | None) -> None:
    with pytest.raises(AuthError) as excinfo:
        _verifier().verify(token or "", scopes=("rerank",))
    assert excinfo.value.reason is AuthReason.MALFORMED


def test_failure_never_contains_token() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 120)
    with pytest.raises(AuthError) as excinfo:
        _verifier().verify(token, scopes=("rerank",))
    message = str(excinfo.value)
    assert token not in message
    assert "eyJ" not in message
    assert message == "token verification failed: expired"


# ---------- static / dual 模式 ----------


def test_static_mode_accepts_only_exact_token() -> None:
    verifier = TokenVerifier(mode=TokenMode.STATIC, static_token="static-test-token")
    assert verifier.authorize("Bearer static-test-token", scope="rerank") == "static-test-token"
    with pytest.raises(AuthError):
        verifier.authorize("Bearer wrong-token", scope="rerank")
    with pytest.raises(AuthError):
        verifier.authorize(None, scope="rerank")


def test_static_mode_fails_closed_without_configuration() -> None:
    verifier = TokenVerifier(mode=TokenMode.STATIC, static_token="")
    with pytest.raises(AuthUnavailableError):
        verifier.authorize("Bearer anything", scope="rerank")


def test_insecure_flag_only_bypasses_static_mode() -> None:
    verifier = TokenVerifier(
        mode=TokenMode.STATIC,
        static_token="",
        allow_insecure_internal=True,
    )
    assert verifier.authorize(None, scope="rerank") == ""


def test_dual_mode_accepts_jwt_and_static_token() -> None:
    verifier = _verifier(
        mode=TokenMode.DUAL,
        static_token="dual-static-token",
        key_ring=RING,
    )
    jwt = _mint()
    assert verifier.authorize(f"Bearer {jwt}", scope="rerank") == jwt
    assert verifier.authorize("Bearer dual-static-token", scope="rerank") == "dual-static-token"
    with pytest.raises(AuthError) as excinfo:
        verifier.authorize("Bearer neither", scope="rerank")
    assert excinfo.value.reason is AuthReason.MALFORMED


def test_dual_mode_without_public_keys_falls_back_to_static() -> None:
    verifier = TokenVerifier(
        mode=TokenMode.DUAL,
        static_token="dual-static-token",
        key_ring=Ed25519KeyRing({}),
    )
    assert verifier.authorize("Bearer dual-static-token", scope="rerank") == "dual-static-token"


def test_dual_mode_without_any_material_fails_closed() -> None:
    verifier = TokenVerifier(
        mode=TokenMode.DUAL,
        static_token="",
        key_ring=Ed25519KeyRing({}),
    )
    with pytest.raises(AuthUnavailableError):
        verifier.authorize("Bearer anything", scope="rerank")


# ---------- readiness ----------


def test_readyz_fails_only_for_ed25519_without_keys() -> None:
    assert TokenVerifier(mode=TokenMode.ED25519, static_token="").readiness_failure() == (
        "ed25519 公钥未配置或非法"
    )
    assert (
        TokenVerifier(mode=TokenMode.ED25519, static_token="", key_ring=RING).readiness_failure()
        is None
    )
    assert TokenVerifier(mode=TokenMode.STATIC, static_token="").readiness_failure() is None
    assert TokenVerifier(mode=TokenMode.DUAL, static_token="").readiness_failure() is None


# ---------- 公钥环解析 ----------


def _spki_b64(key: Ed25519PrivateKey) -> str:
    from cryptography.hazmat.primitives import serialization

    return base64.b64encode(
        key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")


def test_key_ring_parses_multiple_kids() -> None:
    raw = f"k1={_spki_b64(PRIMARY_KEY)}, k2={_spki_b64(SECONDARY_KEY)}"
    ring = Ed25519KeyRing.parse(raw)
    assert ring.kids == frozenset({"k1", "k2"})
    assert len(ring) == 2


def test_key_ring_rejects_bad_configuration_without_echoing_values() -> None:
    with pytest.raises(AuthConfigurationError) as malformed:
        Ed25519KeyRing.parse("not-a-pair")
    assert "kid=SPKI-DER-base64" in str(malformed.value)

    with pytest.raises(AuthConfigurationError) as bad_kid:
        Ed25519KeyRing.parse(f"k9={_spki_b64(PRIMARY_KEY)},k9={_spki_b64(PRIMARY_KEY)}")
    assert "k9" in str(bad_kid.value)

    with pytest.raises(AuthConfigurationError) as not_ed25519:
        Ed25519KeyRing.parse("k1=AAAA")
    assert "k1" in str(not_ed25519.value)


def test_clock_default_is_used_when_not_injected() -> None:
    token = _mint(iat=int(time.time()), exp=int(time.time()) + 300)
    assert _verifier(clock=time.time).verify(token, scopes=("rerank",))["jti"] == "unit-jti"


def test_clock_callable_is_invoked_once_per_verify() -> None:
    calls: list[float] = []

    def clock() -> float:
        calls.append(NOW)
        return NOW

    _verifier(clock=clock).verify(_mint(), scopes=("rerank",))
    assert calls == [NOW]


def test_audience_as_string_is_accepted_for_compatibility() -> None:
    header = _b64(b'{"alg":"EdDSA","typ":"JWT","kid":"k1"}')
    payload = _b64(
        json.dumps(
            {
                "iss": ISS,
                "sub": SUB,
                "aud": "ai-worker",
                "scope": "rerank",
                "jti": "j",
                "iat": int(NOW),
                "exp": int(NOW) + 300,
            },
            separators=(",", ":"),
        ).encode("utf-8")
    )
    signing_input = f"{header}.{payload}"
    token = f"{signing_input}.{_b64(PRIMARY_KEY.sign(signing_input.encode('ascii')))}"
    assert _verifier().verify(token, scopes=("rerank",))["aud"] == "ai-worker"
