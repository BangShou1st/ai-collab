"""Ed25519 短令牌验签矩阵（冻结 §2.3/§2.5 的 #1–#11，Python 侧）。

测试密钥由 ``cryptography`` 在内存中生成/加载，不读取任何真实密钥；断言失败消息与日志
不含令牌。
"""

from __future__ import annotations

import base64
import json
import logging
import time
from typing import Any

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from ai_collab_worker.auth.token import (
    AuthConfigurationError,
    AuthError,
    AuthReason,
    Ed25519KeyRing,
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
    scope: str = "plan model-call",
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

    claims = _verifier().verify(token, audience="ai-worker", scopes=("plan",))

    assert claims["iss"] == ISS
    assert claims["sub"] == SUB
    assert claims["aud"] == ["ai-worker", "backend"]
    assert claims["scope"] == "plan model-call"
    assert claims["jti"] == "unit-jti"
    assert claims["iat"] == int(NOW)
    assert claims["exp"] == int(NOW) + 300


def test_authorize_returns_the_original_bearer_token() -> None:
    token = _mint()
    returned, claims = _verifier().authorize(f"Bearer {token}", scope="plan")
    assert returned == token
    assert claims["scope"] == "plan model-call"


# ---------- #2 过期 ----------


def test_expired_token_is_rejected() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 120)
    assert _reason(token, scopes=("plan",)) is AuthReason.EXPIRED


# ---------- #3 签名篡改 ----------


def test_tampered_signature_is_rejected() -> None:
    token = _mint()
    start = token.rindex(".") + 1
    replacement = "B" if token[start] != "B" else "A"
    tampered = token[:start] + replacement + token[start + 1 :]
    assert _reason(tampered, scopes=("plan",)) is AuthReason.BAD_SIGNATURE


# ---------- #4 audience ----------


def test_wrong_audience_is_rejected() -> None:
    token = _mint(aud=("ai-worker",))
    assert _reason(token, audience="backend", scopes=("plan",)) is AuthReason.AUDIENCE


# ---------- #5 subject ----------


def test_wrong_subject_is_rejected() -> None:
    token = _mint(sub="worker")
    assert _reason(token, scopes=("plan",)) is AuthReason.SUBJECT


def test_wrong_issuer_is_rejected_as_subject() -> None:
    token = _mint(iss="other-backend")
    assert _reason(token, scopes=("plan",)) is AuthReason.SUBJECT


# ---------- #6 未知 kid ----------


def test_unknown_kid_is_rejected() -> None:
    token = _mint(SECONDARY_KEY, kid="k2")
    assert _reason(token, scopes=("plan",)) is AuthReason.UNKNOWN_KID


# ---------- #7/#8 时钟偏移 ----------


def test_minus_fifty_nine_seconds_still_passes() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 59)
    assert _verifier().verify(token, scopes=("plan",))["exp"] == int(NOW) - 59


def test_minus_sixty_one_seconds_is_expired() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 61)
    assert _reason(token, scopes=("plan",)) is AuthReason.EXPIRED


def test_plus_fifty_nine_seconds_future_iat_passes() -> None:
    token = _mint(iat=int(NOW) + 59)
    assert _verifier().verify(token, scopes=("plan",))["iat"] == int(NOW) + 59


def test_plus_sixty_one_seconds_future_iat_is_rejected() -> None:
    token = _mint(iat=int(NOW) + 61)
    assert _reason(token, scopes=("plan",)) is AuthReason.EXPIRED


# ---------- #9/#10 轮换 ----------


def test_old_kid_passes_while_public_key_present() -> None:
    ring = Ed25519KeyRing({"k1": PRIMARY_KEY.public_key(), "k2": SECONDARY_KEY.public_key()})
    verifier = _verifier(key_ring=ring)
    assert verifier.verify(_mint(PRIMARY_KEY, kid="k1"), scopes=("plan",))["jti"] == "unit-jti"


def test_old_kid_rejected_after_key_removed() -> None:
    ring = Ed25519KeyRing({"k2": SECONDARY_KEY.public_key()})
    verifier = _verifier(key_ring=ring)
    with pytest.raises(AuthError) as excinfo:
        verifier.verify(_mint(PRIMARY_KEY, kid="k1"), scopes=("plan",))
    assert excinfo.value.reason is AuthReason.UNKNOWN_KID


