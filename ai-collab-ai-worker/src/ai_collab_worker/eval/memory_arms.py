"""``ai-eval memory-arms``：三组跨会话记忆对照的**骨架**（D7-B）。

本命令不调用模型、不写数据库、不伪造任务执行；它只把 Java 侧两种记忆选择语义在
本地做确定性镜像，用于后续真实任务执行前的口径对照：

- ``A_LEGACY``：**治理前语义的本地重建**，不是当前 shipped
  ``AgentMemoryService.context(projectId)`` 的实际行为。当前 Java ``context(projectId)``
  调用的是 ``repository.list(projectId, true, 10, true)``（带 recall-safe 过滤）；本 arm
  镜像的是 3 参兼容重载 ``repository.list(projectId, true, 10)``
  （``excludeRecallUnsafe=false``）：项目内 ``status=ACTIVE`` 的记忆，按
  ``updatedAt DESC, id DESC`` 取前 10，不做相关性过滤。它**不过滤 SUPERSEDED /
  STALE / CONFLICTED / SOURCE_REVOKED**，因此会如实暴露治理前的撤权泄露；仓库没有生产
  开关会选择这个 arm，读者不得把它当作线上 LEGACY 行为。
- ``B_GOVERNED_RECALL``：镜像当前的
  ``AgentMemoryService.context(projectId, userId, request)``——项目内 active +
  recall-safe（``verificationState ∈ {VERIFIED, UNVERIFIED}`` 且 ``supersededBy`` 为空）
  + 请求关键词相关性过滤，按 Java 的 score / updatedAt / id 排序取前 10。
- ``C_DREAMING``：**未实现**。仓库没有 Job dispatcher / 候选发布实现，本命令不得用
  stub 冒充，整组指标固定 ``NOT_VERIFIED``。

指标口径（有数据才算，没有就写 ``NOT_VERIFIED``）：

- ``Recall@K``：``|返回前 K ∩ expectedRecallIds| / |expectedRecallIds|``，按 case 取宏平均；
- ``citationValidityRate``：返回记忆中"同项目 + ACTIVE + recall-safe"的比例；
- ``conflictMisuseRate``：case 的冲突里，返回了任一非 preferred 成员的冲突数 / 冲突总数；
- ``revocationLeaks``：返回记忆命中 case ``revocations`` 的次数；B 必须为 0，A 如实报；
- ``crossProjectLeaks``：返回记忆属于其他项目的条数（A/B 的项目过滤都应保证 0）；
- ``taskAccuracy`` / ``dreamingCost`` / ``humanApprovalRate``：没有真实任务执行 / Dreaming
  实现 / 审批流，固定 ``NOT_VERIFIED``；
- ``tokens``：仅给 ``ceil((title+content)字符数 / 4)`` 的粗略估算，标 ``source=ESTIMATE``，
  不是 provider usage；
- ``latency``：只有显式 ``--measure-latency`` 才测本地检索耗时（P50/P95，最近秩法），
  并标注 ``local-retrieval-only-non-production``；默认不测，保证报告逐字节稳定。

**门禁**：三组对照因 C 未实现，固定 ``NOT_EVALUATED``；A/B 只给诊断数字，不得写成
PASS/FAIL，也不得外推到生产。
"""

from __future__ import annotations

import datetime as dt
import json
import math
import re
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.contract.common import SCHEMA_VERSION
from ai_collab_worker.contract.memory_job import MemoryKind
from ai_collab_worker.eval.canonical import DatasetError, canonicalize_document, digest

#: 三组 arm 的固定名称。
ARM_LEGACY: Final[str] = "A_LEGACY"
ARM_GOVERNED: Final[str] = "B_GOVERNED_RECALL"
ARM_DREAMING: Final[str] = "C_DREAMING"
ARMS: Final[tuple[str, str, str]] = (ARM_LEGACY, ARM_GOVERNED, ARM_DREAMING)

#: 每组 arm 的口径说明（写进报告，避免把治理前重建误读成线上行为）。
ARM_DEFINITIONS: Final[Mapping[str, str]] = {
    ARM_LEGACY: (
        "治理前语义的本地重建：镜像 3 参兼容重载 repository.list(projectId, true, 10)"
        "（excludeRecallUnsafe=false），只按 ACTIVE + updatedAt DESC/id DESC 取前 10；"
        "不过滤 SUPERSEDED/STALE/CONFLICTED/SOURCE_REVOKED。"
        "这不是当前 shipped AgentMemoryService.context(projectId)（它使用 4 参 true），"
        "仓库也没有生产开关会选择该 arm。"
    ),
    ARM_GOVERNED: (
        "当前语义的本地镜像：active + recall-safe（VERIFIED/UNVERIFIED 且 "
        "supersededBy 为空）+ 请求关键词相关性，按 score DESC / updatedAt DESC / id ASC "
        "取前 10。"
    ),
    ARM_DREAMING: "未实现：仓库无 Job dispatcher / 候选发布实现；不得用 stub 冒充。",
}

#: 每个 arm 的返回上限（与 Java context 的 10 一致）。
RECALL_LIMIT: Final[int] = 10
#: case 的 K 上限（Recall@K 不超过 Java 返回上限）。
MAX_K: Final[int] = 10
#: fixture case 数范围（8–12）。
MIN_CASES: Final[int] = 8
MAX_CASES: Final[int] = 12

