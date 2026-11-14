"""跨语言契约（schemaVersion=1）。

``common`` 定义版本、错误码、错误信封与请求通用约定；``prompts`` 定义 PromptPack 的
manifest 与响应模型；``schema`` 负责把 Pydantic 模型渲染成入库的 JSON Schema 快照。
"""

from __future__ import annotations

from ai_collab_worker.contract.common import (
    DEADLINE_MS_MAX,
    DEADLINE_MS_MIN,
    IDEMPOTENCY_HEADER,
    SCHEMA_VERSION,
    AppError,
    CommonRequest,
    ContractModel,
    ErrorCode,
    ErrorEnvelope,
)
from ai_collab_worker.contract.prompts import (
    PLACEHOLDER_PATTERN,
    PROMPT_KEY_PATTERN,
    PROMPT_VERSION_PATTERN,
    PromptDocument,
    PromptManifest,
    PromptManifestEntry,
    PromptPackEntry,
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

__all__ = [
    "DEADLINE_MS_MAX",
    "DEADLINE_MS_MIN",
    "IDEMPOTENCY_HEADER",
    "PLACEHOLDER_PATTERN",
    "PROMPT_KEY_PATTERN",
    "PROMPT_VERSION_PATTERN",
    "SCHEMA_VERSION",
    "AppError",
    "CommonRequest",
    "ContractModel",
    "ErrorCode",
    "ErrorEnvelope",
    "PromptDocument",
    "PromptManifest",
    "PromptManifestEntry",
    "PromptPackEntry",
    "PromptPackManifest",
    "extract_variables",
    "prompt_chars",
    "prompt_sha256",
    "prompt_text_from_bytes",
]
