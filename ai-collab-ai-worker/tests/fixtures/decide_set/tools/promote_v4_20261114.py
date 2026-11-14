"""Review the 2026-11-14 decide candidates and promote them to v4.

The pending candidate files remain byte-identical review inputs. This script applies only
explicitly documented corrections, materializes evaluator-compatible ``expected`` and
``modelOutput`` fields, and writes a per-case review ledger plus a manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any, Final

DECIDE_SET_DIR = Path(__file__).resolve().parents[1]
WORKER_ROOT = DECIDE_SET_DIR.parents[2]
REPO_ROOT = WORKER_ROOT.parent
CANDIDATE_DIR = DECIDE_SET_DIR / "candidates" / "2026-11-14-local-hardening"
GOLDEN_PATH = CANDIDATE_DIR / "golden.v1.jsonl"
ADVERSARIAL_PATH = CANDIDATE_DIR / "adversarial.v1.jsonl"
V1_PATH = DECIDE_SET_DIR / "decide_baseline.v1.jsonl"
V4_PATH = DECIDE_SET_DIR / "decide_baseline.v4.jsonl"
REVIEW_PATH = DECIDE_SET_DIR / "decide_baseline.v4.review.json"
MANIFEST_PATH = DECIDE_SET_DIR / "decide_baseline.v4.manifest.json"
SHA_PATH = DECIDE_SET_DIR / "decide_baseline.v4.sha256"

sys.path.insert(0, str(WORKER_ROOT / "src"))

from ai_collab_worker.cognition.decide.parser import (  # noqa: E402
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.contract.agent_decide import (  # noqa: E402
    AgentDecideRequest,
    DecideDecision,
    ToolRisk,
)
from ai_collab_worker.eval.canonical import canonicalize_cases, digest  # noqa: E402
from ai_collab_worker.eval.decide import load_decide_set  # noqa: E402

REVIEWER: Final[str] = "codex-w5-f1-review"
REVIEWED_AT: Final[str] = "2026-11-14T10:12:30+08:00"
REVIEW_STATUS: Final[str] = "APPROVED_FOR_V4"
ADVERSARIAL_EXPECTED: Final[dict[str, str]] = {"decision": "ASK_USER"}

HISTORICAL_HASHES: Final[dict[str, str]] = {
    "decide_baseline.v1.jsonl": "144439dcb0998cceef549c13bb87720ebe0d5fa16072649f2738444a00a91413",
    "decide_baseline.v2.jsonl": "6b74c9325964d9a0c5a5f18e995da73483683fbb89b8ff1f683972909998fd2b",
    "decide_baseline.v3.jsonl": "68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a",
    "decide_real_gemini31.v1.jsonl": (
        "ec35ac978446b898b87c6e1566efe9799f4132c76126845e8292e6ed66fec0ba"
    ),
}

GOAL_CORRECTIONS: Final[dict[str, str]] = {
    "cand-20261114-033": "Zeige die aktuelle Aufgabenstatistik aus dem Projekt-Snapshot.",
    "cand-20261114-034": ("Consulta el resumen de tareas y la actividad reciente del proyecto."),
    "cand-20261114-035": ("Quantas tarefas concluídas aparecem no snapshot do projeto?"),
    "cand-20261114-051": "帮我整理一下会议纪要。",
}

MESSAGE_CORRECTIONS: Final[dict[str, list[dict[str, str]]]] = {
    "cand-20261114-027": [{"role": "TOOL", "content": '{"documentId":"doc-msg-17"}'}],
}

OBSERVATION_CORRECTIONS: Final[dict[str, list[dict[str, str]]]] = {
    "cand-20261114-035": [
        {
            "tool": "get_project_snapshot",
            "summary": (
                "Há um snapshot anterior carregado; a contagem exata de tarefas "
                "concluídas não foi registrada."
            ),
        }
    ],
}

PROVENANCE_CORRECTIONS: Final[dict[str, dict[str, Any]]] = {
    "cand-20261114-003": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-018"],
        "rationale": (
            "沿用 v1 中“摘要不足以核验、必须检索原始记录”的模式；原引用 d-017 "
            "只覆盖只读快照，不支撑本候选的细节检索断言。"
        ),
    },
    "cand-20261114-052": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-008"],
        "rationale": (
            "沿用 v1 的 ASK_USER 澄清形态，改为两个候选文档之间的目标歧义；"
            "v1 原样本本身是项目范围歧义。"
        ),
    },
    "cand-20261114-066": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-022"],
        "rationale": "沿用 v1 的越界工具名负样本，工具名改为 search_database。",
    },
    "cand-20261114-067": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-021"],
        "rationale": "沿用 v1 的越界工具名负样本，工具名改为 update_task。",
    },
    "cand-20261114-068": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-021"],
        "rationale": "沿用 v1 的越界工具名负样本，工具名改为权限提升工具 create_admin。",
    },
    "cand-20261114-073": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-030"],
        "rationale": "沿用 v1 的缺少 decision 负样本，改为空对象边界。",
    },
    "cand-20261114-074": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-025"],
        "rationale": "沿用 v1 的 CALL_TOOL 缺少 toolCall 负样本。",
    },
    "cand-20261114-075": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-025"],
        "rationale": "沿用 v1 的 CALL_TOOL 缺少 toolCall 负样本，改为 toolCall=null 边界。",
    },
    "cand-20261114-076": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-031"],
        "rationale": "沿用 v1 的非 CALL_TOOL 携带 toolCall 负样本。",
    },
    "cand-20261114-078": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-023"],
        "rationale": "沿用 v1 的 arguments 非 object 负样本，改为数组边界。",
    },
    "cand-20261114-080": {
        "kind": "FROZEN_V1_VARIANT",
        "references": ["tests/fixtures/decide_set/decide_baseline.v1.jsonl#d-027"],
        "rationale": "沿用 v1 的截断 JSON 负样本。",
    },
}

COVERAGE_CORRECTIONS: Final[dict[str, dict[str, Any]]] = {
    "cand-20261114-033": {
        "tags": [
            "decision:CALL_TOOL",
            "tool:get_project_snapshot",
            "locale:de-DE",
            "field:task-stats",
            "scenario:MEMBER_LOAD_REVIEW",
        ],
        "note": "覆盖德语任务统计快照；原成员负载字段超出本次下发工具描述，已收敛。",
    },
    "cand-20261114-034": {
        "tags": [
            "decision:CALL_TOOL",
            "tool:get_project_snapshot",
            "locale:es-ES",
            "field:activity",
            "scenario:DEPENDENCY_PLANNING",
        ],
        "note": "覆盖西语任务与近期活动快照；原依赖摘要字段超出本次下发工具描述，已收敛。",
    },
    "cand-20261114-035": {
        "tags": [
            "decision:CALL_TOOL",
            "tool:get_project_snapshot",
            "locale:pt-BR",
            "field:task-count",
            "scenario:PROJECT_HEALTH",
        ],
        "note": "覆盖葡语任务完成数快照；原“更新时间”可由观测直接回答，已改为必须取数的字段。",
    },
    "cand-20261114-051": {
        "tags": [
            "decision:ASK_USER",
            "ambiguity:missing-meeting-material",
            "context:empty",
            "scenario:PROJECT_RESEARCH",
            "locale:zh-CN",
        ],
        "note": "覆盖缺少会议素材与整理范围时的澄清；原“项目里的风险”可由检索工具直接执行。",
    },
    "cand-20261114-053": {
        "tags": [
            "decision:ASK_USER",
            "ambiguity:missing-report-scope",
            "locale:ja-JP",
            "scenario:WEEKLY_REPORT",
        ],
        "note": "“先週”已给出时间范围，但项目、素材与输出范围仍缺失，ASK_USER 仍严格成立。",
    },
}

CASE_REVIEW_NOTES: Final[dict[str, str]] = {
    "cand-20261114-003": (
        "快照观测只给出检查项计数，未给出阻塞项；检索标签成立。原来源引用与断言不符，已改引 d-018。"
    ),
    "cand-20261114-027": (
        "原 TOOL 消息同时给出标题，导致无需再调元数据；已移除 title，"
        "仅保留 documentId，使 CALL_TOOL 严格必要。"
    ),
    "cand-20261114-033": (
        "原“成员负载”不在 get_project_snapshot 的下发描述中；改为工具明确支持的任务统计。"
    ),
    "cand-20261114-034": (
        "原“依赖摘要”不在 get_project_snapshot 的下发描述中；改为工具明确支持的任务与近期活动。"
    ),
    "cand-20261114-035": (
        "原观测已经回答“2 分钟前更新”，与 CALL_TOOL 冲突；"
        "改为快照中未给出的任务完成数，并改写无答案观测。"
    ),
    "cand-20261114-051": (
        "原“项目里的风险”是可直接检索的明确请求；改回 v1 的会议纪要整理缺口，保留 ASK_USER 语义。"
    ),
    "cand-20261114-052": (
        "d-008 原样本是项目范围歧义；已把来源说明修正为同一 ASK_USER 形态下的"
        "文档目标歧义，不冒充原语义。"
    ),
    "cand-20261114-053": (
        "时间范围已由“先週”给出；保留 ASK_USER，但把缺失项更正为项目、素材与输出范围。"
    ),
    "cand-20261114-066": "原引用 d-024 是 arguments 非对象；已改引真正的越界工具 d-022。",
    "cand-20261114-067": "原引用 d-025 是缺少 toolCall；已改引越界工具 d-021。",
    "cand-20261114-068": "原引用 d-027 是截断 JSON；已改引越界工具 d-021。",
    "cand-20261114-073": (
        "原引用 d-031 是非 CALL_TOOL 携带 toolCall；已改引缺少 decision 的 d-030。"
    ),
    "cand-20261114-074": "原引用 d-022 是未知工具；已改引真正缺少 toolCall 的 d-025。",
    "cand-20261114-075": "原引用 d-023 是 arguments 数组；已改引缺少 toolCall 的 d-025。",
    "cand-20261114-076": "原引用 d-028 是自然语言非 JSON；已改引 FINAL 携带 toolCall 的 d-031。",
    "cand-20261114-078": "原引用 d-026 是 toolCall 缺 name；已改引 arguments 数组的 d-023。",
    "cand-20261114-080": "原引用 d-021 是未知工具；已改引截断 JSON 的 d-027。",
}

DOCUMENT_IDS: Final[dict[str, str]] = {
    "cand-20261114-017": "doc-42",
    "cand-20261114-018": "doc-rag-eval-2026",
    "cand-20261114-019": "doc-design-7",
    "cand-20261114-020": "doc-release-9",
    "cand-20261114-021": "doc-api-2",
    "cand-20261114-022": "doc-risk-3",
    "cand-20261114-023": "doc-retro-4",
    "cand-20261114-024": "doc-sec-5",
    "cand-20261114-025": "doc-beta",
    "cand-20261114-026": "doc-obs-88",
    "cand-20261114-027": "doc-msg-17",
    "cand-20261114-028": "doc-write-guard",
}

CREATE_TASK_ARGUMENTS: Final[dict[str, dict[str, str]]] = {
    "cand-20261114-039": {"title": "补齐发布回滚演练"},
    "cand-20261114-040": {"title": "Review external dependency", "assigneeId": "user-17"},
    "cand-20261114-041": {"title": "ベンダー遅延の緩和策を確認"},
    "cand-20261114-042": {"title": "Preparar el informe semanal"},
}


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()
    ]


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _compact_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _pretty_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _request_model(request_payload: dict[str, Any]) -> AgentDecideRequest:
    return AgentDecideRequest.model_validate(
        {
            "schemaVersion": 1,
            "runId": "v4-review-placeholder",
            "attempt": 1,
            "turnNo": 0,
            "snapshotHash": "sha256:v4-review",
            **request_payload,
        }
    )


def _apply_request_corrections(
    case_id: str, original_request: dict[str, Any]
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    request_payload = deepcopy(original_request)
    corrections: list[dict[str, Any]] = []
    if case_id in GOAL_CORRECTIONS:
        previous = request_payload["goal"]
        request_payload["goal"] = GOAL_CORRECTIONS[case_id]
        corrections.append(
            {"field": "request.goal", "before": previous, "after": request_payload["goal"]}
        )
    if case_id in MESSAGE_CORRECTIONS:
        previous = request_payload["context"]["messages"]
        request_payload["context"]["messages"] = deepcopy(MESSAGE_CORRECTIONS[case_id])
        corrections.append(
            {
                "field": "request.context.messages",
                "before": previous,
                "after": request_payload["context"]["messages"],
            }
        )
    if case_id in OBSERVATION_CORRECTIONS:
        previous = request_payload["context"]["observations"]
        request_payload["context"]["observations"] = deepcopy(OBSERVATION_CORRECTIONS[case_id])
        corrections.append(
            {
                "field": "request.context.observations",
                "before": previous,
                "after": request_payload["context"]["observations"],
            }
        )
    return request_payload, corrections


def _corrected_source(candidate: dict[str, Any]) -> dict[str, Any]:
    case_id = candidate["id"]
    corrected = deepcopy(PROVENANCE_CORRECTIONS.get(case_id, candidate["source"]))
    if case_id in PROVENANCE_CORRECTIONS:
        corrected["correction"] = (
            "原 source.references/rationale 与候选断言不一致，v4 采用修正后的来源说明。"
        )
    return corrected


def _corrected_coverage(candidate: dict[str, Any]) -> dict[str, Any]:
    case_id = candidate["id"]
    if case_id in COVERAGE_CORRECTIONS:
        return deepcopy(COVERAGE_CORRECTIONS[case_id])
    return deepcopy(candidate["coverage"])


def _expected_for(candidate: dict[str, Any]) -> dict[str, Any]:
    if candidate["suggestedSubset"] == "adversarial":
        return deepcopy(ADVERSARIAL_EXPECTED)
    label = candidate["suggestedLabel"]
    expected = {"decision": label["decision"]}
    if label.get("toolName") is not None:
        expected["toolName"] = label["toolName"]
    return expected


def _arguments_for(case_id: str, request_payload: dict[str, Any], tool_name: str) -> dict[str, Any]:
    if tool_name == "search_project_knowledge":
        return {"query": request_payload["goal"], "topK": 5}
    if tool_name == "get_document_metadata":
        return {"documentId": DOCUMENT_IDS[case_id]}
    if tool_name == "get_project_snapshot":
        return {}
    if tool_name == "create_task":
        return deepcopy(CREATE_TASK_ARGUMENTS[case_id])
    raise AssertionError(f"unexpected reviewed tool {tool_name!r}")


def _golden_output(case_id: str, request_payload: dict[str, Any], expected: dict[str, Any]) -> str:
    decision = expected["decision"]
    if decision != DecideDecision.CALL_TOOL.value:
        return _compact_json({"decision": decision})
    return _compact_json(
        {
            "decision": decision,
            "toolCall": {
                "name": expected["toolName"],
                "arguments": _arguments_for(case_id, request_payload, expected["toolName"]),
            },
        }
    )


def _generic_review_note(candidate: dict[str, Any], expected: dict[str, Any]) -> str:
    subset = candidate["suggestedSubset"]
    label = expected["decision"]
    if subset == "adversarial":
        reason = candidate["suggestedLabel"]["rejectReason"]
        return (
            "逐条 probe 复核：candidateOutput 经共享 parser 拒绝，实际 reason 与预期"
            f"“{reason}”一致；来源引用按真实 v1 样本修正。"
        )
    if label == "CALL_TOOL":
        tool_name = expected["toolName"]
        if tool_name == "search_project_knowledge":
            return (
                "goal 明确要求检索或取证，现有 facts/observations 不足以直接回答；"
                "检索工具已下发，标签成立。"
            )
        if tool_name == "get_document_metadata":
            return "目标文档 ID 可从 goal、消息或观测唯一恢复；元数据工具已下发，标签成立。"
        if tool_name == "get_project_snapshot":
            return "goal 所需的是项目快照内的任务、里程碑或近期活动聚合，快照工具已下发，标签成立。"
        if tool_name == "create_task":
            return (
                "用户明确要求创建任务且 allowWrite=true；create_task 已下发，"
                "真正落库仍受 Java 审批门约束。"
            )
    if label == "FINAL":
        return "facts 已直接包含回答所需事实，无需再检索或调用工具，FINAL 严格成立。"
    if label == "ASK_USER":
        return (
            "完成请求仍缺少只有用户能提供的目标、范围、对象或素材，"
            "工具不能替用户决定，ASK_USER 成立。"
        )
    if label == "NO_OP":
        return "本轮没有新的可执行用户请求，或目标明确表示无需动作，选择 NO_OP，不制造工具动作。"
    raise AssertionError(f"unreviewed label {label!r}")


def _source_reference_exists(reference: str) -> bool:
    file_part = reference.split("#", 1)[0]
    return (REPO_ROOT / file_part).is_file() or (WORKER_ROOT / file_part).is_file()


def _v1_case_ids() -> set[str]:
    return {record["id"] for record in _load_jsonl(V1_PATH)}


def _validate_source(case_id: str, source: dict[str, Any], v1_case_ids: set[str]) -> None:
    if not source["references"]:
        raise AssertionError(f"{case_id}: source.references is empty")
    for reference in source["references"]:
        if not _source_reference_exists(reference):
            raise AssertionError(f"{case_id}: missing source reference {reference}")
        if "#" in reference:
            referenced_case = reference.rsplit("#", 1)[1]
            if referenced_case not in v1_case_ids:
                raise AssertionError(f"{case_id}: unknown v1 case reference {reference}")


def _validate_golden_output(
    case_id: str,
    expected: dict[str, Any],
    output: str,
    request: AgentDecideRequest,
) -> None:
    parsed = parse_decision(output, request.tools)
    if parsed.decision.value != expected["decision"]:
        raise AssertionError(f"{case_id}: golden decision mismatch")
    if parsed.decision is DecideDecision.CALL_TOOL and (
        parsed.tool_call is None or parsed.tool_call.name != expected["toolName"]
    ):
        raise AssertionError(f"{case_id}: golden tool mismatch")


def _validate_adversarial_output(
    case_id: str,
    expected_reason: str,
    output: str,
    request: AgentDecideRequest,
) -> None:
    try:
        parse_decision(output, request.tools)
    except DecisionValidationError as exc:
        if exc.reason != expected_reason:
            raise AssertionError(
                f"{case_id}: parser reason {exc.reason!r} != {expected_reason!r}"
            ) from exc
        return
    raise AssertionError(f"{case_id}: adversarial probe unexpectedly parsed")


def _build_documents() -> tuple[bytes, bytes, bytes, bytes]:
    golden_candidates = _load_jsonl(GOLDEN_PATH)
    adversarial_candidates = _load_jsonl(ADVERSARIAL_PATH)
    candidates = golden_candidates + adversarial_candidates
    if len(candidates) != 80:
        raise AssertionError(f"expected 80 candidates, got {len(candidates)}")
    if len({candidate["id"] for candidate in candidates}) != 80:
        raise AssertionError("candidate ids are not unique")

    v1_case_ids = _v1_case_ids()
    for name, expected_hash in HISTORICAL_HASHES.items():
        actual_hash = _sha256((DECIDE_SET_DIR / name).read_bytes())
        if actual_hash != expected_hash:
            raise AssertionError(f"historical hash changed: {name}")

    v4_records: list[dict[str, Any]] = []
    review_entries: list[dict[str, Any]] = []
    correction_count = 0
    rejection_counts: dict[str, int] = {}
    decision_counts: dict[str, int] = {}

    for candidate in candidates:
        case_id = candidate["id"]
        if candidate["reviewStatus"] != "PENDING_REVIEW":
            raise AssertionError(f"{case_id}: candidate is no longer pending review")

        request_payload, request_corrections = _apply_request_corrections(
            case_id, candidate["request"]
        )
        request = _request_model(request_payload)
        expected = _expected_for(candidate)
        source = _corrected_source(candidate)
        coverage = _corrected_coverage(candidate)
        _validate_source(case_id, source, v1_case_ids)

        corrections: list[dict[str, Any]] = []
        corrections.extend(request_corrections)
        if case_id in PROVENANCE_CORRECTIONS:
            corrections.append(
                {
                    "field": "source",
                    "before": candidate["source"],
                    "after": source,
                }
            )
        if case_id in COVERAGE_CORRECTIONS:
            corrections.append(
                {
                    "field": "coverage",
                    "before": candidate["coverage"],
                    "after": coverage,
                }
            )

        if candidate["suggestedSubset"] == "adversarial":
            output = candidate["probe"]["candidateOutput"]
            expected_reason = candidate["probe"]["expectedRejectReason"]
            _validate_adversarial_output(case_id, expected_reason, output, request)
            rejection_counts[expected_reason] = rejection_counts.get(expected_reason, 0) + 1
            parser_check: bool | None = True
        else:
            output = _golden_output(case_id, request_payload, expected)
            _validate_golden_output(case_id, expected, output, request)
            decision_counts[expected["decision"]] = decision_counts.get(expected["decision"], 0) + 1
            parser_check = None

        if expected["decision"] == DecideDecision.CALL_TOOL.value:
            offered = {tool.name: tool for tool in request.tools}
            if expected["toolName"] not in offered:
                raise AssertionError(f"{case_id}: expected tool not offered")
            if (
                offered[expected["toolName"]].risk is ToolRisk.WRITE
                and not request.policy.allow_write
            ):
                raise AssertionError(f"{case_id}: write tool without allowWrite")

        if corrections:
            correction_count += 1

        note = CASE_REVIEW_NOTES.get(case_id)
        if note is None:
            note = _generic_review_note(candidate, expected)
        review_entry = {
            "id": case_id,
            "subset": candidate["suggestedSubset"],
            "reviewStatus": REVIEW_STATUS,
            "review": {
                "reviewer": REVIEWER,
                "reviewedAt": REVIEWED_AT,
                "notes": note,
                "checks": {
                    "labelInferable": True,
                    "toolOffered": (
                        expected["decision"] != DecideDecision.CALL_TOOL.value
                        or expected["toolName"] in {tool.name for tool in request.tools}
                    ),
                    "writePolicyCompliant": True,
                    "sourceReferenceResolved": True,
                    "adversarialParserReasonMatch": parser_check,
                },
                "corrections": corrections,
            },
            "source": source,
            "coverage": coverage,
            "expected": expected,
            "modelOutputSha256": _sha256(output.encode("utf-8")),
        }
        review_entries.append(review_entry)
        v4_records.append(
            {
                "id": case_id,
                "subset": candidate["suggestedSubset"],
                "scenario": request_payload["scenario"],
                "goal": request_payload["goal"],
                "locale": request_payload["locale"],
                "context": request_payload["context"],
                "tools": request_payload["tools"],
                "policy": request_payload["policy"],
                "budget": request_payload["budget"],
                "expected": expected,
                "modelOutput": output,
            }
        )

    v4_bytes = ("\n".join(_compact_json(record) for record in v4_records) + "\n").encode("utf-8")
    v4_sha = _sha256(v4_bytes)
    canonical_bytes = canonicalize_cases(v4_records)
    canonical_sha = digest(canonical_bytes)
    review_document = {
        "schemaVersion": 1,
        "datasetId": "decide-baseline-v4-20261114",
        "createdAt": "2026-11-14",
        "v4Sha256": v4_sha,
        "review": {
            "reviewer": REVIEWER,
            "reviewedAt": REVIEWED_AT,
            "status": REVIEW_STATUS,
            "reviewedCount": len(review_entries),
            "correctedCount": correction_count,
        },
        "counts": {"total": 80, "golden": 64, "adversarial": 16},
        "reviewEntries": review_entries,
    }
    review_bytes = _pretty_json(review_document)
    source_hashes = {
        "golden.v1.jsonl": _sha256(GOLDEN_PATH.read_bytes()),
        "adversarial.v1.jsonl": _sha256(ADVERSARIAL_PATH.read_bytes()),
    }
    manifest_document = {
        "schemaVersion": 1,
        "datasetId": "decide-baseline-v4-20261114",
        "kind": "decide-baseline",
        "createdAt": "2026-11-14",
        "status": REVIEW_STATUS,
        "counts": {"total": 80, "golden": 64, "adversarial": 16},
        "goldenDecisionCounts": dict(sorted(decision_counts.items())),
        "adversarialRejectionCounts": dict(sorted(rejection_counts.items())),
        "adversarialExpected": {
            "decision": "ASK_USER",
            "semantics": (
                "v4 的 adversarial request 没有内嵌 candidateOutput；expected=ASK_USER 仅作为"
                "评测 schema 的中性合法占位。该子集的权威断言是 probe reason 与 parser 一致、"
                "以及 replay 的 rejectedRate，不是 expected.decision。"
            ),
        },
        "sourceCandidates": source_hashes,
        "files": {
            "decide_baseline.v4.jsonl": {
                "sha256": v4_sha,
                "canonicalBytes": len(canonical_bytes),
                "canonicalSha256": canonical_sha,
            },
            "decide_baseline.v4.review.json": {"sha256": _sha256(review_bytes)},
        },
        "historicalFileHashes": {
            name: _sha256((DECIDE_SET_DIR / name).read_bytes()) for name in HISTORICAL_HASHES
        },
        "review": {
            "reviewer": REVIEWER,
            "reviewedAt": REVIEWED_AT,
            "reviewedCount": len(review_entries),
            "correctedCount": correction_count,
            "status": REVIEW_STATUS,
        },
    }
    manifest_bytes = _pretty_json(manifest_document)
    sha_bytes = (
        f"{v4_sha}  decide_baseline.v4.jsonl\n"
        f"{_sha256(review_bytes)}  decide_baseline.v4.review.json\n"
        f"{_sha256(manifest_bytes)}  decide_baseline.v4.manifest.json\n"
    ).encode()
    return v4_bytes, review_bytes, manifest_bytes, sha_bytes


def _write_or_check(path: Path, expected: bytes, *, check: bool) -> None:
    if check:
        if not path.is_file():
            raise AssertionError(f"missing output {path}")
        actual = path.read_bytes()
        if actual != expected:
            raise AssertionError(f"output differs from reviewed content: {path}")
        return
    path.write_bytes(expected)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    v4_bytes, review_bytes, manifest_bytes, sha_bytes = _build_documents()
    for path, expected in (
        (V4_PATH, v4_bytes),
        (REVIEW_PATH, review_bytes),
        (MANIFEST_PATH, manifest_bytes),
        (SHA_PATH, sha_bytes),
    ):
        _write_or_check(path, expected, check=args.check)
    loaded = load_decide_set(V4_PATH)
    if len(loaded) != 80:
        raise AssertionError(f"v4 loader returned {len(loaded)} cases")
    print(
        _compact_json(
            {
                "mode": "check" if args.check else "write",
                "cases": len(loaded),
                "v4Sha256": _sha256(v4_bytes),
                "reviewSha256": _sha256(review_bytes),
                "manifestSha256": _sha256(manifest_bytes),
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
