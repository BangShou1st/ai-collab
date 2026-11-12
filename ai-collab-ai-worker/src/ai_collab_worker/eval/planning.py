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
  - ``sourceViolations``：被丢弃的 ``sourceRefs`` 条数（Python 本波不产出引用来源，响应恒为空
    数组，所以任何模型给出的 ref 都计入这里）；
  - ``assigneeViolations``：被置 null 的 ``assigneeId`` 条数；
  - ``duplicateTitles``：与既有标题或同稿前序条目重名的条目数（NFKC + casefold + 空白折叠）；
- ``nonEmptySourceRefs``：清洗后仍有非空 ``sourceRefs`` 的条目数，**必须为 0**（eval 侧对
  "响应恒为空数组"的断言）；
- ``confidence``：case 数 < 200 → ``LOW_CONFIDENCE``（沿用 rerank/decide 口径）；
- ``evidence`` / ``qualityClaim``：默认 ``TESTED (stub)`` / ``NOT_VERIFIED``；D10 起可由
  调用方显式覆盖（真实模型运行必须显式传 ``LIVE_VERIFIED``），空标签报错。

**≥95% 只在 stub 冻结集上测量，不是真实模型结论**；真实模型的质量只能在 staging 由 Java
shadow 链路观测。报告里的 ``limitations`` 字段逐字写明这一点，不得省略。

D6（本轮新增）：``--adversarial-mutations`` 显式开启后，对**当前能通过校验的模型输出**做
定向变异（不存在的 milestone 引用 / 依赖环 / 任务数超限 / 日期越过 dueDate / 重复
tempKey），再把变异体喂回同一个 ``parse_draft`` + ``validate_draft``。旧请求级
``adversarial`` 子集保留，但它依赖"模型复制非法输入"，不再作为校验器证据；校验器证据
看 ``bySubset.adversarialMutations`` 与 ``mutationRejectedRate``。``duplicateTitles``
只记录、不评估质量。
"""

from __future__ import annotations

import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
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
from ai_collab_worker.eval.labels import resolve_label, resolve_optional_text
from ai_collab_worker.eval.mutation import (
    MUTATION_SOURCE_NOTES,
    MUTATION_SOURCE_REPLAY_DATASET,
    MutationOutcome,
    evaluate_planning_mutations,
)

#: 样本口径：case 数 < 200 只能标 LOW_CONFIDENCE。
CONFIDENCE_SAMPLE_THRESHOLD: Final[int] = 200
CONFIDENCE_LOW: Final[str] = "LOW_CONFIDENCE"
CONFIDENCE_OK: Final[str] = "OK"

#: 证据与质量声明（无真实模型）。
EVIDENCE_STUB: Final[str] = "TESTED (stub)"
QUALITY_CLAIM_NOT_VERIFIED: Final[str] = "NOT_VERIFIED"

#: 显式证据标签（如真实模型运行）下替换默认 stub 声明，避免标签与正文自相矛盾。
EXPLICIT_EVIDENCE_LIMITATIONS: Final[str] = (
    "证据标签与质量声明由调用方显式指定；本报告指标只对本次输入与标注成立，"
    "不得外推到生产、其他模型或 provider。schemaPassRate 是离线校验器指标，"
    "不等于真实模型的任务质量。"
)

#: 旧请求级 adversarial 子集的定位（D6）。
ADVERSARIAL_SUBSET_NOTE: Final[str] = (
    "旧 request-level adversarial 子集依赖模型复制非法输入，只保留为历史对照，"
    "不再作为校验器证据；校验器证据看 bySubset.adversarialMutations。"
)

#: 冻结 §4.1 的目标（只在 golden 子集 + stub 输出上测量）。
TARGET_SCHEMA_PASS_RATE: Final[float] = 0.95

#: 诚实声明（写进报告，不是注释）。
LIMITATIONS: Final[str] = (
    "schemaPassRate>=0.95 只在 golden 子集与 stub 冻结集输出上测量，"
    "不是真实模型结论；真实模型质量只能在 staging 由 Java shadow 链路观测。"
    "响应已按 Java Validator 对齐：sourceRefs 恒为空数组（模型给出的引用一律丢弃并计数）、"
    "objective 与任务 description 必填非空、estimatedHours 上限 80。"
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
    source_refs_empty: bool = True
    mutations: tuple[MutationOutcome, ...] = ()


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
    non_empty_source_refs: int = 0
    mutation_cases: int = 0
    mutation_variants: int = 0
    mutation_rejected: int = 0
    mutation_rejected_rate: float | None = None
    mutation_source: str | None = None


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


def evaluate(
    cases: Sequence[PlanningCase], *, adversarial_mutations: bool = False
) -> PlanningEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。

    ``adversarial_mutations`` 默认关闭，旧输出逐字节不变；显式开启后只对**当前通过
    校验的个案**做定向变异，并提供 ``mutationRejectedRate`` 与逐 case 变异明细。
    """

    if not cases:
        raise ValueError("评测集不能为空")

    ordered = sorted(cases, key=lambda case: case.id)
    items: list[tuple[PlanningCase, PlanningCaseOutcome]] = []
    for case in ordered:
        outcome = _score(case)
        if adversarial_mutations and outcome.schema_pass:
            outcome = replace(
                outcome,
                mutations=evaluate_planning_mutations(case.model_output, case.request),
            )
        items.append((case, outcome))
    count = len(items)
    passed = sum(1 for _, outcome in items if outcome.schema_pass)
    mutation_cases = sum(1 for _, outcome in items if outcome.mutations)
    mutation_variants = sum(len(outcome.mutations) for _, outcome in items)
    mutation_rejected = sum(
        1 for _, outcome in items for mutation in outcome.mutations if mutation.rejected
    )
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
        non_empty_source_refs=sum(1 for _, outcome in items if not outcome.source_refs_empty),
        mutation_cases=mutation_cases,
        mutation_variants=mutation_variants,
        mutation_rejected=mutation_rejected,
        mutation_rejected_rate=(
            mutation_rejected / mutation_variants if mutation_variants else None
        ),
        mutation_source=MUTATION_SOURCE_REPLAY_DATASET if adversarial_mutations else None,
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
        source_refs_empty=not has_non_empty_source_refs(validated.plan),
    )


