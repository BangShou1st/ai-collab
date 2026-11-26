"""用量攒批上报（契约 §3）与 Java 内部接口客户端（契约 §2、§4.3）的单元测试。

替代 P1 删除的 ``test_java_gateway.py`` / ``test_java_jobs.py``：同样覆盖鉴权头透传、
超时/传输错误/4xx/5xx/非法 JSON 的分类、日志不含令牌与响应体，只是对象换成了新路径。
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta

import httpx
import pytest

from ai_collab_worker.clients.java_internal import (
    LEASE_PATH,
    TASKS_PATH,
    USAGE_PATH,
    JavaInternalClient,
    JavaInternalError,
)
from ai_collab_worker.models.lease import LeasePurpose, LeaseRequest
from ai_collab_worker.models.usage import UsageItem, UsageReporter
from ai_collab_worker.observability.metrics import REGISTRY

BASE = "http://java.test"
TOKEN = "eyJ.unit-test-token.sig"
TASK_ID = "33333333-3333-4333-8333-333333333333"
USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")


def _failures(purpose: str = "QUERY_PLAN") -> float:
    value = REGISTRY.get_sample_value(
        "ai_usage_report_failures_total", {"purpose": purpose, "caller_type": "PYTHON"}
    )
    return value or 0.0


def _item(call_id: str | None = None, purpose: str = "QUERY_PLAN") -> UsageItem:
    return UsageItem(
        call_id=call_id or str(uuid.uuid4()),
        lease_id=str(uuid.uuid4()),
        purpose=purpose,
        model="m",
        input_tokens=10,
        output_tokens=2,
        latency_ms=5,
        status="SUCCESS",
    )


# --------------------------------------------------------------------------- #
# UsageReporter
# --------------------------------------------------------------------------- #


class FakeSender:
    def __init__(
        self,
        fail_times: int = 0,
        reject: frozenset[str] = frozenset(),
        error: Exception | None = None,
    ) -> None:
        self.fail_times = fail_times
        self.reject = reject
        self.error = error or JavaInternalError("java down", status=503, retryable=True)
        self.batches: list[tuple[str, list[dict[str, object]]]] = []

    async def __call__(self, items: Sequence[Mapping[str, object]], token: str) -> dict[str, bool]:
        self.batches.append((token, [dict(i) for i in items]))
        if self.fail_times > 0:
            self.fail_times -= 1
            raise self.error
        return {str(i["callId"]): str(i["callId"]) not in self.reject for i in items}


def test_usage_flushes_at_batch_size_and_groups_by_token() -> None:
    async def main() -> FakeSender:
        sender = FakeSender()
        reporter = UsageReporter(sender, max_batch=3, flush_interval=60)
        reporter.start()
        for _ in range(3):
            reporter.submit(_item(), bearer_token="tok-a")
        reporter.submit(_item(), bearer_token="tok-b")
        await asyncio.sleep(0.05)  # 满批唤醒
        await reporter.aclose()
        return sender

    sender = asyncio.run(main())
    tokens = [token for token, _ in sender.batches]
    assert tokens[0] == "tok-a"
    assert len(sender.batches[0][1]) == 3
    assert "tok-b" in tokens
    assert set(sender.batches[0][1][0]) == {
        "callId",
        "leaseId",
        "purpose",
        "model",
        "inputTokens",
        "outputTokens",
        "latencyMs",
        "status",
        "errorCode",
    }


def test_usage_flushes_on_interval() -> None:
    async def main() -> FakeSender:
        sender = FakeSender()
        reporter = UsageReporter(sender, max_batch=50, flush_interval=0.05)
        reporter.start()
        reporter.submit(_item(), bearer_token="tok")
        await asyncio.sleep(0.2)
        assert reporter.pending == 0
        await reporter.aclose()
        return sender

    assert len(asyncio.run(main()).batches) == 1


def test_usage_retry_uses_same_call_id_and_counts_failures() -> None:
    before = _failures()

    async def main() -> FakeSender:
        clock = [0.0]
        sender = FakeSender(fail_times=2)
        reporter = UsageReporter(sender, backoff_base=1.0, clock=lambda: clock[0])
        reporter.submit(_item("call-fixed"), bearer_token="tok")
        await reporter.flush()  # 失败 1
        await reporter.flush()  # 退避未到：不发
        clock[0] += 1.0
        await reporter.flush()  # 失败 2
        clock[0] += 2.0
        await reporter.flush()  # 成功
        assert reporter.pending == 0
        return sender

    sender = asyncio.run(main())
    call_ids = [batch[0]["callId"] for _token, batch in sender.batches]
    assert call_ids == ["call-fixed", "call-fixed", "call-fixed"]
    assert _failures() == before + 2


def test_usage_gives_up_after_max_attempts() -> None:
    before = _failures("MEMORY_EXTRACTION")

    async def main() -> int:
        sender = FakeSender(fail_times=99)
        reporter = UsageReporter(sender, max_attempts=3, backoff_base=0, backoff_max=0)
        reporter.submit(_item(purpose="MEMORY_EXTRACTION"), bearer_token="tok")
        for _ in range(5):
            await reporter.flush()
        return len(sender.batches)

    assert asyncio.run(main()) == 3
    assert _failures("MEMORY_EXTRACTION") == before + 3


def test_usage_rejected_item_is_counted_not_retried() -> None:
    before = _failures()

    async def main() -> FakeSender:
        sender = FakeSender(reject=frozenset({"bad"}))
        reporter = UsageReporter(sender)
        reporter.submit(_item("bad"), bearer_token="tok")
        reporter.submit(_item("good"), bearer_token="tok")
        await reporter.flush()
        await reporter.flush()
        assert reporter.pending == 0
        return sender

    assert len(asyncio.run(main()).batches) == 1
    assert _failures() == before + 1


@pytest.mark.parametrize("status", [400, 401, 403, 409])
def test_usage_batch_non_retryable_status_is_counted_not_retried(status: int) -> None:
    """契约 §9.7：整批 400/401/403/409 → 不重试，每条计一次失败。"""

    before = _failures()

    async def main() -> FakeSender:
        sender = FakeSender(
            fail_times=99, error=JavaInternalError("rejected", status=status, retryable=False)
        )
        reporter = UsageReporter(sender, backoff_base=0, backoff_max=0)
        reporter.submit(_item("a"), bearer_token="tok")
        reporter.submit(_item("b"), bearer_token="tok")
        await reporter.flush()
        assert reporter.pending == 0
        await reporter.flush(ignore_backoff=True)
        await reporter.aclose()
        return sender

    assert len(asyncio.run(main()).batches) == 1
    assert _failures() == before + 2


@pytest.mark.parametrize(
    "error",
    [
        JavaInternalError("rate_limited", status=429, retryable=True),
        JavaInternalError("unavailable", status=500, retryable=True),
        JavaInternalError("unavailable", status=503, retryable=True),
        JavaInternalError("timeout", retryable=True),
        JavaInternalError("unavailable", retryable=True),
    ],
    ids=["429", "500", "503", "timeout", "transport"],
)
def test_usage_batch_retryable_failure_retries_with_same_call_id(error: Exception) -> None:
    """契约 §9.7：429/5xx/超时/传输错误 → 同一 callId 重试，直到成功。"""

    async def main() -> FakeSender:
        sender = FakeSender(fail_times=1, error=error)
        reporter = UsageReporter(sender, backoff_base=0, backoff_max=0)
        reporter.submit(_item("call-retry"), bearer_token="tok")
        await reporter.flush()
        assert reporter.pending == 1
        await reporter.flush()
        assert reporter.pending == 0
        return sender

    call_ids = [batch[0]["callId"] for _token, batch in asyncio.run(main()).batches]
    assert call_ids == ["call-retry", "call-retry"]


def test_usage_close_flushes_pending_ignoring_backoff() -> None:
    async def main() -> FakeSender:
        sender = FakeSender(fail_times=1)
        reporter = UsageReporter(sender, backoff_base=60)
        reporter.submit(_item(), bearer_token="tok")
        await reporter.flush()  # 失败，进入 60s 退避
        await reporter.aclose()  # 退出前尽力 flush：忽略退避
        return sender

    assert len(asyncio.run(main()).batches) == 2


# --------------------------------------------------------------------------- #
# JavaInternalClient
# --------------------------------------------------------------------------- #


def _client(handler: httpx.MockTransport) -> JavaInternalClient:
    return JavaInternalClient(base_url=BASE + "/", transport=handler)


def _lease_body() -> dict[str, object]:
    return {
        "leaseId": str(uuid.uuid4()),
        "protocol": "OPENAI_CHAT",
        "providerType": "OPENAI",
        "baseUrl": "https://api.openai.test",
        "apiPath": None,
        "model": "gpt",
        "apiKey": "sk-unit-LEAK",
        "params": {"temperature": 0.2, "maxOutputTokens": 4096},
        "capabilities": ["STREAMING"],
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
        "futureField": "ignored",
    }


def test_lease_request_shape_and_auth_header() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=_lease_body())

    async def main() -> None:
        client = _client(httpx.MockTransport(handler))
        lease = await client.lease(
            LeaseRequest(
                purpose=LeasePurpose.MEMORY_EXTRACTION,
                user_id=USER_ID,
                project_id=PROJECT_ID,
                task_id=uuid.UUID(TASK_ID),
            ),
            bearer_token=TOKEN,
        )
        await client.aclose()
        assert lease.model == "gpt"
        assert lease.params.max_output_tokens == 4096

    asyncio.run(main())
    request = seen[0]
    assert request.url.path == LEASE_PATH
    assert request.headers["authorization"] == f"Bearer {TOKEN}"
    assert json.loads(request.content) == {
        "purpose": "MEMORY_EXTRACTION",
        "userId": str(USER_ID),
        "projectId": str(PROJECT_ID),
        "taskId": TASK_ID,
        "runId": None,
    }


def test_task_callback_paths_and_bodies() -> None:
    seen: list[tuple[str, str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else None
        seen.append((request.method, request.url.path, body))
        path = request.url.path
        if path.endswith("/claim"):
            return httpx.Response(
                200, json={"leaseToken": "lt", "leaseExpiresAt": "x", "token": "t2"}
            )
        if path.endswith("/heartbeat"):
            return httpx.Response(200, json={"cancelRequested": True, "token": "t3"})
        if path.endswith("/snapshot"):
            return httpx.Response(200, json={"jobId": TASK_ID})
        return httpx.Response(200, content=b"")

    async def main() -> None:
        client = _client(httpx.MockTransport(handler))
        claim = await client.claim(
            TASK_ID, task_type="MEMORY_EXTRACTION", epoch=3, worker_id="w", bearer_token=TOKEN
        )
        assert (claim.lease_token, claim.token) == ("lt", "t2")
        beat = await client.heartbeat(TASK_ID, epoch=3, lease_token="lt", bearer_token="t2")
        assert beat.cancel_requested
        assert beat.token == "t3"
        assert await client.snapshot(TASK_ID, bearer_token="t3") == {"jobId": TASK_ID}
        await client.result(
            TASK_ID, epoch=3, lease_token="lt", payload={"candidates": []}, bearer_token="t3"
        )
        await client.fail(
            TASK_ID,
            epoch=3,
            lease_token="lt",
            error_code="CANCELED",
            retryable=False,
            bearer_token="t3",
        )
        await client.aclose()

    asyncio.run(main())
    prefix = f"{TASKS_PATH}/{TASK_ID}"
    assert seen == [
        ("POST", f"{prefix}/claim", {"taskType": "MEMORY_EXTRACTION", "epoch": 3, "workerId": "w"}),
        ("POST", f"{prefix}/heartbeat", {"epoch": 3, "leaseToken": "lt"}),
        ("GET", f"{prefix}/snapshot", None),
        (
            "POST",
            f"{prefix}/result",
            {"epoch": 3, "leaseToken": "lt", "payload": {"candidates": []}},
        ),
        (
            "POST",
            f"{prefix}/fail",
            {"epoch": 3, "leaseToken": "lt", "errorCode": "CANCELED", "retryable": False},
        ),
    ]


def test_usage_endpoint_parses_per_item_results() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == USAGE_PATH
        items = json.loads(request.content)["items"]
        return httpx.Response(
            200,
            json={
                "results": [
                    {"callId": items[0]["callId"], "accepted": True},
                    {"callId": items[1]["callId"], "accepted": False, "reason": "lease_mismatch"},
                ]
            },
        )

    async def main() -> None:
        client = _client(httpx.MockTransport(handler))
        results = await client.report_usage(
            [_item("a").to_wire(), _item("b").to_wire()], bearer_token=TOKEN
        )
        await client.aclose()
        assert [(r.call_id, r.accepted) for r in results] == [("a", True), ("b", False)]

    asyncio.run(main())


@pytest.mark.parametrize(
    ("status", "reason", "retryable"),
    [
        (401, "unauthorized", False),
        (403, "forbidden", False),
        (404, "not_found", False),
        (409, "conflict", False),
        (429, "rate_limited", True),
        (400, "rejected", False),
        (500, "unavailable", True),
        (503, "unavailable", True),
    ],
)
def test_http_status_classification(status: int, reason: str, retryable: bool) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"code": "TASK_CLAIM_CONFLICT", "message": TOKEN})

    async def main() -> JavaInternalError:
        client = _client(httpx.MockTransport(handler))
        try:
            await client.claim(TASK_ID, task_type="T", epoch=1, worker_id="w", bearer_token=TOKEN)
        except JavaInternalError as exc:
            return exc
        finally:
            await client.aclose()
        raise AssertionError

    exc = asyncio.run(main())
    assert (exc.status, exc.reason, exc.retryable) == (status, reason, retryable)
    assert exc.code == "TASK_CLAIM_CONFLICT"
    assert TOKEN not in str(exc)


@pytest.mark.parametrize(
    ("error", "reason"),
    [
        (httpx.ReadTimeout("slow"), "timeout"),
        (httpx.ConnectError("refused"), "unavailable"),
    ],
)
def test_transport_errors_are_retryable(error: Exception, reason: str) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise error

    async def main() -> JavaInternalError:
        client = _client(httpx.MockTransport(handler))
        try:
            await client.heartbeat(TASK_ID, epoch=1, lease_token="lt", bearer_token=TOKEN)
        except JavaInternalError as exc:
            return exc
        finally:
            await client.aclose()
        raise AssertionError

    exc = asyncio.run(main())
    assert (exc.status, exc.reason, exc.retryable) == (None, reason, True)
    assert exc.__cause__ is None


def test_invalid_json_and_contract_mismatch() -> None:
    responses = iter(
        [
            httpx.Response(200, content=b"not json"),
            httpx.Response(200, json={"leaseToken": ""}),
            httpx.Response(200, json={**_lease_body(), "protocol": "UNKNOWN"}),
        ]
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return next(responses)

    async def main() -> list[JavaInternalError]:
        client = _client(httpx.MockTransport(handler))
        errors: list[JavaInternalError] = []
        for call in (
            client.heartbeat(TASK_ID, epoch=1, lease_token="lt", bearer_token=TOKEN),
            client.claim(TASK_ID, task_type="T", epoch=1, worker_id="w", bearer_token=TOKEN),
            client.lease(
                LeaseRequest(
                    purpose=LeasePurpose.QUERY_PLAN, user_id=USER_ID, project_id=PROJECT_ID
                ),
                bearer_token=TOKEN,
            ),
        ):
            try:
                await call
            except JavaInternalError as exc:
                errors.append(exc)
        await client.aclose()
        return errors

    errors = asyncio.run(main())
    assert [e.reason for e in errors] == ["contract", "contract", "contract"]
    # lease 响应契约不符时不回显任何字段（含 apiKey）。
    assert all("sk-unit-LEAK" not in str(e) for e in errors)


def test_logs_never_contain_token_or_body(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text=f"boom {TOKEN} secret-body")

    async def main() -> None:
        client = _client(httpx.MockTransport(handler))
        with pytest.raises(JavaInternalError):
            await client.snapshot(TASK_ID, bearer_token=TOKEN)
        await client.aclose()

    asyncio.run(main())
    assert "kind=snapshot" in caplog.text
    assert TOKEN not in caplog.text
    assert "secret-body" not in caplog.text
