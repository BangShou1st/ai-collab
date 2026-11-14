"""服务配置（``AI_WORKER_*``）与进程环境 allowlist。

设计依据：``docs/design/python-ai-capability-plane-2026-11-11.md`` §6.1（模块）、§10.1
（上线前安全门禁）与 README §8。认知面只读 allowlist 内的 ``AI_WORKER_*`` 变量；进程环境
一旦出现 ``JWT_SECRET``、``POSTGRES_*``、``*_API_KEY``，或出现未知的 ``AI_WORKER_*``，
立即失败，避免密钥与数据库凭据被误挂载进 Python 进程。

P1 的 rerank 配置（服务令牌、Java 网关地址、模型别名、幂等 TTL、候选上限）也走同一
allowlist；W3.2 的令牌模式与 Ed25519 公钥同样走 allowlist（**私钥只在 Java**）。
**不新增任何 Provider 密钥字段**，配置里永远不会出现 ``*_API_KEY``。

错误信息只包含变量名，绝不包含变量值：值可能是密钥，异常会进日志与错误信封。
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Final, Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

#: 配置变量统一前缀。
ENV_PREFIX: Final[str] = "AI_WORKER_"

#: 默认 PromptPack 目录：``ai-collab-ai-worker/prompts/``（本文件位于 ``src/ai_collab_worker/``）。
_DEFAULT_PROMPTS_DIR: Final[Path] = Path(__file__).resolve().parents[2] / "prompts"

#: 禁入前缀（比对前统一大写，因此天然大小写不敏感）。
_FORBIDDEN_PREFIXES: Final[tuple[str, ...]] = ("JWT_SECRET", "POSTGRES_")

#: 禁入后缀（比对前统一大写）。Provider 密钥一律留在 Java 控制面。
_FORBIDDEN_SUFFIXES: Final[tuple[str, ...]] = ("_API_KEY",)


class UnsafeEnvironmentError(RuntimeError):
    """进程环境里出现 P0 禁入变量。

    只携带变量名，不携带变量值：值可能是密钥，会随异常进入日志与错误响应。
    """


class Settings(BaseSettings):
    """``AI_WORKER_*`` 配置（大小写不敏感，未知同前缀变量一律失败）。

    ``extra="forbid"`` 拒绝直接构造时多传字段；环境侧未知的 ``AI_WORKER_*`` 由
    :func:`assert_env_allowlist` 拦截，因为 pydantic-settings 只按字段读取环境，
    不会把无关变量当成校验输入。
    """

    model_config = SettingsConfigDict(
        env_prefix=ENV_PREFIX,
        case_sensitive=False,
        extra="forbid",
        # 不读 .env：配置只来自进程环境，避免把仓库里的开发密钥带进进程。
        env_file=None,
    )

    host: str = "0.0.0.0"
    port: int = 9100
    log_level: str = "INFO"
    role: Literal["api", "job"] = "api"
    prompts_dir: Path = Field(
        default_factory=lambda: _DEFAULT_PROMPTS_DIR,
        description="PromptPack 目录；容器里通常只读挂载到 /app/prompts。",
    )
    service_token: str = Field(
        default="",
        repr=False,
        description="内部服务令牌（只经环境变量传入）；未配置时 rerank 按 fail-closed 拒绝。",
    )
    token_mode: Literal["static", "ed25519", "dual"] = Field(
        default="static",
        description=(
            "内部令牌模式：static=静态令牌（默认，行为不变）；ed25519=只验 Java 签发的短令牌；"
            "dual=轮换过渡（先验 JWT，失败再比较静态令牌）。"
        ),
    )
    ed25519_public_keys: str = Field(
        default="",
        repr=False,
        description=(
            "Ed25519 公钥集合（kid=SPKI DER base64，逗号分隔，支持多 kid 轮换）；"
            "Python 只持公钥，私钥只在 Java。"
        ),
    )
    allow_insecure_internal: bool = Field(
        default=False,
        description="仅测试用：令牌未配置时仍放行 rerank；生产必须保持 false。",
    )
    java_gateway_url: str = Field(
        default="http://app:8080",
        min_length=1,
        description="Java 控制面地址；Python 只经此调用内部模型网关。",
    )
    model_alias: str = Field(
        default="rerank-default",
        min_length=1,
        max_length=128,
        description="响应 modelAlias 字段的取值（来自 worker 配置，不取上游响应）。",
    )
    idempotency_ttl_seconds: int = Field(
        default=600,
        ge=1,
        description="Idempotency-Key 结果缓存 TTL（秒），默认 10 分钟。",
    )
    max_candidates: int = Field(
        default=50,
        ge=1,
        le=50,
        description="服务端候选上限；不得高于契约的 50。",
    )
    query_plan_model_alias: str = Field(
        default="query-plan",
        min_length=1,
        max_length=128,
        description="query plan 响应 modelAlias 的安全口径来源；不在本波响应里返回。",
    )
    decide_model_alias: str = Field(
        default="agent-main",
        min_length=1,
        max_length=128,
        description="decide 响应 modelAlias 的取值（来自 worker 配置，不取上游响应）。",
    )
    query_plan_deadline_ms: int = Field(
        default=8000,
        ge=1,
        le=30_000,
        description="query plan 服务端 deadline 上限；实际取 min(请求 deadlineMs, 本值)。",
    )
    decide_deadline_ms: int = Field(
        default=30_000,
        ge=1,
        le=30_000,
        description=(
            "decide 服务端 deadline 上限；实际取 min(budget.deadlineMs, 本值)。"
            "上限与 Java 内部模型网关的 MAX_DEADLINE_MS=30000 对齐。"
        ),
    )
    planning_model_alias: str = Field(
        default="planning-main",
        min_length=1,
        max_length=128,
        description=(
            "planning 草稿的模型别名；W5 响应不含 modelAlias 字段，本值保留给后续网关选路"
            "与日志关联（与 query_plan_model_alias 同口径）。"
        ),
    )
    planning_deadline_ms: int = Field(
        default=30_000,
        ge=1,
        le=120_000,
        description=(
            "planning 服务端 deadline 上限；实际取 min(请求 deadlineMs, 本值)。"
            "请求契约上限为 30000（与 Java 网关 MAX_DEADLINE_MS 对齐），"
            "本值即使配大也不会放大预算。"
        ),
    )
    memory_job_deadline_ms: int = Field(
        default=60_000,
        ge=1,
        le=120_000,
        description="Memory Job 服务端 deadline 上限；实际取 min(请求 deadlineMs, 本值)。",
    )
    memory_max_candidates: int = Field(
        default=50,
        ge=1,
        le=50,
        description="每次 Memory Job 提交给 Java 的候选上限；不得高于契约的 50。",
    )

    @classmethod
    def allowed_env_names(cls) -> frozenset[str]:
        """按模型字段推导允许出现在进程环境里的完整变量名。

        新增字段即自动放宽 allowlist，避免"字段加了但变量名忘了登记"。
        """

        return frozenset(f"{ENV_PREFIX}{name.upper()}" for name in cls.model_fields)


def assert_env_allowlist(environ: Mapping[str, str]) -> None:
    """拒绝把密钥/数据库凭据带进认知面进程，命中即抛 :class:`UnsafeEnvironmentError`。

    纯函数：只读入参，不读进程环境、不写任何状态。判定规则均大小写不敏感：

    1. 变量名为 ``JWT_SECRET`` 或以 ``JWT_SECRET`` 开头（如 ``JWT_SECRET_OLD``）；
    2. 变量名以 ``POSTGRES_`` 开头；
    3. 变量名以 ``_API_KEY`` 结尾；
    4. 变量名以 ``AI_WORKER_`` 开头，但不在 :meth:`Settings.allowed_env_names` 里。

    错误信息只写变量名，绝不写变量值。
    """

    allowed = Settings.allowed_env_names()
    for key in sorted(environ):
        name = key.upper()
        if name.startswith(_FORBIDDEN_PREFIXES):
            msg = f"环境变量 {key} 命中 P0 禁入前缀（密钥/数据库凭据不得进入 Python 进程）"
        elif name.endswith(_FORBIDDEN_SUFFIXES):
            msg = f"环境变量 {key} 命中 P0 禁入后缀 _API_KEY（Provider 密钥不得进入 Python 进程）"
        elif name.startswith(ENV_PREFIX) and name not in allowed:
            msg = f"未知环境变量 {key}；只允许 {', '.join(sorted(allowed))}"
        else:
            continue
        raise UnsafeEnvironmentError(msg)


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    """校验 allowlist 后构造 :class:`Settings`。

    ``environ`` 缺省读 ``os.environ``（服务启动路径）；显式传入映射时只使用该映射，
    缺失字段取模型默认值，因此显式映射的调用不依赖进程环境，便于测试与容器入口。
    非法取值（如 ``AI_WORKER_PORT=abc``）抛 ``pydantic.ValidationError``。
    """

    effective = os.environ if environ is None else environ
    assert_env_allowlist(effective)
    return Settings(**_settings_values(effective))


def _settings_values(environ: Mapping[str, str]) -> dict[str, Any]:
    """把环境映射投影成 ``Settings`` 的初始化参数（缺失字段补模型默认值）。

    显式补齐默认值是为了隔离进程环境：既然传了映射就只认映射，避免宿主环境里的
    ``AI_WORKER_*`` 串进调用方的确定输入。
    """

    provided = {key.upper(): value for key, value in environ.items()}
    values: dict[str, Any] = {}
    for name, field in Settings.model_fields.items():
        env_name = f"{ENV_PREFIX}{name.upper()}"
        values[name] = (
            provided[env_name]
            if env_name in provided
            else field.get_default(call_default_factory=True)
        )
    return values


__all__ = [
    "ENV_PREFIX",
    "Settings",
    "UnsafeEnvironmentError",
    "assert_env_allowlist",
    "load_settings",
]
