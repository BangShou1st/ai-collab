"""Memory 抽取任务的数据形状（设计 §7.7；P1 契约 §0、§4.3）。

P1 起记忆抽取走 Redis Stream（``taskType=MEMORY_EXTRACTION``），HTTP 推送入口已删除。本模块只剩：

1. Memory 候选（``MemoryCandidate``），作为 ``POST /internal/ai/tasks/{taskId}/result`` 的
   ``payload.candidates``；
2. ``GET /internal/ai/tasks/{taskId}/snapshot`` 的快照形状（沿用现有内容）。

它们是 Python → Java 的**内部传输形状**，不登记契约快照，只作为强类型模型。

固定口径：

- 候选 ``{kind, title, content, confidence, sourceRef, dedupeKey}``：``kind`` 与
  ``ck_agent_memory_type`` 一致，``title`` 1–200、``content`` 1–2000、``confidence ∈ [0,1]``、
  ``sourceRef`` 必须引用快照里的 fact/memory、``dedupeKey = sha256(kind|normalized(title)|
  normalized(content))``（64 位小写十六进制）；每次 ≤50 条；
- ``dedupeKey`` **由 Python 计算**。模型输出里可以省略它；若模型给了非空值又与重算结果不一致，
  该候选被丢弃（防止模型编造幂等键），见 ``cognition/memory/parser.py``。

任务状态机（``ai_job.status``）只在 Java：Python 不建表、不调度、不决定重试、不发布候选。
"""

from __future__ import annotations

import uuid
from enum import StrEnum
from typing import Final, Literal, Self

from pydantic import Field, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, ContractModel

#: Job 类型：本波只有一种（与 ``ck_ai_job_type`` 一致）。
JOB_TYPE_MEMORY_EXTRACTION: Final[str] = "MEMORY_EXTRACTION"

#: 每次候选上限（冻结 §3.5）。
MAX_MEMORY_CANDIDATES: Final[int] = 50

#: 候选字段长度上限（冻结 §3.5）。
MAX_CANDIDATE_TITLE_CHARS: Final[int] = 200
MAX_CANDIDATE_CONTENT_CHARS: Final[int] = 2000
MAX_SOURCE_REF_CHARS: Final[int] = 256

#: dedupeKey：小写十六进制 SHA-256。
DEDUPE_KEY_PATTERN: Final[str] = r"^[0-9a-f]{64}$"

#: Job 请求 deadline 默认值与区间（毫秒）。
DEFAULT_MEMORY_JOB_DEADLINE_MS: Final[int] = 60_000
MIN_MEMORY_JOB_DEADLINE_MS: Final[int] = 1
MAX_MEMORY_JOB_DEADLINE_MS: Final[int] = 120_000

#: 快照总字符上限（冻结 §3.3：≤64 KiB）。按正文/摘要字符数计，不按 UTF-8 字节。
MAX_SNAPSHOT_CHARS: Final[int] = 64 * 1024

#: 快照 facts / existingMemories 的条目数护栏（冻结未给条数，只给了 64 KiB 总上限）。
MAX_SNAPSHOT_ITEMS: Final[int] = 500


class JobType(StrEnum):
    """``ai_job.job_type`` 的合法取值。"""

    MEMORY_EXTRACTION = JOB_TYPE_MEMORY_EXTRACTION


class JobState(StrEnum):
    """``ai_job.status`` 全状态（冻结 §3.1 的 CHECK 约束，仅 Java 写入）。"""

    QUEUED = "QUEUED"
    LEASED = "LEASED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED_RETRYABLE = "FAILED_RETRYABLE"
    FAILED = "FAILED"
    CANCELED = "CANCELED"
    EXPIRED = "EXPIRED"


class MemoryKind(StrEnum):
    """记忆类型（与 ``ck_agent_memory_type`` 一致，冻结 §3.5）。"""

    DECISION = "DECISION"
    PREFERENCE = "PREFERENCE"
    CONSTRAINT = "CONSTRAINT"
    LESSON = "LESSON"


