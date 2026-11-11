"""Memory 提取 Job 契约（设计 §7.7，W5 施工冻结文档 §3 的落地版）。

本模块同时承载三类形状，**只有前两个登记进 ``contract/snapshots/v1/``**（冻结要求 4 个新快照）：

1. ``POST /v1/jobs`` 的请求/响应（Java → Python，冻结 §3.4）；
2. Memory 候选（冻结 §3.5），嵌在 results 请求里；
3. Java 内部端点的请求/响应（Python → Java，冻结 §3.3）——它们是**内部传输形状**，
   不是 Java 调 Python 的契约，因此不登记快照，只作为客户端解析的强类型模型。

固定口径：

- Job 请求只有 ``{schemaVersion, jobId, type, deadlineMs}``，**不含快照**；快照由 Python 用
  同一个 job token 向 Java 拉取（设计 §7.7）；
- Job 响应 ``{schemaVersion, jobId, status, candidateCount, latencyMs}``；``status`` 只允许
  ``SUCCEEDED`` / ``FAILED_RETRYABLE`` / ``FAILED`` —— 重试与否由 Java 决定，Python 只报状态；
- 候选 ``{kind, title, content, confidence, sourceRef, dedupeKey}``：``kind`` 与
  ``ck_agent_memory_type`` 一致，``title`` 1–200、``content`` 1–2000、``confidence ∈ [0,1]``、
  ``sourceRef`` 必须引用快照里的 fact/memory、``dedupeKey = sha256(kind|normalized(title)|
  normalized(content))``（64 位小写十六进制）；每次 ≤50 条；
- ``dedupeKey`` **由 Python 计算**（冻结 §3.5）。模型输出里可以省略它；若模型给了非空值又
  与重算结果不一致，该候选被丢弃（防止模型编造幂等键），见 ``cognition/memory/parser.py``；
- ``latencyMs`` 是 worker 自测的端到端耗时（单调时钟），**不是**模型耗时；模型耗时由 Java
  账本负责，本侧不伪造 ``T_model``。

Job 状态机（``ai_job.status`` 的 8 个取值，冻结 §3.1 的 CHECK 约束）只在 Java：Python 不建表、
不调度、不决定重试、不发布候选、不读 ``review_status``。
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


class MemoryJobStatus(StrEnum):
    """``POST /v1/jobs`` 响应里的状态（冻结 §3.4 只允许三个取值）。"""

    SUCCEEDED = "SUCCEEDED"
    FAILED_RETRYABLE = "FAILED_RETRYABLE"
    FAILED = "FAILED"


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


class MemoryJobRequest(ContractModel):
    """``POST /v1/jobs`` 请求：只带 job 身份，不含快照。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    job_id: uuid.UUID
    type: JobType = Field(description="本波只允许 MEMORY_EXTRACTION。")
    deadline_ms: int = Field(
        default=DEFAULT_MEMORY_JOB_DEADLINE_MS,
        ge=MIN_MEMORY_JOB_DEADLINE_MS,
        le=MAX_MEMORY_JOB_DEADLINE_MS,
        description="端到端硬 deadline（毫秒）；到点报 FAILED_RETRYABLE，不返回半成品。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_schema_version(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version", "deadlineMs", "deadline_ms"):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
                    raise ValueError(msg)
        return data


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


class MemoryJobResponse(ContractModel):
    """``POST /v1/jobs`` 响应；状态判定权在 Java，本侧只如实回报一次执行的结果。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    job_id: uuid.UUID
    status: MemoryJobStatus
    candidate_count: int = Field(
        ge=0,
        le=MAX_MEMORY_CANDIDATES,
        description="提交给 Java 的合法候选数（不含本侧丢弃的候选）。",
    )
    latency_ms: int = Field(
        ge=0,
        description="worker 自测端到端耗时（毫秒）；不是模型耗时。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_numbers(cls, data: object) -> object:
        if isinstance(data, dict):
            for key in (
                "candidateCount",
                "candidate_count",
                "latencyMs",
                "latency_ms",
            ):
                if isinstance(data.get(key), bool):
                    msg = f"{key} 必须为整数"
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
    """``GET /internal/ai/v1/jobs/{id}/snapshot`` 响应（冻结 §3.3，≤64 KiB）。"""

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


class JobHeartbeatRequest(ContractModel):
    """``POST /internal/ai/v1/jobs/{id}/heartbeat`` 请求。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    attempt: int = Field(ge=1)
    lease_epoch: int = Field(ge=0)


class JobHeartbeatResponse(ContractModel):
    """心跳响应：``status`` 是 Java 侧的当前状态，``leaseExpiresAt`` 可为空。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    status: JobState
    lease_expires_at: str | None = Field(default=None, max_length=64)


class JobResultsRequest(ContractModel):
    """``POST /internal/ai/v1/jobs/{id}/results`` 请求；候选 ≤50 条。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    attempt: int = Field(ge=1)
    lease_epoch: int = Field(ge=0)
    candidates: tuple[MemoryCandidate, ...] = Field(default=(), max_length=MAX_MEMORY_CANDIDATES)


class JobResultsResponse(ContractModel):
    """结果回报响应；重复回报时 Java 只更新心跳并返回既有结果（冻结 §3.2 幂等）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    job_id: uuid.UUID
    status: MemoryJobStatus
    accepted: int = Field(ge=0)
    duplicates: int = Field(ge=0)


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
    "JobHeartbeatRequest",
    "JobHeartbeatResponse",
    "JobResultsRequest",
    "JobResultsResponse",
    "JobState",
    "JobType",
    "MemoryCandidate",
    "MemoryJobRequest",
    "MemoryJobResponse",
    "MemoryJobSnapshot",
    "MemoryJobStatus",
    "MemoryKind",
    "SnapshotFact",
]
