"""result 被 Java 拒收后的处理（契约 §4.3、§9.7；审查打回项 1）。

不连 Redis：只替换 ``xack``，直接驱动 :meth:`StreamConsumer._process` 一条消息的完整生命周期。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Mapping
from typing import Any

import pytest

from ai_collab_worker.clients.java_internal import (
    ClaimResponse,
    HeartbeatResponse,
    JavaInternalError,
)
from ai_collab_worker.tasks.stream import (
    ERROR_CODE_MAX,
    StreamConsumer,
    TaskContext,
)


class FakeRedis:
    def __init__(self) -> None:
        self.acked: list[str] = []

    async def xack(self, _stream: str, _group: str, message_id: str) -> int:
        self.acked.append(message_id)
        return 1


class FakeCallbacks:
    """``result`` 依次弹出 ``result_errors``（``None`` 表示成功），``fail`` 只记录。"""

    def __init__(self, result_errors: list[JavaInternalError | None]) -> None:
        self.result_errors = list(result_errors)
        self.result_calls = 0
        self.fails: list[tuple[str, bool]] = []

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
        self.result_calls += 1
        error = self.result_errors.pop(0) if self.result_errors else None
        if error is not None:
            raise error

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
        self.fails.append((error_code, retryable))


async def _ok(_context: TaskContext) -> Mapping[str, object]:
    return {"ok": True}


def _run(callbacks: FakeCallbacks) -> FakeRedis:
    redis = FakeRedis()

    async def main() -> None:
        consumer = StreamConsumer(
            redis,  # type: ignore[arg-type]
            callbacks,
            {"T": _ok},
            worker_id="w-1",
            heartbeat_interval=3600,
            backoff_initial=0,
            backoff_max=0,
        )
        fields: dict[str, Any] = {
            "taskType": "T",
            "taskId": str(uuid.uuid4()),
            "epoch": "0",
            "token": "tok-0",
        }
        await consumer._process("1-0", fields)

    asyncio.run(main())
    return redis


@pytest.mark.parametrize(
    ("status", "code", "expected"),
    [
        (400, "INVALID_REQUEST", "RESULT_REJECTED_INVALID_REQUEST"),
        (422, "SCHEMA_VIOLATION", "RESULT_REJECTED_SCHEMA_VIOLATION"),
        (422, None, "RESULT_REJECTED_422"),
        (410, None, "RESULT_REJECTED_410"),
    ],
    ids=["400", "422", "422-no-code", "410"],
)
def test_result_rejected_4xx_sends_fail_once_then_acks(
    status: int, code: str | None, expected: str
) -> None:
    """不可重试 4xx：result 只发一次，立刻补发 fail(带拒收原因, retryable=false)，再 ACK。"""

    error = JavaInternalError("rejected", status=status, code=code, retryable=False)
    callbacks = FakeCallbacks([error])
    redis = _run(callbacks)

    assert callbacks.result_calls == 1
    assert callbacks.fails == [(expected, False)]
    assert redis.acked == ["1-0"]


def test_rejection_code_is_bounded_to_java_column() -> None:
    error = JavaInternalError("rejected", status=422, code="X" * 64, retryable=False)
    callbacks = FakeCallbacks([error])
    _run(callbacks)

    ((code, _retryable),) = callbacks.fails
    assert len(code) == ERROR_CODE_MAX
    assert code.startswith("RESULT_REJECTED_")


@pytest.mark.parametrize("status", [409, 401, 403, 404])
def test_result_stale_or_lost_does_not_send_fail(status: int) -> None:
    """409（epoch 过期）以及 401/403/404：不补发 fail，直接停止并 ACK。"""

    error = JavaInternalError("conflict", status=status, retryable=False)
    callbacks = FakeCallbacks([error])
    redis = _run(callbacks)

    assert callbacks.result_calls == 1
    assert callbacks.fails == []
    assert redis.acked == ["1-0"]


@pytest.mark.parametrize(
    "error",
    [
        JavaInternalError("rate_limited", status=429, retryable=True),
        JavaInternalError("unavailable", status=500, retryable=True),
        JavaInternalError("unavailable", status=503, retryable=True),
        JavaInternalError("timeout", retryable=True),
    ],
    ids=["429", "500", "503", "timeout"],
)
def test_result_retryable_failure_retries_then_succeeds(error: JavaInternalError) -> None:
    """429/5xx/超时：照旧重试 result，成功后不发 fail。"""

    callbacks = FakeCallbacks([error, None])
    redis = _run(callbacks)

    assert callbacks.result_calls == 2
    assert callbacks.fails == []
    assert redis.acked == ["1-0"]


def test_result_retryable_failure_exhausted_does_not_send_fail() -> None:
    """可重试错误用尽 3 次：不补发 fail（交给补偿扫描），照常 ACK。"""

    error = JavaInternalError("unavailable", status=503, retryable=True)
    callbacks = FakeCallbacks([error, error, error])
    redis = _run(callbacks)

    assert callbacks.result_calls == 3
    assert callbacks.fails == []
    assert redis.acked == ["1-0"]