def has_non_empty_source_refs(plan: PlanningPlan) -> bool:
    """清洗后的草稿是否仍有非空 ``sourceRefs``（契约要求恒为空数组）。"""

    items: list[PlanningMilestone | PlanningTask] = [*plan.milestones, *plan.tasks]
    return any(item.source_refs for item in items)


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
    *,
    adversarial_mutations: bool = False,
    mutation_source: str | None = None,
) -> dict[str, dict[str, object]]:
    """按子集拆分：golden 看通过率，adversarial 看拒绝率（两者分母不同，不能混算）。

    D6 开启变异时额外给出 ``adversarialMutations``：变异体只从"当前通过校验"的个案
    派生，因此它衡量的是**校验器对定向非法的拒绝能力**，不是请求级负样本的复制率。
    """

    golden = [(case, outcome) for case, outcome in items if case.subset == SUBSET_GOLDEN]
    adversarial = [(case, outcome) for case, outcome in items if case.subset == SUBSET_ADVERSARIAL]
    golden_passed = sum(1 for _, outcome in golden if outcome.schema_pass)
    adversarial_rejected = sum(1 for _, outcome in adversarial if not outcome.schema_pass)
    result: dict[str, dict[str, object]] = {
        SUBSET_GOLDEN: {
            "cases": len(golden),
            "schemaPassRate": golden_passed / len(golden) if golden else 0.0,
        },
        SUBSET_ADVERSARIAL: {
            "cases": len(adversarial),
            "rejectedRate": adversarial_rejected / len(adversarial) if adversarial else 0.0,
        },
    }
    if adversarial_mutations:
        mutated = [(case, outcome) for case, outcome in items if outcome.mutations]
        variants = sum(len(outcome.mutations) for _, outcome in mutated)
        rejected = sum(
            1 for _, outcome in mutated for mutation in outcome.mutations if mutation.rejected
        )
        result["adversarialMutations"] = {
            "cases": len(mutated),
            "variants": variants,
            "rejected": rejected,
            "rejectedRate": rejected / variants if variants else None,
            "sourceSubsets": sorted({case.subset for case, _ in mutated}),
            "source": mutation_source,
            "note": ADVERSARIAL_SUBSET_NOTE,
        }
    return result


