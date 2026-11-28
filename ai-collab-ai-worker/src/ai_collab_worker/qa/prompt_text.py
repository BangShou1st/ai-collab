"""``KnowledgePromptText.escapeXmlText`` 的逐字节移植。

规则（与 Java 完全一致）：

- 只转义 ``&`` ``<`` ``>`` 三个字符（引号**不**转义——多轮消歧上下文里的
  ``QueryUnderstandingService.escapeXml`` 才转义引号，那是另一个实现）；
- 限长按 **code point** 计，实体（如 ``&amp;`` 计 5 码点）不可分割：装不下就
  整体停住，绝不输出半个实体；
- 返回 ``(original, escaped)`` 二元组——``original`` 是实际写入的原文前缀，
  ``escaped`` 是对应的转义文本。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Final

_ESCAPE_MAP: Final[dict[int, str]] = {0x26: "&amp;", 0x3C: "&lt;", 0x3E: "&gt;"}


@dataclass(frozen=True, slots=True)
class EscapedText:
    """Java ``KnowledgePromptText.EscapedText`` record。"""

    original: str
    escaped: str


def escape_xml_text(value: str | None, maximum_escaped_code_points: int = 2**31 - 1) -> str:
    """无限额转义（Java 无参重载）。"""

    return escape_xml_text_bounded(value, maximum_escaped_code_points).escaped


def escape_xml_text_bounded(value: str | None, maximum_escaped_code_points: int) -> EscapedText:
    if value is None or maximum_escaped_code_points <= 0:
        return EscapedText("", "")

    original: list[str] = []
    escaped: list[str] = []
    used = 0
    for char in value:
        code_point = ord(char)
        token = _ESCAPE_MAP.get(code_point, char)
        token_length = len(token)
        if used > maximum_escaped_code_points - token_length:
            break
        original.append(char)
        escaped.append(token)
        used += token_length
    return EscapedText("".join(original), "".join(escaped))


__all__ = ["EscapedText", "escape_xml_text", "escape_xml_text_bounded"]