class MemoryCandidate(ContractModel):
    """一条记忆候选；``dedupeKey`` 由 Python 计算后填入（见模块 docstring）。"""

    kind: MemoryKind
    title: str = Field(min_length=1, max_length=MAX_CANDIDATE_TITLE_CHARS)
    content: str = Field(min_length=1, max_length=MAX_CANDIDATE_CONTENT_CHARS)
    confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    source_ref: str = Field(
        min_length=1,
        max_length=MAX_SOURCE_REF_CHARS,
        description="快照 fact 的 ref 或 ``memory:<id>``；不在快照内即丢弃该候选。",
    )
    dedupe_key: str = Field(
        pattern=DEDUPE_KEY_PATTERN,
        description="sha256(kind|normalized(title)|normalized(content))，64 位小写十六进制。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_confidence(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("confidence",):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为数字"
                    raise ValueError(msg)
        return data


# --------------------------------------------------------------------------- #
# Java 内部端点形状（Python → Java；不登记契约快照）
# --------------------------------------------------------------------------- #


class SnapshotFact(ContractModel):
    """快照里的一条事实：``ref`` 是候选 ``sourceRef`` 的合法取值之一。"""

    ref: str = Field(min_length=1, max_length=MAX_SOURCE_REF_CHARS)
    kind: str = Field(min_length=1, max_length=64)
    text: str = Field(min_length=1, max_length=4000)


class ExistingMemory(ContractModel):
    """既有记忆：候选的 ``sourceRef`` 可用 ``memory:<id>`` 引用它。"""

    id: uuid.UUID
    type: MemoryKind
    title: str = Field(default="", max_length=MAX_CANDIDATE_TITLE_CHARS)
    content: str = Field(default="", max_length=MAX_CANDIDATE_CONTENT_CHARS)


class MemoryJobSnapshot(ContractModel):
    """``GET /internal/ai/tasks/{taskId}/snapshot`` 响应（≤64 KiB）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    job_id: uuid.UUID
    project_id: str = Field(min_length=1, max_length=128)
    attempt: int = Field(ge=1)
    lease_epoch: int = Field(ge=0)
    snapshot_hash: str = Field(min_length=1, max_length=128)
    facts: tuple[SnapshotFact, ...] = Field(default=(), max_length=MAX_SNAPSHOT_ITEMS)
    existing_memories: tuple[ExistingMemory, ...] = Field(default=(), max_length=MAX_SNAPSHOT_ITEMS)

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_numbers(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version", "attempt", "leaseEpoch", "lease_epoch"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data

    @model_validator(mode="after")
    def _check_size(self) -> Self:
        total = sum(len(fact.text) + len(fact.ref) for fact in self.facts)
        total += sum(len(memory.title) + len(memory.content) for memory in self.existing_memories)
        if total > MAX_SNAPSHOT_CHARS:
            msg = f"快照超过 {MAX_SNAPSHOT_CHARS} 字符上限"
            raise ValueError(msg)
        return self

    def source_refs(self) -> frozenset[str]:
        """候选 ``sourceRef`` 的合法取值集合：fact 的 ref 与 ``memory:<id>``。"""

        refs = {fact.ref for fact in self.facts}
        refs.update(f"memory:{memory.id}" for memory in self.existing_memories)
        return frozenset(refs)


__all__ = [
    "DEDUPE_KEY_PATTERN",
    "DEFAULT_MEMORY_JOB_DEADLINE_MS",
    "JOB_TYPE_MEMORY_EXTRACTION",
    "MAX_CANDIDATE_CONTENT_CHARS",
    "MAX_CANDIDATE_TITLE_CHARS",
    "MAX_MEMORY_CANDIDATES",
    "MAX_MEMORY_JOB_DEADLINE_MS",
    "MAX_SNAPSHOT_CHARS",
    "MAX_SNAPSHOT_ITEMS",
    "MAX_SOURCE_REF_CHARS",
    "MIN_MEMORY_JOB_DEADLINE_MS",
    "ExistingMemory",
    "JobState",
    "JobType",
    "MemoryCandidate",
    "MemoryJobSnapshot",
    "MemoryKind",
    "SnapshotFact",
]
