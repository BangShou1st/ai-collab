"""FastAPI 应用装配。

设计依据：设计 §6.1（``main.py`` = FastAPI 装配）与 §10.1（启动期安全门禁）；设计
2026-11-25 §0/§4.1/§11；P1 契约 ``docs/design/p1-ai-kernel-contract-2026-11-26.md``。

装配顺序固定为：配置 → Ed25519 公钥环 → PromptPack 存储 → Java 内部接口客户端（凭据租借、
用量回报、任务回调，同一连接池）→ 模型内核（lease 缓存 + 用量上报队列）→ query plan 服务 →
（配置了 ``REDIS_URL`` 时）Stream 消费者与记忆抽取处理器 → 错误信封 → 指标 middleware → 路由 →
就绪检查。启动期（lifespan）再做一次环境 allowlist 校验、配置日志与脱敏、登记 ``REDIS_URL``
密码进脱敏规则，再启动用量上报与 Stream 消费；退出时按相反顺序优雅停机。

模块级 ``app`` 在首次访问时装配（PEP 562），装配期允许的失败原因只有两类：配置不合法（未知/
非法的 ``AI_WORKER_*``，或进程环境命中密钥禁入清单），以及 ``AI_WORKER_ED25519_PUBLIC_KEYS``
缺失或非法（``AuthConfigurationError``，消息不含公钥内容）。

PromptPack 缺失/损坏**不会**让进程起不来：``/healthz`` 仍 200、``/readyz`` 503。配置了 Redis 时
``/readyz`` 还反映 Stream 消费者的 Redis 连接状态（断连 → ``redis_unavailable``）。Java 内部接口
**不进入** ``/readyz``：暂不可用时由各调用点按 503/504 语义逐请求失败。
"""

from __future__ import annotations

import functools
import os
import socket
from collections.abc import AsyncIterator, Mapping, Sequence
from contextlib import asynccontextmanager

from fastapi import FastAPI
from redis.asyncio import Redis

