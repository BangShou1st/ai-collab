"""健康检查与指标端点（存活 ``/healthz``、就绪 ``/readyz``、``/metrics``）。

设计依据：设计 §6.3（P0 交付）与 README §4。三条语义约定：

- ``/healthz`` 只证明进程能服务，**不检查任何依赖**；响应里的 role 取自
  ``request.app.state.settings``，不在 import 时读全局配置；
- ``/readyz`` 执行 ``app.state.readyz_checks`` 里注册的全部检查，P0 只允许本地检查
  （PromptPack manifest/模板），因此零外部依赖；
- 失败原因只写检查返回的文本或异常**类型名**，绝不回显异常文本（可能含上游密钥）。
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Final

from fastapi import APIRouter, FastAPI, Request
from fastapi.responses import JSONResponse, Response

from ai_collab_worker import SERVICE_NAME, __version__
from ai_collab_worker.config import Settings
from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.observability.metrics import render_metrics

router: Final[APIRouter] = APIRouter()

#: 就绪检查签名：返回 ``None`` 表示通过，返回字符串表示失败原因（不得含密钥）。
ReadinessCheck = Callable[[], str | None]

#: 检查通过时 ``checks`` 里的取值。
CHECK_OK: Final[str] = "ok"

#: 检查失败时 ``checks`` 里的取值。
CHECK_FAILED: Final[str] = "failed"


def add_readiness_check(app: FastAPI, name: str, check: ReadinessCheck) -> None:
    """注册一个就绪检查，写入 ``app.state.readyz_checks[name]``。

    同名检查后注册者覆盖先注册者；``/readyz`` 按注册顺序执行。
    """

    checks = getattr(app.state, "readyz_checks", None)
    if checks is None:
        checks = {}
        app.state.readyz_checks = checks
    checks[name] = check


@router.get("/healthz")
async def healthz(request: Request) -> JSONResponse:
    """存活探针：进程可服务即 200，不检查任何依赖。"""

    settings: Settings = request.app.state.settings
    return JSONResponse(
        content={
            "status": "ok",
            "service": SERVICE_NAME,
            "version": __version__,
            "role": settings.role,
            "schemaVersion": SCHEMA_VERSION,
        }
    )


@router.get("/readyz")
async def readyz(request: Request) -> JSONResponse:
    """就绪探针：全部检查通过返回 200，任一失败返回 503 并给出原因。"""

    checks: Mapping[str, ReadinessCheck] = getattr(request.app.state, "readyz_checks", {})
    states: dict[str, str] = {}
    reasons: dict[str, str] = {}
    for name, check in checks.items():
        try:
            reason = check()
        except Exception as exc:  # 任何异常都只降级为 failed，且不回显异常文本
            states[name] = CHECK_FAILED
            reasons[name] = type(exc).__name__
            continue
        if reason is None:
            states[name] = CHECK_OK
            continue
        states[name] = CHECK_FAILED
        reasons[name] = reason

    if reasons:
        return JSONResponse(
            content={
                "status": "not_ready",
                "checks": states,
                "reasons": reasons,
                "schemaVersion": SCHEMA_VERSION,
            },
            status_code=503,
        )
    return JSONResponse(
        content={
            "status": "ready",
            "checks": states,
            "schemaVersion": SCHEMA_VERSION,
        }
    )


@router.get("/metrics")
async def metrics() -> Response:
    """Prometheus 文本格式指标（``text/plain``，具体版本由 prometheus-client 给出）。"""

    payload, content_type = render_metrics()
    return Response(content=payload, media_type=content_type)


__all__ = ["ReadinessCheck", "add_readiness_check", "router"]
