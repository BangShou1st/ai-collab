"""Planning 草稿的 Prompt 渲染（模板来自 PromptStore，禁止在模块里硬编码正文）。

模板 key：

- ``planning.draft.system``：角色、JSON 形状、冻结 §1 的硬约束（数量/依赖/日期/成员/来源/
  文本上限）、"只输出单个 JSON object"纪律；变量 ``template``/``locale``/``maxTasks``/
  ``maxMilestones``/``startDate``/``dueDate``；
- ``planning.draft.user``：变量 ``goal``/``projectFactsJson``/``allowedSourceRefsJson``。

版本取 manifest 中的最高版本（``prompts.render.load_current``），改 Prompt 发新版后自动跟随。
``allowedSourceRefsJson`` 直接把派生集合交给模型：模型只能从这份清单里选，在线自校验会丢弃
清单外的引用。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.cognition.planning.validator import allowed_source_refs
from ai_collab_worker.contract.planning import PlanningDraftRequest
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore

#: PromptPack key：只加不删，改文案即发新版本。
PLANNING_SYSTEM_KEY: Final[str] = "planning.draft.system"
PLANNING_USER_KEY: Final[str] = "planning.draft.user"


@dataclass(frozen=True)
class PlanningPrompt:
    """一次 planning 调用需要的两段文本与主 Prompt 版本。"""

    system: str
    user: str
    version: str


def build_planning_prompt(store: PromptStore, request: PlanningDraftRequest) -> PlanningPrompt:
    """从 PromptStore 取模板并渲染；``version`` 取 planning.draft.system 的 manifest 版本。"""

    system = load_current(store, PLANNING_SYSTEM_KEY)
    user = load_current(store, PLANNING_USER_KEY)
    facts_json = _compact_json(request.project_facts.model_dump(by_alias=True, mode="json"))
    allowed_refs = _compact_json(sorted(allowed_source_refs(request.project_facts)))
    variables = {
        "template": request.template.value,
        "locale": request.locale,
        "maxTasks": str(request.constraints.max_tasks),
        "maxMilestones": str(request.constraints.max_milestones),
        "startDate": request.constraints.start_date or "",
        "dueDate": request.constraints.due_date or "",
        "goal": request.goal,
        "projectFactsJson": facts_json,
        "allowedSourceRefsJson": allowed_refs,
    }
    return PlanningPrompt(
        system=render_template(system.text, variables),
        user=render_template(user.text, variables),
        version=system.version,
    )


def prompt_size_bytes(prompt: PlanningPrompt) -> int:
    """system + user 的 UTF-8 字节数（Java 网关 64 KiB prompt 上限的判定口径）。"""

    return len(prompt.system.encode("utf-8")) + len(prompt.user.encode("utf-8"))


def _compact_json(value: object) -> str:
    """紧凑 UTF-8 JSON（不转义中文），作为 Prompt 变量原样嵌入。"""

    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


__all__ = [
    "PLANNING_SYSTEM_KEY",
    "PLANNING_USER_KEY",
    "PlanningPrompt",
    "build_planning_prompt",
    "prompt_size_bytes",
]
