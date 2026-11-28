"""``TimeContext.beijingTimeContext`` 的等价移植（P3 契约 §2.1 行 7，Q12=a）。

时间行格式逐字节钉在 ``p3_safety_golden.v1.jsonl`` 的 time_row 样本：
``当前北京时间：YYYY-MM-DD HH:mm（UTC+8），日期计算必须基于此时区。\\n``
"""

from __future__ import annotations

from datetime import UTC, datetime
from zoneinfo import ZoneInfo

_BEIJING = ZoneInfo("Asia/Shanghai")

#: 格式与 Java ``DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")`` 一致。
_FORMAT = "%Y-%m-%d %H:%M"


def beijing_time_context(now: datetime | None = None) -> str:
    """一行时间上下文（注入 ``knowledge.system`` 的 ``{{ timeContext }}``）。"""

    moment = now if now is not None else datetime.now(UTC)
    local = moment.astimezone(_BEIJING)
    return f"当前北京时间：{local.strftime(_FORMAT)}（UTC+8），日期计算必须基于此时区。\n"


__all__ = ["beijing_time_context"]
