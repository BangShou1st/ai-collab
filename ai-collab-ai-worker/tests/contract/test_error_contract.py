"""错误信封的契约测试：形状、HTTP 映射、retryable 默认值与信息泄漏边界。"""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi import FastAPI, HTTPException, Query
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai_collab_worker.api.errors import install_error_handlers
from ai_collab_worker.contract.common import (
    RETRYABLE_ERROR_CODES,
    AppError,
    ErrorCode,
    ErrorEnvelope,
)

DESIGN_ERROR_CODES = {
    "INVALID_REQUEST",
    "DEADLINE_EXCEEDED",
    "MODEL_UNAVAILABLE",
    "MODEL_RATE_LIMITED",
    "PROMPT_UNAVAILABLE",
    "BUDGET_EXCEEDED",
    "SCHEMA_VIOLATION",
    "CONTENT_BLOCKED",
    "INTERNAL",
}


@pytest.fixture
def client() -> Iterator[TestClient]:
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/boom")
    def boom() -> None:
        raise AppError(ErrorCode.MODEL_UNAVAILABLE, "上游不可用")

    @app.get("/unknown-prompt")
    def unknown_prompt() -> None:
        raise AppError(
            ErrorCode.PROMPT_UNAVAILABLE,
            "Prompt 不存在",
            status_code=404,
            details={"reason": "unknown-prompt"},
        )

    @app.get("/lengthy")
    def lengthy(q: str = Query(min_length=20)) -> str:
        return q

    @app.get("/crash")
    def crash() -> None:
        raise RuntimeError("secret-token-should-not-leak")

    @app.get("/teapot")
    def teapot() -> None:
        raise HTTPException(status_code=418, detail="teapot")

    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


def test_error_code_set_is_frozen_to_design() -> None:
    assert {code.value for code in ErrorCode} == DESIGN_ERROR_CODES


def test_error_envelope_rejects_extra_fields() -> None:
    with pytest.raises(ValidationError):
        ErrorEnvelope(
            code=ErrorCode.INTERNAL,
            message="x",
            retryable=True,
            unexpected=1,  # type: ignore[call-arg]
        )


def test_app_error_defaults_follow_code_table() -> None:
    unavailable = AppError(ErrorCode.MODEL_UNAVAILABLE, "x")
    assert unavailable.status_code == 503
    assert unavailable.retryable is True
    prompt = AppError(ErrorCode.PROMPT_UNAVAILABLE, "x")
    assert prompt.status_code == 503
    assert prompt.retryable is False
    assert ErrorCode.PROMPT_UNAVAILABLE not in RETRYABLE_ERROR_CODES


def test_app_error_to_envelope_keeps_details_when_provided() -> None:
    envelope = AppError(
        ErrorCode.SCHEMA_VIOLATION,
        "bad",
        status_code=422,
        details={"field": "scores"},
    ).to_envelope()
    assert envelope.model_dump(by_alias=True, exclude_none=True) == {
        "code": "SCHEMA_VIOLATION",
        "message": "bad",
        "retryable": False,
        "details": {"field": "scores"},
    }


def test_app_error_response_shape(client: TestClient) -> None:
    response = client.get("/boom")
    assert response.status_code == 503
    assert response.json() == {
        "code": "MODEL_UNAVAILABLE",
        "message": "上游不可用",
        "retryable": True,
    }


def test_unknown_prompt_uses_404_without_retry(client: TestClient) -> None:
    response = client.get("/unknown-prompt")
    assert response.status_code == 404
    assert response.json() == {
        "code": "PROMPT_UNAVAILABLE",
        "message": "Prompt 不存在",
        "retryable": False,
        "details": {"reason": "unknown-prompt"},
    }


def test_validation_error_is_invalid_request_without_echoing_input(
    client: TestClient,
) -> None:
    secret_like = "LEAKME-0123456789"
    response = client.get("/lengthy", params={"q": "LEAKME-123"})
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "INVALID_REQUEST"
    assert body["retryable"] is False
    assert body["details"]["errors"]
    assert "input" not in body["details"]["errors"][0]
    assert secret_like not in response.text
    assert "LEAKME-123" not in response.text


def test_unhandled_error_hides_exception_message(client: TestClient) -> None:
    response = client.get("/crash")
    assert response.status_code == 500
    assert response.json() == {
        "code": "INTERNAL",
        "message": "内部错误",
        "retryable": True,
    }
    assert "secret" not in response.text


def test_http_exception_is_enveloped(client: TestClient) -> None:
    response = client.get("/teapot")
    assert response.status_code == 418
    assert response.json()["code"] == "INVALID_REQUEST"
    assert response.json()["message"] == "teapot"


def test_unknown_route_is_enveloped(client: TestClient) -> None:
    response = client.get("/does-not-exist")
    assert response.status_code == 404
    assert response.json()["code"] == "INVALID_REQUEST"
