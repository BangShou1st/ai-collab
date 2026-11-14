"""``ai-eval memory``：Memory 候选提取的离线评测（W5 冻结文档 §4.2，D7 修订）。

冻结集：``tests/fixtures/memory_set/memory_baseline.v1.jsonl``（v1 不动，保持历史封印）。
新增 ``memory_baseline.v2.jsonl``：14 条 case 全保留，并为每条人工判断增加显式
``factRefs``，指向 ``snapshot.facts`` 中真实存在的事实；这是判定集可审计修复，不改变
旧集指纹，也不删除任何 case / judgement。

每条 case 含：

- ``snapshot``：与 ``GET /internal/ai/v1/jobs/{id}/snapshot`` 同形状的快照（facts + 既有记忆）；
- ``modelOutput``：**冻结的代表性候选输出（stub 标注）**，不声称来自真实模型；
- ``humanJudgement``：人工判断"应当提取"的候选集合（``[{kind, title, factRefs?}]``）。

打分方式只有一种：**进程内 replay**，直接复用
``cognition.memory.parser.parse_candidates``（与 ``POST /v1/jobs`` 同一段代码），不联网、
不需要 job token、不调用模型。``dedupeKey`` 也由同一段代码计算，因此与在线行为一致。

匹配口径（``--matcher``）：

- ``exact``（默认，旧行为逐字节不变）：标题级精确匹配（NFKC + casefold + 空白折叠），
  并要求 kind 相同；
- ``semantic``（D7 新增，显式开启）：先精确匹配；未命中时用**确定性词面代理**——
  ``difflib.SequenceMatcher`` 归一化相似度 ≥ ``0.82`` **且** 人工判断标题的字符
  bigram 在候选标题中的包含率 ≥ ``0.80``，并要求 kind 相同。阈值来自冻结样本上的
  可复核间隔：真实同义改写（如 ``写操作必须人工审批`` vs ``写操作必须经过人工审批``
  = 0.90 / 0.875；``检索链路统一走 Java 网关`` vs ``检索链路统一至 Java 网关``
  = 0.9333 / 0.8333）全部通过；近似但含义不同/数字相反的标题（``审批`` vs ``复核``
  = 0.7778；``两周`` vs ``三周`` = 0.8889 但包含率 0.75；``需要`` vs ``无需``
  = 0.8421 但包含率 0.75）被拒绝。该匹配器是**词面代理，不是人工语义裁决**。
- ``semantic`` 模式在报告里新增 ``matcher``、``diagnosticOnly=true`` 与
  ``matchAudit``（逐命中：caseId / candidateTitle / judgementTitle / method / score）；
  ``exact`` 模式不写这些键，保证旧报告逐字节不变。

指标口径（冻结 §4.2）：

- ``precision`` = 命中人工判断的候选数 / 提交的候选数（micro：总和相除）；
- ``recall`` = 命中候选数 / 人工判断总数；
- ``candidateAgreement`` = precision 与 recall 的调和平均（F1；分母为 0 时为 0.0）；
- ``duplicateRate`` = 与既有记忆 ``dedupeKey`` 相同的候选数 / 提交的候选数；
- ``schemaPassRate`` = 模型输出可解析为候选契约的 case 数 / case 数
  （来源非法与 dedupeKey 不一致属于**丢弃**而不是解析失败，不计入 schema 失败）；
- ``confidence``：case 数 < 200 → ``LOW_CONFIDENCE``；
- ``evidence`` / ``qualityClaim``：默认仍为 ``TESTED (stub)`` / ``NOT_VERIFIED``；
  真实模型运行必须由调用方显式传 ``LIVE_VERIFIED``（CLI ``--evidence`` / D10）。

**D7 口径声明**：precision / recall / candidateAgreement 在本模块中一律是**诊断指标**，
不设门禁、不作为通过/失败判定；真实模型结论必须与证据标签、输入来源一起阅读。
``semantic`` 只改善"同义改写"的匹配，不会把无事实支撑的判定变成可召回。
"""

from __future__ import annotations

import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Final

from pydantic import ValidationError

