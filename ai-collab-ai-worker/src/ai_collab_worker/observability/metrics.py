"""Prometheus 指标与 HTTP middleware。

设计依据：设计 §6.2（prometheus-client）与 README §4（``/metrics``）。

两条硬约束：

- **独立 Registry**：不使用 prometheus_client 的默认全局 ``REGISTRY``。多个 app、
  多次导入或测试之间重复注册同名指标会直接抛 ``Duplicated timeseries``，隔离 Registry
  让装配可重复。
- **低基数标签**：``path`` 标签只取路由模板（``request.scope["route"].path``），
  未匹配路由记 ``unmatched``。原始 URL 既会放大时间序列基数（含资源 id），也可能带上
  Token 之类的敏感值，因此绝不入标签。

指标记录本身**不得改变业务结果**：:func:`observe_histogram` / :func:`increment_counter`
把任何异常（例如标签数量不符）吞成 DEBUG 日志，调用方在业务路径上直接调用即可。
"""

from __future__ import annotations

import logging
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

logger: Final[logging.Logger] = logging.getLogger(__name__)

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

#: Rerank 请求结果（低基数）：ok / deadline / model_error / invalid / partial。
RERANK_REQUESTS: Final[Counter] = Counter(
    "ai_worker_rerank_requests_total",
    "Rerank 请求总数，按结果分类。",
    ("outcome",),
    registry=REGISTRY,
)

#: Rerank 端到端耗时（秒）：含排队、网关调用、解析与打分，不含调用方网络。
#: W3.1 冻结 §1.2：在原有无标签基础上加 ``outcome``（ok/partial/deadline/model_error/
#: invalid），**一次请求一条**；语义与既有 ``ai_worker_rerank_requests_total`` 一致。
RERANK_DURATION: Final[Histogram] = Histogram(
    "ai_worker_rerank_duration_seconds",
    "Rerank 端到端耗时（秒），按结果分类。",
    ("outcome",),
    registry=REGISTRY,
)

#: worker → Java 内部模型网关的 HTTP 耗时（秒）：含超时与失败（``outcome`` = ok/error）。
#: 用于把 ``T_worker_total`` 拆成"传输"与"本地计算"两段（设计 §3 指标表）。
RERANK_GATEWAY: Final[Histogram] = Histogram(
    "ai_worker_rerank_gateway_seconds",
    "worker → Java 内部模型网关的 HTTP 耗时（秒），含超时与异常。",
    ("outcome",),
    registry=REGISTRY,
)

#: 模型输出解析 + 分数映射耗时（秒）：无标签，用于定位解析退化。
RERANK_PARSE: Final[Histogram] = Histogram(
    "ai_worker_rerank_parse_seconds",
    "Rerank 模型输出解析与候选分数映射耗时（秒）。",
    registry=REGISTRY,
)

#: 模型输出漏掉的候选分数总数（每条候选记 1，不按请求记）。
RERANK_MISSING_SCORES: Final[Counter] = Counter(
    "ai_worker_rerank_missing_scores_total",
    "模型未返回、由服务端补 0.0 的候选分数总数。",
    registry=REGISTRY,
)


def observe_histogram(metric: Histogram, value: float, **labels: str) -> None:
    """记录一次直方图观测；任何异常都被吞掉（指标不得改变业务结果）。

    ``value`` 是秒（Prometheus 直方图约定）；负数会被 prometheus-client 忽略，这里不做
    额外处理，避免在业务路径上引入新的失败分支。
    """

    try:
        target = metric.labels(**labels) if labels else metric
        target.observe(value)
    except Exception:  # 指标异常绝不冒泡到业务路径（沿用 PromptCatalogMetrics 约定）
        logger.debug("prometheus histogram observe failed", exc_info=True)


def increment_counter(metric: Counter, amount: float = 1.0, **labels: str) -> None:
    """计数器自增（可指定增量）；任何异常都被吞掉（指标不得改变业务结果）。"""

    try:
        target = metric.labels(**labels) if labels else metric
        target.inc(amount)
    except Exception:  # 指标异常绝不冒泡到业务路径
        logger.debug("prometheus counter increment failed", exc_info=True)


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
    "RERANK_DURATION",
    "RERANK_GATEWAY",
    "RERANK_MISSING_SCORES",
    "RERANK_PARSE",
    "RERANK_REQUESTS",
    "UNMATCHED_PATH",
    "increment_counter",
    "instrument_app",
    "observe_histogram",
    "render_metrics",
]
