"""Redis Stream 消费框架与记忆抽取任务（P1 契约 §4.3、§4.5、§6）。

Redis 用 testcontainers 起**一个**真实的 ``redis:7-alpine``（模块级共享，每个用例独立 stream key）；
Java 回调用进程内假服务 :class:`FakeJava`，作为 httpx ``MockTransport`` 挂在真实的
:class:`JavaInternalClient` 下面，因此回调的路径、请求体与错误分类也一起被验证。假服务的 claim
实现**条件更新**语义：``status='QUEUED' AND epoch=:epoch`` 才成功，否则 409。

覆盖：XACK；claim 409 / 旧 epoch 时丢弃并 XACK；消费者崩溃后由 XAUTOCLAIM 接管；认领后被杀 →
补偿重派 → 旧消息 409 丢弃、新消息执行；cancel；heartbeat 409 放弃且不再回调；并发上限；优雅停机；
BUSYGROUP 幂等；Redis 不可达时 ``/readyz`` 失败；记忆抽取任务经 Stream 的全链路。
记忆抽取处理器另有不依赖 Redis 的直接用例。
"""

from __future__ import annotations

import asyncio
import base64
import json
import re
import uuid
from collections.abc import Awaitable, Callable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import httpx2
import pytest
from redis.asyncio import Redis
from testcontainers.community.redis import RedisContainer

from ai_collab_worker.clients.java_internal import JavaInternalClient
from ai_collab_worker.cognition.memory.parser import compute_dedupe_key
from ai_collab_worker.config import Settings
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import ModelKernel
from ai_collab_worker.models.lease import LeaseCache
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.tasks.memory import MemoryExtractionHandler
from ai_collab_worker.tasks.stream import (
    GROUP_NAME,
    StreamConsumer,
    TaskContext,
    TaskFailedError,
    TaskHandler,
)

PROMPTS_DIR = Path(__file__).resolve().parents[2] / "prompts"
JAVA_URL = "http://java.test"
USER_ID = "11111111-1111-4111-8111-111111111111"
PROJECT_ID = "22222222-2222-4222-8222-222222222222"
TERMINAL = {"SUCCEEDED", "FAILED", "FAILED_RETRYABLE"}


def _task_token(task_id: str, epoch: int) -> str:
    """任务令牌的形状（不验签：Python 只 peek ``uid/pid``，鉴权在 Java）。"""

    payload = {"uid": USER_ID, "pid": PROJECT_ID, "tid": task_id, "ep": epoch}
    raw = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
    return f"eyJhbGciOiJFZERTQSJ9.{raw}.sig"


# --------------------------------------------------------------------------- #
# 进程内假 Java（条件更新语义）
# --------------------------------------------------------------------------- #


@dataclass
class FakeTask:
    task_id: str
    status: str = "QUEUED"
    epoch: int = 0
    lease_token: str | None = None
    claimed_by: str | None = None
    cancel: bool = False
    heartbeat_409: bool = False
    snapshot: dict[str, Any] | None = None
    claim_calls: int = 0
    heartbeat_calls: int = 0
    statuses: list[str] = field(default_factory=list)
    results: list[tuple[int, dict[str, Any]]] = field(default_factory=list)
    fails: list[tuple[int, str, bool]] = field(default_factory=list)

    def set(self, status: str) -> None:
        self.status = status
        self.statuses.append(status)


