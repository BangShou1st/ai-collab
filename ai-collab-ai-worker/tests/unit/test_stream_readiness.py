"""StreamConsumer 就绪状态与 Redis socket 超时单元测试（不依赖 Docker）。"""

from __future__ import annotations

import asyncio
import base64
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr
from redis.exceptions import ConnectionError as RedisConnectionError
from redis.exceptions import ResponseError
from redis.exceptions import TimeoutError as RedisTimeoutError

from ai_collab_worker.api import health
from ai_collab_worker.api.health import add_readiness_check
from ai_collab_worker.clients.java_internal import (
    ClaimResponse,
    HeartbeatResponse,
)
from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.tasks.stream import (
    DEFAULT_BLOCK_MS,
    DEFAULT_SOCKET_CONNECT_TIMEOUT_SECONDS,
    DEFAULT_SOCKET_TIMEOUT_SECONDS,
    StreamConsumer,
    TaskContext,
)

_TEST_KEY = Ed25519PrivateKey.from_private_bytes(bytes.fromhex("44" * 32))
TEST_PUBLIC_KEYS = "k1=" + base64.b64encode(
    _TEST_KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode("ascii")


class _StubCallbacks:
    async def claim(
        self, task_id: str, *, task_type: str, epoch: int, worker_id: str, bearer_token: str
    ) -> ClaimResponse:
        return ClaimResponse.model_validate({"leaseToken": "lease-1", "token": "tok-1"})

    async def heartbeat(
        self, task_id: str, *, epoch: int, lease_token: str, bearer_token: str
    ) -> HeartbeatResponse:
        return HeartbeatResponse.model_validate({"cancelRequested": False})

    async def result(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        payload: Mapping[str, object],
        bearer_token: str,
    ) -> None:
        return None

    async def fail(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        error_code: str,
        retryable: bool,
        bearer_token: str,
    ) -> None:
        return None


class _FakeStreamRedis:
    """可控的假异步 Redis 客户端，用于模拟冷启动、空闲阻塞轮询与断连恢复。"""

    def __init__(self) -> None:
        self.group_error: BaseException | None = None
        self.read_error: BaseException | None = None
        self.group_calls = 0
        self.read_calls = 0
        self.read_started = asyncio.Event()
        self.release_read = asyncio.Event()
        self.wait_for_release = False

    async def xgroup_create(
        self, _name: str, _groupname: str, *, id: str = "$", mkstream: bool = False
    ) -> bool:
        self.group_calls += 1
        if self.group_error is not None:
            raise self.group_error
        return True

    async def xreadgroup(
        self,
        _groupname: str,
        _consumername: str,
        _streams: Mapping[str, str],
        *,
        count: int | None = None,
        block: int | None = None,
    ) -> list[Any]:
        self.read_calls += 1
        self.read_started.set()
        if self.wait_for_release:
            await self.release_read.wait()
        if self.read_error is not None:
            raise self.read_error
        await asyncio.sleep(0)
        return []

    async def xautoclaim(
        self,
        _name: str,
        _groupname: str,
        _consumername: str,
        *,
        min_idle_time: int,
        start_id: str = "0-0",
        count: int | None = None,
    ) -> list[Any]:
        return [b"0-0", []]

    async def xack(self, _name: str, _groupname: str, _id: str) -> int:
        return 1


async def _noop_handler(_context: TaskContext) -> Mapping[str, object]:
    return {}


async def _wait_until(predicate: Any, *, timeout: float = 2.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while not predicate():
        if asyncio.get_running_loop().time() >= deadline:
            msg = "条件在超时前未满足"
            raise AssertionError(msg)
        await asyncio.sleep(0.01)


def test_create_app_redis_pool_socket_timeout_exceeds_block_ms(tmp_path: Path) -> None:
    app = create_app(
        Settings(
            prompts_dir=tmp_path / "prompts",
            ed25519_public_keys=TEST_PUBLIC_KEYS,
            redis_url=SecretStr("redis://localhost:6379/0"),
        )
    )
    try:
        redis = app.state.redis
        assert redis is not None
        pool_kwargs = redis.connection_pool.connection_kwargs
        assert pool_kwargs["socket_timeout"] > DEFAULT_BLOCK_MS / 1000.0
        assert pool_kwargs["socket_timeout"] == DEFAULT_SOCKET_TIMEOUT_SECONDS
        assert pool_kwargs["socket_connect_timeout"] == DEFAULT_SOCKET_CONNECT_TIMEOUT_SECONDS
    finally:

        async def _close() -> None:
            if app.state.redis is not None:
                await app.state.redis.aclose()
            await app.state.java_internal.aclose()

        asyncio.run(_close())


def test_consumer_becomes_ready_while_xreadgroup_is_blocking_and_stays_ready_when_idle() -> None:
    async def main() -> None:
        redis = _FakeStreamRedis()
        redis.wait_for_release = True
        consumer = StreamConsumer(
            redis,  # type: ignore[arg-type]
            _StubCallbacks(),
            {"T": _noop_handler},
            worker_id="w-idle",
            autoclaim_interval=3600,
            backoff_initial=0.01,
            backoff_max=0.02,
        )
        assert consumer.readiness_failure() == "redis_unavailable"

        consumer.start()
        try:
            await asyncio.wait_for(redis.read_started.wait(), timeout=2.0)
            # 首次 XREADGROUP BLOCK 尚未返回时，_ensure_group() 已成功，探针应已就绪。
            assert consumer.ready is True
            assert consumer.readiness_failure() is None

            # 放开阻塞并完成多轮空闲轮询，readiness_failure() 持续保持 None。
            redis.wait_for_release = False
            redis.release_read.set()
            await _wait_until(lambda: redis.read_calls >= 3)
            assert consumer.readiness_failure() is None
            assert redis.group_calls == 1
        finally:
            await consumer.stop(timeout=1.0)
        assert consumer.readiness_failure() == "redis_unavailable"

    asyncio.run(main())


def test_consumer_recovers_readiness_after_disconnect_and_loading_error() -> None:
    async def main() -> None:
        redis = _FakeStreamRedis()
        consumer = StreamConsumer(
            redis,  # type: ignore[arg-type]
            _StubCallbacks(),
            {"T": _noop_handler},
            worker_id="w-recover",
            autoclaim_interval=3600,
            backoff_initial=0.01,
            backoff_max=0.02,
        )
        consumer.start()
        try:
            await _wait_until(lambda: consumer.readiness_failure() is None)

            # 断连 / 超时：立即降级为 redis_unavailable。
            redis.read_error = RedisTimeoutError("Timeout reading from redis:6379")
            await _wait_until(lambda: consumer.readiness_failure() == "redis_unavailable")

            # Redis 处于 LOADING 或连接不可用期间：不抖动恢复就绪。
            redis.read_error = None
            redis.group_error = ResponseError("LOADING Redis is loading the dataset in memory")
            before_calls = redis.group_calls
            await _wait_until(lambda: redis.group_calls >= before_calls + 2)
            assert consumer.readiness_failure() == "redis_unavailable"

            # 连接彻底断开后再恢复为已存在消费组（BUSYGROUP 幂等）：立刻恢复就绪。
            redis.group_error = RedisConnectionError("lost")
            before_calls = redis.group_calls
            await _wait_until(lambda: redis.group_calls >= before_calls + 1)
            assert consumer.readiness_failure() == "redis_unavailable"

            redis.group_error = ResponseError("BUSYGROUP Consumer Group name already exists")
            redis.wait_for_release = True
            await _wait_until(lambda: consumer.readiness_failure() is None)
            assert consumer.ready is True
        finally:
            redis.release_read.set()
            await consumer.stop(timeout=1.0)

    asyncio.run(main())


def test_readyz_endpoint_reflects_consumer_readiness(prompts_dir: Path) -> None:
    redis = _FakeStreamRedis()
    consumer = StreamConsumer(
        redis,  # type: ignore[arg-type]
        _StubCallbacks(),
        {"T": _noop_handler},
        worker_id="w-http",
        autoclaim_interval=3600,
        backoff_initial=0.01,
        backoff_max=0.02,
    )
    store = PromptStore(prompts_dir)
    app = FastAPI()
    app.include_router(health.router)
    add_readiness_check(app, "prompts", store.readiness_failure)
    add_readiness_check(app, "redis", consumer.readiness_failure)

    with TestClient(app) as client:
        assert client.get("/readyz").status_code == 503
        consumer._ready = True
        ok = client.get("/readyz")
        assert ok.status_code == 200
        assert ok.json()["checks"] == {"prompts": "ok", "redis": "ok"}

        consumer._mark_unavailable(RedisConnectionError("down"))
        not_ready = client.get("/readyz")
        assert not_ready.status_code == 503
        assert not_ready.json()["checks"] == {"prompts": "ok", "redis": "failed"}
        assert not_ready.json()["reasons"] == {"redis": "redis_unavailable"}
