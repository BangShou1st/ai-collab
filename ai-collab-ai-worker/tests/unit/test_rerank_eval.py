"""``eval.rerank`` 的单元测试。

覆盖四类口径：

1. 指标手算样例：NDCG（并列、``rel=0``、无正例、k 截断）、引用支持率、最近秩 P95；
2. Heuristic 打分与 Java ``SemanticHeuristicReranker`` 的构造样例：标题命中、中文
   bigram、停用词、长句采样、虚词过滤、权重公式、排序与截断；
3. canonical 幂等与报告封口（``sha256`` 不参与自身摘要）；
4. 冻结集读取校验（字段、覆盖、档位一致性、UTF-8 无 BOM）。
"""

from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any

import pytest

from ai_collab_worker.eval.canonical import DatasetError, canonicalize_document, digest
from ai_collab_worker.eval.rerank import (
    CJK_FUNCTION_CHARS,
    CJK_TOKEN_MAX_LENGTH,
    MAX_CHINESE_BIGRAMS,
    MAX_TERMS,
    RRF_EPSILON,
    STOP_WORDS,
    HeuristicScorer,
    RerankCandidate,
    _split_tokens,
    build_worker_request,
    canonical_report_bytes,
    evaluate,
    extract_terms,
    has_positive_labels,
    heuristic_rank,
    heuristic_scores,
    load_rerank_set,
    ndcg_at_k,
    p95,
    report_document,
    seal_report,
    support_rate,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RERANK_SET = PROJECT_ROOT / "tests" / "fixtures" / "rerank_set" / "rerank_baseline.v1.jsonl"
JAVA_RERANKER = (
    PROJECT_ROOT.parent
    / "ai-collab-backend"
    / "src"
    / "main"
    / "java"
    / "com"
    / "shitulelv"
    / "aicollab"
    / "retrieval"
    / "pipeline"
    / "SemanticHeuristicReranker.java"
)


def _candidate(
    candidate_id: str, title: str = "", text: str = "", rrf_score: float | None = None
) -> RerankCandidate:
    return RerankCandidate(id=candidate_id, title=title, text=text, rrf_score=rrf_score)


# --------------------------------------------------------------------------- #
# 指标
# --------------------------------------------------------------------------- #


def test_ndcg_perfect_ranking_is_one() -> None:
    labels = {"a": 2, "b": 1, "c": 0}
    assert ndcg_at_k(["a", "b", "c"], labels, 3) == 1.0


def test_ndcg_hand_computed_swapped_ranking() -> None:
    """手算：labels a=2,b=1,c=0；理想序 a,b,c。

    DCG = 0/log2(2) + 1/log2(3) + 3/log2(4) = 0.6309297535714578 + 1.5
    IDCG = 3/log2(2) + 1/log2(3) = 3 + 0.6309297535714578
    """

    dcg = 1 / math.log2(3) + 3 / math.log2(4)
    idcg = 3 + 1 / math.log2(3)
    expected = dcg / idcg
    assert expected == pytest.approx(0.58688267143572, rel=1e-14)
    assert ndcg_at_k(["c", "b", "a"], {"a": 2, "b": 1, "c": 0}, 3) == pytest.approx(expected)


def test_ndcg_ties_do_not_depend_on_order() -> None:
    labels = {"a": 1, "b": 1, "c": 0}
    assert ndcg_at_k(["a", "b", "c"], labels, 3) == 1.0
    assert ndcg_at_k(["b", "a", "c"], labels, 3) == 1.0


def test_ndcg_zeros_at_tail_are_worthless() -> None:
    labels = {"a": 2, "b": 0, "c": 0}
    assert ndcg_at_k(["a", "b", "c"], labels, 3) == 1.0
    # DCG = 3/log2(3)，IDCG = 3 → 1/log2(3)。
    assert ndcg_at_k(["b", "a", "c"], labels, 3) == pytest.approx(1 / math.log2(3))


def test_ndcg_without_positive_labels_is_one_and_flagged() -> None:
    labels = {"a": 0, "b": 0}
    assert not has_positive_labels(labels)
    assert ndcg_at_k(["a", "b"], labels, 8) == 1.0
    assert ndcg_at_k([], {}, 8) == 1.0


def test_ndcg_ideal_uses_all_labels_not_only_ranked() -> None:
    # 只有 c1 进入 ranking，但理想排序按全部标签计算（c2 的 rel=2 拉高 IDCG）。
    assert ndcg_at_k(["c1"], {"c1": 0, "c2": 2}, 8) == 0.0


def test_ndcg_truncates_at_k() -> None:
    labels = {"pos": 1, **{f"c{index}": 0 for index in range(8)}}
    ranking = [f"c{index}" for index in range(8)] + ["pos"]
    assert ndcg_at_k(ranking, labels, 8) == 0.0
    assert ndcg_at_k(ranking, labels, 9) == pytest.approx(1 / math.log2(10))


def test_ndcg_rejects_non_positive_k() -> None:
    with pytest.raises(ValueError, match="k 必须"):
        ndcg_at_k(["a"], {"a": 1}, 0)


def test_support_rate_counts_top_k_over_denominator() -> None:
    supports = {f"c{index}": index in {0, 2, 4, 9} for index in range(10)}
    ranking = [f"c{index}" for index in range(10)]
    # top-8 里 c0/c2/c4 命中，c9 被截断；分母 min(8, 10) = 8。
    assert support_rate(ranking, supports, 8) == pytest.approx(3 / 8)


def test_support_rate_denominator_uses_candidate_count() -> None:
    supports = {"c1": True, "c2": False, "c3": True, "c4": False, "c5": False, "c6": True}
    assert support_rate(["c1", "c2", "c3", "c4", "c5", "c6"], supports, 8) == pytest.approx(3 / 6)


def test_support_rate_ignores_non_true_values() -> None:
    supports: dict[str, Any] = {"c1": True, "c2": 1, "c3": "true"}
    assert support_rate(["c1", "c2", "c3"], supports, 8) == pytest.approx(1 / 3)


def test_support_rate_handles_empty_supports() -> None:
    assert support_rate([], {}, 8) == 1.0


def test_p95_is_nearest_rank_not_interpolated() -> None:
    # 4 个样本：秩 = ceil(95*4/100) = 4，取最大值；线性插值会得到 3.85。
    assert p95([1.0, 2.0, 3.0, 4.0]) == 4.0


@pytest.mark.parametrize(
    ("count", "expected"),
    [
        (1, 1.0),
        (20, 19.0),
        (100, 95.0),
        (270, 257.0),
    ],
)
def test_p95_rank_matches_ceiling_of_95_percent(count: int, expected: float) -> None:
    samples = [float(value) for value in range(1, count + 1)]
    assert p95(samples) == expected


def test_p95_rejects_empty_samples() -> None:
    with pytest.raises(ValueError, match="不能为空"):
        p95([])


def test_p95_is_order_insensitive() -> None:
    assert p95([9.0, 1.0, 5.0, 3.0, 7.0]) == p95([1.0, 3.0, 5.0, 7.0, 9.0])


# --------------------------------------------------------------------------- #
# Heuristic：Java SemanticHeuristicReranker 复刻
# --------------------------------------------------------------------------- #


def test_stop_words_are_filtered_for_english_tokens() -> None:
    assert extract_terms("a how to backup") == ["backup"]


def test_stop_words_are_filtered_but_long_cjk_token_is_kept_as_whole_term() -> None:
    # Java 只对"切分后的整个 token"做停用词判断，"如何配置超时"不是停用词，整串保留；
    # 其后由 bigram 提供"配置/超时"这类可匹配词项。
    terms = extract_terms("如何配置超时")
    assert "如何" not in terms
    assert "配置" in terms
    assert "超时" in terms
    assert "如何配置超时" in terms


def test_cjk_bigrams_filter_function_chars() -> None:
    terms = extract_terms("星云的问题")
    assert "星云" in terms
    assert "问题" in terms
    assert "云的" not in terms
    assert "的问" not in terms
    assert not CJK_FUNCTION_CHARS & {"星", "云", "问", "题"}


def test_cjk_token_length_limit() -> None:
    ten = "甲乙丙丁戊己庚辛壬癸"
    eleven = ten + "子"
    assert ten in extract_terms(ten)
    assert eleven not in extract_terms(eleven)


def test_non_bmp_token_length_uses_utf16_code_units() -> None:
    # Java String.length() 把扩展区汉字算 2 个 code unit，单个这样的 token 仍满足 >=2。
    assert extract_terms("\U00020000") == ["\U00020000"]


def test_long_cjk_token_is_sampled_by_uniform_step() -> None:
    # 120 个互不相同的汉字、无标点：整串超过 10 字不入库，bigram 数 119 > 48，
    # 步长 = ceil(119/48) = 3，覆盖整句而不是只取前缀。
    long_token = "".join(chr(0x4E00 + 2 * index) for index in range(120))
    terms = extract_terms(long_token)
    assert len(terms) <= MAX_CHINESE_BIGRAMS
    assert len(terms) >= 30
    assert long_token[0:2] in terms
    assert long_token[117:119] in terms
    assert long_token[1:3] not in terms


def test_terms_are_bounded_to_max_terms() -> None:
    query = " ".join(f"t{index:02d}x" for index in range(70))
    terms = extract_terms(query)
    assert len(terms) == MAX_TERMS
    assert terms[0] == "t00x"
    assert "t63x" in terms
    assert "t64x" not in terms


def test_heading_full_match_scores_one_and_weighs_025() -> None:
    candidates = [
        _candidate("a", title="住宿费标准与报销流程", text=""),
        _candidate("b", title="", text="住宿费标准为每晚三百元"),
    ]
    scores = heuristic_scores("住宿费标准", candidates)
    # a：标题整串包含查询 → heading=1.0、content=0 → 0.25
    # b：正文覆盖全部词项（含 bigram）→ content=1.0 → 0.40
    assert scores == [("a", pytest.approx(0.25)), ("b", pytest.approx(0.40))]


def test_heading_partial_match_is_term_coverage() -> None:
    scores = heuristic_scores("报销 标准", [_candidate("a", title="报销流程说明", text="")])
    assert scores[0][1] == pytest.approx(0.5 * 0.25)


def test_weight_formula_includes_rrf_and_defaults_to_zero() -> None:
    scores = heuristic_scores("备份", [_candidate("a", text="备份")])
    # content=1.0（含 0.02 词频加成后仍被 min(1.0) 截断）→ 0.40；rrf 缺省 0.0。
    assert scores[0][1] == pytest.approx(0.40)
    with_rrf = heuristic_scores("备份", [_candidate("a", text="备份", rrf_score=0.6)])
    assert with_rrf[0][1] == pytest.approx(0.40 + 0.35 * 0.6)


def test_ranking_breaks_ties_by_rrf_then_id() -> None:
    candidates = [
        _candidate("z", text="备份", rrf_score=0.0),
        _candidate("m", text="备份", rrf_score=0.5),
    ]
    assert heuristic_rank("备份", candidates, 8) == ["m", "z"]

    tied = [_candidate("b2", text="备份"), _candidate("a1", text="备份")]
    assert heuristic_rank("备份", tied, 8) == ["a1", "b2"]


def test_blank_query_or_empty_terms_degrade_to_input_order() -> None:
    candidates = [_candidate("b", text="备份"), _candidate("a", text="恢复")]
    assert heuristic_rank("   ", candidates, 8) == ["b", "a"]
    assert heuristic_rank(None, candidates, 8) == ["b", "a"]
    assert heuristic_rank("!!!", candidates, 8) == ["b", "a"]


def test_long_sentence_ranks_matching_content_first() -> None:
    query = "灰度放量过程中错误率突然升高应该先回滚还是先看日志再决定是否继续放量"
    matching = _candidate("match", text=query)
    distractor = _candidate("noise", text="成员负载视图按人汇总进行中卡片。")
    assert heuristic_rank(query, [distractor, matching], 8) == ["match", "noise"]


def test_ranking_truncates_to_top_k() -> None:
    candidates = [_candidate(f"c{index}", title="备份", text="备份") for index in range(10)]
    assert heuristic_rank("备份", candidates, 8) == [f"c{index}" for index in range(8)]
    assert len(heuristic_rank("备份", candidates, 0)) == 10


def test_empty_candidates_returns_empty() -> None:
    assert heuristic_rank("备份", [], 8) == []
    assert heuristic_scores("备份", []) == []


# --------------------------------------------------------------------------- #
# 与 Java 源码的跨语言漂移守护（同一仓库检出；缺失即失败，不 skip）
# --------------------------------------------------------------------------- #


def _java_source() -> str:
    if not JAVA_RERANKER.is_file():
        pytest.fail(f"必须与 Java 后端同一仓库检出：缺少 {JAVA_RERANKER}")
    return JAVA_RERANKER.read_text(encoding="utf-8")


def _java_set_block(source: str, declaration: str) -> str:
    match = re.search(rf"{declaration} = Set\.of\((.*?)\);", source, re.S)
    assert match is not None, f"Java 源码里找不到 {declaration}"
    return match.group(1)


def test_stop_words_match_java_source() -> None:
    block = _java_set_block(_java_source(), "STOP_WORDS")
    assert set(re.findall(r'"([^"]+)"', block)) == set(STOP_WORDS)


def test_cjk_function_chars_match_java_source() -> None:
    block = _java_set_block(_java_source(), "CJK_FUNCTION_CHARS")
    assert set(re.findall(r"'(.+?)'", block)) == set(CJK_FUNCTION_CHARS)


def test_bounds_weights_and_split_pattern_match_java_source() -> None:
    source = _java_source()
    assert f"MAX_TERMS = {MAX_TERMS};" in source
    assert f"MAX_CHINESE_BIGRAMS = {MAX_CHINESE_BIGRAMS};" in source
    assert f"CJK_TOKEN_MAX_LENGTH = {CJK_TOKEN_MAX_LENGTH};" in source
    epsilon = re.search(r"EPSILON = ([0-9.eE+-]+);", source)
    assert epsilon is not None
    assert float(epsilon.group(1)) == RRF_EPSILON
    weights = re.search(
        r"\(rrf \* ([\d.]+)\) \+ \(contentScore \* ([\d.]+)\) "
        r"\+ \(headingScore \* ([\d.]+)\)",
        source,
    )
    assert weights is not None, "Java 权重公式与复刻实现的假设不一致"
    assert weights.groups() == ("0.35", "0.40", "0.25")
    # Java `[\\p{P}\\p{S}\\s]+`：切分集合是"标点 + 符号 + ASCII 空白"，不含全角空格。
    assert "[\\\\p{P}\\\\p{S}\\\\s]+" in source


def test_split_tokens_matches_java_classes() -> None:
    assert _split_tokens("甲，乙") == ["甲", "乙"]
    assert _split_tokens("甲→乙") == ["甲", "乙"]
    assert _split_tokens("a\tb\nc") == ["a", "b", "c"]
    # Java 未开 UNICODE_CHARACTER_CLASS 时 `\s` 只匹配 ASCII 空白：全角空格不分词。
    assert _split_tokens("a\u3000b") == ["a\u3000b"]


# --------------------------------------------------------------------------- #
# Worker 请求形状（冻结 §1）
# --------------------------------------------------------------------------- #


def test_worker_request_matches_frozen_contract() -> None:
    case = load_rerank_set(RERANK_SET)[0]
    request = build_worker_request(case, top_k=8, deadline_ms=3000)
    assert list(request) == ["schemaVersion", "query", "topK", "candidates", "deadlineMs"]
    assert request["schemaVersion"] == 1
    assert request["topK"] == 8
    assert request["deadlineMs"] == 3000
    assert request["query"] == case.query
    assert [item["id"] for item in request["candidates"]] == [
        candidate.id for candidate in case.candidates
    ]
    assert all(list(item) == ["id", "title", "text"] for item in request["candidates"])


# --------------------------------------------------------------------------- #
# 冻结集
# --------------------------------------------------------------------------- #


def test_frozen_rerank_set_shape() -> None:
    raw = RERANK_SET.read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf")
    assert b"\r" not in raw
    assert raw.endswith(b"\n")

    cases = load_rerank_set(RERANK_SET)
    assert len(cases) == 15
    assert len({case.id for case in cases}) == 15
    for case in cases:
        assert 6 <= len(case.candidates) <= 10
        assert set(case.labels.values()) == {0, 1, 2}
        candidate_ids = {candidate.id for candidate in case.candidates}
        assert set(case.labels) == candidate_ids
        assert set(case.supports) == candidate_ids
        for candidate_id, label in case.labels.items():
            if label == 2:
                assert case.supports[candidate_id] is True
            if label == 0:
                assert case.supports[candidate_id] is False


def test_frozen_rerank_set_covers_query_styles() -> None:
    queries = {case.id: case.query for case in load_rerank_set(RERANK_SET)}
    assert "SLA" in queries["r04"]
    assert "MCP" in queries["r09"]
    assert "RAG" in queries["r14"]
    assert "HNSW" in queries["r06"]
    assert "有什么不同" in queries["r05"]
    assert len(queries["r03"]) > 30
    assert "大半夜" in queries["r07"]


def _write_jsonl(path: Path, case: dict[str, Any]) -> Path:
    path.write_bytes((json.dumps(case, ensure_ascii=False) + "\n").encode("utf-8"))
    return path


def _valid_case() -> dict[str, Any]:
    return {
        "id": "x01",
        "query": "备份怎么做",
        "candidates": [
            {"id": "c1", "title": "备份策略", "text": "每天做一次全量备份。"},
            {"id": "c2", "title": "恢复流程", "text": "从备份恢复需要校验和。"},
        ],
        "labels": {"c1": 2, "c2": 0},
        "supports": {"c1": True, "c2": False},
    }


def test_loader_accepts_valid_case(tmp_path: Path) -> None:
    cases = load_rerank_set(_write_jsonl(tmp_path / "ok.jsonl", _valid_case()))
    assert [case.id for case in cases] == ["x01"]


@pytest.mark.parametrize(
    ("mutate", "needle"),
    [
        (lambda case: case.update({"extra": 1}), "未定义字段"),
        (lambda case: case.pop("supports"), "缺少字段"),
        (lambda case: case.update({"query": "   "}), "query"),
        (lambda case: case.update({"query": "x" * 4001}), "4000"),
        (lambda case: case["candidates"][0].update({"text": ""}), "text 长度"),
        (lambda case: case["candidates"][0].update({"text": "x" * 2001}), "text 长度"),
        (lambda case: case["candidates"][0].update({"title": "x" * 501}), "title"),
        (lambda case: case["candidates"].append(dict(case["candidates"][0])), "重复"),
        (lambda case: case.update({"candidates": []}), "候选数"),
        (lambda case: case.update({"labels": {"c1": 2}}), "缺少候选"),
        (lambda case: case.update({"labels": {"c1": 2, "c2": 0, "c3": 1}}), "未知候选"),
        (lambda case: case["labels"].update({"c2": 3}), "0/1/2"),
        (lambda case: case["supports"].update({"c2": "false"}), "布尔值"),
        (lambda case: case["supports"].update({"c1": False}), "labels=2"),
        (lambda case: case["labels"].update({"c2": 2}), "labels=2"),
    ],
)
def test_loader_rejects_invalid_cases(tmp_path: Path, mutate: Any, needle: str) -> None:
    case = _valid_case()
    mutate(case)
    with pytest.raises(DatasetError, match=needle):
        load_rerank_set(_write_jsonl(tmp_path / "bad.jsonl", case))


def test_loader_rejects_zero_label_with_support(tmp_path: Path) -> None:
    case = _valid_case()
    case["supports"]["c2"] = True
    with pytest.raises(DatasetError, match="labels=0"):
        load_rerank_set(_write_jsonl(tmp_path / "bad.jsonl", case))


def test_loader_accepts_optional_rrf_score(tmp_path: Path) -> None:
    # load_dataset 用 Decimal 解析小数，loader 必须把它归一成 float 而不是拒绝。
    case = _valid_case()
    case["candidates"][0]["rrfScore"] = 0.125
    loaded = load_rerank_set(_write_jsonl(tmp_path / "rrf.jsonl", case))
    assert loaded[0].candidates[0].rrf_score == pytest.approx(0.125)
    assert loaded[0].candidates[1].rrf_score is None


# --------------------------------------------------------------------------- #
# canonical 与报告封口
# --------------------------------------------------------------------------- #


def test_canonicalize_document_sorts_keys_and_keeps_arrays() -> None:
    assert (
        canonicalize_document({"b": [1.50, True], "a": "中"})
        == '{"a":"中","b":[1.5,true]}'.encode()
    )


def test_canonicalize_document_is_idempotent() -> None:
    document = {"schemaVersion": 1, "cases": [{"id": "a", "ranking": ["c2", "c1"]}]}
    first = canonicalize_document(document)
    assert canonicalize_document(json.loads(first)) == first


def test_report_is_byte_stable_and_sealed() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        report = evaluate(cases, scorer, top_k=8)
    finally:
        scorer.close()

    document = report_document(report)
    sealed = seal_report(document)
    payload = canonical_report_bytes(document)

    assert payload == canonicalize_document(sealed)
    assert payload == canonical_report_bytes(dict(document))
    assert sealed["sha256"] == digest(
        canonicalize_document({key: value for key, value in sealed.items() if key != "sha256"})
    )
    # 再次封口不会因为已有 sha256 而漂移。
    assert seal_report(sealed) == sealed
    assert b"\n" not in payload


def test_report_has_expected_shape_and_latency_is_off_by_default() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        report = evaluate(cases, scorer, top_k=8)
    finally:
        scorer.close()

    document = report_document(report)
    assert document["schemaVersion"] == 1
    assert document["scorer"] == "heuristic"
    assert document["topK"] == 8
    assert document["aggregate"]["cases"] == 15
    assert document["aggregate"]["p95Ms"] is None
    assert document["latency"] == {
        "measured": False,
        "runs": 0,
        "warmup": 0,
        "samples": 0,
        "p95Ms": None,
    }
    for case in document["cases"]:
        assert set(case) >= {"id", "ndcgAt8", "supportRate", "ranking", "noPositiveLabels"}
        assert len(case["ranking"]) <= 8
        assert 0.0 <= case["ndcgAt8"] <= 1.0
        assert 0.0 <= case["supportRate"] <= 1.0


def test_evaluate_measures_latency_with_warmup_excluded() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        report = evaluate(cases, scorer, top_k=8, runs=4, warmup=1)
    finally:
        scorer.close()

    assert report.latency.measured
    assert report.latency.runs == 4
    assert report.latency.warmup == 1
    # 每条 case 跑 4 次、前 1 次 warmup 不计 → 15 * 3 个样本。
    assert report.latency.samples == 15 * 3
    assert report.latency.p95_ms is not None
    assert report.latency.p95_ms >= 0.0


def test_evaluate_rejects_bad_parameters() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    with pytest.raises(ValueError, match="top_k 必须"):
        evaluate(cases, scorer, top_k=0)
    with pytest.raises(ValueError, match="不能为负数"):
        evaluate(cases, scorer, top_k=8, runs=-1)
    with pytest.raises(ValueError, match="warmup 必须小于 runs"):
        evaluate(cases, scorer, top_k=8, runs=2, warmup=2)
    with pytest.raises(ValueError, match="评测集不能为空"):
        evaluate([], scorer, top_k=8)


def test_evaluate_is_case_order_independent() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        forward = evaluate(cases, scorer, top_k=8)
        backward = evaluate(list(reversed(cases)), scorer, top_k=8)
    finally:
        scorer.close()
    assert [case.id for case in forward.cases] == [case.id for case in backward.cases]
    assert canonical_report_bytes(report_document(forward)) == canonical_report_bytes(
        report_document(backward)
    )
