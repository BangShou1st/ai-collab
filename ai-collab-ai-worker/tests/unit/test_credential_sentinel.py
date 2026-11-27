"""凭据哨兵测试（P1 契约 §2.5、§6 "凭据安全"）。

用一个高熵哨兵 apiKey 跑完整调用链：Java 租借（假 transport）→ :class:`ModelKernel` →
本地模型客户端（OpenAI / Anthropic / Gemini，夹具回放）→ 用量上报（假 sender），成功与报错
路径各跑一遍，然后断言：

- structlog 输出、标准 logging（``caplog`` 与一个写文件的 handler）都搜不到哨兵；
- 异常的 ``str`` / ``repr`` / traceback 文本里搜不到哨兵；
- ``tmp_path`` 下所有文件（含日志文件）里搜不到哨兵；
- ``pickle.dumps(lease)``、``json.dumps(lease)``、``lease.model_dump_json()``、``copy`` 都抛错；
  ``repr`` / ``str`` / ``model_dump`` 不出明文；
- 脱敏处理器同时覆盖键名规则与活跃密钥子串规则，``REDIS_URL`` 密码也在规则内。
"""

from __future__ import annotations

import asyncio
import copy
import io
import json
import logging
import pickle
import secrets
import traceback
import uuid
from collections.abc import Iterator, Mapping, Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import httpx2
import pytest
import structlog
from pydantic import ValidationError