#: 状态/验证状态白名单。
STATUS_ACTIVE: Final[str] = "ACTIVE"
STATUS_DISABLED: Final[str] = "DISABLED"
VALID_STATUSES: Final[frozenset[str]] = frozenset({STATUS_ACTIVE, STATUS_DISABLED})
RECALL_SAFE_STATES: Final[frozenset[str]] = frozenset({"VERIFIED", "UNVERIFIED"})
VALID_VERIFICATION_STATES: Final[frozenset[str]] = frozenset(
    {"VERIFIED", "UNVERIFIED", "STALE", "CONFLICTED", "SOURCE_REVOKED"}
)
VALID_REVOCATION_REASONS: Final[frozenset[str]] = frozenset(
    {"DISABLED", "SUPERSEDED", "STALE", "CONFLICTED", "SOURCE_REVOKED"}
)

#: 与 Java ``AgentMemoryService.terms`` 对齐的关键词规则与停用词。
_TERM_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"[a-z0-9_]{2,}|[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]{2,}"
)
_STOPWORDS: Final[frozenset[str]] = frozenset(
    {
        "项目",
        "目标",
        "现在",
        "换成",
        "换个",
        "改成",
        "生成",
        "规划",
        "任务",
        "继续",
        "一个",
        "根据",
        "请问",
    }
)

#: ISO-8601 UTC 秒级时间戳；固定形状保证字符串排序与时间排序一致。
_UPDATED_AT_PATTERN: Final[re.Pattern[str]] = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

#: 诚实声明。
LIMITATIONS: Final[str] = (
    "本命令是三组跨会话对照的骨架：A/B 是本地镜像 Java 记忆选择语义，不调用模型、"
    "不写数据库、不执行任务；A_LEGACY 是治理前 3 参 list 语义的本地重建，"
    "不是当前 shipped context(projectId)（后者带 recall-safe），也没有生产开关选择它；"
    "C DREAMING 未实现（仓库无 Job dispatcher / 候选发布），"
    "整组 NOT_VERIFIED；三组对照门禁 NOT_EVALUATED；A/B 数字仅用于诊断，不得外推生产。"
    "tokens 是字符数/4 的粗略估算，不是 provider usage。"
)

#: 证据标签：本地镜像，不是真实模型运行。
EVIDENCE_LOCAL_MIRROR: Final[str] = "TESTED (local mirror)"

#: 门禁状态：C 未实现，不能判定。
GATE_NOT_EVALUATED: Final[str] = "NOT_EVALUATED"
GATE_REASON: Final[str] = (
    "三组对照需要 C DREAMING 的真实实现与运行数据；C 未实现，门禁不具备判定条件。"
    "A/B 仅为本地镜像诊断，不得写成 PASS/FAIL。"
)

NOT_VERIFIED: Final[str] = "NOT_VERIFIED"
_TOP_FIELDS: Final[frozenset[str]] = frozenset(
    {"schemaVersion", "sessions", "projectSnapshots", "memories", "cases"}
)
_SESSION_FIELDS: Final[frozenset[str]] = frozenset(
    {"id", "projectId", "userId", "request", "priorSessionIds"}
)
_PROJECT_FIELDS: Final[frozenset[str]] = frozenset({"projectId", "members", "memoryIds"})
_MEMBER_FIELDS: Final[frozenset[str]] = frozenset({"id", "name"})
_MEMORY_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
        "projectId",
        "type",
        "title",
        "content",
        "status",
        "verificationState",
        "supersededBy",
        "updatedAt",
    }
)
_CASE_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "id",
        "sessionId",
        "projectId",
        "expectedRecallIds",
        "expectedAnswerFacts",
        "conflicts",
        "revocations",
        "maxK",
    }
)
_CONFLICT_FIELDS: Final[frozenset[str]] = frozenset(
    {"id", "memoryIds", "preferredMemoryId", "description"}
)
_REVOCATION_FIELDS: Final[frozenset[str]] = frozenset({"memoryId", "reason"})


@dataclass(frozen=True, slots=True)
class Member:
    id: str
    name: str


@dataclass(frozen=True, slots=True)
class ProjectSnapshot:
    project_id: str
    members: tuple[Member, ...]
    memory_ids: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class MemoryEntry:
    id: str
    project_id: str
    type: MemoryKind
    title: str
    content: str
    status: str
    verification_state: str
    superseded_by: str | None
    updated_at: str


@dataclass(frozen=True, slots=True)
class Session:
    id: str
    project_id: str
    user_id: str
    request: str
    prior_session_ids: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Conflict:
    id: str
    memory_ids: tuple[str, ...]
    preferred_memory_id: str
    description: str


@dataclass(frozen=True, slots=True)
class Revocation:
    memory_id: str
    reason: str


@dataclass(frozen=True, slots=True)
class ArmCase:
    id: str
    session_id: str
    project_id: str
    expected_recall_ids: tuple[str, ...]
    expected_answer_facts: tuple[str, ...]
    conflicts: tuple[Conflict, ...]
    revocations: tuple[Revocation, ...]
    max_k: int


@dataclass(frozen=True, slots=True)
class MemoryArmsDataset:
    sessions: tuple[Session, ...]
    project_snapshots: tuple[ProjectSnapshot, ...]
    memories: tuple[MemoryEntry, ...]
    cases: tuple[ArmCase, ...]


@dataclass(frozen=True, slots=True)
class ArmCaseResult:
    id: str
    project_id: str
    returned_ids: tuple[str, ...]
    expected_recall_ids: tuple[str, ...]
    recovered_ids: tuple[str, ...]
    recall: float
    citation_valid: int
    citation_total: int
    conflict_misuses: tuple[str, ...]
    revocation_leaks: tuple[str, ...]
    cross_project_leaks: tuple[str, ...]
    estimated_tokens: int


@dataclass(frozen=True, slots=True)
class ArmResult:
    arm: str
    status: str
    recall_at_k: float | str
    citation_validity_rate: float | str
    conflict_misuse_rate: float | str
    revocation_leaks: int | str
    cross_project_leaks: int | str
    cases: tuple[ArmCaseResult, ...]
    token_estimate: Mapping[str, object] | str
    latency: Mapping[str, object] | str


