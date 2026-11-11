"""structlog 结构化日志：单行 JSON 输出到 stdout。

设计依据：设计 §6.2（structlog）与 §11（成本观测）。约束：

- 只输出 JSON，便于日志采集器直接解析，不再依赖人类可读的彩色输出；
- 不注册任何 handler（``PrintLoggerFactory`` 直接写文件对象），因此 ``configure_logging``
  重复调用不会叠加 handler、也不会出现重复行；
- 不接管 stdlib ``logging`` 的 root handler：uvicorn 的 access/error logger 由 uvicorn
  自己配置，pytest 的 caplog 也不会被我们破坏。
"""

from __future__ import annotations

import logging
import sys
from typing import Final

import structlog

#: 合法级别名 → 数值（含 WARN/FATAL 等别名）。
_LOG_LEVELS: Final[dict[str, int]] = dict(logging.getLevelNamesMapping())


def configure_logging(level: str = "INFO") -> None:
    """把 structlog 配置成 JSON→stdout，并设置最低输出级别。

    幂等：``structlog.configure`` 是整份配置替换而不是追加，本实现也不注册 handler，
    因此重复调用不会让同一条日志输出两次。级别名非法时抛 ``ValueError``。
    """

    normalized = level.upper()
    if normalized not in _LOG_LEVELS:
        msg = f"非法日志级别 {level!r}；可选值：{', '.join(sorted(_LOG_LEVELS))}"
        raise ValueError(msg)

    structlog.configure(
        processors=[
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(sort_keys=True),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(_LOG_LEVELS[normalized]),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        # 不用"首次使用即缓存"：测试与重配后要立刻按新配置输出。
        cache_logger_on_first_use=False,
    )


__all__ = ["configure_logging"]
