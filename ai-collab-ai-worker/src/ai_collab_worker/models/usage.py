"""用量收集与攒批上报（P1 契约 §3、§5）。

- :class:`UsageCallback`：LangChain 回调，从 ``on_llm_end`` 的生成结果里取 ``usage_metadata``
  （流式时 LangChain 交给 ``on_llm_end`` 的是合并后的消息，usage 已合并），记录耗时；
  ``on_llm_error`` 记失败。一个回调对象对应**一次**逻辑调用（一个 ``callId``）。
- :class:`UsageReporter`：内存队列，``≤ 50`` 条或 ``≤ 2s`` 刷一次；按 bearer token 分组上报
  （用量条目必须与令牌的 ``uid/pid`` 一致，契约 §3）。失败用**同一 callId** 指数退避重试，
  重试耗尽后丢弃并计 ``ai_usage_report_failures_total``；进程退出前 :meth:`aclose` 尽力 flush。
  上报失败永远不影响业务结果。

条目里只有 ``callId/leaseId/purpose/model/tokens/latency/status/errorCode``，没有正文与密钥。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
import uuid
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Final

from langchain_core.callbacks import AsyncCallbackHandler
from langchain_core.outputs import ChatGeneration, LLMResult

from ai_collab_worker.models.errors import USAGE_SUCCESS, classify
from ai_collab_worker.observability.metrics import AI_MODEL_CALLS, AI_USAGE_REPORT_FAILURES

logger: Final[logging.Logger] = logging.getLogger(__name__)

CALLER_TYPE: Final[str] = "PYTHON"
MAX_BATCH: Final[int] = 50
FLUSH_INTERVAL_SECONDS: Final[float] = 2.0
MAX_ATTEMPTS: Final[int] = 5
BACKOFF_BASE_SECONDS: Final[float] = 0.5
BACKOFF_MAX_SECONDS: Final[float] = 8.0
MAX_QUEUE: Final[int] = 10_000


@dataclass(frozen=True)
class UsageItem:
    """契约 §3 的一条用量。"""

    call_id: str
    lease_id: str
    purpose: str
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    status: str
    error_code: str | None = None

    def to_wire(self) -> dict[str, object]:
        return {
            "callId": self.call_id,
            "leaseId": self.lease_id,
            "purpose": self.purpose,
            "model": self.model,
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "latencyMs": self.latency_ms,
            "status": self.status,
            "errorCode": self.error_code,
        }


@dataclass
class _Pending:
    item: UsageItem
    bearer_token: str = field(repr=False)
    attempts: int = 0
    not_before: float = 0.0


#: ``(items, bearer_token) -> 逐条 {callId: accepted}``；抛异常表示整批失败。
UsageSender = Callable[[Sequence[Mapping[str, object]], str], Awaitable[Mapping[str, bool]]]


class UsageReporter:
    """攒批上报器；生命周期跟随 app（``start`` / ``aclose``）。"""

    def __init__(
        self,
        sender: UsageSender,
        *,
        max_batch: int = MAX_BATCH,
        flush_interval: float = FLUSH_INTERVAL_SECONDS,
        max_attempts: int = MAX_ATTEMPTS,
        backoff_base: float = BACKOFF_BASE_SECONDS,
        backoff_max: float = BACKOFF_MAX_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._sender = sender
        self._max_batch = max_batch
        self._flush_interval = flush_interval
        self._max_attempts = max_attempts
        self._backoff_base = backoff_base
        self._backoff_max = backoff_max
        self._clock = clock
        self._pending: list[_Pending] = []
        self._wakeup = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._closed = False
        self._lock = asyncio.Lock()

    # ---- 生命周期 ------------------------------------------------------------

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="usage-reporter")

    async def aclose(self, *, timeout: float = 5.0) -> None:
        """停止后台循环并尽力 flush 一次（忽略退避时间）。"""

        self._closed = True
        self._wakeup.set()
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._task
            self._task = None
        with contextlib.suppress(Exception):
            await asyncio.wait_for(self.flush(ignore_backoff=True), timeout)
        for pending in self._pending:
            _count_failure(pending.item)
        self._pending.clear()

    # ---- 入队 / 刷新 ---------------------------------------------------------

    def submit(self, item: UsageItem, *, bearer_token: str) -> None:
        """入队一条用量（同步、不阻塞业务路径）。"""

        AI_MODEL_CALLS.labels(
            purpose=item.purpose, caller_type=CALLER_TYPE, status=item.status
        ).inc()
        if len(self._pending) >= MAX_QUEUE:
            _count_failure(item)
            return
        self._pending.append(_Pending(item=item, bearer_token=bearer_token))
        if len(self._pending) >= self._max_batch:
            self._wakeup.set()

    @property
    def pending(self) -> int:
        return len(self._pending)

    async def flush(self, *, ignore_backoff: bool = False) -> None:
        """把当前到期的条目按令牌分组、每组 ≤ max_batch 上报一次。"""

        async with self._lock:
            now = self._clock()
            ready = [p for p in self._pending if ignore_backoff or p.not_before <= now]
            if not ready:
                return
            ready_ids = {id(p) for p in ready}
            self._pending = [p for p in self._pending if id(p) not in ready_ids]
            groups: dict[str, list[_Pending]] = {}
            for pending in ready:
                groups.setdefault(pending.bearer_token, []).append(pending)
            for token, entries in groups.items():
                for start in range(0, len(entries), self._max_batch):
                    await self._send(token, entries[start : start + self._max_batch])

    # ---- 内部 ----------------------------------------------------------------

    async def _run(self) -> None:
        while not self._closed:
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), self._flush_interval)
            self._wakeup.clear()
            try:
                await self.flush()
            except Exception:  # 上报不得让后台循环退出
                logger.warning("usage flush failed unexpectedly")

    async def _send(self, token: str, batch: list[_Pending]) -> None:
        try:
            results = await self._sender([p.item.to_wire() for p in batch], token)
        except Exception as exc:
            logger.warning("usage report failed items=%d error=%s", len(batch), type(exc).__name__)
            for pending in batch:
                self._retry_later(pending)
            return
        for pending in batch:
            accepted = results.get(pending.item.call_id)
            if accepted is None:
                # 逐条结果缺失：按失败重试（重复 callId 在 Java 侧幂等）。
                self._retry_later(pending)
            elif not accepted:
                logger.warning("usage item rejected call_id=%s", pending.item.call_id)
                _count_failure(pending.item)

    def _retry_later(self, pending: _Pending) -> None:
        _count_failure(pending.item)
        pending.attempts += 1
        if pending.attempts >= self._max_attempts:
            logger.warning("usage item dropped call_id=%s", pending.item.call_id)
            return
        delay = min(self._backoff_max, self._backoff_base * (2 ** (pending.attempts - 1)))
        pending.not_before = self._clock() + delay
        self._pending.append(pending)


def _count_failure(item: UsageItem) -> None:
    AI_USAGE_REPORT_FAILURES.labels(purpose=item.purpose, caller_type=CALLER_TYPE).inc()


# --------------------------------------------------------------------------- #
# LangChain 回调
# --------------------------------------------------------------------------- #


class UsageCallback(AsyncCallbackHandler):
    """收集一次逻辑调用的 token 用量与耗时；``finish`` 把结果交给 reporter。"""

    raise_error = False

    def __init__(self, *, clock: Callable[[], float] = time.perf_counter) -> None:
        self.call_id: Final[str] = str(uuid.uuid4())
        self._clock = clock
        self._started: float | None = None
        self.input_tokens = 0
        self.output_tokens = 0
        self.error: BaseException | None = None
        self.ended = False

    async def on_chat_model_start(self, *_args: Any, **_kwargs: Any) -> None:
        if self._started is None:
            self._started = self._clock()

    async def on_llm_start(self, *_args: Any, **_kwargs: Any) -> None:
        if self._started is None:
            self._started = self._clock()

    async def on_llm_end(self, response: LLMResult, **_kwargs: Any) -> None:
        self.ended = True
        found = False
        for generations in response.generations:
            for generation in generations:
                if not isinstance(generation, ChatGeneration):
                    continue
                usage = getattr(generation.message, "usage_metadata", None)
                if usage:
                    self.input_tokens += int(usage.get("input_tokens", 0) or 0)
                    self.output_tokens += int(usage.get("output_tokens", 0) or 0)
                    found = True
        if not found and isinstance(response.llm_output, dict):
            raw = response.llm_output.get("token_usage") or response.llm_output.get("usage")
            if isinstance(raw, Mapping):
                self.input_tokens += _int(raw.get("prompt_tokens", raw.get("input_tokens")))
                self.output_tokens += _int(raw.get("completion_tokens", raw.get("output_tokens")))

    async def on_llm_error(self, error: BaseException, **_kwargs: Any) -> None:
        self.error = error

    def latency_ms(self) -> int:
        if self._started is None:
            return 0
        return max(0, int((self._clock() - self._started) * 1000))

    def to_item(
        self,
        *,
        lease_id: str,
        purpose: str,
        model: str,
        status: str | None = None,
        error: BaseException | None = None,
    ) -> UsageItem:
        failure = error or self.error
        resolved = status
        error_code: str | None = None
        if resolved is None:
            if failure is None:
                resolved = USAGE_SUCCESS
            else:
                kind = classify(failure)
                resolved = kind.usage_status
                error_code = kind.reason
        elif failure is not None and resolved != USAGE_SUCCESS:
            error_code = classify(failure).reason
        return UsageItem(
            call_id=self.call_id,
            lease_id=lease_id,
            purpose=purpose,
            model=model,
            input_tokens=self.input_tokens,
            output_tokens=self.output_tokens,
            latency_ms=self.latency_ms(),
            status=resolved,
            error_code=error_code,
        )


def _int(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        return 0
    return max(0, value)


__all__ = [
    "CALLER_TYPE",
    "FLUSH_INTERVAL_SECONDS",
    "MAX_BATCH",
    "UsageCallback",
    "UsageItem",
    "UsageReporter",
    "UsageSender",
]
