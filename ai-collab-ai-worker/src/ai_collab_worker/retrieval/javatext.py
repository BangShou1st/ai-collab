"""Java ``String`` / ``Character`` 语义的最小复刻，供逐行移植的检索算法使用。

只实现移植代码真正用到的几个点，避免 Python 内置语义的细微差别（``str.strip`` 去掉的字符集、
``isspace`` 的范围、UTF-16 长度）让排序与 Java 不一致。
"""

from __future__ import annotations

import unicodedata
from typing import Final

#: ``String.trim()`` 去掉的字符：码点 ≤ U+0020。
_TRIM_CHARS: Final[str] = "".join(chr(code) for code in range(0x21))

#: ``Character.isWhitespace`` 额外承认的控制字符。
_JAVA_WHITESPACE_CONTROLS: Final[frozenset[str]] = frozenset(
    "\t\n\u000b\f\r\u001c\u001d\u001e\u001f"
)
#: ``Character.isWhitespace`` 排除的不换行空格。
_NON_BREAKING: Final[frozenset[str]] = frozenset("   ")

#: 正则 ``\s``（未开 ``UNICODE_CHARACTER_CLASS``）只匹配这 6 个字符。
_REGEX_SPACE: Final[frozenset[str]] = frozenset(" \t\n\u000b\f\r")


def trim(value: str) -> str:
    """``String.trim()``。"""

    return value.strip(_TRIM_CHARS)


def strip_ws(value: str) -> str:
    """``String.strip()``（Java 11+，按 ``Character.isWhitespace`` 去两端）。"""

    start, end = 0, len(value)
    while start < end and is_whitespace(value[start]):
        start += 1
    while end > start and is_whitespace(value[end - 1]):
        end -= 1
    return value[start:end]


def is_whitespace(char: str) -> bool:
    """``Character.isWhitespace``。"""

    if char in _JAVA_WHITESPACE_CONTROLS:
        return True
    if char in _NON_BREAKING:
        return False
    return unicodedata.category(char) in {"Zs", "Zl", "Zp"}


def is_blank(value: str | None) -> bool:
    """``value == null || value.isBlank()``。"""

    return value is None or all(is_whitespace(char) for char in value)


def lower(value: str) -> str:
    """``toLowerCase(Locale.ROOT)``（Python ``str.lower`` 同样实现 Final_Sigma）。"""

    return value.lower()


def utf16_length(value: str) -> int:
    """``String.length()``：UTF-16 代码单元数。"""

    return sum(2 if ord(char) > 0xFFFF else 1 for char in value)


def is_punct_symbol_or_space(char: str) -> bool:
    """``[\\p{P}\\p{S}\\s]``：Unicode 标点、符号，或 6 个 ASCII 空白。"""

    if char in _REGEX_SPACE:
        return True
    return unicodedata.category(char)[0] in {"P", "S"}


def split_on_punct_symbol_space(value: str) -> list[str]:
    """``Pattern.compile("[\\p{P}\\p{S}\\s]+").split(value)``。

    Java ``split`` 会去掉末尾空串、保留开头的空串；调用方对空串一律跳过，所以这里只需
    保证非空片段与顺序一致。
    """

    tokens: list[str] = []
    current: list[str] = []
    for char in value:
        if is_punct_symbol_or_space(char):
            if current:
                tokens.append("".join(current))
                current = []
            elif not tokens:
                tokens.append("")
        else:
            current.append(char)
    if current:
        tokens.append("".join(current))
    while tokens and tokens[-1] == "":
        tokens.pop()
    return tokens


def contains_cjk(value: str) -> bool:
    """``c >= '\\u4e00' && c <= '\\u9fa5'``。"""

    return any("一" <= char <= "龥" for char in value)


def keep_cjk(value: str) -> str:
    """``replaceAll("[^\\u4e00-\\u9fa5]", "")``。"""

    return "".join(char for char in value if "一" <= char <= "龥")


__all__ = [
    "contains_cjk",
    "is_blank",
    "is_punct_symbol_or_space",
    "is_whitespace",
    "keep_cjk",
    "lower",
    "split_on_punct_symbol_space",
    "strip_ws",
    "trim",
    "utf16_length",
]
