"""``ai-eval planning``：Planning 草稿的离线评测（W5 冻结文档 §4.1）。

冻结集：``tests/fixtures/planning_set/planning_baseline.v1.jsonl``。每条 case 含
``goal/projectFacts/constraints`` + ``modelOutput``（**冻结的代表性模型输出，stub 标注**）+
``expected`` 元信息（``shouldPass``）。``modelOutput`` 不声称来自任何真实 Provider。

打分方式只有一种：**进程内 replay**。它把 ``modelOutput`` 走真实解析与自校验核心
（``cognition.planning.parser.parse_draft`` + ``cognition.planning.validator.validate_draft``），
与 ``POST /v1/planning/draft`` 共用同一段代码，因此离线结果可以解释在线行为。本评测不联网、
不需要令牌、不调用模型。

指标口径（冻结 §4.1）：

- ``schemaPassRate``：全体 case 中"契约 + 数量/依赖/日期/成员/来源校验一次通过"的比例；
- ``bySubset.golden.schemaPassRate``：**目标评估看这一项**（≥95%）；``adversarial`` 子集是
  刻意非法的负样本，只用于证明校验器确实会拒绝；
- ``sourceViolations`` / ``assigneeViolations`` / ``duplicateTitles``：只在**通过校验**的 case
  上统计（校验失败的 case 拿不到清洗后的 plan）：
  - ``sourceViolations``：被丢弃的 ``sourceRefs`` 条数；
  - ``assigneeViolations``：被置 null 的 ``assigneeId`` 条数；
  - ``duplicateTitles``：与既有标题或同稿前序条目重名的条目数（NFKC + casefold + 空白折叠）；
- ``confidence``：case 数 < 200 → ``LOW_CONFIDENCE``（沿用 rerank/decide 口径）；
- ``evidence``：固定 ``TESTED (stub)``；``qualityClaim``：固定 ``NOT_VERIFIED``。

**≥95% 只在 stub 冻结集上测量，不是真实模型结论**；真实模型的质量只能在 staging 由 Java
shadow 链路观测。报告里的 ``limitations`` 字段逐字写明这一点，不得省略。
"""

from __future__ import annotations

import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from pydantic import ValidationError

from ai_collab_worker.cognition.planning.parser import DraftValidationError, parse_draft
from ai_collab_worker.cognition.planning.validator import validate_draft
from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.contract.planning import (
    PlanningDraftRequest,
    PlanningMilestone,
    PlanningPlan,
    PlanningTask,
)
from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_document,
    digest,
    load_dataset,
)

#: 样本口径：case 数 < 200 只能标 LOW_CONFIDENCE。
CONFIDENCE_SAMPLE_THRESHOLD: Final[int] = 200
CONFIDENCE_LOW: Final[str] = "LOW_CONFIDENCE"
CONFIDENCE_OK: Final[str] = "OK"

#: 证据与质量声明（无真实模型）。
EVIDENCE_STUB: Final[str] = "TESTED (stub)"
QUALITY_CLAIM_NOT_VERIFIED: Final[str] = "NOT_VERIFIED"

#: 冻结 §4.1 的目标（只在 golden 子集 + stub 输出上测量）。
TARGET_SCHEMA_PASS_RATE: Final[float] = 0.95

#: 诚实声明（写进报告，不是注释）。
LIMITATIONS: Final[str] = (
    "schemaPassRate>=0.95 只在 golden 子集与 stub 冻结集输出上测量，"
    "不是真实模型结论；真实模型质量只能在 staging 由 Java shadow 链路观测。"
)

#: case 的评测子集：golden=正常样本；adversarial=刻意非法负样本。
SUBSET_GOLDEN: Final[str] = "golden"
SUBSET_ADVERSARIAL: Final[str] = "adversarial"
VALID_SUBSETS: Final[frozenset[str]] = frozenset({SUBSET_GOLDEN, SUBSET_ADVERSARIAL})

#: case 的精确字段集（多一个少一个都退出码 2）。
_CASE_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
        "subset",
        "goal",
        "locale",
        "projectFacts",
        "template",
        "constraints",
        "expected",
        "modelOutput",
    }
)

#: case 里属于请求契约的字段（其余是评测专用字段）。
_REQUEST_FIELDS: Final[tuple[str, ...]] = (
    "goal",
    "locale",
    "projectFacts",
    "template",
    "constraints",
)

#: ``expected`` 允许的键。
_EXPECTED_FIELDS: Final[frozenset[str]] = frozenset({"shouldPass", "note"})


@dataclass(frozen=True, slots=True)
class PlanningCase:
    """冻结集的一条 case：请求模型 + 期望元信息 + 冻结模型输出 + 评测子集。"""

    id: str
    subset: str
    request: PlanningDraftRequest
    expected_pass: bool
    model_output: str


