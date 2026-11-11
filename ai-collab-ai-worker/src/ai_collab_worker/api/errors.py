"""统一错误出口：把 ``AppError``、参数校验失败与未捕获异常都收敛成错误信封。

任何端点都不得手写错误 JSON；新增错误一律 ``raise AppError(ErrorCode.X, "...")``。
"""

from __future__ import annotations

import logging
from typing import Any, Final

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from ai_collab_worker.contract.common import AppError, ErrorCode, ErrorEnvelope

logger: Final[logging.Logger] = logging.getLogger(__name__)

_MAX_VALIDATION_DETAILS: Final[int] = 20


def render_error(
    code: ErrorCode,
    message: str,
    *,
    status_code: int,
    retryable: bool | None = None,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    """把错误渲染成 ``{code, message, retryable, details?}`` 响应。"""

    envelope = AppError(
        code,
        message,
        retryable=retryable,
        status_code=status_code,
        details=details,
    ).to_envelope()
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(by_alias=True, mode="json", exclude_none=True),
    )


def _validation_details(exc: RequestValidationError) -> dict[str, Any]:
    """只保留 loc/type/msg，绝不回显输入值（可能含密钥、Prompt 或用户数据）。"""

    errors = [
        {
            "loc": [str(part) for part in error.get("loc", ())],
            "type": str(error.get("type", "")),
            "msg": str(error.get("msg", "")),
        }
        for error in exc.errors()[:_MAX_VALIDATION_DETAILS]
    ]
    return {"errors": errors}


def install_error_handlers(app: FastAPI) -> None:
    """注册全部异常处理器；应在创建 app 时调用一次。"""

    @app.exception_handler(AppError)
    async def _handle_app_error(request: Request, exc: AppError) -> JSONResponse:
        if exc.status_code >= 500:
            logger.error(
                "app error path=%s code=%s status=%s",
                request.url.path,
                exc.code.value,
                exc.status_code,
            )
        return render_error(
            exc.code,
            exc.message,
            status_code=exc.status_code,
            retryable=exc.retryable,
            details=exc.details,
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(
        _request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        return render_error(
            ErrorCode.INVALID_REQUEST,
            "请求不符合契约",
            status_code=422,
            details=_validation_details(exc),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_error(
        _request: Request,
        exc: StarletteHTTPException,
    ) -> JSONResponse:
        code = ErrorCode.INTERNAL if exc.status_code >= 500 else ErrorCode.INVALID_REQUEST
        return render_error(code, str(exc.detail), status_code=exc.status_code)

    @app.exception_handler(Exception)
    async def _handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        # 只记异常类型与路径：异常文本可能包含上游返回的敏感内容。
        logger.error(
            "unhandled error path=%s type=%s",
            request.url.path,
            type(exc).__name__,
        )
        return render_error(ErrorCode.INTERNAL, "内部错误", status_code=500)


__all__ = ["ErrorEnvelope", "install_error_handlers", "render_error"]
