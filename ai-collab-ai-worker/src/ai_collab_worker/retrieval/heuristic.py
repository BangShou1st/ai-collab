"""语义启发式重排与透传重排（逐行对照 Java ``SemanticHeuristicReranker``、
``PassThroughReranker``）。

与 Java 的差别只有两处，都是契约要求：

1. 平局最后一级按 ``(documentId, chunkNo)`` 升序（P2-R2 R2-5）；
2. Java 的 ``catch (Throwable) → degrade`` 删除（P2-R2 R2-1）：计算出错直接抛出，由调用方
   返回 503 ``RERANK_UNAVAILABLE``。查询为空白、或抽不出词项时的 ``degrade`` 是正常分支，
   不是出错回退，原样保留。
"""

from __future__ import annotations

from collections.abc import Sequence
from functools import cmp_to_key
from typing import Final

from ai_collab_worker.retrieval.candidate import (
    RERANKER_DEGRADED,
    RERANKER_HEURISTIC,
    RERANKER_PASS_THROUGH,
    Candidate,
    compare_keys,
    java_double_compare,
    truncate,
)
from ai_collab_worker.retrieval.javatext import (
    contains_cjk,
    is_blank,
    keep_cjk,
    lower,
    split_on_punct_symbol_space,
    trim,
    utf16_length,
)

EPSILON: Final[float] = 1e-9

#: 权重：0.35 * RRF + 0.40 * 内容核心词覆盖 + 0.25 * 标题匹配。
WEIGHT_RRF: Final[float] = 0.35
WEIGHT_CONTENT: Final[float] = 0.40
WEIGHT_HEADING: Final[float] = 0.25

STOP_WORDS: Final[frozenset[str]] = frozenset(
    {
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
        "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
        "自己", "这", "如何", "怎么", "为什么", "什么", "哪些", "及", "与", "或",
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of",
        "with", "is", "are", "was", "were", "it", "this", "that", "how", "what", "why",
    }
)  # fmt: skip

MAX_TERMS: Final[int] = 64
MAX_CHINESE_BIGRAMS: Final[int] = 48
CJK_TOKEN_MAX_LENGTH: Final[int] = 10
CJK_FUNCTION_CHARS: Final[frozenset[str]] = frozenset("的了在是和与或及对为等把被向从到要会有都")

MAX_OCCURRENCES: Final[int] = 10
FREQUENCY_STEP: Final[float] = 0.02
FREQUENCY_CAP: Final[float] = 0.15


def heuristic_rerank(query: str, candidates: Sequence[Candidate], top_n: int) -> list[Candidate]:
    """``SemanticHeuristicReranker.rerank``（不含异常降级）。"""

    if not candidates:
        return []
    if is_blank(query):
        return degrade(candidates, top_n)
    terms = extract_terms(query)
    if not terms:
        return degrade(candidates, top_n)

    normalized_query = lower(trim(query))
    scored: list[Candidate] = []
    for candidate in candidates:
        heading_score = heading_score_of(candidate.heading, normalized_query, terms)
        content_score = content_score_of(candidate.content, terms)
        rrf = candidate.rrf
        combined = (
            (rrf * WEIGHT_RRF) + (content_score * WEIGHT_CONTENT) + (heading_score * WEIGHT_HEADING)
        )
        scored.append(candidate.with_rerank(combined, RERANKER_HEURISTIC))
    scored.sort(key=cmp_to_key(rerank_order))
    return truncate(scored, top_n)


def rerank_order(left: Candidate, right: Candidate) -> int:
    """rerank 降序 → rrf 降序（差值 > 1e-9 才算不同）→ ``(documentId, chunkNo)`` 升序。

    Java ``SemanticHeuristicReranker`` 与 ``ConfiguredReranker.candidateOrder`` 共用这一口径
    （后者 rerank 为空时按 ``-∞``）。
    """

    left_score = left.rerank if left.rerank is not None else float("-inf")
    right_score = right.rerank if right.rerank is not None else float("-inf")
    by_score = java_double_compare(right_score, left_score)
    if by_score != 0:
        return by_score
    if abs(left.rrf - right.rrf) > EPSILON:
        return java_double_compare(right.rrf, left.rrf)
    return compare_keys(left, right)


