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

#: 记忆抽取任务执行次数：每个从 Stream 认领并执行的任务一条，标签 outcome 为低基数分类。
MEMORY_JOB_REQUESTS: Final[Counter] = Counter(
    "ai_worker_memory_job_requests_total",
    "Memory Job 请求总数，按结果分类（succeeded/failed_retryable/failed/invalid/...）。",
    ("outcome",),
    registry=REGISTRY,
)

#: Memory Job 端到端耗时（秒），与 requests 同标签口径。
MEMORY_JOB_DURATION: Final[Histogram] = Histogram(
    "ai_worker_memory_job_duration_seconds",
    "Memory Job 端到端耗时（秒），按结果分类。",
    ("outcome",),
    registry=REGISTRY,
)

#: 提交给 Java 的合法记忆候选总数（按条计）。
MEMORY_CANDIDATES: Final[Counter] = Counter(
    "ai_worker_memory_candidates_total",
    "提交给 Java 的合法记忆候选总数（不含本侧丢弃的候选）。",
    registry=REGISTRY,
)

#: 来源引用不在快照内、被丢弃的候选数。
MEMORY_INVALID_SOURCE: Final[Counter] = Counter(
    "ai_worker_memory_invalid_source_total",
    "sourceRef 不在快照 facts/existingMemories 内、被丢弃的候选数。",
    registry=REGISTRY,
)

#: dedupeKey 与重算结果不一致、被丢弃的候选数（防模型编造幂等键）。
MEMORY_INVALID_DEDUPE: Final[Counter] = Counter(
    "ai_worker_memory_invalid_dedupe_total",
    "模型给出但与本侧重算不一致的 dedupeKey 候选数（丢弃，不放行）。",
    registry=REGISTRY,
)

#: 契约 §3：用量上报失败的条数（含重试耗尽丢弃）。标签固定 purpose / caller_type。
AI_USAGE_REPORT_FAILURES: Final[Counter] = Counter(
    "ai_usage_report_failures_total",
    "用量上报失败条数（每次失败的投递尝试按条计；上报失败不影响业务结果）。",
    ("purpose", "caller_type"),
    registry=REGISTRY,
)

#: 模型调用次数，按用量状态分类（契约 §3 的 status 枚举）。
AI_MODEL_CALLS: Final[Counter] = Counter(
    "ai_model_calls_total",
    "本进程发起的模型调用次数，按用量状态分类。",
    ("purpose", "caller_type", "status"),
    registry=REGISTRY,
)

#: Stream 任务处理结果（契约 §4.5）；outcome 低基数。
AI_TASKS: Final[Counter] = Counter(
    "ai_worker_tasks_total",
    "从 Redis Stream 消费的任务数，按结果分类（succeeded/failed/canceled/claim_conflict/...）。",
    ("task_type", "outcome"),
    registry=REGISTRY,
)

#: P2 §10 索引任务端到端耗时（秒）。
AI_INDEX_TASK_DURATION: Final[Histogram] = Histogram(
    "ai_index_task_duration_seconds",
    "P2 索引任务执行耗时（秒），按 taskType 与 caller_type 分类。",
    ("taskType", "caller_type"),
    registry=REGISTRY,
)

#: P2 §10 检索端到端耗时（秒）。
KNOWLEDGE_SEARCH_LATENCY: Final[Histogram] = Histogram(
    "knowledge_search_latency_seconds",
    "P2 知识检索耗时（秒），按 intent、strategy 与 caller_type 分类。",
    ("intent", "strategy", "caller_type"),
    registry=REGISTRY,
)

#: P2 §10 检索命中条数分布。
KNOWLEDGE_SEARCH_HITS: Final[Histogram] = Histogram(
    "knowledge_search_hits",
    "P2 知识检索返回命中数，按 intent 与 caller_type 分类。",
    ("intent", "caller_type"),
    buckets=(0, 1, 2, 4, 8, 12, 16, 20, 24),
    registry=REGISTRY,
)

#: P2 §10 检索失败总数，按 HTTP status 与 caller_type 分类。
KNOWLEDGE_SEARCH_ERRORS: Final[Counter] = Counter(
    "knowledge_search_errors_total",
    "P2 知识检索错误总数（不含 200 空结果）。",
    ("status", "caller_type"),
    registry=REGISTRY,
)

#: P3 §3.3 问答流式端到端耗时（秒），按 outcome 与 caller_type 分类。
KNOWLEDGE_QA_DURATION: Final[Histogram] = Histogram(
    "knowledge_qa_duration_seconds",
    "P3 知识问答流式端到端耗时（秒），按 outcome 与 caller_type 分类。",
    ("outcome", "caller_type"),
    registry=REGISTRY,
)

#: P2 §10 索引代次行数，按 generation 状态与 caller_type 分类。
AI_INDEX_GENERATION_ROWS: Final[Gauge] = Gauge(
    "ai_index_generation_rows",
    "P2 各状态 generation 的当前索引总行数（由 admin 读或状态变更更新）。",
    ("status", "caller_type"),
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


def set_gauge(metric: Gauge, value: float, **labels: str) -> None:
    """设置 Gauge 值；任何异常都被吞掉（指标不得改变业务结果）。"""

    try:
        target = metric.labels(**labels) if labels else metric
        target.set(value)
    except Exception:
        logger.debug("prometheus gauge set failed", exc_info=True)


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
    "AI_INDEX_GENERATION_ROWS",
    "AI_INDEX_TASK_DURATION",
    "AI_MODEL_CALLS",
    "AI_TASKS",
    "AI_USAGE_REPORT_FAILURES",
    "HTTP_DURATION",
    "HTTP_REQUESTS",
    "INFO",
    "KNOWLEDGE_SEARCH_ERRORS",
    "KNOWLEDGE_SEARCH_HITS",
    "KNOWLEDGE_SEARCH_LATENCY",
    "MEMORY_CANDIDATES",
    "MEMORY_INVALID_DEDUPE",
    "MEMORY_INVALID_SOURCE",
    "MEMORY_JOB_DURATION",
    "MEMORY_JOB_REQUESTS",
    "QUERY_PLAN_CONFIDENCE",
    "QUERY_PLAN_DURATION",
    "QUERY_PLAN_UNKNOWN_DOCUMENT_IDS",
    "REGISTRY",
    "UNMATCHED_PATH",
    "increment_counter",
    "instrument_app",
    "observe_histogram",
    "render_metrics",
    "set_gauge",
]
