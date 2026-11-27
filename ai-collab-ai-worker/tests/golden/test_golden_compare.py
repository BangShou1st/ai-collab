"""``golden_compare`` 的非 Docker 单测（R2-5 比对规则本身）。"""

from __future__ import annotations

import pytest
from golden_compare import (
    EPSILON,
    GoldenFieldError,
    RankedItem,
    compare_ranked,
    item_from_row,
    require_score,
    tie_groups,
)

DOC_A = "00000000-0000-4000-8000-00000000000a"
DOC_B = "00000000-0000-4000-8000-00000000000b"


def _it(doc: str, no: int, *scores: float) -> RankedItem:
    return RankedItem(doc, no, tuple(scores))


def test_epsilon_is_fixed_at_1e_9() -> None:
    assert EPSILON == 1e-9


def test_tie_group_boundaries_are_chained_on_adjacent_items() -> None:
    items = [
        _it(DOC_A, 1, 0.5),
        _it(DOC_A, 2, 0.5 - 0.6e-9),
        _it(DOC_A, 3, 0.5 - 1.2e-9),  # 与首项差 > 1e-9，但与相邻项差 ≤ 1e-9：链式同组
        _it(DOC_B, 1, 0.4),
    ]
    groups = tie_groups(items)
    assert groups == [
        frozenset({(DOC_A, 1), (DOC_A, 2), (DOC_A, 3)}),
        frozenset({(DOC_B, 1)}),
    ]


def test_shuffled_within_tie_group_is_equal() -> None:
    java = [_it(DOC_A, 1, 0.9), _it(DOC_B, 2, 0.5), _it(DOC_A, 3, 0.5), _it(DOC_B, 4, 0.1)]
    python = [_it(DOC_A, 1, 0.9), _it(DOC_A, 3, 0.5), _it(DOC_B, 2, 0.5), _it(DOC_B, 4, 0.1)]
    result = compare_ranked(java, python)
    assert result.equal, result.reason
    assert result.expected_groups == result.actual_groups == 3


def test_swapping_across_groups_fails() -> None:
    java = [_it(DOC_A, 1, 0.9), _it(DOC_B, 2, 0.5)]
    python = [_it(DOC_B, 2, 0.5), _it(DOC_A, 1, 0.9)]
    result = compare_ranked(java, python)
    assert not result.equal
    assert result.kind == "group_order"
    assert result.first_mismatch_group == 0


def test_epsilon_boundary_inclusive_and_exclusive() -> None:
    base = 0.25
    inside = [_it(DOC_A, 1, base), _it(DOC_A, 2, base - EPSILON)]
    assert len(tie_groups(inside)) == 1
    outside = [_it(DOC_A, 1, base), _it(DOC_A, 2, base - 2 * EPSILON)]
    assert len(tie_groups(outside)) == 2
    # 超出 1e-9 的两项若两侧换序则判失败（不放宽）。
    swapped = [_it(DOC_A, 2, base - 2 * EPSILON), _it(DOC_A, 1, base)]
    assert compare_ranked(outside, swapped).kind == "group_order"


def test_same_identity_score_drift_beyond_epsilon_fails() -> None:
    java = [_it(DOC_A, 1, 0.5)]
    python = [_it(DOC_A, 1, 0.5 + 3 * EPSILON)]
    result = compare_ranked(java, python)
    assert not result.equal
    assert result.kind == "score"
    assert result.max_score_diff == pytest.approx(3 * EPSILON, rel=1e-3)


def test_membership_difference_fails() -> None:
    result = compare_ranked([_it(DOC_A, 1, 0.5)], [_it(DOC_B, 1, 0.5)])
    assert not result.equal
    assert result.kind == "membership"


def test_multi_component_scores_group_only_when_all_components_tie() -> None:
    items = [_it(DOC_A, 1, 0.5, -2.0, 1.0), _it(DOC_A, 2, 0.5, -1.0, 1.0)]
    assert len(tie_groups(items)) == 2


def test_duplicate_identity_in_one_list_is_an_error() -> None:
    with pytest.raises(GoldenFieldError, match="重复"):
        tie_groups([_it(DOC_A, 1, 0.5), _it(DOC_A, 1, 0.4)])


@pytest.mark.parametrize(
    ("row", "pattern"),
    [
        ({"chunkNo": 1, "scores": {"rrf": 0.1}}, "documentId"),
        ({"documentId": DOC_A, "scores": {"rrf": 0.1}}, "chunkNo"),
        ({"documentId": DOC_A, "chunkNo": None, "scores": {"rrf": 0.1}}, "chunkNo"),
        ({"documentId": DOC_A, "chunkNo": "1", "scores": {"rrf": 0.1}}, "chunkNo"),
    ],
)
def test_missing_identity_fields_raise(row: dict[str, object], pattern: str) -> None:
    with pytest.raises(GoldenFieldError, match=pattern):
        item_from_row(row, (0.1,))


@pytest.mark.parametrize(
    "row",
    [
        {"documentId": DOC_A, "chunkNo": 1},
        {"documentId": DOC_A, "chunkNo": 1, "scores": {}},
        {"documentId": DOC_A, "chunkNo": 1, "scores": {"rrf": None}},
        {"documentId": DOC_A, "chunkNo": 1, "scores": {"rrf": "0.1"}},
    ],
)
def test_missing_or_bad_score_raises(row: dict[str, object]) -> None:
    with pytest.raises(GoldenFieldError):
        require_score(row, "rrf")


def test_document_id_is_case_normalized() -> None:
    upper = item_from_row({"documentId": DOC_A.upper(), "chunkNo": 1}, (0.1,))
    assert upper.key == (DOC_A, 1)
