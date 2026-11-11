"""分数映射（unit）：覆盖全部 id、缺失补 0、夹取与丢弃非有限数。"""

from __future__ import annotations

import math

from ai_collab_worker.cognition.rerank.scorer import (
    MISSING_SCORE,
    clamp_score,
    score_candidates,
)
from ai_collab_worker.contract.rerank import RerankCandidate

CANDIDATES = (
    RerankCandidate(id="c1", text="一"),
    RerankCandidate(id="c2", text="二"),
    RerankCandidate(id="c3", text="三"),
)


def test_scores_cover_all_ids_in_input_order() -> None:
    result = score_candidates(CANDIDATES, {2: 0.2, 0: 0.9, 1: 0.5})

    assert [score.id for score in result.scores] == ["c1", "c2", "c3"]
    assert [score.score for score in result.scores] == [0.9, 0.5, 0.2]
    assert result.missing_ids == ()
    assert result.is_partial is False


def test_missing_ids_filled_with_zero_and_recorded() -> None:
    result = score_candidates(CANDIDATES, {2: 0.7})

    assert [score.score for score in result.scores] == [MISSING_SCORE, MISSING_SCORE, 0.7]
    assert result.missing_ids == ("c1", "c2")
    assert result.is_partial is True


def test_missing_ids_are_recorded_in_input_order() -> None:
    result = score_candidates(CANDIDATES, {1: 0.4})

    assert result.missing_ids == ("c1", "c3")


def test_extra_indexes_are_ignored() -> None:
    result = score_candidates(CANDIDATES, {0: 0.1, 3: 0.9, 99: 0.5, -1: 0.8})

    assert [score.score for score in result.scores] == [0.1, 0.0, 0.0]
    assert result.missing_ids == ("c2", "c3")


def test_scores_are_clamped_to_closed_interval() -> None:
    result = score_candidates(CANDIDATES, {0: 1.5, 1: -0.2, 2: 0.0})

    assert [score.score for score in result.scores] == [1.0, 0.0, 0.0]
    assert result.missing_ids == ()


def test_non_finite_scores_are_dropped_and_treated_as_missing() -> None:
    result = score_candidates(
        CANDIDATES,
        {0: math.nan, 1: math.inf, 2: -math.inf},
    )

    assert [score.score for score in result.scores] == [0.0, 0.0, 0.0]
    assert result.missing_ids == ("c1", "c2", "c3")
    assert result.is_partial is True


def test_absent_mapping_marks_everything_missing() -> None:
    result = score_candidates(CANDIDATES, {})

    assert len(result.scores) == len(CANDIDATES)
    assert result.missing_ids == ("c1", "c2", "c3")


def test_clamp_score_keeps_boundaries() -> None:
    assert clamp_score(0.0) == 0.0
    assert clamp_score(1.0) == 1.0
    assert clamp_score(0.42) == 0.42
    assert clamp_score(2.0) == 1.0
    assert clamp_score(-2.0) == 0.0