class FakeJava:
    """``/internal/ai/**`` 的进程内实现；单事件循环内天然原子。"""

    _TASK = re.compile(
        r"^/internal/ai/tasks/([0-9a-f-]{36})/(claim|snapshot|heartbeat|result|fail)$"
    )

    def __init__(self) -> None:
        self.tasks: dict[str, FakeTask] = {}
        self.lease_requests: list[dict[str, Any]] = []
        self.usage_items: list[dict[str, Any]] = []
        self.provider: str = "https://provider.test"

    def add(self, **kwargs: Any) -> FakeTask:
        task = FakeTask(task_id=str(uuid.uuid4()), **kwargs)
        self.tasks[task.task_id] = task
        return task

    def client(self) -> JavaInternalClient:
        return JavaInternalClient(base_url=JAVA_URL, transport=httpx.MockTransport(self.handle))

    async def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        body = json.loads(request.content) if request.content else {}
        if path == "/internal/ai/credentials/lease":
            self.lease_requests.append(body)
            return httpx.Response(200, json=self._lease())
        if path == "/internal/ai/usage":
            self.usage_items.extend(body["items"])
            return httpx.Response(
                200,
                json={
                    "results": [{"callId": i["callId"], "accepted": True} for i in body["items"]]
                },
            )
        match = self._TASK.match(path)
        if match is None:
            return httpx.Response(404)
        task = self.tasks.get(match.group(1))
        if task is None:
            return httpx.Response(404)
        return getattr(self, f"_{match.group(2)}")(task, body)

    # ---- 任务回调 -----------------------------------------------------------

    def _claim(self, task: FakeTask, body: dict[str, Any]) -> httpx.Response:
        task.claim_calls += 1
        if task.status != "QUEUED" or task.epoch != body["epoch"]:
            return httpx.Response(409, json={"code": "TASK_CLAIM_CONFLICT"})
        task.set("LEASED")
        task.lease_token = uuid.uuid4().hex
        task.claimed_by = body["workerId"]
        return httpx.Response(
            200,
            json={
                "leaseToken": task.lease_token,
                "leaseExpiresAt": _later(60),
                "token": _task_token(task.task_id, task.epoch),
            },
        )

    def _fenced(self, task: FakeTask, body: dict[str, Any]) -> bool:
        return body.get("epoch") != task.epoch or body.get("leaseToken") != task.lease_token

    def _heartbeat(self, task: FakeTask, body: dict[str, Any]) -> httpx.Response:
        task.heartbeat_calls += 1
        if task.heartbeat_409 or self._fenced(task, body) or task.status in TERMINAL:
            return httpx.Response(409, json={"code": "TASK_EPOCH_STALE"})
        if task.status == "LEASED":
            task.set("RUNNING")
        return httpx.Response(
            200,
            json={
                "leaseExpiresAt": _later(60),
                "cancelRequested": task.cancel,
                "token": _task_token(task.task_id, task.epoch),
            },
        )

    def _snapshot(self, task: FakeTask, _body: dict[str, Any]) -> httpx.Response:
        return httpx.Response(200, json=task.snapshot or {})

    def _result(self, task: FakeTask, body: dict[str, Any]) -> httpx.Response:
        if self._fenced(task, body):
            return httpx.Response(409, json={"code": "TASK_EPOCH_STALE"})
        if task.status not in TERMINAL:
            task.results.append((body["epoch"], body["payload"]))
            task.set("SUCCEEDED")
        return httpx.Response(200, json={})

    def _fail(self, task: FakeTask, body: dict[str, Any]) -> httpx.Response:
        if self._fenced(task, body):
            return httpx.Response(409, json={"code": "TASK_EPOCH_STALE"})
        if task.status not in TERMINAL:
            task.fails.append((body["epoch"], body["errorCode"], body["retryable"]))
            task.set("FAILED_RETRYABLE" if body["retryable"] else "FAILED")
        return httpx.Response(200, json={})

    # ---- 补偿扫描（契约 §4.4 规则 2 的最小模拟）-----------------------------

    def expire_and_requeue(self, task: FakeTask) -> None:
        task.epoch += 1
        task.lease_token = None
        task.claimed_by = None
        task.set("QUEUED")

    def _lease(self) -> dict[str, Any]:
        return {
            "leaseId": str(uuid.uuid4()),
            "protocol": "OPENAI_CHAT",
            "providerType": "OLLAMA",
            "baseUrl": self.provider,
            "apiPath": "/v1/chat/completions",
            "model": "memory-model",
            "apiKey": "sk-stream-test",
            "expiresAt": _later(300),
        }


def _later(seconds: int) -> str:
    return (datetime.now(UTC) + timedelta(seconds=seconds)).isoformat()


# --------------------------------------------------------------------------- #
# Redis 容器与工具
# --------------------------------------------------------------------------- #


