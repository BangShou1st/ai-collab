# JMeter perf baseline evidence (2026-11-12)

> 结论：**FAIL（仅 per-label P95 基线对比）**。错误率、HTTP 5xx、AI stub overhead 均 PASS。  
> 场景级只读 P95 `63ms → 68ms`（+7.9%），但项目详情 per-label `4ms → 5ms`（+25%，1ms 绝对差）触发冻结门禁。

| 场景 | 样本 | 错误 | 5xx | P95 |
| --- | ---: | ---: | ---: | ---: |
| read-baseline B1/B2 | 51,620 / 48,970 | 0 / 0 | 0 / 0 | 63 / 68ms |
| mixed-baseline | 37,289 | 0 | 0 | 68ms |
| spike | 28,714 | 0 | 0 | 148ms |
| soak | 355,810 | 0 | 0 | 55ms |
| ai-stub | 379 | 0 | 0 | elapsed 1,374ms；overhead 6ms |
| sse-smoke | 10 | 0 | 0 | 3,321ms（前 2 个冷样本） |

文件：

- [summary.csv](summary.csv)：场景级样本、错误、5xx、P50/P95/P99、门禁
- [labels.csv](labels.csv)：按 label 的聚合
- [gate-summary.csv](gate-summary.csv)：四条硬门禁
- [comparison-baseline.csv](comparison-baseline.csv)：B2 vs B1 的逐 label P95（FAIL 来源）
- [comparison-mixed-vs-read.csv](comparison-mixed-vs-read.csv)：mixed vs read 诊断，不是门禁
- [environment.txt](environment.txt)：主机、Docker VM、端口、版本
- [route-inventory.txt](route-inventory.txt)：Controller 路由清单
- [post-run-checks.txt](post-run-checks.txt)：清理、JVM/线程池/DB 快照
- [backend-warnings-official.txt](backend-warnings-official.txt)：官方窗口 0 条 WARN/ERROR
- [ai-stub-notes.txt](ai-stub-notes.txt)：stub 链路与 overhead 口径
- [sse-latency.txt](sse-latency.txt)：SSE 冷/热样本
- [sse-final-validation.txt](sse-final-validation.txt)：提交前对最终 SSE JMX 的复验（含 Content-Type 断言）
- [seed.json](seed.json) / [cleanup.json](cleanup.json)：测试项目 ID 与清理结果（项目已删除）

边界：

- 本地共置：JMeter、后端、Docker DB 同机；Docker VM 仅 8 vCPU / 1.86GiB，**不是 4C/8G**。
- AI 只打本地 200ms stub，经公网 HTTPS quick tunnel；真实模型未调用。
- 不代表 staging/production 容量、SSE 有文档流、多实例、Redis/MQ 开启路径。
- 原始 JTL 不提交；汇总 CSV 是本目录的断言基准。
