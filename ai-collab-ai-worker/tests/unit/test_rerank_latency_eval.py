"""W3.1 延迟三口径、置信度与候选补齐的单元测试。

口径来源：``docs/archive/handoffs/w3-final-freeze-20261112.md`` §1.5 / §1.6。

覆盖：

1. 最近秩法 P50/P95/P99（不插值）与样本量置信度分级；
2. ``summarize_latency`` 的 total/model/overhead 拆分：已知/未知混合、负 overhead 夹 0、
   全未知时为 ``None``、未测延迟时全空；
3. ``evaluate`` 把 ``Scorer.model_latency_ms`` 逐样本带进摘要，并写进 canonical 报告；
4. ``pad_candidates`` 的确定性补齐规则（纯函数，含 id 冲突与长度边界）；
5. worker 响应 ``modelLatencyMs`` 的读取（缺省/非法）。
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import httpx
import pytest

from ai_collab_worker.eval.rerank import (
    CONFIDENCE_HIGH,
    CONFIDENCE_LOW,
    CONFIDENCE_MEDIUM,
    HeuristicScorer,
    RerankCandidate,
    RerankCase,
    WorkerError,
    WorkerScorer,
    canonical_report_bytes,
    confidence_for_samples,
    evaluate,
    load_rerank_set,
    p50,
    p95,
    p99,
    pad_candidates,
    report_document,
    summarize_latency,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RERANK_SET = PROJECT_ROOT / "tests" / "fixtures" / "rerank_set" / "rerank_baseline.v1.jsonl"
TOKEN_ENV = "AI_EVAL_W3F_S1_TEST_TOKEN"


# --------------------------------------------------------------------------- #
# 分位数与置信度
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    ("count", "p50_expected", "p95_expected", "p99_expected"),
    [
        (1, 1.0, 1.0, 1.0),
        (4, 2.0, 4.0, 4.0),
        (20, 10.0, 19.0, 20.0),
        (100, 50.0, 95.0, 99.0),
        (500, 250.0, 475.0, 495.0),
    ],
)
def test_percentiles_use_nearest_rank(
    count: int, p50_expected: float, p95_expected: float, p99_expected: float
) -> None:
    samples = [float(value) for value in range(1, count + 1)]
    assert p50(samples) == p50_expected
    assert p95(samples) == p95_expected
    assert p99(samples) == p99_expected


def test_percentiles_are_order_insensitive() -> None:
    unsorted = [9.0, 1.0, 5.0, 3.0, 7.0]
    ordered = [1.0, 3.0, 5.0, 7.0, 9.0]
    assert p50(unsorted) == p50(ordered)
    assert p99(unsorted) == p99(ordered)


def test_percentiles_reject_empty_samples() -> None:
    for function in (p50, p95, p99):
        with pytest.raises(ValueError, match="不能为空"):
            function([])


@pytest.mark.parametrize(
    ("samples", "expected"),
    [
        (0, CONFIDENCE_LOW),
        (199, CONFIDENCE_LOW),
        (200, CONFIDENCE_MEDIUM),
        (499, CONFIDENCE_MEDIUM),
        (500, CONFIDENCE_HIGH),
        (510, CONFIDENCE_HIGH),
        (10_000, CONFIDENCE_HIGH),
    ],
)
def test_confidence_thresholds(samples: int, expected: str) -> None:
    assert confidence_for_samples(samples) == expected


# --------------------------------------------------------------------------- #
# 三口径汇总
# --------------------------------------------------------------------------- #


def test_summarize_latency_splits_total_model_and_overhead() -> None:
    pairs = [
        (100.0, 40),
        (200.0, None),  # 模型耗时未知：只计入 total/samples，不进入 model/overhead
        (300.0, 50),
        (400.0, 60),
        (500.0, 70),
    ]
    summary = summarize_latency(measured=True, runs=5, warmup=0, pairs=pairs)

    assert summary.samples == 5
    assert summary.model_known_samples == 4
    assert summary.p50_ms == 300.0
    assert summary.p95_ms == 500.0
    assert summary.p99_ms == 500.0
    # 旧别名与 totalP95Ms 同值（冻结 §1.5）。
    assert summary.total_p95_ms == summary.p95_ms == 500.0
    assert summary.total_p99_ms == 500.0
    assert summary.model_p95_ms == 70.0
    assert summary.model_p99_ms == 70.0
    # overhead 样本 = [60, 250, 340, 430]，最近秩 P95/P99 都是最大值 430。
    assert summary.overhead_p95_ms == 430.0
    assert summary.overhead_p99_ms == 430.0
    assert summary.confidence == CONFIDENCE_LOW


def test_summarize_latency_clamps_negative_overhead_to_zero() -> None:
    summary = summarize_latency(measured=True, runs=2, warmup=0, pairs=[(100.0, 150), (50.0, 20)])
    assert summary.model_known_samples == 2
    # max(0, 100-150)=0、max(0, 50-20)=30 → P95 取 30；不出现负数。
    assert summary.overhead_p95_ms == 30.0
    assert summary.overhead_p99_ms == 30.0


def test_summarize_latency_with_all_models_unknown_keeps_null() -> None:
    summary = summarize_latency(
        measured=True, runs=3, warmup=0, pairs=[(10.0, None), (20.0, None), (30.0, None)]
    )
    assert summary.samples == 3
    assert summary.model_known_samples == 0
    assert summary.model_p95_ms is None
    assert summary.model_p99_ms is None
    assert summary.overhead_p95_ms is None
    assert summary.overhead_p99_ms is None
    assert summary.p95_ms == 30.0
    assert summary.confidence == CONFIDENCE_LOW


def test_summarize_latency_not_measured_is_all_null() -> None:
    summary = summarize_latency(measured=False, runs=0, warmup=0, pairs=[])
    assert summary.measured is False
    assert summary.runs == 0
    assert summary.warmup == 0
    assert summary.samples == 0
    assert {summary.p50_ms, summary.p95_ms, summary.p99_ms} == {None}
    assert {summary.total_p95_ms, summary.total_p99_ms} == {None}
    assert {summary.model_p95_ms, summary.model_p99_ms} == {None}
    assert {summary.overhead_p95_ms, summary.overhead_p99_ms} == {None}
    assert summary.model_known_samples == 0
    assert summary.confidence == CONFIDENCE_LOW


def test_summarize_latency_high_confidence_uses_500_samples() -> None:
    pairs = [(float(index), 0) for index in range(500)]
    summary = summarize_latency(measured=True, runs=500, warmup=0, pairs=pairs)
    assert summary.samples == 500
    assert summary.confidence == CONFIDENCE_HIGH
    assert summary.model_p95_ms == 0.0
    assert summary.overhead_p95_ms == 474.0


# --------------------------------------------------------------------------- #
# Scorer 协议：逐样本 model latency
# --------------------------------------------------------------------------- #


class _LatencyScorer:
    """按调用序返回交替的已知/未知模型耗时；ranking 固定为候选原序。"""

    name: str = "latency-stub"

    def __init__(self, latencies: list[int | None]) -> None:
        self._latencies = latencies
        self._calls = 0

    def rank(self, case: RerankCase) -> list[str]:
        self._calls += 1
        return [candidate.id for candidate in case.candidates]

    def model_latency_ms(self) -> int | None:
        return self._latencies[(self._calls - 1) % len(self._latencies)]

    def close(self) -> None:
        return None


def test_evaluate_carries_model_latency_into_aggregate() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = _LatencyScorer([37, None])
    report = evaluate(cases, scorer, top_k=8, runs=2, warmup=0, candidate_target=20)

    latency = report.latency
    assert latency.samples == 15 * 2
    assert latency.model_known_samples == 15
    assert latency.model_p95_ms == 37.0
    assert latency.model_p99_ms == 37.0
    assert latency.overhead_p95_ms is not None
    assert latency.overhead_p95_ms >= 0.0
    assert report.candidate_target == 20

    document = report_document(report)
    aggregate = document["aggregate"]
    assert aggregate["samples"] == 30
    assert aggregate["modelKnownSamples"] == 15
    assert aggregate["modelP95Ms"] == 37.0
    assert aggregate["modelP99Ms"] == 37.0
    assert aggregate["candidateTarget"] == 20
    assert aggregate["confidence"] == CONFIDENCE_LOW
    assert aggregate["overheadP95Ms"] == latency.overhead_p95_ms
    assert aggregate["totalP95Ms"] == aggregate["p95Ms"] == latency.p95_ms
    assert math.isfinite(aggregate["totalP50Ms"])


def test_report_without_latency_measurement_is_byte_stable_and_null() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        first = evaluate(cases, scorer, top_k=8)
        second = evaluate(cases, scorer, top_k=8)
    finally:
        scorer.close()

    aggregate = report_document(first)["aggregate"]
    assert aggregate["p95Ms"] is None
    assert aggregate["totalP50Ms"] is None
    assert aggregate["totalP95Ms"] is None
    assert aggregate["totalP99Ms"] is None
    assert aggregate["overheadP95Ms"] is None
    assert aggregate["overheadP99Ms"] is None
    assert aggregate["modelP95Ms"] is None
    assert aggregate["modelP99Ms"] is None
    assert aggregate["samples"] == 0
    assert aggregate["modelKnownSamples"] == 0
    assert aggregate["confidence"] == CONFIDENCE_LOW
    assert aggregate["candidateTarget"] is None
    # canonical 口径不变：同一输入两次运行逐字节一致。
    assert canonical_report_bytes(report_document(first)) == canonical_report_bytes(
        report_document(second)
    )


def test_evaluate_rejects_out_of_range_candidate_target() -> None:
    cases = load_rerank_set(RERANK_SET)
    scorer = HeuristicScorer(top_k=8)
    try:
        for target in (0, 51):
            with pytest.raises(ValueError, match="candidate_target"):
                evaluate(cases, scorer, top_k=8, candidate_target=target)
    finally:
        scorer.close()


# --------------------------------------------------------------------------- #
# 候选补齐（纯函数）
# --------------------------------------------------------------------------- #


def _case(candidates: list[RerankCandidate]) -> RerankCase:
    ids = [candidate.id for candidate in candidates]
    return RerankCase(
        id="case-1",
        query="查询",
        candidates=tuple(candidates),
        labels=dict.fromkeys(ids, 0),
        supports=dict.fromkeys(ids, False),
    )


def test_pad_candidates_appends_deterministic_synthetic_candidates() -> None:
    case = _case(
        [
            RerankCandidate(id="c1", title="标题一", text="甲乙"),
            RerankCandidate(id="c2", title="标题二", text="丙丁"),
        ]
    )
    padded = pad_candidates(case, 5)

    assert padded.id == case.id
    assert padded.query == case.query
    assert [candidate.id for candidate in padded.candidates] == [
        "c1",
        "c2",
        "pad-1",
        "pad-2",
        "pad-3",
    ]
    for candidate in padded.candidates[2:]:
        assert candidate.title
        assert 1 <= len(candidate.text) <= 2000
        assert padded.labels[candidate.id] == 0
        assert padded.supports[candidate.id] is False
    # 既有候选的标签/支持标记原样保留。
    assert padded.labels["c1"] == case.labels["c1"]
    assert padded.supports["c2"] is case.supports["c2"]


def test_pad_candidates_cycles_existing_text_and_caps_at_2000() -> None:
    case = _case(
        [
            RerankCandidate(id="c1", title="", text="abc"),
            RerankCandidate(id="c2", title="", text="de"),
        ]
    )
    padded = pad_candidates(case, 3)
    synthetic = padded.candidates[2].text

    assert len(synthetic) == 2000
    assert synthetic == ("abcde" * 400)[:2000]
    assert synthetic.startswith("abcdeabcde")


def test_pad_candidates_single_long_source_is_truncated_to_contract_limit() -> None:
    source = "长" * 5000  # loader 不会放行，但纯函数必须兜住 2000 上限
    case = _case([RerankCandidate(id="c1", title="", text=source)])
    synthetic = pad_candidates(case, 2).candidates[1].text
    assert len(synthetic) == 2000


def test_pad_candidates_rejects_all_empty_sources() -> None:
    """纯函数兜底：没有任何非空正文时不得拼出违反契约的空 text。"""

    case = _case([RerankCandidate(id="c1", title="", text="")])
    with pytest.raises(ValueError, match="非空候选正文"):
        pad_candidates(case, 2)


def test_pad_candidates_is_noop_when_candidates_already_enough() -> None:
    case = _case([RerankCandidate(id="c1", title="", text="ab")])
    assert pad_candidates(case, 1) is case
    assert pad_candidates(case, 1).candidates == case.candidates


def test_pad_candidates_avoids_id_collisions() -> None:
    case = _case([RerankCandidate(id="pad-1", title="", text="ab")])
    padded = pad_candidates(case, 3)
    ids = [candidate.id for candidate in padded.candidates]
    assert ids == ["pad-1", "pad-1-1", "pad-2"]
    assert len(set(ids)) == len(ids)


@pytest.mark.parametrize("target", [0, -1, 51])
def test_pad_candidates_rejects_out_of_range_target(target: int) -> None:
    case = _case([RerankCandidate(id="c1", title="", text="ab")])
    with pytest.raises(ValueError, match="target"):
        pad_candidates(case, target)


# --------------------------------------------------------------------------- #
# worker 响应的 modelLatencyMs
# --------------------------------------------------------------------------- #


def _worker_response(model_latency: object, request: httpx.Request) -> httpx.Response:
    body = json.loads(request.content)
    scores = [
        {"id": candidate["id"], "score": round(1.0 - index / 100, 2)}
        for index, candidate in enumerate(body["candidates"])
    ]
    payload: dict[str, Any] = {"schemaVersion": 1, "scores": scores}
    if model_latency is not ...:
        payload["modelLatencyMs"] = model_latency
    return httpx.Response(200, json=payload)


def _worker_scorer(model_latency: object) -> WorkerScorer:
    def handler(request: httpx.Request) -> httpx.Response:
        return _worker_response(model_latency, request)

    return WorkerScorer(
        "http://worker.test",
        token_env=TOKEN_ENV,
        transport=httpx.MockTransport(handler),
    )


def test_worker_scorer_reads_model_latency(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    scorer = _worker_scorer(1882)
    try:
        ranking = scorer.rank(load_rerank_set(RERANK_SET)[0])
        assert ranking
        assert scorer.model_latency_ms() == 1882
    finally:
        scorer.close()


@pytest.mark.parametrize("missing", [..., None])
def test_worker_scorer_missing_model_latency_is_unknown(
    monkeypatch: pytest.MonkeyPatch, missing: object
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    scorer = _worker_scorer(missing)
    try:
        scorer.rank(load_rerank_set(RERANK_SET)[0])
        assert scorer.model_latency_ms() is None
    finally:
        scorer.close()


@pytest.mark.parametrize("invalid", [-1, 1.5, "812", True])
def test_worker_scorer_rejects_malformed_model_latency(
    monkeypatch: pytest.MonkeyPatch, invalid: object
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    scorer = _worker_scorer(invalid)
    try:
        with pytest.raises(WorkerError, match="modelLatencyMs"):
            scorer.rank(load_rerank_set(RERANK_SET)[0])
    finally:
        scorer.close()