@pytest.fixture(scope="module")
def redis_endpoint() -> Iterator[tuple[str, int]]:
    """整个模块只起一个 Redis 7 容器；用例之间靠独立 stream key 隔离。"""

    with RedisContainer("redis:7-alpine") as container:
        host = container.get_container_host_ip()
        # Windows 上 ``localhost`` 先解析到 ::1，Docker Desktop 的 IPv6 端口转发会挂到连接超时；
        # 回环地址固定走 IPv4。
        if host == "localhost":
            host = "127.0.0.1"
        yield host, int(container.get_exposed_port(6379))


@dataclass
class Env:
    redis: Redis
    java: FakeJava
    stream: str
    clients: list[JavaInternalClient] = field(default_factory=list)
    consumers: list[StreamConsumer] = field(default_factory=list)

    def consumer(
        self, handlers: Mapping[str, TaskHandler], *, worker_id: str, **kwargs: Any
    ) -> StreamConsumer:
        client = self.java.client()
        self.clients.append(client)
        options: dict[str, Any] = {
            "concurrency": 4,
            "block_ms": 100,
            "heartbeat_interval": 0.05,
            "autoclaim_interval": 3600,
            "autoclaim_min_idle_ms": 200,
            "shutdown_timeout": 5,
            "backoff_initial": 0.05,
            "backoff_max": 0.2,
        }
        options.update(kwargs)
        consumer = StreamConsumer(
            self.redis, client, handlers, worker_id=worker_id, stream=self.stream, **options
        )
        self.consumers.append(consumer)
        return consumer

    async def ensure_group(self) -> None:
        """Java 侧也会幂等建组（id=0：不丢建组前已写入的消息）。"""

        try:
            await self.redis.xgroup_create(self.stream, GROUP_NAME, id="0", mkstream=True)
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def publish(
        self, task: FakeTask, *, task_type: str = "T", epoch: int | None = None
    ) -> str:
        ep = task.epoch if epoch is None else epoch
        message_id = await self.redis.xadd(
            self.stream,
            {
                "taskType": task_type,
                "taskId": task.task_id,
                "epoch": str(ep),
                "token": _task_token(task.task_id, ep),
            },
            maxlen=10000,
            approximate=True,
        )
        return str(message_id)

    async def pending(self) -> int:
        info = await self.redis.xpending(self.stream, GROUP_NAME)
        return int(info["pending"])

    async def close(self) -> None:
        for consumer in self.consumers:
            await consumer.stop(timeout=1)
        for client in self.clients:
            await client.aclose()
        await self.redis.delete(self.stream)
        await self.redis.aclose()


def run_env(
    endpoint: tuple[str, int], body: Callable[[Env], Awaitable[None]], *, timeout: float = 30
) -> None:
    async def main() -> None:
        host, port = endpoint
        env = Env(
            redis=Redis(host=host, port=port, decode_responses=True),
            java=FakeJava(),
            stream=f"test:ai:tasks:{uuid.uuid4().hex}",
        )
        try:
            await asyncio.wait_for(body(env), timeout)
        finally:
            await env.close()

    asyncio.run(main())


async def until(predicate: Callable[[], Awaitable[bool] | bool], *, timeout: float = 10) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        value = predicate()
        if not isinstance(value, bool):
            value = await value
        if value:
            return
        if asyncio.get_running_loop().time() > deadline:
            msg = "条件在超时前未满足"
            raise AssertionError(msg)
        await asyncio.sleep(0.02)


def _sleeping_handler(seconds: float, seen: list[TaskContext] | None = None) -> TaskHandler:
    async def handler(context: TaskContext) -> Mapping[str, object]:
        if seen is not None:
            seen.append(context)
        await asyncio.sleep(seconds)
        return {"done": context.task_id}

    return handler


# --------------------------------------------------------------------------- #
# Stream 消费语义
# --------------------------------------------------------------------------- #


