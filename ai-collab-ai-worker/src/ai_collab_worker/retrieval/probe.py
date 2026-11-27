"""证据充分性探针（逐行对照 Java 语义报告 §2，P2-R2）。

不访问数据库，也不读业务数据（文件名与 token 预算不影响探针结论），仅依据：
1. 候选不被同文档的 ``SENTENCE`` 块抑制（按 ``(documentId, parentChunkNo)`` 匹配）；
2. ``scores.final``（准入分） ``>= 0.15``；
3. ``content`` 不为空白（按 Java ``Character.isWhitespace`` 口径）。

按 ``intent`` 选用：
- ``KNOWLEDGE_QA``：执行上述纯函数；
- 其余（``AGENT_RESEARCH`` / ``PLANNING_SOURCE`` / ``PLANNING_TOOL_LOOKUP``）：只判断候选列表非空。
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Final

from ai_collab_worker.contract.knowledge_search import ChunkType, SearchIntent
from ai_collab_worker.retrieval.candidate import Candidate
from ai_collab_worker.retrieval.javatext import is_blank

#: 来自 Java ``KnowledgeContextBuilder.MIN_SIMILARITY``（语义报告 §2）。
MIN_EVIDENCE_SIMILARITY: Final[float] = 0.15


def has_usable_evidence(intent: SearchIntent, candidates: Sequence[Candidate]) -> bool:
    """判定当前候选列表是否包含至少一条可用证据（语义报告 §2）。"""

    if not candidates:
        return False
    if intent is not SearchIntent.KNOWLEDGE_QA:
        return True

    suppressed_containers = {
        (candidate.document_id, candidate.parent_chunk_no)
        for candidate in candidates
        if candidate.chunk_type == ChunkType.SENTENCE.value
        and candidate.parent_chunk_no is not None
    }
    for candidate in candidates:
        if candidate.key in suppressed_containers:
            continue
        if candidate.admission < MIN_EVIDENCE_SIMILARITY:
            continue
        if is_blank(candidate.content):
            continue
        return True
    return False


__all__ = ["MIN_EVIDENCE_SIMILARITY", "has_usable_evidence"]
