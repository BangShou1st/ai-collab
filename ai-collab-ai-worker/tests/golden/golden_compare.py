"""R2-5 金标准比对规则（纯函数，不依赖数据库 / Ollama）。

规则（docs/design/p2-rag-contract-2026-11-27.md R2-5）：

1. 按给定顺序扫描候选，**相邻**两条的排序分量逐项绝对差都 ``<= 1e-9`` 时归入同一平局组
   （链式：a~b、b~c 则 a、b、c 同组）；
2. 组内按 ``(documentId, chunkNo)`` 比较**集合**，不比顺序（Java 平局末级按 chunkId，
   Python 按 ``(documentId, chunkNo)``，组内顺序允许不同）；
3. 组间顺序必须一致；
4. 同一身份两侧的排序分量逐项绝对差也必须 ``<= 1e-9``。

补充规则 R2-5a「截断边界平局组」（:func:`compare_ranked_truncated`）：

若 Python **不截断**的完整结果里，第 ``top_k`` 位（截断后最后一条）所在平局组延伸到
``top_k`` 之后，该组为「边界组」，起点记为 ``s``。此时：

- Java 前 ``s`` 条与 Python 前 ``s`` 条按规则 1–4 严格比对；
- Java 第 ``s`` 条起的条数必须恰为 ``top_k - s``，每条都必须是边界组成员、身份不重复，
  且与 Python 同一身份的排序分量逐项绝对差 ``<= 1e-9``。

理由：边界组成员的排序分量完全相同，截断时保留其中哪几条在两侧都是实现定义的——
Java 最后一级按 chunkId 排，Python 按 ``(documentId, chunkNo)`` 排，于是 ``top_k`` 从组中间
切开时两侧可能各留不同成员（CI run 45991923308：H-G02-CROSS-D / QA / topK=20，两条
``rrf = 1/74``、命中路数 1、最好名次 14 的候选）。该补充规则只作用于边界组；其余组照旧严格，
容差仍固定为 ``1e-9``，不提供放宽入口。边界组不跨 ``top_k`` 时，结果与对截断后列表调用
:func:`compare_ranked` 完全相同。
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from typing import Final

EPSILON: Final[float] = 1e-9

BOUNDARY_TIE_RULE: Final[str] = (
    "R2-5a 截断边界平局组：若 Python 不截断的完整结果中第 topK 位所在平局组（同 1e-9 链式划分）"
    "延伸到 topK 之后，记组起点为 s；Java 前 s 条与 Python 前 s 条按 R2-5 严格比对，"
    "Java 第 s 条起的条数必须恰为 topK - s，且每条都是该边界组成员、身份不重复、与 Python "
    "同一身份排序分量逐项差 <= 1e-9。理由：边界组成员排序分量完全相同，保留哪条在两侧都是"
    "实现定义的（Java 最后一级按 chunkId，Python 按 (documentId, chunkNo)）。其他组照旧严格，"
    "1e-9 不放宽。"
)

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
    #: ``None`` / ``"membership"`` / ``"group_order"`` / ``"score"`` / ``"boundary"``
    kind: str | None
    reason: str | None
    expected_groups: int
    actual_groups: int
    max_score_diff: float | None
    first_mismatch_group: int | None = None
    #: 仅当严格比对不一致、而按 R2-5a 边界组规则通过时为 ``True``。
    boundary_tie_resolved: bool = False
    #: Python 完整结果中跨 ``top_k`` 的边界组大小 / 起点；不跨截断时为 ``None``。
    boundary_group_size: int | None = None
    boundary_group_start: int | None = None


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


def _score_diff(left: tuple[float, ...], right: tuple[float, ...]) -> float:
    if len(left) != len(right):
        msg = f"排序分量维度不一致：{len(left)} vs {len(right)}"
        raise GoldenFieldError(msg)
    return max(abs(a - b) for a, b in zip(left, right, strict=True))


def tie_spans(items: Sequence[RankedItem]) -> list[tuple[int, int]]:
    """平局组的位置区间 ``[start, end)``；划分口径同 :func:`tie_groups`。"""

    seen: set[Key] = set()
    spans: list[tuple[int, int]] = []
    start = 0
    for index, item in enumerate(items):
        if item.key in seen:
            msg = f"同一列表内身份重复：{item.key}"
            raise GoldenFieldError(msg)
        seen.add(item.key)
        if index > 0 and not _same_score(items[index - 1].scores, item.scores):
            spans.append((start, index))
            start = index
    if items:
        spans.append((start, len(items)))
    return spans


def tie_groups(items: Sequence[RankedItem]) -> list[frozenset[Key]]:
    """按相邻分差 ``<= 1e-9`` 划分平局组；列表内出现重复身份时报错。"""

    return [frozenset(items[i].key for i in range(s, e)) for s, e in tie_spans(items)]


def max_score_diff(expected: Sequence[RankedItem], actual: Sequence[RankedItem]) -> float | None:
    """两侧共有身份上排序分量的最大逐项绝对差；无共有身份时返回 ``None``。"""

    by_key = {item.key: item.scores for item in actual}
    worst: float | None = None
    for item in expected:
        other = by_key.get(item.key)
        if other is None:
            continue
        diff = _score_diff(item.scores, other)
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


def boundary_span(items: Sequence[RankedItem], top_k: int) -> tuple[int, int] | None:
    """完整结果中跨 ``top_k`` 截断点的平局组区间 ``[start, end)``；不跨时返回 ``None``。"""

    if top_k <= 0 or len(items) <= top_k:
        return None
    for start, end in tie_spans(items):
        if start <= top_k - 1 < end:
            return (start, end) if end > top_k else None
    return None


def compare_ranked_truncated(
    java_items: Sequence[RankedItem],
    python_full_items: Sequence[RankedItem],
    top_k: int,
) -> CompareResult:
    """R2-5 + R2-5a：Java 截断输出 vs Python **不截断**的完整有序结果。

    先对 ``python_full_items[:top_k]`` 做严格比对（与 :func:`compare_ranked` 相同）；
    严格一致即返回。严格不一致且存在跨截断的边界组时，改按 R2-5a 判定：前 ``s`` 条严格，
    其后每条须是边界组成员。只有靠 R2-5a 通过时 ``boundary_tie_resolved`` 才为 ``True``。
    """

    truncated = list(python_full_items[:top_k]) if top_k > 0 else list(python_full_items)
    strict = compare_ranked(java_items, truncated)
    span = boundary_span(python_full_items, top_k)
    if span is None:
        return strict
    start, end = span
    size = end - start
    marks = {"boundary_group_size": size, "boundary_group_start": start}
    if strict.equal:
        return replace(strict, **marks)

    counts = (strict.expected_groups, strict.actual_groups)
    prefix = compare_ranked(java_items[:start], python_full_items[:start])
    if not prefix.equal:
        return replace(prefix, expected_groups=counts[0], actual_groups=counts[1], **marks)

    worst = max_score_diff(java_items, python_full_items)
    where = f"截断边界平局组（Python 第 {start}–{end - 1} 位，共 {size} 条）"
    expected_tail = top_k - start
    tail = java_items[start:]
    if len(java_items) != top_k:
        reason = f"{where}：Java 应保留 {expected_tail} 条，实际 {len(tail)} 条"
        return CompareResult(False, "boundary", reason, *counts, worst, None, **marks)

    members = {item.key: item for item in python_full_items[start:end]}
    outside = [item.key for item in tail if item.key not in members]
    if outside:
        reason = f"{where}：Java 边界成员 {outside[:5]} 不在 Python 边界组内"
        return CompareResult(False, "boundary", reason, *counts, worst, None, **marks)
    tail_keys = [item.key for item in tail]
    if len(set(tail_keys)) != len(tail_keys):
        reason = f"{where}：Java 边界成员身份重复 {tail_keys}"
        return CompareResult(False, "boundary", reason, *counts, worst, None, **marks)

    tail_worst = max(_score_diff(item.scores, members[item.key].scores) for item in tail)
    if tail_worst > EPSILON:
        reason = f"{where}：同一身份分数最大差 {tail_worst!r} > {EPSILON}"
        return CompareResult(False, "score", reason, *counts, worst, None, **marks)
    return CompareResult(True, None, None, *counts, worst, None, True, size, start)


def _first_diff(left: Sequence[frozenset[Key]], right: Sequence[frozenset[Key]]) -> int | None:
    for index in range(max(len(left), len(right))):
        if index >= len(left) or index >= len(right) or left[index] != right[index]:
            return index
    return None


__all__ = [
    "BOUNDARY_TIE_RULE",
    "EPSILON",
    "CompareResult",
    "GoldenFieldError",
    "RankedItem",
    "boundary_span",
    "compare_ranked",
    "compare_ranked_truncated",
    "item_from_row",
    "max_score_diff",
    "require_field",
    "require_score",
    "tie_groups",
    "tie_spans",
]
