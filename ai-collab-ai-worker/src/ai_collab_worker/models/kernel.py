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
import time
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Final

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from ai_collab_worker.clients.java_internal import JavaInternalError
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.errors import (
    REASON_AUTH,
    USAGE_CANCELED,
    USAGE_INVALID_OUTPUT,
    USAGE_SUCCESS,
    classify,
    to_app_error,
)
from ai_collab_worker.models.factory import (
    EmbeddingDimensionMismatchError,
    EmbeddingTaskType,
    ModelRuntime,
    RerankDocument,
    build_chat_model,
    call_embeddings,
    call_rerank,
)
from ai_collab_worker.models.lease import (
    Lease,
    LeaseCache,
    LeaseHandle,
    LeasePurpose,
    LeaseRequest,
)
from ai_collab_worker.models.usage import UsageCallback, UsageItem, UsageReporter


@dataclass(frozen=True)
class CallScope:
    """一次模型调用的归属：决定 lease 键与用量归属。"""

    purpose: LeasePurpose
    user_id: uuid.UUID | None
    project_id: uuid.UUID | None
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


@dataclass(frozen=True)
class EmbeddingResult:
    """一次（可分批）向量化调用的结果与用量摘要。"""

    vectors: tuple[tuple[float, ...], ...]
    dimension: int
    model: str
    call_id: str
    input_tokens: int
    latency_ms: int


@dataclass(frozen=True)
class RerankResult:
    """一次重排调用结果：``scores`` 覆盖融合前 ``min(n, max_candidates)`` 条，按输入下标对齐。"""

    scores: tuple[float, ...]
    max_candidates: int
    model: str
    call_id: str
    latency_ms: int


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

    async def embed_texts(
        self,
        scope: CallScope,
        texts: Sequence[str],
        *,
        task_type: EmbeddingTaskType,
        expected_dimension: int | None = None,
        on_lease: Callable[[Lease], None] | None = None,
    ) -> EmbeddingResult:
        """租借 ``EMBEDDING`` → 可选预检 lease（模型/维度） → 批量向量化 → 全量上报用量。"""

        async with await self.lease(scope) as lease:
            if on_lease is not None:
                on_lease(lease)
            return await self.embed_with_lease(
                scope,
                lease,
                texts,
                task_type=task_type,
                expected_dimension=expected_dimension,
            )

    async def embed_with_lease(
        self,
        scope: CallScope,
        lease: Lease,
        texts: Sequence[str],
        *,
        task_type: EmbeddingTaskType,
        expected_dimension: int | None = None,
    ) -> EmbeddingResult:
        """使用已持有的 ``EMBEDDING`` lease 执行向量化并记录用量（成功/失败/取消全上报）。"""

        call_id = str(uuid.uuid4())
        started = time.monotonic()
        status: str | None = None
        failure: BaseException | None = None
        input_tokens = 0
        try:
            output = await call_embeddings(
                lease,
                texts,
                task_type=task_type,
                expected_dimension=expected_dimension,
                runtime=self._runtime,
            )
            input_tokens = output.input_tokens
            status = USAGE_SUCCESS
        except asyncio.CancelledError as exc:
            status, failure = USAGE_CANCELED, exc
            raise
        except EmbeddingDimensionMismatchError as exc:
            input_tokens = exc.input_tokens
            status, failure = USAGE_INVALID_OUTPUT, exc
            raise
        except Exception as exc:
            failure = exc
            if classify(exc).reason == REASON_AUTH:
                self._leases.invalidate(scope.lease_request())
            raise to_app_error(exc) from None
        finally:
            latency_ms = max(0, int((time.monotonic() - started) * 1000))
            self._submit_usage(
                call_id=call_id,
                lease=lease,
                scope=scope,
                input_tokens=input_tokens,
                latency_ms=latency_ms,
                status=status,
                failure=failure,
            )
        return EmbeddingResult(
            vectors=output.vectors,
            dimension=output.dimension,
            model=lease.model,
            call_id=call_id,
            input_tokens=input_tokens,
            latency_ms=latency_ms,
        )

    async def rerank(
        self,
        scope: CallScope,
        *,
        query: str,
        documents: Sequence[RerankDocument],
    ) -> RerankResult:
        """租借 ``RERANK`` → 截取前 ``min(n, maxCandidates)`` 调用重排 → 上报用量。"""

        async with await self.lease(scope) as lease:
            max_candidates = lease.params.max_candidates or len(documents)
            head = list(documents[:max_candidates])
            call_id = str(uuid.uuid4())
            started = time.monotonic()
            status: str | None = None
            failure: BaseException | None = None
            try:
                scores = await call_rerank(
                    lease, query=query, documents=head, runtime=self._runtime
                )
                status = USAGE_SUCCESS
            except asyncio.CancelledError as exc:
                status, failure = USAGE_CANCELED, exc
                raise
            except Exception as exc:
                failure = exc
                if classify(exc).reason == REASON_AUTH:
                    self._leases.invalidate(scope.lease_request())
                raise to_app_error(exc) from None
            finally:
                latency_ms = max(0, int((time.monotonic() - started) * 1000))
                self._submit_usage(
                    call_id=call_id,
                    lease=lease,
                    scope=scope,
                    input_tokens=0,
                    latency_ms=latency_ms,
                    status=status,
                    failure=failure,
                )
            return RerankResult(
                scores=scores,
                max_candidates=max_candidates,
                model=lease.model,
                call_id=call_id,
                latency_ms=latency_ms,
            )

    def _submit_usage(
        self,
        *,
        call_id: str,
        lease: Lease,
        scope: CallScope,
        input_tokens: int,
        latency_ms: int,
        status: str | None,
        failure: BaseException | None,
    ) -> None:
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
        self._usage.submit(
            UsageItem(
                call_id=call_id,
                lease_id=str(lease.lease_id),
                purpose=scope.purpose.value,
                model=lease.model,
                input_tokens=input_tokens,
                output_tokens=0,
                latency_ms=latency_ms,
                status=resolved,
                error_code=error_code,
            ),
            bearer_token=scope.bearer_token,
        )


#: 契约 R3：租借下发前生成参数越界（temperature/maxOutputTokens）时 Java 返回 503 + 此错误码；
#: 是配置问题，重试或重派都只会得到同样的拒绝。其余 503 仍是 ``AI_PROVIDER_UNAVAILABLE``（可重试）。
LEASE_PARAMS_INVALID_CODE: Final[str] = "AI_PROVIDER_PARAMS_INVALID"


def _lease_error(exc: JavaInternalError) -> AppError:
    if exc.status == 503 and exc.code == LEASE_PARAMS_INVALID_CODE:
        return AppError(
            ErrorCode.MODEL_UNAVAILABLE,
            "模型提供商参数配置不合格",
            status_code=503,
            retryable=False,
            details={"reason": "lease_params_invalid"},
        )
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


__all__ = ["CallScope", "Completion", "EmbeddingResult", "ModelKernel", "RerankResult"]
