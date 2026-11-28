"""``POST /v1/knowledge/qa:stream`` 的请求模型、校验与事件契约（P3 契约 §3.3、§3.5）。

校验分两层（与 ``contract.knowledge_search`` 同构）：

1. :func:`parse_qa_request` 把原始 JSON 解析成 :class:`KnowledgeQaRequest`；
   格式错误一律 400 ``VALIDATION_ERROR``；
2. :func:`authorize_qa` 对比令牌 ``uid/pid`` 与请求体：缺失 → 401，不符 → 403
   ``QA_FORBIDDEN``（``knowledge:qa`` scope 本身由 ``TokenVerifier`` 校验）。

事件契约（SSE）：``token{text}`` → ``finalize{...}`` 终止，或 ``error{code,message}``
终止。错误码沿用 P2 §4.4 风格 + worker 冻结枚举（泄漏终止用 ``CONTENT_BLOCKED``）。
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Final, Literal

from pydantic import Field, ValidationError, field_validator

from ai_collab_worker.contract.common import (
    DEADLINE_MS_MAX,
    DEADLINE_MS_MIN,
    SCHEMA_VERSION,
    ContractModel,
)
from ai_collab_worker.contract.knowledge_search import RerankMode

#: 契约 §3.3 的状态码与错误码（Java 只按 code 分支）。
CODE_VALIDATION_ERROR: Final[str] = "VALIDATION_ERROR"
CODE_UNAUTHORIZED: Final[str] = "UNAUTHORIZED"
CODE_QA_FORBIDDEN: Final[str] = "QA_FORBIDDEN"
CODE_QA_DEADLINE_EXCEEDED: Final[str] = "QA_DEADLINE_EXCEEDED"
CODE_AI_PROVIDER_UNAVAILABLE: Final[str] = "AI_PROVIDER_UNAVAILABLE"
CODE_INTERNAL_ERROR: Final[str] = "INTERNAL_ERROR"
CODE_CONTENT_BLOCKED: Final[str] = "CONTENT_BLOCKED"

QUESTION_MAX_CODE_POINTS: Final[int] = 2000
DOCUMENT_IDS_MAX: Final[int] = 20
VISIBLE_DOCUMENTS_MAX: Final[int] = 5000
TOP_K: Final[int] = 8
#: 进程内检索的 deadline（Java ``KnowledgeSearchProperties`` 默认 8000ms 同口径）。
SEARCH_DEADLINE_MS: Final[int] = 8000

#: SSE 事件名（Java 中继与 N 面评测按名字分发）。
EVENT_TOKEN: Final[str] = "token"
EVENT_FINALIZE: Final[str] = "finalize"
EVENT_ERROR: Final[str] = "error"


class HistoryRole(StrEnum):
    USER = "USER"
    ASSISTANT = "ASSISTANT"


class HistoryMessage(ContractModel):
    """会话历史一条消息（Java ``repository.recentContext`` 下发，裁剪在 worker 侧执行）。"""

    role: HistoryRole
    content: str = Field(max_length=100_000)
    insufficient_evidence: bool = False
    #: ISO-8601（含时区）；排序键，缺失排在最早（Java nullsFirst）。
    created_at: str | None = Field(default=None, alias="createdAt")


class KnowledgeQaRequest(ContractModel):
    """问答生成请求（P3 契约 §3.3 形状 + rerank 沿 P2 §4.4 由 Java 决定下发）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    request_id: uuid.UUID
    project_id: uuid.UUID
    user_id: uuid.UUID
    #: 仅作调用追溯；worker 不校验会话属主（属主校验在 Java 入站）。
    session_id: uuid.UUID
    question: str = Field(min_length=1)
    document_ids: tuple[uuid.UUID, ...] = Field(default=(), max_length=DOCUMENT_IDS_MAX)
    visible_document_ids: tuple[uuid.UUID, ...]
    #: 文档名回填（Java 在计算可见集合时已取得；worker 不读业务 schema，证据块的
    #: "文件：" 行以此填充；缺省为空 → 文件名留空，仅影响提示词排版）。
    document_names: dict[str, str] = Field(default_factory=dict)
    top_k: int = Field(default=TOP_K, ge=1, le=20)
    history: tuple[HistoryMessage, ...] = Field(default=(), max_length=50)
    rerank: RerankMode
    deadline_ms: int = Field(ge=DEADLINE_MS_MIN, le=DEADLINE_MS_MAX)

    @field_validator("visible_document_ids")
    @classmethod
    def _dedupe_visible(cls, value: tuple[uuid.UUID, ...]) -> tuple[uuid.UUID, ...]:
        return tuple(dict.fromkeys(value))

    @field_validator("document_ids")
    @classmethod
    def _dedupe_documents(cls, value: tuple[uuid.UUID, ...]) -> tuple[uuid.UUID, ...]:
        return tuple(dict.fromkeys(value))

    @property
    def effective_question(self) -> str:
        """问题按 code point 限长（Java 入站已裁剪，这里兜底拒绝）。"""

        return self.question