@dataclass(frozen=True, slots=True)
class MemoryArmsReport:
    case_count: int
    memory_count: int
    project_count: int
    session_count: int
    arms: tuple[ArmResult, ...]
    gate_status: str
    gate_reason: str


# --------------------------------------------------------------------------- #
# 读取与校验
# --------------------------------------------------------------------------- #


def load_memory_arms(path: Path) -> MemoryArmsDataset:
    """读取并 fail-closed 校验 memory-arms fixture。"""

    document = _load_document(path)
    _check_fields(document, _TOP_FIELDS, "fixture 顶层")
    version = document.get("schemaVersion")
    if not isinstance(version, int) or isinstance(version, bool) or version != SCHEMA_VERSION:
        raise DatasetError(f"fixture.schemaVersion 必须为 {SCHEMA_VERSION}")

    memories = _parse_memories(document["memories"])
    memory_index = {memory.id: memory for memory in memories}
    projects = _parse_projects(document["projectSnapshots"], memory_index)
    project_index = {project.project_id: project for project in projects}
    sessions = _parse_sessions(document["sessions"], project_index)
    session_index = {session.id: session for session in sessions}
    cases = _parse_cases(document["cases"], session_index, project_index, memory_index)
    return MemoryArmsDataset(
        sessions=tuple(sessions),
        project_snapshots=tuple(projects),
        memories=tuple(memories),
        cases=tuple(cases),
    )


def _load_document(path: Path) -> Mapping[str, Any]:
    if path.suffix.lower() != ".json":
        raise DatasetError("memory-arms fixture 只接受 .json（单个文档，含共享 memories/projects）")
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raise DatasetError("memory-arms fixture 不允许 UTF-8 BOM")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise DatasetError(f"memory-arms fixture 不是合法 UTF-8（第 {exc.start} 字节）") from None
    try:
        value: object = json.loads(text)
    except json.JSONDecodeError as exc:
        raise DatasetError(f"memory-arms fixture 不是合法 JSON：{exc.msg}") from None
    if not isinstance(value, Mapping):
        raise DatasetError("memory-arms fixture 顶层必须是对象")
    return value


def _check_fields(value: Mapping[str, Any], expected: frozenset[str], where: str) -> None:
    unknown = sorted(set(value) - expected)
    if unknown:
        raise DatasetError(f"{where} 含未定义字段：{', '.join(unknown)}")
    missing = sorted(expected - set(value))
    if missing:
        raise DatasetError(f"{where} 缺少字段：{', '.join(missing)}")


def _require_str(value: object, where: str, *, max_chars: int | None = None) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DatasetError(f"{where} 必须是非空字符串")
    if max_chars is not None and len(value) > max_chars:
        raise DatasetError(f"{where} 超过 {max_chars} 字符")
    return value


def _require_list(value: object, where: str) -> Sequence[object]:
    if not isinstance(value, list):
        raise DatasetError(f"{where} 必须是数组")
    return value


def _require_int(value: object, where: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise DatasetError(f"{where} 必须是整数")
    return value


def _parse_memories(raw: object) -> list[MemoryEntry]:
    entries: list[MemoryEntry] = []
    seen: set[str] = set()
    items = _require_list(raw, "fixture.memories")
    if not items:
        raise DatasetError("fixture.memories 不能为空")
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"memories 第 {position} 项必须是对象")
        _check_fields(item, _MEMORY_FIELDS, f"memories 第 {position} 项")
        memory_id = _require_str(item["id"], f"memories 第 {position} 项 id", max_chars=128)
        if memory_id in seen:
            raise DatasetError(f"memory id 重复：{memory_id!r}")
        seen.add(memory_id)
        raw_type = item["type"]
        if not isinstance(raw_type, str):
            raise DatasetError(f"memory {memory_id!r} 的 type 必须是字符串")
        try:
            memory_type = MemoryKind(raw_type)
        except ValueError as exc:
            raise DatasetError(f"memory {memory_id!r} 的 type 不在四个取值内") from exc
        status = item["status"]
        if not isinstance(status, str) or status not in VALID_STATUSES:
            raise DatasetError(f"memory {memory_id!r} 的 status 必须是 ACTIVE 或 DISABLED")
        verification = item["verificationState"]
        if not isinstance(verification, str) or verification not in VALID_VERIFICATION_STATES:
            raise DatasetError(f"memory {memory_id!r} 的 verificationState 不在允许的五个取值内")
        superseded_by = item["supersededBy"]
        if superseded_by is not None and (not isinstance(superseded_by, str) or not superseded_by):
            raise DatasetError(f"memory {memory_id!r} 的 supersededBy 必须是 null 或非空字符串")
        updated_at = _require_str(item["updatedAt"], f"memory {memory_id!r} updatedAt")
        if _UPDATED_AT_PATTERN.fullmatch(updated_at) is None:
            raise DatasetError(
                f"memory {memory_id!r} 的 updatedAt 必须是 UTC 秒级 ISO-8601"
                "（YYYY-MM-DDTHH:MM:SSZ）"
            )
        try:
            dt.datetime.strptime(updated_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.UTC)
        except ValueError as exc:
            raise DatasetError(f"memory {memory_id!r} 的 updatedAt 不是合法日期") from exc
        entries.append(
            MemoryEntry(
                id=memory_id,
                project_id=_require_str(
                    item["projectId"], f"memory {memory_id!r} projectId", max_chars=128
                ),
                type=memory_type,
                title=_require_str(item["title"], f"memory {memory_id!r} title", max_chars=200),
                content=_require_str(
                    item["content"], f"memory {memory_id!r} content", max_chars=2000
                ),
                status=status,
                verification_state=verification,
                superseded_by=superseded_by,
                updated_at=updated_at,
            )
        )
    index = {entry.id: entry for entry in entries}
    for entry in entries:
        if entry.superseded_by is None:
            continue
        target = index.get(entry.superseded_by)
        if target is None:
            raise DatasetError(
                f"memory {entry.id!r} 的 supersededBy 指向不存在的记忆：{entry.superseded_by!r}"
            )
        if target.id == entry.id:
            raise DatasetError(f"memory {entry.id!r} 不能 supersede 自己")
        if target.project_id != entry.project_id:
            raise DatasetError(f"memory {entry.id!r} 的 supersededBy 必须同项目")
        if target.status != STATUS_ACTIVE or target.superseded_by is not None:
            raise DatasetError(f"memory {entry.id!r} 的 supersededBy 目标必须是未被替代的 ACTIVE")
    return entries


