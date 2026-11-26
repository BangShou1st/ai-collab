"""凭据租借失败的重试分类（契约 R3：503 ``AI_PROVIDER_PARAMS_INVALID`` 不可重试）。

Java 在下发前校验生成参数（temperature/maxOutputTokens），越界时返回 HTTP 503 +
``{"code":"AI_PROVIDER_PARAMS_INVALID","retryable":false}``：这是提供商配置问题，重试或重派
都只会得到同样的拒绝。其余 503 仍是 ``AI_PROVIDER_UNAVAILABLE`` + ``retryable:true``；响应体缺
``code`` 或不是 JSON 时按原来的 503 处理（可重试）。

Java 侧用 ``httpx.MockTransport`` 注入，不连网络、不起 Redis。
"""

from __future__ import annotations

import asyncio
import base64
import json
import uuid
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

import httpx
import pytest

from ai_collab_worker.clients.java_internal import JavaInternalClient
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.models.kernel import LEASE_PARAMS_INVALID_CODE, CallScope, ModelKernel
from ai_collab_worker.models.lease import LeaseCache, LeasePurpose
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.tasks.memory import MemoryExtractionHandler
from ai_collab_worker.tasks.stream import TaskContext

PROMPTS_DIR = Path(__file__).resolve().parents[2] / "prompts"
JAVA_URL = "http://java.test"
LEASE_PATH = "/internal/ai/credentials/lease"
USER_ID = "11111111-1111-4111-8111-111111111111"
PROJECT_ID = "22222222-2222-4222-8222-222222222222"
TOKEN = "eyJhbGciOiJFZERTQSJ9.e30.sig"

Responder = Callable[[], httpx.Response]


def _json_503(body: Mapping[str, object]) -> Responder:
    return lambda: httpx.Response(503, json=dict(body))


PARAMS_INVALID = _json_503(
    {"code": LEASE_PARAMS_INVALID_CODE, "message": "参数越界", "retryable": False}
)
PROVIDER_UNAVAILABLE = _json_503(
    {"code": "AI_PROVIDER_UNAVAILABLE", "message": "暂不可用", "retryable": True}
)


class FakeJava:
    """只实现租借与快照两个端点；记录租借次数。"""

    def __init__(self, lease: Responder, snapshot: Mapping[str, object] | None = None) -> None:
        self._lease = lease
        self._snapshot = snapshot
        self.lease_calls = 0
        self.paths: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.paths.append(request.url.path)
        if request.url.path == LEASE_PATH:
            self.lease_calls += 1
            return self._lease()
        if request.url.path.endswith("/snapshot") and self._snapshot is not None:
            return httpx.Response(200, json=dict(self._snapshot))
        return httpx.Response(404, json={"code": "NOT_FOUND"})

    def client(self) -> JavaInternalClient:
        return JavaInternalClient(base_url=JAVA_URL, transport=httpx.MockTransport(self.handler))


async def _no_usage(items: Sequence[Mapping[str, object]], token: str) -> dict[str, bool]:
    return {str(i["callId"]): True for i in items}


def _kernel(client: JavaInternalClient) -> tuple[ModelKernel, UsageReporter]:
    usage = UsageReporter(_no_usage)
    leases = LeaseCache(lambda request, token: client.lease(request, bearer_token=token))
    return ModelKernel(leases=leases, usage=usage), usage


def _lease_failure(responder: Responder) -> tuple[AppError, int]:
    async def main() -> tuple[AppError, int]:
        java = FakeJava(responder)
        client = java.client()
        kernel, usage = _kernel(client)
        scope = CallScope(
            purpose=LeasePurpose.QUERY_PLAN,
            user_id=uuid.UUID(USER_ID),
            project_id=uuid.UUID(PROJECT_ID),
            bearer_token=TOKEN,
        )
        try:
            with pytest.raises(AppError) as excinfo:
                await kernel.lease(scope)
        finally:
            await usage.aclose()
            await client.aclose()
        return excinfo.value, java.lease_calls

    return asyncio.run(main())


