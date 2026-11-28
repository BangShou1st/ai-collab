"""知识问答的契约冻结常量（P3 契约 §2.1 行 6、§3.3、§3.5）。

全部数值/文案与 Java 侧现状逐字一致；``GET /v1/knowledge/qa/capabilities`` 的返回
以此为准（§3.5 内部对账口径）。改动任何一个常量都属于契约修订，不得顺手调整。
"""

from __future__ import annotations

from typing import Final

#: 证据装配准入阈值（作用于 ``scores.final`` 准入分语义，Java ``KnowledgeContextBuilder``）。
MIN_SIMILARITY: Final[float] = 0.15

#: 问答默认来源上限。
DEFAULT_MAX_SOURCES: Final[int] = 5

#: 来源上限的公共上限（Agent 工具共享）。
MAX_SOURCES_LIMIT: Final[int] = 12

#: 每文档来源名额均衡上限（P6-RAG-QUALITY-01 S2）。
PER_DOCUMENT_SOURCE_CAP: Final[int] = 2

#: 生产 QA 证据装配的 token 预算（``buildWithTokenBudget(candidates, 5, 2500)``）。
CONTEXT_TOKEN_BUDGET: Final[int] = 2500

#: 问答检索条数（TOP_K）。
TOP_K: Final[int] = 8

#: 输出安全 k-gram 窗口长度（code point）。
K_GRAM: Final[int] = 16

#: 泄漏命中时的统一拦截文案。
BLOCKED_ANSWER: Final[str] = (
    "抱歉，该回答因包含不应披露的内部信息，已被安全策略拦截。请重新提问或联系管理员。"
)

#: 证据不足统一拒答文案（落库/前端契约，必须逐字不变）。
INSUFFICIENT_ANSWER: Final[str] = "当前项目资料不足以回答该问题"

#: 引用来源正文最短长度（低于即告警）。
MIN_SOURCE_CONTENT_LENGTH: Final[int] = 20

#: 问题长度上限（code point）。
QUESTION_MAX_CODE_POINTS: Final[int] = 2000

#: 显式文档范围上限。
DOCUMENT_IDS_MAX: Final[int] = 20

#: 可见文档集合上限（P2 §4.4 / P3 §3.3）。
VISIBLE_DOCUMENTS_MAX: Final[int] = 5000

#: 提示词装配限额（Java ``KnowledgeContextBuilder`` / ``KnowledgePromptAssembler``）。
MAX_PROMPT_SOURCE_CODE_POINTS: Final[int] = 8000
MAX_PROMPT_FILENAME_CODE_POINTS: Final[int] = 180
MAX_PROMPT_HEADING_CODE_POINTS: Final[int] = 300
MIN_SOURCE_BODY_CODE_POINTS: Final[int] = 120
SOURCE_BODY_ESCAPE_LIMIT: Final[int] = 1500
PARENT_SNIPPET_ESCAPE_LIMIT: Final[int] = 200

#: 多轮消歧预算（Java ``QueryUnderstandingService``）。
UNDERSTANDING_MAX_TURNS: Final[int] = 5
UNDERSTANDING_CONTEXT_CHAR_BUDGET: Final[int] = 3500
REWRITE_QUERY_MAX_CODE_POINTS: Final[int] = 2000

__all__ = [
    "BLOCKED_ANSWER",
    "CONTEXT_TOKEN_BUDGET",
    "DEFAULT_MAX_SOURCES",
    "DOCUMENT_IDS_MAX",
    "INSUFFICIENT_ANSWER",
    "K_GRAM",
    "MAX_PROMPT_FILENAME_CODE_POINTS",
    "MAX_PROMPT_HEADING_CODE_POINTS",
    "MAX_PROMPT_SOURCE_CODE_POINTS",
    "MAX_SOURCES_LIMIT",
    "MIN_SIMILARITY",
    "MIN_SOURCE_BODY_CODE_POINTS",
    "MIN_SOURCE_CONTENT_LENGTH",
    "PARENT_SNIPPET_ESCAPE_LIMIT",
    "PER_DOCUMENT_SOURCE_CAP",
    "QUESTION_MAX_CODE_POINTS",
    "REWRITE_QUERY_MAX_CODE_POINTS",
    "SOURCE_BODY_ESCAPE_LIMIT",
    "TOP_K",
    "UNDERSTANDING_CONTEXT_CHAR_BUDGET",
    "UNDERSTANDING_MAX_TURNS",
    "VISIBLE_DOCUMENTS_MAX",
]