def _parse_projects(raw: object, memory_index: Mapping[str, MemoryEntry]) -> list[ProjectSnapshot]:
    projects: list[ProjectSnapshot] = []
    seen: set[str] = set()
    items = _require_list(raw, "fixture.projectSnapshots")
    if not items:
        raise DatasetError("fixture.projectSnapshots 不能为空")
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"projectSnapshots 第 {position} 项必须是对象")
        _check_fields(item, _PROJECT_FIELDS, f"projectSnapshots 第 {position} 项")
        project_id = _require_str(
            item["projectId"], f"projectSnapshots 第 {position} 项 projectId", max_chars=128
        )
        if project_id in seen:
            raise DatasetError(f"projectId 重复：{project_id!r}")
        seen.add(project_id)
        members: list[Member] = []
        member_ids: set[str] = set()
        for member_position, member in enumerate(
            _require_list(item["members"], f"project {project_id!r} members"), start=1
        ):
            if not isinstance(member, Mapping):
                raise DatasetError(
                    f"project {project_id!r} members 第 {member_position} 项必须是对象"
                )
            _check_fields(
                member, _MEMBER_FIELDS, f"project {project_id!r} members 第 {member_position} 项"
            )
            member_id = _require_str(
                member["id"], f"project {project_id!r} member id", max_chars=128
            )
            if member_id in member_ids:
                raise DatasetError(f"project {project_id!r} 的成员 id 重复：{member_id!r}")
            member_ids.add(member_id)
            members.append(
                Member(
                    id=member_id,
                    name=_require_str(
                        member["name"], f"project {project_id!r} member name", max_chars=100
                    ),
                )
            )
        if not members:
            raise DatasetError(f"project {project_id!r} 至少需要一个成员")
        memory_ids: list[str] = []
        for memory_id in _require_list(item["memoryIds"], f"project {project_id!r} memoryIds"):
            if not isinstance(memory_id, str) or not memory_id:
                raise DatasetError(f"project {project_id!r} memoryIds 必须是非空字符串数组")
            memory = memory_index.get(memory_id)
            if memory is None:
                raise DatasetError(f"project {project_id!r} 引用了不存在的 memory：{memory_id!r}")
            if memory.project_id != project_id:
                raise DatasetError(f"project {project_id!r} 的 memory {memory_id!r} 属于其他项目")
            if memory_id in memory_ids:
                raise DatasetError(f"project {project_id!r} 的 memoryIds 重复：{memory_id!r}")
            memory_ids.append(memory_id)
        actual = {entry.id for entry in memory_index.values() if entry.project_id == project_id}
        if set(memory_ids) != actual:
            missing = sorted(actual - set(memory_ids))
            raise DatasetError(
                f"project {project_id!r} 的 memoryIds 必须覆盖该项目全部记忆；缺少：{missing}"
            )
        projects.append(
            ProjectSnapshot(
                project_id=project_id,
                members=tuple(members),
                memory_ids=tuple(memory_ids),
            )
        )
    return projects


def _parse_sessions(raw: object, project_index: Mapping[str, ProjectSnapshot]) -> list[Session]:
    sessions: list[Session] = []
    seen: set[str] = set()
    items = _require_list(raw, "fixture.sessions")
    if not items:
        raise DatasetError("fixture.sessions 不能为空")
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"sessions 第 {position} 项必须是对象")
        _check_fields(item, _SESSION_FIELDS, f"sessions 第 {position} 项")
        session_id = _require_str(item["id"], f"sessions 第 {position} 项 id", max_chars=128)
        if session_id in seen:
            raise DatasetError(f"session id 重复：{session_id!r}")
        seen.add(session_id)
        project_id = _require_str(
            item["projectId"], f"session {session_id!r} projectId", max_chars=128
        )
        project = project_index.get(project_id)
        if project is None:
            raise DatasetError(f"session {session_id!r} 引用了不存在的项目：{project_id!r}")
        user_id = _require_str(item["userId"], f"session {session_id!r} userId", max_chars=128)
        if user_id not in {member.id for member in project.members}:
            raise DatasetError(f"session {session_id!r} 的 userId 不是项目成员：{user_id!r}")
        prior_ids: list[str] = []
        for prior in _require_list(
            item["priorSessionIds"], f"session {session_id!r} priorSessionIds"
        ):
            if not isinstance(prior, str) or not prior:
                raise DatasetError(f"session {session_id!r} 的 priorSessionIds 必须是字符串数组")
            if prior == session_id:
                raise DatasetError(f"session {session_id!r} 不能把自己列为 prior session")
            if prior in prior_ids:
                raise DatasetError(f"session {session_id!r} 的 priorSessionIds 含重复项")
            prior_ids.append(prior)
        sessions.append(
            Session(
                id=session_id,
                project_id=project_id,
                user_id=user_id,
                request=_require_str(
                    item["request"], f"session {session_id!r} request", max_chars=4000
                ),
                prior_session_ids=tuple(prior_ids),
            )
        )
    session_index = {session.id: session for session in sessions}
    for session in sessions:
        for prior in session.prior_session_ids:
            target = session_index.get(prior)
            if target is None:
                raise DatasetError(f"session {session.id!r} 的 priorSessionId 不存在：{prior!r}")
            if target.project_id != session.project_id:
                raise DatasetError(
                    f"session {session.id!r} 的 priorSessionId 必须同项目：{prior!r}"
                )
    return sessions


