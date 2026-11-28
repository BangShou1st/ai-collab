"""``ModelKernel.complete_stream`` 的行为测试（P3 契约 §2.1 行 8、§8.2.2）。

用录制夹具（``tests/fixtures/providers/openai_compatible/text_stream.sse``）走真实
LangChain 适配器 + MockTransport，验证：增量回调序、全文拼接、usage 聚合、
中止（StreamAbortedError → CANCELED 用量）、provider 失败映射。
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx2
import pytest
from pydantic import SecretStr

from ai_collab_worker.contract.common import AppError
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import (
    CallScope,
    ModelKernel,
    StreamAbortedError,
)
from ai_collab_worker.models.lease import (
    Lease,
    LeaseCache,
    LeaseProtocol,
    LeasePurpose,
    LeaseRequest,
)
from ai_collab_worker.models.usage import UsageReporter

FIXTURES: Any = Path(__file__).resolve().parents[1] / "fixtures" / "providers"


def _stream_transport(name: str = "openai_compatible") -> httpx2.MockTransport:
    def handler(_request: httpx2.Request) -> httpx2.Response:
        body = (FIXTURES / name / "text_stream.sse").read_text(encoding="utf-8")
        return httpx2.Response(
            200,
            content=body.encode(),
            headers={"content-type": "text/event-stream"},
        )

    return httpx2.MockTransport(handler)


def _error_transport(status: int) -> httpx2.MockTransport:
    def handler(_request: httpx2.Request) -> httpx2.Response:
        body = (FIXTURES / "openai_compatible" / f"error_{status}.json").read_text(encoding="utf-8")
        return httpx2.Response(
            status, content=body.encode(), headers={"content-type": "application/json"}
        )

    return httpx2.MockTransport(handler)


def _kernel(
    transport: httpx2.AsyncBaseTransport,
) -> tuple[ModelKernel, list[dict[str, object]], Lease, UsageReporter]:
    submitted: list[dict[str, object]] = []
    lease = Lease(
        lease_id=uuid.UUID("00000000-0000-4000-8000-000000000001"),
        protocol=LeaseProtocol.OPENAI_CHAT,
        provider_type="OPENAI_COMPATIBLE",
        base_url="http://provider.test",
        api_path=None,
        model="qwen2.5:7b",
        api_key=SecretStr("secret-key"),
        expires_at=datetime.now(UTC) + timedelta(minutes=10),
    )

    async def fetch_lease(_req: LeaseRequest, _token: str) -> Lease:
        return lease

    async def send_usage(items: Any, _token: str) -> dict[str, bool]:
        for entry in items:
            submitted.append(dict(entry))
        return {str(entry["callId"]): True for entry in items}

    cache = LeaseCache(fetch_lease)
    reporter = UsageReporter(send_usage, max_batch=50, flush_interval=60.0)
    kernel = ModelKernel(
        leases=cache,
        usage=reporter,
        runtime=ModelRuntime(
            timeout_seconds=5.0,
            max_retries=0,
            retry_backoff_seconds=0.0,
            transport=transport,
        ),
    )
    return kernel, submitted, lease, reporter


def _scope() -> CallScope:
    return CallScope(
        purpose=LeasePurpose.KNOWLEDGE_CHAT,
        user_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        project_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        bearer_token="token",
    )


def test_complete_stream_yields_deltas_and_aggregates_usage() -> None:
    kernel, submitted, _lease, reporter = _kernel(_stream_transport())
    deltas: list[str] = []

    async def on_delta(text: str) -> None:
        deltas.append(text)

    async def main() -> Any:
        return await kernel.complete_stream(
            _scope(),
            system_prompt="sys",
            user_prompt="user",
            on_delta=on_delta,
        )

    result = asyncio.run(main())
    asyncio.run(reporter.flush())
    assert deltas == ["Hello", " world"]
    assert result.text == "Hello world"
    assert result.input_tokens == 12
    assert result.output_tokens == 3
    assert result.latency_ms >= 0
    assert len(submitted) == 1
    usage = submitted[0]
    assert usage["status"] == "SUCCESS"
    assert usage["inputTokens"] == 12
    assert usage["outputTokens"] == 3
    assert usage["purpose"] == "KNOWLEDGE_CHAT"


def test_complete_stream_abort_reports_canceled() -> None:
    kernel, submitted, _lease, reporter = _kernel(_stream_transport())

    async def on_delta(text: str) -> None:
        if "world" in text:
            raise StreamAbortedError("blocked")

    async def main() -> Any:
        return await kernel.complete_stream(
            _scope(),
            system_prompt="sys",
            user_prompt="user",
            on_delta=on_delta,
        )

    with pytest.raises(StreamAbortedError):
        asyncio.run(main())
    asyncio.run(reporter.flush())
    assert len(submitted) == 1
    assert submitted[0]["status"] == "CANCELED"


def test_complete_stream_provider_error_maps_to_app_error() -> None:
    kernel, submitted, _lease, reporter = _kernel(_error_transport(500))

    async def main() -> Any:
        return await kernel.complete_stream(
            _scope(),
            system_prompt="sys",
            user_prompt="user",
            on_delta=lambda _text: None,
        )

    with pytest.raises(AppError) as exc_info:
        asyncio.run(main())
    assert isinstance(exc_info.value, AppError)
    asyncio.run(reporter.flush())
    assert len(submitted) == 1
    assert submitted[0]["status"] != "SUCCESS"
