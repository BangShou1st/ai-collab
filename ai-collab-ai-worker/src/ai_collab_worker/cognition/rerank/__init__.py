"""Rerank 认知层：提示词拼装、模型输出解析、分数映射与缺失补齐。"""

from __future__ import annotations

from ai_collab_worker.cognition.rerank.parser import parse_scores
from ai_collab_worker.cognition.rerank.prompt import (
    MAX_PROMPT_TEXT_CHARS,
    SYSTEM_PROMPT,
    ScoringPrompt,
    build_scoring_prompt,
    build_user_prompt,
    truncate_for_prompt,
)
from ai_collab_worker.cognition.rerank.scorer import RerankScoring, score_candidates

__all__ = [
    "MAX_PROMPT_TEXT_CHARS",
    "SYSTEM_PROMPT",
    "RerankScoring",
    "ScoringPrompt",
    "build_scoring_prompt",
    "build_user_prompt",
    "parse_scores",
    "score_candidates",
    "truncate_for_prompt",
]
