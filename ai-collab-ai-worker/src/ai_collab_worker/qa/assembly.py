"""``KnowledgeContextBuilder.buildWithTokenBudget`` 与 ``TokenEstimator`` 的等价移植。

P3 契约 §2.1 行 6：证据装配与生成同侧；阈值/预算/截断全部被金标准
``p3_context_golden.v1.jsonl`` 钉住，任何一行漂移都会在对拍测试里显形。

移植范围说明：

- 只移植生产 QA 入口 ``buildWithTokenBudget``（同文档哈希去重、句子块抑制容器块、
  每文档名额两遍重排、精确 token 预算、完整丢弃原因诊断）；
- ``TokenEstimator``（``CJK_WEIGHTED_V1``）：``ascii×0.25 + cjk×0.75 + other×0.5``，
  ``Math.round`` 语义（半值向 +∞ 进位，与 Python 内建 round 的银行家舍入不同）；
- Java ``Character.UnicodeBlock`` 的 CJK 判定用 Unicode 区段区间复刻（CJK 统一表意
  文字 4E00–9FFF、扩展 A 3400–4DBF、扩展 B 20000–2A6DF）。
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Final

from ai_collab_worker.qa.constants import (
    DEFAULT_MAX_SOURCES,
    MAX_PROMPT_FILENAME_CODE_POINTS,
    MAX_PROMPT_HEADING_CODE_POINTS,
    MAX_PROMPT_SOURCE_CODE_POINTS,
    MAX_SOURCES_LIMIT,
    MIN_SIMILARITY,
    MIN_SOURCE_BODY_CODE_POINTS,
    PARENT_SNIPPET_ESCAPE_LIMIT,
    PER_DOCUMENT_SOURCE_CAP,
    SOURCE_BODY_ESCAPE_LIMIT,
)
from ai_collab_worker.qa.prompt_text import escape_xml_text_bounded

# --------------------------------------------------------------------------- #
# TokenEstimator（CJK_WEIGHTED_V1）
# --------------------------------------------------------------------------- #

_TOKENS_PER_ASCII_CHAR: Final[float] = 0.25
_TOKENS_PER_CJK_CHAR: Final[float] = 0.75
_TOKENS_PER_OTHER_CHAR: Final[float] = 0.5

#: CJK 统一表意文字及扩展 A/B 区（与 Java ``TokenEstimator.isCjk`` 等价）。
_CJK_RANGES: Final[tuple[tuple[int, int], ...]] = (
    (0x4E00, 0x9FFF),  # CJK_UNIFIED_IDEOGRAPHS
    (0x3400, 0x4DBF),  # CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    (0x20000, 0x2A6DF),  # CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
)


def _is_ascii_text(code_point: int) -> bool:
    """ASCII 可打印字符与 6 个 ASCII 空白（Java ``isAsciiText``）。"""

    return (0x20 <= code_point < 0x7F) or (0x09 <= code_point <= 0x0D)


def _is_cjk(code_point: int) -> bool:
    return any(low <= code_point <= high for low, high in _CJK_RANGES)


def estimate_tokens(text: str | None) -> int:
    """估算文本 token 数（Java ``TokenEstimator.estimate``，``Math.round`` 半值进位）。"""

    if not text:
        return 0
    total = 0.0
    for char in text:
        code_point = ord(char)
        if _is_cjk(code_point):
            total += _TOKENS_PER_CJK_CHAR
        elif _is_ascii_text(code_point):
            total += _TOKENS_PER_ASCII_CHAR
        else:
            total += _TOKENS_PER_OTHER_CHAR
    return math.floor(total + 0.5)


# --------------------------------------------------------------------------- #
# 输入/输出模型
# --------------------------------------------------------------------------- #


class ContextDropReason(StrEnum):
    """候选被丢弃的具体原因（Java ``ContextDropReason``，wire name 一致）。"""

    BELOW_SIMILARITY_THRESHOLD = "BELOW_SIMILARITY_THRESHOLD"
    EMPTY_BODY = "EMPTY_BODY"
    INTRA_DOCUMENT_DUPLICATE = "INTRA_DOCUMENT_DUPLICATE"
    OVERLAPPING_CHUNK = "OVERLAPPING_CHUNK"
    CROSS_DOCUMENT_DUPLICATE = "CROSS_DOCUMENT_DUPLICATE"
    SOURCE_CAP = "SOURCE_CAP"
    BODY_BUDGET = "BODY_BUDGET"
    TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED"
    UNRESOLVED_VERSION = "UNRESOLVED_VERSION"
    FAILED_FILTER = "FAILED_FILTER"
    UNATTRIBUTED = "UNATTRIBUTED"


@dataclass(frozen=True, slots=True)
class CandidateHit:
    """装配输入候选（对应 Java ``DocumentSearchHit``；身份 = rowId + 引用身份字段）。"""

    id: uuid.UUID
    document_id: uuid.UUID
    original_filename: str
    heading: str
    content: str
    content_hash: str
    similarity: float
    metadata: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class KnowledgeSource:
    """装配产出的一条来源（Java ``KnowledgeSource`` record）。"""

    chunk_id: uuid.UUID
    document_id: uuid.UUID
    original_filename: str
    heading: str
    content: str
    content_hash: str
    similarity: float
    rank: int
    metadata: dict[str, object] = field(default_factory=dict)

    @property
    def chunk_no(self) -> int | None:
        value = self.metadata.get("chunkNo")
        return value if isinstance(value, int) else None

    @property
    def body_snapshot_id(self) -> uuid.UUID | None:
        value = self.metadata.get("bodySnapshotId")
        return value if isinstance(value, uuid.UUID) else None


@dataclass(frozen=True, slots=True)
class KnowledgeContext:
    """装配结果：来源列表 + 提示词证据块文本（Java ``KnowledgeContext``）。"""

    sources: tuple[KnowledgeSource, ...]
    prompt_sources: str


@dataclass(frozen=True, slots=True)
class BuildResult:
    """装配结果 + 每个被丢弃候选的原因（Java ``BuildResult``）。"""

    context: KnowledgeContext
    drop_reasons: dict[uuid.UUID, ContextDropReason]


# --------------------------------------------------------------------------- #
# 装配器
# --------------------------------------------------------------------------- #

_META_CHUNK_TYPE: Final[str] = "chunkType"
_META_PARENT_CHUNK_ID: Final[str] = "parentChunkId"
_META_PARENT_CHUNK_NO: Final[str] = "parentChunkNo"
_META_CHUNK_NO: Final[str] = "chunkNo"
_CHUNK_TYPE_SENTENCE: Final[str] = "SENTENCE"


class KnowledgeContextBuilder:
    """生产 QA 证据装配器（Java ``KnowledgeContextBuilder`` 的 buildWithTokenBudget 路径）。"""

    def build_with_token_budget(
        self,
        hits: list[CandidateHit],
        max_sources: int,
        token_budget: int,
        token_counter: object | None = None,
        provider: str | None = None,
        model: str | None = None,
    ) -> BuildResult:
        """候选顺序装配：每文档名额均衡 → 句子抑制 → 阈值/去重/预算淘汰 → 提示词块。

        ``token_counter`` 为 ``None`` 或缺 ``count_text`` 时退回估算器（与 Java
        ``tokenCounter != null ? tokenCounter : TokenEstimator`` 分支一致；当前唯一实现
        ``ModelAwareTokenCounter`` 的数值即估算器，见其 javadoc）。
        """

        _validate_max_sources(max_sources)
        counter = token_counter if _has_count_text(token_counter) else None
        ordered = _reorder_per_document_cap(hits, max_sources, PER_DOCUMENT_SOURCE_CAP)
        sources: list[KnowledgeSource] = []
        retained_chunk_ids: set[uuid.UUID] = set()
        hashes_by_document: dict[uuid.UUID, set[str]] = {}
        dropped: dict[uuid.UUID, ContextDropReason] = {}
        ordered = _suppress_container_chunks_with_selected_sentences(ordered, dropped)
        prompt_parts: list[str] = []

        used_tokens = 0
        max_tokens = token_budget if token_budget > 0 else 2500

        for hit in ordered:
            if hit.similarity < MIN_SIMILARITY:
                dropped[hit.id] = ContextDropReason.BELOW_SIMILARITY_THRESHOLD
                continue
            if len(sources) >= max_sources:
                dropped[hit.id] = ContextDropReason.SOURCE_CAP
                continue
            document_hashes = hashes_by_document.get(hit.document_id)
            hash_duplicated = (
                hit.content_hash
                and document_hashes is not None
                and hit.content_hash in document_hashes
            )
            if hit.id in retained_chunk_ids or hash_duplicated:
                dropped[hit.id] = ContextDropReason.INTRA_DOCUMENT_DUPLICATE
                continue
            if not hit.content or not hit.content.strip():
                dropped[hit.id] = ContextDropReason.EMPTY_BODY
                continue

            rank = len(sources) + 1
            separator = "" if not prompt_parts else "\n\n"
            filename = escape_xml_text_bounded(
                hit.original_filename, MAX_PROMPT_FILENAME_CODE_POINTS
            ).escaped
            heading = escape_xml_text_bounded(hit.heading, MAX_PROMPT_HEADING_CODE_POINTS).escaped

            parent_context = ""
            parent_snippet = hit.metadata.get("parentSnippet")
            if (
                isinstance(parent_snippet, str)
                and parent_snippet.strip()
                and parent_snippet != hit.content
            ):
                parent_context = (
                    "【章节背景】"
                    + escape_xml_text_bounded(parent_snippet, PARENT_SNIPPET_ESCAPE_LIMIT).escaped
                    + "\n"
                )

            body_content = _resolve_effective_body_content(hit)
            source_block = (
                separator
                + f"[S{rank}]\n"
                + f"文件：{filename}\n"
                + f"标题：{heading}\n"
                + parent_context
                + "内容："
                + escape_xml_text_bounded(body_content, SOURCE_BODY_ESCAPE_LIMIT).escaped
            )

            if counter is not None:
                block_tokens = counter.count_text(source_block, provider, model).count()
            else:
                block_tokens = estimate_tokens(source_block)

            if used_tokens + block_tokens > max_tokens and sources:
                dropped[hit.id] = ContextDropReason.TOKEN_BUDGET_EXCEEDED
                continue

            prompt_parts.append(source_block)
            used_tokens += block_tokens
            sources.append(
                KnowledgeSource(
                    chunk_id=hit.id,
                    document_id=hit.document_id,
                    original_filename=hit.original_filename,
                    heading=hit.heading,
                    content=hit.content,
                    content_hash=hit.content_hash,
                    similarity=hit.similarity,
                    rank=rank,
                    metadata=hit.metadata,
                )
            )
            retained_chunk_ids.add(hit.id)
            if hit.content_hash:
                hashes_by_document.setdefault(hit.document_id, set()).add(hit.content_hash)

        return BuildResult(
            KnowledgeContext(tuple(sources), "".join(prompt_parts)),
            dict(dropped),
        )


def _has_count_text(token_counter: object | None) -> bool:
    return token_counter is not None and hasattr(token_counter, "count_text")


def _validate_max_sources(max_sources: int) -> None:
    if max_sources < 1 or max_sources > MAX_SOURCES_LIMIT:
        msg = f"maxSources 必须在 1 到 {MAX_SOURCES_LIMIT} 之间"
        raise ValueError(msg)


def _reorder_per_document_cap(
    hits: list[CandidateHit], max_sources: int, per_document_cap: int
) -> list[CandidateHit]:
    """两遍重排：第一遍每文档至多 ``cap`` 条，第二遍按候选顺序补齐剩余名额。"""

    if len(hits) <= max_sources:
        return hits
    balanced: list[CandidateHit] = []
    used_by_document: dict[uuid.UUID, int] = {}
    selected: dict[uuid.UUID, None] = {}
    for hit in hits:
        if len(balanced) == max_sources:
            break
        used = used_by_document.get(hit.document_id, 0)
        if used < per_document_cap:
            used_by_document[hit.document_id] = used + 1
            balanced.append(hit)
            selected[hit.id] = None
    for hit in hits:
        if len(balanced) == max_sources:
            break
        if hit.id not in selected:
            selected[hit.id] = None
            balanced.append(hit)
    reordered = list(balanced)
    reordered.extend(hit for hit in hits if hit.id not in selected)
    return reordered


def _suppress_container_chunks_with_selected_sentences(
    hits: list[CandidateHit], dropped: dict[uuid.UUID, ContextDropReason]
) -> list[CandidateHit]:
    """句子块命中时抑制其容器块（只依据 parentChunkId/parentChunkNo 元数据）。"""

    if len(hits) < 2:
        return hits
    parent_ids: set[uuid.UUID] = set()
    parent_keys: set[tuple[uuid.UUID, int]] = set()
    for hit in hits:
        if _metadata_text(hit, _META_CHUNK_TYPE) != _CHUNK_TYPE_SENTENCE:
            continue
        parent_id = _metadata_uuid(hit, _META_PARENT_CHUNK_ID)
        if parent_id is not None:
            parent_ids.add(parent_id)
        parent_no = hit.metadata.get(_META_PARENT_CHUNK_NO)
        if isinstance(parent_no, int):
            parent_keys.add((hit.document_id, parent_no))
    if not parent_ids and not parent_keys:
        return hits

    kept: list[CandidateHit] = []
    for hit in hits:
        parent_no = hit.metadata.get(_META_CHUNK_NO)
        suppressed = hit.id in parent_ids or (
            isinstance(parent_no, int) and (hit.document_id, parent_no) in parent_keys
        )
        if suppressed:
            dropped.setdefault(hit.id, ContextDropReason.OVERLAPPING_CHUNK)
        else:
            kept.append(hit)
    return kept


def _metadata_text(hit: CandidateHit, key: str) -> str | None:
    value = hit.metadata.get(key)
    return value if isinstance(value, str) else None


def _metadata_uuid(hit: CandidateHit, key: str) -> uuid.UUID | None:
    value = _metadata_text(hit, key)
    if not value:
        return None
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


def _resolve_effective_body_content(hit: CandidateHit) -> str:
    expanded = hit.metadata.get("expandedContent")
    if isinstance(expanded, str) and expanded.strip():
        return expanded
    return hit.content


def build_with_token_budget(
    hits: list[CandidateHit],
    max_sources: int = DEFAULT_MAX_SOURCES,
    token_budget: int = 2500,
    token_counter: object | None = None,
    provider: str | None = None,
    model: str | None = None,
) -> BuildResult:
    """模块级便捷入口（生产 QA 参数默认 5 源 / 2500 token）。"""

    return KnowledgeContextBuilder().build_with_token_budget(
        hits, max_sources, token_budget, token_counter, provider, model
    )


__all__ = [
    "DEFAULT_MAX_SOURCES",
    "MAX_PROMPT_SOURCE_CODE_POINTS",
    "MIN_SIMILARITY",
    "MIN_SOURCE_BODY_CODE_POINTS",
    "BuildResult",
    "CandidateHit",
    "ContextDropReason",
    "KnowledgeContext",
    "KnowledgeContextBuilder",
    "KnowledgeSource",
    "build_with_token_budget",
    "estimate_tokens",
]
