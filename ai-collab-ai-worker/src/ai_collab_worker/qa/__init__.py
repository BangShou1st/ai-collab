"""知识问答（P3）：常量、转义、输出安全、引用校验与证据装配的移植实现。

模块布局：

- :mod:`constants`：契约冻结常量（阈值 / 预算 / 拒答文案）；
- :mod:`prompt_text`：``KnowledgePromptText.escapeXmlText`` 的逐字节移植；
- :mod:`safety`：``KnowledgeOutputSafetyGuard`` 的 k-gram 指纹预检（Q9=a 发送侧）；
- :mod:`citation`：``KnowledgeCitationValidator``（``[Sn]`` 对照 / 拒答判定）；
- :mod:`assembly`：``KnowledgeContextBuilder.buildWithTokenBudget`` 与
  ``TokenEstimator`` 的等价移植（证据装配 + token 计量）；
- :mod:`pipeline`：问答编排（消歧 → 路由 → 检索 → 装配 → 生成 → 预检 → finalize）。
"""

from ai_collab_worker.qa import constants

__all__ = ["constants"]