def test_success_reports_result_and_acks(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()
        seen: list[TaskContext] = []
        consumer = env.consumer({"T": _sleeping_handler(0.2, seen)}, worker_id="w-1")
        consumer.start()
        await env.publish(task)

        await until(lambda: task.status == "SUCCEEDED")
        await until(lambda: env.pending() if False else _zero(env))

        assert task.claimed_by == "w-1"
        assert task.statuses[:2] == ["LEASED", "RUNNING"]  # 首次 heartbeat 推进到 RUNNING
        assert task.heartbeat_calls >= 1
        assert task.results == [(0, {"done": task.task_id})]
        # heartbeat 换发的令牌被后续回调使用。
        assert seen[0].claims()["tid"] == task.task_id
        assert consumer.readiness_failure() is None

    run_env(redis_endpoint, body)


async def _zero(env: Env) -> bool:
    return await env.pending() == 0


@pytest.mark.parametrize(
    ("status", "epoch", "message_epoch"),
    [("LEASED", 0, 0), ("SUCCEEDED", 0, 0), ("QUEUED", 2, 1)],
    ids=["already-claimed", "already-done", "stale-epoch"],
)
def test_claim_conflict_acks_and_drops(
    redis_endpoint: tuple[str, int], status: str, epoch: int, message_epoch: int
) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add(status=status, epoch=epoch)
        calls: list[TaskContext] = []
        consumer = env.consumer({"T": _sleeping_handler(0, calls)}, worker_id="w-1")
        consumer.start()
        await env.publish(task, epoch=message_epoch)

        await until(lambda: task.claim_calls == 1)
        await until(lambda: _zero(env))
        assert calls == []
        assert task.status == status
        assert task.results == []
        assert task.fails == []

    run_env(redis_endpoint, body)


def test_cancel_requested_aborts_and_fails_with_canceled(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add(cancel=True)
        cancelled = asyncio.Event()

        async def handler(_context: TaskContext) -> Mapping[str, object]:
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.set()
                raise
            return {}

        env.consumer({"T": handler}, worker_id="w-1").start()
        await env.publish(task)

        await until(lambda: task.status == "FAILED")
        await until(lambda: _zero(env))
        assert cancelled.is_set()
        assert task.fails == [(0, "CANCELED", False)]
        assert task.results == []

    run_env(redis_endpoint, body)


def test_heartbeat_409_abandons_without_further_callbacks(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add(heartbeat_409=True)
        cancelled = asyncio.Event()

        async def handler(_context: TaskContext) -> Mapping[str, object]:
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.set()
                raise
            return {}

        env.consumer({"T": handler}, worker_id="w-1").start()
        await env.publish(task)

        await until(cancelled.is_set)
        await until(lambda: _zero(env))
        await asyncio.sleep(0.2)  # 再给一点时间：确认没有迟到的 result/fail
        assert task.results == []
        assert task.fails == []
        assert task.heartbeat_calls == 1

    run_env(redis_endpoint, body)


def test_business_failure_reports_fail_then_acks(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()

        async def handler(_context: TaskContext) -> Mapping[str, object]:
            raise TaskFailedError("SCHEMA_VIOLATION", retryable=False)

        env.consumer({"T": handler}, worker_id="w-1").start()
        await env.publish(task)

        await until(lambda: task.status == "FAILED")
        await until(lambda: _zero(env))
        assert task.fails == [(0, "SCHEMA_VIOLATION", False)]

    run_env(redis_endpoint, body)


def test_crashed_consumer_message_is_taken_over_by_xautoclaim(
    redis_endpoint: tuple[str, int],
) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()
        await env.publish(task)
        # "dead" 消费者读到消息后崩溃：消息留在它的 PEL 里，从未 claim、从未 ACK。
        read = await env.redis.xreadgroup(GROUP_NAME, "dead", {env.stream: ">"}, count=1)
        assert read
        assert await env.pending() == 1

        survivor = env.consumer(
            {"T": _sleeping_handler(0.05)},
            worker_id="survivor",
            autoclaim_interval=0.1,
            autoclaim_min_idle_ms=150,
        )
        survivor.start()

        await until(lambda: task.status == "SUCCEEDED")
        await until(lambda: _zero(env))
        assert task.claimed_by == "survivor"
        assert task.results == [(0, {"done": task.task_id})]

    run_env(redis_endpoint, body)


def test_killed_after_claim_then_compensation_redispatch(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()
        started = asyncio.Event()

        async def hang(_context: TaskContext) -> Mapping[str, object]:
            started.set()
            await asyncio.sleep(60)
            return {}

        victim = env.consumer({"T": hang}, worker_id="victim")
        victim.start()
        await env.publish(task)
        await until(started.is_set)
        assert task.claimed_by == "victim"

        # 杀掉：在途任务被打断，不回调、不 ACK。
        await victim.stop(timeout=0)
        assert await env.pending() == 1
        assert task.results == []
        assert task.fails == []

        # Java 补偿扫描：租约到期 → epoch+1 → QUEUED → 重新 XADD。
        env.java.expire_and_requeue(task)
        await env.publish(task)

        survivor = env.consumer(
            {"T": _sleeping_handler(0.05)},
            worker_id="survivor",
            autoclaim_interval=0.1,
            autoclaim_min_idle_ms=150,
        )
        survivor.start()

        await until(lambda: task.status == "SUCCEEDED")
        # 旧消息经 XAUTOCLAIM 接管后 claim 409 → ACK；新消息正常执行 → ACK。
        await until(lambda: _zero(env))
        assert task.results == [(1, {"done": task.task_id})]
        assert task.claimed_by == "survivor"
        assert task.claim_calls >= 3

    run_env(redis_endpoint, body)


def test_concurrency_limit_is_respected(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        tasks = [env.java.add() for _ in range(5)]
        running = 0
        peak = 0
        release = asyncio.Event()

        async def handler(context: TaskContext) -> Mapping[str, object]:
            nonlocal running, peak
            running += 1
            peak = max(peak, running)
            try:
                await release.wait()
            finally:
                running -= 1
            return {"done": context.task_id}

        consumer = env.consumer({"T": handler}, worker_id="w-1", concurrency=2)
        consumer.start()
        for task in tasks:
            await env.publish(task)

        await until(lambda: running == 2)
        await asyncio.sleep(0.3)
        assert peak == 2
        assert consumer.inflight == 2
        release.set()

        await until(lambda: all(t.status == "SUCCEEDED" for t in tasks))
        await until(lambda: _zero(env))
        assert peak == 2

    run_env(redis_endpoint, body)


def test_graceful_stop_waits_for_inflight_tasks(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()
        seen: list[TaskContext] = []
        consumer = env.consumer({"T": _sleeping_handler(0.4, seen)}, worker_id="w-1")
        consumer.start()
        await env.publish(task)
        await until(lambda: bool(seen))

        await consumer.stop(timeout=5)

        assert task.status == "SUCCEEDED"
        assert await env.pending() == 0
        # 停机后不再读新消息。
        late = env.java.add()
        await env.publish(late)
        await asyncio.sleep(0.3)
        assert late.claim_calls == 0

    run_env(redis_endpoint, body)


def test_group_creation_is_idempotent(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        # 不预先建组：两个消费者同时启动，各自 XGROUP CREATE … MKSTREAM，后者拿到 BUSYGROUP。
        first = env.consumer({"T": _sleeping_handler(0)}, worker_id="a")
        second = env.consumer({"T": _sleeping_handler(0)}, worker_id="b")
        first.start()
        second.start()
        await until(lambda: first.ready and second.ready)
        groups = await env.redis.xinfo_groups(env.stream)
        assert [g["name"] for g in groups] == [GROUP_NAME]

        task = env.java.add()
        await env.publish(task)
        await until(lambda: task.status == "SUCCEEDED")
        assert task.claim_calls == 1

    run_env(redis_endpoint, body)


def test_malformed_and_unknown_type_messages_are_acked(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        env.consumer({"T": _sleeping_handler(0)}, worker_id="w-1").start()
        await env.redis.xadd(env.stream, {"taskType": "T", "taskId": "not-a-uuid", "epoch": "x"})
        task = env.java.add()
        await env.publish(task, task_type="UNKNOWN")

        await until(lambda: _zero(env))
        await asyncio.sleep(0.1)
        assert await env.pending() == 0
        assert task.claim_calls == 0

    run_env(redis_endpoint, body)


# --------------------------------------------------------------------------- #
# Redis 不可达 → /readyz 失败（不需要容器）
# --------------------------------------------------------------------------- #


def test_unreachable_redis_reports_not_ready_and_backs_off() -> None:
    async def main() -> None:
        redis = Redis(host="127.0.0.1", port=1, socket_connect_timeout=0.2)
        java = FakeJava()
        client = java.client()
        consumer = StreamConsumer(
            redis,
            client,
            {"T": _sleeping_handler(0)},
            worker_id="w",
            block_ms=100,
            backoff_initial=0.05,
            backoff_max=0.1,
        )
        consumer.start()
        await asyncio.sleep(0.5)
        assert consumer.readiness_failure() == "redis_unavailable"
        await consumer.stop(timeout=1)
        await client.aclose()
        await redis.aclose()

    asyncio.run(asyncio.wait_for(main(), 10))


# --------------------------------------------------------------------------- #
# 记忆抽取任务（契约 §0：MEMORY_EXTRACTION 改走 Stream + 租借 + 本地模型）
# --------------------------------------------------------------------------- #


def _memory_snapshot(task_id: str) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "jobId": task_id,
        "projectId": PROJECT_ID,
        "attempt": 1,
        "leaseEpoch": 0,
        "snapshotHash": "sha256:abc",
        "facts": [{"ref": "fact-1", "kind": "DECISION", "text": "统一使用 Redis Stream 派发任务"}],
        "existingMemories": [],
    }


_CANDIDATE = {
    "kind": "DECISION",
    "title": "任务派发改用 Redis Stream",
    "content": "记忆抽取任务从 HTTP 推送改为 Redis Stream 消费。",
    "confidence": 0.9,
    "sourceRef": "fact-1",
}


class FakeModel:
    def __init__(self, content: str) -> None:
        self.content = content
        self.requests: list[httpx2.Request] = []

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        return httpx2.Response(
            200,
            json={
                "id": "chatcmpl-mem",
                "object": "chat.completion",
                "created": 1764100000,
                "model": "memory-model",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": self.content},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
            },
        )


@dataclass
class MemoryRig:
    handler: MemoryExtractionHandler
    usage: UsageReporter
    client: JavaInternalClient

    async def close(self) -> None:
        await self.usage.aclose()
        await self.client.aclose()


def _memory_rig(java: FakeJava, model: FakeModel) -> MemoryRig:
    client = java.client()
    usage = UsageReporter(
        lambda items, token: _send_usage(client, items, token), flush_interval=0.05
    )
    kernel = ModelKernel(
        leases=LeaseCache(lambda request, token: client.lease(request, bearer_token=token)),
        usage=usage,
        runtime=ModelRuntime(
            timeout_seconds=5, max_retries=0, transport=httpx2.MockTransport(model)
        ),
    )
    handler = MemoryExtractionHandler(
        settings=Settings(prompts_dir=PROMPTS_DIR),
        java=client,
        kernel=kernel,
        prompt_store=PromptStore(PROMPTS_DIR),
    )
    return MemoryRig(handler=handler, usage=usage, client=client)


async def _send_usage(client: JavaInternalClient, items: Any, token: str) -> dict[str, bool]:
    results = await client.report_usage(items, bearer_token=token)
    return {r.call_id: r.accepted for r in results}


def test_memory_extraction_task_through_stream_end_to_end(redis_endpoint: tuple[str, int]) -> None:
    async def body(env: Env) -> None:
        await env.ensure_group()
        task = env.java.add()
        task.snapshot = _memory_snapshot(task.task_id)
        model = FakeModel(json.dumps({"candidates": [_CANDIDATE]}, ensure_ascii=False))
        rig = _memory_rig(env.java, model)
        try:
            env.consumer({"MEMORY_EXTRACTION": rig.handler}, worker_id="w-mem").start()
            await env.publish(task, task_type="MEMORY_EXTRACTION")

            await until(lambda: task.status == "SUCCEEDED")
            await until(lambda: _zero(env))
            await rig.usage.flush(ignore_backoff=True)
        finally:
            await rig.close()

        ((epoch, payload),) = task.results
        assert epoch == 0
        (candidate,) = payload["candidates"]
        assert candidate["sourceRef"] == "fact-1"
        assert candidate["dedupeKey"] == compute_dedupe_key(
            "DECISION", _CANDIDATE["title"], _CANDIDATE["content"]
        )
        assert env.java.lease_requests == [
            {
                "purpose": "MEMORY_EXTRACTION",
                "userId": USER_ID,
                "projectId": PROJECT_ID,
                "taskId": task.task_id,
                "runId": None,
            }
        ]
        assert len(model.requests) == 1
        prompt = model.requests[0].content.decode()
        assert "fact-1" in prompt
        (usage,) = env.java.usage_items
        assert usage["purpose"] == "MEMORY_EXTRACTION"
        assert usage["status"] == "SUCCESS"
        assert (usage["inputTokens"], usage["outputTokens"]) == (100, 20)

    run_env(redis_endpoint, body)


def _context(java: FakeJava, task: FakeTask, *, token: str | None = None) -> TaskContext:
    return TaskContext(
        task_id=task.task_id,
        task_type="MEMORY_EXTRACTION",
        epoch=task.epoch,
        lease_token="lt",
        token=token or _task_token(task.task_id, task.epoch),
    )


def _run_memory(
    content: str,
    *,
    snapshot: Callable[[str], dict[str, Any]] = _memory_snapshot,
    token: str | None = None,
) -> tuple[Mapping[str, object] | BaseException, FakeJava, FakeModel]:
    java = FakeJava()
    task = java.add()
    task.snapshot = snapshot(task.task_id)
    model = FakeModel(content)

    async def main() -> Mapping[str, object] | BaseException:
        rig = _memory_rig(java, model)
        try:
            return await rig.handler(_context(java, task, token=token))
        except Exception as exc:
            return exc
        finally:
            await rig.close()

    return asyncio.run(main()), java, model


def test_memory_handler_returns_candidates_payload() -> None:
    result, java, _model = _run_memory(json.dumps({"candidates": [_CANDIDATE]}))
    assert isinstance(result, Mapping)
    assert result["schemaVersion"] == 1
    assert len(result["candidates"]) == 1  # type: ignore[arg-type]
    assert java.usage_items[0]["status"] == "SUCCESS"


def test_memory_handler_drops_candidates_outside_snapshot() -> None:
    bad = {**_CANDIDATE, "sourceRef": "fact-404"}
    result, _java, _model = _run_memory(json.dumps({"candidates": [bad, _CANDIDATE]}))
    assert isinstance(result, Mapping)
    assert [c["sourceRef"] for c in result["candidates"]] == ["fact-1"]  # type: ignore[union-attr]


def test_memory_handler_invalid_model_output_is_schema_violation() -> None:
    result, _java, _model = _run_memory("not json at all")
    assert isinstance(result, TaskFailedError)
    assert (result.error_code, result.retryable) == ("SCHEMA_VIOLATION", False)


def test_memory_handler_rejects_token_without_scope_ids() -> None:
    raw = base64.urlsafe_b64encode(b'{"tid":"x"}').rstrip(b"=").decode()
    result, java, model = _run_memory("{}", token=f"h.{raw}.s")
    assert isinstance(result, TaskFailedError)
    assert result.error_code == "TASK_TOKEN_INVALID"
    assert java.lease_requests == []
    assert model.requests == []


def test_memory_handler_rejects_snapshot_for_other_job() -> None:
    def other(_task_id: str) -> dict[str, Any]:
        return _memory_snapshot(str(uuid.uuid4()))

    result, _java, model = _run_memory("{}", snapshot=other)
    assert isinstance(result, TaskFailedError)
    assert (result.error_code, result.retryable) == ("SNAPSHOT_INVALID", False)
    assert model.requests == []