def _parse_cases(
    raw: object,
    session_index: Mapping[str, Session],
    project_index: Mapping[str, ProjectSnapshot],
    memory_index: Mapping[str, MemoryEntry],
) -> list[ArmCase]:
    items = _require_list(raw, "fixture.cases")
    if not MIN_CASES <= len(items) <= MAX_CASES:
        raise DatasetError(
            f"fixture.cases 必须包含 {MIN_CASES}–{MAX_CASES} 条（实际 {len(items)}）"
        )
    cases: list[ArmCase] = []
    seen: set[str] = set()
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"cases 第 {position} 项必须是对象")
        _check_fields(item, _CASE_FIELDS, f"cases 第 {position} 项")
        case_id = _require_str(item["id"], f"cases 第 {position} 项 id", max_chars=128)
        if case_id in seen:
            raise DatasetError(f"case id 重复：{case_id!r}")
        seen.add(case_id)
        session_id = _require_str(item["sessionId"], f"case {case_id!r} sessionId")
        session = session_index.get(session_id)
        if session is None:
            raise DatasetError(f"case {case_id!r} 引用了不存在的 session：{session_id!r}")
        project_id = _require_str(item["projectId"], f"case {case_id!r} projectId")
        if project_id != session.project_id:
            raise DatasetError(f"case {case_id!r} 的 projectId 与 session 不一致")
        if project_id not in project_index:
            raise DatasetError(f"case {case_id!r} 引用了不存在的项目：{project_id!r}")

        expected = _parse_id_list(item["expectedRecallIds"], f"case {case_id!r} expectedRecallIds")
        for memory_id in expected:
            memory = memory_index.get(memory_id)
            if memory is None:
                raise DatasetError(
                    f"case {case_id!r} 的 expectedRecallIds 含不存在记忆：{memory_id!r}"
                )
            if memory.project_id != project_id:
                raise DatasetError(
                    f"case {case_id!r} 的 expectedRecallIds 必须同项目：{memory_id!r}"
                )
            if not _is_recall_safe(memory):
                raise DatasetError(
                    f"case {case_id!r} 的 expectedRecallIds 必须 recall-safe：{memory_id!r}"
                )
        facts = _parse_text_list(
            item["expectedAnswerFacts"], f"case {case_id!r} expectedAnswerFacts"
        )
        conflicts = _parse_conflicts(item["conflicts"], case_id, project_id, memory_index)
        revocations = _parse_revocations(item["revocations"], case_id, project_id, memory_index)
        max_k = _require_int(item["maxK"], f"case {case_id!r} maxK")
        if not 1 <= max_k <= MAX_K:
            raise DatasetError(f"case {case_id!r} 的 maxK 必须在 1–{MAX_K} 之间")
        cases.append(
            ArmCase(
                id=case_id,
                session_id=session_id,
                project_id=project_id,
                expected_recall_ids=expected,
                expected_answer_facts=facts,
                conflicts=conflicts,
                revocations=revocations,
                max_k=max_k,
            )
        )
    return cases


def _parse_id_list(value: object, where: str) -> tuple[str, ...]:
    items = _require_list(value, where)
    if not items:
        raise DatasetError(f"{where} 不能为空")
    result: list[str] = []
    for item in items:
        if not isinstance(item, str) or not item:
            raise DatasetError(f"{where} 必须是非空字符串数组")
        if item in result:
            raise DatasetError(f"{where} 含重复 id：{item!r}")
        result.append(item)
    return tuple(result)


def _parse_text_list(value: object, where: str) -> tuple[str, ...]:
    items = _require_list(value, where)
    if not items:
        raise DatasetError(f"{where} 不能为空")
    result: list[str] = []
    for item in items:
        if not isinstance(item, str) or not item.strip():
            raise DatasetError(f"{where} 必须是非空字符串数组")
        if len(item) > 500:
            raise DatasetError(f"{where} 的条目超过 500 字符")
        result.append(item)
    return tuple(result)


