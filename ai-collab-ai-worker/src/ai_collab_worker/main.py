"""FastAPI 应用装配。

设计依据：设计 §6.1（``main.py`` = FastAPI 装配）与 §10.1（启动期安全门禁）。

装配顺序固定为：配置 → 错误信封 → 指标 middleware → 路由。启动期（lifespan）再做一次
环境 allowlist 校验并配置日志，因此"环境里有密钥"表现为**启动失败**而不是请求期异常。

模块级 ``app`` 会在 import 时装配，导入期唯一允许的失败原因是配置不合法（未知/非法的
``AI_WORKER_*``，或进程环境命中密钥禁入清单）；``PATH``、``JAVA_HOME`` 等无关变量不影响。
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from ai_collab_worker import __version__
from ai_collab_worker.api import health
from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.config import Settings, assert_env_allowlist, load_settings
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import instrument_app


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动期：配置日志，并在真正对外服务前再校验一次进程环境。"""

    settings: Settings = app.state.settings
    configure_logging(settings.log_level)
    assert_env_allowlist(os.environ)
    yield


def create_app(settings: Settings | None = None) -> FastAPI:
    """装配并返回 FastAPI app；``settings`` 缺省时按 ``AI_WORKER_*`` 环境读取。

    依赖注入 ``settings`` 是为了让测试与容器入口不必改进程环境；无论哪种方式，
    ``app.state.settings`` 都是运行期唯一配置来源（端点不读模块级全局）。
    """

    resolved = load_settings() if settings is None else settings
    app = FastAPI(
        title="AI Collab AI Worker",
        version=__version__,
        lifespan=_lifespan,
    )
    app.state.settings = resolved
    app.state.readyz_checks = {}

    install_error_handlers(app)
    instrument_app(app)
    app.include_router(health.router)
    return app


#: ``uvicorn ai_collab_worker.main:app`` 的入口。
app = create_app()


__all__ = ["app", "create_app"]
