"""解析模型返回的 Memory 候选列表（W5 冻结文档 §3.5）。

候选契约（冻结 §3.5）：

```json
{"kind":"DECISION","title":"…","content":"…","confidence":0.82,
 "sourceRef":"memory:<uuid>","dedupeKey":"<sha256 hex 64>"}
```

本模块是 **HTTP 端点与 ``ai-eval memory`` replay 打分器共用的唯一解析核心**。

``dedupeKey`` 口径（冻结：``sha256(kind|normalized(title)|normalized(content))``）：

1. ``normalized(text)`` = NFKC 归一 → 转小写（``str.casefold``）→ 连续空白折叠为单个空格 →
   去首尾空白；
2. 三部分用 ``|`` 连接后取 UTF-8 字节的 SHA-256，十六进制小写（64 位）。

"规范化"冻结未逐字定义，本文件是唯一权威；Java 侧要独立复算时必须逐条对齐（README §4.5
已登记该口径）。

关于模型是否要输出 ``dedupeKey``：冻结写的是"**由 Python 计算**"，因此模型可以省略；
但如果模型给了非空值且与重算结果不一致，说明它在编造幂等键 —— 这种候选被丢弃并计数
（``ai_worker_memory_invalid_dedupe_total``），不放行。

丢弃 ≠ 报错：来源非法/幂等键不一致都只会减少候选数；只有"不是 JSON / 字段越界 / 超过
条数上限"才是 ``SCHEMA_VIOLATION``。
"""

from __future__ import annotations

import hashlib
import json
import unicodedata
from dataclasses import dataclass
from typing import Final

from pydantic import Field, ValidationError, model_validator

from ai_collab_worker.contract.common import ContractModel
from ai_collab_worker.contract.memory_job import (
    MAX_MEMORY_CANDIDATES,
    MemoryCandidate,
    MemoryJobSnapshot,
    MemoryKind,
)

#: 对外可见的固定失败原因（不含模型原文）。
REASON_EMPTY_OUTPUT: Final[str] = "模型输出为空"
REASON_NOT_JSON: Final[str] = "模型输出不是合法 JSON"
REASON_NOT_OBJECT: Final[str] = "模型输出不是单个 JSON 对象"
REASON_MISSING_CANDIDATES: Final[str] = "缺少 candidates 字段"
REASON_CANDIDATES_NOT_ARRAY: Final[str] = "candidates 不是数组"
REASON_TOO_MANY_CANDIDATES: Final[str] = "候选数量超过上限"
REASON_CANDIDATE_INVALID: Final[str] = "候选字段缺失、多余或类型/范围非法"


class MemoryCandidateError(Exception):
    """模型候选输出不合法；``reason`` 可安全对外暴露。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class MemoryParseResult:
    """一次解析的结果：合法候选 + 被丢弃的计数（丢弃不报错，只计数）。"""

    candidates: tuple[MemoryCandidate, ...]
    invalid_source: int
    invalid_dedupe: int


class _RawCandidate(ContractModel):
    """模型输出的候选形状：``dedupeKey`` 可缺省（由本侧计算）。"""

    kind: MemoryKind
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=2000)
    confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    source_ref: str = Field(min_length=1, max_length=256)
    dedupe_key: str | None = Field(default=None, max_length=64)

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_confidence(cls, data: object) -> object:
        if isinstance(data, dict) and isinstance(data.get("confidence"), bool):
            msg = "confidence 必须为数字"
            raise ValueError(msg)
        return data


def normalize_text(value: str) -> str:
    """冻结 ``normalized()`` 的唯一实现：NFKC → casefold → 空白折叠 → 去首尾空白。"""

    folded = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(folded.split())


def compute_dedupe_key(kind: MemoryKind | str, title: str, content: str) -> str:
    """``sha256(kind|normalized(title)|normalized(content))`` 的十六进制小写。"""

    kind_value = kind.value if isinstance(kind, MemoryKind) else kind
    material = f"{kind_value}|{normalize_text(title)}|{normalize_text(content)}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def parse_candidates(
    content: str,
    *,
    snapshot: MemoryJobSnapshot,
    max_candidates: int = MAX_MEMORY_CANDIDATES,
) -> MemoryParseResult:
    """解析候选列表；不合契约时抛 :class:`MemoryCandidateError`。"""

    limit = min(max(1, max_candidates), MAX_MEMORY_CANDIDATES)
    cleaned = _strip_code_fence(content).strip()
    if not cleaned:
        raise MemoryCandidateError(REASON_EMPTY_OUTPUT)
    try:
        root: object = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise MemoryCandidateError(REASON_NOT_JSON) from exc
    if not isinstance(root, dict):
        raise MemoryCandidateError(REASON_NOT_OBJECT)

    raw_candidates = root.get("candidates")
    if raw_candidates is None:
        raise MemoryCandidateError(REASON_MISSING_CANDIDATES)
    if not isinstance(raw_candidates, list):
        raise MemoryCandidateError(REASON_CANDIDATES_NOT_ARRAY)
    if len(raw_candidates) > limit:
        raise MemoryCandidateError(REASON_TOO_MANY_CANDIDATES)

    # 模型可能编造顶层 trace 字段（如 reasoning）；顶层额外键容忍，候选内部严格。
    allowed_refs = snapshot.source_refs()
    accepted: list[MemoryCandidate] = []
    invalid_source = 0
    invalid_dedupe = 0
    for raw in raw_candidates:
        candidate = _parse_candidate(raw)
        if candidate.source_ref not in allowed_refs:
            invalid_source += 1
            continue
        computed = compute_dedupe_key(candidate.kind, candidate.title, candidate.content)
        supplied = candidate.dedupe_key
        if supplied and supplied != computed:
            invalid_dedupe += 1
            continue
        accepted.append(
            MemoryCandidate(
                kind=candidate.kind,
                title=candidate.title,
                content=candidate.content,
                confidence=candidate.confidence,
                source_ref=candidate.source_ref,
                dedupe_key=computed,
            )
        )
    return MemoryParseResult(
        candidates=tuple(accepted),
        invalid_source=invalid_source,
        invalid_dedupe=invalid_dedupe,
    )


def _parse_candidate(raw: object) -> _RawCandidate:
    if not isinstance(raw, dict):
        raise MemoryCandidateError(REASON_CANDIDATE_INVALID)
    try:
        return _RawCandidate.model_validate(raw)
    except ValidationError as exc:
        raise MemoryCandidateError(REASON_CANDIDATE_INVALID) from exc


def _strip_code_fence(content: str) -> str:
    """按 decide/query 解析器的顺序去掉 ```json / ``` 围栏，返回去空白后的文本。"""

    cleaned = content.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[len("```json") :]
    elif cleaned.startswith("```"):
        cleaned = cleaned[len("```") :]
    if cleaned.endswith("```"):
        cleaned = cleaned[: -len("```")]
    return cleaned.strip()


__all__ = [
    "REASON_CANDIDATES_NOT_ARRAY",
    "REASON_CANDIDATE_INVALID",
    "REASON_EMPTY_OUTPUT",
    "REASON_MISSING_CANDIDATES",
    "REASON_NOT_JSON",
    "REASON_NOT_OBJECT",
    "REASON_TOO_MANY_CANDIDATES",
    "MemoryCandidateError",
    "MemoryParseResult",
    "compute_dedupe_key",
    "normalize_text",
    "parse_candidates",
]