from ai_collab_worker.cognition.memory.parser import (
    MemoryCandidateError,
    compute_dedupe_key,
    parse_candidates,
)
from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.contract.memory_job import (
    MemoryCandidate,
    MemoryJobSnapshot,
    MemoryKind,
)
from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_document,
    digest,
    load_dataset,
)
from ai_collab_worker.eval.labels import resolve_label, resolve_optional_text

#: 样本口径：case 数 < 200 只能标 LOW_CONFIDENCE。
CONFIDENCE_SAMPLE_THRESHOLD: Final[int] = 200
CONFIDENCE_LOW: Final[str] = "LOW_CONFIDENCE"
CONFIDENCE_OK: Final[str] = "OK"

#: 证据与质量声明（无真实模型）。
EVIDENCE_STUB: Final[str] = "TESTED (stub)"
QUALITY_CLAIM_NOT_VERIFIED: Final[str] = "NOT_VERIFIED"

#: 匹配器：exact=旧行为；semantic=D7 新增的确定性词面代理。
MATCHER_EXACT: Final[str] = "exact"
MATCHER_SEMANTIC: Final[str] = "semantic"
VALID_MATCHERS: Final[frozenset[str]] = frozenset({MATCHER_EXACT, MATCHER_SEMANTIC})

#: semantic 阈值（论证见模块 docstring；不要随手放宽）。
SEMANTIC_MIN_RATIO: Final[float] = 0.82
SEMANTIC_MIN_CONTAINMENT: Final[float] = 0.80

#: 诚实声明（写进报告，不是注释）。
LIMITATIONS: Final[str] = (
    "precision/recall/candidateAgreement/duplicateRate 只在 stub 冻结集输出上测量，"
    "不是真实模型结论；真实模型的一致率/重复率只能在 staging 由 Java shadow 链路观测。"
)

#: 显式证据标签（如真实模型运行）下替换默认 stub 声明，避免标签与正文自相矛盾。
EXPLICIT_EVIDENCE_LIMITATIONS: Final[str] = (
    "证据标签与质量声明由调用方显式指定；本报告的指标只对本次输入与标注成立，"
    "不得外推到生产、其他模型或 provider。precision/recall 是诊断指标，不设门禁。"
)

#: semantic 模式的附加声明。
SEMANTIC_LIMITATIONS_NOTE: Final[str] = (
    "semantic 匹配是确定性词面相似度代理（SequenceMatcher + 字符 bigram 包含率），"
    "不是人工语义裁决；precision/recall 仅作诊断，不设门禁。"
)

#: case 的精确字段集。
_CASE_FIELDS: Final[frozenset[str]] = frozenset({"id", "snapshot", "modelOutput", "humanJudgement"})

#: ``humanJudgement`` 条目的精确字段集（``factRefs`` 可选，兼容 v1）。
_JUDGEMENT_FIELDS: Final[frozenset[str]] = frozenset({"kind", "title", "factRefs"})


@dataclass(frozen=True, slots=True)
class MemoryJudgement:
    """一条人工判断：原始标题、归一化标题与可选事实支撑。"""

    kind: MemoryKind
    title: str
    normalized_title: str
    fact_refs: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class MatchAudit:
    """一次候选命中人工判断的审计明细（只在 semantic 模式写入报告）。"""

    candidate_title: str
    judgement_title: str
    method: str
    score: float


@dataclass(frozen=True, slots=True)
class MemoryCase:
    """冻结集的一条 case：快照 + 冻结模型输出 + 人工判断。"""

    id: str
    snapshot: MemoryJobSnapshot
    judgement: tuple[MemoryJudgement, ...]
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
    match_audit: tuple[MatchAudit, ...] = ()


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
    matcher: str = MATCHER_EXACT