def _parse_conflicts(
    value: object,
    case_id: str,
    project_id: str,
    memory_index: Mapping[str, MemoryEntry],
) -> tuple[Conflict, ...]:
    items = _require_list(value, f"case {case_id!r} conflicts")
    conflicts: list[Conflict] = []
    seen: set[str] = set()
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"case {case_id!r} conflicts 第 {position} 项必须是对象")
        _check_fields(item, _CONFLICT_FIELDS, f"case {case_id!r} conflicts 第 {position} 项")
        conflict_id = _require_str(item["id"], f"case {case_id!r} conflicts 第 {position} 项 id")
        if conflict_id in seen:
            raise DatasetError(f"case {case_id!r} 的 conflict id 重复：{conflict_id!r}")
        seen.add(conflict_id)
        memory_ids = _parse_id_list(
            item["memoryIds"], f"case {case_id!r} conflict {conflict_id!r} memoryIds"
        )
        if len(memory_ids) < 2:
            raise DatasetError(f"case {case_id!r} conflict {conflict_id!r} 至少需要两个记忆")
        for memory_id in memory_ids:
            memory = memory_index.get(memory_id)
            if memory is None:
                raise DatasetError(
                    f"case {case_id!r} conflict {conflict_id!r} 引用不存在记忆：{memory_id!r}"
                )
            if memory.project_id != project_id:
                raise DatasetError(
                    f"case {case_id!r} conflict {conflict_id!r} 的记忆必须同项目：{memory_id!r}"
                )
        preferred = _require_str(
            item["preferredMemoryId"],
            f"case {case_id!r} conflict {conflict_id!r} preferredMemoryId",
        )
        if preferred not in memory_ids:
            raise DatasetError(
                f"case {case_id!r} conflict {conflict_id!r} 的 "
                "preferredMemoryId 必须在 memoryIds 内"
            )
        preferred_memory = memory_index[preferred]
        if not _is_recall_safe(preferred_memory):
            raise DatasetError(
                f"case {case_id!r} conflict {conflict_id!r} 的 preferred 记忆必须 recall-safe"
            )
        conflicts.append(
            Conflict(
                id=conflict_id,
                memory_ids=memory_ids,
                preferred_memory_id=preferred,
                description=_require_str(
                    item["description"],
                    f"case {case_id!r} conflict {conflict_id!r} description",
                    max_chars=500,
                ),
            )
        )
    return tuple(conflicts)


def _parse_revocations(
    value: object,
    case_id: str,
    project_id: str,
    memory_index: Mapping[str, MemoryEntry],
) -> tuple[Revocation, ...]:
    items = _require_list(value, f"case {case_id!r} revocations")
    revocations: list[Revocation] = []
    seen: set[str] = set()
    for position, item in enumerate(items, start=1):
        if not isinstance(item, Mapping):
            raise DatasetError(f"case {case_id!r} revocations 第 {position} 项必须是对象")
        _check_fields(item, _REVOCATION_FIELDS, f"case {case_id!r} revocations 第 {position} 项")
        memory_id = _require_str(item["memoryId"], f"case {case_id!r} revocation memoryId")
        if memory_id in seen:
            raise DatasetError(f"case {case_id!r} 的 revocation memoryId 重复：{memory_id!r}")
        seen.add(memory_id)
        memory = memory_index.get(memory_id)
        if memory is None:
            raise DatasetError(f"case {case_id!r} 的 revocation 引用不存在记忆：{memory_id!r}")
        if memory.project_id != project_id:
            raise DatasetError(f"case {case_id!r} 的 revocation 记忆必须同项目：{memory_id!r}")
        reason = item["reason"]
        if not isinstance(reason, str) or reason not in VALID_REVOCATION_REASONS:
            raise DatasetError(
                f"case {case_id!r} 的 revocation reason 必须是 {sorted(VALID_REVOCATION_REASONS)}"
            )
        expected = {
            "DISABLED": memory.status == STATUS_DISABLED,
            "SUPERSEDED": memory.superseded_by is not None,
            "STALE": memory.verification_state == "STALE",
            "CONFLICTED": memory.verification_state == "CONFLICTED",
            "SOURCE_REVOKED": memory.verification_state == "SOURCE_REVOKED",
        }[reason]
        if not expected:
            raise DatasetError(
                f"case {case_id!r} 的 revocation {memory_id!r} "
                f"与记忆实际状态不一致（reason={reason}）"
            )
        revocations.append(Revocation(memory_id=memory_id, reason=reason))
    return tuple(revocations)


def _is_recall_safe(memory: MemoryEntry) -> bool:
    return (
        memory.status == STATUS_ACTIVE
        and memory.verification_state in RECALL_SAFE_STATES
        and memory.superseded_by is None
    )


# --------------------------------------------------------------------------- #
# A/B 选择语义（本地镜像）
# --------------------------------------------------------------------------- #


def _terms(request: str) -> frozenset[str]:
    """镜像 Java ``AgentMemoryService.terms``：拉丁/数字 token + 汉字 bigram + 停用词。"""

    result: set[str] = set()
    for match in _TERM_PATTERN.finditer(request.lower()):
        token = match.group(0)
        if token[0].isascii():
            result.add(token)
            continue
        points = list(token)
        for index in range(len(points) - 1):
            result.add(points[index] + points[index + 1])
    return frozenset(result - _STOPWORDS)


def _relevance_score(memory: MemoryEntry, terms: frozenset[str]) -> int:
    """镜像 Java ``score``：PREFERENCE 基础分 1；标题命中 +4，否则内容命中 +1。"""

    title = memory.title.lower()
    content = memory.content.lower()
    score = 1 if memory.type is MemoryKind.PREFERENCE else 0
    for term in terms:
        if term in title:
            score += 4
        elif term in content:
            score += 1
    return score


def legacy_context_ids(dataset: MemoryArmsDataset, case: ArmCase) -> tuple[str, ...]:
    """A LEGACY：项目内 ACTIVE，updatedAt DESC / id DESC，取前 10；不做相关性过滤。"""

    candidates = [
        memory
        for memory in dataset.memories
        if memory.project_id == case.project_id and memory.status == STATUS_ACTIVE
    ]
    candidates.sort(key=lambda memory: (memory.updated_at, memory.id), reverse=True)
    return tuple(memory.id for memory in candidates[:RECALL_LIMIT])


