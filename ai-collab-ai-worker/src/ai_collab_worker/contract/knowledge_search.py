"""``POST /v1/knowledge/search`` 的请求、响应模型与请求校验（P2 契约 §4.4，P2-R1 / P2-R2）。

校验分两层：

1. :func:`parse_search_request` 把原始 JSON 解析成 :class:`KnowledgeSearchRequest`。契约要求格式
   错误一律 **400 ``VALIDATION_ERROR``**（不是全局处理器的 422），所以这里自己捕获
   ``ValidationError``，统一抛 :class:`SearchRejectedError`；
2. :func:`authorize_search` 对比令牌 ``uid/pid`` 与请求体：缺失 → 401，不符 → 403
   ``SEARCH_FORBIDDEN``（``knowledge:search`` scope 本身由 ``TokenVerifier`` 校验）。

校验错误的 ``reason`` 是固定的低基数标签，绝不回显查询原文或文档 ID。
"""

from __future__ import annotations

import math
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Final

from pydantic import Field, ValidationError, field_validator, model_validator

from ai_collab_worker.contract.common import DEADLINE_MS_MAX, DEADLINE_MS_MIN, ContractModel

#: 契约 §4.4 / Q9：可见集合上限；超过返回 400，不截断。
MAX_VISIBLE_DOCUMENTS: Final[int] = 5000
#: 契约 §4.4：topK ∈ [1, 20]，candidatePool ∈ [topK, 50]，queries 1–5 条、每条 ≤ 2000 字符。
TOP_K_MIN: Final[int] = 1
TOP_K_MAX: Final[int] = 20
CANDIDATE_POOL_MAX: Final[int] = 50
QUERIES_MAX: Final[int] = 5
QUERY_TEXT_MAX: Final[int] = 2000
#: P2-R2 第 4 条：MULTI_QUERY 响应条数上限 ``min(topK + 3(n-1), 24)``。
MAX_RESPONSE_HITS: Final[int] = 24
#: ``ai_index.chunk.heading`` 为 ``varchar(300)``。
HEADING_FILTER_MAX: Final[int] = 300

#: 契约 §4.4 的状态码与错误码（Java 只按 HTTP 状态分支）。
CODE_VALIDATION_ERROR: Final[str] = "VALIDATION_ERROR"
CODE_UNAUTHORIZED: Final[str] = "UNAUTHORIZED"
CODE_SEARCH_FORBIDDEN: Final[str] = "SEARCH_FORBIDDEN"


class SearchIntent(StrEnum):
    KNOWLEDGE_QA = "KNOWLEDGE_QA"
    AGENT_RESEARCH = "AGENT_RESEARCH"
    PLANNING_SOURCE = "PLANNING_SOURCE"
    PLANNING_TOOL_LOOKUP = "PLANNING_TOOL_LOOKUP"


class RetrievalStrategy(StrEnum):
    """Java ``RetrievalStrategy`` 的 4 个取值（Java 语义报告 §1，P2-R2 第 5 条）。"""

    DENSE = "DENSE"
    HYBRID = "HYBRID"
    MULTI_QUERY_HYBRID = "MULTI_QUERY_HYBRID"
    DENSE_WITH_BOUNDED_FALLBACK = "DENSE_WITH_BOUNDED_FALLBACK"


class QueryRole(StrEnum):
    PRIMARY = "PRIMARY"
    SUB = "SUB"


class RerankMode(StrEnum):
    HEURISTIC = "HEURISTIC"
    CONFIGURED = "CONFIGURED"


class ChunkType(StrEnum):
    STANDARD = "STANDARD"
    PARENT = "PARENT"
    CHILD = "CHILD"
    SENTENCE = "SENTENCE"


@dataclass(frozen=True)
class SearchRejectedError(Exception):
    """检索请求被拒：``status`` / ``code`` 按契约 §4.4 状态表，``reason`` 为固定标签。"""

    status: int
    code: str
    reason: str
    message: str | None = None
    retryable: bool = False

    def __str__(self) -> str:
        return f"{self.status} {self.code} reason={self.reason}"


# ---- 请求 ----------------------------------------------------------------------


class SearchQuery(ContractModel):
    text: str = Field(min_length=1, max_length=QUERY_TEXT_MAX)
    role: QueryRole


class HeadingFilter(ContractModel):
    exact: str | None = Field(default=None, min_length=1, max_length=HEADING_FILTER_MAX)
    contains: str | None = Field(default=None, min_length=1, max_length=HEADING_FILTER_MAX)


class ExpandOptions(ContractModel):
    sentence_window: bool = True
    parent: bool = True


