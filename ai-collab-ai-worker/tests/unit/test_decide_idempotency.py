"""decide 幂等键/指纹与共享缓存的有界性（unit）。"""

from __future__ import annotations

from ai_collab_worker.api.decide import (
    idempotency_key,
    request_fingerprint,
)
from ai_collab_worker.api.idempotency import MAX_IDEMPOTENCY_ENTRIES, IdempotencyCache
from ai_collab_worker.contract.agent_decide import AgentDecideRequest


class FakeClock:
    def __init__(self, now: float = 100.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _request(**overrides: object) -> AgentDecideRequest:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "runId": "run-1",
        "attempt": 2,
        "turnNo": 5,
        "snapshotHash": "sha256:abc",
        "scenario": {"code": "PROJECT_RESEARCH", "category": "SCENARIO"},
        "goal": "研究项目架构",
        "locale": "zh-CN",
        "context": {"facts": [], "messages": [], "observations": []},
        "tools": [
            {
                "name": "search_project_knowledge",
                "description": "检索",
                "inputSchema": {"type": "object"},
                "risk": "READ_ONLY",
            }
        ],
        "policy": {"allowWrite": False, "allowExternal": False},
        "budget": {"maxInputTokens": 24000, "maxOutputTokens": 1000, "deadlineMs": 30000},
    }
    payload.update(overrides)
    return AgentDecideRequest.model_validate(payload)


def test_idempotency_key_is_tuple_of_four_fields() -> None:
    key = idempotency_key(_request())
    changed = idempotency_key(_request(runId="run-2"))

    assert key != changed
    assert idempotency_key(_request(attempt=3)) != key
    assert idempotency_key(_request(turnNo=6)) != key
    assert idempotency_key(_request(snapshotHash="sha256:def")) != key
    # 不含 deadline：预算是身份之外的字段。
    assert (
        idempotency_key(
            _request(budget={**_request().budget.model_dump(by_alias=True), "deadlineMs": 1})
        )
        == key
    )


def test_fingerprint_is_stable_and_ignores_deadline() -> None:
    first = request_fingerprint(_request())
    second = request_fingerprint(_request())
    shorter = request_fingerprint(
        _request(
            budget={
                "maxInputTokens": 24000,
                "maxOutputTokens": 1000,
                "deadlineMs": 1234,
            }
        )
    )

    assert first == second == shorter
    assert len(first) == 64


def test_fingerprint_changes_with_body_identity() -> None:
    base = request_fingerprint(_request())

    assert request_fingerprint(_request(goal="另一个目标")) != base
    assert request_fingerprint(_request(locale="en-US")) != base
    assert request_fingerprint(_request(attempt=3)) != base
    assert (
        request_fingerprint(_request(policy={"allowWrite": True, "allowExternal": False})) != base
    )


def test_cache_hit_ttl_and_bound() -> None:
    clock = FakeClock()
    cache = IdempotencyCache(ttl_seconds=600, max_entries=2, clock=clock)
    cache.put("k1", fingerprint="fp1", body=b"1")
    cache.put("k2", fingerprint="fp2", body=b"2")

    assert cache.get("k1") is not None
    clock.advance(600)
    assert cache.get("k1") is None
    assert len(cache) == 1

    cache.put("k3", fingerprint="fp3", body=b"3")
    cache.put("k4", fingerprint="fp4", body=b"4")
    assert len(cache) == 2
    assert cache.get("k2") is None
    assert cache.get("k3") is not None
    assert cache.get("k4") is not None


def test_cache_default_bound_matches_frozen_contract() -> None:
    assert MAX_IDEMPOTENCY_ENTRIES == 1024
