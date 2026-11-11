"""配置解析与进程环境 allowlist 的单元测试。

不启动服务、不读真实密钥：所有输入都通过显式映射或 ``monkeypatch`` 构造，
用例之间互不影响。
"""

from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from ai_collab_worker.config import (
    Settings,
    UnsafeEnvironmentError,
    assert_env_allowlist,
    load_settings,
)

#: README §8 冻结的配置变量；允许集合必须与之一致。
ALLOWED_ENV_NAMES = frozenset(
    {
        "AI_WORKER_HOST",
        "AI_WORKER_PORT",
        "AI_WORKER_LOG_LEVEL",
        "AI_WORKER_ROLE",
        "AI_WORKER_PROMPTS_DIR",
        "AI_WORKER_SERVICE_TOKEN",
        "AI_WORKER_TOKEN_MODE",
        "AI_WORKER_ED25519_PUBLIC_KEYS",
        "AI_WORKER_ALLOW_INSECURE_INTERNAL",
        "AI_WORKER_JAVA_GATEWAY_URL",
        "AI_WORKER_MODEL_ALIAS",
        "AI_WORKER_IDEMPOTENCY_TTL_SECONDS",
        "AI_WORKER_MAX_CANDIDATES",
    }
)


@pytest.fixture(autouse=True)
def _clean_worker_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """清掉宿主可能存在的 ``AI_WORKER_*``，让每个用例的输入完全可控。"""

    for name in ALLOWED_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)


def test_defaults_follow_readme() -> None:
    settings = load_settings(environ={})
    assert settings.host == "0.0.0.0"
    assert settings.port == 9100
    assert settings.log_level == "INFO"
    assert settings.role == "api"
    assert settings.prompts_dir == Path(__file__).resolve().parents[2] / "prompts"
    assert settings.service_token == ""
    assert settings.token_mode == "static"
    assert settings.ed25519_public_keys == ""
    assert settings.allow_insecure_internal is False
    assert settings.java_gateway_url == "http://app:8080"
    assert settings.model_alias == "rerank-default"
    assert settings.idempotency_ttl_seconds == 600
    assert settings.max_candidates == 50


def test_allowed_names_are_derived_from_fields() -> None:
    assert Settings.allowed_env_names() == ALLOWED_ENV_NAMES


def test_load_settings_reads_os_environ(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_WORKER_PORT", "9200")
    monkeypatch.setenv("AI_WORKER_ROLE", "job")
    settings = load_settings()
    assert settings.port == 9200
    assert settings.role == "job"


def test_load_settings_uses_given_mapping() -> None:
    assert load_settings({"AI_WORKER_PORT": "9200"}).port == 9200


def test_settings_reads_env_natively(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_WORKER_PORT", "9500")
    assert Settings().port == 9500


def test_env_names_are_case_insensitive() -> None:
    settings = load_settings({"ai_worker_port": "9300", "Ai_Worker_Role": "job"})
    assert settings.port == 9300
    assert settings.role == "job"


def test_prompts_dir_override() -> None:
    settings = load_settings({"AI_WORKER_PROMPTS_DIR": "/srv/prompts"})
    assert settings.prompts_dir == Path("/srv/prompts")


def test_rerank_config_reads_from_environment() -> None:
    settings = load_settings(
        {
            "AI_WORKER_SERVICE_TOKEN": "unit-test-token",
            "AI_WORKER_TOKEN_MODE": "dual",
            "AI_WORKER_ED25519_PUBLIC_KEYS": "k1=unit-test-public-key",
            "AI_WORKER_ALLOW_INSECURE_INTERNAL": "true",
            "AI_WORKER_JAVA_GATEWAY_URL": "http://java.test:8080",
            "AI_WORKER_MODEL_ALIAS": "rerank-test",
            "AI_WORKER_IDEMPOTENCY_TTL_SECONDS": "42",
            "AI_WORKER_MAX_CANDIDATES": "10",
        }
    )
    assert settings.service_token == "unit-test-token"
    assert settings.token_mode == "dual"
    assert settings.ed25519_public_keys == "k1=unit-test-public-key"
    assert settings.allow_insecure_internal is True
    assert settings.java_gateway_url == "http://java.test:8080"
    assert settings.model_alias == "rerank-test"
    assert settings.idempotency_ttl_seconds == 42
    assert settings.max_candidates == 10


def test_service_token_never_appears_in_repr() -> None:
    settings = Settings(service_token="unit-test-LEAK-TOKEN")

    assert "unit-test-LEAK-TOKEN" not in repr(settings)


def test_ed25519_public_keys_never_appear_in_repr() -> None:
    settings = Settings(ed25519_public_keys="k1=unit-test-LEAK-PUBLIC-KEY")

    assert "unit-test-LEAK-PUBLIC-KEY" not in repr(settings)


def test_invalid_token_mode_fails_at_startup() -> None:
    with pytest.raises(ValidationError):
        load_settings({"AI_WORKER_TOKEN_MODE": "jwt"})


def test_unknown_worker_env_fails() -> None:
    with pytest.raises(UnsafeEnvironmentError, match="AI_WORKER_FOO"):
        load_settings({"AI_WORKER_FOO": "1"})


def test_settings_rejects_extra_constructor_field() -> None:
    with pytest.raises(ValidationError):
        Settings(unexpected=1)


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("JWT_SECRET", "jwt-secret-LEAK-1"),
        ("JWT_SECRET_OLD", "jwt-secret-LEAK-2"),
        ("POSTGRES_DSN", "postgres://user:pw@db/LEAK-3"),
        ("OPENAI_API_KEY", "sk-LEAK-4"),
        ("ANTHROPIC_API_KEY", "sk-ant-LEAK-5"),
    ],
)
def test_forbidden_env_names_fail_without_leaking_values(name: str, value: str) -> None:
    with pytest.raises(UnsafeEnvironmentError) as excinfo:
        load_settings({name: value})
    message = str(excinfo.value)
    assert name in message
    assert value not in message


@pytest.mark.parametrize(
    "name",
    ["jwt_secret", "Jwt_Secret_Old", "postgres_password", "openai_api_key", "Anthropic_Api_Key"],
)
def test_forbidden_env_names_are_case_insensitive(name: str) -> None:
    with pytest.raises(UnsafeEnvironmentError):
        load_settings({name: "LEAK-VALUE"})


def test_invalid_port_value_fails() -> None:
    with pytest.raises(ValidationError):
        load_settings({"AI_WORKER_PORT": "abc"})


def test_invalid_role_value_fails() -> None:
    with pytest.raises(ValidationError):
        load_settings({"AI_WORKER_ROLE": "worker"})


def test_assert_env_allowlist_allows_worker_and_unrelated_vars() -> None:
    assert_env_allowlist(
        {
            "AI_WORKER_HOST": "127.0.0.1",
            "AI_WORKER_PORT": "9100",
            "AI_WORKER_LOG_LEVEL": "DEBUG",
            "AI_WORKER_ROLE": "job",
            "AI_WORKER_PROMPTS_DIR": "/app/prompts",
            "PATH": "/usr/bin",
            "JAVA_HOME": "/opt/jdk",
            "PYTHONPATH": "/app/src",
        }
    )


def test_assert_env_allowlist_only_reads_its_argument(monkeypatch: pytest.MonkeyPatch) -> None:
    """纯函数：进程环境里有密钥也不影响对显式映射的判定。"""

    monkeypatch.setenv("OPENAI_API_KEY", "sk-LEAK-pure")
    assert_env_allowlist({"PATH": "/usr/bin"})
