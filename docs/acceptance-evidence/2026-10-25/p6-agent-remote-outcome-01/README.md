# P6-B 验收证据

- [修复前单测](before-unit.json)：FAILED 与 UNKNOWN 的真实失败，以及取消后仍有副作用的刻画。
- [修复前 PostgreSQL](before-postgres.json)：新 ToolCall 的额外执行、UNKNOWN 覆盖风险；原恢复防护通过情况。
- [状态机 / T01–T14](state-machine-matrix.md)：方法、操作次数、Invocation/Run/事件断言与保证边界。
- [完整后端统计](backend-verify.json)：本次 Maven verify 1730 项，0 失败/错误、34 既有 opt-in 跳过；不计陈旧报告。
- [最终补充契约](final-targeted.json)：最后的 V66 → V67 数据回填与 MCP 错误语义，4 项全过。
- [关键容器类守卫](required-container-tests.json)：16 类实际执行。
- [Source Manifest](source-manifest.json)：最终生产/测试/CI 文件 SHA-256；不包含自引用文档哈希。
- [Git 与前端检查](safety-audit.json)：基线、调度器 blob 一致性、前端 393 项、main/PR 开工状态及外部验证边界。
- [实现提交的 GitHub 回执](github-code-receipt.json)：完整 SHA、实际 Actions 链接和记录时 Job 状态；最终文档提交的 CI 按最终回复核实。

交付正文见 [A–F 报告](../../../archive/reports/p6-agent-remote-outcome-01-delivery-20261025.md)。
真实外部 Provider / MCP 取消确认：`LIVE_NOT_RUN`；不声称远端撤销或跨系统 Exactly-Once。
CI 运行链接和实际结果按交付报告及最终 GitHub 回执核对，未完成时只标 CI_PENDING。