class KnowledgeSearchRequest(ContractModel):
    """检索请求。``strategy`` 先按字符串收下，再由 :func:`parse_search_request` 判未知取值。"""

    request_id: uuid.UUID
    project_id: uuid.UUID
    user_id: uuid.UUID
    intent: SearchIntent
    strategy: RetrievalStrategy
    queries: tuple[SearchQuery, ...] = Field(min_length=1, max_length=QUERIES_MAX)
    original_query: str = Field(min_length=1, max_length=QUERY_TEXT_MAX)
    visible_document_ids: tuple[uuid.UUID, ...]
    top_k: int = Field(ge=TOP_K_MIN, le=TOP_K_MAX)
    candidate_pool: int = Field(ge=TOP_K_MIN, le=CANDIDATE_POOL_MAX)
    heading_filter: HeadingFilter = Field(default_factory=HeadingFilter)
    rerank: RerankMode
    expand: ExpandOptions = Field(default_factory=ExpandOptions)
    deadline_ms: int = Field(ge=DEADLINE_MS_MIN, le=DEADLINE_MS_MAX)

    @field_validator("visible_document_ids")
    @classmethod
    def _dedupe(cls, value: tuple[uuid.UUID, ...]) -> tuple[uuid.UUID, ...]:
        return tuple(dict.fromkeys(value))

    @model_validator(mode="after")
    def _check_shape(self) -> KnowledgeSearchRequest:
        if sum(1 for q in self.queries if q.role is QueryRole.PRIMARY) != 1:
            msg = "queries 必须恰好一条 PRIMARY"
            raise ValueError(msg)
        if self.candidate_pool < self.top_k:
            msg = "candidatePool 必须 >= topK"
            raise ValueError(msg)
        return self

    @property
    def primary_query(self) -> str:
        return next(q.text for q in self.queries if q.role is QueryRole.PRIMARY)

    @property
    def sub_queries(self) -> tuple[str, ...]:
        return tuple(q.text for q in self.queries if q.role is QueryRole.SUB)


def parse_search_request(raw: object) -> KnowledgeSearchRequest:
    """解析并校验请求；任何不合格都抛 400 ``VALIDATION_ERROR``（契约 §4.4 状态表）。

    可见集合超过 5000、未知 ``strategy`` 各自有独立 ``reason``，便于 Java 侧排查；
    其余格式错误统一 ``reason=invalid_request``，不回显字段值。
    """

    if not isinstance(raw, Mapping):
        raise SearchRejectedError(400, CODE_VALIDATION_ERROR, "invalid_request")
    visible = raw.get("visibleDocumentIds")
    if isinstance(visible, list | tuple) and len(visible) > MAX_VISIBLE_DOCUMENTS:
        raise SearchRejectedError(400, CODE_VALIDATION_ERROR, "visible_documents_limit")
    strategy = raw.get("strategy")
    if isinstance(strategy, str) and strategy not in RetrievalStrategy.__members__:
        raise SearchRejectedError(400, CODE_VALIDATION_ERROR, "unknown_strategy")
    try:
        request = KnowledgeSearchRequest.model_validate(raw)
    except ValidationError:
        raise SearchRejectedError(400, CODE_VALIDATION_ERROR, "invalid_request") from None
    # 去重后仍可能超限（原始数组在上面已拦截；这里兜底元组输入）。
    if len(request.visible_document_ids) > MAX_VISIBLE_DOCUMENTS:
        raise SearchRejectedError(400, CODE_VALIDATION_ERROR, "visible_documents_limit")
    return request


def authorize_search(claims: Mapping[str, object], request: KnowledgeSearchRequest) -> None:
    """令牌 ``uid/pid`` 必须存在（否则 401）且等于请求体 ``userId/projectId``（否则 403）。"""

    uid = _claim_uuid(claims, "uid")
    pid = _claim_uuid(claims, "pid")
    if uid is None or pid is None:
        raise SearchRejectedError(401, CODE_UNAUTHORIZED, "claims_missing")
    if uid != request.user_id or pid != request.project_id:
        raise SearchRejectedError(403, CODE_SEARCH_FORBIDDEN, "principal_mismatch")


def _claim_uuid(claims: Mapping[str, object], name: str) -> uuid.UUID | None:
    value = claims.get(name)
    if not isinstance(value, str):
        return None
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


# ---- 响应 ----------------------------------------------------------------------


def admission_score(dense: float | None, lexical: float | None) -> float:
    """``scores.final``（P2-R1 第 2 条）：``max(dense 余弦, lexical 匹配强度)``，缺失一路不参与。

    与 Java ``RetrievalCandidate.admissionScore`` 同义；两路都缺失为 0。余弦相似度理论上可为负，
    契约要求 ``final ∈ [0,1]``，因此夹到 ``[0,1]``——小于 0 的值无论夹不夹都低于 0.15 阈值，
    阈值判定与 Java 一致。
    """

    present = [value for value in (dense, lexical) if value is not None]
    if not present:
        return 0.0
    best = max(present)
    if not math.isfinite(best):
        return 0.0
    return min(1.0, max(0.0, best))


