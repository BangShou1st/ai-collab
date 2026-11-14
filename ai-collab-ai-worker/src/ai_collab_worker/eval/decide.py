"""``ai-eval decide``：Agent 单轮决策的离线评测（W4 冻结文档 §5）。

冻结集：``tests/fixtures/decide_set/decide_baseline.v1.jsonl``。每条 case 含
``id/scenario/goal/locale/context/tools/policy/budget/expected/modelOutput``；``modelOutput``
是**冻结的代表性模型输出（stub 标注）**，不声称来自真实模型采样。

两种打分器：

- :class:`ReplayScorer`（默认）：不联网，把 ``modelOutput`` 走**真实解析/校验核心**
  （``cognition.decide.parser.parse_decision``），与 HTTP 端点共用同一段代码；
- :class:`WorkerScorer`：``POST {worker_url}/v1/agent/decide`` 打真实 worker；422
  ``SCHEMA_VIOLATION`` 视为该 case 的"输出不合法"（计入指标，不退出 4），其它错误码
  （429/503/504/400/500/传输失败）视为评测失败（退出码 4）。

指标口径：

- 顶层 ``decisionAgreement`` / ``jsonValidityRate`` / ``invalidToolCalls`` / ``schemaViolations``
  的分母是**全体 case**（golden + adversarial），语义不变；
- ``bySubset.golden``：正常样本的 ``decisionAgreement``（决策+工具名与 expected 一致的比例）、
  ``jsonValidityRate``（可解析且契约合法 / golden 数）与 ``schemaViolations``；
- ``bySubset.adversarial``：刻意非法负样本的 ``rejectedRate``（被解析/校验核心拒绝的比例，含
  非法 JSON 与契约违规）与 ``invalidToolCalls``（返回了不在 tools 内的工具名的条数）；
- **目标评估看 ``bySubset.golden``**：顶层指标混入了刻意负样本，不能直接与 §10.2 的
  98%/99.5% 对比；
- ``confidence``：样本 < 200 时标 ``LOW_CONFIDENCE``（沿用 rerank 的 samples 口径），否则 ``OK``；
- ``qualityClaim``：无真实模型时固定 ``NOT_VERIFIED``，明确不宣称达到设计 §10.2 目标。

无真实模型 → 报告标 ``evidence=TESTED (stub)`` + ``LOW_CONFIDENCE`` +
``qualityClaim=NOT_VERIFIED``，真实模型结论只能在 staging 产生。

D6（本轮新增）：``--adversarial-mutations`` 显式开启后，对**当前判定合法的 decision
输出**做定向变异（越界工具名 / 非法 decision 枚举 / 缺必填字段 / 非法 JSON），再喂回
``parse_decision``；旧请求级 adversarial 子集保留但不再作为校验器证据，校验器证据看
``bySubset.adversarialMutations`` 与 ``mutationRejectedRate``。

D10：``report_document`` 支持显式 ``evidence`` / ``quality_claim`` / ``subject`` /
``model``；默认值不变，真实模型运行必须显式传 ``LIVE_VERIFIED``。
"""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from types import ModuleType
from typing import TYPE_CHECKING, Any, Final, Protocol

from pydantic import ValidationError

from ai_collab_worker.cognition.decide.parser import (
    DecisionValidationError,
    parse_decision,
)
from ai_collab_worker.contract.agent_decide import (
    AgentDecideRequest,
    AgentDecideResponse,
    DecideDecision,
)
from ai_collab_worker.contract.common import SCHEMA_VERSION
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
    MUTATION_SOURCE_WORKER_RESPONSE,
    MutationOutcome,
    evaluate_decide_mutations,
)

if TYPE_CHECKING:
    import httpx

#: worker 内部服务令牌的环境变量名（--service-token-env 默认值，与 rerank 一致）。
DEFAULT_SERVICE_TOKEN_ENV: Final[str] = "AI_WORKER_SERVICE_TOKEN"

#: P95 同款样本口径：case 数 < 200 只能标 LOW_CONFIDENCE。
CONFIDENCE_SAMPLE_THRESHOLD: Final[int] = 200
CONFIDENCE_LOW: Final[str] = "LOW_CONFIDENCE"
CONFIDENCE_OK: Final[str] = "OK"

#: 证据等级标签（冻结文档 §5/§8）：replay 用 stub 输出，worker 打真实 worker。
EVIDENCE_STUB: Final[str] = "TESTED (stub)"
EVIDENCE_WORKER: Final[str] = "TESTED (worker)"