@dataclass(frozen=True, slots=True)
class PlanningCaseOutcome:
    """单条 case 的打分结果；``schema_pass`` 即"契约 + 自校验一次通过"。"""

    schema_pass: bool
    reason: str | None
    warnings: int
    source_violations: int
    assignee_violations: int
    duplicate_titles: int


@dataclass(frozen=True, slots=True)
class PlanningEvalReport:
    """一次评测的完整结果（不含 SHA-256，由 :func:`seal_report` 追加）。"""

    cases: tuple[tuple[PlanningCase, PlanningCaseOutcome], ...]
    schema_pass_rate: float
    source_violations: int
    assignee_violations: int
    duplicate_titles: int
    expectation_matched: int
    confidence: str


def load_planning_set(path: Path) -> list[PlanningCase]:
    """读取 planning 冻结集（JSONL/JSON），按冻结 §1 的字段与契约边界 fail-closed 校验。"""

    cases: list[PlanningCase] = []
    for position, raw_case in enumerate(load_dataset(path), start=1):
        cases.append(_parse_case(raw_case, position))
    if not cases:
        raise DatasetError("planning 冻结集不能为空")
    return cases


def _parse_case(raw_case: Mapping[str, Any], position: int) -> PlanningCase:
    unknown = sorted(set(raw_case) - _CASE_FIELDS)
    if unknown:
        raise DatasetError(f"第 {position} 个 case 含未定义字段：{', '.join(unknown)}")
    missing = sorted(_CASE_FIELDS - set(raw_case))
    if missing:
        raise DatasetError(f"第 {position} 个 case 缺少字段：{', '.join(missing)}")

    case_id = raw_case.get("id")
    if not isinstance(case_id, str) or not case_id:
        raise DatasetError(f"第 {position} 个 case 缺少非空字符串 id")
    where = f"case {case_id!r}"

    subset = raw_case.get("subset")
    if not isinstance(subset, str) or subset not in VALID_SUBSETS:
        raise DatasetError(f"{where} 的 subset 必须是 golden 或 adversarial")

    payload: dict[str, Any] = {"schemaVersion": SCHEMA_VERSION}
    for field in _REQUEST_FIELDS:
        payload[field] = raw_case[field]
    try:
        request = PlanningDraftRequest.model_validate(payload)
    except ValidationError as exc:
        first = exc.errors()[0]
        location = ".".join(str(part) for part in first.get("loc", ()))
        raise DatasetError(
            f"{where} 不符合 planning 请求契约：{location}: {first.get('msg', '')}"
        ) from exc

    expected = raw_case["expected"]
    if not isinstance(expected, Mapping):
        raise DatasetError(f"{where} 的 expected 必须是对象")
    unknown_expected = sorted(set(expected) - _EXPECTED_FIELDS)
    if unknown_expected:
        raise DatasetError(f"{where} 的 expected 含未定义字段：{', '.join(unknown_expected)}")
    should_pass = expected.get("shouldPass")
    if not isinstance(should_pass, bool):
        raise DatasetError(f"{where} 的 expected.shouldPass 必须是布尔值")

    model_output = raw_case["modelOutput"]
    if not isinstance(model_output, str):
        raise DatasetError(f"{where} 的 modelOutput 必须是字符串")

    return PlanningCase(
        id=case_id,
        subset=subset,
        request=request,
        expected_pass=should_pass,
        model_output=model_output,
    )