def load_memory_set(path: Path) -> list[MemoryCase]:
    """读取 memory 冻结集（JSONL/JSON），逐 case 校验快照与人工判断的形状。

    ``humanJudgement[].factRefs`` 可选；一旦出现，必须是去重后的非空 ref 列表，且每个
    ref 都能在同一个 case 的 ``snapshot.facts`` 中找到（v1 不写 factRefs，仍可加载）。
    """

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
    fact_refs = {fact.ref for fact in snapshot.facts}
    entries: list[MemoryJudgement] = []
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
        refs = _parse_fact_refs(item, fact_refs, where, index)
        entries.append(
            MemoryJudgement(
                kind=kind,
                title=title,
                normalized_title=_normalize(title),
                fact_refs=refs,
            )
        )

    model_output = raw_case["modelOutput"]
    if not isinstance(model_output, str):
        raise DatasetError(f"{where} 的 modelOutput 必须是字符串")

    return MemoryCase(
        id=case_id,
        snapshot=snapshot,
        judgement=tuple(entries),
        model_output=model_output,
    )


def _parse_fact_refs(
    item: Mapping[str, Any],
    fact_refs: set[str],
    where: str,
    index: int,
) -> tuple[str, ...]:
    """校验可选的 ``factRefs``；缺省返回空元组（v1 兼容）。"""

    raw_refs = item.get("factRefs")
    if raw_refs is None:
        return ()
    if not isinstance(raw_refs, list):
        raise DatasetError(f"{where} 的 humanJudgement 第 {index} 项 factRefs 必须是数组")
    if not raw_refs:
        raise DatasetError(f"{where} 的 humanJudgement 第 {index} 项 factRefs 不能为空")
    refs: list[str] = []
    for ref in raw_refs:
        if not isinstance(ref, str) or not ref:
            raise DatasetError(
                f"{where} 的 humanJudgement 第 {index} 项 factRefs 必须是非空字符串数组"
            )
        if ref not in fact_refs:
            raise DatasetError(
                f"{where} 的 humanJudgement 第 {index} 项 factRefs 引用了不存在的事实：{ref!r}"
            )
        if ref in refs:
            raise DatasetError(
                f"{where} 的 humanJudgement 第 {index} 项 factRefs 含重复 ref：{ref!r}"
            )
        refs.append(ref)
    return tuple(refs)