def test_params_invalid_503_is_not_retryable() -> None:
    error, calls = _lease_failure(PARAMS_INVALID)
    assert error.retryable is False
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.status_code == 503
    assert error.details == {"reason": "lease_params_invalid"}
    assert calls == 1  # 内核不对租借做重试


def test_provider_unavailable_503_stays_retryable() -> None:
    error, _calls = _lease_failure(PROVIDER_UNAVAILABLE)
    assert error.retryable is True
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert error.details == {"reason": "lease_unavailable"}


@pytest.mark.parametrize(
    "responder",
    [
        _json_503({"message": "no code", "retryable": False}),
        _json_503({"code": "lower_case_not_a_code"}),
        lambda: httpx.Response(503, content=b"<html>Service Unavailable</html>"),
        lambda: httpx.Response(503),
    ],
    ids=["json-without-code", "malformed-code", "html-body", "empty-body"],
)
def test_503_without_recognised_code_keeps_old_retryable_behaviour(responder: Responder) -> None:
    """只认错误码；响应体里的 ``retryable`` 字段不参与判断（契约 §208：其余只按 HTTP 状态）。"""

    error, _calls = _lease_failure(responder)
    assert error.retryable is True
    assert error.details == {"reason": "lease_unavailable"}


def test_params_invalid_code_on_other_status_is_not_special_cased() -> None:
    """新错误码只在 503 上生效；其他状态仍按原有分类（这里 500 → 可重试）。"""

    error, _calls = _lease_failure(
        lambda: httpx.Response(500, json={"code": LEASE_PARAMS_INVALID_CODE})
    )
    assert error.retryable is True
    assert error.details == {"reason": "lease_unavailable"}


# --------------------------------------------------------------------------- #
# 记忆抽取：租借参数越界 → 处理器抛不可重试的 AppError，消费框架据此上报 fail(retryable=false)
# --------------------------------------------------------------------------- #


def _task_token(task_id: str) -> str:
    payload = {"uid": USER_ID, "pid": PROJECT_ID, "tid": task_id, "ep": 0}
    raw = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
    return f"eyJhbGciOiJFZERTQSJ9.{raw}.sig"


def _snapshot(task_id: str) -> dict[str, Any]:
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


@pytest.mark.parametrize(
    ("responder", "retryable"),
    [(PARAMS_INVALID, False), (PROVIDER_UNAVAILABLE, True)],
    ids=["params-invalid", "provider-unavailable"],
)
def test_memory_extraction_lease_failure_retryability(
    responder: Responder, *, retryable: bool
) -> None:
    task_id = str(uuid.uuid4())

    async def main() -> tuple[AppError, FakeJava]:
        java = FakeJava(responder, snapshot=_snapshot(task_id))
        client = java.client()
        kernel, usage = _kernel(client)
        handler = MemoryExtractionHandler(
            settings=Settings(prompts_dir=PROMPTS_DIR),
            java=client,
            kernel=kernel,
            prompt_store=PromptStore(PROMPTS_DIR),
        )
        context = TaskContext(
            task_id=task_id,
            task_type="MEMORY_EXTRACTION",
            epoch=0,
            lease_token="lt",
            token=_task_token(task_id),
        )
        try:
            with pytest.raises(AppError) as excinfo:
                await handler(context)
        finally:
            await usage.aclose()
            await client.aclose()
        return excinfo.value, java

    error, java = asyncio.run(main())
    # 消费框架把处理器抛出的 AppError 原样转成 fail(code, retryable)（tasks/stream.py）。
    assert error.retryable is retryable
    assert error.code is ErrorCode.MODEL_UNAVAILABLE
    assert java.lease_calls == 1
    assert any(path.endswith("/snapshot") for path in java.paths)
