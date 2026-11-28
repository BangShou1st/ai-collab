"""``KnowledgeCitationValidator`` 与 ``FactEntityCoverageChecker`` 的逐字节移植。

P3 契约 §2.1 行 9：引用校验与生成同侧；``INSUFFICIENT_ANSWER`` 文案是落库/前端契约，
逐字不变。全部行为（``[Sn]`` 对照、组内剔除、短正文告警、实体覆盖检测）钉在
``p3_safety_golden.v1.jsonl`` 的 citation 样本里，含"字符串 bodySnapshotId 视为
无效身份"。

移植口径：Java ``\\s``/``\\d``/``\\b`` 均按 ASCII 复刻（环视显式表达）；``String.trim``
用 :func:`javatext.trim`；``String.length`` 用 :func:`javatext.utf16_length`；
``String.format("%.0f")`` 的 HALF_UP 语义手工进位（Python 内建为银行家舍入）。
"""

from __future__ import annotations

import math
import re
import uuid
from dataclasses import dataclass, field
from typing import Final

from ai_collab_worker.qa.assembly import KnowledgeSource
from ai_collab_worker.qa.constants import INSUFFICIENT_ANSWER, MIN_SOURCE_CONTENT_LENGTH
from ai_collab_worker.retrieval.javatext import is_blank, trim, utf16_length

_S: Final[str] = r"[ \t\n\x0b\f\r]"

CITATION: Final[re.Pattern[str]] = re.compile(r"\[(S[0-9]+(?:" + _S + r"*," + _S + r"*S[0-9]+)*)]")


@dataclass(frozen=True, slots=True)
class ValidatedKnowledgeAnswer:
    """Java ``ValidatedKnowledgeAnswer`` record。"""

    answer: str
    insufficient_evidence: bool
    cited_sources: tuple[KnowledgeSource, ...]
    invalid_citation_count: int
    invalid_output: bool
    unsupported_claims: tuple[str, ...] = field(default=())


