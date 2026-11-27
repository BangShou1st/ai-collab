"""``golden_compare`` 的非 Docker 单测（R2-5 比对规则本身）。"""

from __future__ import annotations

import pytest
from golden_compare import (
    EPSILON,
    GoldenFieldError,
    RankedItem,
    boundary_span,
    compare_ranked,
    compare_ranked_truncated,
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


# ---------------------------------------------------------------- R2-5a 截断边界平局组

DOC_C = "00000000-0000-4000-8000-00000000000c"


def test_boundary_group_crossing_cut_with_other_member_passes_and_is_marked() -> None:
    # Python 完整结果：x、然后 {a, b} 平局组跨 top_k=2；Java 保留了 b。
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5), _it(DOC_C, 2, 0.1)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5)]
    result = compare_ranked_truncated(java, python_full, 2)
    assert result.equal, result.reason
    assert result.boundary_tie_resolved
    assert result.boundary_group_size == 2
    assert result.boundary_group_start == 1


def test_strict_equal_with_crossing_group_is_not_marked_resolved() -> None:
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5)]
    result = compare_ranked_truncated(java, python_full, 2)
    assert result.equal
    assert not result.boundary_tie_resolved
    assert result.boundary_group_size == 2


def test_java_boundary_member_outside_group_fails_as_boundary() -> None:
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5), _it(DOC_C, 2, 0.1)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_C, 2, 0.5)]
    result = compare_ranked_truncated(java, python_full, 2)
    assert not result.equal
    assert result.kind == "boundary"
    assert not result.boundary_tie_resolved


def test_wrong_tail_count_fails_as_boundary() -> None:
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5), _it(DOC_C, 2, 0.5)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5)]  # top_k=3 应保留 2 条边界成员
    result = compare_ranked_truncated(java, python_full, 3)
    assert not result.equal
    assert result.kind == "boundary"


def test_difference_before_boundary_group_fails_normally() -> None:
    python_full = [
        _it(DOC_C, 1, 0.9),
        _it(DOC_C, 2, 0.8),
        _it(DOC_A, 1, 0.5),
        _it(DOC_B, 1, 0.5),
    ]
    java = [_it(DOC_C, 2, 0.8), _it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5)]  # 前缀换序
    result = compare_ranked_truncated(java, python_full, 3)
    assert not result.equal
    assert result.kind == "group_order"
    assert not result.boundary_tie_resolved


def test_boundary_member_score_drift_beyond_epsilon_fails() -> None:
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5 + 3 * EPSILON)]
    result = compare_ranked_truncated(java, python_full, 2)
    assert not result.equal
    assert result.kind == "score"
    assert not result.boundary_tie_resolved


def test_group_not_crossing_cut_matches_compare_ranked_exactly() -> None:
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5), _it(DOC_C, 2, 0.1)]
    cases = [
        [_it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5), _it(DOC_A, 1, 0.5)],  # 组内换序：通过
        [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_C, 2, 0.1)],  # 成员不同：失败
    ]
    assert boundary_span(python_full, 3) is None
    for java in cases:
        assert compare_ranked_truncated(java, python_full, 3) == compare_ranked(
            java, python_full[:3]
        )


def test_scores_beyond_epsilon_are_not_a_boundary_group() -> None:
    # a、b 分差 2e-9 > 1e-9，不同组：top_k=2 切在组边界上，Java 取 b 照常判失败。
    python_full = [_it(DOC_C, 1, 0.9), _it(DOC_A, 1, 0.5), _it(DOC_B, 1, 0.5 - 2 * EPSILON)]
    java = [_it(DOC_C, 1, 0.9), _it(DOC_B, 1, 0.5 - 2 * EPSILON)]
    assert boundary_span(python_full, 2) is None
    result = compare_ranked_truncated(java, python_full, 2)
    assert not result.equal
    assert result.kind == "membership"
    assert not result.boundary_tie_resolved
    assert result.boundary_group_size is None


def test_regression_ci_45991923308_h_g02_cross_d_qa_topk20() -> None:
    """CI run 45991923308：rrf=1/74、命中路数 1、最好名次 14 的两条候选在第 19、20 位平局。"""

    prefix = [
        _it(f"00000000-0000-4000-8000-{i:012d}", 0, 0.05 - i * 0.001, -2.0, float(i + 1))
        for i in range(19)
    ]
    tie = (1 / 74, -1.0, 14.0)
    lexical_only = _it("a1ca964f-0000-4000-8000-000000000000", 0, *tie)  # Python 保留
    dense_only = _it("b35a8782-0000-4000-8000-000000000000", 3, *tie)  # Java 保留（chunkId 序）
    python_full = [*prefix, lexical_only, dense_only, _it(DOC_C, 9, 0.001, -1.0, 40.0)]
    java = [*prefix, dense_only]

    assert not compare_ranked(java, python_full[:20]).equal  # 原规则：1 处不一致
    result = compare_ranked_truncated(java, python_full, 20)
    assert result.equal, result.reason
    assert result.boundary_tie_resolved
    assert result.boundary_group_start == 19
    assert result.boundary_group_size == 2
    assert result.max_score_diff == 0.0
