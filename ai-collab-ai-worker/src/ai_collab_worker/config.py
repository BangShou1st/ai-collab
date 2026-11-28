"""服务配置与进程环境 allowlist。

设计依据：``docs/design/python-ai-capability-plane-2026-11-11.md`` §6.1 / §10.1、README §8，以及
P1 契约 ``docs/design/p1-ai-kernel-contract-2026-11-26.md`` §8。认知面只读 allowlist 内的变量；
进程环境一旦出现 ``JWT_SECRET``、``POSTGRES_*``、``*_API_KEY``，或出现未知的 ``AI_WORKER_*``，
立即失败，避免密钥与数据库凭据被误挂载进 Python 进程。**Provider 密钥只经凭据租借按次下发**
（契约 §2），永远不来自环境变量。

变量名：大多数字段是 ``AI_WORKER_<FIELD>``；契约 §8 指定的四个变量按原名读取：

- ``REDIS_URL``（含密码，敏感值：``repr`` 不出，密码登记进日志脱敏）；
- ``AI_WORKER_ID``（可选，缺省 ``{hostname}-{pid}``）；
- ``AI_TASK_CONCURRENCY``（Stream 消费并发上限，默认 4）；
- ``JAVA_INTERNAL_BASE_URL``（Java 内部接口地址；没有任何旧名别名，未知的 ``AI_WORKER_*``
  一律拒绝启动）。

内部令牌只有 Ed25519 一种：``AI_WORKER_ED25519_PUBLIC_KEYS`` 必填，缺失或非法时
:func:`ai_collab_worker.main.create_app` 抛 ``AuthConfigurationError``。错误信息只包含变量名，
绝不包含变量值。
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Final, Literal
from urllib.parse import unquote, urlsplit

from pydantic import AliasChoices, Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

#: 配置变量统一前缀。
ENV_PREFIX: Final[str] = "AI_WORKER_"

#: 默认 PromptPack 目录：``ai-collab-ai-worker/prompts/``（本文件位于 ``src/ai_collab_worker/``）。
_DEFAULT_PROMPTS_DIR: Final[Path] = Path(__file__).resolve().parents[2] / "prompts"

#: 禁入前缀（比对前统一大写，因此天然大小写不敏感）。
_FORBIDDEN_PREFIXES: Final[tuple[str, ...]] = ("JWT_SECRET", "POSTGRES_")

#: 禁入后缀（比对前统一大写）。Provider 密钥一律经凭据租借下发。
_FORBIDDEN_SUFFIXES: Final[tuple[str, ...]] = ("_API_KEY",)

#: 不走 ``AI_WORKER_<FIELD>`` 规则的字段 → 变量名（契约 §8）。
_ENV_NAME_OVERRIDES: Final[Mapping[str, str]] = {
    "redis_url": "REDIS_URL",
    "worker_id": "AI_WORKER_ID",
    "task_concurrency": "AI_TASK_CONCURRENCY",
    "java_internal_base_url": "JAVA_INTERNAL_BASE_URL",
    # P2 契约 §5.2：ai_index 连接串（敏感值），只能是 ai_worker 角色。
    "index_database_url": "AI_INDEX_DATABASE_URL",
}


def env_name(field: str) -> str:
    """字段对应的主变量名。"""

    return _ENV_NAME_OVERRIDES.get(field, f"{ENV_PREFIX}{field.upper()}")


class UnsafeEnvironmentError(RuntimeError):
    """进程环境里出现禁入变量。只携带变量名，不携带变量值。"""


class Settings(BaseSettings):
    """worker 配置（大小写不敏感，未知 ``AI_WORKER_*`` 一律失败）。"""

    model_config = SettingsConfigDict(
        env_prefix=ENV_PREFIX,
        case_sensitive=False,
        extra="forbid",
        populate_by_name=True,
        # 不读 .env：配置只来自进程环境，避免把仓库里的开发密钥带进进程。
        env_file=None,
        # 校验错误不回显输入值：REDIS_URL 里有密码（契约 §8），错误进日志也不能带出来。
        hide_input_in_errors=True,
    )

    host: str = "0.0.0.0"
    port: int = 9100
    log_level: str = "INFO"
    role: Literal["api", "job"] = "api"
    prompts_dir: Path = Field(
        default_factory=lambda: _DEFAULT_PROMPTS_DIR,
        description="PromptPack 目录；容器里通常只读挂载到 /app/prompts。",
    )
    ed25519_public_keys: str = Field(
        default="",
        repr=False,
        description=(
            "必填：Ed25519 公钥集合（kid=SPKI DER base64，逗号分隔，支持多 kid 轮换）；"
            "Python 只持公钥，私钥只在 Java。缺失或非法时启动失败。"
        ),
    )
    java_internal_base_url: str = Field(
        default="http://app:8080",
        min_length=1,
        validation_alias=AliasChoices("JAVA_INTERNAL_BASE_URL"),
        description=(
            "Java 内部接口地址（凭据租借、用量回报、任务回调）；必须能同时到达所有 Java 实例。"
        ),
    )
    redis_url: SecretStr | None = Field(
        default=None,
        repr=False,
        validation_alias=AliasChoices("REDIS_URL"),
        description="Redis 地址（含密码）；配置后启动 Stream 消费，未配置则不消费任务。",
    )
    worker_id: str | None = Field(
        default=None,
        min_length=1,
        max_length=128,
        validation_alias=AliasChoices("AI_WORKER_ID"),
        description="Stream 消费者名；缺省 {hostname}-{pid}。",
    )
    index_database_url: SecretStr | None = Field(
        default=None,
        repr=False,
        validation_alias=AliasChoices("AI_INDEX_DATABASE_URL"),
        description=(
            "ai_index 连接串（含密码，敏感值）；必须以 ai_worker 角色连接。"
            "配置后启动检查 + 迁移，并注册 ai_index 就绪检查；未配置则不提供索引与检索。"
        ),
    )
    task_concurrency: int = Field(
        default=4,
        ge=1,
        le=64,
        validation_alias=AliasChoices("AI_TASK_CONCURRENCY"),
        description="同时执行的任务上限。",
    )
    model_timeout_seconds: float = Field(
        default=60.0,
        gt=0,
        le=600,
        description="单次模型 HTTP 请求超时（秒），由内核统一设置，不用 SDK 默认值。",
    )
    model_max_retries: int = Field(
        default=2,
        ge=0,
        le=5,
        description="模型请求的重试次数（不含首次），由内核统一设置。",
    )
    query_plan_model_alias: str = Field(
        default="query-plan",
        min_length=1,
        max_length=128,
        description="query plan 响应 modelAlias 的安全口径来源；不在本波响应里返回。",
    )
    query_plan_deadline_ms: int = Field(
        default=8000,
        ge=1,
        le=30_000,
        description="query plan 服务端 deadline 上限；实际取 min(请求 deadlineMs, 本值)。",
    )
    qa_plan_enabled: bool = Field(
        default=True,
        description=(
            "问答 COMPLEX 路由的进程内 query plan 增强（P3 契约 §2.4：Java 侧 "
            "query.plan.enabled 语义随进程内化迁移到此处；staging 部署前置检查 "
            "§6.3 要求其生效）。关闭时 COMPLEX 直接使用本地规则结果。"
        ),
    )
    memory_job_deadline_ms: int = Field(
        default=60_000,
        ge=1,
        le=120_000,
        description="记忆抽取任务的执行 deadline（毫秒）。",
    )
    memory_max_candidates: int = Field(
        default=50,
        ge=1,
        le=50,
        description="每次记忆抽取提交给 Java 的候选上限；不得高于契约的 50。",
    )

    @field_validator("redis_url")
    @classmethod
    def _check_redis_url(cls, value: SecretStr | None) -> SecretStr | None:
        if value is None or not value.get_secret_value().strip():
            return None
        if urlsplit(value.get_secret_value()).scheme not in {"redis", "rediss"}:
            # 消息不含值：值里有密码。
            msg = "REDIS_URL 必须是 redis:// 或 rediss:// URL"
            raise ValueError(msg)
        return value

    @field_validator("index_database_url")
    @classmethod
    def _check_index_database_url(cls, value: SecretStr | None) -> SecretStr | None:
        if value is None or not value.get_secret_value().strip():
            return None
        if urlsplit(value.get_secret_value()).scheme not in {"postgresql", "postgres"}:
            # 消息不含值：值里有密码。
            msg = "AI_INDEX_DATABASE_URL 必须是 postgresql:// URL"
            raise ValueError(msg)
        return value

    @classmethod
    def env_names(cls) -> dict[str, str]:
        """字段 → 主变量名。"""

        return {name: env_name(name) for name in cls.model_fields}

    @classmethod
    def allowed_env_names(cls) -> frozenset[str]:
        """允许出现在进程环境里的完整变量名。"""

        return frozenset(cls.env_names().values())

    def redis_password(self) -> str | None:
        """``REDIS_URL`` 里的密码（用于登记日志脱敏）；没有则 ``None``。"""

        if self.redis_url is None:
            return None
        parts = urlsplit(self.redis_url.get_secret_value())
        return unquote(parts.password) if parts.password else None


def assert_env_allowlist(environ: Mapping[str, str]) -> None:
    """拒绝把密钥/数据库凭据带进认知面进程，命中即抛 :class:`UnsafeEnvironmentError`。

    规则均大小写不敏感：``JWT_SECRET*``、``POSTGRES_*``、``*_API_KEY``、以及不在
    :meth:`Settings.allowed_env_names` 里的 ``AI_WORKER_*``。错误信息只写变量名。
    """

    allowed = Settings.allowed_env_names()
    for key in sorted(environ):
        name = key.upper()
        if name.startswith(_FORBIDDEN_PREFIXES):
            msg = f"环境变量 {key} 命中 P0 禁入前缀（密钥/数据库凭据不得进入 Python 进程）"
        elif name.endswith(_FORBIDDEN_SUFFIXES):
            msg = f"环境变量 {key} 命中 P0 禁入后缀 _API_KEY（Provider 密钥不得进入 Python 进程）"
        elif name.startswith(ENV_PREFIX) and name not in allowed:
            listed = sorted(n for n in allowed if n.startswith(ENV_PREFIX))
            msg = f"未知环境变量 {key}；只允许 {', '.join(listed)}"
        else:
            continue
        raise UnsafeEnvironmentError(msg)


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    """校验 allowlist 后构造 :class:`Settings`。

    ``environ`` 缺省读 ``os.environ``；显式传入映射时只使用该映射，缺失字段取模型默认值。
    """

    effective = os.environ if environ is None else environ
    assert_env_allowlist(effective)
    return Settings(**_settings_values(effective))


def _settings_values(environ: Mapping[str, str]) -> dict[str, Any]:
    """把环境映射投影成 ``Settings`` 的初始化参数（缺失字段补模型默认值）。"""

    provided = {key.upper(): value for key, value in environ.items()}
    values: dict[str, Any] = {}
    for name, field in Settings.model_fields.items():
        key = env_name(name)
        found = provided.get(key)
        values[name] = found if found is not None else field.get_default(call_default_factory=True)
    return values


__all__ = [
    "ENV_PREFIX",
    "Settings",
    "UnsafeEnvironmentError",
    "assert_env_allowlist",
    "env_name",
    "load_settings",
]