class SearchScores(ContractModel):
    dense: float | None = None
    lexical: float | None = None
    rrf: float | None = Field(default=None, ge=0.0)
    rerank: float | None = None
    final: float = Field(ge=0.0, le=1.0)

    @field_validator("dense", "lexical", "rrf", "rerank", "final")
    @classmethod
    def _finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            msg = "分数必须是有限值"
            raise ValueError(msg)
        return value

    @model_validator(mode="after")
    def _final_is_admission(self) -> SearchScores:
        # 契约测试固定语义：final 只能由 dense/lexical 推出，rrf / rerank 不得进入。
        if abs(self.final - admission_score(self.dense, self.lexical)) > 1e-12:
            msg = "scores.final 必须等于 max(dense, lexical)"
            raise ValueError(msg)
        return self


class ChunkRef(ContractModel):
    chunk_no: int = Field(ge=0)
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


class HitContext(ContractModel):
    text: str
    chunk_refs: tuple[ChunkRef, ...] = Field(min_length=1)


class SourceRef(ContractModel):
    char_from: int | None = None
    char_through: int | None = None
    page_number: int | None = None


class SearchHit(ContractModel):
    rank: int = Field(ge=1)
    document_id: uuid.UUID
    chunk_no: int = Field(ge=0)
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    chunk_type: ChunkType
    parent_chunk_no: int | None = None
    heading: str | None = None
    content: str
    body_snapshot_id: uuid.UUID
    source_ref: SourceRef = Field(default_factory=SourceRef)
    context: HitContext | None = None
    scores: SearchScores


class SearchDiagnostics(ContractModel):
    """不得含查询原文、正文与 key（契约 §4.4）：只允许计数、布尔与固定标签。"""

    dense_candidates: int = Field(ge=0)
    lexical_candidates: int = Field(ge=0)
    iterative_scan: bool
    rerank: RerankMode
    latency_ms: int = Field(ge=0)


class KnowledgeSearchResponse(ContractModel):
    request_id: uuid.UUID
    #: 空可见集合时不访问数据库，因此不知道 ACTIVE generation（见汇报）。
    generation_id: uuid.UUID | None
    strategy: RetrievalStrategy
    hits: tuple[SearchHit, ...] = Field(max_length=MAX_RESPONSE_HITS)
    diagnostics: SearchDiagnostics

    @model_validator(mode="after")
    def _ranks_are_consecutive(self) -> KnowledgeSearchResponse:
        if [hit.rank for hit in self.hits] != list(range(1, len(self.hits) + 1)):
            msg = "hits.rank 必须从 1 起连续"
            raise ValueError(msg)
        return self


def enforce_visibility(
    hits: Sequence[SearchHit], visible: Sequence[uuid.UUID]
) -> tuple[tuple[SearchHit, ...], int]:
    """纵深防御：丢弃 ``visibleDocumentIds`` 以外的命中并重排 rank，返回 ``(hits, 丢弃数)``。

    SQL 已带 ``document_id = ANY($ids)``，正常情况下丢弃数恒为 0；非 0 说明查询拼装有缺陷。
    """

    allowed = frozenset(visible)
    kept = [hit for hit in hits if hit.document_id in allowed]
    renumbered = tuple(
        hit.model_copy(update={"rank": index}) for index, hit in enumerate(kept, start=1)
    )
    return renumbered, len(hits) - len(kept)


def empty_response(request: KnowledgeSearchRequest, *, latency_ms: int) -> KnowledgeSearchResponse:
    """空可见集合：200 + ``hits=[]``，不访问数据库、不租借（契约 §4.4 / §9.2）。"""

    return KnowledgeSearchResponse(
        request_id=request.request_id,
        generation_id=None,
        strategy=request.strategy,
        hits=(),
        diagnostics=SearchDiagnostics(
            dense_candidates=0,
            lexical_candidates=0,
            iterative_scan=False,
            rerank=request.rerank,
            latency_ms=latency_ms,
        ),
    )


def response_json(response: KnowledgeSearchResponse) -> dict[str, Any]:
    return response.model_dump(by_alias=True, mode="json")


__all__ = [
    "CANDIDATE_POOL_MAX",
    "CODE_SEARCH_FORBIDDEN",
    "CODE_UNAUTHORIZED",
    "CODE_VALIDATION_ERROR",
    "MAX_RESPONSE_HITS",
    "MAX_VISIBLE_DOCUMENTS",
    "ChunkRef",
    "ChunkType",
    "ExpandOptions",
    "HeadingFilter",
    "HitContext",
    "KnowledgeSearchRequest",
    "KnowledgeSearchResponse",
    "QueryRole",
    "RerankMode",
    "RetrievalStrategy",
    "SearchDiagnostics",
    "SearchHit",
    "SearchIntent",
    "SearchQuery",
    "SearchRejectedError",
    "SearchScores",
    "SourceRef",
    "admission_score",
    "authorize_search",
    "empty_response",
    "enforce_visibility",
    "parse_search_request",
    "response_json",
]
