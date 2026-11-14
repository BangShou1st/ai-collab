"""Rerank 打分 Prompt 的字节形状（unit，冻结夹具）。

期望值按 Java ``NeuralModelReranker`` 的 ``SYSTEM_PROMPT`` 文本块与
``buildRerankPrompt`` 手写；任何字符/换行变化都必须同批修改本文件。
"""

from __future__ import annotations

from ai_collab_worker.cognition.rerank.prompt import (
    MAX_PROMPT_TEXT_CHARS,
    SYSTEM_PROMPT,
    build_scoring_prompt,
    build_user_prompt,
    truncate_for_prompt,
)
from ai_collab_worker.contract.rerank import RerankCandidate

SYSTEM_PROMPT_FROZEN = (
    "You are a specialized semantic relevance evaluation reranker.\n"
    "Given a user search query and a numbered list of candidate text passages, "
    "evaluate each passage's direct semantic relevance to answering the query.\n"
    "Output a JSON object mapping each candidate index to a relevance score between "
    '0.00 and 1.00 (e.g. {"0": 0.95, "1": 0.40, "2": 0.10}).\n'
    "Only output the valid JSON object. Do not include markdown code fence formatting "
    "or conversational text.\n"
)


def test_system_prompt_is_byte_frozen() -> None:
    assert SYSTEM_PROMPT == SYSTEM_PROMPT_FROZEN
    assert SYSTEM_PROMPT.encode("utf-8") == SYSTEM_PROMPT_FROZEN.encode("utf-8")
    assert SYSTEM_PROMPT.endswith("\n")


def test_user_prompt_matches_java_shape() -> None:
    candidates = [
        RerankCandidate(id="c1", title="前言", text="第一行\n第二行"),
        RerankCandidate(id="c2", text="无标题正文"),
    ]
    expected = (
        "Query: 核心架构是什么\n"
        "\n"
        "Candidate Passages:\n"
        "[0] Title: 前言 | Content: 第一行 第二行\n"
        "\n"
        "[1] Content: 无标题正文\n"
        "\n"
    )

    assert build_user_prompt("  核心架构是什么  ", candidates) == expected
    assert build_user_prompt("  核心架构是什么  ", candidates).encode("utf-8") == expected.encode(
        "utf-8"
    )


def test_title_is_trimmed_and_blank_title_omitted() -> None:
    candidates = [
        RerankCandidate(id="c1", title="  标题  ", text="a"),
        RerankCandidate(id="c2", title="   ", text="b"),
        RerankCandidate(id="c3", title="", text="c"),
    ]

    prompt = build_user_prompt("q", candidates)

    assert "[0] Title: 标题 | Content: a" in prompt
    assert "[1] Content: b" in prompt
    assert "[2] Content: c" in prompt
    assert "[1] Title:" not in prompt


def test_text_at_limit_is_not_truncated() -> None:
    text = "a" * MAX_PROMPT_TEXT_CHARS
    candidate = RerankCandidate(id="c1", text=text)

    assert truncate_for_prompt(text) == text
    assert f"Content: {text}\n" in build_user_prompt("q", [candidate])


def test_text_over_limit_truncated_with_ellipsis() -> None:
    text = "a" * (MAX_PROMPT_TEXT_CHARS + 1)

    assert truncate_for_prompt(text) == "a" * MAX_PROMPT_TEXT_CHARS + "..."
    assert f"Content: {'a' * MAX_PROMPT_TEXT_CHARS}...\n" in build_user_prompt(
        "q", [RerankCandidate(id="c1", text=text)]
    )


def test_truncation_happens_before_newline_compression() -> None:
    """第 400 个字符是换行：先截断再压行，结果以空格 + ... 结尾（与 Java 顺序一致）。"""

    text = "a" * (MAX_PROMPT_TEXT_CHARS - 1) + "\n" + "b" * 20
    prompt = build_user_prompt("q", [RerankCandidate(id="c1", text=text)])

    assert f"Content: {'a' * (MAX_PROMPT_TEXT_CHARS - 1)} ..." in prompt
    assert "b" not in prompt


def test_prompt_is_reproducible() -> None:
    candidates = [RerankCandidate(id="c1", title="T", text="第一段\n第二段")]

    first = build_scoring_prompt("查询", candidates)
    second = build_scoring_prompt("查询", candidates)

    assert first == second
    assert first.system == SYSTEM_PROMPT
    assert first.user.endswith("\n\n")


def test_empty_candidate_sequence_still_renders_header() -> None:
    assert build_user_prompt("q", []) == "Query: q\n\nCandidate Passages:\n"
