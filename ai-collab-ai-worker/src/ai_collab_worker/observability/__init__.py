"""可观测性：Prometheus 指标（``metrics``）与 structlog JSON 日志（``logging``）。

两者都刻意不碰全局单例：指标用本包自己的 :class:`prometheus_client.CollectorRegistry`，
日志用不注册 handler 的 ``PrintLoggerFactory``，因此重复装配与多 app 并存都不会冲突。
"""

from __future__ import annotations

from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.observability.metrics import REGISTRY, instrument_app, render_metrics

__all__ = [
    "REGISTRY",
    "configure_logging",
    "instrument_app",
    "render_metrics",
]
