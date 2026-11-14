"""D10：评测报告证据标签显式化。"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.eval import decide as decide_eval
from ai_collab_worker.eval import memory as memory_eval
from ai_collab_worker.eval import planning as planning_eval
from ai_collab_worker.eval.canonical import canonicalize_document

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DECIDE_SET = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v1.jsonl"
PLANNING_SET = PROJECT_ROOT / "tests" / "fixtures" / "planning_set" / "planning_baseline.v1.jsonl"
MEMORY_SET = PROJECT_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v1.jsonl"


def _memory_document(**kwargs: object) -> dict[str, object]:
    report = memory_eval.evaluate(memory_eval.load_memory_set(MEMORY_SET))
    return memory_eval.report_document(report, **kwargs)


def _planning_document(**kwargs: object) -> dict[str, object]:
    report = planning_eval.evaluate(planning_eval.load_planning_set(PLANNING_SET))
    return planning_eval.report_document(report, **kwargs)


def _decide_document(**kwargs: object) -> dict[str, object]:
    report = decide_eval.evaluate(
        decide_eval.load_decide_set(DECIDE_SET), decide_eval.ReplayScorer()
    )
    return decide_eval.report_document(report, **kwargs)


def test_defaults_are_unchanged() -> None:
    assert _memory_document()["evidence"] == memory_eval.EVIDENCE_STUB
    assert _memory_document()["qualityClaim"] == memory_eval.QUALITY_CLAIM_NOT_VERIFIED
    assert _planning_document()["evidence"] == planning_eval.EVIDENCE_STUB
    assert _decide_document()["evidence"] == decide_eval.EVIDENCE_STUB


def test_explicit_labels_flow_into_all_three_reports() -> None:
    for document in (
        _memory_document(
            evidence="LIVE_VERIFIED (staging, real model)",
            quality_claim="QUALITY_OK",
            model="gemini-3.1-flash-lite",
        ),
        _planning_document(
            evidence="LIVE_VERIFIED (staging, real model)",
            quality_claim="QUALITY_OK",
            model="gemini-3.1-flash-lite",
        ),
        _decide_document(
            evidence="LIVE_VERIFIED (staging, real model)",
            quality_claim="QUALITY_OK",
            model="gemini-3.1-flash-lite",
        ),
    ):
        assert document["evidence"] == "LIVE_VERIFIED (staging, real model)"
        assert document["qualityClaim"] == "QUALITY_OK"
        assert document["model"] == "gemini-3.1-flash-lite"


def test_explicit_labels_change_sealed_bytes() -> None:
    default = _memory_document()
    explicit = _memory_document(evidence="LIVE_VERIFIED", quality_claim="QUALITY_OK")
    sealed_default = memory_eval.seal_report(default)
    sealed_explicit = memory_eval.seal_report(explicit)

    assert canonicalize_document(sealed_default) != canonicalize_document(sealed_explicit)
    assert sealed_default["sha256"] != sealed_explicit["sha256"]


def test_explicit_non_stub_evidence_removes_stub_limitations() -> None:
    document = _memory_document(evidence="LIVE_VERIFIED")

    assert "只在 stub 冻结集输出上测量" not in str(document["limitations"])
    assert "显式指定" in str(document["limitations"])


@pytest.mark.parametrize("value", ["", "   "])
def test_empty_evidence_is_rejected(value: str) -> None:
    with pytest.raises(ValueError, match="不能为空"):
        _memory_document(evidence=value)
    with pytest.raises(ValueError, match="不能为空"):
        _planning_document(quality_claim=value)
    with pytest.raises(ValueError, match="不能为空"):
        _decide_document(model=value)


def test_subject_metadata_is_optional_and_explicit() -> None:
    assert "subject" not in _memory_document()
    assert _memory_document(subject="worker-eval")["subject"] == "worker-eval"
