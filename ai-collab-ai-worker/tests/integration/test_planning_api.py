"""``POST /v1/planning/draft`` 集成测试（离线，respx 拦截 Java 网关）。

覆盖：成功契约、Prompt 渲染与 purpose=PLANNING、自校验 422 与固定 reason、deadline、
usage 缺失标 UNKNOWN、鉴权、指标、日志不泄漏 goal/项目描述/成员名单。
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

import httpx
import pytest
import respx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_collab_worker.config import Settings
from ai_collab_worker.main import create_app
from ai_collab_worker.observability.metrics import REGISTRY

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
GATEWAY_URL = "http://gateway.test"
TOKEN = "integration-planning-token"
MODEL_PATH = "/internal/ai/v1/model-calls"
MEMBER_A = "11111111-1111-4111-8111-111111111111"

VALID_CONTENT = json.dumps(
    {
        "plan": {
            "milestones": [
                {
                    "tempKey": "m1",
                    "title": "需求冻结",
                    "objective": "完成需求冻结。",
                    "description": "冻结本期范围。",
                    "targetDate": "2026-11-30",
                    "sourceRefs": ["fact:project"],
                }
            ],
            "tasks": [
                {
                    "tempKey": "t1",
                    "milestoneTempKey": "m1",
                    "title": "梳理范围",
                    "objective": "梳理迭代范围。",
                    "description": "输出范围清单与验收标准。",
                    "priority": "MEDIUM",
                    "estimatedHours": 8.0,
                    "startDate": "2026-11-20",
                    "dueDate": "2026-11-25",
                    "assigneeId": MEMBER_A,
                    "dependencyTempKeys": [],
                    "sourceRefs": ["member:" + MEMBER_A, "task:0"],
                    "sortOrder": 1,
                }
            ],
        },
        "warnings": ["成员未提供时任务未指派。"],
    },
    ensure_ascii=False,
)


def _app(**overrides: Any) -> FastAPI:
    settings = Settings(
        prompts_dir=PROMPTS_DIR,
        service_token=TOKEN,
        java_gateway_url=GATEWAY_URL,
        **overrides,
    )
    return create_app(settings)


def _body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "schemaVersion": 1,
        "goal": "为 Q4 迭代排出里程碑与任务。",
        "locale": "zh-CN",
        "projectFacts": {
            "projectName": "AI Collab",
            "description": "协作平台",
            "members": [{"id": MEMBER_A, "name": "张伟"}],
            "existingMilestoneTitles": ["需求冻结"],
            "existingTaskTitles": ["接口联调"],
        },
        "template": "ITERATION",
        "constraints": {
            "maxTasks": 5,
            "maxMilestones": 2,
            "startDate": "2026-11-20",
            "dueDate": "2026-12-31",
        },
        "deadlineMs": 30000,
    }
    body.update(overrides)
    return body


def _gateway_payload(
    content: str = VALID_CONTENT,
    *,
    usage: object = "default",
    latency: object = 90,
) -> dict[str, object]:
    payload: dict[str, object] = {"schemaVersion": 1, "content": content}
    if usage == "default":
        payload["usage"] = {"inputTokens": 1200, "outputTokens": 300, "source": "PROVIDER"}
    elif usage is not None:
        payload["usage"] = usage
    if latency is not None:
        payload["latencyMs"] = latency
    return payload


def _post(
    client: TestClient,
    body: dict[str, Any] | None = None,
    *,
    token: str | None = TOKEN,
    traceparent: str | None = None,
) -> httpx.Response:
    headers: dict[str, str] = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if traceparent is not None:
        headers["traceparent"] = traceparent
    return client.post("/v1/planning/draft", json=body or _body(), headers=headers)


def _metric(name: str, labels: dict[str, str] | None = None) -> float:
    value = REGISTRY.get_sample_value(name, labels)
    return 0.0 if value is None else value


@pytest.fixture
def app() -> FastAPI:
    return _app()


def test_successful_draft_returns_frozen_shape(app: FastAPI) -> None:
    dropped_before = _metric("ai_worker_planning_source_refs_dropped_total")
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, traceparent="00-trace-plan-01")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"schemaVersion", "plan", "warnings", "usage"}
    assert body["schemaVersion"] == 1
    assert set(body["plan"]) == {"milestones", "tasks"}
    assert body["plan"]["milestones"][0]["tempKey"] == "m1"
    assert body["plan"]["tasks"][0]["assigneeId"] == MEMBER_A
    assert body["warnings"] == ["成员未提供时任务未指派。"]
    assert body["usage"] == {"inputTokens": 1200, "outputTokens": 300, "source": "PROVIDER"}
    # sourceRefs 恒为空数组：模型给的 1 + 2 条全部丢弃并计数。
    assert body["plan"]["milestones"][0]["sourceRefs"] == []
    assert body["plan"]["tasks"][0]["sourceRefs"] == []
    assert _metric("ai_worker_planning_source_refs_dropped_total") == dropped_before + 3

    gateway_request = route.calls[0].request
    assert gateway_request.headers["authorization"] == f"Bearer {TOKEN}"
    assert gateway_request.headers["traceparent"] == "00-trace-plan-01"
    gateway_body = json.loads(gateway_request.content)
    assert gateway_body["purpose"] == "PLANNING"
    assert gateway_body["outputFormat"] == "PROMPT_JSON"
    assert 29_990 <= gateway_body["deadlineMs"] <= 30_000
    assert "为 Q4 迭代排出里程碑与任务。" in gateway_body["userPrompt"]
    assert "maxTasks" not in gateway_body["userPrompt"]  # 变量名不进 Prompt，值才进
    assert "1–5" in gateway_body["systemPrompt"]
    assert "1–2" in gateway_body["systemPrompt"]
    assert "张伟" in gateway_body["userPrompt"]
    assert "ALLOWED_SOURCE_REFS" not in gateway_body["userPrompt"]
    assert "member:" not in gateway_body["userPrompt"]
    assert "0.5–80" in gateway_body["systemPrompt"]


def test_assignee_outside_members_is_nulled_with_warning(app: FastAPI) -> None:
    content = json.loads(VALID_CONTENT)
    content["plan"]["tasks"][0]["assigneeId"] = "99999999-9999-4999-8999-999999999999"
    warnings_before = _metric("ai_worker_planning_draft_warnings_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(
                200, json=_gateway_payload(json.dumps(content, ensure_ascii=False))
            )
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["plan"]["tasks"][0]["assigneeId"] is None
    assert len(body["warnings"]) == 2
    assert _metric("ai_worker_planning_draft_warnings_total") == warnings_before + 2


def test_source_refs_are_always_empty_and_counted(app: FastAPI) -> None:
    content = json.loads(VALID_CONTENT)
    content["plan"]["tasks"][0]["sourceRefs"] = ["fact:project", "doc:42", "fact:unknown"]
    content["plan"]["milestones"][0]["sourceRefs"] = ["fact:unknown"]
    dropped_before = _metric("ai_worker_planning_source_refs_dropped_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(
                200, json=_gateway_payload(json.dumps(content, ensure_ascii=False))
            )
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["plan"]["tasks"][0]["sourceRefs"] == []
    assert body["plan"]["milestones"][0]["sourceRefs"] == []
    assert _metric("ai_worker_planning_source_refs_dropped_total") == dropped_before + 4


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda plan: plan["milestones"][0].pop("objective"), "草稿字段缺失、多余或类型/范围非法"),
        (lambda plan: plan["tasks"][0].pop("objective"), "草稿字段缺失、多余或类型/范围非法"),
        (lambda plan: plan["tasks"][0].pop("description"), "草稿字段缺失、多余或类型/范围非法"),
        (
            lambda plan: plan["tasks"][0].update({"description": "   "}),
            "必填文本（title/objective/description）不能为空白",
        ),
    ],
)
def test_missing_required_text_returns_422(app: FastAPI, mutate, reason: str) -> None:
    content = json.loads(VALID_CONTENT)
    mutate(content["plan"])
    violation_before = _metric("ai_worker_planning_draft_schema_violation_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(
                200, json=_gateway_payload(json.dumps(content, ensure_ascii=False))
            )
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert body["details"]["reason"] == reason
    assert _metric("ai_worker_planning_draft_schema_violation_total") == violation_before + 1


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (
            lambda plan: plan["tasks"].append(
                {
                    "tempKey": "t2",
                    "milestoneTempKey": "m9",
                    "title": "悬空",
                    "objective": "引用未知里程碑。",
                    "description": "说明",
                    "priority": "LOW",
                }
            ),
            "任务的 milestoneTempKey 不在本次里程碑内",
        ),
        (
            lambda plan: plan["tasks"].extend(
                {
                    "tempKey": f"t{index}",
                    "milestoneTempKey": "m1",
                    "title": f"超额 {index}",
                    "objective": "超额任务。",
                    "description": "说明",
                    "priority": "LOW",
                }
                for index in range(2, 7)
            ),
            "任务数量不在本次约束范围内",
        ),
    ],
)
def test_invalid_draft_returns_422_with_fixed_reason(app: FastAPI, mutate, reason: str) -> None:
    content = json.loads(VALID_CONTENT)
    mutate(content["plan"])
    violation_before = _metric("ai_worker_planning_draft_schema_violation_total")

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(
                200, json=_gateway_payload(json.dumps(content, ensure_ascii=False))
            )
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "SCHEMA_VIOLATION"
    assert body["retryable"] is False
    assert body["details"]["reason"] == reason
    assert _metric("ai_worker_planning_draft_schema_violation_total") == violation_before + 1


@pytest.mark.parametrize(
    "content",
    ["not-json", '{"plan": {"milestones": [], "tasks": []}}', '{"warnings": []}'],
)
def test_malformed_model_output_returns_422(app: FastAPI, content: str) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload(content))
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 422
    assert response.json()["code"] == "SCHEMA_VIOLATION"
    assert "not-json" not in response.text


def test_end_to_end_deadline_returns_504(app: FastAPI) -> None:
    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(client, _body(deadlineMs=50))

    assert response.status_code == 504
    body = response.json()
    assert body["code"] == "DEADLINE_EXCEEDED"
    assert body["retryable"] is True
    assert "plan" not in body


def test_missing_usage_is_unknown_not_zero(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload(usage=None, latency=None))
        )
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 200
    assert response.json()["usage"] == {
        "inputTokens": None,
        "outputTokens": None,
        "source": "UNKNOWN",
    }


def test_missing_token_fails_closed_when_not_configured() -> None:
    app = create_app(
        Settings(
            prompts_dir=PROMPTS_DIR,
            service_token="",
            allow_insecure_internal=False,
            java_gateway_url=GATEWAY_URL,
        )
    )
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token=None)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert route.call_count == 0


def test_wrong_token_is_rejected(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(
            return_value=httpx.Response(200, json=_gateway_payload())
        )
        with TestClient(app) as client:
            response = _post(client, token="wrong-token")

    assert response.status_code == 401
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert TOKEN not in response.text
    assert route.call_count == 0


def test_gateway_failure_returns_503(app: FastAPI) -> None:
    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(503, text="upstream down"))
        with TestClient(app) as client:
            response = _post(client)

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
    assert "upstream down" not in response.text


def test_metrics_and_logs_do_not_leak_inputs(
    app: FastAPI, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="ai_collab_worker.api.planning")
    secret = "项目-SECRET-5d8b"
    body = _body(goal="目标 " + secret)
    body["projectFacts"]["description"] = "描述 " + secret

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        router.post(MODEL_PATH).mock(return_value=httpx.Response(200, json=_gateway_payload()))
        with TestClient(app) as client:
            response = _post(client, body)
            metrics = client.get("/metrics")

    assert response.status_code == 200
    assert secret not in response.text
    assert secret not in metrics.text
    assert secret not in caplog.text
    assert TOKEN not in metrics.text
    assert "ai_worker_planning_draft_duration_seconds" in metrics.text
    assert "ai_worker_planning_draft_warnings_total" in metrics.text
    assert "ai_worker_planning_draft_schema_violation_total" in metrics.text
    assert "ai_worker_planning_source_refs_dropped_total" in metrics.text
    log_text = caplog.text
    assert "planning_draft call_id=" in log_text
    assert "warning_count=" in log_text


def test_server_deadline_cap_is_applied() -> None:
    app = _app(planning_deadline_ms=50)

    async def slow_response(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.3)
        return httpx.Response(200, json=_gateway_payload())

    with respx.mock(base_url=GATEWAY_URL, assert_all_called=False) as router:
        route = router.post(MODEL_PATH).mock(side_effect=slow_response)
        with TestClient(app) as client:
            response = _post(client, _body(deadlineMs=30000))

    assert response.status_code == 504
    if route.calls:
        assert json.loads(route.calls[0].request.content)["deadlineMs"] <= 50


def test_openapi_documents_planning_route(app: FastAPI) -> None:
    with TestClient(app) as client:
        paths = client.get("/openapi.json").json()["paths"]

    assert "/v1/planning/draft" in paths
