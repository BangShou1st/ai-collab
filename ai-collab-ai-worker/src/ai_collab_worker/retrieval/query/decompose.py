"""``MultiQueryDecomposer`` 的逐行移植（P3 契约 §2.1 行 3）。

长度口径：所有 ``String.length()`` 比较（``>= 2`` / ``<= 30`` / ``<= 10`` / ``< 3``）
按 UTF-16 代码单元数计（:func:`javatext.utf16_length`）；``strip()`` 用 Java 语义。
"""

from __future__ import annotations

import re
from typing import Final

from ai_collab_worker.retrieval.javatext import is_blank, strip_ws, utf16_length
from ai_collab_worker.retrieval.query.models import StructuredQuery

_S: Final[str] = r"[ \t\n\x0b\f\r]"

MAX_SUB_QUERIES: Final[int] = 4
MIN_SUB_QUERIES: Final[int] = 2

QUESTION_SPLIT_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"[?？;；\n]+|(?:^|" + _S + r"+)(?:[1-4][\.、]|(?:第[一二三四][\.、]?))" + _S + r"*"
)

COMPARISON_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"^(.+?)"
    + _S
    + r"*(?:和|与|跟|及|以及|vs|versus)"
    + _S
    + r"*(.+?)"
    + _S
    + r"*(?:的)?"
    + _S
    + r"*(?:区别|不同点|异同|对比|优缺点|优劣|相比)(?:是什么|有哪些|分析)?"
    + r"\??\Z",
    re.IGNORECASE,
)

CONJUNCTION_SPLIT_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"[,，]?" + _S + r"*(?:并且|而且|同时|以及|另外)" + _S + r"*"
)

_LEADING_PUNCT: Final[re.Pattern[str]] = re.compile(r"^[，,。；;：:" + _S + r"]+")
_TRAILING_PUNCT: Final[re.Pattern[str]] = re.compile(r"[，,。；;：:" + _S + r"]+$")

#: 比较句的三路子查询模板（逐字一致）。
_COMPARATIVE_TEMPLATES: Final[tuple[str, str, str]] = (
    "{a} 的定义与特性",
    "{b} 的定义与特性",
    "{a} 与 {b} 的区别与对比",
)


def decompose(complex_query: str | None) -> list[str]:
    """把复杂查询分解为 2~4 条子查询；不足两条或异常时回退原查询单条。"""

    if complex_query is None or is_blank(complex_query):
        return []
    clean_query = strip_ws(complex_query)
    try:
        raw_sub_queries = _do_decompose(clean_query)
        valid: list[str] = []
        for sub in raw_sub_queries:
            normalized = _TRAILING_PUNCT.sub("", _LEADING_PUNCT.sub("", sub)).strip()
            if utf16_length(normalized) >= 2 and normalized not in valid:
                valid.append(normalized)
        if len(valid) < MIN_SUB_QUERIES:
            return [clean_query]
        if len(valid) > MAX_SUB_QUERIES:
            valid = valid[:MAX_SUB_QUERIES]
        return list(valid)
    except Exception:
        return [clean_query]


def decompose_structured(structured_query: StructuredQuery | None) -> list[StructuredQuery]:
    """分解 :class:`StructuredQuery`：每条子查询继承父级全部过滤。"""

    if structured_query is None:
        return []
    sub_query_texts = decompose(structured_query.semantic_query)
    if not sub_query_texts:
        return [structured_query]
    return [StructuredQuery(text, structured_query.filters) for text in sub_query_texts]


def _do_decompose(query: str) -> list[str]:
    # 策略 1：比较句（实体 A vs 实体 B）
    match = COMPARISON_PATTERN.match(query)
    if match:
        entity_a = match.group(1).strip()
        entity_b = match.group(2).strip()
        if entity_a and entity_b and utf16_length(entity_a) <= 30 and utf16_length(entity_b) <= 30:
            return [template.format(a=entity_a, b=entity_b) for template in _COMPARATIVE_TEMPLATES]

    # 策略 2：按多问句 / 编号切分
    candidates = [part.strip() for part in QUESTION_SPLIT_PATTERN.split(query) if part.strip()]
    if len(candidates) >= 2:
        return _propagate_subject_if_elliptical(candidates)

    # 策略 3：按并列连词切分
    conj_parts = CONJUNCTION_SPLIT_PATTERN.split(query)
    if len(conj_parts) >= 2:
        conj_candidates = [part.strip() for part in conj_parts if part.strip()]
        if len(conj_candidates) >= 2:
            return _propagate_subject_if_elliptical(conj_candidates)

    return [query]


def _propagate_subject_if_elliptical(clauses: list[str]) -> list[str]:
    if not clauses:
        return clauses
    enriched = [clauses[0]]
    topic = _extract_leading_topic(clauses[0])
    for current in clauses[1:]:
        if _is_elliptical_clause(current) and topic:
            enriched.append(topic + current)
        else:
            enriched.append(current)
    return enriched


def _is_elliptical_clause(text: str) -> bool:
    if utf16_length(text) <= 10:
        return True
    return text.startswith(
        ("谁是", "责任人", "有哪些", "如何", "能否", "怎么处理", "有什么", "具体")
    )


def _extract_leading_topic(clause: str) -> str | None:
    if clause is None or utf16_length(clause) < 3:
        return None
    index = -1
    for marker in ("前", "后", "中", "时", "有哪些", "怎么", "如何", "是什么"):
        position = clause.find(marker)
        if position > 1 and (index == -1 or position < index):
            index = position
    if index > 2 and index <= 15:
        return clause[:index].strip()
    return None


__all__ = ["decompose", "decompose_structured"]
