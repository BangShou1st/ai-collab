"""Rerank 请求/响应模型的校验边界（unit）。"""

from __future__ import annotations

import math

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.rerank import (
    DEFAULT_RERANK_DEADLINE_MS,
    DEFAULT_TOP_K,
    RerankCandidate,
    RerankRequest,
    RerankResponse,
    RerankScore,
    RerankUsage,
    RerankUsageSource,
)


def _candidate(index: int, *, text: str = "正文", **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {"id": f"c{index}", "text": text}
    payload.update(overrides)
    return payload


def _payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {"query": "核心架构是什么", "candidates": [_candidate(1)]}
    payload.update(overrides)
    return payload


def test_minimal_request_uses_frozen_defaults() -> None:
    request = RerankRequest.model_validate(_payload())

    assert request.schema_version == 1
    assert request.top_k == DEFAULT_TOP_K == 8
    assert request.deadline_ms == DEFAULT_RERANK_DEADLINE_MS == 3000
    assert request.candidates[0].title == ""


@pytest.mark.parametrize("version", [0, 2, "1", True, None])
def test_schema_version_must_be_literal_one(version: object) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(schemaVersion=version))


@pytest.mark.parametrize("query", ["", " ", "\t\n", "a" * 4001])
def test_query_length_and_blank_rejected(query: str) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(query=query))


def test_query_max_length_and_trimmed_content_accepted() -> None:
    assert RerankRequest.model_validate(_payload(query="a" * 4000)).query == "a" * 4000
    assert RerankRequest.model_validate(_payload(query="  架构  ")).query == "  架构  "


@pytest.mark.parametrize("top_k", [1, 8, 50])
def test_top_k_accepts_frozen_range(top_k: int) -> None:
    assert RerankRequest.model_validate(_payload(topK=top_k)).top_k == top_k


@pytest.mark.parametrize("top_k", [0, 51, -1, 1.5, None])
def test_top_k_rejects_out_of_range(top_k: object) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(topK=top_k))


@pytest.mark.parametrize("count", [1, 49, 50])
def test_candidate_count_accepts_one_to_fifty(count: int) -> None:
    request = RerankRequest.model_validate(
        _payload(candidates=[_candidate(index) for index in range(count)])
    )
    assert len(request.candidates) == count


@pytest.mark.parametrize("count", [0, 51])
def test_candidate_count_rejects_out_of_range(count: int) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(
            _payload(candidates=[_candidate(index) for index in range(count)])
        )


def test_duplicate_candidate_id_rejected() -> None:
    with pytest.raises(ValidationError, match="唯一"):
        RerankRequest.model_validate(_payload(candidates=[_candidate(1), _candidate(2, id="c1")]))


@pytest.mark.parametrize("candidate_id", ["a" * 128, "c1", "中文-id"])
def test_candidate_id_length_boundaries(candidate_id: str) -> None:
    request = RerankRequest.model_validate(_payload(candidates=[_candidate(1, id=candidate_id)]))
    assert request.candidates[0].id == candidate_id


@pytest.mark.parametrize("candidate_id", ["", "a" * 129])
def test_candidate_id_out_of_bounds(candidate_id: str) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(candidates=[_candidate(1, id=candidate_id)]))


@pytest.mark.parametrize("text", ["a", "a" * 2000])
def test_text_length_boundaries(text: str) -> None:
    request = RerankRequest.model_validate(_payload(candidates=[_candidate(1, text=text)]))
    assert request.candidates[0].text == text


@pytest.mark.parametrize("text", ["", "a" * 2001])
def test_text_out_of_bounds(text: str) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(candidates=[_candidate(1, text=text)]))


@pytest.mark.parametrize("title", ["", "标题", "a" * 500])
def test_title_boundaries(title: str) -> None:
    request = RerankRequest.model_validate(_payload(candidates=[_candidate(1, title=title)]))
    assert request.candidates[0].title == title


def test_title_too_long_rejected() -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(candidates=[_candidate(1, title="a" * 501)]))


@pytest.mark.parametrize("deadline", [1, 3000, 30000])
def test_deadline_accepts_frozen_range(deadline: int) -> None:
    assert RerankRequest.model_validate(_payload(deadlineMs=deadline)).deadline_ms == deadline