from ai_collab_worker.clients.java_internal import JavaInternalClient
from ai_collab_worker.config import load_settings
from ai_collab_worker.contract.common import AppError
from ai_collab_worker.models.factory import ModelRuntime
from ai_collab_worker.models.kernel import CallScope, ModelKernel
from ai_collab_worker.models.lease import Lease, LeaseCache, LeasePurpose
from ai_collab_worker.models.usage import UsageReporter
from ai_collab_worker.observability.logging import configure_logging
from ai_collab_worker.security.redaction import (
    REDACTED,
    SECRETS,
    SecretRegistry,
    redact_text,
    redact_value,
    structlog_redactor,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "providers"
USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
TOKEN = "header.payload.signature-for-tests"

PROVIDERS = {
    "openai": ("OPENAI_CHAT", "https://api.openai.test", "/v1/chat/completions", "gpt-4o-mini"),
    "anthropic": ("ANTHROPIC_MESSAGES", "https://api.anthropic.test", "/v1/messages", "claude"),
    "gemini": (
        "GEMINI_GENERATE_CONTENT",
        "https://gemini.test",
        "/v1beta/models/gemini-2.5-flash:generateContent",
        "gemini-2.5-flash",
    ),
}


@pytest.fixture
def sentinel() -> Iterator[str]:
    value = f"sk-live-{secrets.token_urlsafe(32)}"
    yield value
    SECRETS.discard(value)


def _lease_payload(name: str, sentinel: str) -> dict[str, object]:
    protocol, base_url, api_path, model = PROVIDERS[name]
    return {
        "leaseId": str(uuid.uuid4()),
        "protocol": protocol,
        "providerType": name.upper(),
        "baseUrl": base_url,
        "apiPath": api_path,
        "model": model,
        "apiKey": sentinel,
        "params": {"temperature": 0.1, "maxOutputTokens": 64},
        "capabilities": ["STREAMING", "TOOLS", "JSON"],
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
    }


def _provider_transport(name: str, sentinel: str, scenario: str) -> httpx2.MockTransport:
    def handler(request: httpx2.Request) -> httpx2.Response:
        file = "text.json" if scenario == "ok" else f"{scenario}.json"
        body = (FIXTURES / name / file).read_text(encoding="utf-8")
        # 厂商在错误体里回显密钥：最坏情况。
        body = body.replace("{{API_KEY}}", sentinel)
        status = 200 if scenario == "ok" else int(scenario.removeprefix("error_"))
        return httpx2.Response(
            status, content=body.encode(), headers={"content-type": "application/json"}
        )

    return httpx2.MockTransport(handler)


def _java_transport(name: str, sentinel: str) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/internal/ai/credentials/lease":
            return httpx.Response(
                200, json=_lease_payload(name, sentinel), headers={"cache-control": "no-store"}
            )
        if request.url.path == "/internal/ai/usage":
            items = json.loads(request.content)["items"]
            return httpx.Response(
                200, json={"results": [{"callId": i["callId"], "accepted": True} for i in items]}
            )
        return httpx.Response(404)

    return httpx.MockTransport(handler)


async def _run_chain(name: str, sentinel: str, scenario: str) -> BaseException | None:
    java = JavaInternalClient(
        base_url="http://java.test", transport=_java_transport(name, sentinel)
    )
    leases = LeaseCache(lambda request, token: java.lease(request, bearer_token=token))
    sent: list[Sequence[Mapping[str, object]]] = []

    async def sender(items: Sequence[Mapping[str, object]], token: str) -> dict[str, bool]:
        sent.append(items)
        results = await java.report_usage(items, bearer_token=token)
        return {r.call_id: r.accepted for r in results}

    usage = UsageReporter(sender)
    kernel = ModelKernel(
        leases=leases,
        usage=usage,
        runtime=ModelRuntime(
            timeout_seconds=5,
            max_retries=0,
            transport=_provider_transport(name, sentinel, scenario),
        ),
    )
    scope = CallScope(
        purpose=LeasePurpose.MEMORY_EXTRACTION,
        user_id=USER_ID,
        project_id=PROJECT_ID,
        bearer_token=TOKEN,
    )
    failure: BaseException | None = None
    try:
        completion = await kernel.complete(scope, system_prompt="sys", user_prompt="usr")
        logging.getLogger("test.chain").info("completion %s", completion)
    except AppError as exc:
        failure = exc
        logging.getLogger("test.chain").exception("model call failed")
        structlog.get_logger("test.chain").error("model call failed", error=str(exc), exc_info=exc)
    finally:
        await usage.aclose()
        leases.clear()
        await java.aclose()
    assert sent, "用量必须被上报（成功与失败都要）"
    assert all(sentinel not in json.dumps(list(batch)) for batch in sent)
    return failure


@pytest.mark.parametrize("scenario", ["ok", "error_401", "error_429", "error_500"])
@pytest.mark.parametrize("name", sorted(PROVIDERS))
def test_sentinel_never_leaks_through_full_chain(
    name: str,
    scenario: str,
    sentinel: str,
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging("DEBUG")
    log_file = tmp_path / "worker.log"
    file_handler = logging.FileHandler(log_file, encoding="utf-8")
    file_handler.setFormatter(logging.Formatter("%(levelname)s %(name)s %(message)s"))
    root = logging.getLogger()
    root.addHandler(file_handler)
    root.setLevel(logging.DEBUG)
    caplog.set_level(logging.DEBUG)
    try:
        failure = asyncio.run(_run_chain(name, sentinel, scenario))
    finally:
        root.removeHandler(file_handler)
        file_handler.close()

    if scenario == "ok":
        assert failure is None
    else:
        assert isinstance(failure, AppError)
        rendered = "".join(traceback.format_exception(failure))
        for text in (str(failure), repr(failure), rendered, json.dumps(failure.details)):
            assert sentinel not in text
        assert failure.__cause__ is None
        assert failure.__suppress_context__

    captured = capsys.readouterr()
    assert sentinel not in captured.out
    assert sentinel not in captured.err
    assert sentinel not in caplog.text
    for record in caplog.records:
        assert sentinel not in record.getMessage()
        assert sentinel not in str(record.__dict__)
    for path in tmp_path.rglob("*"):
        if path.is_file():
            assert sentinel.encode() not in path.read_bytes(), path.name


# --------------------------------------------------------------------------- #
# Lease 本身
# --------------------------------------------------------------------------- #


def test_lease_refuses_every_serialization_path(sentinel: str) -> None:
    lease = Lease.model_validate(_lease_payload("openai", sentinel))

    with pytest.raises(TypeError):
        pickle.dumps(lease)
    with pytest.raises(TypeError):
        json.dumps(lease)
    with pytest.raises(TypeError):
        lease.model_dump_json()
    with pytest.raises(TypeError):
        copy.copy(lease)
    with pytest.raises(TypeError):
        copy.deepcopy(lease)

    for text in (repr(lease), str(lease), str(lease.model_dump()), str(lease.api_key)):
        assert sentinel not in text
    assert lease.reveal_key() == sentinel


def test_lease_validation_error_never_echoes_key(sentinel: str) -> None:
    payload = _lease_payload("openai", sentinel)
    payload["baseUrl"] = "ftp://bad"
    with pytest.raises(ValidationError) as excinfo:
        Lease.model_validate(payload)
    assert sentinel not in str(excinfo.value)


def _request() -> Any:
    from ai_collab_worker.models.lease import LeaseRequest

    return LeaseRequest(purpose=LeasePurpose.QUERY_PLAN, user_id=USER_ID, project_id=PROJECT_ID)


def _leaked(registry: SecretRegistry, sentinel: str) -> bool:
    return sentinel in redact_text(f"provider said: bad key {sentinel}", registry=registry)


def test_lease_cache_registers_and_releases_secret(sentinel: str) -> None:
    registry = SecretRegistry()

    async def fetch(_request: Any, _token: str) -> Lease:
        return Lease.model_validate(_lease_payload("openai", sentinel))

    async def main() -> None:
        cache = LeaseCache(fetch, registry=registry)
        handle = await cache.acquire(_request(), bearer_token=TOKEN)
        assert sentinel in registry
        assert f"key={REDACTED}" == redact_text(f"key={sentinel}", registry=registry)
        handle.release()
        assert sentinel in registry  # 缓存仍持有一份
        cache.clear()
        assert sentinel not in registry

    asyncio.run(main())


def test_cache_eviction_keeps_secret_registered_while_call_in_flight(sentinel: str) -> None:
    """缓存淘汰/作废不能把仍在使用的密钥提前注销；调用结束后才注销（审查打回项 2）。"""

    registry = SecretRegistry()
    now = [datetime.now(UTC)]

    async def fetch(_request: Any, _token: str) -> Lease:
        return Lease.model_validate(_lease_payload("openai", sentinel))  # 5 分钟有效

    async def main() -> None:
        cache = LeaseCache(fetch, registry=registry, clock=lambda: now[0])
        handle = await cache.acquire(_request(), bearer_token=TOKEN)

        # 鉴权失败作废、过期淘汰、清空：调用仍在进行，日志里依然看不到密钥。
        cache.invalidate(_request())
        assert len(cache) == 0
        assert not _leaked(registry, sentinel)
        cache.clear()
        assert not _leaked(registry, sentinel)

        handle.release()
        assert sentinel not in registry
        handle.release()  # 幂等：不会多减别人的引用
        assert sentinel not in registry

    asyncio.run(main())


def test_expiry_eviction_while_call_in_flight_then_release(sentinel: str) -> None:
    registry = SecretRegistry()
    now = [datetime.now(UTC)]

    async def fetch(_request: Any, _token: str) -> Lease:
        return Lease.model_validate(_lease_payload("openai", sentinel))

    async def main() -> None:
        cache = LeaseCache(fetch, registry=registry, clock=lambda: now[0])
        first = await cache.acquire(_request(), bearer_token=TOKEN)
        # 时间推进到 expiresAt−30s 之后：下一次 acquire 淘汰旧条目并重新租借（同一把 key）。
        now[0] += timedelta(minutes=10)
        second = await cache.acquire(_request(), bearer_token=TOKEN)
        assert not _leaked(registry, sentinel)
        first.release()
        assert not _leaked(registry, sentinel)  # second 仍在用
        second.release()
        # 第二次租到的 lease 在新时钟下已不新鲜，不进缓存，因此释放后没有任何残留登记。
        assert sentinel not in registry

    asyncio.run(main())


def test_unregistering_one_secret_does_not_unmask_others(sentinel: str) -> None:
    """注销之后不误伤：另一把仍在使用的密钥照样脱敏，无关文本原样保留。"""

    registry = SecretRegistry()
    other = f"sk-live-{secrets.token_urlsafe(32)}"
    payloads = iter([_lease_payload("openai", sentinel), _lease_payload("openai", other)])

    async def fetch(_request: Any, _token: str) -> Lease:
        return Lease.model_validate(next(payloads))

    async def main() -> None:
        from ai_collab_worker.models.lease import LeaseRequest

        cache = LeaseCache(fetch, registry=registry)
        a = await cache.acquire(_request(), bearer_token=TOKEN)
        b = await cache.acquire(
            LeaseRequest(
                purpose=LeasePurpose.MEMORY_EXTRACTION, user_id=USER_ID, project_id=PROJECT_ID
            ),
            bearer_token=TOKEN,
        )
        a.release()
        cache.invalidate(_request())
        assert sentinel not in registry
        assert not _leaked(registry, other)
        assert redact_text("plain text stays", registry=registry) == "plain text stays"
        b.release()
        cache.clear()
        assert len(registry) == 0

    asyncio.run(main())


def test_short_lived_lease_is_released_after_call(sentinel: str) -> None:
    """剩余不足 30s 的 lease 不进缓存；调用期间登记，结束后注销，不永久残留。"""

    registry = SecretRegistry()

    async def fetch(_request: Any, _token: str) -> Lease:
        payload = _lease_payload("openai", sentinel)
        payload["expiresAt"] = (datetime.now(UTC) + timedelta(seconds=10)).isoformat()
        return Lease.model_validate(payload)

    async def main() -> None:
        cache = LeaseCache(fetch, registry=registry)
        async with await cache.acquire(_request(), bearer_token=TOKEN) as lease:
            assert lease.reveal_key() == sentinel
            assert len(cache) == 0
            assert not _leaked(registry, sentinel)
        assert sentinel not in registry

    asyncio.run(main())


def test_lease_handle_refuses_pickle_and_hides_key(sentinel: str) -> None:
    registry = SecretRegistry()

    async def fetch(_request: Any, _token: str) -> Lease:
        return Lease.model_validate(_lease_payload("openai", sentinel))

    async def main() -> None:
        handle = await LeaseCache(fetch, registry=registry).acquire(_request(), bearer_token=TOKEN)
        assert sentinel not in repr(handle)
        with pytest.raises(TypeError):
            pickle.dumps(handle)
        handle.release()
        with pytest.raises(RuntimeError):
            _ = handle.lease

    asyncio.run(main())


# --------------------------------------------------------------------------- #
# 脱敏处理器
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "key",
    [
        "api_key",
        "apiKey",
        "API-KEY",
        "Authorization",
        "x-api-key",
        "X-Goog-Api-Key",
        "client_secret",
        "password",
        "access_token",
    ],
)
def test_key_name_rule_redacts_value(key: str) -> None:
    assert redact_value({key: "anything-at-all"}) == {key: REDACTED}


@pytest.mark.parametrize("key", ["inputTokens", "output_tokens", "max_tokens", "token_count"])
def test_token_counters_are_not_redacted(key: str) -> None:
    assert redact_value({key: 12}) == {key: 12}


def test_active_secret_substring_rule(sentinel: str) -> None:
    registry = SecretRegistry()
    registry.add(sentinel)
    nested = {"msg": f"failed calling https://x.test/?key={sentinel}", "list": [sentinel]}
    out = redact_value(nested, registry=registry)
    assert sentinel not in json.dumps(out)
    assert REDACTED in out["msg"]


def test_structlog_processor_redacts_both_rules(sentinel: str) -> None:
    SECRETS.add(sentinel)
    event = {"event": f"boom {sentinel}", "headers": {"x-api-key": "other"}, "n": 1}
    out = structlog_redactor(None, "info", dict(event))
    assert sentinel not in json.dumps(out)
    assert out["headers"] == {"x-api-key": REDACTED}
    assert out["n"] == 1


def test_structlog_pipeline_redacts_rendered_output(sentinel: str) -> None:
    configure_logging("DEBUG")
    SECRETS.add(sentinel)
    buffer = io.StringIO()
    structlog.configure(logger_factory=structlog.PrintLoggerFactory(file=buffer))
    try:
        structlog.get_logger().info("call", detail=f"auth={sentinel}", api_key=sentinel)
    finally:
        configure_logging("INFO")
    line = buffer.getvalue()
    assert sentinel not in line
    assert REDACTED in line


def test_stdlib_logging_is_redacted_at_record_creation(
    sentinel: str, caplog: pytest.LogCaptureFixture
) -> None:
    configure_logging("DEBUG")
    SECRETS.add(sentinel)
    caplog.set_level(logging.INFO)
    logger = logging.getLogger("test.redaction")
    logger.info("calling with %s", sentinel)
    try:
        raise RuntimeError(f"provider said {sentinel}")
    except RuntimeError:
        logger.exception("failed")
    assert sentinel not in caplog.text
    assert all(sentinel not in r.getMessage() for r in caplog.records)


def test_redis_url_password_is_registered_for_redaction() -> None:
    pw = f"redis-{secrets.token_hex(12)}"
    settings = load_settings({"REDIS_URL": f"redis://:{pw}@redis.test:6379/0"})
    assert settings.redis_password() == pw
    registry = SecretRegistry()
    registry.add(settings.redis_password() or "")
    assert pw not in redact_text(f"connect failed redis://:{pw}@redis.test", registry=registry)
    # URL userinfo 兜底规则：即便没登记也不会原样输出密码。
    assert pw not in redact_text(f"redis://:{pw}@redis.test:6379", registry=SecretRegistry())


def test_uvicorn_access_logger_formats_without_typeerror_and_redacts_sentinel(
    sentinel: str,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """uvicorn.access 使用 AccessFormatter 解包 5 元组 args，不能置 None 且必须完整脱敏。"""

    from uvicorn.logging import AccessFormatter

    configure_logging("DEBUG")
    SECRETS.add(sentinel)

    access_logger = logging.getLogger("uvicorn.access")
    saved_handlers = list(access_logger.handlers)
    saved_level = access_logger.level
    saved_propagate = access_logger.propagate

    buffer = io.StringIO()
    formatter = AccessFormatter(
        fmt='%(levelprefix)s %(client_addr)s - "%(request_line)s" %(status_code)s',
        use_colors=False,
    )
    stream_handler = logging.StreamHandler(buffer)
    stream_handler.setFormatter(formatter)

    records: list[logging.LogRecord] = []

    class _CaptureHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            records.append(record)

    capture_handler = _CaptureHandler()
    access_logger.handlers = [stream_handler, capture_handler]
    access_logger.setLevel(logging.INFO)
    access_logger.propagate = False
    try:
        access_logger.info(
            '%s - "%s %s HTTP/%s" %d',
            "127.0.0.1:54321",
            "GET",
            f"/v1/query-plan/{sentinel}?api_key={sentinel}",
            "1.1",
            200,
        )
    finally:
        access_logger.handlers = saved_handlers
        access_logger.setLevel(saved_level)
        access_logger.propagate = saved_propagate
        stream_handler.close()

    captured = capsys.readouterr()
    assert "TypeError" not in captured.err
    assert sentinel not in captured.err

    assert len(records) == 1
    record = records[0]
    assert isinstance(record.args, tuple)
    assert len(record.args) == 5

    # 直接调用 AccessFormatter.format() 确认不抛 TypeError。
    direct_formatted = formatter.format(record)
    stream_formatted = buffer.getvalue()
    rendered_message = record.getMessage()

    for output in (direct_formatted, stream_formatted, rendered_message):
        assert REDACTED in output
        assert sentinel not in output
        assert "200" in output


def test_uvicorn_access_logger_redacts_bearer_userinfo_and_plain_message(
    sentinel: str,
) -> None:
    from uvicorn.logging import AccessFormatter

    configure_logging("DEBUG")
    access_logger = logging.getLogger("uvicorn.access")
    saved_handlers = list(access_logger.handlers)
    saved_level = access_logger.level
    saved_propagate = access_logger.propagate

    records: list[logging.LogRecord] = []

    class _CaptureHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            records.append(record)

    formatter = AccessFormatter(
        fmt='%(levelprefix)s %(client_addr)s - "%(request_line)s" %(status_code)s',
        use_colors=False,
    )
    access_logger.handlers = [_CaptureHandler()]
    access_logger.setLevel(logging.INFO)
    access_logger.propagate = False
    try:
        access_logger.info(
            '%s - "%s %s HTTP/%s" %d',
            "127.0.0.1:54321",
            "POST",
            f"/v1/proxy?auth=Bearer {sentinel}&target=redis://:pw-{sentinel}@redis:6379",
            "1.1",
            401,
        )
        access_logger.info("Bearer %s", sentinel)
    finally:
        access_logger.handlers = saved_handlers
        access_logger.setLevel(saved_level)
        access_logger.propagate = saved_propagate

    assert len(records) == 2
    formatted = formatter.format(records[0])
    assert REDACTED in formatted
    assert sentinel not in formatted
    assert sentinel not in records[0].getMessage()

    assert REDACTED in records[1].getMessage()
    assert sentinel not in records[1].getMessage()
