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

#: README §8 冻结的配置变量 + P1 契约 §8 的四个变量。
ALLOWED_ENV_NAMES = frozenset(
    {
        "REDIS_URL",
        "AI_WORKER_ID",
        "AI_TASK_CONCURRENCY",
        "JAVA_INTERNAL_BASE_URL",
        "AI_WORKER_MODEL_TIMEOUT_SECONDS",
        "AI_WORKER_MODEL_MAX_RETRIES",
        "AI_WORKER_HOST",
        "AI_WORKER_PORT",
        "AI_WORKER_LOG_LEVEL",
        "AI_WORKER_ROLE",
        "AI_WORKER_PROMPTS_DIR",
        "AI_WORKER_ED25519_PUBLIC_KEYS",
        "AI_WORKER_QUERY_PLAN_MODEL_ALIAS",
        "AI_WORKER_QUERY_PLAN_DEADLINE_MS",
        "AI_WORKER_MEMORY_JOB_DEADLINE_MS",
        "AI_WORKER_MEMORY_MAX_CANDIDATES",
    }
)

#: 设计 2026-11-25 §4.1/§11 删除的变量：再出现即按未知 ``AI_WORKER_*`` 拒绝启动。
REMOVED_ENV_NAMES = (
    "AI_WORKER_TOKEN_MODE",
    "AI_WORKER_SERVICE_TOKEN",
    "AI_WORKER_ALLOW_INSECURE_INTERNAL",
    "AI_WORKER_MODEL_ALIAS",
    "AI_WORKER_IDEMPOTENCY_TTL_SECONDS",
    "AI_WORKER_MAX_CANDIDATES",
    "AI_WORKER_DECIDE_MODEL_ALIAS",
    "AI_WORKER_DECIDE_DEADLINE_MS",
    "AI_WORKER_PLANNING_MODEL_ALIAS",
    "AI_WORKER_PLANNING_DEADLINE_MS",
    # P1（2026-11-26）：旧 Java 网关地址别名删除，不保留兼容回落。
    "AI_WORKER_JAVA_GATEWAY_URL",
)


@pytest.fixture(autouse=True)
def _clean_worker_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """清掉宿主可能存在的 ``AI_WORKER_*``，让每个用例的输入完全可控。"""

    for name in (*ALLOWED_ENV_NAMES, *REMOVED_ENV_NAMES):
        monkeypatch.delenv(name, raising=False)


def test_defaults_follow_readme() -> None:
    settings = load_settings(environ={})
    assert settings.host == "0.0.0.0"
    assert settings.port == 9100
    assert settings.log_level == "INFO"
    assert settings.role == "api"
    assert settings.prompts_dir == Path(__file__).resolve().parents[2] / "prompts"
    assert settings.ed25519_public_keys == ""
    assert settings.java_internal_base_url == "http://app:8080"
    assert settings.redis_url is None
    assert settings.worker_id is None
    assert settings.task_concurrency == 4
    assert settings.model_timeout_seconds == 60.0
    assert settings.model_max_retries == 2
    assert settings.query_plan_model_alias == "query-plan"
    assert settings.query_plan_deadline_ms == 8000
    assert settings.memory_job_deadline_ms == 60000
    assert settings.memory_max_candidates == 50


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


def test_endpoint_config_reads_from_environment() -> None:
    settings = load_settings(
        {
            "AI_WORKER_ED25519_PUBLIC_KEYS": "k1=unit-test-public-key",
            "JAVA_INTERNAL_BASE_URL": "http://java.test:8080",
            "AI_WORKER_QUERY_PLAN_MODEL_ALIAS": "query-plan-test",
            "AI_WORKER_QUERY_PLAN_DEADLINE_MS": "1500",
            "AI_WORKER_MEMORY_JOB_DEADLINE_MS": "45000",
            "AI_WORKER_MEMORY_MAX_CANDIDATES": "30",
        }
    )
    assert settings.ed25519_public_keys == "k1=unit-test-public-key"
    assert settings.java_internal_base_url == "http://java.test:8080"
    assert settings.query_plan_model_alias == "query-plan-test"
    assert settings.query_plan_deadline_ms == 1500
    assert settings.memory_job_deadline_ms == 45000
    assert settings.memory_max_candidates == 30


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("AI_WORKER_QUERY_PLAN_DEADLINE_MS", "0"),
        ("AI_WORKER_QUERY_PLAN_DEADLINE_MS", "30001"),
        ("AI_WORKER_QUERY_PLAN_MODEL_ALIAS", ""),
        ("AI_WORKER_MEMORY_JOB_DEADLINE_MS", "0"),
        ("AI_WORKER_MEMORY_JOB_DEADLINE_MS", "120001"),
        ("AI_WORKER_MEMORY_MAX_CANDIDATES", "0"),
        ("AI_WORKER_MEMORY_MAX_CANDIDATES", "51"),
        ("AI_TASK_CONCURRENCY", "0"),
        ("AI_TASK_CONCURRENCY", "65"),
        ("AI_WORKER_ID", ""),
        ("AI_WORKER_MODEL_TIMEOUT_SECONDS", "0"),
        ("AI_WORKER_MODEL_MAX_RETRIES", "6"),
        ("REDIS_URL", "http://redis:6379"),
    ],
)
def test_endpoint_config_bounds(name: str, value: str) -> None:
    with pytest.raises(ValidationError):
        load_settings({name: value})


