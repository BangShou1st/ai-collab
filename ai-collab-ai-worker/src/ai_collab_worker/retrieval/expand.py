"""句子窗口扩展与父子块回读扩展器（对照 Java SentenceWindowExpander / ParentChildExpander）。

硬约束：
- 严格按 ``(documentId, bodySnapshotId, heading)`` 分区，绝不跨文档、跨版本、跨章节混合；
- 绝不修改候选原始的 ``content``、``contentHash``、``chunkNo`` 与 ``sourceRef``；
- 所有扩展用到的 DB 行（章节父块/相邻块/父块）均记入 ``context_refs``（供 Java §6 逐行复核）；
- 表格与代码块跳过句子窗口扩展（保留原文）。
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, replace
from functools import cmp_to_key
from typing import Final

from ai_collab_worker.retrieval.candidate import (
    Candidate,
    RowRef,
    compare_keys,
    java_double_compare,
)
from ai_collab_worker.retrieval.javatext import is_blank, trim
from ai_collab_worker.retrieval.store import RetrievalStore, SectionChunk

DEFAULT_WINDOW_SIZE: Final[int] = 1
MIN_WINDOW_SIZE: Final[int] = 1
MAX_WINDOW_SIZE: Final[int] = 3
DEFAULT_MAX_PARENT_CHARS: Final[int] = 2000
MAX_STITCH_OVERLAP: Final[int] = 300

_TABLE_DIVIDER_RE: Final[re.Pattern[str]] = re.compile(r"^\|[\s\-:\|]+\|$")
_OPEN_QUOTES: Final[frozenset[str]] = frozenset("“‘「『")
_CLOSE_QUOTES: Final[frozenset[str]] = frozenset("”’」』")
_OPEN_BRACKETS: Final[frozenset[str]] = frozenset("([（【《{")
_CLOSE_BRACKETS: Final[frozenset[str]] = frozenset(")]）】》}")
_SENTENCE_END_PUNCT: Final[frozenset[str]] = frozenset("。！？；!?;")


@dataclass(frozen=True)
class SentenceSpan:
    index: int
    start: int
    end: int
    text: str


@dataclass(frozen=True)
class _CandidateWithSpan:
    candidate: Candidate
    sentences: tuple[SentenceSpan, ...]
    window_start: int
    window_end: int
    section_refs: tuple[RowRef, ...]


def is_table_or_code_block(candidate: Candidate) -> bool:
    """判定是否为表格或代码块：是则跳过句子窗口扩展。"""

    meta = candidate.metadata
    if meta:
        for flag in ("isTable", "isCode"):
            val = meta.get(flag)
            if val is True or str(val).lower() == "true":
                return True
        chunk_type = str(meta.get("chunkType", "")).upper()
        if chunk_type in {"TABLE", "CODE"}:
            return True

    stripped = trim(candidate.content)
    if stripped.startswith("```") or "\n```" in stripped:
        return True

    lines = re.split(r"\r?\n", stripped)
    divider_count = 0
    table_row_candidates = 0
    for line in lines:
        trimmed = line.strip()
        if trimmed.startswith("|") and trimmed.endswith("|"):
            table_row_candidates += 1
            if _TABLE_DIVIDER_RE.match(trimmed):
                divider_count += 1
    return divider_count >= 1 or (
        len(lines) >= 2 and table_row_candidates >= 2 and table_row_candidates == len(lines)
    )


def split_sentences(text: str | None) -> list[SentenceSpan]:
    """按中英文句末标点与换行切句，保护成对引号、括号及小数点不被切断。"""

    if is_blank(text):
        return []
    assert text is not None
    spans: list[SentenceSpan] = []
    length = len(text)
    start = 0
    quote_depth = 0
    bracket_depth = 0
    in_ascii_double_quote = False

    i = 0
    while i < length:
        ch = text[i]
        if ch in _OPEN_QUOTES:
            quote_depth += 1
        elif ch in _CLOSE_QUOTES:
            quote_depth = max(0, quote_depth - 1)
        elif ch == '"':
            if in_ascii_double_quote:
                in_ascii_double_quote = False
                quote_depth = max(0, quote_depth - 1)
            else:
                in_ascii_double_quote = True
                quote_depth += 1
        elif ch in _OPEN_BRACKETS:
            bracket_depth += 1
        elif ch in _CLOSE_BRACKETS:
            bracket_depth = max(0, bracket_depth - 1)

        is_boundary = False
        if ch in _SENTENCE_END_PUNCT:
            is_boundary = True
        elif ch == ".":
            is_decimal = (
                i > 0 and text[i - 1].isdigit() and i + 1 < length and text[i + 1].isdigit()
            )
            if not is_decimal and (
                i + 1 == length
                or text[i + 1].isspace()
                or text[i + 1] in _CLOSE_QUOTES
                or text[i + 1] in _CLOSE_BRACKETS
                or text[i + 1] == '"'
            ):
                is_boundary = True
        elif ch == "\n" and bracket_depth == 0 and quote_depth == 0:
            is_boundary = True

        if is_boundary:
            end = i + 1
            while end < length:
                next_ch = text[end]
                if next_ch in _CLOSE_QUOTES or next_ch in _CLOSE_BRACKETS:
                    if next_ch in _CLOSE_QUOTES:
                        quote_depth = max(0, quote_depth - 1)
                    else:
                        bracket_depth = max(0, bracket_depth - 1)
                    end += 1
                elif next_ch == '"' and in_ascii_double_quote:
                    in_ascii_double_quote = False
                    quote_depth = max(0, quote_depth - 1)
                    end += 1
                else:
                    break

            if quote_depth == 0 and bracket_depth == 0:
                piece = trim(text[start:end])
                if piece:
                    spans.append(SentenceSpan(len(spans), start, end, piece))
                while end < length and text[end].isspace():
                    end += 1
                start = end
                i = end - 1
        i += 1

    if start < length:
        remaining = trim(text[start:length])
        if remaining:
            spans.append(SentenceSpan(len(spans), start, length, remaining))
    return spans


def stitch_chunks(contents: Sequence[str]) -> str:
    """将相邻章节分块平滑拼接，合并最多 300 字符的重叠前缀。"""

    non_empty = [trim(item) for item in contents if not is_blank(item)]
    if not non_empty:
        return ""
    if len(non_empty) == 1:
        return non_empty[0]

    buf = non_empty[0]
    for piece in non_empty[1:]:
        max_overlap = min(len(buf), len(piece), MAX_STITCH_OVERLAP)
        overlap = 0
        for overlap_len in range(max_overlap, 0, -1):
            if buf.endswith(piece[:overlap_len]):
                overlap = overlap_len
                break
        if overlap > 0:
            buf += piece[overlap:]
        else:
            buf += "\n" + piece
    return buf


def truncate_prose(prose: str, max_chars: int = DEFAULT_MAX_PARENT_CHARS) -> str:
    if len(prose) <= max_chars:
        return prose
    return prose[:max_chars] + "..."


def _dedupe_refs(refs: Sequence[RowRef]) -> tuple[RowRef, ...]:
    seen: dict[int, RowRef] = {}
    for ref in refs:
        if ref.chunk_no not in seen:
            seen[ref.chunk_no] = ref
    return tuple(seen.values())


def _find_sentence_range(content: str, sentences: Sequence[SentenceSpan]) -> tuple[int, int]:
    stripped = trim(content)
    first = -1
    last = -1
    for idx, span in enumerate(sentences):
        if span.text in stripped or stripped in span.text:
            if first == -1:
                first = idx
            last = idx
    if first != -1:
        return first, last

    max_overlap = 0
    best_idx = 0
    for idx, span in enumerate(sentences):
        limit = min(len(stripped), len(span.text))
        overlap = sum(1 for pos in range(limit) if stripped[pos] == span.text[pos])
        if overlap > max_overlap:
            max_overlap = overlap
            best_idx = idx
    return best_idx, best_idx


def _candidate_score(candidate: Candidate) -> float:
    return candidate.rerank if candidate.rerank is not None else candidate.rrf


def _score_order(left: Candidate, right: Candidate) -> int:
    cmp = java_double_compare(_candidate_score(right), _candidate_score(left))
    return cmp if cmp != 0 else compare_keys(left, right)


def _span_score_order(left: _CandidateWithSpan, right: _CandidateWithSpan) -> int:
    return _score_order(left.candidate, right.candidate)


async def expand_sentence_windows(
    *,
    store: RetrievalStore,
    generation_id: uuid.UUID,
    project_id: uuid.UUID,
    candidates: Sequence[Candidate],
    window_size: int = DEFAULT_WINDOW_SIZE,
) -> list[Candidate]:
    """执行句子窗口扩展（默认前后各 1 句），同章节重叠窗口自动合并并记录 ``context_refs``。"""

    if not candidates:
        return []
    bounded = max(MIN_WINDOW_SIZE, min(MAX_WINDOW_SIZE, window_size))

    section_cache: dict[
        tuple[uuid.UUID, uuid.UUID, str | None],
        list[SectionChunk],
    ] = {}
    partitions: dict[tuple[uuid.UUID, uuid.UUID, str, int | None], list[_CandidateWithSpan]] = {}
    skipped: list[Candidate] = []

    for candidate in candidates:
        if is_table_or_code_block(candidate):
            skipped.append(candidate)
            continue

        sec_key = (candidate.document_id, candidate.body_snapshot_id, candidate.heading)
        if sec_key not in section_cache:
            section_cache[sec_key] = await store.fetch_section_chunks(
                generation_id=generation_id,
                project_id=project_id,
                document_id=candidate.document_id,
                body_snapshot_id=candidate.body_snapshot_id,
                heading=candidate.heading,
            )
        sec_chunks = section_cache[sec_key]
        if sec_chunks:
            section_text = stitch_chunks([sc.content for sc in sec_chunks])
            sec_refs = _dedupe_refs(
                [RowRef(sc.chunk_no, sc.content_hash.lower()) for sc in sec_chunks]
            )
            partition_scope: int | None = None
        else:
            # 未查到章节/父块上下文时，各分块只拥有局部文本坐标，严禁按同一章节合并吞并彼此。
            section_text = candidate.content
            sec_refs = (RowRef(candidate.chunk_no, candidate.content_hash.lower()),)
            partition_scope = candidate.chunk_no

        sentences = tuple(split_sentences(section_text))
        if not sentences:
            skipped.append(candidate)
            continue

        matched_start, matched_end = _find_sentence_range(candidate.content, sentences)
        win_start = max(0, matched_start - bounded)
        win_end = min(len(sentences) - 1, matched_end + bounded)
        part_key = (
            candidate.document_id,
            candidate.body_snapshot_id,
            candidate.heading if candidate.heading is not None else "ROOT",
            partition_scope,
        )
        partitions.setdefault(part_key, []).append(
            _CandidateWithSpan(
                candidate=candidate,
                sentences=sentences,
                window_start=win_start,
                window_end=win_end,
                section_refs=sec_refs,
            )
        )

    expanded_result: list[Candidate] = []
    for group in partitions.values():
        group.sort(key=lambda entry: (entry.window_start, entry.candidate.chunk_no))
        i = 0
        while i < len(group):
            current = group[i]
            merged_start = current.window_start
            merged_end = current.window_end
            overlapping = [current]
            j = i + 1
            while j < len(group):
                nxt = group[j]
                if nxt.window_start <= merged_end:
                    merged_end = max(merged_end, nxt.window_end)
                    overlapping.append(nxt)
                    j += 1
                else:
                    break

            overlapping.sort(key=cmp_to_key(_span_score_order))
            primary = overlapping[0]
            expanded_text = " ".join(
                span.text for span in primary.sentences[merged_start : merged_end + 1]
            )
            merged_refs: list[RowRef] = [
                RowRef(item.candidate.chunk_no, item.candidate.content_hash.lower())
                for item in overlapping
            ]
            merged_refs.extend(primary.section_refs)
            expanded_result.append(
                replace(
                    primary.candidate,
                    expanded=expanded_text,
                    context_refs=_dedupe_refs(merged_refs),
                )
            )
            i = j

    expanded_result.extend(skipped)
    expanded_result.sort(key=cmp_to_key(_score_order))
    return expanded_result


async def expand_parent_child(
    *,
    store: RetrievalStore,
    generation_id: uuid.UUID,
    project_id: uuid.UUID,
    candidates: Sequence[Candidate],
    max_parent_chars: int = DEFAULT_MAX_PARENT_CHARS,
) -> list[Candidate]:
    """对存在 ``parent_chunk_no`` 的候选批量回读同文档同快照的父块，填充上下文与引用。"""

    if not candidates:
        return []
    targets: list[tuple[uuid.UUID, int]] = []
    seen_targets: set[tuple[uuid.UUID, int]] = set()
    for candidate in candidates:
        if candidate.parent_chunk_no is None:
            continue
        key = (candidate.document_id, candidate.parent_chunk_no)
        if key not in seen_targets:
            seen_targets.add(key)
            targets.append(key)

    if not targets:
        return list(candidates)

    parents = await store.fetch_parent_chunks(
        generation_id=generation_id,
        project_id=project_id,
        targets=targets,
    )

    output: list[Candidate] = []
    for candidate in candidates:
        if candidate.parent_chunk_no is None:
            output.append(candidate)
            continue
        parent = parents.get((candidate.document_id, candidate.parent_chunk_no))
        if parent is None:
            output.append(candidate)
            continue
        # 严格校验同文档、同版本快照（防止混读）
        if (
            parent.document_id != candidate.document_id
            or parent.body_snapshot_id != candidate.body_snapshot_id
            or is_blank(parent.content)
        ):
            output.append(candidate)
            continue
        bounded = truncate_prose(parent.content, max_parent_chars)
        parent_ref = RowRef(parent.chunk_no, parent.content_hash.lower())
        merged_refs = _dedupe_refs([*candidate.context_refs, parent_ref])
        output.append(
            replace(
                candidate,
                parent_context=bounded,
                context_refs=merged_refs,
            )
        )
    return output


__all__ = [
    "DEFAULT_MAX_PARENT_CHARS",
    "DEFAULT_WINDOW_SIZE",
    "SentenceSpan",
    "expand_parent_child",
    "expand_sentence_windows",
    "is_table_or_code_block",
    "split_sentences",
    "stitch_chunks",
    "truncate_prose",
]
