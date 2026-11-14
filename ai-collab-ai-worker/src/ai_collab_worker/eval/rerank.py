"""Rerank 离线评测：指标、冻结集校验与两种打分器。

口径的唯一来源是 ``docs/archive/handoffs/w3-rerank-freeze-20261112.md`` §4：

- ``NDCG@8``：graded gain ``2^rel-1``、discount ``log2(rank+1)``，IDCG 取理想排序；
  无正例（全部 ``rel=0``）的 case 记 ``1.0`` 并在报告里用 ``noPositiveLabels`` 标注。
- ``引用支持率``：top-k 中 ``supports[id]=true`` 的条数 / ``min(k, 候选数)``。
- ``P95``：最近秩法（nearest-rank），**不是**线性插值；全体样本升序取
  ``ceil(95*n/100)`` 位。样本口径是"每条 case 连续执行 N 次（warmup 不计）"。
- W3.1 三口径（``docs/archive/handoffs/w3-final-freeze-20261112.md`` §1.5）：逐样本
  ``total`` = 评测外层实测毫秒、``model`` = worker 响应 ``modelLatencyMs``（可能缺失）、
  ``overhead = max(0, total - model)``（仅 model 已知时）。P50/P95/P99 一律最近秩法，
  不做插值；``model`` 全未知时 ``model*/overhead*`` 字段为 ``null``，不用 0 冒充。
  ``confidence`` 由样本量决定（>=500 HIGH、200–499 MEDIUM、<200 LOW_CONFIDENCE）。
- ``--candidate-target`` 用确定性合成候选把每条 case 补齐到 N 条（冻结 §1.6 的 stub 基准
  覆盖候选数）。补齐的候选 ``labels=0``、``supports=false``，因此 NDCG/引用支持率在补齐
  口径下**不具可比性**，只能用于延迟测量。

两种打分器：

- :class:`HeuristicScorer`：逐条复刻 Java ``SemanticHeuristicReranker``
  （``ai-collab-backend`` .../retrieval/pipeline/SemanticHeuristicReranker.java）：
  停用词表、``[\\p{P}\\p{S}\\s]+`` 切分、中文 bigram、``MAX_TERMS=64``、
  ``MAX_CHINESE_BIGRAMS=48``、``CJK_TOKEN_MAX_LENGTH=10``、虚词过滤，以及
  ``0.35*rrf + 0.40*content + 0.25*heading`` 与
  "rerankScore 降序 → rrfScore 降序（``|Δ|<=1e-9`` 视为并列）→ id 升序"的排序。
  候选没有 ``rrfScore`` 时按 ``0.0``。
- :class:`WorkerScorer`：``POST {worker_url}/v1/rag/rerank``，请求形状按冻结 §1，
  头带 ``Authorization: Bearer <token>``（token 只从 ``--service-token-env`` 指定的
  环境变量读取，绝不落盘、不打印）。

与 Java 的**刻意偏差**（都只影响离线评测，不影响在线行为）：

1. 不吞异常：Java 用 ``catch (Throwable)`` 降级为原顺序，这里让异常直接失败，
   避免评测把实现 bug 静默成"分数不好看"。可预期的降级分支（空查询/无词项）仍然复刻。
2. Python ``str.lower()`` 与 Java ``toLowerCase(Locale.ROOT)`` 对个别特殊码点（如 ``İ``）
   的展开可能不同；冻结集只用中文与 ASCII 术语，不受影响。
3. 空白的判定按 ``Character.isWhitespace`` 语义实现，不把不换行空格（U+00A0 等）当空白。
"""

from __future__ import annotations

import functools
import math
import os
import time
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType, ModuleType
from typing import TYPE_CHECKING, Any, Final, Literal, Protocol

from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_document,
    digest,
    load_dataset,
)

if TYPE_CHECKING:
    import httpx

#: 冻结契约 §1 的候选/文本上限（评测集校验也按同一组边界 fail-closed）。
MAX_CANDIDATES: Final[int] = 50
MIN_CANDIDATES: Final[int] = 1
MAX_QUERY_CHARS: Final[int] = 4000
MAX_TITLE_CHARS: Final[int] = 500
MAX_TEXT_CHARS: Final[int] = 2000
MIN_TEXT_CHARS: Final[int] = 1

#: 冻结集 topK 默认 8（``ai-eval rerank --top-k`` 默认一致）。
DEFAULT_TOP_K: Final[int] = 8
#: 冻结 §4：每条 case 连续执行 20 次（含前 2 次 warmup），warmup 不计入样本。
DEFAULT_RUNS: Final[int] = 20
DEFAULT_WARMUP_RUNS: Final[int] = 2
#: 冻结 §1：``deadlineMs`` 默认 3000。
DEFAULT_DEADLINE_MS: Final[int] = 3000
#: worker 内部服务令牌的环境变量名（--service-token-env 默认值）。
DEFAULT_SERVICE_TOKEN_ENV: Final[str] = "AI_WORKER_SERVICE_TOKEN"

#: 置信度分级（W3.1 冻结 §1.5）：样本量决定，<200 只能标 LOW_CONFIDENCE。
Confidence = Literal["HIGH", "MEDIUM", "LOW_CONFIDENCE"]
CONFIDENCE_HIGH: Final[Confidence] = "HIGH"
CONFIDENCE_MEDIUM: Final[Confidence] = "MEDIUM"
CONFIDENCE_LOW: Final[Confidence] = "LOW_CONFIDENCE"
#: samples >= 500 → HIGH（stub 基准 15 case × 34 runs = 510）。
HIGH_CONFIDENCE_MIN_SAMPLES: Final[int] = 500
#: 200 <= samples < 500 → MEDIUM。
MEDIUM_CONFIDENCE_MIN_SAMPLES: Final[int] = 200

#: 候选补齐：追加候选的 id 前缀与标题模板（确定性，不含 query/正文内容）。
PADDED_ID_PREFIX: Final[str] = "pad-"
PADDED_TITLE_TEMPLATE: Final[str] = "补齐候选{index}"