class QaRejectedError(Exception):
    """问答请求被拒：``status`` / ``code`` 按契约 §3.3，``reason`` 为固定标签。"""

    status: int
    code: str
    reason: str
    message: str | None = None
    retryable: bool = False

    def __init__(
        self,
        status: int,
        code: str,
        reason: str,
        *,
        message: str | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(f"{status} {code} reason={reason}")
        self.status = status
        self.code = code
        self.reason = reason
        self.message = message
        self.retryable = retryable


def parse_qa_request(raw: object) -> KnowledgeQaRequest:
    """解析并校验请求；任何不合格都抛 400 ``VALIDATION_ERROR``（契约 §3.3）。"""

    if not isinstance(raw, Mapping):
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "invalid_request")
    visible = raw.get("visibleDocumentIds")
    if isinstance(visible, list | tuple) and len(visible) > VISIBLE_DOCUMENTS_MAX:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "visible_documents_limit")
    question = raw.get("question")
    if isinstance(question, str) and len(question) > QUESTION_MAX_CODE_POINTS:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "question_too_long")
    rerank = raw.get("rerank")
    if isinstance(rerank, str) and rerank not in RerankMode.__members__:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "invalid_request")
    try:
        request = KnowledgeQaRequest.model_validate(raw)
    except ValidationError:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "invalid_request") from None
    if len(request.visible_document_ids) > VISIBLE_DOCUMENTS_MAX:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "visible_documents_limit")
    if len(request.question) > QUESTION_MAX_CODE_POINTS:
        raise QaRejectedError(400, CODE_VALIDATION_ERROR, "question_too_long")
    return request


def authorize_qa(claims: Mapping[str, object], request: KnowledgeQaRequest) -> None:
    """令牌 ``uid/pid`` 必须存在（否则 401）且等于请求体（否则 403）。"""

    uid = _claim_uuid(claims, "uid")
    pid = _claim_uuid(claims, "pid")
    if uid is None or pid is None:
        raise QaRejectedError(401, CODE_UNAUTHORIZED, "claims_missing")
    if uid != request.user_id or pid != request.project_id:
        raise QaRejectedError(403, CODE_QA_FORBIDDEN, "principal_mismatch")


def _claim_uuid(claims: Mapping[str, object], name: str) -> uuid.UUID | None:
    value = claims.get(name)
    if not isinstance(value, str):
        return None
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


# ---- SSE 事件 ---------------------------------------------------------------- #


@dataclass(frozen=True, slots=True)
class CitationIdentity:
    """P2 §6 引用身份 + 排名/相似度/quote（finalize.citations[] 的一条）。"""

    document_id: uuid.UUID
    chunk_no: int
    content_hash: str
    body_snapshot_id: uuid.UUID | None
    rank: int
    similarity: float
    quote: str

    def to_wire(self) -> dict[str, object]:
        return {
            "documentId": str(self.document_id),
            "chunkNo": self.chunk_no,
            "contentHash": self.content_hash,
            "bodySnapshotId": str(self.body_snapshot_id) if self.body_snapshot_id else None,
            "rank": self.rank,
            "similarity": self.similarity,
            "quote": self.quote,
        }


