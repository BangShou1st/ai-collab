"""R2-5 金标准比对规则（纯函数，不依赖数据库 / Ollama）。

规则（docs/design/p2-rag-contract-2026-11-27.md R2-5）：

1. 按给定顺序扫描候选，**相邻**两条的排序分量逐项绝对差都 ``<= 1e-9`` 时归入同一平局组
   （链式：a~b、b~c 则 a、b、c 同组）；
2. 组内按 ``(documentId, chunkNo)`` 比较**集合**，不比顺序（Java 平局末级按 chunkId，
   Python 按 ``(documentId, chunkNo)``，组内顺序允许不同）；
3. 组间顺序必须一致；
4. 同一身份两侧的排序分量逐项绝对差也必须 ``<= 1e-9``。

容差固定为 ``1e-9``，不提供放宽入口。
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Final

EPSILON: Final[float] = 1e-9

Key = tuple[str, int]


class GoldenFieldError(ValueError):
    """夹具行缺字段或字段类型不对。"""


@dataclass(frozen=True)
class RankedItem:
    document_id: str
    chunk_no: int
    scores: tuple[float, ...]

    @property
    def key(self) -> Key:
        return (self.document_id, self.chunk_no)


@dataclass(frozen=True)
class CompareResult:
    equal: bool
    #: ``None`` / ``"membership"`` / ``"group_order"`` / ``"score"``
    kind: str | None
    reason: str | None
    expected_groups: int
    actual_groups: int
    max_score_diff: float | None
    first_mismatch_group: int | None = None


def require_field(row: Mapping[str, object], name: str) -> object:
    """取必需字段；缺失或为 ``None`` 时抛 :class:`GoldenFieldError`。"""

    if not isinstance(row, Mapping):
        msg = f"夹具行不是对象：{type(row).__name__}"
        raise GoldenFieldError(msg)
    if name not in row or row[name] is None:
        msg = f"夹具行缺少字段 {name!r}（现有字段：{sorted(row)}）"
        raise GoldenFieldError(msg)
    return row[name]


def require_score(row: Mapping[str, object], name: str) -> float:
    """取 ``row["scores"][name]`` 并转成 float；缺失时抛 :class:`GoldenFieldError`。"""

    scores = require_field(row, "scores")
    if not isinstance(scores, Mapping):
        msg = "字段 'scores' 不是对象"
        raise GoldenFieldError(msg)
    value = require_field(scores, name)
    if isinstance(value, bool) or not isinstance(value, int | float):
        msg = f"分数 {name!r} 不是数值：{value!r}"
        raise GoldenFieldError(msg)
    result = float(value)
    if math.isnan(result):
        msg = f"分数 {name!r} 为 NaN"
        raise GoldenFieldError(msg)
    return result


def item_from_row(row: Mapping[str, object], scores: Sequence[float]) -> RankedItem:
    """从夹具行取身份 ``(documentId, chunkNo)``，排序分量由调用方给出。"""

    document_id = require_field(row, "documentId")
    chunk_no = require_field(row, "chunkNo")
    if not isinstance(document_id, str) or not document_id.strip():
        msg = f"documentId 非法：{document_id!r}"
        raise GoldenFieldError(msg)
    if isinstance(chunk_no, bool) or not isinstance(chunk_no, int):
        msg = f"chunkNo 不是整数：{chunk_no!r}"
        raise GoldenFieldError(msg)
    if not scores:
        msg = "排序分量不能为空"
        raise GoldenFieldError(msg)
    return RankedItem(document_id.strip().lower(), chunk_no, tuple(float(s) for s in scores))


def _same_score(left: tuple[float, ...], right: tuple[float, ...]) -> bool:
    if len(left) != len(right):
        msg = f"排序分量维度不一致：{len(left)} vs {len(right)}"
        raise GoldenFieldError(msg)
    return all(abs(a - b) <= EPSILON for a, b in zip(left, right, strict=True))


def tie_groups(items: Sequence[RankedItem]) -> list[frozenset[Key]]:
    """按相邻分差 ``<= 1e-9`` 划分平局组；列表内出现重复身份时报错。"""

    seen: set[Key] = set()
    groups: list[frozenset[Key]] = []
    current: list[Key] = []
    previous: RankedItem | None = None
    for item in items:
        if item.key in seen:
            msg = f"同一列表内身份重复：{item.key}"
            raise GoldenFieldError(msg)
        seen.add(item.key)
        if previous is not None and _same_score(previous.scores, item.scores):
            current.append(item.key)
        else:
            if current:
                groups.append(frozenset(current))
            current = [item.key]
        previous = item
    if current:
        groups.append(frozenset(current))
    return groups


def max_score_diff(expected: Sequence[RankedItem], actual: Sequence[RankedItem]) -> float | None:
    """两侧共有身份上排序分量的最大逐项绝对差；无共有身份时返回 ``None``。"""

    by_key = {item.key: item.scores for item in actual}
    worst: float | None = None
    for item in expected:
        other = by_key.get(item.key)
        if other is None:
            continue
        if len(other) != len(item.scores):
            msg = f"排序分量维度不一致：{len(item.scores)} vs {len(other)}"
            raise GoldenFieldError(msg)
        diff = max(abs(a - b) for a, b in zip(item.scores, other, strict=True))
        worst = diff if worst is None else max(worst, diff)
    return worst


def compare_ranked(expected: Sequence[RankedItem], actual: Sequence[RankedItem]) -> CompareResult:
    """按 R2-5 规则比较 Java（expected）与 Python（actual）的排序结果。"""

    expected_groups = tie_groups(expected)
    actual_groups = tie_groups(actual)
    worst = max_score_diff(expected, actual)
    counts = (len(expected_groups), len(actual_groups))

    expected_keys = {item.key for item in expected}
    actual_keys = {item.key for item in actual}
    if expected_keys != actual_keys:
        missing = sorted(expected_keys - actual_keys)
        extra = sorted(actual_keys - expected_keys)
        return CompareResult(
            False,
            "membership",
            f"候选集合不同：Python 缺 {missing[:5]}，多出 {extra[:5]}",
            *counts,
            worst,
            _first_diff(expected_groups, actual_groups),
        )

    first = _first_diff(expected_groups, actual_groups)
    if first is not None:
        exp = sorted(expected_groups[first]) if first < len(expected_groups) else []
        act = sorted(actual_groups[first]) if first < len(actual_groups) else []
        return CompareResult(
            False,
            "group_order",
            f"第 {first} 个平局组不同：Java {exp[:5]} vs Python {act[:5]}",
            *counts,
            worst,
            first,
        )

    if worst is not None and worst > EPSILON:
        return CompareResult(
            False, "score", f"同一身份分数最大差 {worst!r} > {EPSILON}", *counts, worst
        )
    return CompareResult(True, None, None, *counts, worst)


def _first_diff(left: Sequence[frozenset[Key]], right: Sequence[frozenset[Key]]) -> int | None:
    for index in range(max(len(left), len(right))):
        if index >= len(left) or index >= len(right) or left[index] != right[index]:
            return index
    return None


__all__ = [
    "EPSILON",
    "CompareResult",
    "GoldenFieldError",
    "RankedItem",
    "compare_ranked",
    "item_from_row",
    "max_score_diff",
    "require_field",
    "require_score",
    "tie_groups",
]