#: Java ``SemanticHeuristicReranker`` 的常量，逐条对齐。
STOP_WORDS: Final[frozenset[str]] = frozenset(
    {
        "的",
        "了",
        "在",
        "是",
        "我",
        "有",
        "和",
        "就",
        "不",
        "人",
        "都",
        "一",
        "一个",
        "上",
        "也",
        "很",
        "到",
        "说",
        "要",
        "去",
        "你",
        "会",
        "着",
        "没有",
        "看",
        "好",
        "自己",
        "这",
        "如何",
        "怎么",
        "为什么",
        "什么",
        "哪些",
        "及",
        "与",
        "或",
        "the",
        "a",
        "an",
        "and",
        "or",
        "but",
        "in",
        "on",
        "at",
        "to",
        "for",
        "of",
        "with",
        "is",
        "are",
        "was",
        "were",
        "it",
        "this",
        "that",
        "how",
        "what",
        "why",
    }
)
MAX_TERMS: Final[int] = 64
MAX_CHINESE_BIGRAMS: Final[int] = 48
CJK_TOKEN_MAX_LENGTH: Final[int] = 10
CJK_FUNCTION_CHARS: Final[frozenset[str]] = frozenset("的了在是和与或及对为等把被向从到要会有都")
#: Java ``Double.compare`` 的并列阈值（rrf 次级排序）。
RRF_EPSILON: Final[float] = 1e-9

#: Java 正则 ``\s``（未开 UNICODE_CHARACTER_CLASS）只匹配 ASCII 空白。
_ASCII_WHITESPACE: Final[frozenset[str]] = frozenset(" \t\n\x0b\f\r")
#: ``Character.isWhitespace`` 明确排除的不换行空格。
_NON_BREAKING_SPACES: Final[frozenset[str]] = frozenset({"\u00a0", "\u2007", "\u202f"})

_CASE_FIELDS: Final[frozenset[str]] = frozenset({"id", "query", "candidates", "labels", "supports"})
_CANDIDATE_REQUIRED_FIELDS: Final[frozenset[str]] = frozenset({"id", "title", "text"})
_CANDIDATE_OPTIONAL_FIELDS: Final[frozenset[str]] = frozenset({"rrfScore"})

_STATUS_CODES: Final[Mapping[int, str]] = MappingProxyType(
    {
        400: "INVALID_REQUEST",
        401: "INVALID_REQUEST",
        403: "INVALID_REQUEST",
        409: "INVALID_REQUEST",
        422: "INVALID_REQUEST",
        429: "MODEL_RATE_LIMITED",
        503: "MODEL_UNAVAILABLE",
        504: "DEADLINE_EXCEEDED",
    }
)


@dataclass(frozen=True, slots=True)
class RerankCandidate:
    """一个候选片段；``rrf_score`` 对应 Java ``RetrievalCandidate.rrfScore()``。"""

    id: str
    title: str
    text: str
    rrf_score: float | None = None


@dataclass(frozen=True, slots=True)
class RerankCase:
    """冻结集的一条 case；``labels`` / ``supports`` 覆盖全部候选。"""

    id: str
    query: str
    candidates: tuple[RerankCandidate, ...]
    labels: Mapping[str, int]
    supports: Mapping[str, bool]


@dataclass(frozen=True, slots=True)
class CaseResult:
    """单条 case 的评测结果；``ranking`` 是截断到 top-k 的实际排序。"""

    id: str
    ndcg: float
    support: float
    ranking: tuple[str, ...]
    no_positive_labels: bool


@dataclass(frozen=True, slots=True)
class LatencySummary:
    """延迟采样摘要；未开启测量时 ``measured=False`` 且全部分位数为 ``None``。

    ``p95_ms`` 是兼容旧报告的别名，与 ``total_p95_ms`` 同值（W3.1 冻结 §1.5）。
    """

    measured: bool
    runs: int
    warmup: int
    samples: int
    p95_ms: float | None
    p50_ms: float | None = None
    p99_ms: float | None = None
    total_p95_ms: float | None = None
    total_p99_ms: float | None = None
    model_known_samples: int = 0
    model_p95_ms: float | None = None
    model_p99_ms: float | None = None
    overhead_p95_ms: float | None = None
    overhead_p99_ms: float | None = None
    confidence: Confidence = CONFIDENCE_LOW


@dataclass(frozen=True, slots=True)
class EvalReport:
    """一次评测的完整结果（不含 SHA-256，由 :func:`seal_report` 追加）。"""

    scorer: str
    top_k: int
    cases: tuple[CaseResult, ...]
    ndcg: float
    support: float
    latency: LatencySummary
    candidate_target: int | None = None


class Scorer(Protocol):
    """打分器协议：给一条 case 返回候选 id 的完整排序（可长于 top-k）。

    ``model_latency_ms`` 返回**最近一次** ``rank`` 观测到的模型耗时（``T_model``）；
    打分器拿不到（heuristic）或上游未返回时返回 ``None``，绝不猜 0。
    """

    name: str

    def rank(self, case: RerankCase) -> list[str]: ...

    def model_latency_ms(self) -> int | None: ...

    def close(self) -> None: ...