class FactEntityCoverageChecker:
    """轻量级事实实体覆盖检测（Java ``FactEntityCoverageChecker`` 的逐行移植）。

    提取回答句子中的数值实体、引号实体与强断言，核验对应引用语料中是否有依据；
    纯内存计算，产出告警字符串（不改变判定结果）。
    """

    _SENTENCE_SPLIT: Final = re.compile(r"[^。！？\n;；]+[。！？\n;；]?")
    _CITATION_IN_SENTENCE: Final = re.compile(r"\[(S[0-9]+(?:" + _S + r"*," + _S + r"*S[0-9]+)*)]")
    # Java ``\b\d+(?:\.\d+)?%\b``：ASCII 词界显式化（% 后的 \b 要求下一字符是 ASCII 词字符）。
    _NUMBER_ENTITY: Final = re.compile(r"(?<![a-zA-Z0-9_])[0-9]+(?:\.[0-9]+)?%(?=[a-zA-Z0-9_])")
    _QUOTED: Final = re.compile(r"[“\"'《]([^“”\"'》]{2,30})[”\"'》]")
    _STRONG_CLAIM: Final = re.compile(
        r".*(?:必须|严禁|绝对|永远|禁止|确保|100%|所有|全部|唯一|总计|完全|承诺"
        r"|指标为|端口为|密码为|密钥为).*"
    )
    _KEEP_FOR_TERMS: Final = re.compile(r"[^\u4e00-\u9fa5a-zA-Z0-9]")
    _MULTI_SPACE: Final = re.compile(_S + "+")
    _STRIP_CITATIONS: Final = re.compile(r"\[S[0-9]+(?:" + _S + r"*," + _S + r"*S[0-9]+)*]")

    _STOP_WORDS: Final[frozenset[str]] = frozenset(
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
            "根据",
            "文档",
            "资料",
            "项目",
            "说明",
            "显示",
            "提到",
            "指出",
            "参见",
            "可以",
            "能够",
        }
    )

    def check(self, answer: str | None, cited_sources: list[KnowledgeSource]) -> list[str]:
        if not answer or is_blank(answer) or not cited_sources:
            return []

        source_corpus_by_rank: dict[int, str] = {}
        global_parts: list[str] = []
        for source in cited_sources:
            text = (
                (source.heading if source.heading is not None else "")
                + " "
                + (source.content if source.content is not None else "")
            )
            lowered = text.lower()
            source_corpus_by_rank[source.rank] = lowered
            global_parts.append(lowered)
        global_corpus = "".join(part + " " for part in global_parts)

        warnings: list[str] = []
        for sentence_match in self._SENTENCE_SPLIT.finditer(answer):
            sentence = trim(sentence_match.group())
            if utf16_length(sentence) < 4:
                continue

            sentence_ranks = self._extract_citation_ranks(sentence)
            if sentence_ranks:
                evidence_scope = "".join(
                    (source_corpus_by_rank.get(rank) or "") + " " for rank in sentence_ranks
                )
            else:
                evidence_scope = global_corpus

            clean_sentence = trim(self._STRIP_CITATIONS.sub("", sentence))
            if is_blank(clean_sentence):
                continue

            # 1. 数值与比率实体检测
            for num_match in self._NUMBER_ENTITY.finditer(clean_sentence):
                num = num_match.group()
                if utf16_length(num) >= 2 and num.lower() not in evidence_scope:
                    warnings.append(
                        f"句子 '{truncate(clean_sentence, 40)}' 中包含的关键数值/指标 '{num}'"
                        " 在所引用的事实资料中未找到依据"
                    )

            # 2. 引号内特定实体或专有名称核验
            for quote_match in self._QUOTED.finditer(clean_sentence):
                term = trim(quote_match.group(1)).lower()
                if utf16_length(term) >= 3 and term not in evidence_scope:
                    warnings.append(
                        f"句子 '{truncate(clean_sentence, 40)}' 中断言的专有名词 '{term}'"
                        " 在所引用的事实资料中未找到依据"
                    )

            # 3. 强断言语句核验
            if self._STRONG_CLAIM.fullmatch(clean_sentence):
                terms = self._extract_significant_terms(clean_sentence)
                matched_count = sum(1 for term in terms if term in evidence_scope)
                if terms and matched_count / len(terms) < 0.25:
                    coverage = _format_percent(matched_count / len(terms) * 100)
                    warnings.append(
                        f"句子 '{truncate(clean_sentence, 40)}' 作出了高确信度强断言，"
                        f"但与引用资料事实匹配度过低 (覆盖率 {coverage}%)"
                    )
        return warnings

    def _extract_citation_ranks(self, sentence: str) -> set[int]:
        ranks: set[int] = set()
        for match in self._CITATION_IN_SENTENCE.finditer(sentence):
            for part in match.group(1).split(","):
                candidate = part.strip()
                if candidate.startswith("S"):
                    try:
                        ranks.add(int(candidate[1:]))
                    except ValueError:
                        continue
        return ranks

    def _extract_significant_terms(self, sentence: str) -> list[str]:
        cleaned = self._KEEP_FOR_TERMS.sub(" ", sentence)
        terms: list[str] = []
        for token in self._MULTI_SPACE.split(cleaned):
            lowered = token.strip().lower()
            if len(lowered) >= 2 and lowered not in self._STOP_WORDS:
                terms.append(lowered)
        return terms


def truncate(text: str | None, max_len: int) -> str:
    """Java 私有 ``truncate``：按 ``String.length``（UTF-16 单位）截断后补 ``...``。"""

    if text is None:
        return ""
    if len(text.encode("utf-16-le")) // 2 <= max_len:
        return text
    return _utf16_prefix(text, max_len) + "..."


def _utf16_prefix(text: str, units: int) -> str:
    collected: list[str] = []
    used = 0
    for char in text:
        width = 2 if ord(char) > 0xFFFF else 1
        if used + width > units:
            break
        collected.append(char)
        used += width
    return "".join(collected)


def _format_percent(value: float) -> str:
    """Java ``String.format("%.0f")`` 的 HALF_UP 形态（Python 内建为银行家舍入）。"""

    return f"{math.floor(value + 0.5)}"