def evaluate(cases: Sequence[MemoryCase], *, matcher: str = MATCHER_EXACT) -> MemoryEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。"""

    if not cases:
        raise ValueError("评测集不能为空")
    if matcher not in VALID_MATCHERS:
        raise ValueError(f"未知的 matcher：{matcher!r}")

    ordered = sorted(cases, key=lambda case: case.id)
    items: list[tuple[MemoryCase, MemoryCaseOutcome]] = [
        (case, _score(case, matcher)) for case in ordered
    ]
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
        matcher=matcher,
    )


def _score(case: MemoryCase, matcher: str) -> MemoryCaseOutcome:
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
    audit: list[MatchAudit] = []
    for candidate in parsed.candidates:
        normalized = _normalize(candidate.title)
        exact_index = _take_exact(candidate.kind, normalized, remaining)
        if exact_index is not None:
            judgement = remaining.pop(exact_index)
            matched += 1
            audit.append(MatchAudit(candidate.title, judgement.title, MATCHER_EXACT, 1.0))
        elif matcher == MATCHER_SEMANTIC:
            semantic = _take_semantic(candidate, normalized, remaining)
            if semantic is not None:
                index, judgement, score = semantic
                remaining.pop(index)
                matched += 1
                audit.append(MatchAudit(candidate.title, judgement.title, MATCHER_SEMANTIC, score))
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
        match_audit=tuple(audit),
    )


def _take_exact(
    kind: MemoryKind,
    normalized_title: str,
    remaining: Sequence[MemoryJudgement],
) -> int | None:
    """返回第一条 kind 与归一化标题都相同的判断下标。"""

    for index, judgement in enumerate(remaining):
        if judgement.kind == kind and judgement.normalized_title == normalized_title:
            return index
    return None


def _take_semantic(
    candidate: MemoryCandidate,
    normalized_title: str,
    remaining: Sequence[MemoryJudgement],
) -> tuple[int, MemoryJudgement, float] | None:
    """在剩余判断中找最高分的 semantic 命中；同分取剩余顺序靠前者（确定性）。"""

    best: tuple[int, MemoryJudgement, float] | None = None
    for index, judgement in enumerate(remaining):
        if judgement.kind != candidate.kind:
            continue
        score = semantic_score(normalized_title, judgement.normalized_title)
        if score is None:
            continue
        if best is None or score > best[2]:
            best = (index, judgement, score)
    return best


def semantic_score(candidate_title: str, judgement_title: str) -> float | None:
    """确定性词面语义分数；不满足双阈值时返回 ``None``。

    入参可以已是归一化标题，也可以传入原文；本函数会再次执行同一归一化（幂等）。
    返回值为 ``SequenceMatcher.ratio()`` 四舍五入到 6 位小数，便于 canonical 报告稳定。
    """

    candidate = _normalize(candidate_title)
    judgement = _normalize(judgement_title)
    if candidate == judgement:
        return 1.0
    if not candidate or not judgement:
        return None
    ratio = SequenceMatcher(None, judgement, candidate, autojunk=False).ratio()
    if ratio < SEMANTIC_MIN_RATIO:
        return None
    if _bigram_containment(judgement, candidate) < SEMANTIC_MIN_CONTAINMENT:
        return None
    return round(ratio, 6)


def _bigram_containment(reference: str, candidate: str) -> float:
    """``reference`` 的字符 bigram 有多大比例出现在 ``candidate`` 中（去空白后）。"""

    reference_compact = "".join(reference.split())
    candidate_compact = "".join(candidate.split())
    if not reference_compact:
        return 0.0
    if len(reference_compact) == 1:
        return 1.0 if reference_compact in candidate_compact else 0.0
    grams = [reference_compact[index : index + 2] for index in range(len(reference_compact) - 1)]
    candidate_grams = {
        candidate_compact[index : index + 2] for index in range(len(candidate_compact) - 1)
    }
    hits = sum(1 for gram in grams if gram in candidate_grams)
    return hits / len(grams)


def _normalize(value: str) -> str:
    """标题级匹配口径：NFKC + casefold + 空白折叠（与 dedupeKey 的 normalized 一致）。"""

    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def report_document(
    report: MemoryEvalReport,
    *,
    evidence: str | None = None,
    quality_claim: str | None = None,
    subject: str | None = None,
    model: str | None = None,
    limitations: str | None = None,
) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。

    ``evidence`` / ``quality_claim`` / ``subject`` / ``model`` 是 D10 的显式标签入参：
    ``None`` 时保持冻结核默认值（旧输出逐字节不变）；显式传入时写入报告。空标签会抛
    ``ValueError``，避免产出"有键无值"的伪证据。
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
    if report.matcher == MATCHER_SEMANTIC:
        limitations_text = f"{limitations_text} {SEMANTIC_LIMITATIONS_NOTE}"

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
    document: dict[str, Any] = {
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
        "limitations": limitations_text,
        "evidence": evidence_label,
        "qualityClaim": quality_label,
    }
    if report.matcher == MATCHER_SEMANTIC:
        document["matcher"] = MATCHER_SEMANTIC
        document["diagnosticOnly"] = True
        document["matchAudit"] = [
            {
                "caseId": case.id,
                "candidateTitle": audit.candidate_title,
                "judgementTitle": audit.judgement_title,
                "method": audit.method,
                "score": audit.score,
            }
            for case, outcome in report.cases
            for audit in outcome.match_audit
        ]
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
    "CONFIDENCE_LOW",
    "CONFIDENCE_OK",
    "CONFIDENCE_SAMPLE_THRESHOLD",
    "EVIDENCE_STUB",
    "EXPLICIT_EVIDENCE_LIMITATIONS",
    "LIMITATIONS",
    "MATCHER_EXACT",
    "MATCHER_SEMANTIC",
    "QUALITY_CLAIM_NOT_VERIFIED",
    "SEMANTIC_LIMITATIONS_NOTE",
    "SEMANTIC_MIN_CONTAINMENT",
    "SEMANTIC_MIN_RATIO",
    "VALID_MATCHERS",
    "MatchAudit",
    "MemoryCase",
    "MemoryCaseOutcome",
    "MemoryEvalReport",
    "MemoryJudgement",
    "canonical_report_bytes",
    "evaluate",
    "load_memory_set",
    "report_document",
    "seal_report",
    "semantic_score",
]