@pytest.mark.parametrize("deadline", [0, 30001, "abc", 3.5])
def test_deadline_rejects_out_of_range(deadline: object) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(deadlineMs=deadline))


@pytest.mark.parametrize(
    "extra",
    [
        {"unexpected": 1},
        {"candidates": [_candidate(1, unexpected=2)]},
    ],
)
def test_request_forbids_extra_fields(extra: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        RerankRequest.model_validate(_payload(**extra))


def test_candidate_forbids_extra_fields() -> None:
    with pytest.raises(ValidationError):
        RerankCandidate.model_validate({"id": "c1", "text": "t", "score": 0.5})


def test_request_is_frozen() -> None:
    request = RerankRequest.model_validate(_payload())
    with pytest.raises(ValidationError):
        request.top_k = 3


def test_response_wire_shape_is_camel_case() -> None:
    response = RerankResponse(
        scores=(RerankScore(id="c1", score=0.83),),
        model_alias="rerank-default",
        usage=RerankUsage(
            input_tokens=10,
            output_tokens=2,
            source=RerankUsageSource.PROVIDER,
        ),
    )

    assert response.model_dump(by_alias=True, mode="json") == {
        "schemaVersion": 1,
        "scores": [{"id": "c1", "score": 0.83}],
        "modelAlias": "rerank-default",
        "usage": {"inputTokens": 10, "outputTokens": 2, "source": "PROVIDER"},
        "modelLatencyMs": None,
    }


def test_response_model_latency_is_optional_and_non_negative() -> None:
    """W3.1 冻结 §1.4：`modelLatencyMs` 只透传上游 latencyMs，未知即 null，0 是合法真值。"""

    base = {
        "scores": (RerankScore(id="c1", score=0.5),),
        "model_alias": "rerank-default",
    }
    assert RerankResponse(**base).model_latency_ms is None
    assert RerankResponse(**base, model_latency_ms=0).model_latency_ms == 0
    assert RerankResponse(**base, model_latency_ms=1882).model_latency_ms == 1882
    dumped = RerankResponse(**base, model_latency_ms=1882).model_dump(by_alias=True, mode="json")
    assert dumped["modelLatencyMs"] == 1882
    with pytest.raises(ValidationError):
        RerankResponse(**base, model_latency_ms=-1)
    with pytest.raises(ValidationError):
        RerankResponse.model_validate(
            {
                "schemaVersion": 1,
                "scores": [{"id": "c1", "score": 0.5}],
                "modelAlias": "rerank-default",
                "modelLatencyMs": "not-a-number",
            }
        )


def test_response_unknown_usage_keeps_tokens_unset() -> None:
    usage = RerankUsage()

    assert usage.source is RerankUsageSource.UNKNOWN
    assert usage.input_tokens is None
    assert usage.output_tokens is None
    assert usage.model_dump(by_alias=True, mode="json") == {
        "inputTokens": None,
        "outputTokens": None,
        "source": "UNKNOWN",
    }


@pytest.mark.parametrize("score", [0.0, 0.5, 1.0])
def test_score_accepts_closed_interval(score: float) -> None:
    assert RerankScore(id="c1", score=score).score == score


@pytest.mark.parametrize("score", [-0.01, 1.01, math.nan, math.inf, -math.inf])
def test_score_rejects_out_of_range_or_non_finite(score: float) -> None:
    with pytest.raises(ValidationError):
        RerankScore(id="c1", score=score)


def test_response_rejects_duplicate_score_ids() -> None:
    with pytest.raises(ValidationError, match="重复"):
        RerankResponse(
            scores=(RerankScore(id="c1", score=0.1), RerankScore(id="c1", score=0.2)),
            model_alias="rerank-default",
        )


def test_response_requires_at_least_one_score() -> None:
    with pytest.raises(ValidationError):
        RerankResponse(scores=(), model_alias="rerank-default")


def test_response_accepts_camel_case_payload() -> None:
    response = RerankResponse.model_validate(
        {
            "schemaVersion": 1,
            "scores": [{"id": "c1", "score": 0.4}],
            "modelAlias": "rerank-default",
            "usage": {"inputTokens": None, "outputTokens": None, "source": "UNKNOWN"},
        }
    )

    assert response.model_alias == "rerank-default"
    assert response.usage.source is RerankUsageSource.UNKNOWN
