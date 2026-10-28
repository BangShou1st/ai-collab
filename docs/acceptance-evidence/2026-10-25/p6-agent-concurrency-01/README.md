# P6-AGENT-CONCURRENCY-01 验收证据

日期：2026-10-25（Asia/Shanghai）。数据均为临时测试容器或合成负载，不含用户业务资料与凭据。

- `s0-baseline.txt`：修改生产源码前，在 dev@8ee1cebbf43ab3c1b06a4982d3f3d4ad70ec60ba 上执行的原始数量结果。
- `summary.csv`：旧版/新版、每条件三次独立调度器实例的成对实验；P50/P95 为提交至实际 Callable 结束的延迟。
- `latency-samples.csv`：每个成功样本的原始毫秒延迟，拒绝数量见 summary。
- `environment.txt`：JDK/系统/CPU可见核心数/负载参数。
- `local-tests.json`：本地 Surefire 全量摘要及所有类数量，不存原始日志或 JVM 全部环境属性。
- `source-manifest.json`：实现与测试文件的 Git blob，用于核对本证据对应实现，避免自引用 commit SHA。

复现命令（仓库 `ai-collab-backend` 下）：

```powershell
& 'E:/Git/bin/bash.exe' ./mvnw -B -ntp verify
.\mvnw.cmd -B -ntp '-Dtest=AgentToolSchedulerBaselineTest,AgentToolSchedulerTest,AgentToolSchedulerBenchmarkTest' '-Dp6.benchmark=true' test
```

旧版适配器只存在于测试源码 `LegacyToolScheduler.java`，来自基线生产源码（仅类名与@Component不同），没有保留生产切换旧版的入口。
真实容器必须可用，关键集成类 skipped=0；benchmark 为显式 opt-in，普通 verify 中它被跳过是预期行为，独立执行记录在 CSV。
完整最终 SHA、对应 Actions run/job/步骤与 Draft PR 状态随本次交付回执提供；用该 SHA 检出以上 blob 并核对同 SHA CI。

性能口径：REALISTIC 用 2/4 个生产者、每个 Run 每轮4个工具、10轮、单工具模拟4ms I/O；同用户/多用户均覆盖。
STRESS 一次112项，前16项热点Run，其余独立身份，先关门提交再统一释放；不是产品 Worker 实际负载。
旧版 max_pending 仅为 executor 队列（不含已进入16个线程但等 Semaphore 的项）；新版为全部未获准入项。
semaphore_waiting_workers 为压力释放前的线程栈采样，max_actual_active 为整个试验的工具实际执行峰值（含释放后）。
process_cpu_ms 为 JVM 进程CPU时间差，Windows计时粒度较粗，0不表示零CPU开销；无CPU配额修改，环境有其他本地进程。
实验按 old/new 顺序交替、同一 JVM；每条件只有3次独立调度器实例，不提供统计显著性/生产延迟置信保证。