@dataclass(frozen=True, slots=True)
class RetrievalTrace:
    """finalize.retrieval：检索口径回执（不含查询原文以外的高基数内容）。"""

    strategy: str
    effective_query: str
    route_reason: str
    sub_queries: tuple[str, ...]

    def to_wire(self) -> dict[str, object]:
        return {
            "strategy": self.strategy,
            "effectiveQuery": self.effective_query,
            "routeReason": self.route_reason,
            "subQueries": list(self.sub_queries),
        }


@dataclass(frozen=True, slots=True)
class PromptIdentity:
    """finalize.prompt：落库 promptKey/promptVersion（V90 成对语义）。"""

    key: str
    version: str

    def to_wire(self) -> dict[str, object]:
        return {"key": self.key, "version": self.version}


@dataclass(frozen=True, slots=True)
class ModelTrace:
    """finalize.model：``insufficientEvidence=true`` 的未调模型轮次为 ``None``。"""

    provider: str | None = None
    name: str | None = None
    latency_ms: int | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None

    def to_wire(self) -> dict[str, object]:
        return {
            "provider": self.provider,
            "name": self.name,
            "latencyMs": self.latency_ms,
            "promptTokens": self.prompt_tokens,
            "completionTokens": self.completion_tokens,
        }


def encode_event(name: str, payload: Mapping[str, object]) -> bytes:
    """SSE 帧编码：``event: <name>\\ndata: <json>\\n\\n``（紧凑 UTF-8 JSON）。"""

    data = json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    return f"event: {name}\ndata: {data}\n\n".encode()


def token_event(text: str) -> bytes:
    return encode_event(EVENT_TOKEN, {"text": text})


def error_event(code: str, message: str) -> bytes:
    return encode_event(EVENT_ERROR, {"code": code, "message": message})


def finalize_event(
    request_id: uuid.UUID,
    answer: str,
    *,
    insufficient_evidence: bool,
    citations: tuple[CitationIdentity, ...],
    retrieval: RetrievalTrace | None,
    prompt: PromptIdentity | None,
    model: ModelTrace | None,
) -> bytes:
    payload: dict[str, object] = {
        "requestId": str(request_id),
        "answer": answer,
        "insufficientEvidence": insufficient_evidence,
        "citations": [item.to_wire() for item in citations],
        "retrieval": retrieval.to_wire() if retrieval is not None else None,
        "prompt": prompt.to_wire() if prompt is not None else None,
        "model": model.to_wire() if model is not None else None,
    }
    return encode_event(EVENT_FINALIZE, payload)


def error_response(
    code: str,
    message: str,
    *,
    retryable: bool,
    reason: str | None = None,
) -> dict[str, Any]:
    """HTTP 错误信封（非流式路径：鉴权失败 / 校验失败在流建立前返回）。

    信封形状与冻结契约一致：``{code, message, retryable, details?}``；HTTP 状态码由
    调用方（端点）决定，不在信封内。
    """

    payload: dict[str, Any] = {"code": code, "message": message, "retryable": retryable}
    if reason:
        payload["details"] = {"reason": reason}
    return payload


__all__ = [
    "CODE_AI_PROVIDER_UNAVAILABLE",
    "CODE_CONTENT_BLOCKED",
    "CODE_INTERNAL_ERROR",
    "CODE_QA_DEADLINE_EXCEEDED",
    "CODE_QA_FORBIDDEN",
    "CODE_UNAUTHORIZED",
    "CODE_VALIDATION_ERROR",
    "EVENT_ERROR",
    "EVENT_FINALIZE",
    "EVENT_TOKEN",
    "SEARCH_DEADLINE_MS",
    "TOP_K",
    "VISIBLE_DOCUMENTS_MAX",
    "CitationIdentity",
    "HistoryMessage",
    "HistoryRole",
    "KnowledgeQaRequest",
    "ModelTrace",
    "PromptIdentity",
    "QaRejectedError",
    "RetrievalTrace",
    "authorize_qa",
    "encode_event",
    "error_event",
    "error_response",
    "finalize_event",
    "parse_qa_request",
    "token_event",
]
