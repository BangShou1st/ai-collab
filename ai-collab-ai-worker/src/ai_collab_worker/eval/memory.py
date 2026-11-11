"""``ai-eval memory``：Memory 候选提取的离线评测（W5 冻结文档 §4.2）。

冻结集：``tests/fixtures/memory_set/memory_baseline.v1.jsonl``。每条 case 含：

- ``snapshot``：与 ``GET /internal/ai/v1/jobs/{id}/snapshot`` 同形状的快照（facts + 既有记忆）；
- ``modelOutput``：**冻结的代表性候选输出（stub 标注）**，不声称来自真实模型；
- ``humanJudgement``：人工判断"应当提取"的候选集合（``[{kind, title}]``，标题级匹配）。

打分方式只有一种：**进程内 replay**，直接复用
``cognition.memory.parser.parse_candidates``（与 ``POST /v1/jobs`` 同一段代码），不联网、
不需要 job token、不调用模型。``dedupeKey`` 也由同一段代码计算，因此与在线行为一致。

指标口径（冻结 §4.2）：

- ``precision`` = 命中人工判断的候选数 / 提交的候选数（micro：总和相除）；
- ``recall`` = 命中候选数 / 人工判断总数；
- ``candidateAgreement`` = precision 与 recall 的调和平均（F1；分母为 0 时为 0.0）；
- ``duplicateRate`` = 与既有记忆 ``dedupeKey`` 相同的候选数 / 提交的候选数；
- ``schemaPassRate`` = 模型输出可解析为候选契约的 case 数 / case 数
  （来源非法与 dedupeKey 不一致属于**丢弃**而不是解析失败，不计入 schema 失败）；
- ``confidence``：case 数 < 200 → ``LOW_CONFIDENCE``；
- ``evidence`` 固定 ``TESTED (stub)``、``qualityClaim`` 固定 ``NOT_VERIFIED``。

``limitations`` 字段逐字写明：这些数值只证明契约与评测管线正确，**不证明真实模型质量**。
"""

from __future__ import annotations

import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from pydantic import ValidationError

from ai_collab_worker.cognition.memory.parser import (
    MemoryCandidateError,
    compute_dedupe_key,
    parse_candidates,
)
from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.contract.memory_job import MemoryJobSnapshot, MemoryKind
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

#: 诚实声明（写进报告，不是注释）。
LIMITATIONS: Final[str] = (
    "precision/recall/candidateAgreement/duplicateRate 只在 stub 冻结集输出上测量，"
    "不是真实模型结论；真实模型的一致率/重复率只能在 staging 由 Java shadow 链路观测。"
)

#: case 的精确字段集。
_CASE_FIELDS: Final[frozenset[str]] = frozenset({"id", "snapshot", "modelOutput", "humanJudgement"})

#: ``humanJudgement`` 条目的精确字段集。
_JUDGEMENT_FIELDS: Final[frozenset[str]] = frozenset({"kind", "title"})


@dataclass(frozen=True, slots=True)
class MemoryCase:
    """冻结集的一条 case：快照 + 冻结模型输出 + 人工判断。"""

    id: str
    snapshot: MemoryJobSnapshot
    judgement: tuple[tuple[MemoryKind, str], ...]
    model_output: str


@dataclass(frozen=True, slots=True)
class MemoryCaseOutcome:
    """单条 case 的打分结果；``candidates`` 是提交给 Java 的候选数。"""

    schema_pass: bool
    reason: str | None
    candidates: int
    matched: int
    duplicates: int
    invalid_source: int
    invalid_dedupe: int


@dataclass(frozen=True, slots=True)
class MemoryEvalReport:
    """一次评测的完整结果（不含 SHA-256，由 :func:`seal_report` 追加）。"""

    cases: tuple[tuple[MemoryCase, MemoryCaseOutcome], ...]
    precision: float
    recall: float
    candidate_agreement: float
    duplicate_rate: float
    schema_pass_rate: float
    confidence: str


def load_memory_set(path: Path) -> list[MemoryCase]:
    """读取 memory 冻结集（JSONL/JSON），逐 case 校验快照与人工判断的形状。"""

    cases: list[MemoryCase] = []
    for position, raw_case in enumerate(load_dataset(path), start=1):
        cases.append(_parse_case(raw_case, position))
    if not cases:
        raise DatasetError("memory 冻结集不能为空")
    return cases


