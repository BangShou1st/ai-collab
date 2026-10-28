# P6-C 验收矩阵

结果以对应测试回执为准。最终定向47项已通过（专项23、恢复24）；C01–C10/I01–I06均通过；I07以最终SHA CI聊天回执补齐。

| ID | 实际验证入口 | 断言与口径 | 结果 |
| --- | --- | --- | --- |
| C01 | AgentRuntimeMetricsTest.c01c04FixedInvocationOutcomesAndPrivateTags; c01MetricBackendFailureNeverChangesBusinessResults; AgentToolSchedulerTest | 固定标签/类型、观测后端失败隔离；P6-A 调度器源码 blob 不变 | PASS |
| C02 | AgentRuntimeMetricsTest.c02AttemptAllReturnOutcomesAndUnexpectedExit; c02c07ActualWorkerExceptionCleansMdcAndCountsOnce; c02c07WorkerReturnAndCancellationPathsPreserveCorrelation | attempt/Timer、实际 Worker 正常/异常/取消 | PASS |
| C03 | AgentRuntimeMetricsTest.c03ModelSuccessAndBoundedFailureClassification; AgentModelTransportMetricsTest; RoutingAgentModelExecutorRequestConsistencyTest.c03MetricsCountRealRoutingCallsAndExcludeConfigurationRejection | 主轮次/摘要、传输线程信号、SSRF/配置未出站不计、固定异常分类 | PASS |
| C04 | AgentRuntimeMetricsTest.c01c04FixedInvocationOutcomesAndPrivateTags; AgentObservabilityPostgresTest.i04RollbackNeverCountsAndCommittedReplayCannotCountAgain | 六种结果分别统计，UNKNOWN 独立 | PASS |
| C05 | AgentObservabilityPostgresTest.i04RollbackNeverCountsAndCommittedReplayCannotCountAgain; i04BatchCleanupUnknownAndCanceledCommitExactlyOnce | 旧结果读取/重复覆盖/取消重放不重复计数 | PASS |
| C06 | AgentRuntimeMetricsTest.c06SnapshotFailureNeverFakesZeroAndScrapeNeverQueriesDatabase | 首次NaN，last-good，age增长，failure，scrape无SQL | PASS |
| C07 | AgentRuntimeMetricsTest.c07ThreadReuseAndNestedScopeRestoreOnExceptionAndCancellation; c02c07ActualWorkerExceptionCleansMdcAndCountsOnce; c02c07WorkerReturnAndCancellationPathsPreserveCorrelation | 成功/异常/取消、线程复用与嵌套MDC清理 | PASS |
| C08 | AgentRuntimeMetricsTest.c08FailureLogsNeverIncludeSensitiveExceptionText; c01c04FixedInvocationOutcomesAndPrivateTags; RoutingAgentModelExecutorRequestConsistencyTest | 捕获日志不含secret异常正文；标签无ID/Prompt/模型名，安全pattern保留requestId | PASS |
| C09 | AgentActuatorSecurityTest | 加载真实application.yml、真实SecurityConfig/Actuator；health无details、默认metrics404(已认证)、显式开放仍JWT | PASS |
| C10 | AgentRuntimeMetricsTest.c10RealExecutorSaturationButIdlePollDoesNotCount | 真实ThreadPoolTaskExecutor饱和拒绝2次、空轮询0 | PASS |
| I01 | AgentObservabilityPostgresTest.i01UpgradePreservesEveryExistingRowAndUsesPartialIndex | V67→V68；3万行全行指纹相同；实际EXPLAIN命中部分索引 | PASS |
| I02 | AgentObservabilityPostgresTest.i02i03SnapshotMatchesDurableFactsAndLeaseRecovery | 10条UNKNOWN、年龄含dispatch/fallback，Run数量与过期Lease | PASS |
| I03 | AgentObservabilityPostgresTest.i02i03SnapshotMatchesDurableFactsAndLeaseRecovery; AgentPauseResumePostgresTest; AgentRemoteOutcomePostgresTest | Lease续期Gauge归零且claim不变；实际接管/fencing由P6-B回归覆盖 | PASS |
| I04 | AgentObservabilityPostgresTest.i04RollbackNeverCountsAndCommittedReplayCannotCountAgain; i04BatchCleanupUnknownAndCanceledCommitExactlyOnce | 真实事务回滚成功/UNKNOWN/取消不计，提交后单计，Timer提交后发布 | PASS |
| I05 | AgentToolSchedulerTest; AgentRemoteOutcomePostgresTest; AgentRemoteOutcomeMigrationPostgresTest; AgentPauseResumePostgresTest; PersistedModelTurnRecoveryPostgresTest | 完整verify通过（见backend-verify.json） | PASS |
| I06 | bash ./mvnw -B -ntp verify | 完整verify通过（见backend-verify.json） | PASS |
| I07 | 最终HEAD push/PR Actions 与 Git复核 | CI_PENDING，最终回执见交付报告及聊天 | CI_PENDING |
