"""模型调用内核：租借凭据 → 本地模型客户端 → 用量上报（P1 契约 §2.5、§3、§5）。

认知能力（query plan、记忆抽取）只调用 :meth:`ModelKernel.complete`，不直接碰 lease、SDK
或用量队列：

1. 按 ``(purpose, userId, projectId, taskId|runId)`` 从 :class:`LeaseCache` 取 lease（缓存在
   ``expiresAt − 30s`` 失效）；
2. :func:`build_chat_model` 构造厂商客户端，超时/重试由 :class:`ModelRuntime` 统一给出；
3. 带 :class:`UsageCallback` 调 ``ainvoke``；无论成功、失败、取消都生成一条用量进上报队列；
4. 厂商异常在这里包装成 :class:`AppError`（:func:`to_app_error`），``from None`` 切断异常链；
   鉴权类失败顺带作废缓存的 lease。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Final

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from ai_collab_worker.clients.java_internal import JavaInternalError
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.errors import REASON_AUTH, USAGE_CANCELED, classify, to_app_error
from ai_collab_worker.models.factory import ModelRuntime, build_chat_model
from ai_collab_worker.models.lease import (
    Lease,
    LeaseCache,
    LeaseHandle,
    LeasePurpose,
    LeaseRequest,
)
from ai_collab_worker.models.usage import UsageCallback, UsageReporter


@dataclass(frozen=True)
class CallScope:
    """一次模型调用的归属：决定 lease 键与用量归属。"""

    purpose: LeasePurpose
    user_id: uuid.UUID
    project_id: uuid.UUID
    bearer_token: str
    task_id: uuid.UUID | None = None
    run_id: uuid.UUID | None = None

    def lease_request(self) -> LeaseRequest:
        return LeaseRequest(
            purpose=self.purpose,
            user_id=self.user_id,
            project_id=self.project_id,
            task_id=self.task_id,
            run_id=self.run_id,
        )

    def __repr__(self) -> str:  # 不输出令牌
        return (
            f"CallScope(purpose={self.purpose.value}, user_id={self.user_id}, "
            f"project_id={self.project_id}, task_id={self.task_id}, run_id={self.run_id})"
        )


@dataclass(frozen=True)
class Completion:
    """一次调用的结果：正文 + callId + 用量（便于调用方记日志，不含密钥）。"""

    text: str
    call_id: str
    input_tokens: int
    output_tokens: int


class ModelKernel:
    """共享的模型调用入口；生命周期跟随 app。"""

    def __init__(
        self,
        *,
        leases: LeaseCache,
        usage: UsageReporter,
        runtime: ModelRuntime | None = None,
    ) -> None:
        self._leases: Final[LeaseCache] = leases
        self._usage: Final[UsageReporter] = usage
        self._runtime: Final[ModelRuntime] = runtime or ModelRuntime()

    @property
    def runtime(self) -> ModelRuntime:
        return self._runtime

    async def lease(self, scope: CallScope) -> LeaseHandle:
        """取 lease（调用方负责 ``release``）；Java 拒绝统一映射成不含细节的 :class:`AppError`。"""

        try:
            return await self._leases.acquire(
                scope.lease_request(), bearer_token=scope.bearer_token
            )
        except JavaInternalError as exc:
            raise _lease_error(exc) from None

    async def complete(
        self,
        scope: CallScope,
        *,
        system_prompt: str,
        user_prompt: str,
    ) -> Completion:
        """system + user 两段 prompt 的一次非流式调用，返回纯文本。

        本次调用全程持有 lease 的脱敏登记（含用量提交与客户端关闭），结束时注销本次登记；
        期间即使缓存淘汰或作废了这个 lease，日志里也照样搜不到密钥。
        """

        messages: Sequence[BaseMessage] = (
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_prompt),
        )
        async with await self.lease(scope) as lease:
            return await self._complete(scope, lease, messages)

    async def _complete(
        self, scope: CallScope, lease: Lease, messages: Sequence[BaseMessage]
    ) -> Completion:
        built = build_chat_model(lease, self._runtime)
        callback = UsageCallback()
        status: str | None = None
        failure: BaseException | None = None
        try:
            message = await built.model.ainvoke(list(messages), config={"callbacks": [callback]})
        except asyncio.CancelledError as exc:
            status, failure = USAGE_CANCELED, exc
            raise
        except Exception as exc:
            failure = exc
            if classify(exc).reason == REASON_AUTH:
                self._leases.invalidate(scope.lease_request())
            raise to_app_error(exc) from None
        finally:
            self._usage.submit(
                callback.to_item(
                    lease_id=str(lease.lease_id),
                    purpose=scope.purpose.value,
                    model=lease.model,
                    status=status,
                    error=failure,
                ),
                bearer_token=scope.bearer_token,
            )
            await built.aclose()
        return Completion(
            text=message.text,
            call_id=callback.call_id,
            input_tokens=callback.input_tokens,
            output_tokens=callback.output_tokens,
        )


def _lease_error(exc: JavaInternalError) -> AppError:
    if exc.status == 403:
        return AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "凭据租借被拒绝",
            status_code=503,
            retryable=False,
            details={"reason": "lease_forbidden"},
        )
    if exc.status == 401:
        return AppError(
            ErrorCode.INTERNAL_AUTH_FAILED,
            "凭据租借鉴权失败",
            status_code=401,
            retryable=False,
            details={"reason": "lease_unauthorized"},
        )
    if exc.reason == "timeout":
        return AppError(
            ErrorCode.DEADLINE_EXCEEDED,
            "凭据租借超时",
            status_code=504,
            retryable=True,
            details={"reason": "lease_timeout"},
        )
    return AppError(
        ErrorCode.MODEL_UNAVAILABLE,
        "凭据租借不可用",
        status_code=503,
        retryable=exc.retryable or exc.status == 503,
        details={"reason": "lease_unavailable"},
    )


__all__ = ["CallScope", "Completion", "ModelKernel"]