def heading_score_of(heading: str | None, normalized_query: str, terms: Sequence[str]) -> float:
    if heading is None or is_blank(heading):
        return 0.0
    normalized_heading = lower(trim(heading))
    if normalized_query in normalized_heading:
        return 1.0
    matched = sum(1 for term in terms if term in normalized_heading)
    return matched / len(terms)


def content_score_of(content: str | None, terms: Sequence[str]) -> float:
    if content is None or is_blank(content):
        return 0.0
    lower_content = lower(content)
    matched = 0
    occurrences = 0
    for term in terms:
        index = lower_content.find(term)
        if index >= 0:
            matched += 1
            while index >= 0 and occurrences < MAX_OCCURRENCES:
                occurrences += 1
                index = lower_content.find(term, index + len(term))
    coverage = matched / len(terms)
    frequency_bonus = min(FREQUENCY_CAP, occurrences * FREQUENCY_STEP)
    return min(1.0, coverage + frequency_bonus)


def extract_terms(query: str) -> list[str]:
    """``extractTerms``：词级切分 → 中文 bigram → 兜底 → 截断到 64 个（保持插入顺序）。"""

    tokens = split_on_punct_symbol_space(lower(query))
    terms: dict[str, None] = {}

    for token in tokens:
        trimmed = trim(token)
        if not trimmed or trimmed in STOP_WORDS:
            continue
        if contains_cjk(trimmed):
            if len(trimmed) <= CJK_TOKEN_MAX_LENGTH:  # codePointCount
                terms[trimmed] = None
        elif utf16_length(trimmed) >= 2:
            terms[trimmed] = None

    for token in tokens:
        if not contains_cjk(token):
            continue
        _add_chinese_bigrams(keep_cjk(token), terms)

    if not terms:
        for token in tokens:
            trimmed = trim(token)
            if trimmed and trimmed not in STOP_WORDS:
                terms[trimmed] = None

    return list(terms)[:MAX_TERMS]


def _add_chinese_bigrams(clean_chinese: str, terms: dict[str, None]) -> None:
    length = len(clean_chinese)
    if length < 2:
        return
    bigram_count = length - 1
    step = (
        1
        if bigram_count <= MAX_CHINESE_BIGRAMS
        else (bigram_count + MAX_CHINESE_BIGRAMS - 1) // MAX_CHINESE_BIGRAMS
    )
    for index in range(0, bigram_count, step):
        bigram = clean_chinese[index : index + 2]
        if (
            bigram in STOP_WORDS
            or bigram[0] in CJK_FUNCTION_CHARS
            or bigram[1] in CJK_FUNCTION_CHARS
        ):
            continue
        terms[bigram] = None


def degrade(candidates: Sequence[Candidate], top_n: int) -> list[Candidate]:
    """``degrade``：保持原顺序截断，``rerank = rrf``，标记 ``degraded``。"""

    return [
        candidate.with_rerank(candidate.rrf, RERANKER_DEGRADED)
        for candidate in truncate(list(candidates), top_n)
    ]


def pass_through(candidates: Sequence[Candidate], top_n: int) -> list[Candidate]:
    """``PassThroughReranker``：保持顺序截断，``rerank = rrf``。"""

    return [
        candidate.with_rerank(candidate.rrf, RERANKER_PASS_THROUGH)
        for candidate in truncate(list(candidates), top_n)
    ]


__all__ = [
    "STOP_WORDS",
    "content_score_of",
    "degrade",
    "extract_terms",
    "heading_score_of",
    "heuristic_rerank",
    "pass_through",
    "rerank_order",
]
