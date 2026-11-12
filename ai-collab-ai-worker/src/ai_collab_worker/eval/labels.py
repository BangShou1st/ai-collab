"""评测报告的证据标签校验（D10）。

冻结评测核心默认把 ``evidence`` / ``qualityClaim`` 写死为 stub 口径，真实模型运行
（staging、LIVE_VERIFIED）若沿用默认值就会产出标签错误的报告。本模块只做一件事：
在报告生成时允许调用方**显式**覆盖标签，同时拒绝空标签，避免出现"看起来有标签、
实际没有语义"的报告。

默认值仍由各评测模块自己给出（``None`` 表示不覆盖）；本模块不改变任何旧调用行为。
"""

from __future__ import annotations

from typing import Final

#: 质量声明的固定默认值：没有真实模型证据时不得宣称质量达标。
QUALITY_CLAIM_NOT_VERIFIED: Final[str] = "NOT_VERIFIED"


def resolve_label(value: str | None, *, default: str, field: str) -> str:
    """返回显式标签或默认值；显式标签去首尾空白后不得为空。

    Args:
        value: 调用方传入的标签；``None`` 表示沿用默认值。
        default: 默认标签（各评测模块的冻结核常量）。
        field: 错误信息里的字段名（如 ``evidence``）。

    Raises:
        ValueError: 显式标签不是字符串，或去空白后为空。
    """

    if value is None:
        return default
    if not isinstance(value, str):
        raise ValueError(f"{field} 必须是字符串")
    cleaned = value.strip()
    if not cleaned:
        raise ValueError(f"{field} 不能为空")
    return cleaned


def resolve_optional_text(value: str | None, *, field: str) -> str | None:
    """返回可选的元数据文本（如 ``subject`` / ``model``）；空串视为错误而不是 None。"""

    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{field} 必须是字符串")
    cleaned = value.strip()
    if not cleaned:
        raise ValueError(f"{field} 不能为空")
    return cleaned


__all__ = [
    "QUALITY_CLAIM_NOT_VERIFIED",
    "resolve_label",
    "resolve_optional_text",
]