def test_ed25519_public_keys_never_appear_in_repr() -> None:
    settings = Settings(ed25519_public_keys="k1=unit-test-LEAK-PUBLIC-KEY")

    assert "unit-test-LEAK-PUBLIC-KEY" not in repr(settings)


@pytest.mark.parametrize("name", REMOVED_ENV_NAMES)
def test_removed_env_names_fail_at_startup_without_leaking_values(name: str) -> None:
    value = "removed-LEAK-VALUE"
    with pytest.raises(UnsafeEnvironmentError) as excinfo:
        load_settings({name: value})
    message = str(excinfo.value)
    assert name in message
    assert value not in message


@pytest.mark.parametrize("field", ["token_mode", "service_token", "allow_insecure_internal"])
def test_removed_fields_are_rejected_by_constructor(field: str) -> None:
    with pytest.raises(ValidationError):
        Settings(**{field: "static"})


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
        ("POSTGRES_DSN", "postgresql://db/LEAK-3"),
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
            "AI_WORKER_ED25519_PUBLIC_KEYS": "k1=x",
            "PATH": "/usr/bin",
            "JAVA_HOME": "/opt/jdk",
            "PYTHONPATH": "/app/src",
        }
    )


def test_assert_env_allowlist_only_reads_its_argument(monkeypatch: pytest.MonkeyPatch) -> None:
    """纯函数：进程环境里有密钥也不影响对显式映射的判定。"""

    monkeypatch.setenv("OPENAI_API_KEY", "sk-LEAK-pure")
    assert_env_allowlist({"PATH": "/usr/bin"})


# --------------------------------------------------------------------------- #
# P1 契约 §8：REDIS_URL / AI_WORKER_ID / AI_TASK_CONCURRENCY / JAVA_INTERNAL_BASE_URL
# --------------------------------------------------------------------------- #


def test_p1_variables_are_read_by_their_contract_names() -> None:
    settings = load_settings(
        {
            "REDIS_URL": "redis://:unit-REDIS-pw@redis.test:6379/0",
            "AI_WORKER_ID": "worker-a",
            "AI_TASK_CONCURRENCY": "8",
            "JAVA_INTERNAL_BASE_URL": "http://nginx-internal:8080",
        }
    )
    assert settings.redis_url is not None
    assert settings.redis_password() == "unit-REDIS-pw"
    assert settings.worker_id == "worker-a"
    assert settings.task_concurrency == 8
    assert settings.java_internal_base_url == "http://nginx-internal:8080"


def test_legacy_java_gateway_alias_is_rejected() -> None:
    """旧名不再作为别名回落：出现即按未知 ``AI_WORKER_*`` 拒绝启动。"""

    with pytest.raises(UnsafeEnvironmentError) as excinfo:
        load_settings({"AI_WORKER_JAVA_GATEWAY_URL": "http://legacy.test:8080"})
    assert "AI_WORKER_JAVA_GATEWAY_URL" in str(excinfo.value)
    assert "legacy.test" not in str(excinfo.value)


def test_legacy_java_gateway_alias_rejected_even_with_contract_name() -> None:
    with pytest.raises(UnsafeEnvironmentError):
        load_settings(
            {
                "JAVA_INTERNAL_BASE_URL": "http://new.test:8080",
                "AI_WORKER_JAVA_GATEWAY_URL": "http://legacy.test:8080",
            }
        )
    assert Settings.allowed_env_names().isdisjoint({"AI_WORKER_JAVA_GATEWAY_URL"})


def test_redis_url_password_never_appears_in_repr_or_errors() -> None:
    settings = load_settings({"REDIS_URL": "rediss://user:unit-LEAK-redis@redis.test:6380"})
    assert "unit-LEAK-redis" not in repr(settings)
    assert "unit-LEAK-redis" not in str(settings.model_dump())
    with pytest.raises(ValidationError) as excinfo:
        load_settings({"REDIS_URL": "http://user:unit-LEAK-redis@redis.test"})
    assert "unit-LEAK-redis" not in str(excinfo.value)


def test_redis_url_without_password() -> None:
    settings = load_settings({"REDIS_URL": "redis://redis.test:6379"})
    assert settings.redis_password() is None


def test_empty_redis_url_disables_stream_consumer() -> None:
    assert load_settings({"REDIS_URL": ""}).redis_url is None


@pytest.mark.parametrize(
    "name",
    ["OPENAI_API_KEY", "GOOGLE_API_KEY", "GEMINI_API_KEY", "SPEC_AGENT_TAVILY_API_KEY"],
)
def test_any_api_key_variable_is_still_refused(name: str) -> None:
    """契约 §2.5：Provider 密钥只经凭据租借，继续拒绝任何 ``*_API_KEY``。"""

    with pytest.raises(UnsafeEnvironmentError):
        assert_env_allowlist({name: "unit-LEAK"})