#: 质量声明：无真实模型时固定 NOT_VERIFIED，不宣称 §10.2 目标。
QUALITY_CLAIM_NOT_VERIFIED: Final[str] = "NOT_VERIFIED"

#: 旧请求级 adversarial 子集的定位（D6）。
ADVERSARIAL_SUBSET_NOTE: Final[str] = (
    "旧 request-level adversarial 子集依赖模型复制非法输入，只保留为历史对照，"
    "不再作为校验器证据；校验器证据看 bySubset.adversarialMutations。"
)

#: case 的评测子集：golden=正常样本；adversarial=刻意非法负样本。
SUBSET_GOLDEN: Final[str] = "golden"
SUBSET_ADVERSARIAL: Final[str] = "adversarial"
VALID_SUBSETS: Final[frozenset[str]] = frozenset({SUBSET_GOLDEN, SUBSET_ADVERSARIAL})

#: case 的精确字段集。
_CASE_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
        "subset",
        "scenario",
        "goal",
        "locale",
        "context",
        "tools",
        "policy",
        "budget",
        "expected",
        "modelOutput",
    }
)

#: case 里属于请求契约的字段（其余是评测专用字段）。
_REQUEST_FIELDS: Final[tuple[str, ...]] = (
    "scenario",
    "goal",
    "locale",
    "context",
    "tools",
    "policy",
    "budget",
)

#: 评测专用的占位身份：真正的 runId/attempt/turnNo/snapshotHash 在打 worker 前合成。
_PLACEHOLDER_RUN_ID: Final[str] = "eval-placeholder"
_PLACEHOLDER_SNAPSHOT_HASH: Final[str] = "sha256:placeholder"

#: 退出码 4 的错误类别（与 rerank 的 ``WorkerError`` 语义一致）。
_STATUS_CODES: Final[Mapping[int, str]] = {
    400: "INVALID_REQUEST",
    401: "INVALID_REQUEST",
    403: "INVALID_REQUEST",
    409: "INVALID_REQUEST",
    422: "SCHEMA_VIOLATION",
    429: "MODEL_RATE_LIMITED",
    503: "MODEL_UNAVAILABLE",
    504: "DEADLINE_EXCEEDED",
}


@dataclass(frozen=True, slots=True)
class DecideCase:
    """冻结集的一条 case：请求模型 + 人工标注 + 冻结模型输出 + 评测子集。"""

    id: str
    subset: str
    request: AgentDecideRequest
    expected_decision: DecideDecision
    expected_tool_name: str | None
    model_output: str


@dataclass(frozen=True, slots=True)
class CaseOutcome:
    """单条 case 的打分结果；``valid`` 即"可解析且契约合法"。"""

    valid: bool
    decision: DecideDecision | None
    tool_name: str | None
    invalid_tool_call: bool
    schema_violation: bool
    reason: str | None
    raw_output: str | None = None
    mutations: tuple[MutationOutcome, ...] = ()


@dataclass(frozen=True, slots=True)
class DecideEvalReport:
    """一次评测的完整结果（不含 SHA-256，由 :func:`seal_report` 追加）。"""

    scorer: str
    cases: tuple[tuple[DecideCase, CaseOutcome], ...]
    decision_agreement: float
    json_validity: float
    invalid_tool_calls: int
    schema_violations: int
    confidence: str
    evidence: str
    mutation_cases: int = 0
    mutation_variants: int = 0
    mutation_rejected: int = 0
    mutation_rejected_rate: float | None = None
    mutation_source: str | None = None


class Decider(Protocol):
    """打分器协议：一条 case → 一个 :class:`CaseOutcome`。"""

    name: str
    evidence: str

    def score(self, case: DecideCase) -> CaseOutcome: ...

    def close(self) -> None: ...