def evaluate(cases: Sequence[PlanningCase]) -> PlanningEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。"""

    if not cases:
        raise ValueError("评测集不能为空")

    ordered = sorted(cases, key=lambda case: case.id)
    items: list[tuple[PlanningCase, PlanningCaseOutcome]] = [
        (case, _score(case)) for case in ordered
    ]
    count = len(items)
    passed = sum(1 for _, outcome in items if outcome.schema_pass)
    return PlanningEvalReport(
        cases=tuple(items),
        schema_pass_rate=passed / count,
        source_violations=sum(
            outcome.source_violations for _, outcome in items if outcome.schema_pass
        ),
        assignee_violations=sum(
            outcome.assignee_violations for _, outcome in items if outcome.schema_pass
        ),
        duplicate_titles=sum(
            outcome.duplicate_titles for _, outcome in items if outcome.schema_pass
        ),
        expectation_matched=sum(
            1 for case, outcome in items if outcome.schema_pass is case.expected_pass
        ),
        confidence=CONFIDENCE_LOW if count < CONFIDENCE_SAMPLE_THRESHOLD else CONFIDENCE_OK,
    )


def _score(case: PlanningCase) -> PlanningCaseOutcome:
    try:
        output = parse_draft(case.model_output)
        validated = validate_draft(output, case.request)
    except DraftValidationError as exc:
        return PlanningCaseOutcome(
            schema_pass=False,
            reason=exc.reason,
            warnings=0,
            source_violations=0,
            assignee_violations=0,
            duplicate_titles=0,
        )
    return PlanningCaseOutcome(
        schema_pass=True,
        reason=None,
        warnings=len(validated.warnings),
        source_violations=validated.dropped_source_refs,
        assignee_violations=validated.cleared_assignees,
        duplicate_titles=count_duplicate_titles(validated.plan, case.request),
    )


def count_duplicate_titles(plan: PlanningPlan, request: PlanningDraftRequest) -> int:
    """统计与既有标题或同稿前序条目重名的条数（NFKC + casefold + 空白折叠）。"""

    facts = request.project_facts
    seen = {_normalize(title) for title in facts.existing_milestone_titles}
    seen.update(_normalize(title) for title in facts.existing_task_titles)
    duplicates = 0
    items: list[PlanningMilestone | PlanningTask] = [*plan.milestones, *plan.tasks]
    for item in items:
        key = _normalize(item.title)
        if key in seen:
            duplicates += 1
            continue
        seen.add(key)
    return duplicates


def _normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _by_subset(
    items: Sequence[tuple[PlanningCase, PlanningCaseOutcome]],
) -> dict[str, dict[str, float | int]]:
    """按子集拆分：golden 看通过率，adversarial 看拒绝率（两者分母不同，不能混算）。"""

    golden = [(case, outcome) for case, outcome in items if case.subset == SUBSET_GOLDEN]
    adversarial = [(case, outcome) for case, outcome in items if case.subset == SUBSET_ADVERSARIAL]
    golden_passed = sum(1 for _, outcome in golden if outcome.schema_pass)
    adversarial_rejected = sum(1 for _, outcome in adversarial if not outcome.schema_pass)
    return {
        SUBSET_GOLDEN: {
            "cases": len(golden),
            "schemaPassRate": golden_passed / len(golden) if golden else 0.0,
        },
        SUBSET_ADVERSARIAL: {
            "cases": len(adversarial),
            "rejectedRate": adversarial_rejected / len(adversarial) if adversarial else 0.0,
        },
    }


def report_document(report: PlanningEvalReport) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。"""

    cases: list[dict[str, Any]] = []
    for case, outcome in report.cases:
        actual: dict[str, Any] = {
            "schemaPass": outcome.schema_pass,
            "warnings": outcome.warnings,
            "sourceViolations": outcome.source_violations,
            "assigneeViolations": outcome.assignee_violations,
            "duplicateTitles": outcome.duplicate_titles,
        }
        if outcome.reason is not None:
            actual["reason"] = outcome.reason
        cases.append(
            {
                "id": case.id,
                "subset": case.subset,
                "expected": {"shouldPass": case.expected_pass},
                "actual": actual,
                "matched": outcome.schema_pass is case.expected_pass,
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scorer": "replay",
        "cases": cases,
        "aggregate": {
            "cases": len(report.cases),
            "confidence": report.confidence,
            "schemaPassRate": report.schema_pass_rate,
            "sourceViolations": report.source_violations,
            "assigneeViolations": report.assignee_violations,
            "duplicateTitles": report.duplicate_titles,
            "expectationMatched": report.expectation_matched,
            "bySubset": _by_subset(report.cases),
        },
        "targetSchemaPassRate": TARGET_SCHEMA_PASS_RATE,
        "limitations": LIMITATIONS,
        "evidence": EVIDENCE_STUB,
        "qualityClaim": QUALITY_CLAIM_NOT_VERIFIED,
    }


def seal_report(document: Mapping[str, Any]) -> dict[str, Any]:
    """追加 ``sha256``：摘要是 ``canonical(document 去掉 sha256 键)`` 的 SHA-256。"""

    payload = {key: value for key, value in document.items() if key != "sha256"}
    sealed = dict(payload)
    sealed["sha256"] = digest(canonicalize_document(payload))
    return sealed


def canonical_report_bytes(document: Mapping[str, Any]) -> bytes:
    """``seal_report`` + canonical 编码：CLI ``--json`` 输出的精确字节。"""

    return canonicalize_document(seal_report(document))


__all__ = [
    "CONFIDENCE_LOW",
    "CONFIDENCE_OK",
    "CONFIDENCE_SAMPLE_THRESHOLD",
    "EVIDENCE_STUB",
    "LIMITATIONS",
    "QUALITY_CLAIM_NOT_VERIFIED",
    "SUBSET_ADVERSARIAL",
    "SUBSET_GOLDEN",
    "TARGET_SCHEMA_PASS_RATE",
    "PlanningCase",
    "PlanningCaseOutcome",
    "PlanningEvalReport",
    "canonical_report_bytes",
    "count_duplicate_titles",
    "evaluate",
    "load_planning_set",
    "report_document",
    "seal_report",
]