def _parse_case(raw_case: Mapping[str, Any], position: int) -> MemoryCase:
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

    try:
        snapshot = MemoryJobSnapshot.model_validate(raw_case["snapshot"])
    except ValidationError as exc:
        first = exc.errors()[0]
        location = ".".join(str(part) for part in first.get("loc", ()))
        raise DatasetError(
            f"{where} 的 snapshot 不符合内部快照契约：{location}: {first.get('msg', '')}"
        ) from exc

    judgement = raw_case["humanJudgement"]
    if not isinstance(judgement, list):
        raise DatasetError(f"{where} 的 humanJudgement 必须是数组")
    if not judgement:
        raise DatasetError(f"{where} 的 humanJudgement 不能为空")
    entries: list[tuple[MemoryKind, str]] = []
    for index, item in enumerate(judgement, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"{where} 的 humanJudgement 第 {index} 项必须是对象")
        unknown_item = sorted(set(item) - _JUDGEMENT_FIELDS)
        if unknown_item:
            raise DatasetError(
                f"{where} 的 humanJudgement 第 {index} 项含未定义字段：{', '.join(unknown_item)}"
            )
        raw_kind = item.get("kind")
        if not isinstance(raw_kind, str):
            raise DatasetError(f"{where} 的 humanJudgement 第 {index} 项缺少 kind")
        try:
            kind = MemoryKind(raw_kind)
        except ValueError as exc:
            raise DatasetError(
                f"{where} 的 humanJudgement 第 {index} 项 kind 不在四个取值内"
            ) from exc
        title = item.get("title")
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise DatasetError(f"{where} 的 humanJudgement 第 {index} 项 title 必须是 1–200 字符")
        entries.append((kind, _normalize(title)))

    model_output = raw_case["modelOutput"]
    if not isinstance(model_output, str):
        raise DatasetError(f"{where} 的 modelOutput 必须是字符串")

    return MemoryCase(
        id=case_id,
        snapshot=snapshot,
        judgement=tuple(entries),
        model_output=model_output,
    )


def evaluate(cases: Sequence[MemoryCase]) -> MemoryEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。"""

    if not cases:
        raise ValueError("评测集不能为空")

    ordered = sorted(cases, key=lambda case: case.id)
    items: list[tuple[MemoryCase, MemoryCaseOutcome]] = [(case, _score(case)) for case in ordered]
    count = len(items)
    candidates = sum(outcome.candidates for _, outcome in items)
    matched = sum(outcome.matched for _, outcome in items)
    judged = sum(len(case.judgement) for case, _ in items)
    duplicates = sum(outcome.duplicates for _, outcome in items)
    schema_passed = sum(1 for _, outcome in items if outcome.schema_pass)

    precision = matched / candidates if candidates else 0.0
    recall = matched / judged if judged else 0.0
    agreement = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return MemoryEvalReport(
        cases=tuple(items),
        precision=precision,
        recall=recall,
        candidate_agreement=agreement,
        duplicate_rate=duplicates / candidates if candidates else 0.0,
        schema_pass_rate=schema_passed / count,
        confidence=CONFIDENCE_LOW if count < CONFIDENCE_SAMPLE_THRESHOLD else CONFIDENCE_OK,
    )


def _score(case: MemoryCase) -> MemoryCaseOutcome:
    try:
        parsed = parse_candidates(case.model_output, snapshot=case.snapshot)
    except MemoryCandidateError as exc:
        return MemoryCaseOutcome(
            schema_pass=False,
            reason=exc.reason,
            candidates=0,
            matched=0,
            duplicates=0,
            invalid_source=0,
            invalid_dedupe=0,
        )

    existing_keys = {
        compute_dedupe_key(memory.type, memory.title, memory.content)
        for memory in case.snapshot.existing_memories
    }
    remaining = list(case.judgement)
    matched = 0
    duplicates = 0
    for candidate in parsed.candidates:
        key = (candidate.kind, _normalize(candidate.title))
        if key in remaining:
            remaining.remove(key)
            matched += 1
        if candidate.dedupe_key in existing_keys:
            duplicates += 1
    return MemoryCaseOutcome(
        schema_pass=True,
        reason=None,
        candidates=len(parsed.candidates),
        matched=matched,
        duplicates=duplicates,
        invalid_source=parsed.invalid_source,
        invalid_dedupe=parsed.invalid_dedupe,
    )


def _normalize(value: str) -> str:
    """标题级匹配口径：NFKC + casefold + 空白折叠（与 dedupeKey 的 normalized 一致）。"""

    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def report_document(report: MemoryEvalReport) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。"""

    cases: list[dict[str, Any]] = []
    for case, outcome in report.cases:
        actual: dict[str, Any] = {
            "schemaPass": outcome.schema_pass,
            "candidates": outcome.candidates,
            "matched": outcome.matched,
            "duplicates": outcome.duplicates,
            "invalidSource": outcome.invalid_source,
            "invalidDedupe": outcome.invalid_dedupe,
        }
        if outcome.reason is not None:
            actual["reason"] = outcome.reason
        cases.append(
            {
                "id": case.id,
                "judged": len(case.judgement),
                "actual": actual,
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scorer": "replay",
        "cases": cases,
        "aggregate": {
            "cases": len(report.cases),
            "confidence": report.confidence,
            "precision": report.precision,
            "recall": report.recall,
            "candidateAgreement": report.candidate_agreement,
            "duplicateRate": report.duplicate_rate,
            "schemaPassRate": report.schema_pass_rate,
            "candidates": sum(outcome.candidates for _, outcome in report.cases),
            "invalidSource": sum(outcome.invalid_source for _, outcome in report.cases),
            "invalidDedupe": sum(outcome.invalid_dedupe for _, outcome in report.cases),
        },
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
    "MemoryCase",
    "MemoryCaseOutcome",
    "MemoryEvalReport",
    "canonical_report_bytes",
    "evaluate",
    "load_memory_set",
    "report_document",
    "seal_report",
]
