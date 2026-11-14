"""通用跨语言契约：schemaVersion、错误信封、deadline 与幂等约定。

口径由设计文档 ``docs/design/python-ai-capability-plane-2026-11-11.md`` §6.4 与 §7.1 固定：

- 所有响应带 ``schemaVersion``（PromptPack 的 manifest 与文档均已带）；
- 所有请求带 ``deadlineMs``（P0 无认知端点，只有通用请求模型）；
- 错误统一为 ``{code, message, retryable, details?}``，``code`` 为大写下划线；
- 幂等键走请求头 ``Idempotency-Key``。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from enum import StrEnum
from types import MappingProxyType
from typing import Any, Final, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

#: 当前跨语言契约版本。破坏性变更必须新端点，不得原地改语义。
SCHEMA_VERSION: Final[Literal[1]] = 1

#: deadlineMs 允许区间（1ms .. 10 分钟）。
DEADLINE_MS_MIN: Final[int] = 1
DEADLINE_MS_MAX: Final[int] = 600_000

#: 幂等请求头名称（大小写不敏感，HTTP/1.1 头字段）。
IDEMPOTENCY_HEADER: Final[str] = "Idempotency-Key"

#: 幂等键取值：8-128 位，允许字母/数字与 ``._:-``。
IDEMPOTENCY_KEY_PATTERN: Final[str] = r"^[A-Za-z0-9._:-]{8,128}$"


class ContractModel(BaseModel):
    """契约模型基类。

    - 序列化/反序列化统一使用 camelCase（``schemaVersion``、``deadlineMs``），
      同时允许按字段名构造，避免 Python 侧出现两套命名；
    - ``extra="forbid"``：多字段直接失败，防止"悄悄忽略"破坏跨语言演进；
    - ``frozen=True``：契约对象不可变，可安全跨线程共享与缓存。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


class ErrorCode(StrEnum):
    """设计 §7.1 固定的 9 个错误码。"""

    INVALID_REQUEST = "INVALID_REQUEST"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    MODEL_RATE_LIMITED = "MODEL_RATE_LIMITED"
    PROMPT_UNAVAILABLE = "PROMPT_UNAVAILABLE"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    CONTENT_BLOCKED = "CONTENT_BLOCKED"
    INTERNAL = "INTERNAL"


#: 错误码 → HTTP 状态码。未知 prompt 用 404 覆盖（见 ``AppError`` 的 ``status_code``）。
ERROR_HTTP_STATUS: Final[Mapping[ErrorCode, int]] = MappingProxyType(
    {
        ErrorCode.INVALID_REQUEST: 400,
        ErrorCode.DEADLINE_EXCEEDED: 504,
        ErrorCode.MODEL_UNAVAILABLE: 503,
        ErrorCode.MODEL_RATE_LIMITED: 429,
        ErrorCode.PROMPT_UNAVAILABLE: 503,
        ErrorCode.BUDGET_EXCEEDED: 429,
        ErrorCode.SCHEMA_VIOLATION: 422,
        ErrorCode.CONTENT_BLOCKED: 400,
        ErrorCode.INTERNAL: 500,
    }
)

#: 默认可重试的错误码。PROMPT_UNAVAILABLE 故意不可重试：Prompt 缺失/校验失败是确定性
#: 失败，Java 侧应回退到"上次成功版本"而不是重试（设计 §5.3 J2 失败语义）。
RETRYABLE_ERROR_CODES: Final[frozenset[ErrorCode]] = frozenset(
    {
        ErrorCode.DEADLINE_EXCEEDED,
        ErrorCode.MODEL_UNAVAILABLE,
        ErrorCode.MODEL_RATE_LIMITED,
        ErrorCode.INTERNAL,
    }
)


class ErrorEnvelope(ContractModel):
    """统一错误信封（设计 §7.1 原文：``{code, message, retryable, details?}``）。"""

    code: ErrorCode
    message: str = Field(min_length=1, max_length=4_000)
    retryable: bool
    details: dict[str, Any] | None = Field(
        default=None,
        description="结构化补充信息；不得包含密钥、Token、Prompt 正文或用户隐私数据。",
    )


class AppError(Exception):
    """业务错误：``code`` 决定默认 HTTP 状态与 retryable，可在构造时覆盖。

    只抛这个异常，不手写响应体，保证所有错误都经过统一信封。
    """

    def __init__(
        self,
        code: ErrorCode,
        message: str,
        *,
        retryable: bool | None = None,
        status_code: int | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = code in RETRYABLE_ERROR_CODES if retryable is None else retryable
        self.status_code = ERROR_HTTP_STATUS[code] if status_code is None else status_code
        self.details = details

    def to_envelope(self) -> ErrorEnvelope:
        """渲染成可序列化的错误信封。"""

        return ErrorEnvelope(
            code=self.code,
            message=self.message,
            retryable=self.retryable,
            details=self.details,
        )


class CommonRequest(ContractModel):
    """认知端点的通用请求头字段（P1 起被具体请求模型继承）。

    - ``schemaVersion`` 必为 1；
    - ``deadlineMs`` 为端到端截止时间。GET（如 prompts）可省略；POST 认知端点必须提供。
    """

    schema_version: Literal[1] = SCHEMA_VERSION
    deadline_ms: int | None = Field(
        default=None,
        ge=DEADLINE_MS_MIN,
        le=DEADLINE_MS_MAX,
        description=(
            "调用方给定的剩余预算（毫秒）；超时必须返回 DEADLINE_EXCEEDED，不得返回半成品。"
        ),
    )


def validate_idempotency_key(value: str) -> str:
    """校验 ``Idempotency-Key`` 请求头，返回原值；不合法时抛 ``ValueError``。

    服务端按 key 缓存 10 分钟结果（P1 起由具体端点落地）。
    """

    if re.fullmatch(IDEMPOTENCY_KEY_PATTERN, value) is None:
        msg = "Idempotency-Key 必须为 8-128 位，仅允许字母、数字与 . _ : -"
        raise ValueError(msg)
    return value