def governed_context_ids(dataset: MemoryArmsDataset, case: ArmCase) -> tuple[str, ...]:
    """B GOVERNED_RECALL：active + recall-safe + 请求关键词相关性，取前 10。"""

    session = next(session for session in dataset.sessions if session.id == case.session_id)
    terms = _terms(session.request)
    scored: list[tuple[int, MemoryEntry]] = []
    for memory in dataset.memories:
        if memory.project_id != case.project_id or not _is_recall_safe(memory):
            continue
        score = _relevance_score(memory, terms)
        if score > 0:
            scored.append((score, memory))
    # Java：score DESC → updatedAt DESC(nullsLast) → id ASC。稳定排序从次要键开始。
    scored.sort(key=lambda pair: pair[1].id)
    scored.sort(key=lambda pair: pair[1].updated_at, reverse=True)
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return tuple(memory.id for _, memory in scored[:RECALL_LIMIT])


# --------------------------------------------------------------------------- #
# 评测与报告
# --------------------------------------------------------------------------- #


def evaluate_memory_arms(
    dataset: MemoryArmsDataset,
    *,
    measure_latency: bool = False,
    runs: int = 20,
) -> MemoryArmsReport:
    """计算 A/B 诊断指标；C 固定 NOT_VERIFIED；门禁固定 NOT_EVALUATED。"""

    if not 1 <= runs <= 1000:
        raise ValueError("runs 必须在 1–1000 之间")
    memory_index = {memory.id: memory for memory in dataset.memories}
    ordered_cases = sorted(dataset.cases, key=lambda case: case.id)
    legacy = _evaluate_arm(
        ARM_LEGACY,
        ordered_cases,
        lambda case: legacy_context_ids(dataset, case),
        memory_index,
        measure_latency=measure_latency,
        runs=runs,
    )
    governed = _evaluate_arm(
        ARM_GOVERNED,
        ordered_cases,
        lambda case: governed_context_ids(dataset, case),
        memory_index,
        measure_latency=measure_latency,
        runs=runs,
    )
    dreaming = ArmResult(
        arm=ARM_DREAMING,
        status=NOT_VERIFIED,
        recall_at_k=NOT_VERIFIED,
        citation_validity_rate=NOT_VERIFIED,
        conflict_misuse_rate=NOT_VERIFIED,
        revocation_leaks=NOT_VERIFIED,
        cross_project_leaks=NOT_VERIFIED,
        cases=(),
        token_estimate=NOT_VERIFIED,
        latency=NOT_VERIFIED,
    )
    return MemoryArmsReport(
        case_count=len(dataset.cases),
        memory_count=len(dataset.memories),
        project_count=len(dataset.project_snapshots),
        session_count=len(dataset.sessions),
        arms=(legacy, governed, dreaming),
        gate_status=GATE_NOT_EVALUATED,
        gate_reason=GATE_REASON,
    )


def _evaluate_arm(
    arm: str,
    cases: Sequence[ArmCase],
    selector: Callable[[ArmCase], tuple[str, ...]],
    memory_index: Mapping[str, MemoryEntry],
    *,
    measure_latency: bool,
    runs: int,
) -> ArmResult:
    results: list[ArmCaseResult] = []
    for case in cases:
        full = selector(case)
        top_k = full[: case.max_k]
        returned = [memory_id for memory_id in top_k if memory_id in memory_index]
        expected = set(case.expected_recall_ids)
        recovered = tuple(memory_id for memory_id in returned if memory_id in expected)
        citation_valid = sum(
            1
            for memory_id in returned
            if memory_index[memory_id].project_id == case.project_id
            and _is_recall_safe(memory_index[memory_id])
        )
        revocation_ids = {revocation.memory_id for revocation in case.revocations}
        revocation_leaks = tuple(memory_id for memory_id in returned if memory_id in revocation_ids)
        cross_project = tuple(
            memory_id
            for memory_id in returned
            if memory_index[memory_id].project_id != case.project_id
        )
        misuses = tuple(
            conflict.id
            for conflict in case.conflicts
            if any(
                memory_id != conflict.preferred_memory_id and memory_id in returned
                for memory_id in conflict.memory_ids
            )
        )
        estimated_tokens = sum(
            math.ceil(
                (len(memory_index[memory_id].title) + len(memory_index[memory_id].content)) / 4
            )
            for memory_id in returned
        )
        results.append(
            ArmCaseResult(
                id=case.id,
                project_id=case.project_id,
                returned_ids=tuple(returned),
                expected_recall_ids=case.expected_recall_ids,
                recovered_ids=recovered,
                recall=len(recovered) / len(case.expected_recall_ids),
                citation_valid=citation_valid,
                citation_total=len(returned),
                conflict_misuses=misuses,
                revocation_leaks=revocation_leaks,
                cross_project_leaks=cross_project,
                estimated_tokens=estimated_tokens,
            )
        )

    case_count = len(results)
    recall_at_k = sum(result.recall for result in results) / case_count
    returned_total = sum(result.citation_total for result in results)
    citation_valid_total = sum(result.citation_valid for result in results)
    conflicts_total = sum(len(case.conflicts) for case in cases)
    misused_total = sum(len(result.conflict_misuses) for result in results)
    revocation_total = sum(len(result.revocation_leaks) for result in results)
    cross_project_total = sum(len(result.cross_project_leaks) for result in results)
    token_total = sum(result.estimated_tokens for result in results)

    latency: Mapping[str, object] | str = NOT_VERIFIED
    if measure_latency:
        latency = _measure_latency(cases, selector, runs)
    return ArmResult(
        arm=arm,
        status="DIAGNOSTIC_ONLY",
        recall_at_k=recall_at_k,
        citation_validity_rate=(
            citation_valid_total / returned_total if returned_total else NOT_VERIFIED
        ),
        conflict_misuse_rate=(misused_total / conflicts_total if conflicts_total else NOT_VERIFIED),
        revocation_leaks=revocation_total,
        cross_project_leaks=cross_project_total,
        cases=tuple(results),
        token_estimate={
            "source": "ESTIMATE",
            "method": "ceil(chars/4)",
            "total": token_total,
            "meanPerCase": round(token_total / case_count, 3),
        },
        latency=latency,
    )


