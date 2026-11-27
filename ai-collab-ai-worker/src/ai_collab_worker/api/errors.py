"""统一错误出口：把 ``AppError``、参数校验失败与未捕获异常都收敛成错误信封。

任何端点都不得手写错误 JSON；新增错误一律 ``raise AppError(ErrorCode.X, "...")``。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Final

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from ai_collab_worker.contract.common import AppError, ErrorCode, ErrorEnvelope
from ai_collab_worker.contract.knowledge_search import SearchRejectedError

logger: Final[logging.Logger] = logging.getLogger(__name__)

_MAX_VALIDATION_DETAILS: Final[int] = 20


@dataclass
class IndexAdminError(Exception):
    """P2 索引管理端点业务/状态错误（不改动冻结的 P0 ``ErrorCode``）。"""

    status: int
    code: str
    message: str
    retryable: bool = False
    reason: str | None = None


def render_code_error(
    code: str,
    message: str,
    *,
    status_code: int,
    retryable: bool = False,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    """渲染自定义字符串错误码（P2 检索与索引管理端点专用）。"""

    payload: dict[str, Any] = {"code": code, "message": message, "retryable": retryable}
    if details:
        payload["details"] = details
    return JSONResponse(status_code=status_code, content=payload)


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

    @app.exception_handler(SearchRejectedError)
    async def _handle_search_rejected(
        request: Request,
        exc: SearchRejectedError,
    ) -> JSONResponse:
        if exc.status >= 500:
            logger.error(
                "knowledge search error path=%s code=%s status=%s reason=%s",
                request.url.path,
                exc.code,
                exc.status,
                exc.reason,
            )
        return render_code_error(
            exc.code,
            exc.message or exc.reason,
            status_code=exc.status,
            retryable=exc.retryable,
            details={"reason": exc.reason},
        )

    @app.exception_handler(IndexAdminError)
    async def _handle_index_admin_error(
        request: Request,
        exc: IndexAdminError,
    ) -> JSONResponse:
        if exc.status >= 500:
            logger.error(
                "index admin error path=%s code=%s status=%s",
                request.url.path,
                exc.code,
                exc.status,
            )
        details = {"reason": exc.reason} if exc.reason else None
        return render_code_error(
            exc.code,
            exc.message,
            status_code=exc.status,
            retryable=exc.retryable,
            details=details,
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


__all__ = [
    "ErrorEnvelope",
    "IndexAdminError",
    "install_error_handlers",
    "render_code_error",
    "render_error",
]