def report_document(
    report: PlanningEvalReport,
    *,
    evidence: str | None = None,
    quality_claim: str | None = None,
    subject: str | None = None,
    model: str | None = None,
    limitations: str | None = None,
) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。

    D10：``evidence`` / ``quality_claim`` / ``subject`` / ``model`` 为显式标签入参；
    ``None`` 时保持冻结核默认值（旧输出逐字节不变），空标签抛 ``ValueError``。
    D6：只有 ``report.mutation_variants > 0`` 时才写变异字段，关闭时报告形状不变。
    """

    evidence_label = resolve_label(evidence, default=EVIDENCE_STUB, field="evidence")
    quality_label = resolve_label(
        quality_claim, default=QUALITY_CLAIM_NOT_VERIFIED, field="qualityClaim"
    )
    subject_label = resolve_optional_text(subject, field="subject")
    model_label = resolve_optional_text(model, field="model")
    if limitations is not None:
        limitations_text = resolve_label(limitations, default=LIMITATIONS, field="limitations")
    elif evidence is not None and evidence_label != EVIDENCE_STUB:
        limitations_text = EXPLICIT_EVIDENCE_LIMITATIONS
    else:
        limitations_text = LIMITATIONS

    mutation_enabled = report.mutation_source is not None
    cases: list[dict[str, Any]] = []
    for case, outcome in report.cases:
        actual: dict[str, Any] = {
            "schemaPass": outcome.schema_pass,
            "warnings": outcome.warnings,
            "sourceViolations": outcome.source_violations,
            "assigneeViolations": outcome.assignee_violations,
            "duplicateTitles": outcome.duplicate_titles,
            "sourceRefsEmpty": outcome.source_refs_empty,
        }
        if outcome.reason is not None:
            actual["reason"] = outcome.reason
        entry: dict[str, Any] = {
            "id": case.id,
            "subset": case.subset,
            "expected": {"shouldPass": case.expected_pass},
            "actual": actual,
            "matched": outcome.schema_pass is case.expected_pass,
        }
        if mutation_enabled and outcome.mutations:
            entry["mutations"] = [
                {
                    "kind": mutation.kind,
                    "rejected": mutation.rejected,
                    "reason": mutation.reason,
                }
                for mutation in outcome.mutations
            ]
        cases.append(entry)

    aggregate: dict[str, Any] = {
        "cases": len(report.cases),
        "confidence": report.confidence,
        "schemaPassRate": report.schema_pass_rate,
        "sourceViolations": report.source_violations,
        "assigneeViolations": report.assignee_violations,
        "duplicateTitles": report.duplicate_titles,
        "expectationMatched": report.expectation_matched,
        "nonEmptySourceRefs": report.non_empty_source_refs,
    }
    if mutation_enabled:
        source = report.mutation_source
        aggregate["mutationRejectedRate"] = report.mutation_rejected_rate
        aggregate["mutationVariants"] = report.mutation_variants
        aggregate["mutationRejected"] = report.mutation_rejected
        aggregate["mutationCases"] = report.mutation_cases
        aggregate["mutationSource"] = source
        if source is not None:
            aggregate["mutationSourceNote"] = MUTATION_SOURCE_NOTES[source]
    aggregate["bySubset"] = _by_subset(
        report.cases, adversarial_mutations=mutation_enabled, mutation_source=report.mutation_source
    )

    document: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "scorer": "replay",
        "cases": cases,
        "aggregate": aggregate,
        "targetSchemaPassRate": TARGET_SCHEMA_PASS_RATE,
        "limitations": limitations_text,
        "evidence": evidence_label,
        "qualityClaim": quality_label,
    }
    if subject_label is not None:
        document["subject"] = subject_label
    if model_label is not None:
        document["model"] = model_label
    return document


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
    "ADVERSARIAL_SUBSET_NOTE",
    "CONFIDENCE_LOW",
    "CONFIDENCE_OK",
    "CONFIDENCE_SAMPLE_THRESHOLD",
    "EVIDENCE_STUB",
    "EXPLICIT_EVIDENCE_LIMITATIONS",
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
    "has_non_empty_source_refs",
    "load_planning_set",
    "report_document",
    "seal_report",
]
