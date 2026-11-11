"""Rerank 打分提示词（与 Java ``NeuralModelReranker`` 逐字节对齐）。

Java 侧的 system prompt 是一个文本块，user prompt 由 ``buildRerankPrompt`` 拼出。
本模块把两者固定下来，任何字节级改动都必须同批更新 ``tests/unit/test_rerank_prompt.py``
里的冻结夹具，避免"改了措辞却没改测试"的静默漂移。

user prompt 形状（``\\n`` 为真实换行）::

    Query: <query.trim()>\n
    \n
    Candidate Passages:\n
    [0] Title: <title.trim()> | Content: <text 截断/压行后>\n
    \n
    [1] Content: <...>\n
    \n

细节：

- 单条 ``text`` 超过 400 字符时截成前 400 字符 + ``...``，再做换行压缩（与 Java 顺序一致）；
- 换行只把 ``\\n`` 压成空格，与 Java ``String.replace("\\n", " ")`` 一致；
- ``title`` 去首尾空白后非空才输出 ``Title: … | ``；
- 截断按 Unicode 码点计数（Python ``len``）。Java ``String.length()`` 是 UTF-16 code unit，
  仅含增补平面字符（emoji 等）时两种口径会差 1–2 个字符；对 BMP 文本（含中文）逐字节一致。
  这里选择与仓库其余"字符数"口径统一（README §6），不模仿 Java 可能切断代理对的行为。
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.rerank import RerankCandidate

#: 与 Java ``MAX_PROMPT_TEXT_CHARS`` 对齐的单条正文上限。
MAX_PROMPT_TEXT_CHARS: Final[int] = 400

#: 截断后追加的省略标记（Java 同样是三个点）。
TRUNCATION_SUFFIX: Final[str] = "..."

#: system prompt 原文；末尾换行是 Java 文本块的形状，必须保留。
SYSTEM_PROMPT: Final[str] = (
    "You are a specialized semantic relevance evaluation reranker.\n"
    "Given a user search query and a numbered list of candidate text passages, "
    "evaluate each passage's direct semantic relevance to answering the query.\n"
    "Output a JSON object mapping each candidate index to a relevance score between "
    '0.00 and 1.00 (e.g. {"0": 0.95, "1": 0.40, "2": 0.10}).\n'
    "Only output the valid JSON object. Do not include markdown code fence formatting "
    "or conversational text.\n"
)


@dataclass(frozen=True)
class ScoringPrompt:
    """一次打分调用需要的 system/user 两段文本。"""

    system: str
    user: str


def truncate_for_prompt(text: str) -> str:
    """按 Java 顺序截断到 400 字符，再追加 ``...``；返回值仍可能含原始换行。"""

    if len(text) > MAX_PROMPT_TEXT_CHARS:
        return text[:MAX_PROMPT_TEXT_CHARS] + TRUNCATION_SUFFIX
    return text


def _prompt_safe_text(text: str) -> str:
    """截断 + 把 ``\\n`` 压成空格（与 Java 的替换范围一致，不处理 ``\\r``）。"""

    return truncate_for_prompt(text).replace("\n", " ")


def build_user_prompt(query: str, candidates: Sequence[RerankCandidate]) -> str:
    """拼出与 Java ``NeuralModelReranker.buildRerankPrompt`` 等价的 user prompt。"""

    parts: list[str] = [f"Query: {query.strip()}\n\nCandidate Passages:\n"]
    for index, candidate in enumerate(candidates):
        parts.append(f"[{index}] ")
        title = candidate.title.strip()
        if title:
            parts.append(f"Title: {title} | ")
        parts.append(f"Content: {_prompt_safe_text(candidate.text)}\n\n")
    return "".join(parts)


def build_scoring_prompt(query: str, candidates: Sequence[RerankCandidate]) -> ScoringPrompt:
    """返回固定 system prompt 与按候选拼出的 user prompt。"""

    return ScoringPrompt(system=SYSTEM_PROMPT, user=build_user_prompt(query, candidates))


__all__ = [
    "MAX_PROMPT_TEXT_CHARS",
    "SYSTEM_PROMPT",
    "TRUNCATION_SUFFIX",
    "ScoringPrompt",
    "build_scoring_prompt",
    "build_user_prompt",
    "truncate_for_prompt",
]
