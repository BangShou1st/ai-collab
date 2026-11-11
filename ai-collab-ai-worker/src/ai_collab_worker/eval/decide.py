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

- ``decisionAgreement`` = （决策类型一致，且非 CALL_TOOL 或工具名一致）/ case 数；
- ``jsonValidityRate`` = （可解析为 JSON object 且契约合法）/ case 数；
- ``invalidToolCalls`` = 模型返回了工具名但不在本次 ``tools`` 内的 case 数；
- ``schemaViolations`` = 解析出 JSON object 但契约违规（未知决策、缺字段、toolCall 形状错等）
  的 case 数；
- ``confidence``：样本 < 200 时标 ``LOW_CONFIDENCE``（沿用 rerank 的 samples 口径），否则 ``OK``。

无真实模型 → 报告标 ``evidence=TESTED (stub)`` + ``LOW_CONFIDENCE``，**不宣称达到**设计
§10.2 的一致率/合法率目标；真实模型结论只能在 staging 产生。
"""

from __future__ import annotations

import hashlib
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
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

#: case 的精确字段集。
_CASE_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
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
    """冻结集的一条 case：请求模型 + 人工标注 + 冻结模型输出。"""

    id: str
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
            )
        return CaseOutcome(
            valid=True,
            decision=result.decision,
            tool_name=None if result.tool_call is None else result.tool_call.name,
            invalid_tool_call=False,
            schema_violation=False,
            reason=None,
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
    )


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


def evaluate(cases: Sequence[DecideCase], scorer: Decider) -> DecideEvalReport:
    """逐 case 打分并汇总指标（case 按 id 的 Unicode 码点升序，保证报告稳定）。"""

    if not cases:
        raise ValueError("评测集不能为空")

    ordered = sorted(cases, key=lambda case: case.id)
    items: list[tuple[DecideCase, CaseOutcome]] = [(case, scorer.score(case)) for case in ordered]
    count = len(items)
    agreement = sum(1 for case, outcome in items if agrees(case, outcome))
    valid = sum(1 for _, outcome in items if outcome.valid)
    invalid_tool_calls = sum(1 for _, outcome in items if outcome.invalid_tool_call)
    schema_violations = sum(1 for _, outcome in items if outcome.schema_violation)
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
    )


def agrees(case: DecideCase, outcome: CaseOutcome) -> bool:
    if not outcome.valid or outcome.decision is not case.expected_decision:
        return False
    if case.expected_decision is not DecideDecision.CALL_TOOL:
        return True
    return outcome.tool_name == case.expected_tool_name


def report_document(report: DecideEvalReport) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。"""

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
        cases.append(
            {
                "id": case.id,
                "expected": expected,
                "actual": actual,
                "agreement": agrees(case, outcome),
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scorer": report.scorer,
        "cases": cases,
        "aggregate": {
            "cases": len(report.cases),
            "confidence": report.confidence,
            "decisionAgreement": report.decision_agreement,
            "invalidToolCalls": report.invalid_tool_calls,
            "jsonValidityRate": report.json_validity,
            "schemaViolations": report.schema_violations,
            "scorer": report.scorer,
        },
        "evidence": report.evidence,
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
    "EVIDENCE_WORKER",
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
