"""凭据租借：``Lease`` 模型与进程内缓存（P1 契约 §2.3 / §2.5）。

``Lease`` 的密钥字段只以 :class:`pydantic.SecretStr` 存在：``repr``/``str``/``model_dump`` 都只
给出掩码；``pickle``（``__reduce__``/``__reduce_ex__``/``__getstate__``）与
``model_dump_json`` 直接抛错，``json.dumps(lease)`` 天然抛 ``TypeError``。这样 lease 永远进不了
LangGraph checkpoint、缓存文件或消息队列。

缓存键为 ``(purpose, userId, projectId, taskId|runId)``，在 ``expiresAt − 30s`` 失效；只在内存，
不写盘。缓存命中期间密钥登记在 :data:`~ai_collab_worker.security.SECRETS`（日志子串脱敏），
过期淘汰时注销。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Final, NoReturn

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator
from pydantic.alias_generators import to_camel

from ai_collab_worker.security.redaction import SECRETS, SecretRegistry

#: 缓存提前失效的余量（契约：expiresAt − 30s）。
LEASE_EXPIRY_MARGIN: Final[timedelta] = timedelta(seconds=30)


class LeasePurpose(StrEnum):
    """租借用途（契约 §2.1 枚举）。"""

    KNOWLEDGE_CHAT = "KNOWLEDGE_CHAT"
    AGENT = "AGENT"
    PLANNING = "PLANNING"
    QUERY_PLAN = "QUERY_PLAN"
    MEMORY_EXTRACTION = "MEMORY_EXTRACTION"
    EMBEDDING = "EMBEDDING"


class LeaseProtocol(StrEnum):
    """厂商协议（契约 §2.3）。"""

    OPENAI_CHAT = "OPENAI_CHAT"
    ANTHROPIC_MESSAGES = "ANTHROPIC_MESSAGES"
    GEMINI_GENERATE_CONTENT = "GEMINI_GENERATE_CONTENT"
    OPENAI_EMBEDDINGS = "OPENAI_EMBEDDINGS"


class LeaseRequest(BaseModel):
    """``POST /internal/ai/credentials/lease`` 请求体。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    purpose: LeasePurpose
    user_id: uuid.UUID
    project_id: uuid.UUID
    task_id: uuid.UUID | None = None
    run_id: uuid.UUID | None = None

    def cache_key(self) -> tuple[str, str, str, str]:
        scope = self.task_id or self.run_id
        return (self.purpose.value, str(self.user_id), str(self.project_id), str(scope or "-"))


class LeaseParams(BaseModel):
    """lease 附带的默认生成参数；未知字段忽略（Java 侧可能扩展）。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    temperature: float | None = Field(default=None, ge=0.0, le=2.0)
    max_output_tokens: int | None = Field(default=None, ge=1)


class Lease(BaseModel):
    """Java 下发的短期凭据。密钥只在 :meth:`reveal_key` 一处取明文。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="ignore",
        hide_input_in_errors=True,
    )

    lease_id: uuid.UUID
    protocol: LeaseProtocol
    provider_type: str = Field(min_length=1, max_length=64)
    base_url: str = Field(min_length=1, max_length=2048)
    api_path: str | None = Field(default=None, max_length=512)
    model: str = Field(min_length=1, max_length=256)
    api_key: SecretStr = Field(repr=False)
    params: LeaseParams = Field(default_factory=LeaseParams)
    capabilities: frozenset[str] = frozenset()
    expires_at: datetime

    @field_validator("expires_at")
    @classmethod
    def _require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value

    @field_validator("base_url")
    @classmethod
    def _require_http_url(cls, value: str) -> str:
        if not value.startswith(("https://", "http://")):
            msg = "baseUrl 必须是 http(s) URL"
            raise ValueError(msg)
        return value.rstrip("/")

    def reveal_key(self) -> str:
        """唯一取明文的入口：只允许在构造厂商客户端时调用。"""

        return self.api_key.get_secret_value()

    def has_capability(self, name: str) -> bool:
        return name in self.capabilities

    def is_fresh(self, *, now: datetime | None = None) -> bool:
        current = now or datetime.now(UTC)
        return current < self.expires_at - LEASE_EXPIRY_MARGIN

    # ---- 禁止序列化 -------------------------------------------------------

    def __reduce__(self) -> NoReturn:
        _refuse()

    def __reduce_ex__(self, protocol: Any) -> NoReturn:
        _refuse()

    def __getstate__(self) -> NoReturn:
        _refuse()

    def __copy__(self) -> NoReturn:
        _refuse()

    def __deepcopy__(self, memo: dict[int, Any] | None = None) -> NoReturn:
        _refuse()

    def model_dump_json(self, **_kwargs: Any) -> NoReturn:
        _refuse()


def _refuse() -> NoReturn:
    msg = "Lease 含凭据，禁止序列化/复制"
    raise TypeError(msg)


LeaseFetcher = Callable[[LeaseRequest, str], Awaitable[Lease]]


class LeaseCache:
    """进程内 lease 缓存：键 ``(purpose, userId, projectId, taskId|runId)``。

    同一键并发获取只发一次请求（per-key 锁）。``bearer_token`` 只用于本次租借调用，不进键、
    不缓存。
    """

    def __init__(
        self,
        fetcher: LeaseFetcher,
        *,
        registry: SecretRegistry = SECRETS,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._fetcher = fetcher
        self._registry = registry
        self._clock = clock
        self._entries: dict[tuple[str, str, str, str], Lease] = {}
        self._locks: dict[tuple[str, str, str, str], asyncio.Lock] = {}

    async def get(self, request: LeaseRequest, *, bearer_token: str) -> Lease:
        key = request.cache_key()
        self._evict_expired()
        cached = self._entries.get(key)
        if cached is not None and cached.is_fresh(now=self._clock()):
            return cached
        lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            cached = self._entries.get(key)
            if cached is not None and cached.is_fresh(now=self._clock()):
                return cached
            if cached is not None:
                self._drop(key)
            lease = await self._fetcher(request, bearer_token)
            self._registry.add(lease.reveal_key())
            if lease.is_fresh(now=self._clock()):
                self._entries[key] = lease
            else:
                # 剩余有效期不足 30s：本次仍可使用，但不进缓存；密钥登记由调用方生命周期内保持。
                pass
            return lease

    def invalidate(self, request: LeaseRequest) -> None:
        self._drop(request.cache_key())

    def clear(self) -> None:
        for key in list(self._entries):
            self._drop(key)

    def __len__(self) -> int:
        return len(self._entries)

    def __reduce__(self) -> NoReturn:
        _refuse()

    def _evict_expired(self) -> None:
        now = self._clock()
        for key, lease in list(self._entries.items()):
            if not lease.is_fresh(now=now):
                self._drop(key)

    def _drop(self, key: tuple[str, str, str, str]) -> None:
        lease = self._entries.pop(key, None)
        if lease is not None:
            self._registry.discard(lease.reveal_key())


__all__ = [
    "LEASE_EXPIRY_MARGIN",
    "Lease",
    "LeaseCache",
    "LeaseFetcher",
    "LeaseParams",
    "LeaseProtocol",
    "LeasePurpose",
    "LeaseRequest",
]