from ai_collab_worker import __version__
from ai_collab_worker.api import health
from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.api.health import add_readiness_check
from ai_collab_worker.api.prompts import router as prompts_router
from ai_collab_worker.api.query_plan import QueryPlanService
from ai_collab_worker.api.query_plan import router as query_plan_router
from ai_collab_worker.auth.token import Ed25519KeyRing, TokenVerifier
from ai_collab_worker.clients.java_internal import JavaInternalClient
from ai_collab_worker.config import Settings, assert_env_allowlist, load_settings
from ai_collab_worker.index.db import IndexDatabase, database_password
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import LeaseCache
from ai_collab_worker.models.usage import UsageReporter, UsageSender
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import instrument_app
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.security.redaction import SECRETS
from ai_collab_worker.tasks.memory import TASK_TYPE as MEMORY_TASK_TYPE
from ai_collab_worker.tasks.memory import MemoryExtractionHandler
from ai_collab_worker.tasks.stream import (
    DEFAULT_SOCKET_CONNECT_TIMEOUT_SECONDS,
    DEFAULT_SOCKET_TIMEOUT_SECONDS,
    StreamConsumer,
)


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动：日志/脱敏/环境校验 → 用量上报 → Stream 消费；退出：反序优雅停机。"""

    settings: Settings = app.state.settings
    usage: UsageReporter = app.state.usage_reporter
    consumer: StreamConsumer | None = app.state.stream_consumer
    java: JavaInternalClient = app.state.java_internal
    redis: Redis | None = app.state.redis
    index_db: IndexDatabase | None = app.state.index_db
    try:
        configure_logging(settings.log_level)
        assert_env_allowlist(os.environ)
        redis_pw = settings.redis_password()
        if redis_pw:
            SECRETS.add(redis_pw)
        db_pw = database_password(settings.index_database_url)
        if db_pw:
            SECRETS.add(db_pw)
        if index_db is not None:
            await index_db.start()
        usage.start()
        if consumer is not None:
            consumer.start()
        yield
    finally:
        if consumer is not None:
            await consumer.stop()
        await usage.aclose()
        app.state.lease_cache.clear()
        if redis is not None:
            await redis.aclose()
        if index_db is not None:
            await index_db.aclose()
        await java.aclose()


def create_app(settings: Settings | None = None) -> FastAPI:
    """装配并返回 FastAPI app；``settings`` 缺省时按环境读取。

    依赖注入 ``settings`` 是为了让测试与容器入口不必改进程环境；无论哪种方式，
    ``app.state.settings`` 都是运行期唯一配置来源（端点不读模块级全局）。

    Ed25519 公钥环最先解析：缺失或非法时直接抛
    :class:`~ai_collab_worker.auth.token.AuthConfigurationError`，进程不对外服务（fail fast）。
    """

    resolved = load_settings() if settings is None else settings
    key_ring = Ed25519KeyRing.parse(resolved.ed25519_public_keys)

    app = FastAPI(
        title="AI Collab AI Worker",
        version=__version__,
        lifespan=_lifespan,
    )
    app.state.settings = resolved
    app.state.readyz_checks = {}
    app.state.token_verifier = TokenVerifier(key_ring=key_ring)

    store = PromptStore(resolved.prompts_dir)
    app.state.prompt_store = store
    add_readiness_check(app, "prompts", store.readiness_failure)

    java = JavaInternalClient(base_url=resolved.java_internal_base_url)
    app.state.java_internal = java
    leases = LeaseCache(lambda request, token: java.lease(request, bearer_token=token))
    app.state.lease_cache = leases
    usage = UsageReporter(_usage_sender(java))
    app.state.usage_reporter = usage
    kernel = ModelKernel(
        leases=leases,
        usage=usage,
        runtime=ModelRuntime(
            timeout_seconds=resolved.model_timeout_seconds,
            max_retries=resolved.model_max_retries,
        ),
    )
    app.state.model_kernel = kernel
    app.state.query_plan_service = QueryPlanService(
        settings=resolved, kernel=kernel, prompt_store=store
    )

    app.state.redis = None
    app.state.stream_consumer = None
    app.state.index_db = None
    if resolved.index_database_url is not None:
        index_db = IndexDatabase(resolved.index_database_url)
        app.state.index_db = index_db
        add_readiness_check(app, "ai_index", index_db.readiness_failure)
    if resolved.redis_url is not None:
        redis = Redis.from_url(
            resolved.redis_url.get_secret_value(),
            decode_responses=True,
            socket_timeout=DEFAULT_SOCKET_TIMEOUT_SECONDS,
            socket_connect_timeout=DEFAULT_SOCKET_CONNECT_TIMEOUT_SECONDS,
        )
        consumer = StreamConsumer(
            redis,
            java,
            {
                MEMORY_TASK_TYPE: MemoryExtractionHandler(
                    settings=resolved, java=java, kernel=kernel, prompt_store=store
                ),
            },
            worker_id=resolved.worker_id or default_worker_id(),
            concurrency=resolved.task_concurrency,
        )
        app.state.redis = redis
        app.state.stream_consumer = consumer
        add_readiness_check(app, "redis", consumer.readiness_failure)

    install_error_handlers(app)
    instrument_app(app)
    app.include_router(health.router)
    app.include_router(prompts_router)
    app.include_router(query_plan_router)
    return app


def default_worker_id() -> str:
    """契约 §4.5：``{hostname}-{pid}``。"""

    return f"{socket.gethostname()}-{os.getpid()}"


def _usage_sender(java: JavaInternalClient) -> UsageSender:
    async def send(items: Sequence[Mapping[str, object]], bearer_token: str) -> dict[str, bool]:
        results = await java.report_usage(items, bearer_token=bearer_token)
        return {result.call_id: result.accepted for result in results}

    return send


@functools.cache
def _default_app() -> FastAPI:
    return create_app()


def __getattr__(name: str) -> FastAPI:
    """``uvicorn ai_collab_worker.main:app`` 的入口（PEP 562 惰性装配）。

    公钥必填后，import 期装配会让任何 ``import ai_collab_worker.main`` 都依赖进程环境；
    改为首次访问 ``app`` 时才按环境装配一次，启动命令保持不变。
    """

    if name == "app":
        return _default_app()
    msg = f"module {__name__!r} has no attribute {name!r}"
    raise AttributeError(msg)


# ``app`` 由模块级 ``__getattr__`` 惰性提供（PEP 562），静态分析看不到绑定。
__all__ = ["app", "create_app", "default_worker_id"]  # noqa: F822
