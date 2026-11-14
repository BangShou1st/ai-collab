"""Agent decide 的 Prompt 渲染与纠错指令（模板来自 PromptStore）。

模板 key：

- ``decide.system``：角色、四决策、JSON 形状、"只输出单个 JSON object"纪律；
  变量 ``scenarioCode``/``goal``/``locale``/``allowWrite``/``allowExternal``/``toolsJson``；
- ``decide.user``：变量 ``contextJson``/``toolsJson``。

`promptVersion` 取 **decide.system**（主 Prompt）的 manifest 版本。内部重试时在 user prompt
末尾追加 :func:`build_correction_prompt` 生成的固定纠错段：只含固定中文说明与解析层的固定
reason 短语，绝不回显模型原文（防止把注入内容再喂回模型）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.agent_decide import AgentDecideRequest
from ai_collab_worker.prompts.render import load_current, render_template
from ai_collab_worker.prompts.store import PromptStore

#: PromptPack key：只加不删，改文案即发新版本。
DECIDE_SYSTEM_KEY: Final[str] = "decide.system"
DECIDE_USER_KEY: Final[str] = "decide.user"

#: 纠错段的最坏字节数预留（实际约 250–350 字节）；拼接后的 prompt 与它一起受 64 KiB 约束。
MAX_CORRECTION_BYTES: Final[int] = 512


@dataclass(frozen=True)
class DecidePrompt:
    """一次 decide 调用需要的两段文本与主 Prompt 版本。"""

    system: str
    user: str
    version: str


def build_decide_prompt(store: PromptStore, request: AgentDecideRequest) -> DecidePrompt:
    """从 PromptStore 取模板并渲染；``version`` 取 decide.system 的 manifest 版本。"""

    system = load_current(store, DECIDE_SYSTEM_KEY)
    user = load_current(store, DECIDE_USER_KEY)
    tools_json = _compact_json(
        [tool.model_dump(by_alias=True, mode="json") for tool in request.tools]
    )
    context_json = _compact_json(request.context.model_dump(by_alias=True, mode="json"))
    variables = {
        "scenarioCode": request.scenario.code,
        "goal": request.goal,
        "locale": request.locale,
        "allowWrite": "true" if request.policy.allow_write else "false",
        "allowExternal": "true" if request.policy.allow_external else "false",
        "toolsJson": tools_json,
        "contextJson": context_json,
    }
    return DecidePrompt(
        system=render_template(system.text, variables),
        user=render_template(user.text, variables),
        version=system.version,
    )


def build_correction_prompt(user_prompt: str, reason: str) -> str:
    """在 user prompt 末尾追加固定纠错段（用于内部只重试一次的第二次调用）。"""

    correction = (
        "\n\n<CORRECTION>\n"
        f"上一次输出未通过校验（原因：{reason}）。请只输出一个 JSON object，形状为 "
        '{"decision":"CALL_TOOL|FINAL|ASK_USER|NO_OP",'
        '"toolCall":{"name":"工具名","arguments":{}}}。'
        "CALL_TOOL 的 name 必须原样取自 <TOOLS_JSON>，arguments 必须是 JSON object；"
        "其它决策不要带 toolCall。不要 Markdown、不要解释。\n"
        "</CORRECTION>\n"
    )
    return user_prompt + correction


def prompt_size_bytes(prompt: DecidePrompt) -> int:
    """system + user 的 UTF-8 字节数（Java 网关 64 KiB prompt 上限的判定口径）。"""

    return len(prompt.system.encode("utf-8")) + len(prompt.user.encode("utf-8"))


def _compact_json(value: object) -> str:
    """紧凑 UTF-8 JSON（不转义中文），作为 Prompt 变量原样嵌入。"""

    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


__all__ = [
    "DECIDE_SYSTEM_KEY",
    "DECIDE_USER_KEY",
    "MAX_CORRECTION_BYTES",
    "DecidePrompt",
    "build_correction_prompt",
    "build_decide_prompt",
    "prompt_size_bytes",
]
