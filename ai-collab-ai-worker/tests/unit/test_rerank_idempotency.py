"""进程内幂等缓存（unit）：TTL 用单调时钟、容量有界、过期条目不可读。"""

from __future__ import annotations

from ai_collab_worker.api.rerank import (
    MAX_IDEMPOTENCY_ENTRIES,
    IdempotencyCache,
    request_fingerprint,
)
from ai_collab_worker.contract.rerank import RerankRequest


class FakeClock:
    def __init__(self, now: float = 100.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_put_then_get_returns_same_bytes_and_fingerprint() -> None:
    clock = FakeClock()
    cache = IdempotencyCache(ttl_seconds=600, clock=clock)

    cache.put("k1", fingerprint="fp1", body=b'{"ok":true}', partial=False)
    entry = cache.get("k1")

    assert entry is not None
    assert entry.fingerprint == "fp1"
    assert entry.body == b'{"ok":true}'
    assert entry.partial is False


def test_entry_expires_after_ttl() -> None:
    clock = FakeClock()
    cache = IdempotencyCache(ttl_seconds=600, clock=clock)
    cache.put("k1", fingerprint="fp1", body=b"{}", partial=False)

    clock.advance(599)
    assert cache.get("k1") is not None

    clock.advance(1)
    assert cache.get("k1") is None
    assert len(cache) == 0


def test_cache_is_bounded_and_evicts_oldest_insertion() -> None:
    clock = FakeClock()
    cache = IdempotencyCache(ttl_seconds=600, max_entries=2, clock=clock)

    cache.put("k1", fingerprint="fp1", body=b"1", partial=False)
    cache.put("k2", fingerprint="fp2", body=b"2", partial=False)
    cache.put("k3", fingerprint="fp3", body=b"3", partial=False)

    assert len(cache) == 2
    assert cache.get("k1") is None
    assert cache.get("k2") is not None
    assert cache.get("k3") is not None


def test_default_bound_matches_frozen_contract() -> None:
    assert MAX_IDEMPOTENCY_ENTRIES == 1024


def _request(deadline_ms: int = 3000, query: str = "q") -> RerankRequest:
    return RerankRequest(
        query=query,
        candidates=[{"id": "c1", "text": "正文"}],
        deadline_ms=deadline_ms,
    )


def test_fingerprint_is_stable_and_ignores_deadline() -> None:
    first = request_fingerprint(_request(deadline_ms=1))
    second = request_fingerprint(_request(deadline_ms=30000))

    assert first == second
    assert len(first) == 64


def test_fingerprint_changes_with_identity_fields() -> None:
    base = request_fingerprint(_request())

    assert request_fingerprint(_request(query="other")) != base
    assert (
        request_fingerprint(RerankRequest(query="q", candidates=[{"id": "c1", "text": "另一段"}]))
        != base
    )
    assert (
        request_fingerprint(
            RerankRequest(query="q", candidates=[{"id": "c1", "text": "正文"}], top_k=3)
        )
        != base
    )
