"""Redis Stream 任务消费框架（P1 契约 §4.1、§4.5）。

一条消息的生命周期（只有本模块决定何时 ``XACK``）：

1. ``XREADGROUP GROUP ai-worker {workerId} BLOCK 5000 COUNT n``（``n`` = 空闲并发槽位）；
2. ``claim``：成功 → 执行；返回 429 以外的 4xx（401/403/404/409，以及 400/422 等，契约 §9.7）
   → 直接 ``XACK``（令牌过期、任务已被别人认领、已不存在或请求不可能被接受，由 Java 补偿扫描兜底）；
   429/5xx/超时/传输层失败/200 但形状不对 → **不 ACK**，留在 PEL 里等 ``XAUTOCLAIM``；
3. 执行期间每 20s 一次 heartbeat（认领后立刻发第一次，让 Java 把 ``LEASED`` 推进到 ``RUNNING``）：
   ``cancelRequested=true`` → 中止并上报 ``fail(CANCELED, retryable=false)``；返回 409（以及
   401/403/404，同样表示租约已不属于本 worker）→ 立即放弃，**不再回调**；
4. 执行结束（成功或业务失败）→ ``result`` 或 ``fail`` → ``XACK``；``result`` 被 Java 以
   401/403/404/409 之外的不可重试 4xx（400/422 等）拒收 → 立刻补发一次
   ``fail(RESULT_REJECTED_*, retryable=false)`` 再 ``XACK``，不让任务卡在 ``RUNNING`` 等补偿扫描；
   ``result`` 返回 409（epoch 过期）或 401/403/404 → 不补发，直接 ``XACK``；429/5xx/超时照旧重试；
5. 后台每 30s 一次 ``XAUTOCLAIM … min-idle 120000``，接管崩溃消费者的消息，走同样的流程
   （epoch 过期会被 claim 409 拒掉，正常 ``XACK``）。

并发上限默认 4；优雅停机：不再读新消息，等在途任务完成或超时后取消（被取消的任务不 ACK，
租约到期后由 Java 补偿重派）。Redis 断连时指数退避重连，期间 :meth:`readiness_failure` 返回
``redis_unavailable``（``/readyz`` 503）。消费组创建幂等：``BUSYGROUP`` 视为已存在。

消息字段：``taskType``、``taskId``、``epoch``、``token``。``token`` 只在内存里传给 Java，绝不进
日志；日志只记 ``task_id`` / ``task_type`` / ``epoch`` / ``outcome``。
"""

from __future__ import annotations

import asyncio
import contextlib
import inspect
import logging
import uuid
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from typing import Any, Final, Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError, ResponseError

from ai_collab_worker.auth.token import peek_claims
from ai_collab_worker.clients.java_internal import (
    ClaimResponse,
    HeartbeatResponse,
    JavaInternalError,
)
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.observability.metrics import AI_TASKS

logger: Final[logging.Logger] = logging.getLogger(__name__)

STREAM_KEY: Final[str] = "aicollab:ai:tasks"
GROUP_NAME: Final[str] = "ai-worker"

DEFAULT_BLOCK_MS: Final[int] = 5000
DEFAULT_HEARTBEAT_SECONDS: Final[float] = 20.0
DEFAULT_AUTOCLAIM_SECONDS: Final[float] = 30.0
DEFAULT_AUTOCLAIM_MIN_IDLE_MS: Final[int] = 120_000
DEFAULT_SHUTDOWN_SECONDS: Final[float] = 30.0
BACKOFF_INITIAL_SECONDS: Final[float] = 0.5
BACKOFF_MAX_SECONDS: Final[float] = 30.0
REPORT_ATTEMPTS: Final[int] = 3

#: heartbeat 返回这些状态 → 租约已不属于本 worker。
_LOST_STATUSES: Final[frozenset[int]] = frozenset({401, 403, 404, 409})