class KnowledgeCitationValidator:
    """引用校验器（Java ``KnowledgeCitationValidator``）。"""

    def __init__(self, fact_coverage_checker: FactEntityCoverageChecker | None = None) -> None:
        self._fact_coverage_checker = fact_coverage_checker or FactEntityCoverageChecker()

    def validate(
        self,
        answer: str | None,
        sources: list[KnowledgeSource],
        expected_project_id: uuid.UUID | None = None,
    ) -> ValidatedKnowledgeAnswer:
        if answer is None or not answer.strip():
            return _insufficient(0, True)
        normalized_answer = answer.strip()
        if normalized_answer == INSUFFICIENT_ANSWER:
            return _insufficient(0, False)

        by_rank = {source.rank: source for source in sources}
        invalid = 0
        valid_ranks: list[int] = []
        cleaned_parts: list[str] = []
        last_end = 0

        for match in CITATION.finditer(normalized_answer):
            group = match.group()
            members = group[1:-1].split(",")
            valid_members: list[int] = []
            for member in members:
                rank = _parse_rank(member)
                source = by_rank.get(rank)
                if source is not None and _is_identity_valid(source, expected_project_id):
                    if rank not in valid_ranks:
                        valid_ranks.append(rank)
                    valid_members.append(rank)
                else:
                    invalid += 1
            cleaned_parts.append(normalized_answer[last_end : match.start()])
            if not valid_members:
                cleaned_parts.append("")
            elif len(valid_members) == 1 and len(members) == 1:
                cleaned_parts.append(f"[S{valid_members[0]}]")
            elif len(valid_members) == len(members):
                cleaned_parts.append(group)
            else:
                survivors = ", ".join(f"S{rank}" for rank in valid_members)
                cleaned_parts.append(f"[{survivors}]")
            last_end = match.end()
        cleaned_parts.append(normalized_answer[last_end:])

        cleaned_answer = re.sub("[ \t]+\\n", "\n", "".join(cleaned_parts)).strip()

        cited: list[KnowledgeSource] = []
        unsupported_claims: list[str] = []
        for rank in valid_ranks:
            source = by_rank[rank]
            cited.append(source)
            content = source.content or ""
            if len(content.strip()) < MIN_SOURCE_CONTENT_LENGTH:
                short_text = "null" if source.content is None else content.strip()
                unsupported_claims.append(
                    f"引用来源 [S{rank}] 正文内容过短截断 ({len(short_text)} 字符): '{short_text}'"
                )

        if not cleaned_answer or not valid_ranks:
            return _insufficient(invalid, True)

        unsupported_claims.extend(self._fact_coverage_checker.check(cleaned_answer, cited))

        return ValidatedKnowledgeAnswer(
            cleaned_answer,
            False,
            tuple(cited),
            invalid,
            False,
            tuple(unsupported_claims),
        )


def _is_identity_valid(
    source: KnowledgeSource | None, expected_project_id: uuid.UUID | None
) -> bool:
    if source is None or source.document_id is None or source.chunk_id is None:
        return False
    if source.chunk_no is not None and (
        source.chunk_no < 0 or source.body_snapshot_id is None or not source.content_hash
    ):
        return False
    if expected_project_id is not None:
        meta_project_id = source.metadata.get("projectId")
        if (
            meta_project_id is not None
            and str(expected_project_id).lower() != str(meta_project_id).lower()
        ):
            return False
    return True


def _parse_rank(member: str) -> int:
    value = member.strip()
    if not value.startswith("S"):
        return -1
    try:
        return int(value[1:])
    except ValueError:
        return -1


def _insufficient(invalid: int, invalid_output: bool) -> ValidatedKnowledgeAnswer:
    return ValidatedKnowledgeAnswer(INSUFFICIENT_ANSWER, True, (), invalid, invalid_output, ())


__all__ = [
    "FactEntityCoverageChecker",
    "KnowledgeCitationValidator",
    "ValidatedKnowledgeAnswer",
]