class WorkerError(Exception):
    """worker 打分失败；``code`` 透传 worker 错误信封，缺省时按 HTTP 状态映射。"""

    def __init__(self, code: str, status: int | None, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.status = status
        self.detail = detail


class ReplayScorer:
    """进程内 replay：直接复用 HTTP 端点的解析/校验核心，不联网。"""

    name: str = "replay"
    evidence: str = EVIDENCE_STUB

    def score(self, case: DecideCase) -> CaseOutcome:
        try:
            result = parse_decision(case.model_output, case.request.tools)
        except DecisionValidationError as exc:
            return CaseOutcome(
                valid=False,
                decision=None,
                tool_name=None,
                invalid_tool_call=exc.invalid_tool_name is not None,
                schema_violation=exc.json_parsed,
                reason=exc.reason,
                raw_output=case.model_output,
            )
        return CaseOutcome(
            valid=True,
            decision=result.decision,
            tool_name=None if result.tool_call is None else result.tool_call.name,
            invalid_tool_call=False,
            schema_violation=False,
            reason=None,
            raw_output=case.model_output,
        )

    def close(self) -> None:
        return None


class WorkerScorer:
    """把冻结 case 打到真实 worker（``POST /v1/agent/decide``）。"""

    name: str = "worker"
    evidence: str = EVIDENCE_WORKER

    def __init__(
        self,
        worker_url: str,
        *,
        token_env: str = DEFAULT_SERVICE_TOKEN_ENV,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not worker_url.strip():
            raise ValueError("worker_url 不能为空")
        token = os.environ.get(token_env, "").strip()
        if not token:
            raise WorkerError(
                "SERVICE_TOKEN_MISSING",
                None,
                f"环境变量 {token_env} 未设置或为空；worker 需要内部服务令牌",
            )
        httpx_module = _require_httpx()
        self._url = f"{worker_url.rstrip('/')}/v1/agent/decide"
        self._token = token
        self._client = httpx_module.Client(transport=transport)

    def score(self, case: DecideCase) -> CaseOutcome:
        import httpx  # 只在真正打 worker 时才要求 dev 依赖

        payload = build_worker_payload(case)
        deadline_ms = case.request.budget.deadline_ms
        timeout = httpx.Timeout(deadline_ms / 1000.0 + 5.0, connect=2.0)
        try:
            response = self._client.post(
                self._url,
                json=payload,
                headers={"Authorization": f"Bearer {self._token}"},
                timeout=timeout,
            )
        except httpx.TimeoutException as exc:
            raise WorkerError("DEADLINE_EXCEEDED", None, f"请求超时：{type(exc).__name__}") from exc
        except httpx.HTTPError as exc:
            raise WorkerError("TRANSPORT_ERROR", None, f"{type(exc).__name__}: {exc}") from exc

        if response.status_code >= 400:
            return _worker_failure_outcome(response)
        return _parse_worker_response(response, case)

    def close(self) -> None:
        self._client.close()


def _require_httpx() -> ModuleType:
    """按需导入 httpx；生产镜像不装 dev 依赖时 replay 仍然可用。"""

    try:
        import httpx
    except ImportError as exc:
        raise WorkerError(
            "WORKER_CLIENT_UNAVAILABLE",
            None,
            "缺少 httpx；--scorer worker 需要 dev 依赖（cd ai-collab-ai-worker && uv sync）",
        ) from exc
    return httpx


def _error_envelope(response: httpx.Response) -> tuple[str | None, str | None]:
    try:
        body = response.json()
    except ValueError:
        return None, None
    if not isinstance(body, Mapping):
        return None, None
    code = body.get("code")
    message = body.get("message")
    resolved_code = code if isinstance(code, str) and code else None
    resolved_message = message if isinstance(message, str) and message else None
    return resolved_code, resolved_message


def _worker_failure_outcome(response: httpx.Response) -> CaseOutcome:
    """worker 的 422 是"该 case 输出不合法"，计入指标；其它状态是评测失败（退出码 4）。"""

    code, message = _error_envelope(response)
    status = response.status_code
    if status == 422 and code == "SCHEMA_VIOLATION":
        return CaseOutcome(
            valid=False,
            decision=None,
            tool_name=None,
            invalid_tool_call=False,
            schema_violation=True,
            reason=message or "SCHEMA_VIOLATION",
        )
    resolved = code or _STATUS_CODES.get(status, "INTERNAL" if status >= 500 else "INVALID_REQUEST")
    raise WorkerError(resolved, status, message or f"HTTP {status}")


def _parse_worker_response(response: httpx.Response, case: DecideCase) -> CaseOutcome:
    try:
        body = response.json()
    except ValueError as exc:
        raise WorkerError("INVALID_RESPONSE", response.status_code, "响应不是合法 JSON") from exc
    try:
        parsed = AgentDecideResponse.model_validate(body)
    except ValidationError as exc:
        raise WorkerError(
            "INVALID_RESPONSE",
            response.status_code,
            f"响应不符合 decide 契约：{exc.errors()[0].get('loc', ())}",
        ) from exc

    tool_name = None if parsed.tool_call is None else parsed.tool_call.name
    allowed = {tool.name for tool in case.request.tools}
    invalid_tool_call = tool_name is not None and tool_name not in allowed
    return CaseOutcome(
        valid=True,
        decision=parsed.decision,
        tool_name=tool_name,
        invalid_tool_call=invalid_tool_call,
        schema_violation=False,
        reason=None,
        raw_output=_rebuild_decision_json(parsed),
    )


def _rebuild_decision_json(parsed: AgentDecideResponse) -> str:
    """把 worker 响应重建为可喂回 ``parse_decision`` 的决策 JSON（非原始文本）。"""

    payload: dict[str, object] = {"decision": parsed.decision.value}
    if parsed.tool_call is not None:
        payload["toolCall"] = {
            "name": parsed.tool_call.name,
            "arguments": parsed.tool_call.arguments,
        }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


# --------------------------------------------------------------------------- #
# 冻结集读取与校验
# --------------------------------------------------------------------------- #


def load_decide_set(path: Path) -> list[DecideCase]:
    """读取 decide 冻结集（JSONL/JSON），按冻结 §5 的字段与契约边界 fail-closed 校验。"""

    cases: list[DecideCase] = []
    for position, raw_case in enumerate(load_dataset(path), start=1):
        cases.append(_parse_case(raw_case, position))
    if not cases:
        raise DatasetError("decide 冻结集不能为空")
    return cases


def _parse_case(raw_case: Mapping[str, Any], position: int) -> DecideCase:
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
    if len(build_run_id(case_id)) > 128:
        raise DatasetError(f"{where} 的 id 过长：合成 runId 超过 128 字符")

    subset = raw_case.get("subset")
    if not isinstance(subset, str) or subset not in VALID_SUBSETS:
        raise DatasetError(f"{where} 的 subset 必须是 golden 或 adversarial（评测子集必填）")

    request_payload: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": _PLACEHOLDER_RUN_ID,
        "attempt": 1,
        "turnNo": 0,
        "snapshotHash": _PLACEHOLDER_SNAPSHOT_HASH,
    }
    for field in _REQUEST_FIELDS:
        request_payload[field] = raw_case[field]
    try:
        request = AgentDecideRequest.model_validate(request_payload)
    except ValidationError as exc:
        first = exc.errors()[0]
        location = ".".join(str(part) for part in first.get("loc", ()))
        raise DatasetError(
            f"{where} 不符合 decide 请求契约：{location}: {first.get('msg', '')}"
        ) from exc

    expected = raw_case["expected"]
    if not isinstance(expected, Mapping):
        raise DatasetError(f"{where} 的 expected 必须是对象")
    unknown_expected = sorted(set(expected) - {"decision", "toolName"})
    if unknown_expected:
        raise DatasetError(f"{where} 的 expected 含未定义字段：{', '.join(unknown_expected)}")
    raw_decision = expected.get("decision")
    if not isinstance(raw_decision, str):
        raise DatasetError(f"{where} 的 expected.decision 不在四个取值内")
    try:
        expected_decision = DecideDecision(raw_decision)
    except ValueError as exc:
        raise DatasetError(f"{where} 的 expected.decision 不在四个取值内") from exc
    raw_tool_name = expected.get("toolName")
    if expected_decision is DecideDecision.CALL_TOOL:
        if not isinstance(raw_tool_name, str) or not raw_tool_name:
            raise DatasetError(f"{where} 的 expected.decision=CALL_TOOL 必须给 toolName")
        if raw_tool_name not in {tool.name for tool in request.tools}:
            raise DatasetError(f"{where} 的 expected.toolName 不在本次 tools 内：{raw_tool_name!r}")
    elif raw_tool_name is not None:
        raise DatasetError(f"{where} 的 expected 非 CALL_TOOL 时不得给 toolName")

    model_output = raw_case["modelOutput"]
    if not isinstance(model_output, str):
        raise DatasetError(f"{where} 的 modelOutput 必须是字符串")

    return DecideCase(
        id=case_id,
        subset=subset,
        request=request,
        expected_decision=expected_decision,
        expected_tool_name=None if raw_tool_name is None else str(raw_tool_name),
        model_output=model_output,
    )


# --------------------------------------------------------------------------- #
# 请求构造（replay 不用，worker 用）
# --------------------------------------------------------------------------- #


def build_run_id(case_id: str) -> str:
    """由 case id 合成稳定 runId（≤128 字符）。"""

    return f"eval-decide-{case_id}"


def build_snapshot_hash(case: DecideCase) -> str:
    """由 case 身份合成稳定 snapshotHash：``sha256:<32 hex>``。"""

    identity = {
        "id": case.id,
        "goal": case.request.goal,
        "context": case.request.context.model_dump(by_alias=True, mode="json"),
        "tools": [tool.model_dump(by_alias=True, mode="json") for tool in case.request.tools],
    }
    return "sha256:" + hashlib.sha256(canonicalize_document(identity)).hexdigest()[:32]


def build_worker_payload(case: DecideCase) -> dict[str, Any]:
    """冻结 §2 的请求体：case 字段 + 合成身份（runId/attempt/turnNo/snapshotHash）。"""

    payload = case.request.model_dump(by_alias=True, mode="json")
    payload["runId"] = build_run_id(case.id)
    payload["attempt"] = 1
    payload["turnNo"] = 0
    payload["snapshotHash"] = build_snapshot_hash(case)
    return payload


# --------------------------------------------------------------------------- #
# 评测与报告
# --------------------------------------------------------------------------- #


def evaluate(
    cases: Sequence[DecideCase],
    scorer: Decider,
    *,
    adversarial_mutations: bool = False,
) -> DecideEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。

    ``adversarial_mutations`` 默认关闭，旧输出逐字节不变；显式开启后只对**当前判定
    合法**的个案做定向变异，并提供 ``mutationRejectedRate`` 与逐 case 变异明细。
    """

    if not cases:
        raise ValueError("评测集不能为空")

    ordered = sorted(cases, key=lambda case: case.id)
    mutation_source = (
        (
            MUTATION_SOURCE_WORKER_RESPONSE
            if scorer.name == "worker"
            else MUTATION_SOURCE_REPLAY_DATASET
        )
        if adversarial_mutations
        else None
    )
    items: list[tuple[DecideCase, CaseOutcome]] = []
    for case in ordered:
        outcome = scorer.score(case)
        if adversarial_mutations and outcome.valid and outcome.raw_output is not None:
            outcome = replace(
                outcome,
                mutations=evaluate_decide_mutations(outcome.raw_output, case.request),
            )
        items.append((case, outcome))
    count = len(items)
    agreement = sum(1 for case, outcome in items if agrees(case, outcome))
    valid = sum(1 for _, outcome in items if outcome.valid)
    invalid_tool_calls = sum(1 for _, outcome in items if outcome.invalid_tool_call)
    schema_violations = sum(1 for _, outcome in items if outcome.schema_violation)
    mutation_cases = sum(1 for _, outcome in items if outcome.mutations)
    mutation_variants = sum(len(outcome.mutations) for _, outcome in items)
    mutation_rejected = sum(
        1 for _, outcome in items for mutation in outcome.mutations if mutation.rejected
    )
    confidence = CONFIDENCE_LOW if count < CONFIDENCE_SAMPLE_THRESHOLD else CONFIDENCE_OK
    return DecideEvalReport(
        scorer=scorer.name,
        cases=tuple(items),
        decision_agreement=agreement / count,
        json_validity=valid / count,
        invalid_tool_calls=invalid_tool_calls,
        schema_violations=schema_violations,
        confidence=confidence,
        evidence=scorer.evidence,
        mutation_cases=mutation_cases,
        mutation_variants=mutation_variants,
        mutation_rejected=mutation_rejected,
        mutation_rejected_rate=(
            mutation_rejected / mutation_variants if mutation_variants else None
        ),
        mutation_source=mutation_source,
    )


def agrees(case: DecideCase, outcome: CaseOutcome) -> bool:
    if not outcome.valid or outcome.decision is not case.expected_decision:
        return False
    if case.expected_decision is not DecideDecision.CALL_TOOL:
        return True
    return outcome.tool_name == case.expected_tool_name


def _by_subset(
    items: Sequence[tuple[DecideCase, CaseOutcome]],
    *,
    adversarial_mutations: bool = False,
    mutation_source: str | None = None,
) -> dict[str, dict[str, object]]:
    """按评测子集拆分指标。

    golden 与 adversarial 的语义不同，不能共用一个分母：

    - golden：正常样本，看 ``decisionAgreement`` / ``jsonValidityRate`` / ``schemaViolations``；
    - adversarial：刻意非法负样本，看 ``rejectedRate``（被解析/校验核心拒绝的比例）与
      ``invalidToolCalls``（返回了不在 tools 内的工具名的条数）；
    - D6 开启变异时额外 ``adversarialMutations``：从当前合法输出派生的变异体，衡量校验器
      对定向非法的拒绝能力（与请求级负样本分开，不混算）。
    """

    golden = [(case, outcome) for case, outcome in items if case.subset == SUBSET_GOLDEN]
    adversarial = [(case, outcome) for case, outcome in items if case.subset == SUBSET_ADVERSARIAL]

    golden_count = len(golden)
    golden_agreement = (
        sum(1 for case, outcome in golden if agrees(case, outcome)) / golden_count
        if golden_count
        else 0.0
    )
    golden_valid = sum(1 for _, outcome in golden if outcome.valid)
    golden_json_validity = golden_valid / golden_count if golden_count else 0.0
    golden_schema_violations = sum(1 for _, outcome in golden if outcome.schema_violation)

    adversarial_count = len(adversarial)
    adversarial_rejected = sum(1 for _, outcome in adversarial if not outcome.valid)
    adversarial_rejected_rate = (
        adversarial_rejected / adversarial_count if adversarial_count else 0.0
    )
    adversarial_invalid_tool_calls = sum(
        1 for _, outcome in adversarial if outcome.invalid_tool_call
    )

    result: dict[str, dict[str, object]] = {
        "golden": {
            "cases": golden_count,
            "decisionAgreement": golden_agreement,
            "jsonValidityRate": golden_json_validity,
            "schemaViolations": golden_schema_violations,
        },
        "adversarial": {
            "cases": adversarial_count,
            "rejectedRate": adversarial_rejected_rate,
            "invalidToolCalls": adversarial_invalid_tool_calls,
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
    report: DecideEvalReport,
    *,
    evidence: str | None = None,
    quality_claim: str | None = None,
    subject: str | None = None,
    model: str | None = None,
) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。

    ``aggregate`` 的顶层指标是**全体分母**（含刻意负样本）；目标评估看
    ``aggregate.bySubset.golden``。D10：``evidence`` 默认沿用 scorer 的证据标签，
    ``quality_claim`` 默认 ``NOT_VERIFIED``；显式传入时写入报告，空标签抛 ``ValueError``。
    D6：只有 ``report.mutation_variants > 0`` 时才写变异字段，关闭时报告形状不变。
    """

    evidence_label = resolve_label(evidence, default=report.evidence, field="evidence")
    quality_label = resolve_label(
        quality_claim, default=QUALITY_CLAIM_NOT_VERIFIED, field="qualityClaim"
    )
    subject_label = resolve_optional_text(subject, field="subject")
    model_label = resolve_optional_text(model, field="model")
    mutation_enabled = report.mutation_source is not None

    cases: list[dict[str, Any]] = []
    for case, outcome in report.cases:
        expected: dict[str, Any] = {"decision": case.expected_decision.value}
        if case.expected_tool_name is not None:
            expected["toolName"] = case.expected_tool_name
        actual: dict[str, Any] = {
            "decision": None if outcome.decision is None else outcome.decision.value,
            "toolName": outcome.tool_name,
            "jsonValid": outcome.valid,
            "invalidToolCall": outcome.invalid_tool_call,
            "schemaViolation": outcome.schema_violation,
        }
        if outcome.reason is not None:
            actual["reason"] = outcome.reason
        entry: dict[str, Any] = {
            "id": case.id,
            "subset": case.subset,
            "expected": expected,
            "actual": actual,
            "agreement": agrees(case, outcome),
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
        "decisionAgreement": report.decision_agreement,
        "invalidToolCalls": report.invalid_tool_calls,
        "jsonValidityRate": report.json_validity,
        "schemaViolations": report.schema_violations,
        "scorer": report.scorer,
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
        "scorer": report.scorer,
        "cases": cases,
        "aggregate": aggregate,
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
    "EVIDENCE_WORKER",
    "QUALITY_CLAIM_NOT_VERIFIED",
    "SUBSET_ADVERSARIAL",
    "SUBSET_GOLDEN",
    "VALID_SUBSETS",
    "CaseOutcome",
    "DecideCase",
    "DecideEvalReport",
    "Decider",
    "ReplayScorer",
    "WorkerError",
    "WorkerScorer",
    "agrees",
    "build_run_id",
    "build_snapshot_hash",
    "build_worker_payload",
    "canonical_report_bytes",
    "evaluate",
    "load_decide_set",
    "report_document",
    "seal_report",
]
