"""Prometheus 指标与 HTTP middleware。

设计依据：设计 §6.2（prometheus-client）与 README §4（``/metrics``）。

两条硬约束：

- **独立 Registry**：不使用 prometheus_client 的默认全局 ``REGISTRY``。多个 app、
  多次导入或测试之间重复注册同名指标会直接抛 ``Duplicated timeseries``，隔离 Registry
  让装配可重复。
- **低基数标签**：``path`` 标签只取路由模板（``request.scope["route"].path``），
  未匹配路由记 ``unmatched``。原始 URL 既会放大时间序列基数（含资源 id），也可能带上
  Token 之类的敏感值，因此绝不入标签。
"""

from __future__ import annotations

import time
from typing import Final

from fastapi import FastAPI, Request, Response
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)
from starlette.middleware.base import RequestResponseEndpoint

from ai_collab_worker import __version__

#: 本进程专属的指标注册表（不污染、也不依赖 prometheus_client 的全局 ``REGISTRY``）。
REGISTRY: Final[CollectorRegistry] = CollectorRegistry()

#: 未匹配到任何路由时的 ``path`` 标签值。
UNMATCHED_PATH: Final[str] = "unmatched"

#: ``app.state.settings`` 缺失时的 ``role`` 标签值（正常装配不会出现）。
UNKNOWN_ROLE: Final[str] = "unknown"

#: 进程信息：值恒为 1，用标签区分版本与角色（api/job 共用镜像）。
INFO: Final[Gauge] = Gauge(
    "ai_worker_info",
    "进程信息，恒为 1；标签区分 version 与 role。",
    ("version", "role"),
    registry=REGISTRY,
)

#: HTTP 请求数：path 为路由模板，status 为响应状态码。
HTTP_REQUESTS: Final[Counter] = Counter(
    "ai_worker_http_requests_total",
    "HTTP 请求总数（path 为路由模板，未匹配为 unmatched）。",
    ("method", "path", "status"),
    registry=REGISTRY,
)

#: HTTP 请求耗时（秒）：不带 status，避免指标维度膨胀。
HTTP_DURATION: Final[Histogram] = Histogram(
    "ai_worker_http_request_duration_seconds",
    "HTTP 请求耗时（秒）。",
    ("method", "path"),
    registry=REGISTRY,
)


def render_metrics() -> tuple[bytes, str]:
    """渲染 Prometheus 文本格式快照，返回 ``(payload, content_type)``。"""

    return generate_latest(REGISTRY), CONTENT_TYPE_LATEST


def instrument_app(app: FastAPI) -> None:
    """给 app 装上 HTTP 指标 middleware，并写入一次 ``ai_worker_info``。"""

    INFO.labels(version=__version__, role=_service_role(app)).set(1)

    @app.middleware("http")
    async def _observe_http(request: Request, call_next: RequestResponseEndpoint) -> Response:
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            # 交给外层 ServerErrorMiddleware 的异常处理器渲染，但账要先记上。
            _record(request, status="500", elapsed=time.perf_counter() - started)
            raise
        _record(request, status=str(response.status_code), elapsed=time.perf_counter() - started)
        return response


def _service_role(app: FastAPI) -> str:
    """从 ``app.state.settings`` 取角色；未装配时退化为 ``unknown``。"""

    settings = getattr(app.state, "settings", None)
    role = getattr(settings, "role", None)
    return role if isinstance(role, str) else UNKNOWN_ROLE


def _route_label(request: Request) -> str:
    """取路由模板作为 ``path`` 标签；取不到（如 404）返回 ``unmatched``。"""

    template = getattr(request.scope.get("route"), "path", None)
    return template if isinstance(template, str) else UNMATCHED_PATH


def _record(request: Request, *, status: str, elapsed: float) -> None:
    """记录一次请求：计数 + 耗时，标签只含 method/path/status。"""

    path = _route_label(request)
    method = request.method
    HTTP_REQUESTS.labels(method=method, path=path, status=status).inc()
    HTTP_DURATION.labels(method=method, path=path).observe(elapsed)


__all__ = [
    "HTTP_DURATION",
    "HTTP_REQUESTS",
    "INFO",
    "REGISTRY",
    "UNMATCHED_PATH",
    "instrument_app",
    "render_metrics",
]
