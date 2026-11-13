"""``ai-eval decide`` 冻结集读取、replay 评测与报告口径（unit）。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_document,
    canonicalize_path,
    digest,
)
from ai_collab_worker.eval.decide import (
    CONFIDENCE_LOW,
    EVIDENCE_STUB,
    QUALITY_CLAIM_NOT_VERIFIED,
    SUBSET_ADVERSARIAL,
    SUBSET_GOLDEN,
    ReplayScorer,
    build_run_id,
    build_worker_payload,
    canonical_report_bytes,
    evaluate,
    load_decide_set,
    report_document,
    seal_report,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DECIDE_SET = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v1.jsonl"
DECIDE_SET_V2 = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v2.jsonl"
DECIDE_SET_V3 = PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v3.jsonl"


def _write_set(tmp_path: Path, cases: list[dict[str, object]]) -> Path:
    path = tmp_path / "set.jsonl"
    path.write_text(
        "\n".join(json.dumps(case, ensure_ascii=False) for case in cases) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return path


def _valid_case(case_id: str = "x-001", **overrides: object) -> dict[str, object]:
    case: dict[str, object] = {
        "id": case_id,
        "subset": SUBSET_GOLDEN,
        "scenario": {"code": "PROJECT_RESEARCH", "category": "SCENARIO"},
        "goal": "研究项目",
        "locale": "zh-CN",
        "context": {"facts": [], "messages": [], "observations": []},
        "tools": [
            {
                "name": "search_project_knowledge",
                "description": "检索",
                "inputSchema": {"type": "object"},
                "risk": "READ_ONLY",
            }
        ],
        "policy": {"allowWrite": False, "allowExternal": False},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
        "expected": {"decision": "FINAL"},
        "modelOutput": '{"decision":"FINAL"}',
    }
    case.update(overrides)
    return case


def test_frozen_set_loads_and_covers_required_shapes() -> None:
    cases = load_decide_set(DECIDE_SET)

    assert len(cases) == 32
    assert [case.id for case in cases] == sorted(case.id for case in cases)
    assert all(case.model_output for case in cases)

    decisions = {case.expected_decision.value for case in cases}
    assert decisions == {"CALL_TOOL", "FINAL", "ASK_USER", "NO_OP"}
    assert sum(1 for case in cases if case.subset == SUBSET_GOLDEN) == 20
    assert sum(1 for case in cases if case.subset == SUBSET_ADVERSARIAL) == 12
    assert {case.request.locale for case in cases} >= {"zh-CN", "en-US", "en-GB"}
    assert any(case.expected_tool_name == "search_project_knowledge" for case in cases)
    assert any(len(case.request.tools) == 4 for case in cases)
    assert any(sum(len(fact) for fact in case.request.context.facts) > 4000 for case in cases)
    assert any(
        any(
            tool.name == "create_task" and tool.risk.value == "WRITE" for tool in case.request.tools
        )
        for case in cases
    )


def test_v2_is_minimal_d020_goal_correction_and_keeps_v1_untouched() -> None:
    """v2 只把 d-020 的 goal 改成可支撑 FINAL 的具体常识题，其余原样。"""

    assert digest(canonicalize_path(DECIDE_SET)) == (
        "1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58"
    )
    assert digest(canonicalize_path(DECIDE_SET_V2)) == (
        "59e96bbe38c2f96fea51d86d25132f07d0110d6fa97e81a2bc48543db1674666"
    )

    v1_cases = load_decide_set(DECIDE_SET)
    v2_cases = load_decide_set(DECIDE_SET_V2)
    assert [case.id for case in v1_cases] == [case.id for case in v2_cases]

    changed: list[str] = []
    for v1, v2 in zip(v1_cases, v2_cases, strict=True):
        assert v1.subset == v2.subset
        assert v1.expected_decision == v2.expected_decision
        assert v1.expected_tool_name == v2.expected_tool_name
        assert v1.model_output == v2.model_output
        if v1.request.model_dump(by_alias=True, mode="json") != v2.request.model_dump(
            by_alias=True, mode="json"
        ):
            changed.append(v1.id)
        if v1.id == "d-020":
            assert v1.request.goal == "空上下文下回答一个已知事实。"
            assert v2.request.goal == "空上下文下直接回答一个已知事实：一年有多少个月？"
            assert v1.request.context.model_dump(mode="json") == {
                "facts": [],
                "messages": [],
                "observations": [],
            }
            assert v2.request.context.model_dump(mode="json") == {
                "facts": [],
                "messages": [],
                "observations": [],
            }
            assert v2.expected_decision.value == "FINAL"

    assert changed == ["d-020"]


def test_v3_is_minimal_d014_d020_goal_correction_and_keeps_labels() -> None:
    """v3 只补齐两个不可执行 goal，expected/modelOutput 与 v1 保持一致。"""

    assert digest(canonicalize_path(DECIDE_SET)) == (
        "1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58"
    )
    assert digest(canonicalize_path(DECIDE_SET_V3)) == (
        "55927589c2ab1e6772ac8db044115d47cb4ceb6685c3500576affa326eb759e1"
    )

    v1_cases = load_decide_set(DECIDE_SET)
    v3_cases = load_decide_set(DECIDE_SET_V3)
    assert [case.id for case in v1_cases] == [case.id for case in v3_cases]

    changed: list[str] = []
    for v1, v3 in zip(v1_cases, v3_cases, strict=True):
        assert v1.subset == v3.subset
        assert v1.expected_decision == v3.expected_decision
        assert v1.expected_tool_name == v3.expected_tool_name
        assert v1.model_output == v3.model_output
        if v1.request.model_dump(by_alias=True, mode="json") != v3.request.model_dump(
            by_alias=True, mode="json"
        ):
            changed.append(v1.id)
        if v1.id == "d-014":
            assert v1.request.goal == "回答包含引号与换行的上下文问题。"
            assert v3.request.goal == "根据上下文回答：风险描述中引号与换行的内容是什么？"
            assert v3.expected_decision.value == "FINAL"
        if v1.id == "d-020":
            assert v3.request.goal == "空上下文下直接回答一个已知事实：一年有多少个月？"
            assert v3.expected_decision.value == "FINAL"

    assert changed == ["d-014", "d-020"]


def test_replay_scores_frozen_set() -> None:
    report = evaluate(load_decide_set(DECIDE_SET), ReplayScorer())

    assert report.scorer == "replay"
    assert report.evidence == EVIDENCE_STUB
    assert len(report.cases) == 32
    assert report.decision_agreement == pytest.approx(20 / 32)
    assert report.json_validity == pytest.approx(20 / 32)
    assert report.invalid_tool_calls == 2
    assert report.schema_violations == 10
    assert report.confidence == CONFIDENCE_LOW


def test_report_document_shape_and_sha256() -> None:
    report = evaluate(load_decide_set(DECIDE_SET), ReplayScorer())
    document = seal_report(report_document(report))

    assert set(document) == {
        "schemaVersion",
        "scorer",
        "cases",
        "aggregate",
        "evidence",
        "qualityClaim",
        "sha256",
    }
    assert document["qualityClaim"] == QUALITY_CLAIM_NOT_VERIFIED
    aggregate = document["aggregate"]
    assert set(aggregate) == {
        "cases",
        "confidence",
        "decisionAgreement",
        "invalidToolCalls",
        "jsonValidityRate",
        "schemaViolations",
        "scorer",
        "bySubset",
    }
    assert aggregate["cases"] == 32
    assert aggregate["scorer"] == "replay"
    assert aggregate["confidence"] == CONFIDENCE_LOW

    body = {key: value for key, value in document.items() if key != "sha256"}
    assert document["sha256"] == digest(canonicalize_document(body))

    first = canonical_report_bytes(report_document(report))
    second = canonical_report_bytes(report_document(report))
    assert first == second
    assert not first.endswith(b"\n")
    assert b"\\u" not in first


def test_by_subset_separates_golden_and_adversarial() -> None:
    report = evaluate(load_decide_set(DECIDE_SET), ReplayScorer())
    aggregate = report_document(report)["aggregate"]
    by_subset = aggregate["bySubset"]

    assert set(by_subset) == {SUBSET_GOLDEN, SUBSET_ADVERSARIAL}
    assert by_subset["golden"] == {
        "cases": 20,
        "decisionAgreement": 1.0,
        "jsonValidityRate": 1.0,
        "schemaViolations": 0,
    }
    assert by_subset["adversarial"] == {
        "cases": 12,
        "rejectedRate": 1.0,
        "invalidToolCalls": 2,
    }
    # 顶层仍是全体分母（含刻意负样本），不能直接对照 §10.2 目标。
    assert aggregate["cases"] == 32
    assert aggregate["decisionAgreement"] == pytest.approx(20 / 32)
    assert aggregate["jsonValidityRate"] == pytest.approx(20 / 32)

    # 逐 case 报告也带 subset，便于审查人复核分组。
    cases = report_document(report)["cases"]
    assert {case["subset"] for case in cases} == {SUBSET_GOLDEN, SUBSET_ADVERSARIAL}
    golden_cases = [case for case in cases if case["subset"] == SUBSET_GOLDEN]
    adversarial_cases = [case for case in cases if case["subset"] == SUBSET_ADVERSARIAL]
    assert all(case["agreement"] for case in golden_cases)
    assert all(case["actual"]["jsonValid"] for case in golden_cases)
    assert all(not case["actual"]["jsonValid"] for case in adversarial_cases)


def test_loader_requires_explicit_subset(tmp_path: Path) -> None:
    missing = _valid_case()
    del missing["subset"]
    with pytest.raises(DatasetError, match="subset"):
        load_decide_set(_write_set(tmp_path, [missing]))

    for invalid in ("unknown", "", 1, None):
        with pytest.raises(DatasetError, match="subset"):
            load_decide_set(_write_set(tmp_path, [_valid_case(subset=invalid)]))


def test_report_contains_no_frozen_case_text() -> None:
    report = evaluate(load_decide_set(DECIDE_SET), ReplayScorer())
    payload = canonical_report_bytes(report_document(report))

    # 报告只含 id/决策/工具名/计数，不回显 goal/context/modelOutput。
    case = next(case for case in load_decide_set(DECIDE_SET) if case.id == "d-001")
    assert case.request.goal.encode("utf-8") not in payload
    assert case.model_output.encode("utf-8") not in payload
    assert b"SECRET" not in payload


def test_build_worker_payload_is_deterministic_and_identity_only() -> None:
    case = load_decide_set(DECIDE_SET)[0]
    first = build_worker_payload(case)
    second = build_worker_payload(case)

    assert first == second
    assert set(first) == {
        "schemaVersion",
        "runId",
        "attempt",
        "turnNo",
        "snapshotHash",
        "scenario",
        "goal",
        "locale",
        "context",
        "tools",
        "policy",
        "budget",
    }
    assert first["runId"] == build_run_id(case.id)
    assert len(first["runId"]) <= 128
    assert str(first["snapshotHash"]).startswith("sha256:")
    assert len(str(first["snapshotHash"])) == len("sha256:") + 32
    assert first["attempt"] == 1
    assert first["turnNo"] == 0
    assert "expected" not in first
    assert "modelOutput" not in first


def test_loader_rejects_unknown_and_missing_fields(tmp_path: Path) -> None:
    with pytest.raises(DatasetError, match="未定义字段"):
        load_decide_set(_write_set(tmp_path, [_valid_case(extra=1)]))
    with pytest.raises(DatasetError, match="缺少字段"):
        load_decide_set(_write_set(tmp_path, [{"id": "x"}]))


def test_loader_rejects_bad_expected_labels(tmp_path: Path) -> None:
    with pytest.raises(DatasetError, match=r"expected\.decision"):
        load_decide_set(_write_set(tmp_path, [_valid_case(expected={"decision": "DELEGATE"})]))
    with pytest.raises(DatasetError, match="必须给 toolName"):
        load_decide_set(_write_set(tmp_path, [_valid_case(expected={"decision": "CALL_TOOL"})]))
    with pytest.raises(DatasetError, match="不在本次 tools 内"):
        load_decide_set(
            _write_set(
                tmp_path,
                [_valid_case(expected={"decision": "CALL_TOOL", "toolName": "delete_all_tasks"})],
            )
        )
    with pytest.raises(DatasetError, match="不得给 toolName"):
        load_decide_set(
            _write_set(tmp_path, [_valid_case(expected={"decision": "FINAL", "toolName": "x"})])
        )


def test_loader_rejects_contract_violations(tmp_path: Path) -> None:
    with pytest.raises(DatasetError, match="请求契约"):
        load_decide_set(_write_set(tmp_path, [_valid_case(locale="zh_CN")]))
    with pytest.raises(DatasetError, match="modelOutput"):
        load_decide_set(_write_set(tmp_path, [_valid_case(modelOutput=1)]))


def test_loader_rejects_empty_set(tmp_path: Path) -> None:
    path = tmp_path / "empty.jsonl"
    path.write_text("", encoding="utf-8")

    with pytest.raises(DatasetError, match="不能为空"):
        load_decide_set(path)
