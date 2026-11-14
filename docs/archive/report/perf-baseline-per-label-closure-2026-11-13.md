# JMeter per-label 门禁口径收口（2026-11-13）

> 性质：只读口径收口，不重跑容量、不改阈值、不改 JMX/JTL。
> 原始报告：[perf-baseline-2026-11-12.md](perf-baseline-2026-11-12.md)
> 原始证据：[perf-baseline](../../acceptance-evidence/2026-11-12/perf-baseline/)

## 1. 结论

JMeter 本地基线保持 **FAIL**，失败项只有冻结的 per-label 门禁：

```text
GET /api/v1/projects/{projectId}
B1 P95 = 4ms
B2 P95 = 5ms
变化 = +25%（绝对差 1ms），超过预先冻结的 20% 阈值
```

这不是“已修复”或“已忽略”的门禁。原 `gate-summary.csv`、
`comparison-baseline.csv` 和 FAIL 结论均保留，不覆盖、不改写。

## 2. 不能替代门禁的背景指标

| 指标 | 结果 | 解释 |
| --- | --- | --- |
| 错误率 | 0 / 522,806 = 0% | PASS |
| 非预期 5xx | 0 | PASS |
| AI stub overhead P95 | 6ms | PASS；不是真实模型容量 |
| 场景级只读 P95 | 63ms → 68ms（+7.9%） | 在 20% 内，但不能替代 per-label |
| 项目详情 per-label | 4ms → 5ms（+25%） | **FAIL，保持** |

## 3. Follow-up 结论

原始 follow-up 是独立复测，不覆盖原 FAIL：

| 数据集 | 样本 | P95 | 对 B1 | 对 B2 |
| --- | ---: | ---: | ---: | ---: |
| baseline B1 | 4,371 | 4ms | — | — |
| baseline B2 | 4,150 | 5ms | — | — |
| follow-up R1 | 10,000 | 6ms | +50% | +20% |
| follow-up R2 | 10,000 | 5ms | +25% | 0% |
| follow-up R3 | 10,000 | 7ms | +75% | +40% |
| pooled follow-up | 30,000 | 6ms | +20% | +20% |

判定：**FAIL / not a clean pass**。复测没有回到 4ms；端点实际落在 5–7ms。
这更像是 1ms 计时粒度、JIT/缓存与 run variance 对 20% 相对阈值的放大，
但没有证据证明它绝对不是回归，所以不能改成 PASS。

已做的只读排查：

- 项目详情 SQL 实际执行约 `0.075ms`，3 shared buffers；
- 37/42 行小表使用 nested loop + seq scan 属正常；
- `ProjectView` 只有 12 个标量字段；
- 当前没有证据指向 SQL、索引或序列化缺陷。

## 4. 后续复测口径

如果未来要取得“干净”结论，必须重新冻结并明确：

1. 固定测试数据规模，不能每次 seed 不同任务数；
2. 先 warm-up，再至少跑 2 组 B1/B2，而不是只跑一轮；
3. 记录绝对毫秒值，1ms 级别的相对百分比不得单独作为生产结论；
4. 在目标 4C/8G 或 staging 规格上重跑，不能用本机 i9/16GB 外推；
5. 仍保留原 FAIL 和原始 JTL 摘要，不覆盖历史证据。

## 5. 边界

- 本轮不重跑容量、不新增 JMX、不改 `thresholds.csv`。
- 本机结果不是生产容量，也不是 4C/8G 基线。
- AI stub 的 6ms 仅是 Java 网关 overhead，不能写成真实模型延迟。
- W3 的真实模型 3s FAIL 不在本文件范围内，不能混入 JMeter 结论。
