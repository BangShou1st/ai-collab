"""进程内有界幂等缓存（rerank 与 decide 共用）。

口径（设计 §7.1）：服务端按 key 缓存 10 分钟结果，**只缓存成功**响应；TTL 用单调时钟，
容量有界（≤1024，满后淘汰最旧插入项）。条目保存渲染后的响应字节，命中时**逐字节重放**，
保证同一次调用重试不会因为重新序列化产生差异。
"""

from __future__ import annotations

import threading
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from typing import Final

#: 幂等缓存条目上限（冻结文档 §1/§2：≤1024）。
MAX_IDEMPOTENCY_ENTRIES: Final[int] = 1024


@dataclass(frozen=True)
class CacheEntry:
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
        self._entries: OrderedDict[str, CacheEntry] = OrderedDict()
        self._lock: Final[threading.Lock] = threading.Lock()
        self._ttl_seconds: Final[float] = max(1.0, ttl_seconds)
        self._max_entries: Final[int] = max(1, max_entries)
        self._clock: Final[Callable[[], float]] = clock

    def get(self, key: str) -> CacheEntry | None:
        """取未过期条目；过期即删除。"""

        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            if entry.expires_at <= self._clock():
                del self._entries[key]
                return None
            return entry

    def put(self, key: str, *, fingerprint: str, body: bytes, partial: bool = False) -> None:
        """写入/覆盖条目，随后按上限淘汰最旧插入项。"""

        now = self._clock()
        with self._lock:
            self._prune_expired(now)
            self._entries[key] = CacheEntry(
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


__all__ = ["MAX_IDEMPOTENCY_ENTRIES", "CacheEntry", "IdempotencyCache"]
