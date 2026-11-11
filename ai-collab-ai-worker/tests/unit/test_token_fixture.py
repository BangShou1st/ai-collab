"""跨语言 Ed25519 fixture（冻结 §2.5 #14）：Java 固定密钥/claims 签发的令牌，Python 必须验签通过。

fixture 同时存在于 Java 测试资源（``internal-ai/ed25519-test-fixture.json``）与 Python 测试
语料（``tests/fixtures/auth/ed25519-test-fixture.json``）；Java 侧断言"用同一私钥+claims 生成
的字符串 == fixture.token"，本模块断言同一字符串可被 Python 公钥验签，两处必须逐字节一致。
"""

from __future__ import annotations

import base64
import json
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from ai_collab_worker.auth.token import Ed25519KeyRing, TokenMode, TokenVerifier

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "auth" / "ed25519-test-fixture.json"
BACKEND_FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "ai-collab-backend"
    / "src"
    / "test"
    / "resources"
    / "internal-ai"
    / "ed25519-test-fixture.json"
)


def _load() -> dict[str, Any]:
    with FIXTURE.open("r", encoding="utf-8") as handle:
        loaded: object = json.load(handle)
    assert isinstance(loaded, dict)
    return loaded


def _unb64(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def test_fixture_is_explicitly_test_only() -> None:
    fixture = _load()
    assert "TEST ONLY" in fixture["note"]
    assert "never use" in fixture["note"]


def test_fixture_private_key_derives_the_fixture_public_key() -> None:
    fixture = _load()
    private_key = serialization.load_der_private_key(
        base64.b64decode(fixture["privateKeyPkcs8"]), password=None
    )
    assert isinstance(private_key, Ed25519PrivateKey)
    derived = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    assert base64.b64encode(derived).decode("ascii") == fixture["publicKeySpki"]


def test_fixture_token_verifies_with_fixture_public_key() -> None:
    fixture = _load()
    claims: dict[str, Any] = fixture["claims"]
    verifier = TokenVerifier(
        mode=TokenMode.ED25519,
        static_token="",
        key_ring=Ed25519KeyRing.parse(f"k1={fixture['publicKeySpki']}"),
        clock=lambda: float(claims["iat"]) + 10.0,
    )

    verified = verifier.verify(
        fixture["token"],
        audience="ai-worker",
        scopes=("rerank", "model-call"),
    )

    assert dict(verified) == claims
    assert verified["iss"] == "ai-collab-backend"
    assert verified["sub"] == "backend"
    assert verified["aud"] == ["ai-worker", "backend"]
    assert verified["scope"] == "rerank model-call"
    assert verified["exp"] - verified["iat"] == 300
    assert verified["jti"] == "3f1c9b2e-7d4a-4c1e-9f6a-2b8d5e0a1c73"


def test_fixture_token_is_still_valid_at_the_skew_boundary() -> None:
    fixture = _load()
    claims: dict[str, Any] = fixture["claims"]
    verifier = TokenVerifier(
        mode=TokenMode.ED25519,
        static_token="",
        key_ring=Ed25519KeyRing.parse(f"k1={fixture['publicKeySpki']}"),
        clock=lambda: float(claims["exp"]) + 59.0,
    )
    assert (
        verifier.verify(fixture["token"], audience="ai-worker", scopes=("rerank",))["jti"]
        == (claims["jti"])
    )


def test_fixture_token_header_is_contract_shaped() -> None:
    fixture = _load()
    token: str = fixture["token"]
    parts = token.split(".")
    assert len(parts) == 3
    assert token.startswith("eyJ")

    header = json.loads(_unb64(parts[0]))
    assert header == {"alg": "EdDSA", "typ": "JWT", "kid": "k1"}
    payload = json.loads(_unb64(parts[1]))
    assert list(payload) == ["iss", "sub", "aud", "scope", "jti", "iat", "exp"]


def test_java_and_python_fixture_copies_are_identical() -> None:
    if not BACKEND_FIXTURE.exists():
        return
    assert BACKEND_FIXTURE.read_bytes() == FIXTURE.read_bytes()
