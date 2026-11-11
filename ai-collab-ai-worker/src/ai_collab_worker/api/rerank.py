"""``POST /v1/rag/rerank``：模型打分 + 超时、幂等与指标（设计 §7.3，冻结文档 §1）。

端到端流程：

1. 服务令牌鉴权（常量时间比较；只作用于本路由，不影响 prompts 端点）；
2. ``Idempotency-Key`` 可选；命中同 key + 同请求体时直接重放**逐字节相同**的响应，
   不再调用网关；同 key 不同请求体 → ``INVALID_REQUEST``(400)；
3. 在 ``asyncio.timeout(deadlineMs/1000)`` 内拼 Prompt、调用 Java 网关、解析、映射分数；
   超时 → ``DEADLINE_EXCEEDED``(504)，**不返回半成品**；
4. 响应覆盖全部输入 id，模型漏掉的 id 用 0.0 补齐并记 ``partial`` 指标/日志。

指标（独立 ``REGISTRY``，低基数）：``ai_worker_rerank_requests_total{outcome}``、
``ai_worker_rerank_duration_seconds``、``ai_worker_rerank_missing_scores_total``。
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import threading
import time
import uuid
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated, Final

from fastapi import APIRouter, Depends, Request, Response

from ai_collab_worker.clients.java_gateway import JavaGatewayClient
from ai_collab_worker.cognition.rerank.parser import parse_scores
from ai_collab_worker.cognition.rerank.prompt import build_scoring_prompt
from ai_collab_worker.cognition.rerank.scorer import score_candidates
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import (
    IDEMPOTENCY_HEADER,
    AppError,
    ErrorCode,
    validate_idempotency_key,
)
from ai_collab_worker.contract.rerank import (
    RerankRequest,
    RerankResponse,
    RerankUsage,
    RerankUsageSource,
)
from ai_collab_worker.observability.metrics import (
    RERANK_DURATION,
    RERANK_MISSING_SCORES,
    RERANK_REQUESTS,
)

logger: Final[logging.Logger] = logging.getLogger(__name__)

#: 路由路径（router 带 ``/v1/rag`` 前缀）。
RERANK_PATH: Final[str] = "/rerank"

#: 幂等缓存条目上限（冻结文档 §1：≤1024）。
MAX_IDEMPOTENCY_ENTRIES: Final[int] = 1024

#: 指标 outcome 取值（低基数，禁止把错误文本放进标签）。
OUTCOME_OK: Final[str] = "ok"
OUTCOME_DEADLINE: Final[str] = "deadline"
OUTCOME_MODEL_ERROR: Final[str] = "model_error"
OUTCOME_INVALID: Final[str] = "invalid"
OUTCOME_PARTIAL: Final[str] = "partial"

router = APIRouter(prefix="/v1/rag", tags=["rerank"])


@dataclass(frozen=True)
class _CacheEntry:
    """一次成功响应：用于按 key 逐字节重放。"""

    fingerprint: str
    body: bytes
    partial: bool
    expires_at: float


class IdempotencyCache:
    """进程内有界幂等缓存：TTL 用 ``monotonic``，满 1024 条后淘汰最旧插入项。

    只缓存**成功**响应：把 504/503 缓存 10 分钟会让同一次重试持续失败，违背
    "瞬时失败应允许重试"的语义（Java 侧只对 502/503/504 重试）。
    """

    def __init__(
        self,
        *,
        ttl_seconds: float,
        max_entries: int = MAX_IDEMPOTENCY_ENTRIES,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._entries: OrderedDict[str, _CacheEntry] = OrderedDict()
        self._lock: Final[threading.Lock] = threading.Lock()
        self._ttl_seconds: Final[float] = max(1.0, ttl_seconds)
        self._max_entries: Final[int] = max(1, max_entries)
        self._clock: Final[Callable[[], float]] = clock

    def get(self, key: str) -> _CacheEntry | None:
        """取未过期条目；过期即删除。"""

        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            if entry.expires_at <= self._clock():
                del self._entries[key]
                return None
            return entry

    def put(self, key: str, *, fingerprint: str, body: bytes, partial: bool) -> None:
        """写入/覆盖条目，随后按上限淘汰最旧插入项。"""

        now = self._clock()
        with self._lock:
            self._prune_expired(now)
            self._entries[key] = _CacheEntry(
                fingerprint=fingerprint,
                body=body,
                partial=partial,
                expires_at=now + self._ttl_seconds,
            )
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_entries:
                self._entries.popitem(last=False)

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)

    def _prune_expired(self, now: float) -> None:
        expired = [key for key, entry in self._entries.items() if entry.expires_at <= now]
        for key in expired:
            del self._entries[key]


class RerankService:
    """rerank 的业务编排：Prompt → 网关 → 解析 → 打分 → 契约响应。"""

    def __init__(self, *, settings: Settings, gateway: JavaGatewayClient) -> None:
        self._settings: Final[Settings] = settings
        self._gateway: Final[JavaGatewayClient] = gateway
        self._cache: Final[IdempotencyCache] = IdempotencyCache(
            ttl_seconds=settings.idempotency_ttl_seconds
        )

    async def aclose(self) -> None:
        """关闭网关连接池（lifespan 退出时调用）。"""

        await self._gateway.aclose()

    def cached(self, key: str, fingerprint: str) -> _CacheEntry | None:
        """命中返回缓存条目；同 key 不同请求体抛 ``INVALID_REQUEST``(400)。"""

        entry = self._cache.get(key)
        if entry is None:
            return None
        if not hmac.compare_digest(entry.fingerprint, fingerprint):
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "同一 Idempotency-Key 的请求体不一致",
                status_code=400,
            )
        return entry

    def remember(self, key: str, *, fingerprint: str, body: bytes, partial: bool) -> None:
        self._cache.put(key, fingerprint=fingerprint, body=body, partial=partial)

    async def score(
        self,
        body: RerankRequest,
        *,
        call_id: str,
        traceparent: str | None,
    ) -> tuple[RerankResponse, tuple[str, ...]]:
        """调用网关并返回契约响应与缺失 id 列表。"""

        if len(body.candidates) > self._settings.max_candidates:
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "候选数超过服务端配置上限",
                status_code=422,
            )
        prompt = build_scoring_prompt(body.query, body.candidates)
        result = await self._gateway.call_model(
            system_prompt=prompt.system,
            user_prompt=prompt.user,
            deadline_ms=body.deadline_ms,
            call_id=call_id,
            traceparent=traceparent,
        )
        raw_scores = parse_scores(result.content)
        scoring = score_candidates(body.candidates, raw_scores)
        usage = (
            result.usage
            if result.usage is not None
            else RerankUsage(source=RerankUsageSource.UNKNOWN)
        )
        response = RerankResponse(
            scores=scoring.scores,
            model_alias=self._settings.model_alias,
            usage=usage,
        )
        return response, scoring.missing_ids


def get_rerank_service(request: Request) -> RerankService:
    """从 ``app.state.rerank_service`` 取服务；未配置属于装配错误（500 INTERNAL）。"""

    service = getattr(request.app.state, "rerank_service", None)
    if not isinstance(service, RerankService):
        msg = "rerank_service 未配置"
        raise RuntimeError(msg)
    return service


RerankServiceDep = Annotated[RerankService, Depends(get_rerank_service)]


@router.post(
    RERANK_PATH,
    response_model=RerankResponse,
    summary="对候选文档重排打分（经 Java 内部模型网关；覆盖全部输入 id）",
)
async def rerank(request: Request, body: RerankRequest, service: RerankServiceDep) -> Response:
    """处理一次 rerank 请求；错误一律 ``AppError`` + 统一信封。"""

    started = time.perf_counter()
    outcome: str = OUTCOME_MODEL_ERROR
    try:
        _require_service_token(request)
        idempotency_key = _read_idempotency_key(request)
        fingerprint = request_fingerprint(body)
        if idempotency_key is not None:
            entry = service.cached(idempotency_key, fingerprint)
            if entry is not None:
                outcome = OUTCOME_PARTIAL if entry.partial else OUTCOME_OK
                return _json_response(entry.body)

        try:
            async with asyncio.timeout(body.deadline_ms / 1000.0):
                response, missing_ids = await service.score(
                    body,
                    call_id=_call_id(fingerprint),
                    traceparent=request.headers.get("traceparent"),
                )
        except TimeoutError as exc:
            raise AppError(
                ErrorCode.DEADLINE_EXCEEDED,
                "重排超时",
                status_code=504,
                retryable=True,
            ) from exc

        payload = serialize_response(response)
        partial = bool(missing_ids)
        if partial:
            RERANK_MISSING_SCORES.inc(len(missing_ids))
            logger.warning(
                "rerank partial missing_count=%d missing_ids=%s",
                len(missing_ids),
                list(missing_ids),
            )
        if idempotency_key is not None:
            service.remember(
                idempotency_key,
                fingerprint=fingerprint,
                body=payload,
                partial=partial,
            )
        outcome = OUTCOME_PARTIAL if partial else OUTCOME_OK
        return _json_response(payload)
    except AppError as exc:
        outcome = _outcome_for_error(exc)
        raise
    finally:
        RERANK_DURATION.observe(time.perf_counter() - started)
        RERANK_REQUESTS.labels(outcome=outcome).inc()


def request_fingerprint(body: RerankRequest) -> str:
    """请求身份指纹：query/topK/candidates 的稳定 SHA-256。

    ``deadlineMs`` 是本次调用的剩余预算，不是请求身份：Java 的幂等键
    ``sha256(projectId|query|topK|candidateIds)`` 同样不含 deadline。把它排除后，
    同一次重排重试时即使 deadline 被调小也会命中缓存，而不是误判为"同 key 不同 body"。
    """

    payload = body.model_dump(by_alias=True, mode="json")
    payload.pop("deadlineMs", None)
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def serialize_response(response: RerankResponse) -> bytes:
    """固定的紧凑 UTF-8 JSON；缓存重放与首次响应共用同一份字节。"""

    payload = response.model_dump(by_alias=True, mode="json")
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _json_response(payload: bytes) -> Response:
    return Response(content=payload, status_code=200, media_type="application/json")


def _call_id(fingerprint: str) -> str:
    """每次模型调用唯一；前缀带请求指纹便于跨日志关联（不含正文）。"""

    return f"rerank:{fingerprint[:16]}:{uuid.uuid4().hex}"


def _read_idempotency_key(request: Request) -> str | None:
    raw = request.headers.get(IDEMPOTENCY_HEADER)
    if raw is None:
        return None
    try:
        return validate_idempotency_key(raw)
    except ValueError as exc:
        raise AppError(ErrorCode.INVALID_REQUEST, str(exc), status_code=400) from exc


def _require_service_token(request: Request) -> None:
    """常量时间校验服务令牌；未配置时 fail closed（除非显式测试开关）。"""

    settings: Settings = request.app.state.settings
    expected = settings.service_token
    if not expected:
        if settings.allow_insecure_internal:
            return
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务令牌未配置",
            status_code=503,
            retryable=False,
        )
    provided = _bearer_value(request.headers.get("authorization"))
    if provided is None or not hmac.compare_digest(
        provided.encode("utf-8"), expected.encode("utf-8")
    ):
        raise AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "内部服务鉴权失败",
            status_code=401,
            retryable=False,
        )


def _bearer_value(header: str | None) -> str | None:
    if not header:
        return None
    scheme, separator, value = header.partition(" ")
    if not separator or scheme.lower() != "bearer":
        return None
    token = value.strip()
    return token or None


def _outcome_for_error(exc: AppError) -> str:
    if exc.code == ErrorCode.DEADLINE_EXCEEDED:
        return OUTCOME_DEADLINE
    if exc.code == ErrorCode.INVALID_REQUEST:
        return OUTCOME_INVALID
    return OUTCOME_MODEL_ERROR


__all__ = [
    "MAX_IDEMPOTENCY_ENTRIES",
    "RERANK_PATH",
    "IdempotencyCache",
    "RerankService",
    "get_rerank_service",
    "request_fingerprint",
    "router",
    "serialize_response",
]