def _measure_latency(
    cases: Sequence[ArmCase],
    selector: Callable[[ArmCase], tuple[str, ...]],
    runs: int,
) -> Mapping[str, object]:
    """只测本地检索函数耗时；样本为 ``cases × runs``，最近秩法 P50/P95。"""

    samples: list[int] = []
    for case in cases:
        for _ in range(runs):
            start = time.perf_counter_ns()
            selector(case)
            samples.append(time.perf_counter_ns() - start)
    samples.sort()
    return {
        "p50Ms": _percentile_nearest_rank(samples, 50),
        "p95Ms": _percentile_nearest_rank(samples, 95),
        "samples": len(samples),
        "scope": "local-retrieval-only-non-production",
        "note": "只测本地选择函数，不含模型、网络、数据库；不得当作生产延迟。",
    }


def _percentile_nearest_rank(sorted_samples: Sequence[int], percentile: int) -> float:
    if not sorted_samples:
        raise ValueError("延迟样本不能为空")
    rank = max(1, math.ceil(percentile / 100 * len(sorted_samples)))
    return round(sorted_samples[rank - 1] / 1_000_000, 3)


def report_document(report: MemoryArmsReport) -> dict[str, Any]:
    """把报告转成待 canonical 化的 JSON 对象（未含 SHA-256）。"""

    arms: dict[str, Any] = {}
    for arm in report.arms:
        if arm.arm == ARM_DREAMING:
            arms[arm.arm] = {
                "status": NOT_VERIFIED,
                "definition": ARM_DEFINITIONS[arm.arm],
                "reason": "未实现：仓库无 Job dispatcher / 候选发布实现；不得用 stub 冒充。",
                "recallAtK": NOT_VERIFIED,
                "citationValidityRate": NOT_VERIFIED,
                "conflictMisuseRate": NOT_VERIFIED,
                "revocationLeaks": NOT_VERIFIED,
                "crossProjectLeaks": NOT_VERIFIED,
                "taskAccuracy": NOT_VERIFIED,
                "tokens": NOT_VERIFIED,
                "latency": NOT_VERIFIED,
                "dreamingCost": NOT_VERIFIED,
                "humanApprovalRate": NOT_VERIFIED,
                "cases": [],
            }
            continue
        arms[arm.arm] = {
            "status": arm.status,
            "definition": ARM_DEFINITIONS[arm.arm],
            "recallAtK": arm.recall_at_k,
            "citationValidityRate": arm.citation_validity_rate,
            "conflictMisuseRate": arm.conflict_misuse_rate,
            "revocationLeaks": arm.revocation_leaks,
            "crossProjectLeaks": arm.cross_project_leaks,
            "taskAccuracy": NOT_VERIFIED,
            "tokens": arm.token_estimate,
            "latency": arm.latency,
            "dreamingCost": NOT_VERIFIED,
            "humanApprovalRate": NOT_VERIFIED,
            "cases": [
                {
                    "id": result.id,
                    "projectId": result.project_id,
                    "returnedIds": list(result.returned_ids),
                    "expectedRecallIds": list(result.expected_recall_ids),
                    "recoveredIds": list(result.recovered_ids),
                    "recall": result.recall,
                    "citationValid": result.citation_valid,
                    "citationTotal": result.citation_total,
                    "conflictMisuses": list(result.conflict_misuses),
                    "revocationLeaks": list(result.revocation_leaks),
                    "crossProjectLeaks": list(result.cross_project_leaks),
                    "estimatedTokens": result.estimated_tokens,
                }
                for result in arm.cases
            ],
        }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "dataset": {
            "cases": report.case_count,
            "memories": report.memory_count,
            "projects": report.project_count,
            "sessions": report.session_count,
        },
        "arms": arms,
        "gate": {"status": report.gate_status, "reason": report.gate_reason},
        "limitations": LIMITATIONS,
        "evidence": EVIDENCE_LOCAL_MIRROR,
        "qualityClaim": NOT_VERIFIED,
    }


def seal_report(document: Mapping[str, Any]) -> dict[str, Any]:
    """追加 ``sha256``：摘要是 ``canonical(document 去掉 sha256 键)`` 的 SHA-256。"""

    payload = {key: value for key, value in document.items() if key != "sha256"}
    sealed = dict(payload)
    sealed["sha256"] = digest(canonicalize_document(payload))
    return sealed


def canonical_report_bytes(document: Mapping[str, Any]) -> bytes:
    """``seal_report`` + canonical 编码：CLI ``--json`` 输出的精确字节。"""

    return canonicalize_document(seal_report(document))


__all__ = [
    "ARMS",
    "ARM_DEFINITIONS",
    "ARM_DREAMING",
    "ARM_GOVERNED",
    "ARM_LEGACY",
    "EVIDENCE_LOCAL_MIRROR",
    "GATE_NOT_EVALUATED",
    "GATE_REASON",
    "LIMITATIONS",
    "MAX_CASES",
    "MAX_K",
    "MIN_CASES",
    "NOT_VERIFIED",
    "RECALL_LIMIT",
    "ArmCase",
    "ArmCaseResult",
    "ArmResult",
    "Conflict",
    "Member",
    "MemoryArmsDataset",
    "MemoryArmsReport",
    "MemoryEntry",
    "ProjectSnapshot",
    "Revocation",
    "Session",
    "canonical_report_bytes",
    "evaluate_memory_arms",
    "governed_context_ids",
    "legacy_context_ids",
    "load_memory_arms",
    "report_document",
    "seal_report",
]
