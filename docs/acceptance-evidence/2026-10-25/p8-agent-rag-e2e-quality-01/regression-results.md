# 本轮工程验证

| 命令组 | 状态 | Tests | Failures / Errors / Skipped | 退出码 |
| --- | --- | ---: | --- | ---: |
| retrieval | PASS | 5 | 0 / 0 / 0 | 0 |
| integration-attempt1 | INCONCLUSIVE | 1 | 0 / 0 / 0 | 0 |
| integration-attempt2 | INCONCLUSIVE | 1 | 0 / 0 / 0 | 0 |
| regression | PASS | 155 | 0 / 0 / 0 | 0 |
| integration | PASS | 1 | 0 / 0 / 0 | 0 |
| verify | PASS | 1794 | 0 / 0 / 35 | 0 |

完整命令、UTC 起止时间、逐测试类实际执行/跳过数、输出哈希与本地 XML 路径见 [test-matrix.json](test-matrix.json)。报告展示日期按北京时间；原始机器时间保留时区。完整 verify 的跳过均须按类核对，不能把 opt-in 跳过视作质量通过。

初次定向命令包含不存在的 AgentCrashRecoveryPostgresTest 类名，该项并未执行；真实类 AgentCrashRecoveryTimingPostgresTest 由完整 verify 覆盖。初次使用的 surefire.reportsDirectory 参数没有改变本工程的输出位置，已按运行时段把真实 surefire-reports 归档到对应 target 子目录；后续 runner 直接归档，不依赖该参数。

integration-attempt1 保留初步运行的控制台证据；首次逐类 XML 已被第二次复测替换，故不为它补造逐类结果。integration-attempt2 的断言虽通过，Q02/Q07 辅助模型分支未显式脚本化，记 INCONCLUSIVE。最终 integration 明确返回 EMPTY 摘要并计数，才作为最终链路证据。MinIO 127.0.0.1:1 连接拒绝为隔离测试的预期后台初始化告警；没有操作应用存储。

本轮没有前端改动，前端验证 NOT_EXECUTED；外部生成用例 BLOCKED_EXTERNAL。