def _claim_rejected(exc: JavaInternalError) -> bool:
    """claim 失败是否该 ``XACK`` 丢弃（契约 §9.7）。

    429 以外的 4xx（含 400/422）都不可重试：留在 PEL 里只会被 ``XAUTOCLAIM`` 反复接管、
    每次得到同样的拒绝。429、5xx、超时、传输错误、200 但形状不对 → 不 ACK。
    """

    status = exc.status
    return status is not None and 400 <= status < 500 and status != 429


def _result_rejected(exc: JavaInternalError) -> bool:
    """``result`` 被拒收、需要立刻补发 ``fail``（契约 §4.3、§9.7）。

    只针对内容被拒的不可重试 4xx（400/422 等）。409（epoch 过期）、401/403/404（租约或
    令牌已不属于本 worker）补发 ``fail`` 也只会得到同样的拒绝，直接放弃；429 走重试。
    """

    status = exc.status
    return (
        status is not None
        and 400 <= status < 500
        and status != 429
        and status not in _LOST_STATUSES
    )


def _rejection_code(exc: JavaInternalError) -> str:
    """补发 ``fail`` 的 ``errorCode``：带上 Java 的拒收原因（错误码或状态码），不超过 64 字符。"""

    detail = exc.code or str(exc.status)
    return f"{ERROR_RESULT_REJECTED}_{detail}"[:ERROR_CODE_MAX]


ERROR_CANCELED: Final[str] = "CANCELED"
ERROR_INTERNAL: Final[str] = "INTERNAL"
#: ``result`` 被拒收后补发 ``fail`` 的错误码前缀，后接 Java 错误码或 HTTP 状态。
ERROR_RESULT_REJECTED: Final[str] = "RESULT_REJECTED"
#: Java 侧 ``ai_job.last_error_code`` 为 ``VARCHAR(64)``。
ERROR_CODE_MAX: Final[int] = 64

OUTCOME_SUCCEEDED: Final[str] = "succeeded"
OUTCOME_FAILED: Final[str] = "failed"
OUTCOME_CANCELED: Final[str] = "canceled"
OUTCOME_ABANDONED: Final[str] = "abandoned"
OUTCOME_CLAIM_REJECTED: Final[str] = "claim_rejected"
OUTCOME_CLAIM_ERROR: Final[str] = "claim_error"
OUTCOME_MALFORMED: Final[str] = "malformed"
OUTCOME_UNKNOWN_TYPE: Final[str] = "unknown_type"
OUTCOME_INTERRUPTED: Final[str] = "interrupted"
OUTCOME_RESULT_REJECTED: Final[str] = "result_rejected"


@dataclass(frozen=True)
class TaskMessage:
    """一条 Stream 消息（已校验形状）。"""

    message_id: str
    task_type: str
    task_id: str
    epoch: int
    token: str = field(repr=False)

    @classmethod
    def parse(cls, message_id: str, fields: Mapping[str, Any] | None) -> TaskMessage | None:
        if not fields:
            return None
        task_type = _as_str(fields.get("taskType"))
        task_id = _as_str(fields.get("taskId"))
        token = _as_str(fields.get("token"))
        raw_epoch = _as_str(fields.get("epoch"))
        if not task_type or not task_id or not token or raw_epoch is None:
            return None
        try:
            uuid.UUID(task_id)
            epoch = int(raw_epoch)
        except ValueError:
            return None
        if epoch < 0:
            return None
        return cls(message_id, task_type, task_id, epoch, token)


@dataclass
class TaskContext:
    """处理器看到的任务上下文；``token`` 会被 heartbeat 换发的新令牌刷新。"""

    task_id: str
    task_type: str
    epoch: int
    lease_token: str = field(repr=False)
    token: str = field(repr=False)
    cancel_requested: bool = False
    abandoned: bool = False

    def claims(self) -> Mapping[str, object]:
        """当前任务令牌里的路由字段（``uid``/``pid``/``purp``/``tid``/``ep``），不验签。"""

        return peek_claims(self.token) or {}


