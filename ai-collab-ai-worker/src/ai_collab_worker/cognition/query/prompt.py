"""Query plan 的 Prompt 渲染（模板来自 PromptStore，禁止在本模块硬编码正文）。

模板 key 与版本口径见 ``prompts/README.md``：

- ``query_plan.system``：角色、JSON 形状、字段规则与"只输出单个 JSON object"纪律；
- ``query_plan.user``：渲染 ``locale``/``query``/``historySummary``/``documentIds`` 四个变量。

版本取 manifest 中的最高版本（``prompts.render.load_current``），改 Prompt 发新版后自动跟随。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.query_plan import QueryPlanRequest
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore

#: PromptPack key：只加不删，改文案即发新版本。
QUERY_PLAN_SYSTEM_KEY: Final[str] = "query_plan.system"
QUERY_PLAN_USER_KEY: Final[str] = "query_plan.user"


@dataclass(frozen=True)
class QueryPlanPrompt:
    """一次 query plan 调用需要的两段文本与实际使用的版本。"""

    system: str
    user: str
    system_version: str
    user_version: str


def build_query_plan_prompt(store: PromptStore, request: QueryPlanRequest) -> QueryPlanPrompt:
    """从 PromptStore 取模板并渲染；返回 system/user 与实际版本。"""

    system = load_current(store, QUERY_PLAN_SYSTEM_KEY)
    user = load_current(store, QUERY_PLAN_USER_KEY)
    variables = {
        "locale": request.locale,
        "query": request.query,
        "historySummary": request.history_summary,
        "documentIds": json.dumps(
            list(request.document_ids), ensure_ascii=False, separators=(",", ":")
        ),
    }
    return QueryPlanPrompt(
        system=render_template(system.text, variables),
        user=render_template(user.text, variables),
        system_version=system.version,
        user_version=user.version,
    )


__all__ = [
    "QUERY_PLAN_SYSTEM_KEY",
    "QUERY_PLAN_USER_KEY",
    "QueryPlanPrompt",
    "build_query_plan_prompt",
]
