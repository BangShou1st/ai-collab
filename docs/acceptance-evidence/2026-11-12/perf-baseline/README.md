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

### Follow-up（项目详情单接口，不覆盖原 FAIL）

| 数据集 | 样本 | P95 | 95% CI | 对 B2（5ms） |
| --- | ---: | ---: | --- | ---: |
| baseline B1/B2 | 4,371 / 4,150 | 4 / 5ms | [4,5] / [5,5] | — |
| follow-up R1/R2/R3 | 10,000 ×3 | 6 / 5 / 7ms | [6,6] / [5,5] / [7,7] | +20% / 0% / +40% |
| pooled | 8,521 / 30,000 | 5 / 6ms | — | +20%（+1ms） |

Follow-up **FAIL / not a clean pass**：没有回到 4ms；原 per-label FAIL 保留。SQL 实际 join 0.075ms、3 buffers，无 SQL/索引/序列化缺陷证据；按 1ms 门禁敏感性 + 本机 run variance 解释。

文件：

- [summary.csv](summary.csv)：场景级样本、错误、5xx、P50/P95/P99、门禁
- [labels.csv](labels.csv)：按 label 的聚合
- [gate-summary.csv](gate-summary.csv)：四条硬门禁
- [comparison-baseline.csv](comparison-baseline.csv)：B2 vs B1 的逐 label P95（FAIL 来源）
- [comparison-mixed-vs-read.csv](comparison-mixed-vs-read.csv)：mixed vs read 诊断，不是门禁
- [followup-p95.csv](followup-p95.csv) / [followup-comparison.csv](followup-comparison.csv)：项目详情 3×10k 复测与 bootstrap CI
- [followup-notes.txt](followup-notes.txt)：复测方法、SQL 检查与 FAIL/噪声结论
- [project-detail-sql.txt](project-detail-sql.txt)：实际 mapper join 的 EXPLAIN ANALYZE
- [environment.txt](environment.txt)：主机、Docker VM、端口、版本
- [route-inventory.txt](route-inventory.txt)：Controller 路由清单
- [post-run-checks.txt](post-run-checks.txt)：清理、JVM/线程池/DB 快照
- [backend-warnings-official.txt](backend-warnings-official.txt)：官方窗口 0 条 WARN/ERROR
- [ai-stub-notes.txt](ai-stub-notes.txt) / [ai-stub-decomposition.txt](ai-stub-decomposition.txt) / [ai-stub-decomposition.csv](ai-stub-decomposition.csv)：stub 链路、冷启动/隧道/provider/overhead 拆解
- [info-soak-diagnostic.txt](info-soak-diagnostic.txt)：183s INFO short soak 与 DEBUG soak 对比
- [info-soak-backend.txt](info-soak-backend.txt)：INFO short soak 的 12KB 后端日志
- [sse-latency.txt](sse-latency.txt)：SSE 冷/热样本
- [sse-final-validation.txt](sse-final-validation.txt)：提交前对最终 SSE JMX 的复验（含 Content-Type 断言）
- [seed.json](seed.json) / [cleanup.json](cleanup.json)：测试项目 ID 与清理结果（项目已删除）

边界：

- 本地共置：JMeter、后端、Docker DB 同机；Docker VM 仅 8 vCPU / 1.86GiB，**不是 4C/8G**。
- AI 只打本地 200ms stub，经公网 HTTPS quick tunnel；真实模型未调用。
- 不代表 staging/production 容量、SSE 有文档流、多实例、Redis/MQ 开启路径。
- 原始 JTL 不提交；汇总 CSV 是本目录的断言基准。
