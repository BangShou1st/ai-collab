"""Planning 认知模块：草稿 Prompt 渲染、模型输出解析与 Python 侧自校验。

三段职责严格分开（与 rerank/query/decide 的组织方式一致）：

- ``prompt.py``：只经 :class:`~ai_collab_worker.prompts.store.PromptStore` 取模板并渲染；
- ``parser.py``：只负责"单个 JSON object + 契约形状"（围栏容忍、字段缺失/多余/类型错）；
- ``validator.py``：冻结 §1 第 1–8 条的跨条目自校验（数量/tempKey/引用/依赖环/日期/成员/
  来源），**不调用模型、不写库**。

校验失败一律 ``SCHEMA_VIOLATION``(422)，``details.reason`` 是固定短语，绝不回显模型原文。
"""

from __future__ import annotations

__all__: list[str] = []
