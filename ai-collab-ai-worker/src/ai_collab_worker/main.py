"""FastAPI 应用装配。

设计依据：设计 §6.1（``main.py`` = FastAPI 装配）与 §10.1（启动期安全门禁）；设计
2026-11-25 §0/§4.1/§11：只保留 Ed25519 短令牌，删除 rerank / decide / planning 端点。

装配顺序固定为：配置 → Ed25519 公钥环 → PromptPack 存储 → 共享 Java 网关（模型调用）+ Job
内部端点客户端 → 两个认知服务（query plan / memory job）→ 错误信封 → 指标 middleware → 路由 →
就绪检查。启动期（lifespan）再做一次环境 allowlist 校验并配置日志，因此 "环境里有密钥" 表现为
**启动失败**而不是请求期异常。

模块级 ``app`` 在首次访问时装配（PEP 562），装配期允许的失败原因只有两类：配置不合法（未知/
非法的 ``AI_WORKER_*``，或进程环境命中密钥禁入清单），以及 ``AI_WORKER_ED25519_PUBLIC_KEYS``
缺失或非法（``AuthConfigurationError``，消息不含公钥内容）。

PromptPack 缺失/损坏**不会**让进程起不来：``/healthz`` 仍 200、``/readyz`` 503，
由编排层决定是否接流量（设计 §6.3 / §7.2）。Java 网关**不进入** ``/readyz``：
网关暂不可用时进程仍就绪，由端点按 503/504 语义逐请求失败。

认知端点**共用同一个** :class:`JavaGatewayClient`（同一连接池）；Job 的三个内部端点用独立的
:class:`JavaJobClient`。两个连接池都在 lifespan 退出时关闭一次。
"""

from __future__ import annotations

import functools
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from ai_collab_worker import __version__
from ai_collab_worker.api import health
from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.api.health import add_readiness_check
from ai_collab_worker.api.jobs import JobService
from ai_collab_worker.api.jobs import router as jobs_router
from ai_collab_worker.api.prompts import router as prompts_router
from ai_collab_worker.api.query_plan import QueryPlanService
from ai_collab_worker.api.query_plan import router as query_plan_router
from ai_collab_worker.auth.token import Ed25519KeyRing, TokenVerifier
from ai_collab_worker.clients.java_gateway import JavaGatewayClient
from ai_collab_worker.clients.java_jobs import JavaJobClient
from ai_collab_worker.config import Settings, assert_env_allowlist, load_settings
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import instrument_app
from ai_collab_worker.prompts.store import PromptStore


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动期：配置日志、再校验一次进程环境；退出时关闭共享网关连接池。"""

    settings: Settings = app.state.settings
    try:
        configure_logging(settings.log_level)
        assert_env_allowlist(os.environ)
        yield
    finally:
        gateway = getattr(app.state, "gateway", None)
        if isinstance(gateway, JavaGatewayClient):
            await gateway.aclose()
        jobs = getattr(app.state, "java_jobs", None)
        if isinstance(jobs, JavaJobClient):
            await jobs.aclose()


def create_app(settings: Settings | None = None) -> FastAPI:
    """装配并返回 FastAPI app；``settings`` 缺省时按 ``AI_WORKER_*`` 环境读取。

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

    gateway = JavaGatewayClient(base_url=resolved.java_internal_base_url)
    app.state.gateway = gateway
    java_jobs = JavaJobClient(base_url=resolved.java_internal_base_url)
    app.state.java_jobs = java_jobs
    app.state.query_plan_service = QueryPlanService(
        settings=resolved, gateway=gateway, prompt_store=store
    )
    app.state.job_service = JobService(
        settings=resolved,
        gateway=gateway,
        jobs=java_jobs,
        prompt_store=store,
    )

    install_error_handlers(app)
    instrument_app(app)
    app.include_router(health.router)
    app.include_router(prompts_router)
    app.include_router(query_plan_router)
    app.include_router(jobs_router)
    return app


@functools.cache
def _default_app() -> FastAPI:
    return create_app()


def __getattr__(name: str) -> FastAPI:
    """``uvicorn ai_collab_worker.main:app`` 的入口（PEP 562 惰性装配）。

    公钥必填后，import 期装配会让任何 ``import ai_collab_worker.main`` 都依赖进程环境；
    改为首次访问 ``app`` 时才按 ``AI_WORKER_*`` 装配一次，启动命令保持不变。
    """

    if name == "app":
        return _default_app()
    msg = f"module {__name__!r} has no attribute {name!r}"
    raise AttributeError(msg)


# ``app`` 由模块级 ``__getattr__`` 惰性提供（PEP 562），静态分析看不到绑定。
__all__ = ["app", "create_app"]  # noqa: F822
