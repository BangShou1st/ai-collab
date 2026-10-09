# D1-D8 交付后复核证据

基线：`9707012`，2026-10-09。这里只保存本轮新执行证据，不沿用上轮红灯作为本轮结果。

- `D1D8FollowupProbe.java.txt`：两个行为探针的最终源文件，实际生产 Composer/Summarizer/Coordinator/Routing，仓库/Native 出站/配置存储受控。
- `probe-output.txt`：失败压缩未切当前模型、辅助失败后切小窗口未重新组装，2 项断言失败。
- `D2GoalRevisionPostgresProbe.java.txt`：真实 PostgreSQL 目标修订并发探针。触发器与 advisory lock 只控制时间交错；真实发布 SQL/事务/目标更新均执行。
- `postgres-probe-output.txt`：当前目标修订 1、旧摘要 COMMITTED 且修订 0，1 项断言失败。
- `formal-*.xml`：本轮重跑的 8 个正式测试类 Surefire XML，合计 75 项通过、0 失败、0 错误、0 跳过。

临时源文件仅放在后端 ignored `target/review-d1d8-20261009` 下编译执行，没有进入正式测试目录，没有修改生产文件或业务配置。

编译/执行使用 `E:\Java\jdk-21.0.10\bin\javac.exe`、`java.exe`，classpath 来自当前 Surefire XML 的 `java.class.path`。输出归档是运行日志原样复制。首次夹具的摘要类型识别失误已修正重跑，最终日志与最终源文件一致。

正式回归命令：

```powershell
.\mvnw.cmd -o '-Dtest=AgentContextReliabilityD1D8RegressionTest,AgentRunContextCompactionRegressionTest,AgentAuxiliaryOutboundRegressionTest,LegacyReadOnlyAgentExecutorTest,AgentRuntimeRequestSnapshotTest,AgentModelMessageComposerV2Test,AgentContextSummarizerTest' test
.\mvnw.cmd -o '-Dtest=AgentRunContextCommitPostgresTest' test
```

结果分别为 67 项通过、8 项通过。不是后端全量结果。

本轮只是证明缺陷仍存在，没有修复，故不声称已完成红绿。真实模型多周期质量与浏览器路径未验证。
