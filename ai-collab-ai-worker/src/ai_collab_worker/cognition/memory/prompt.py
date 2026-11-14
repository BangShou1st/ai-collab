"""Memory 候选提取的 Prompt 渲染（模板来自 PromptStore，禁止硬编码正文）。

模板 key：

- ``memory.extract.system``：角色、候选 JSON 形状、冻结 §3.5 的字段边界、
  "dedupeKey 不要自行计算"与"只输出单个 JSON object"纪律；变量 ``maxCandidates``；
- ``memory.extract.user``：变量 ``snapshotJson``（只含 facts 与 existingMemories）与
  ``allowedSourceRefsJson``（合法 ``sourceRef`` 清单）。

快照里的 job/lease 元数据**不进 Prompt**：它们对生成没有帮助，却会把内部租约信息带进
模型上下文（设计 §3.3 要求响应不含租约之外的内部细节）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.memory_job import MemoryJobSnapshot
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore

#: PromptPack key：只加不删，改文案即发新版本。
MEMORY_SYSTEM_KEY: Final[str] = "memory.extract.system"
MEMORY_USER_KEY: Final[str] = "memory.extract.user"


@dataclass(frozen=True)
class MemoryPrompt:
    """一次 memory 提取需要的两段文本与主 Prompt 版本。"""

    system: str
    user: str
    version: str


def build_memory_prompt(
    store: PromptStore,
    snapshot: MemoryJobSnapshot,
    *,
    max_candidates: int,
) -> MemoryPrompt:
    """从 PromptStore 取模板并渲染；``version`` 取 memory.extract.system 的版本。"""

    system = load_current(store, MEMORY_SYSTEM_KEY)
    user = load_current(store, MEMORY_USER_KEY)
    snapshot_json = _compact_json(
        {
            "facts": [fact.model_dump(by_alias=True, mode="json") for fact in snapshot.facts],
            "existingMemories": [
                memory.model_dump(by_alias=True, mode="json")
                for memory in snapshot.existing_memories
            ],
        }
    )
    variables = {
        "maxCandidates": str(max_candidates),
        "snapshotJson": snapshot_json,
        "allowedSourceRefsJson": _compact_json(sorted(snapshot.source_refs())),
    }
    return MemoryPrompt(
        system=render_template(system.text, variables),
        user=render_template(user.text, variables),
        version=system.version,
    )


def prompt_size_bytes(prompt: MemoryPrompt) -> int:
    """system + user 的 UTF-8 字节数（Java 网关 64 KiB prompt 上限的判定口径）。"""

    return len(prompt.system.encode("utf-8")) + len(prompt.user.encode("utf-8"))


def _compact_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


__all__ = [
    "MEMORY_SYSTEM_KEY",
    "MEMORY_USER_KEY",
    "MemoryPrompt",
    "build_memory_prompt",
    "prompt_size_bytes",
]
