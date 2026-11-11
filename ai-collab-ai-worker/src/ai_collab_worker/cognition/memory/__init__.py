"""Memory 认知模块：候选提取 Prompt 渲染与候选解析（不发布、不写库、不调度）。

两段职责：

- ``prompt.py``：只经 :class:`~ai_collab_worker.prompts.store.PromptStore` 读取模板并渲染；
  交给模型的只有快照的 ``facts``/``existingMemories`` 与合法的 ``sourceRef`` 清单；
- ``parser.py``：解析候选列表，按冻结 §3.5 强制来源引用与 ``dedupeKey``：
  - ``sourceRef`` 不在快照内 → **丢弃该候选**并计数（不报错）；
  - ``dedupeKey`` 由 Python 计算 ``sha256(kind|normalized(title)|normalized(content))``；
    模型省略时由本侧补齐，模型给了不一致的值时丢弃该候选（防编造幂等键）。

解析失败（非 JSON / 候选字段越界 / 超过条数上限）抛 :class:`MemoryCandidateError`，
由端点映射成 ``SCHEMA_VIOLATION``(422)；``reason`` 是固定短语，不回显模型原文。
"""

from __future__ import annotations

__all__: list[str] = []
