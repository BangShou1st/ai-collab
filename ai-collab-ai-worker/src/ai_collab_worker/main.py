"""FastAPI 应用装配。

设计依据：设计 §6.1（``main.py`` = FastAPI 装配）与 §10.1（启动期安全门禁）。

装配顺序固定为：配置 → PromptPack 存储 → rerank 服务/网关 → 错误信封 → 指标 middleware
→ 路由 → 就绪检查。启动期（lifespan）再做一次环境 allowlist 校验并配置日志，因此
"环境里有密钥"表现为**启动失败**而不是请求期异常。

模块级 ``app`` 会在 import 时装配，导入期唯一允许的失败原因是配置不合法（未知/非法的
``AI_WORKER_*``，或进程环境命中密钥禁入清单）；``PATH``、``JAVA_HOME`` 等无关变量不影响。
PromptPack 缺失/损坏**不会**让进程起不来：``/healthz`` 仍 200、``/readyz`` 503，
由编排层决定是否接流量（设计 §6.3 / §7.2）。rerank 的 Java 网关同样**不进入** ``/readyz``：
网关暂不可用时进程仍就绪，由端点按 503/504 语义逐请求失败（P1 冻结口径）。
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from ai_collab_worker import __version__
from ai_collab_worker.api import health
from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.api.health import add_readiness_check
from ai_collab_worker.api.prompts import router as prompts_router
from ai_collab_worker.api.rerank import RerankService
from ai_collab_worker.api.rerank import router as rerank_router
from ai_collab_worker.clients.java_gateway import JavaGatewayClient
from ai_collab_worker.config import Settings, assert_env_allowlist, load_settings
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import instrument_app
from ai_collab_worker.prompts.store import PromptStore


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动期：配置日志、再校验一次进程环境；退出时关闭 rerank 网关连接池。"""

    settings: Settings = app.state.settings
    try:
        configure_logging(settings.log_level)
        assert_env_allowlist(os.environ)
        yield
    finally:
        service = getattr(app.state, "rerank_service", None)
        if isinstance(service, RerankService):
            await service.aclose()


def create_app(settings: Settings | None = None) -> FastAPI:
    """装配并返回 FastAPI app；``settings`` 缺省时按 ``AI_WORKER_*`` 环境读取。

    依赖注入 ``settings`` 是为了让测试与容器入口不必改进程环境；无论哪种方式，
    ``app.state.settings`` 都是运行期唯一配置来源（端点不读模块级全局）。

    PromptPack 存储在这里初始化并注册为 ``prompts`` 就绪检查：资产不可用时进程仍可存活，
    但 ``/readyz`` 返回 503；``GET /v1/prompts/*`` 则由存储按 503/404/400 语义拒绝。
    rerank 服务同时初始化并挂到 ``app.state.rerank_service``；它不在就绪检查里。
    """

    resolved = load_settings() if settings is None else settings
    app = FastAPI(
        title="AI Collab AI Worker",
        version=__version__,
        lifespan=_lifespan,
    )
    app.state.settings = resolved
    app.state.readyz_checks = {}

    store = PromptStore(resolved.prompts_dir)
    app.state.prompt_store = store
    add_readiness_check(app, "prompts", store.readiness_failure)

    gateway = JavaGatewayClient(
        base_url=resolved.java_gateway_url,
        service_token=resolved.service_token,
    )
    app.state.rerank_service = RerankService(settings=resolved, gateway=gateway)

    install_error_handlers(app)
    instrument_app(app)
    app.include_router(health.router)
    app.include_router(prompts_router)
    app.include_router(rerank_router)
    return app


#: ``uvicorn ai_collab_worker.main:app`` 的入口。
app = create_app()


__all__ = ["app", "create_app"]
