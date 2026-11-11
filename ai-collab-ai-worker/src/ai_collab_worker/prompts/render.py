"""PromptStore 之上的"取当前版本 + 渲染占位符"工具。

设计依据：设计 §7.2（PromptPack 唯一来源、版本化、只加不删）与 W4 冻结文档 §1/§2。

两条硬约束：

1. **只经 :class:`~ai_collab_worker.prompts.store.PromptStore` 读取**：本模块不直接读文件，
   因此 ``sha256``/``chars``/``variables`` 的逐次校验与 ``PROMPT_UNAVAILABLE`` 语义完全复用
   存储层，不出现第二条资产读取路径；
2. **版本取 manifest 里的最高版本**：``PromptStore.manifest()`` 已按 ``(key, version)``
   升序排列，``YYYY-MM-DD.N`` 字典序即版本序；调用方不需要硬编码版本号，改 Prompt 发新版
   后服务自动跟随，旧版本仍可被显式读取。

渲染只做一次 ``{{ name }}`` 替换，替换值里的 ``{{ ... }}`` 不会被二次展开（避免注入模板）。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Final

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.prompts import PLACEHOLDER_PATTERN, PromptDocument
from ai_collab_worker.prompts.store import PromptStore

#: 对外可见的固定短语（不含路径与正文）。
REASON_KEY_MISSING: Final[str] = "prompt key missing from manifest"


def load_current(store: PromptStore, key: str) -> PromptDocument:
    """读取 ``key`` 在 manifest 中的最高版本模板。

    key 不在 manifest 内 → ``PROMPT_UNAVAILABLE``(503)：这是部署资产缺失，不是调用方错误，
    与 ``PromptStore.get`` 遇到损坏模板时的语义一致（Java 侧应回退"上次成功版本"）。
    """

    manifest = store.manifest()
    versions = [entry.version for entry in manifest.prompts if entry.key == key]
    if not versions:
        raise AppError(
            ErrorCode.PROMPT_UNAVAILABLE,
            "Prompt 不可用",
            status_code=503,
            details={"reason": REASON_KEY_MISSING, "key": key},
        )
    return store.get(key, max(versions))


def render_template(text: str, variables: Mapping[str, str]) -> str:
    """把 ``{{ name }}`` 占位符替换成 ``variables[name]``。

    缺少变量时抛 ``ValueError``：PromptStore 已保证模板占位符与 manifest 一致，因此这里
    缺变量只可能是代码与模板版本脱节，属于编程错误，交由统一错误处理器记为 500。
    """

    missing: set[str] = set()

    def _replace(match: re.Match[str]) -> str:
        name = match.group(1)
        value = variables.get(name)
        if value is None:
            missing.add(name)
            return ""
        return value

    rendered = PLACEHOLDER_PATTERN.sub(_replace, text)
    if missing:
        msg = f"模板缺少变量：{', '.join(sorted(missing))}"
        raise ValueError(msg)
    return rendered


__all__ = ["REASON_KEY_MISSING", "load_current", "render_template"]
