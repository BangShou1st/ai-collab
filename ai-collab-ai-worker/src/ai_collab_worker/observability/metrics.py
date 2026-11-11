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

#: Rerank 请求结果（低基数）：ok / deadline / model_error / invalid / partial。
RERANK_REQUESTS: Final[Counter] = Counter(
    "ai_worker_rerank_requests_total",
    "Rerank 请求总数，按结果分类。",
    ("outcome",),
    registry=REGISTRY,
)

#: Rerank 端到端耗时（秒）：含排队、网关调用、解析与打分，不含调用方网络。
RERANK_DURATION: Final[Histogram] = Histogram(
    "ai_worker_rerank_duration_seconds",
    "Rerank 端到端耗时（秒）。",
    registry=REGISTRY,
)

#: 模型输出漏掉的候选分数总数（每条候选记 1，不按请求记）。
RERANK_MISSING_SCORES: Final[Counter] = Counter(
    "ai_worker_rerank_missing_scores_total",
    "模型未返回、由服务端补 0.0 的候选分数总数。",
    registry=REGISTRY,
)

#: Query plan 端到端耗时（秒），outcome 为低基数分类（ok/deadline/model_error/invalid）。
QUERY_PLAN_DURATION: Final[Histogram] = Histogram(
    "ai_worker_query_plan_duration_seconds",
    "Query plan 端到端耗时（秒）。",
    ("outcome",),
    registry=REGISTRY,
)

#: Query plan 置信度分布（0..1）；成功响应才观测。
QUERY_PLAN_CONFIDENCE: Final[Histogram] = Histogram(
    "ai_worker_query_plan_confidence",
    "Query plan 响应里的 confidence 分布（0..1）。",
    buckets=(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0),
    registry=REGISTRY,
)

#: 模型编造、被解析层丢弃的 documentIds 总数（按 id 计，不按请求计）。
QUERY_PLAN_UNKNOWN_DOCUMENT_IDS: Final[Counter] = Counter(
    "ai_worker_query_plan_unknown_document_ids_total",
    "模型返回但不在请求 documentIds 白名单内、被丢弃的文档 id 总数。",
    registry=REGISTRY,
)

#: Decide 端到端耗时（秒）；decision 取四枚举或 none（失败/重放无法判定时）。
DECIDE_DURATION: Final[Histogram] = Histogram(
    "ai_worker_decide_duration_seconds",
    "Agent decide 端到端耗时（秒）。",
    ("outcome", "decision"),
    registry=REGISTRY,
)

#: 决策重试次数：内部纠错重试一次即记 1。
DECIDE_RETRY: Final[Counter] = Counter(
    "ai_worker_decide_retry_total",
    "Decide 内部纠错重试次数（每个请求最多一次）。",
    registry=REGISTRY,
)

#: 无效工具调用计数：CALL_TOOL 缺 toolCall / 形状非法 / 工具名不在 tools 内（最终仍失败）。
DECIDE_INVALID_TOOL_CALL: Final[Counter] = Counter(
    "ai_worker_decide_invalid_tool_call_total",
    "最终仍非法的工具调用决策次数（含缺 toolCall、形状非法与越界工具名）。",
    registry=REGISTRY,
)

#: 契约违规计数：模型输出 422（SCHEMA_VIOLATION）的总次数（含上面的无效工具调用）。
DECIDE_CONTRACT_VIOLATION: Final[Counter] = Counter(
    "ai_worker_decide_contract_violation_total",
    "模型决策输出不符合契约、最终返回 SCHEMA_VIOLATION 的次数。",
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
    "DECIDE_CONTRACT_VIOLATION",
    "DECIDE_DURATION",
    "DECIDE_INVALID_TOOL_CALL",
    "DECIDE_RETRY",
    "HTTP_DURATION",
    "HTTP_REQUESTS",
    "INFO",
    "QUERY_PLAN_CONFIDENCE",
    "QUERY_PLAN_DURATION",
    "QUERY_PLAN_UNKNOWN_DOCUMENT_IDS",
    "REGISTRY",
    "RERANK_DURATION",
    "RERANK_MISSING_SCORES",
    "RERANK_REQUESTS",
    "UNMATCHED_PATH",
    "instrument_app",
    "render_metrics",
]
