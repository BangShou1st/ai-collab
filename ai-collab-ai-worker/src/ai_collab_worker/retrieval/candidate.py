"""检索候选（对应 Java ``RetrievalCandidate``，身份改为 ``(documentId, chunkNo)``）。"""

from __future__ import annotations

import math
import struct
import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from types import MappingProxyType
from typing import Final

from ai_collab_worker.contract.knowledge_search import admission_score

#: 重排器名字（诊断用；与 Java ``Reranker.name()`` 一致）。
RERANKER_HEURISTIC: Final[str] = "semantic-heuristic"
RERANKER_PASS_THROUGH: Final[str] = "pass-through"
RERANKER_DEGRADED: Final[str] = "degraded"
RERANKER_CONFIGURED: Final[str] = "configured-rerank"

_EMPTY: Final[Mapping[str, object]] = MappingProxyType({})


@dataclass(frozen=True)
class RowRef:
    """``context.chunkRefs`` 的一项：扩展文本用到的一行。"""

    chunk_no: int
    content_hash: str


@dataclass(frozen=True)
class Candidate:
    """一行索引的检索候选；分数字段与 Java 同名同义。

    - ``rrf`` 默认 ``0.0``：Java ``fromDenseHit`` / ``fromLexicalHit`` 构造时就是 ``0.0``；
    - ``expanded``：句子窗口扩展文本（Java ``metadata.expandedContent``），``context_refs`` 是
      构造它用到的行。
    """

    document_id: uuid.UUID
    chunk_no: int
    content_hash: str
    chunk_type: str
    heading: str | None
    content: str
    body_snapshot_id: uuid.UUID
    parent_chunk_no: int | None = None
    metadata: Mapping[str, object] = field(default=_EMPTY)
    dense_rank: int | None = None
    dense: float | None = None
    lexical_rank: int | None = None
    lexical: float | None = None
    rrf: float = 0.0
    rerank: float | None = None
    reranker: str | None = None
    expanded: str | None = None
    parent_context: str | None = None
    context_refs: tuple[RowRef, ...] = ()

    @property
    def key(self) -> tuple[uuid.UUID, int]:
        """身份与平局键：``(documentId, chunkNo)``（P2-R2 R2-5）。"""

        return (self.document_id, self.chunk_no)

    @property
    def admission(self) -> float:
        """准入分 = ``scores.final``（P2-R1 R1-2）。"""

        return admission_score(self.dense, self.lexical)

    @property
    def effective_content(self) -> str:
        """Java ``effectiveContent()``：有非空白扩展文本时用扩展文本，否则用原文。"""

        if self.expanded is not None and self.expanded.strip() != "":
            return self.expanded
        return self.content

    def with_rerank(self, score: float, reranker: str) -> Candidate:
        return replace(self, rerank=score, reranker=reranker)


def java_double_compare(left: float, right: float) -> int:
    """``Double.compare(left, right)``：``-0.0 < 0.0``，NaN 最大且等于自身。"""

    if left < right:
        return -1
    if left > right:
        return 1
    left_bits = _double_bits(left)
    right_bits = _double_bits(right)
    if left_bits == right_bits:
        return 0
    return -1 if left_bits < right_bits else 1


def _double_bits(value: float) -> int:
    # Double.doubleToLongBits：所有 NaN 归一成同一个位模式，再按有符号 long 比较。
    if math.isnan(value):
        return 0x7FF8000000000000
    result: int = struct.unpack(">q", struct.pack(">d", value))[0]
    return result


def compare_keys(left: Candidate, right: Candidate) -> int:
    """``(documentId, chunkNo)`` 升序（uuid 按无符号字节序，与 PostgreSQL 一致）。"""

    if left.key < right.key:
        return -1
    if left.key > right.key:
        return 1
    return 0


def truncate(candidates: list[Candidate], top_n: int) -> list[Candidate]:
    """``topN > 0 && size > topN`` 时截断（Java 各重排器共用口径）。"""

    if top_n > 0 and len(candidates) > top_n:
        return candidates[:top_n]
    return candidates


__all__ = [
    "RERANKER_CONFIGURED",
    "RERANKER_DEGRADED",
    "RERANKER_HEURISTIC",
    "RERANKER_PASS_THROUGH",
    "Candidate",
    "RowRef",
    "compare_keys",
    "java_double_compare",
    "truncate",
]
