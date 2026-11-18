"""rerank 与 decide 共用的进程内、已完成成功响应缓存。

缓存按 key 保存成功响应的原始 JSON 字节；命中同一请求指纹时逐字节重放。TTL 用单调时钟，
容量有界（≤1024，满后淘汰最旧插入项）。缓存只在上游调用完成后写入，不为在途请求预留 key；
因此并发到达的相同请求都可能 miss 并独立调用上游。缓存也不跨进程共享，重启后为空。
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
    """一次已完成的成功响应：用于后续缓存命中时按 key 逐字节重放。"""

    fingerprint: str
    body: bytes
    partial: bool
    expires_at: float


class IdempotencyCache:
    """进程内有界的已完成响应缓存；TTL 用 ``monotonic``，满后淘汰最旧插入项。

    只缓存**成功**响应：把 504/503 缓存 10 分钟会让同一次重试持续失败，违背
    "瞬时失败应允许重试"的语义（Java 侧只对 502/503/504 重试）。

    锁只保护单次 ``get``/``put`` 的存储访问，不覆盖上游调用，也不实现 single-flight；两个
    同 key 请求若都在首个成功结果写入前查询缓存，都会得到 miss 并继续各自的上游调用。
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