class TaskFailedError(Exception):
    """处理器的业务失败：``error_code`` 原样进 ``fail``，``retryable`` 交给 Java 决定是否重排。"""

    def __init__(self, error_code: str, *, retryable: bool) -> None:
        super().__init__(error_code)
        self.error_code = error_code
        self.retryable = retryable


TaskHandler = Callable[[TaskContext], Awaitable[Mapping[str, object]]]


class TaskCallbacks(Protocol):
    """契约 §4.3 的 Java 回调（由 ``JavaInternalClient`` 实现）。"""

    async def claim(
        self, task_id: str, *, task_type: str, epoch: int, worker_id: str, bearer_token: str
    ) -> ClaimResponse: ...

    async def heartbeat(
        self, task_id: str, *, epoch: int, lease_token: str, bearer_token: str
    ) -> HeartbeatResponse: ...

    async def result(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        payload: Mapping[str, object],
        bearer_token: str,
    ) -> None: ...

    async def fail(
        self,
        task_id: str,
        *,
        epoch: int,
        lease_token: str,
        error_code: str,
        retryable: bool,
        bearer_token: str,
    ) -> None: ...


class StreamConsumer:
    """``aicollab:ai:tasks`` 的消费者；``start`` / ``stop`` 跟随 app 生命周期。"""

    def __init__(
        self,
        redis: Redis,
        callbacks: TaskCallbacks,
        handlers: Mapping[str, TaskHandler],
        *,
        worker_id: str,
        concurrency: int = 4,
        stream: str = STREAM_KEY,
        group: str = GROUP_NAME,
        block_ms: int = DEFAULT_BLOCK_MS,
        heartbeat_interval: float = DEFAULT_HEARTBEAT_SECONDS,
        autoclaim_interval: float = DEFAULT_AUTOCLAIM_SECONDS,
        autoclaim_min_idle_ms: int = DEFAULT_AUTOCLAIM_MIN_IDLE_MS,
        shutdown_timeout: float = DEFAULT_SHUTDOWN_SECONDS,
        backoff_initial: float = BACKOFF_INITIAL_SECONDS,
        backoff_max: float = BACKOFF_MAX_SECONDS,
    ) -> None:
        if concurrency < 1:
            msg = "concurrency 必须 ≥ 1"
            raise ValueError(msg)
        self._redis = redis
        self._callbacks = callbacks
        self._handlers = dict(handlers)
        self.worker_id: Final[str] = worker_id
        self._concurrency = concurrency
        self._stream = stream
        self._group = group
        self._block_ms = block_ms
        self._heartbeat_interval = heartbeat_interval
        self._autoclaim_interval = autoclaim_interval
        self._autoclaim_min_idle_ms = autoclaim_min_idle_ms
        self._shutdown_timeout = shutdown_timeout
        self._backoff_initial = backoff_initial
        self._backoff_max = backoff_max

        self._ready = False
        self._group_ready = False
        self._stopping = asyncio.Event()
        self._slot_freed = asyncio.Event()
        self._inflight: dict[str, asyncio.Task[None]] = {}
        self._loops: list[asyncio.Task[None]] = []

    # ---- 生命周期 ------------------------------------------------------------

    @property
    def ready(self) -> bool:
        return self._ready

    def readiness_failure(self) -> str | None:
        """``/readyz`` 检查：Redis 可用且消费组已就绪时返回 ``None``。"""

        return None if self._ready else "redis_unavailable"

    @property
    def inflight(self) -> int:
        return len(self._inflight)

    def start(self) -> None:
        if self._loops:
            return
        self._loops = [
            asyncio.create_task(self._read_loop(), name="ai-task-read"),
            asyncio.create_task(self._autoclaim_loop(), name="ai-task-autoclaim"),
        ]

    async def stop(self, *, timeout: float | None = None) -> None:
        """优雅停机：停止读取 → 等在途任务（最多 ``timeout`` 秒）→ 取消剩余任务。"""

        self._stopping.set()
        self._slot_freed.set()
        for loop in self._loops:
            loop.cancel()
        for loop in self._loops:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await loop
        self._loops = []
        pending = list(self._inflight.values())
        if pending:
            budget = self._shutdown_timeout if timeout is None else timeout
            _done, still_running = await asyncio.wait(pending, timeout=budget)
            for task in still_running:
                task.cancel()
            for task in still_running:
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await task
        self._ready = False

    # ---- 读取 / 接管 ---------------------------------------------------------

    async def _read_loop(self) -> None:
        backoff = self._backoff_initial
        while not self._stopping.is_set():
            try:
                await self._ensure_group()
                free = await self._wait_for_slot()
                if free <= 0:
                    continue
                response = await _await(
                    self._redis.xreadgroup(
                        self._group,
                        self.worker_id,
                        {self._stream: ">"},
                        count=free,
                        block=self._block_ms,
                    )
                )
                self._ready = True
                backoff = self._backoff_initial
                for _stream, entries in response or []:
                    for message_id, fields in entries:
                        self._dispatch(str(message_id), fields)
            except asyncio.CancelledError:
                raise
            except (RedisError, OSError) as exc:
                self._mark_unavailable(exc)
                await self._sleep(backoff)
                backoff = min(self._backoff_max, backoff * 2)

    async def _autoclaim_loop(self) -> None:
        while not self._stopping.is_set():
            await self._sleep(self._autoclaim_interval)
            if self._stopping.is_set() or not self._ready:
                continue
            try:
                await self.autoclaim_once()
            except asyncio.CancelledError:
                raise
            except (RedisError, OSError) as exc:
                self._mark_unavailable(exc)

    async def autoclaim_once(self) -> int:
        """执行一次 ``XAUTOCLAIM``，返回接管并派发的消息数（测试也直接调用）。"""

        free = self._concurrency - len(self._inflight)
        if free <= 0:
            return 0
        response = await _await(
            self._redis.xautoclaim(
                self._stream,
                self._group,
                self.worker_id,
                min_idle_time=self._autoclaim_min_idle_ms,
                start_id="0-0",
                count=free,
            )
        )
        entries = response[1] if isinstance(response, list | tuple) and len(response) > 1 else []
        dispatched = 0
        for message_id, fields in entries:
            if self._dispatch(str(message_id), fields):
                dispatched += 1
        if dispatched:
            logger.info("ai task autoclaimed count=%d", dispatched)
        return dispatched

    async def _ensure_group(self) -> None:
        if self._group_ready:
            return
        try:
            await _await(
                self._redis.xgroup_create(self._stream, self._group, id="$", mkstream=True)
            )
        except ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise
        self._group_ready = True

    async def _wait_for_slot(self) -> int:
        while len(self._inflight) >= self._concurrency and not self._stopping.is_set():
            self._slot_freed.clear()
            await self._slot_freed.wait()
        return self._concurrency - len(self._inflight)

    def _dispatch(self, message_id: str, fields: Mapping[str, Any] | None) -> bool:
        if message_id in self._inflight or self._stopping.is_set():
            return False
        task = asyncio.create_task(self._process(message_id, fields), name=f"ai-task:{message_id}")
        self._inflight[message_id] = task

        def _done(_task: asyncio.Task[None], *, key: str = message_id) -> None:
            self._inflight.pop(key, None)
            self._slot_freed.set()

        task.add_done_callback(_done)
        return True

    # ---- 单条消息 ------------------------------------------------------------

    async def _process(self, message_id: str, fields: Mapping[str, Any] | None) -> None:
        message = TaskMessage.parse(message_id, fields)
        if message is None:
            logger.warning("ai task malformed message_id=%s", message_id)
            _count("-", OUTCOME_MALFORMED)
            await self._ack(message_id)
            return
        handler = self._handlers.get(message.task_type)
        if handler is None:
            logger.warning(
                "ai task unknown type task_id=%s task_type=%s", message.task_id, message.task_type
            )
            _count(message.task_type, OUTCOME_UNKNOWN_TYPE)
            await self._ack(message_id)
            return

        try:
            claim = await self._callbacks.claim(
                message.task_id,
                task_type=message.task_type,
                epoch=message.epoch,
                worker_id=self.worker_id,
                bearer_token=message.token,
            )
        except JavaInternalError as exc:
            if _claim_rejected(exc):
                logger.info(
                    "ai task claim rejected task_id=%s epoch=%d status=%s",
                    message.task_id,
                    message.epoch,
                    exc.status,
                )
                _count(message.task_type, OUTCOME_CLAIM_REJECTED)
                await self._ack(message_id)
            else:
                # 暂时性失败：不 ACK，留给 XAUTOCLAIM 重试。
                logger.warning(
                    "ai task claim failed task_id=%s reason=%s", message.task_id, exc.reason
                )
                _count(message.task_type, OUTCOME_CLAIM_ERROR)
            return

        context = TaskContext(
            task_id=message.task_id,
            task_type=message.task_type,
            epoch=message.epoch,
            lease_token=claim.lease_token,
            token=claim.token,
        )
        outcome = await self._execute(handler, context)
        if outcome == OUTCOME_INTERRUPTED:
            return
        _count(message.task_type, outcome)
        await self._ack(message_id)

    async def _execute(self, handler: TaskHandler, context: TaskContext) -> str:
        run: asyncio.Task[Mapping[str, object]] = asyncio.ensure_future(handler(context))
        beat = asyncio.create_task(self._heartbeat_loop(context, run))
        final: tuple[str, str, bool] | None = None
        payload: Mapping[str, object] | None = None
        outcome = OUTCOME_FAILED
        try:
            payload = await run
            outcome = OUTCOME_SUCCEEDED
        except asyncio.CancelledError:
            if context.abandoned:
                outcome = OUTCOME_ABANDONED
            elif context.cancel_requested:
                outcome = OUTCOME_CANCELED
                final = ("fail", ERROR_CANCELED, False)
            else:
                # 停机取消：不回调、不 ACK，租约到期后由 Java 补偿重派。
                _count(context.task_type, OUTCOME_INTERRUPTED)
                beat.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await beat
                raise
        except TaskFailedError as exc:
            final = ("fail", exc.error_code, exc.retryable)
        except AppError as exc:
            final = ("fail", exc.code.value, exc.retryable)
        except Exception as exc:
            logger.warning(
                "ai task handler crashed task_id=%s error=%s",
                context.task_id,
                type(exc).__name__,
            )
            final = ("fail", ERROR_INTERNAL, True)
        finally:
            if not beat.done():
                beat.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await beat

        if context.abandoned:
            # heartbeat 已判定租约丢失：不再回调。
            return OUTCOME_ABANDONED
        if outcome == OUTCOME_SUCCEEDED and payload is not None:
            if not await self._report_result(context, payload):
                outcome = OUTCOME_RESULT_REJECTED
        elif final is not None:
            _kind, code, retryable = final
            await self._report_fail(context, code, retryable)
        logger.info(
            "ai task done task_id=%s task_type=%s epoch=%d outcome=%s",
            context.task_id,
            context.task_type,
            context.epoch,
            outcome,
        )
        return outcome

    async def _heartbeat_loop(
        self, context: TaskContext, run: asyncio.Task[Mapping[str, object]]
    ) -> None:
        first = True
        while not run.done():
            if not first:
                await asyncio.sleep(self._heartbeat_interval)
                if run.done():
                    return
            first = False
            try:
                response = await self._callbacks.heartbeat(
                    context.task_id,
                    epoch=context.epoch,
                    lease_token=context.lease_token,
                    bearer_token=context.token,
                )
            except JavaInternalError as exc:
                if exc.status in _LOST_STATUSES:
                    logger.info(
                        "ai task lease lost task_id=%s epoch=%d status=%s",
                        context.task_id,
                        context.epoch,
                        exc.status,
                    )
                    context.abandoned = True
                    run.cancel()
                    return
                logger.warning(
                    "ai task heartbeat failed task_id=%s reason=%s", context.task_id, exc.reason
                )
                continue
            if response.token:
                context.token = response.token
            if response.cancel_requested:
                logger.info("ai task cancel requested task_id=%s", context.task_id)
                context.cancel_requested = True
                run.cancel()
                return

    async def _report_result(self, context: TaskContext, payload: Mapping[str, object]) -> bool:
        """上报 result；返回 ``False`` 表示 Java 拒收了结果内容且已补发 ``fail``。"""

        async def call() -> None:
            await self._callbacks.result(
                context.task_id,
                epoch=context.epoch,
                lease_token=context.lease_token,
                payload=payload,
                bearer_token=context.token,
            )

        error = await self._report(context, "result", call)
        if error is None or not _result_rejected(error):
            return True
        # 结果内容被拒（400/422 等）：立刻补发 fail，任务直接进终态，而不是卡在 RUNNING
        # 等租约过期被补偿扫描重派、再算一遍同样被拒的结果。
        await self._report_fail(context, _rejection_code(error), False)
        return False

    async def _report_fail(self, context: TaskContext, code: str, retryable: bool) -> None:
        async def call() -> None:
            await self._callbacks.fail(
                context.task_id,
                epoch=context.epoch,
                lease_token=context.lease_token,
                error_code=code,
                retryable=retryable,
                bearer_token=context.token,
            )

        await self._report(context, "fail", call)

    async def _report(
        self, context: TaskContext, kind: str, call: Callable[[], Awaitable[None]]
    ) -> JavaInternalError | None:
        """带重试地回调；成功返回 ``None``，放弃时返回最后一次错误（由调用方决定是否补救）。"""

        delay = self._backoff_initial
        for attempt in range(1, REPORT_ATTEMPTS + 1):
            try:
                await call()
                return None
            except JavaInternalError as exc:
                if not exc.retryable or attempt == REPORT_ATTEMPTS:
                    # 409 等：结果被 fence 或已终态；放弃即可，ACK 后由 Java 状态为准。
                    logger.warning(
                        "ai task %s not accepted task_id=%s status=%s reason=%s code=%s",
                        kind,
                        context.task_id,
                        exc.status,
                        exc.reason,
                        exc.code or "-",
                    )
                    return exc
            await asyncio.sleep(delay)
            delay = min(self._backoff_max, delay * 2)
        return None  # pragma: no cover - 循环必定在最后一次尝试内返回

    async def _ack(self, message_id: str) -> None:
        try:
            await _await(self._redis.xack(self._stream, self._group, message_id))
        except (RedisError, OSError) as exc:
            # ACK 失败：消息留在 PEL，之后 XAUTOCLAIM → claim 409 → 再 ACK。
            self._mark_unavailable(exc)

    # ---- 工具 ----------------------------------------------------------------

    def _mark_unavailable(self, exc: BaseException) -> None:
        if self._ready:
            logger.warning("redis unavailable error=%s", type(exc).__name__)
        self._ready = False
        # 连接类错误或消费组被删（NOGROUP）：重连后重新幂等建组。
        if not isinstance(exc, ResponseError) or "NOGROUP" in str(exc):
            self._group_ready = False

    async def _sleep(self, seconds: float) -> None:
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self._stopping.wait(), seconds)


async def _await(value: object) -> Any:
    """redis-py 的类型标注是 ``T | Awaitable[T]``；统一成 await。"""

    if inspect.isawaitable(value):
        return await value
    return value


def _as_str(value: object) -> str | None:
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            return None
    if isinstance(value, str):
        return value
    return None


def _count(task_type: str, outcome: str) -> None:
    AI_TASKS.labels(task_type=task_type, outcome=outcome).inc()


__all__ = [
    "DEFAULT_AUTOCLAIM_MIN_IDLE_MS",
    "DEFAULT_HEARTBEAT_SECONDS",
    "ERROR_CANCELED",
    "GROUP_NAME",
    "STREAM_KEY",
    "StreamConsumer",
    "TaskCallbacks",
    "TaskContext",
    "TaskFailedError",
    "TaskHandler",
    "TaskMessage",
]
