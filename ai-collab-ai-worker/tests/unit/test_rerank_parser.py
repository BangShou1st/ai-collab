"""模型打分输出解析（unit）：object/array 两形态、围栏、字符串数字与非法输入。"""

from __future__ import annotations

import math

import pytest

from ai_collab_worker.cognition.rerank.parser import parse_scores
from ai_collab_worker.contract.common import AppError, ErrorCode


def test_object_shape_parses_numeric_and_string_values() -> None:
    assert parse_scores('{"0": 0.95, "1": "0.40", " 2 ": " 0.1 "}') == {
        0: 0.95,
        1: 0.40,
        2: 0.1,
    }


def test_array_of_objects_with_index_and_score() -> None:
    content = '[{"index":0,"score":0.9},{"index":"1","score":"0.2"},{"score":0.3}]'

    assert parse_scores(content) == {0: 0.9, 1: 0.2, 2: 0.3}


def test_array_of_numbers_and_string_numbers() -> None:
    assert parse_scores('[0.9, "0.1", 0]') == {0: 0.9, 1: 0.1, 2: 0.0}


def test_json_code_fence_is_stripped() -> None:
    assert parse_scores('```json\n{"0": 0.5}\n```') == {0: 0.5}


def test_bare_code_fence_is_stripped() -> None:
    assert parse_scores("```\n[0.25, 0.75]\n```") == {0: 0.25, 1: 0.75}


def test_invalid_entries_are_ignored_but_valid_ones_survive() -> None:
    content = '{"0": "not-a-number", "bad": 0.5, "1": true, "2": 0.7, "3": {"x": 1}}'

    assert parse_scores(content) == {2: 0.7}


@pytest.mark.parametrize(
    "content",
    [
        "",
        "   ",
        "not json",
        "[]",
        "{}",
        "0.5",
        '"0.5"',
        "null",
        "true",
        '{"a": 1, "b": 2}',
        '[{"foo": 1}]',
        "```json\n{broken}\n```",
        "```json\n```",
    ],
)
def test_invalid_or_empty_output_raises_schema_violation(content: str) -> None:
    with pytest.raises(AppError) as excinfo:
        parse_scores(content)

    error = excinfo.value
    assert error.code is ErrorCode.SCHEMA_VIOLATION
    assert error.status_code == 422
    assert error.retryable is False
    assert error.details is not None
    if content.strip():
        assert content[:20] not in error.message
        assert content[:20] not in str(error.details)


def test_negative_and_float_indexes_are_kept_for_scorer_to_ignore() -> None:
    content = '[{"index": -1, "score": 0.9}, {"index": 1.0, "score": 0.8}]'

    assert parse_scores(content) == {-1: 0.9, 1: 0.8}


def test_non_finite_values_are_returned_for_scorer_to_drop() -> None:
    scores = parse_scores('{"0": NaN, "1": Infinity, "2": 0.5}')

    assert math.isnan(scores[0])
    assert math.isinf(scores[1])
    assert scores[2] == 0.5


def test_duplicate_keys_last_wins() -> None:
    assert parse_scores('{"0": 0.1, "0": 0.9}') == {0: 0.9}
