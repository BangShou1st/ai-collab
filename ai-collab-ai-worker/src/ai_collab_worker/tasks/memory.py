"""``MEMORY_EXTRACTION`` 任务处理器（P1 契约 §0、§4.3、§4.5）。

由 :class:`~ai_collab_worker.tasks.stream.StreamConsumer` 在 claim 成功后调用，一次执行：

1. 从任务令牌（claim / heartbeat 换发的新令牌）里取 ``uid`` / ``pid``——这两个值随后会被 Java
   在租借接口里再校验一次（``userId == uid``、``projectId == pid``），这里只用来组请求；
2. ``GET /internal/ai/tasks/{taskId}/snapshot``（沿用现有快照形状），校验 ``jobId`` 与任务一致；
3. 按快照拼 PromptPack prompt，经 :class:`~ai_collab_worker.models.kernel.ModelKernel`
   （租借凭据 → 本地模型客户端 → 用量上报）调模型，``purpose=MEMORY_EXTRACTION``；
4. 解析候选（``dedupeKey`` 由本侧重算，``sourceRef`` 必须在快照内），返回 result 的 ``payload``。

失败一律抛 :class:`~ai_collab_worker.tasks.stream.TaskFailedError` / ``AppError``，由消费框架转成
``fail(errorCode, retryable)``；是否重排由 Java 决定。日志只记 ``task_id`` 与固定 reason。
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import Mapping
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.clients.java_internal import JavaInternalClient, JavaInternalError
from ai_collab_worker.cognition.memory.parser import MemoryCandidateError, parse_candidates
from ai_collab_worker.cognition.memory.prompt import build_memory_prompt, prompt_size_bytes
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import SCHEMA_VERSION, ErrorCode
from ai_collab_worker.contract.memory_job import JOB_TYPE_MEMORY_EXTRACTION, MemoryJobSnapshot
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import LeasePurpose
from ai_collab_worker.observability.metrics import (
    MEMORY_CANDIDATES,
    MEMORY_INVALID_DEDUPE,
    MEMORY_INVALID_SOURCE,
    MEMORY_JOB_DURATION,
    MEMORY_JOB_REQUESTS,
)
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.tasks.stream import TaskContext, TaskFailedError

logger: Final[logging.Logger] = logging.getLogger(__name__)

TASK_TYPE: Final[str] = JOB_TYPE_MEMORY_EXTRACTION

#: prompt 字节上限（沿用 P0 的 64 KiB 口径）。
PROMPT_BYTE_LIMIT: Final[int] = 64 * 1024

#: fail 的错误码（低基数，Java 只记录不解析）。
ERROR_TOKEN_CLAIMS: Final[str] = "TASK_TOKEN_INVALID"
ERROR_SNAPSHOT: Final[str] = "SNAPSHOT_INVALID"
ERROR_SNAPSHOT_UNAVAILABLE: Final[str] = "SNAPSHOT_UNAVAILABLE"
ERROR_PROMPT_TOO_LARGE: Final[str] = "PROMPT_TOO_LARGE"


class MemoryExtractionHandler:
    """记忆抽取的任务处理器；实例是 ``TaskHandler``（可直接注册进消费框架）。"""

    def __init__(
        self,
        *,
        settings: Settings,
        java: JavaInternalClient,
        kernel: ModelKernel,
        prompt_store: PromptStore,
    ) -> None:
        self._settings: Final[Settings] = settings
        self._java: Final[JavaInternalClient] = java
        self._kernel: Final[ModelKernel] = kernel
        self._prompt_store: Final[PromptStore] = prompt_store

    async def __call__(self, context: TaskContext) -> Mapping[str, object]:
        started = time.perf_counter()
        outcome = "failed"
        try:
            async with asyncio.timeout(self._settings.memory_job_deadline_ms / 1000.0):
                payload = await self._run(context)
            outcome = "succeeded"
            return payload
        except TimeoutError:
            outcome = "deadline"
            raise TaskFailedError(ErrorCode.DEADLINE_EXCEEDED.value, retryable=True) from None
        except asyncio.CancelledError:
            outcome = "canceled"
            raise
        finally:
            elapsed = time.perf_counter() - started
            MEMORY_JOB_REQUESTS.labels(outcome=outcome).inc()
            MEMORY_JOB_DURATION.labels(outcome=outcome).observe(elapsed)

    async def _run(self, context: TaskContext) -> Mapping[str, object]:
        user_id, project_id = _scope_ids(context)
        snapshot = await self._snapshot(context)
        prompt = build_memory_prompt(
            self._prompt_store,
            snapshot,
            max_candidates=self._settings.memory_max_candidates,
        )
        if prompt_size_bytes(prompt) > PROMPT_BYTE_LIMIT:
            raise TaskFailedError(ERROR_PROMPT_TOO_LARGE, retryable=False)

        completion = await self._kernel.complete(
            CallScope(
                purpose=LeasePurpose.MEMORY_EXTRACTION,
                user_id=user_id,
                project_id=project_id,
                task_id=uuid.UUID(context.task_id),
                bearer_token=context.token,
            ),
            system_prompt=prompt.system,
            user_prompt=prompt.user,
        )
        try:
            parsed = parse_candidates(
                completion.text,
                snapshot=snapshot,
                max_candidates=self._settings.memory_max_candidates,
            )
        except MemoryCandidateError as exc:
            logger.info(
                "memory task invalid model output task_id=%s reason=%s",
                context.task_id,
                exc.reason,
            )
            raise TaskFailedError(ErrorCode.SCHEMA_VIOLATION.value, retryable=False) from None

        if parsed.invalid_source:
            MEMORY_INVALID_SOURCE.inc(parsed.invalid_source)
        if parsed.invalid_dedupe:
            MEMORY_INVALID_DEDUPE.inc(parsed.invalid_dedupe)
        if parsed.candidates:
            MEMORY_CANDIDATES.inc(len(parsed.candidates))
        logger.info(
            "memory task extracted task_id=%s call_id=%s candidates=%d",
            context.task_id,
            completion.call_id,
            len(parsed.candidates),
        )
        return {
            "schemaVersion": SCHEMA_VERSION,
            "candidates": [
                candidate.model_dump(by_alias=True, mode="json") for candidate in parsed.candidates
            ],
        }

    async def _snapshot(self, context: TaskContext) -> MemoryJobSnapshot:
        try:
            raw = await self._java.snapshot(context.task_id, bearer_token=context.token)
        except JavaInternalError as exc:
            raise TaskFailedError(ERROR_SNAPSHOT_UNAVAILABLE, retryable=exc.retryable) from None
        try:
            snapshot = MemoryJobSnapshot.model_validate(raw)
        except ValidationError:
            raise TaskFailedError(ERROR_SNAPSHOT, retryable=False) from None
        if str(snapshot.job_id) != context.task_id:
            raise TaskFailedError(ERROR_SNAPSHOT, retryable=False)
        return snapshot


def _scope_ids(context: TaskContext) -> tuple[uuid.UUID, uuid.UUID]:
    claims = context.claims()
    try:
        user_id = uuid.UUID(str(claims["uid"]))
        project_id = uuid.UUID(str(claims["pid"]))
    except (KeyError, ValueError):
        raise TaskFailedError(ERROR_TOKEN_CLAIMS, retryable=False) from None
    return user_id, project_id


__all__ = ["TASK_TYPE", "MemoryExtractionHandler"]