class WorkerError(Exception):
    """worker 打分失败；``code`` 透传 worker 错误信封，缺省时按 HTTP 状态映射。"""

    def __init__(self, code: str, status: int | None, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.status = status
        self.detail = detail


# --------------------------------------------------------------------------- #
# 指标
# --------------------------------------------------------------------------- #


def ndcg_at_k(ranking: Sequence[str], labels: Mapping[str, int], k: int = DEFAULT_TOP_K) -> float:
    """NDCG@k：``gain=2^rel-1``、``discount=log2(rank+1)``，IDCG 取理想排序。

    没有正例（``labels`` 为空或全部 ``rel=0``）时按冻结口径记 ``1.0``；调用方应同时
    用 :func:`has_positive_labels` 在报告里标注，避免把"没有可评测的正例"误读成"完美命中"。
    """

    if k < 1:
        raise ValueError("k 必须 >= 1")
    if not labels or not has_positive_labels(labels):
        return 1.0

    dcg_value = _dcg(list(ranking)[:k], labels)
    ideal = sorted(labels.values(), reverse=True)[:k]
    idcg_value = 0.0
    for rank, relevance in enumerate(ideal, start=1):
        idcg_value += (2**relevance - 1) / math.log2(rank + 1)
    return dcg_value / idcg_value


def has_positive_labels(labels: Mapping[str, int]) -> bool:
    """是否至少有一个 ``rel>0`` 的候选（NDCG 的可评测前提）。"""

    return any(relevance > 0 for relevance in labels.values())


def support_rate(
    ranking: Sequence[str], supports: Mapping[str, bool], k: int = DEFAULT_TOP_K
) -> float:
    """引用支持率：top-k 中 ``supports[id]=true`` 的条数 / ``min(k, 候选数)``。

    ``supports`` 必须覆盖全部候选（冻结集校验保证），因此 ``len(supports)`` 就是候选数。
    """

    if k < 1:
        raise ValueError("k 必须 >= 1")
    denominator = min(k, len(supports))
    if denominator == 0:
        # loader 禁止空候选集，这里只处理纯函数被直接调用的退化输入。
        return 1.0
    hits = sum(1 for candidate_id in list(ranking)[:k] if supports.get(candidate_id) is True)
    return hits / denominator


def _nearest_rank(samples: Sequence[float], percentile: int) -> float:
    """最近秩法分位数：升序后取 ``ceil(percentile*n/100)`` 位，**不做线性插值**。

    用整数运算算秩，避免 ``0.95 * n`` 的二进制误差（``0.95*20 == 19.000000000000004``
    会把 n=20 的秩错误地抬到 20）。
    """

    if not 1 <= percentile <= 100:
        raise ValueError("percentile 必须在 1–100 之间")
    if not samples:
        raise ValueError("延迟样本不能为空")
    ordered = sorted(samples)
    count = len(ordered)
    rank = max(1, (percentile * count + 99) // 100)
    return ordered[rank - 1]


def p50(latencies_ms: Sequence[float]) -> float:
    """最近秩法 P50（与 P95/P99 同口径，供 stub 基准报告使用）。"""

    return _nearest_rank(latencies_ms, 50)


def p95(latencies_ms: Sequence[float]) -> float:
    """最近秩法 P95（冻结口径，**不是**插值）。"""

    return _nearest_rank(latencies_ms, 95)


def p99(latencies_ms: Sequence[float]) -> float:
    """最近秩法 P99（W3.1 工程护栏，与 P95 同口径，不做插值）。"""

    return _nearest_rank(latencies_ms, 99)


def confidence_for_samples(samples: int) -> Confidence:
    """按样本量给出置信度（W3.1 冻结 §1.5）：>=500 HIGH、200–499 MEDIUM、其余 LOW。"""

    if samples >= HIGH_CONFIDENCE_MIN_SAMPLES:
        return CONFIDENCE_HIGH
    if samples >= MEDIUM_CONFIDENCE_MIN_SAMPLES:
        return CONFIDENCE_MEDIUM
    return CONFIDENCE_LOW


def _dcg(ranking: Sequence[str], labels: Mapping[str, int]) -> float:
    total = 0.0
    for rank, candidate_id in enumerate(ranking, start=1):
        total += (2 ** labels[candidate_id] - 1) / math.log2(rank + 1)
    return total


# --------------------------------------------------------------------------- #
# Heuristic：Java SemanticHeuristicReranker 的等价复刻
# --------------------------------------------------------------------------- #


class HeuristicScorer:
    """Python 侧的 Java 基线代理：调用 :func:`heuristic_rank`。"""

    name: str = "heuristic"

    def __init__(self, top_k: int = DEFAULT_TOP_K) -> None:
        if top_k < 1:
            raise ValueError("top_k 必须 >= 1")
        self._top_k = top_k

    def rank(self, case: RerankCase) -> list[str]:
        return heuristic_rank(case.query, case.candidates, self._top_k)

    def model_latency_ms(self) -> int | None:
        """纯本地启发式没有模型调用，``T_model`` 恒为未知。"""

        return None

    def close(self) -> None:
        return None


def heuristic_rank(
    query: str | None, candidates: Sequence[RerankCandidate], top_k: int = DEFAULT_TOP_K
) -> list[str]:
    """复刻 ``doRerank``：打分 → 排序（rerankScore→rrf→id）→ 截断 topN。

    与 Java 一致：``query`` 为 ``None``/空白、或过滤后无词项时走 ``degrade``，
    保持原候选顺序（分数取 rrf）而不是重新排序。
    """

    if not candidates:
        return []

    ranked_ids = [candidate.id for candidate in candidates]
    if query is None or _is_blank(query):
        return _take(ranked_ids, top_k)

    terms = extract_terms(query)
    if not terms:
        return _take(ranked_ids, top_k)

    normalized_query = _java_trim(query).lower()
    scored = [
        (candidate, _combined_score(normalized_query, terms, candidate)) for candidate in candidates
    ]
    scored.sort(key=functools.cmp_to_key(_compare_scored))
    return [candidate.id for candidate, _ in _take(scored, top_k)]


def heuristic_scores(
    query: str | None, candidates: Sequence[RerankCandidate]
) -> list[tuple[str, float]]:
    """按输入顺序返回 ``(id, rerankScore)``，用于逐条核对权重公式。

    空查询/无词项时与 Java ``degrade`` 一致：分数取 ``rrfScore``（缺省 0.0），顺序不变。
    """

    if not candidates:
        return []
    if query is None or _is_blank(query):
        return [(candidate.id, _rrf_score(candidate)) for candidate in candidates]

    terms = extract_terms(query)
    if not terms:
        return [(candidate.id, _rrf_score(candidate)) for candidate in candidates]

    normalized_query = _java_trim(query).lower()
    return [
        (candidate.id, _combined_score(normalized_query, terms, candidate))
        for candidate in candidates
    ]


def extract_terms(query: str) -> list[str]:
    """复刻 ``extractTerms``：切分 → 停用词 → 短中文保留 → 中文 bigram → 兜底 → 截断。"""

    lowercased = query.lower()
    tokens = _split_tokens(lowercased)
    terms: dict[str, None] = {}

    for token in tokens:
        trimmed = _java_trim(token)
        if not trimmed or trimmed in STOP_WORDS:
            continue
        if _contains_cjk(trimmed):
            if len(trimmed) <= CJK_TOKEN_MAX_LENGTH:
                terms.setdefault(trimmed, None)
        elif _java_length(trimmed) >= 2:
            terms.setdefault(trimmed, None)

    for token in tokens:
        if not _contains_cjk(token):
            continue
        clean_chinese = "".join(character for character in token if _is_cjk(character))
        _add_chinese_bigrams(clean_chinese, terms)

    if not terms:
        for token in tokens:
            trimmed = _java_trim(token)
            if trimmed and trimmed not in STOP_WORDS:
                terms.setdefault(trimmed, None)

    if len(terms) > MAX_TERMS:
        return list(terms)[:MAX_TERMS]
    return list(terms)


def _add_chinese_bigrams(clean_chinese: str, terms: dict[str, None]) -> None:
    """复刻 ``addChineseBigrams``：超过上限时按均匀步长采样，并过滤虚词端点。"""

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
        if bigram in STOP_WORDS:
            continue
        if bigram[0] in CJK_FUNCTION_CHARS or bigram[1] in CJK_FUNCTION_CHARS:
            continue
        terms.setdefault(bigram, None)


def _combined_score(
    normalized_query: str, terms: Sequence[str], candidate: RerankCandidate
) -> float:
    heading_score = _heading_score(candidate.title, normalized_query, terms)
    content_score = _content_score(candidate.text, terms)
    rrf = _rrf_score(candidate)
    # 与 Java 逐字相同：0.35 * RRF + 0.40 * 内容核心词覆盖 + 0.25 * 标题匹配。
    return (rrf * 0.35) + (content_score * 0.40) + (heading_score * 0.25)


def _heading_score(heading: str | None, normalized_query: str, terms: Sequence[str]) -> float:
    """复刻 ``calculateHeadingScore``：整句包含查询得 1.0，否则按词项覆盖率。"""

    if heading is None or _is_blank(heading):
        return 0.0
    normalized_heading = _java_trim(heading).lower()
    if normalized_query in normalized_heading:
        return 1.0
    matched = sum(1 for term in terms if term in normalized_heading)
    return matched / len(terms)


def _content_score(content: str | None, terms: Sequence[str]) -> float:
    """复刻 ``calculateContentScore``：覆盖率 + 词频加成（全局最多 10 次，上限 0.15）。"""

    if content is None or _is_blank(content):
        return 0.0

    lower_content = content.lower()
    matched = 0
    occurrences = 0
    for term in terms:
        index = lower_content.find(term)
        if index < 0:
            continue
        matched += 1
        while index >= 0 and occurrences < 10:
            occurrences += 1
            index = lower_content.find(term, index + len(term))

    coverage = matched / len(terms)
    frequency_bonus = min(0.15, occurrences * 0.02)
    return min(1.0, coverage + frequency_bonus)


def _compare_scored(
    left: tuple[RerankCandidate, float], right: tuple[RerankCandidate, float]
) -> int:
    """Java 排序比较器：rerankScore 降序 → rrfScore 降序（epsilon）→ id 升序。"""

    left_candidate, left_score = left
    right_candidate, right_score = right
    if left_score != right_score:
        return -1 if left_score > right_score else 1

    left_rrf = _rrf_score(left_candidate)
    right_rrf = _rrf_score(right_candidate)
    if abs(left_rrf - right_rrf) > RRF_EPSILON:
        return -1 if left_rrf > right_rrf else 1

    if left_candidate.id == right_candidate.id:
        return 0
    return -1 if left_candidate.id < right_candidate.id else 1


def _rrf_score(candidate: RerankCandidate) -> float:
    return candidate.rrf_score if candidate.rrf_score is not None else 0.0


def _take[T](values: Sequence[T], top_k: int) -> list[T]:
    """Java ``topN > 0 && size > topN`` 的截断语义；``topN <= 0`` 表示不截断。"""

    if top_k > 0 and len(values) > top_k:
        return list(values[:top_k])
    return list(values)


def _split_tokens(value: str) -> list[str]:
    """复刻 ``[\\p{P}\\p{S}\\s]+`` 切分；丢弃空片段（Java 也会跳过空 token）。"""

    tokens: list[str] = []
    current: list[str] = []
    for character in value:
        if _is_split_character(character):
            if current:
                tokens.append("".join(current))
                current = []
        else:
            current.append(character)
    if current:
        tokens.append("".join(current))
    return tokens


def _is_split_character(character: str) -> bool:
    if character in _ASCII_WHITESPACE:
        return True
    return unicodedata.category(character)[0] in {"P", "S"}


def _contains_cjk(value: str) -> bool:
    return any(_is_cjk(character) for character in value)


def _is_cjk(character: str) -> bool:
    return "\u4e00" <= character <= "\u9fa5"


def _java_length(value: str) -> int:
    """Java ``String.length()``：UTF-16 code unit 数（BMP 之外算 2）。

    Python 的 ``len`` 数的是码点；非 CJK token（例如扩展区汉字）会被 Java 当成 2 个
    code unit，这里按同一口径折算，避免"单码点 token 被吞掉"的偏差。
    """

    return sum(2 if ord(character) > 0xFFFF else 1 for character in value)


def _java_trim(value: str) -> str:
    """Java ``String.trim``：去掉首尾 ``<= U+0020`` 的字符。"""

    start = 0
    end = len(value)
    while start < end and ord(value[start]) <= 0x20:
        start += 1
    while end > start and ord(value[end - 1]) <= 0x20:
        end -= 1
    return value[start:end]


def _is_blank(value: str) -> bool:
    """Java ``String.isBlank``：全部字符都是 ``Character.isWhitespace``。"""

    return all(_is_java_whitespace(character) for character in value)


def _is_java_whitespace(character: str) -> bool:
    if character in _ASCII_WHITESPACE or "\x1c" <= character <= "\x1f":
        return True
    if character in _NON_BREAKING_SPACES:
        return False
    return unicodedata.category(character) in {"Zs", "Zl", "Zp"}


# --------------------------------------------------------------------------- #
# Worker：POST {worker_url}/v1/rag/rerank
# --------------------------------------------------------------------------- #


class WorkerScorer:
    """把冻结 §1 的请求打到真实 worker，并把响应归一成候选排序。"""

    name: str = "worker"

    def __init__(
        self,
        worker_url: str,
        *,
        top_k: int = DEFAULT_TOP_K,
        deadline_ms: int = DEFAULT_DEADLINE_MS,
        token_env: str = DEFAULT_SERVICE_TOKEN_ENV,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not worker_url.strip():
            raise ValueError("worker_url 不能为空")
        if not 1 <= top_k <= MAX_CANDIDATES:
            raise ValueError("top_k 必须在 1–50 之间")
        if not 1 <= deadline_ms <= 30000:
            raise ValueError("deadline_ms 必须在 1–30000 之间")
        token = os.environ.get(token_env, "").strip()
        if not token:
            raise WorkerError(
                "SERVICE_TOKEN_MISSING",
                None,
                f"环境变量 {token_env} 未设置或为空；worker 需要内部服务令牌",
            )
        httpx_module = _require_httpx()
        self._url = f"{worker_url.rstrip('/')}/v1/rag/rerank"
        self._token = token
        self._top_k = top_k
        self._deadline_ms = deadline_ms
        self._last_model_latency_ms: int | None = None
        # 冻结 §7.1：read = deadlineMs + 5s、connect 2s。
        timeout = httpx_module.Timeout(deadline_ms / 1000 + 5.0, connect=2.0)
        self._client = httpx_module.Client(timeout=timeout, transport=transport)

    def rank(self, case: RerankCase) -> list[str]:
        import httpx  # 只在真正打 worker 时才要求 dev 依赖

        self._last_model_latency_ms = None
        payload = build_worker_request(case, top_k=self._top_k, deadline_ms=self._deadline_ms)
        try:
            response = self._client.post(
                self._url,
                json=payload,
                headers={"Authorization": f"Bearer {self._token}"},
            )
        except httpx.TimeoutException as exc:
            raise WorkerError("DEADLINE_EXCEEDED", None, f"请求超时：{exc}") from exc
        except httpx.HTTPError as exc:
            raise WorkerError("TRANSPORT_ERROR", None, f"{type(exc).__name__}: {exc}") from exc

        if response.status_code >= 400:
            raise _worker_error_from_response(response)
        ranking, model_latency_ms = _parse_worker_scores(response, case)
        self._last_model_latency_ms = model_latency_ms
        return ranking

    def model_latency_ms(self) -> int | None:
        """最近一次成功 ``rank`` 的 ``modelLatencyMs``；响应没带就是 ``None``。"""

        return self._last_model_latency_ms

    def close(self) -> None:
        self._client.close()


def _require_httpx() -> ModuleType:
    """按需导入 httpx。

    P0 的 ``fingerprint`` / ``canonicalize`` / ``verify`` 只依赖标准库；把 httpx 做成
    worker 路径的按需依赖，生产镜像（不装 dev 依赖）里的 ``ai-eval`` 才不会因为缺
    httpx 而整体不可用。
    """

    try:
        import httpx
    except ImportError as exc:
        raise WorkerError(
            "WORKER_CLIENT_UNAVAILABLE",
            None,
            "缺少 httpx；--scorer worker 需要 dev 依赖（cd ai-collab-ai-worker && uv sync）",
        ) from exc
    return httpx


def build_worker_request(
    case: RerankCase, *, top_k: int = DEFAULT_TOP_K, deadline_ms: int = DEFAULT_DEADLINE_MS
) -> dict[str, Any]:
    """冻结 §1 的请求体；字段名与顺序都不影响服务端，但这里是契约测试的锚点。"""

    return {
        "schemaVersion": SCHEMA_VERSION,
        "query": case.query,
        "topK": top_k,
        "candidates": [
            {"id": candidate.id, "title": candidate.title, "text": candidate.text}
            for candidate in case.candidates
        ],
        "deadlineMs": deadline_ms,
    }


def _worker_error_from_response(response: httpx.Response) -> WorkerError:
    code, message = _error_envelope(response)
    status = response.status_code
    resolved = code or _STATUS_CODES.get(status, "INTERNAL" if status >= 500 else "INVALID_REQUEST")
    return WorkerError(resolved, status, message or f"HTTP {status}")


def _error_envelope(response: httpx.Response) -> tuple[str | None, str | None]:
    try:
        body = response.json()
    except ValueError:
        return None, None
    if not isinstance(body, Mapping):
        return None, None
    code = body.get("code")
    message = body.get("message")
    resolved_code = code if isinstance(code, str) and code else None
    resolved_message = message if isinstance(message, str) and message else None
    return resolved_code, resolved_message


def _parse_worker_scores(
    response: httpx.Response, case: RerankCase
) -> tuple[list[str], int | None]:
    try:
        body = response.json()
    except ValueError as exc:
        raise WorkerError("INVALID_RESPONSE", response.status_code, "响应不是合法 JSON") from exc
    if not isinstance(body, Mapping):
        raise WorkerError("INVALID_RESPONSE", response.status_code, "响应顶层必须是 JSON 对象")
    if not _is_schema_version(body.get("schemaVersion")):
        raise WorkerError(
            "INVALID_RESPONSE", response.status_code, f"schemaVersion 必须为 {SCHEMA_VERSION}"
        )

    raw_scores = body.get("scores")
    if not isinstance(raw_scores, list):
        raise WorkerError("INVALID_RESPONSE", response.status_code, "scores 必须是数组")

    expected = [candidate.id for candidate in case.candidates]
    if len(raw_scores) != len(expected):
        raise WorkerError(
            "INVALID_RESPONSE",
            response.status_code,
            f"scores 条数 {len(raw_scores)} 与候选数 {len(expected)} 不一致",
        )

    received: list[str] = []
    values: dict[str, float] = {}
    for item in raw_scores:
        if not isinstance(item, Mapping):
            raise WorkerError("INVALID_RESPONSE", response.status_code, "scores 条目必须是对象")
        candidate_id = item.get("id")
        if not isinstance(candidate_id, str) or not candidate_id:
            raise WorkerError("INVALID_RESPONSE", response.status_code, "scores.id 缺失或非法")
        if candidate_id in values:
            raise WorkerError(
                "INVALID_RESPONSE", response.status_code, f"scores 出现重复 id {candidate_id!r}"
            )
        try:
            value = _optional_number(item.get("score"), "score")
        except DatasetError as exc:
            # 冻结集校验用的数值错误在 worker 路径上必须表达为"非法响应"（退出码 4）。
            raise WorkerError("INVALID_RESPONSE", response.status_code, str(exc)) from exc
        if value is None or not math.isfinite(value) or not 0.0 <= value <= 1.0:
            raise WorkerError(
                "INVALID_RESPONSE",
                response.status_code,
                f"候选 {candidate_id!r} 的 score 必须是 [0,1] 内的有限数值",
            )
        received.append(candidate_id)
        values[candidate_id] = value

    if received != expected:
        raise WorkerError(
            "INVALID_RESPONSE", response.status_code, "scores 未按输入顺序覆盖全部候选 id"
        )

    model_latency_ms = _parse_model_latency(body, response.status_code)
    # Python 的 sorted 是稳定排序：同分时保持输入顺序（与 worker 的返回顺序一致）。
    return sorted(expected, key=lambda candidate_id: -values[candidate_id]), model_latency_ms


def _parse_model_latency(body: Mapping[str, Any], status: int) -> int | None:
    """读取 ``modelLatencyMs``：缺省/``null`` 视为未知；形状非法按契约漂移拒绝。"""

    raw = body.get("modelLatencyMs")
    if raw is None:
        return None
    if isinstance(raw, bool) or not isinstance(raw, int) or raw < 0:
        raise WorkerError("INVALID_RESPONSE", status, "modelLatencyMs 必须是非负整数或 null")
    return int(raw)


def _is_schema_version(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value == SCHEMA_VERSION


# --------------------------------------------------------------------------- #
# 冻结集读取与校验
# --------------------------------------------------------------------------- #


def load_rerank_set(path: Path) -> list[RerankCase]:
    """读取 rerank 冻结集（JSONL/JSON），按冻结 §4 的字段与语义 fail-closed 校验。"""

    cases: list[RerankCase] = []
    for position, raw_case in enumerate(load_dataset(path), start=1):
        cases.append(_parse_case(raw_case, position))
    if not cases:
        raise DatasetError("rerank 冻结集不能为空")
    return cases


def _parse_case(raw_case: Mapping[str, Any], position: int) -> RerankCase:
    unknown = sorted(set(raw_case) - _CASE_FIELDS)
    if unknown:
        raise DatasetError(f"第 {position} 个 case 含未定义字段：{', '.join(unknown)}")
    missing = sorted(_CASE_FIELDS - set(raw_case))
    if missing:
        raise DatasetError(f"第 {position} 个 case 缺少字段：{', '.join(missing)}")

    case_id = raw_case.get("id")
    if not isinstance(case_id, str) or not case_id:
        raise DatasetError(f"第 {position} 个 case 缺少非空字符串 id")
    where = f"case {case_id!r}"

    query = _require_string(raw_case["query"], f"{where} 的 query")
    if not query.strip():
        raise DatasetError(f"{where} 的 query 去空白后不能为空")
    if len(query) > MAX_QUERY_CHARS:
        raise DatasetError(f"{where} 的 query 超过 {MAX_QUERY_CHARS} 字符上限")

    candidates = _parse_candidates(raw_case["candidates"], where)
    labels = _parse_labels(raw_case["labels"], candidates, where)
    supports = _parse_supports(raw_case["supports"], candidates, where)

    for candidate in candidates:
        if labels[candidate.id] == 2 and supports[candidate.id] is not True:
            raise DatasetError(
                f"{where} 的候选 {candidate.id!r} labels=2 但 supports=false；"
                "2 档必须标记为可支撑引用"
            )
        if labels[candidate.id] == 0 and supports[candidate.id] is not False:
            raise DatasetError(
                f"{where} 的候选 {candidate.id!r} labels=0 但 supports=true；"
                "0 档不得标记为可支撑引用"
            )

    return RerankCase(
        id=case_id,
        query=query,
        candidates=tuple(candidates),
        labels=MappingProxyType(labels),
        supports=MappingProxyType(supports),
    )


def _parse_candidates(raw_candidates: object, where: str) -> list[RerankCandidate]:
    if not isinstance(raw_candidates, list):
        raise DatasetError(f"{where} 的 candidates 必须是数组")
    if not MIN_CANDIDATES <= len(raw_candidates) <= MAX_CANDIDATES:
        raise DatasetError(
            f"{where} 的候选数必须在 {MIN_CANDIDATES}–{MAX_CANDIDATES} 之间，"
            f"实际 {len(raw_candidates)}"
        )

    candidates: list[RerankCandidate] = []
    seen: set[str] = set()
    for index, raw_candidate in enumerate(raw_candidates, start=1):
        if not isinstance(raw_candidate, Mapping):
            raise DatasetError(f"{where} 的第 {index} 个候选不是 JSON 对象")
        keys = set(raw_candidate)
        unknown = sorted(keys - _CANDIDATE_REQUIRED_FIELDS - _CANDIDATE_OPTIONAL_FIELDS)
        if unknown:
            raise DatasetError(f"{where} 的第 {index} 个候选含未定义字段：{', '.join(unknown)}")
        missing = sorted(_CANDIDATE_REQUIRED_FIELDS - keys)
        if missing:
            raise DatasetError(f"{where} 的第 {index} 个候选缺少字段：{', '.join(missing)}")

        candidate_id = _require_string(raw_candidate["id"], f"{where} 的第 {index} 个候选 id")
        if candidate_id in seen:
            raise DatasetError(f"{where} 的候选 id 重复：{candidate_id!r}")
        seen.add(candidate_id)

        title = _require_string(raw_candidate["title"], f"{where} 候选 {candidate_id!r} 的 title")
        if len(title) > MAX_TITLE_CHARS:
            raise DatasetError(
                f"{where} 候选 {candidate_id!r} 的 title 超过 {MAX_TITLE_CHARS} 字符"
            )
        text = _require_string(raw_candidate["text"], f"{where} 候选 {candidate_id!r} 的 text")
        if not MIN_TEXT_CHARS <= len(text) <= MAX_TEXT_CHARS:
            raise DatasetError(
                f"{where} 候选 {candidate_id!r} 的 text 长度必须在 "
                f"{MIN_TEXT_CHARS}–{MAX_TEXT_CHARS} 之间，实际 {len(text)}"
            )
        rrf_score = _optional_number(
            raw_candidate.get("rrfScore"), f"{where} 候选 {candidate_id!r} 的 rrfScore"
        )
        candidates.append(
            RerankCandidate(id=candidate_id, title=title, text=text, rrf_score=rrf_score)
        )
    return candidates


def _parse_labels(
    raw_labels: object, candidates: Sequence[RerankCandidate], where: str
) -> dict[str, int]:
    if not isinstance(raw_labels, Mapping):
        raise DatasetError(f"{where} 的 labels 必须是对象")
    candidate_ids = [candidate.id for candidate in candidates]
    _require_exact_keys(raw_labels, candidate_ids, f"{where} 的 labels")

    labels: dict[str, int] = {}
    for candidate_id in candidate_ids:
        label = raw_labels[candidate_id]
        if not isinstance(label, int) or isinstance(label, bool) or label not in (0, 1, 2):
            raise DatasetError(f"{where} 的 labels[{candidate_id!r}] 必须是 0/1/2 的整数")
        labels[candidate_id] = label
    return labels


def _parse_supports(
    raw_supports: object, candidates: Sequence[RerankCandidate], where: str
) -> dict[str, bool]:
    if not isinstance(raw_supports, Mapping):
        raise DatasetError(f"{where} 的 supports 必须是对象")
    candidate_ids = [candidate.id for candidate in candidates]
    _require_exact_keys(raw_supports, candidate_ids, f"{where} 的 supports")

    supports: dict[str, bool] = {}
    for candidate_id in candidate_ids:
        support = raw_supports[candidate_id]
        if not isinstance(support, bool):
            raise DatasetError(f"{where} 的 supports[{candidate_id!r}] 必须是布尔值")
        supports[candidate_id] = support
    return supports


def _require_exact_keys(raw: Mapping[str, Any], expected: Sequence[str], where: str) -> None:
    missing = [candidate_id for candidate_id in expected if candidate_id not in raw]
    if missing:
        raise DatasetError(f"{where} 缺少候选：{', '.join(repr(item) for item in missing)}")
    extra = sorted(set(raw) - set(expected))
    if extra:
        raise DatasetError(f"{where} 含未知候选：{', '.join(repr(item) for item in extra)}")


def _require_string(value: object, what: str) -> str:
    if not isinstance(value, str):
        raise DatasetError(f"{what} 必须是字符串")
    return value


def _optional_number(value: object, what: str) -> float | None:
    """把 ``int``/``float``/``Decimal``（JSON 精确小数）归一成 ``float``；``None`` 透传。"""

    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise DatasetError(f"{what} 必须是数值")
    number = float(value)
    if not math.isfinite(number):
        raise DatasetError(f"{what} 必须是有限数值")
    return number


# --------------------------------------------------------------------------- #
# 候选补齐（--candidate-target，仅用于 stub 基准覆盖候选数）
# --------------------------------------------------------------------------- #


def pad_candidates(case: RerankCase, target: int) -> RerankCase:
    """把一条 case 的候选补齐到 ``target`` 条（不足时追加确定性合成候选）。

    W3.1 冻结 §1.6：stub 基准要覆盖 8/20/50 个候选，而冻结集每条只有 6–10 个候选。
    补齐规则（纯函数、确定性，不含 query/正文内容）：

    - 候选数已 ``>= target`` 时**原样返回**（不截断，评测口径不受影响）；
    - 追加候选 id 为 ``pad-<序号>``（1 起；与既有 id 冲突时追加 ``-<n>`` 后缀）；
    - ``title`` 非空（``补齐候选<序号>``，≤500 字符）；
    - ``text`` 由既有候选正文**循环拼接**并截到 ≤2000 字符（至少 1 字符）；
    - ``labels=0``、``supports=false``：合成候选既不是正例也不支撑引用。

    因此补齐后的 NDCG/引用支持率与原始候选数**不具可比性**，只能用于延迟测量。
    """

    if not MIN_CANDIDATES <= target <= MAX_CANDIDATES:
        raise ValueError(f"target 必须在 {MIN_CANDIDATES}–{MAX_CANDIDATES} 之间")
    if len(case.candidates) >= target:
        return case

    existing_ids = {candidate.id for candidate in case.candidates}
    sources = [candidate.text for candidate in case.candidates]
    candidates = list(case.candidates)
    labels = dict(case.labels)
    supports = dict(case.supports)
    for index in range(1, target - len(case.candidates) + 1):
        candidate_id = _padded_id(index, existing_ids)
        existing_ids.add(candidate_id)
        candidates.append(
            RerankCandidate(
                id=candidate_id,
                title=PADDED_TITLE_TEMPLATE.format(index=index),
                text=_padded_text(sources),
            )
        )
        labels[candidate_id] = 0
        supports[candidate_id] = False
    return RerankCase(
        id=case.id,
        query=case.query,
        candidates=tuple(candidates),
        labels=MappingProxyType(labels),
        supports=MappingProxyType(supports),
    )


def _padded_id(index: int, existing_ids: set[str]) -> str:
    candidate_id = f"{PADDED_ID_PREFIX}{index}"
    suffix = 0
    while candidate_id in existing_ids:
        suffix += 1
        candidate_id = f"{PADDED_ID_PREFIX}{index}-{suffix}"
    return candidate_id


def _padded_text(sources: Sequence[str]) -> str:
    """循环拼接既有正文并截到 ``MAX_TEXT_CHARS``（至少 1 字符）。

    ``RerankCase`` 由 loader 保证每条 ``text`` 非空；这里再过滤一次空串并 fail-closed，
    避免纯函数被直接调用时拼出空正文（违反契约 ``text`` 1–2000）。
    """

    usable = [source for source in sources if source]
    if not usable:
        raise ValueError("补齐正文需要至少一条非空候选正文")
    pieces: list[str] = []
    total = 0
    index = 0
    while total < MAX_TEXT_CHARS:
        piece = usable[index % len(usable)]
        if total + len(piece) > MAX_TEXT_CHARS:
            piece = piece[: MAX_TEXT_CHARS - total]
        pieces.append(piece)
        total += len(piece)
        index += 1
    return "".join(pieces)


# --------------------------------------------------------------------------- #
# 评测与报告
# --------------------------------------------------------------------------- #


def evaluate(
    cases: Sequence[RerankCase],
    scorer: Scorer,
    *,
    top_k: int = DEFAULT_TOP_K,
    runs: int = 0,
    warmup: int = 0,
    candidate_target: int | None = None,
) -> EvalReport:
    """逐 case 打分并汇总指标。

    ``runs=0`` 表示不测量耗时：每条 case 只跑一次，``latency.measured=False``、
    全部分位数为 ``None``。测量模式下每条 case 连续跑 ``runs`` 次，前 ``warmup`` 次的耗时
    **丢弃**（只热缓存/连接），其余 ``runs - warmup`` 次计入样本；指标取最后一次排序。
    冻结 §4 的默认值是 ``runs=20``、``warmup=2``（每条 case 18 个有效样本，15 条共 270）。

    每次有效样本同时记录客户端实测总耗时与 :meth:`Scorer.model_latency_ms`，按 W3.1 冻结
    §1.5 拆成 total/model/overhead 三口径；``model`` 缺失的样本只计入 total 与 samples，
    不计入 overhead（不拿 0 冒充模型耗时）。``candidate_target`` 只**记录**补齐口径
    （补齐由 CLI 在调用前用 :func:`pad_candidates` 完成，评测函数不改候选集）。
    """

    if top_k < 1:
        raise ValueError("top_k 必须 >= 1")
    if runs < 0 or warmup < 0:
        raise ValueError("runs / warmup 不能为负数")
    if runs > 0 and warmup >= runs:
        raise ValueError("warmup 必须小于 runs，否则没有有效样本")
    if not cases:
        raise ValueError("评测集不能为空")
    if candidate_target is not None and not MIN_CANDIDATES <= candidate_target <= MAX_CANDIDATES:
        raise ValueError(f"candidate_target 必须在 {MIN_CANDIDATES}–{MAX_CANDIDATES} 之间")

    ordered_cases = sorted(cases, key=lambda case: case.id)
    measured = runs > 0
    results: list[CaseResult] = []
    pairs: list[tuple[float, int | None]] = []

    for case in ordered_cases:
        ranking: list[str] = []
        if not measured:
            ranking = scorer.rank(case)
        else:
            for index in range(runs):
                started_at = time.perf_counter_ns()
                ranking = scorer.rank(case)
                elapsed_ms = (time.perf_counter_ns() - started_at) / 1_000_000.0
                if index >= warmup:
                    pairs.append((elapsed_ms, scorer.model_latency_ms()))

        top_ranking = tuple(ranking[:top_k])
        results.append(
            CaseResult(
                id=case.id,
                ndcg=ndcg_at_k(top_ranking, case.labels, top_k),
                support=support_rate(top_ranking, case.supports, top_k),
                ranking=top_ranking,
                no_positive_labels=not has_positive_labels(case.labels),
            )
        )

    ndcg_total = 0.0
    support_total = 0.0
    for result in results:
        ndcg_total += result.ndcg
        support_total += result.support
    count = len(results)
    latency = summarize_latency(measured=measured, runs=runs, warmup=warmup, pairs=pairs)
    return EvalReport(
        scorer=scorer.name,
        top_k=top_k,
        cases=tuple(results),
        ndcg=ndcg_total / count,
        support=support_total / count,
        latency=latency,
        candidate_target=candidate_target,
    )


def summarize_latency(
    *,
    measured: bool,
    runs: int,
    warmup: int,
    pairs: Sequence[tuple[float, int | None]],
) -> LatencySummary:
    """把逐样本 ``(total_ms, model_latency_ms|None)`` 汇总成 W3.1 三口径摘要。

    - ``total`` 分位数取全部样本；``model`` 分位数只取已知样本；
    - ``overhead = max(0, total - model)`` 只在 ``model`` 已知时产生样本（时钟噪声导致的
      负值夹到 0，不当作负数统计）；
    - 没有已知 ``model`` 时 ``model*/overhead*`` 全为 ``None``（冻结 §1.5，不用 0 冒充）；
    - ``confidence`` 只看参与统计的 ``samples``。
    """

    totals = [total for total, _ in pairs]
    known = [(total, model) for total, model in pairs if model is not None]
    models = [model for _, model in known]
    overheads = [max(0.0, total - model) for total, model in known]
    samples = len(totals)
    return LatencySummary(
        measured=measured,
        runs=runs if measured else 0,
        warmup=warmup if measured else 0,
        samples=samples,
        p50_ms=p50(totals) if totals else None,
        p95_ms=p95(totals) if totals else None,
        p99_ms=p99(totals) if totals else None,
        total_p95_ms=p95(totals) if totals else None,
        total_p99_ms=p99(totals) if totals else None,
        model_known_samples=len(models),
        model_p95_ms=p95(models) if models else None,
        model_p99_ms=p99(models) if models else None,
        overhead_p95_ms=p95(overheads) if overheads else None,
        overhead_p99_ms=p99(overheads) if overheads else None,
        confidence=confidence_for_samples(samples),
    )


def report_document(report: EvalReport) -> dict[str, Any]:
    """把 :class:`EvalReport` 转成待 canonical 化的 JSON 对象（未含 SHA-256）。"""

    ndcg_key = f"ndcgAt{report.top_k}"
    cases: list[dict[str, Any]] = [
        {
            "id": result.id,
            ndcg_key: result.ndcg,
            "supportRate": result.support,
            "ranking": list(result.ranking),
            "noPositiveLabels": result.no_positive_labels,
        }
        for result in report.cases
    ]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scorer": report.scorer,
        "topK": report.top_k,
        "cases": cases,
        "aggregate": {
            "cases": len(report.cases),
            ndcg_key: report.ndcg,
            "supportRate": report.support,
            # p95Ms 是兼容别名，与 totalP95Ms 同值（W3.1 冻结 §1.5）。
            "p95Ms": report.latency.p95_ms,
            "totalP50Ms": report.latency.p50_ms,
            "totalP95Ms": report.latency.p95_ms,
            "totalP99Ms": report.latency.p99_ms,
            "overheadP95Ms": report.latency.overhead_p95_ms,
            "overheadP99Ms": report.latency.overhead_p99_ms,
            "modelP95Ms": report.latency.model_p95_ms,
            "modelP99Ms": report.latency.model_p99_ms,
            "samples": report.latency.samples,
            "modelKnownSamples": report.latency.model_known_samples,
            "confidence": report.latency.confidence,
            "candidateTarget": report.candidate_target,
        },
        "latency": {
            "measured": report.latency.measured,
            "runs": report.latency.runs,
            "warmup": report.latency.warmup,
            "samples": report.latency.samples,
            "p95Ms": report.latency.p95_ms,
        },
    }


def seal_report(document: Mapping[str, Any]) -> dict[str, Any]:
    """追加 ``sha256``：摘要是 ``canonical(document 去掉 sha256 键)`` 的 SHA-256。

    这样``sha256``本身不进入被摘要的字节（否则自引用），而同一输入两次运行的字节与
    摘要都稳定；复算方式见 ``tests/fixtures/rerank_set/README.md``。
    """

    payload = {key: value for key, value in document.items() if key != "sha256"}
    sealed = dict(payload)
    sealed["sha256"] = digest(canonicalize_document(payload))
    return sealed


def canonical_report_bytes(document: Mapping[str, Any]) -> bytes:
    """``seal_report`` + canonical 编码：CLI ``--json`` 输出的精确字节。"""

    return canonicalize_document(seal_report(document))