# ---------- #11 alg=none / 篡改 header / payload ----------


def test_alg_none_is_rejected() -> None:
    header = _b64(b'{"alg":"none","typ":"JWT","kid":"k1"}')
    payload = _b64(b'{"iss":"ai-collab-backend","sub":"backend"}')
    assert _reason(f"{header}.{payload}.", scopes=("plan",)) is AuthReason.ALG


def test_missing_typ_is_tolerated_but_wrong_typ_is_rejected() -> None:
    assert _verifier().verify(_mint(typ=None), scopes=("plan",))["iss"] == ISS
    assert _reason(_mint(typ="JWT2"), scopes=("plan",)) is AuthReason.ALG


def test_tampered_header_is_rejected() -> None:
    token = _mint()
    parts = token.split(".")
    header = json.loads(_unb64(parts[0]))
    header["x-extra"] = "1"
    tampered_header = _b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    assert (
        _reason(f"{tampered_header}.{parts[1]}.{parts[2]}", scopes=("plan",))
        is AuthReason.BAD_SIGNATURE
    )


def test_tampered_payload_is_rejected() -> None:
    token = _mint()
    parts = token.split(".")
    payload = json.loads(_unb64(parts[1]))
    payload["scope"] = "plan"
    tampered_payload = _b64(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    assert (
        _reason(f"{parts[0]}.{tampered_payload}.{parts[2]}", scopes=("plan",))
        is AuthReason.BAD_SIGNATURE
    )


# ---------- scope ----------


def test_missing_scope_is_rejected() -> None:
    assert _reason(_mint(scope="plan"), scopes=("plan", "model-call")) is AuthReason.SCOPE


def test_all_scopes_are_required() -> None:
    assert _reason(_mint(scope="plan"), scopes=("model-call",)) is AuthReason.SCOPE


# ---------- 结构畸形与卫生 ----------


@pytest.mark.parametrize(
    "token",
    [None, "", "   ", "abc", "a.b", "!!!.???.###", "a.b.c", "x" * 9000],
)
def test_malformed_tokens_are_rejected(token: str | None) -> None:
    with pytest.raises(AuthError) as excinfo:
        _verifier().verify(token or "", scopes=("plan",))
    assert excinfo.value.reason is AuthReason.MALFORMED


def test_failure_never_contains_token() -> None:
    token = _mint(iat=int(NOW) - 400, exp=int(NOW) - 120)
    with pytest.raises(AuthError) as excinfo:
        _verifier().verify(token, scopes=("plan",))
    message = str(excinfo.value)
    assert token not in message
    assert "eyJ" not in message
    assert message == "token verification failed: expired"


# ---------- 审查清单 #3：失败路径不泄漏私钥 / 令牌原文 ----------

#: 测试私钥的三种常见编码；任何一种出现在日志或异常里都算泄漏。
_PRIVATE_SECRETS = (
    "11" * 32,
    base64.b64encode(bytes.fromhex("11" * 32)).decode("ascii"),
    _b64(bytes.fromhex("11" * 32)),
)


def _failure_tokens() -> dict[str, str]:
    header, _payload, signature = _mint().split(".")
    forged_payload = _b64(json.dumps({"scope": "plan"}).encode("utf-8"))
    return {
        "bad_signature": _mint(SECONDARY_KEY),
        "expired": _mint(iat=int(NOW) - 400, exp=int(NOW) - 120),
        "missing_scope": _mint(scope="model-call"),
        "tampered_payload": f"{header}.{forged_payload}.{signature}",
        "unknown_kid": _mint(kid="k-missing"),
        "wrong_alg": _mint(extra_header={"alg": "HS256"}),
        "wrong_audience": _mint(aud=("backend",)),
        "wrong_issuer": _mint(iss="attacker"),
    }


def _rendered(exc: BaseException) -> list[str]:
    """异常链上每一环的 ``str``/``repr``/``args``，防止原文藏在 ``__cause__`` 里。"""

    texts: list[str] = []
    current: BaseException | None = exc
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        texts += [str(current), repr(current), repr(current.args)]
        current = current.__cause__ or current.__context__
    return texts


def _assert_no_leak(texts: list[str], *, token: str = "") -> None:
    segments = [segment for segment in token.split(".") if len(segment) >= 8]
    for text in texts:
        if token:
            assert token not in text
        for segment in segments:
            assert segment not in text
        for secret in _PRIVATE_SECRETS:
            assert secret not in text


@pytest.mark.parametrize("case", sorted(_failure_tokens()))
def test_verification_failure_never_leaks_token_or_private_key(
    case: str, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.DEBUG)
    token = _failure_tokens()[case]

    with pytest.raises(AuthError) as excinfo:
        _verifier().authorize(f"Bearer {token}", scope="plan")

    _assert_no_leak([*_rendered(excinfo.value), caplog.text], token=token)


def test_key_ring_parse_failure_never_leaks_key_material(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    spki = base64.b64encode(
        PRIMARY_KEY.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    ).decode("ascii")
    leaky = "LEAKY-KEY-MATERIAL-not-base64"
    # 误把私钥当公钥粘进配置，是最可能泄漏私钥的真实场景。
    private_pasted = base64.b64encode(bytes.fromhex("11" * 32)).decode("ascii")
    for raw, material in (
        (f"k1={leaky}", leaky),
        (f"k1={spki[:-12]}", spki[:-12]),
        (f"k1={private_pasted}", private_pasted),
    ):
        with pytest.raises(AuthConfigurationError) as excinfo:
            Ed25519KeyRing.parse(raw)
        texts = [*_rendered(excinfo.value), caplog.text]
        _assert_no_leak(texts)
        for text in texts:
            assert material not in text


# ---------- 只保留 Ed25519：静态令牌 / 缺公钥 ----------


def test_static_style_bearer_is_rejected_as_malformed() -> None:
    """旧的静态服务令牌不再被接受：不是三段 JWS 一律 ``malformed``。"""

    verifier = _verifier()
    for value in ("Bearer static-test-token", "Bearer dual-static-token", "static-test-token"):
        with pytest.raises(AuthError) as excinfo:
            verifier.authorize(value, scope="plan")
        assert excinfo.value.reason is AuthReason.MALFORMED
    with pytest.raises(AuthError):
        verifier.authorize(None, scope="plan")


def test_verifier_has_no_static_or_mode_parameters() -> None:
    with pytest.raises(TypeError):
        TokenVerifier(key_ring=RING, static_token="static-test-token")  # type: ignore[call-arg]
    with pytest.raises(TypeError):
        TokenVerifier(key_ring=RING, mode="static")  # type: ignore[call-arg]


def test_empty_key_ring_is_a_configuration_error() -> None:
    with pytest.raises(AuthConfigurationError) as excinfo:
        Ed25519KeyRing({})
    assert "AI_WORKER_ED25519_PUBLIC_KEYS" in str(excinfo.value)
    for raw in ("", "   ", " , ,"):
        with pytest.raises(AuthConfigurationError):
            Ed25519KeyRing.parse(raw)


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
    assert _verifier(clock=time.time).verify(token, scopes=("plan",))["jti"] == "unit-jti"


def test_clock_callable_is_invoked_once_per_verify() -> None:
    calls: list[float] = []

    def clock() -> float:
        calls.append(NOW)
        return NOW

    _verifier(clock=clock).verify(_mint(), scopes=("plan",))
    assert calls == [NOW]


def test_audience_as_string_is_accepted_for_compatibility() -> None:
    header = _b64(b'{"alg":"EdDSA","typ":"JWT","kid":"k1"}')
    payload = _b64(
        json.dumps(
            {
                "iss": ISS,
                "sub": SUB,
                "aud": "ai-worker",
                "scope": "plan",
                "jti": "j",
                "iat": int(NOW),
                "exp": int(NOW) + 300,
            },
            separators=(",", ":"),
        ).encode("utf-8")
    )
    signing_input = f"{header}.{payload}"
    token = f"{signing_input}.{_b64(PRIMARY_KEY.sign(signing_input.encode('ascii')))}"
    assert _verifier().verify(token, scopes=("plan",))["aud"] == "ai-worker"
