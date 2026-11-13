# W3 inline 产品边界验收（8 / 3s）

- 日期：2026-11-13
- 代码基线：`dev @ 853e3420` + 本轮未提交变更
- 证据等级：策略 `TESTED (offline)`；真实模型 `TESTED (local llama.cpp)`；生产路径 `NOT_VERIFIED`
- 判定：**INLINE_8_PASS / TIER_20_REJECTED / TIER_50_REJECTED / ASYNC_NOT_IMPLEMENTED**

## 1. 结论

| 项 | 结果 | 证据 |
| --- | --- | --- |
| inline 候选上限 | **8** | Java 测试断言 8 候选发送、9/20/50 零外呼 |
| inline deadline | **3000ms** | Java 测试把 30s 配置收敛为 3000ms |
| 20 候选 | **inline 拒绝** | 参数化测试 `9/20/50` 全部回退 Java，无 worker 调用 |
| 50 候选 | **inline 拒绝** | 同上 |
| 失败回退 | **PASS（offline）** | `rerankerDegraded=true`，保留候选与启发式顺序 |
| 管理端 8/20/50 offline | **未改变** | `CapabilityProfileServiceTest` 仍允许 50 候选离线测试 |
| 真实模型 8 候选 3s smoke | **PASS（local）** | 15 次请求全部 <3s，每条 8 个有限分数 |
| 生产用户路径 | **NOT_VERIFIED** | `RERANK_MODE=java` 未变，未启用专用 Reranker |
| async | **`ASYNC_NOT_IMPLEMENTED`** | 当前没有 async 执行器 |

**20/50 不是 inline PASS。** 本轮只证明它们不会进入 inline；此前 20 档
`adapter_TIMEOUT`、50 档 `NOT_RUN` 的 3s 历史结论保留，不因本轮改写。

## 2. Java 策略证据

### 2.1 定向策略测试

执行：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=RerankOverheadBudgetTest,RerankModeRouterTest,ConfiguredRerankerTest,RerankPropertiesBindingTest' test
```

结果：`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`。

- `RerankOverheadBudgetTest`：2 项；8 候选 510 样本，overhead P50 7.170ms /
  P95 22.992ms / P99 29.902ms（stub，门禁 P95≤800ms、P99≤1500ms）；
- `RerankModeRouterTest`：17 项，含 8 候选发送、9/20/50 候选拒绝、3000ms 收敛、
  worker 失败回退；
- `ConfiguredRerankerTest`：5 项，含配置化入口的 20 候选拒绝与 profile timeout 收敛；
- `RerankPropertiesBindingTest`：4 项，默认仍为 Java，`maxCandidates` 仅为 shadow 上限。

原始输出：`raw/java-rerank-targeted.txt`（sha256 `77173f03…`）。

### 2.2 管理端/管线回归

执行：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=CapabilityProfileServiceTest,RerankSettingsSchemaTest,RerankPipelineModeIntegrationTest' test
```

结果：`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`。

- 能力档案离线测试仍支持 50 候选；
- schema 未改变；
- `java`/`shadow`/`python` 管线语义未被产品边界破坏。

原始输出：`raw/java-admin-offline-tests.txt`（sha256 `2a296f07…`）。

## 3. 真实模型 8 候选 smoke

环境：Qwen3-Reranker-0.6B Q8_0（GGUF SHA-256
`22c9979c…`）+ llama.cpp Vulkan build 12318 / commit `7021f7e09`（本地 RTX 4060），
`r01` 恰好 8 个候选；1 次 warm-up + 15 次观测。环境原始记录：
`raw/environment.json`（sha256 `9db41de2…`）。

| 指标 | 结果 |
| --- | ---: |
| candidates | 8 |
| runs | 15 |
| result count | 15/15 恰好 8 |
| 所有分数有限 | 15/15 |
| P50 | 255.149ms |
| P95 | 415.640ms |
| >3000ms | 0/15 |
| 判定 | **PASS（local model smoke）** |

这是 llama.cpp 直达 smoke，不含 Java overhead 与 admin capability 路径；
不能替代正式端到端门禁，也不把生产用户路径写成 PASS。

原始输出：`raw/inline-8-smoke.json`（sha256 `fed7fe07…`）。

## 4. 行为与配置

- inline 超过 8 条：不调用 worker，指标
  `rerank.inline.rejected{reason=candidate_limit}`，回退 Java 启发式；
- inline deadline：`min(配置值, 3000)`；
- shadow：后台对比，不进入用户可见关键路径；
- offline：管理端能力测试仍可跑 20/50，仅作功能性/延迟观测；
- assignment `enabled` 默认 false；无绑定回退 Java；
- `RERANK_MODE=java` 未改变。

## 5. 未验证项与风险

1. 没有把 Java 进程接到本机真实 Reranker 做 8 候选端到端复测；本轮只有
   Java 策略测试 + 直达模型 smoke。
2. 未重跑 20/50 的 3s formal gate；历史 `FAIL/NOT_RUN` 仍是唯一结论。
3. 未实现 async，因此大候选档位没有后台执行路径。
4. 生产用户路径仍为 `NOT_VERIFIED`。
5. 8 候选 smoke 只覆盖一个 query/case，`LOW_CONFIDENCE`，不用于容量外推。

## 6. 原始证据索引

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `raw/inline-8-smoke.json` | 真实模型 8 候选 15 次观测 | `fed7fe07…` |
| `raw/java-targeted-tests.txt` | inline 上限、20/50 拒绝、回退测试 | `358a82b2…` |
| `raw/java-rerank-targeted.txt` | 含 8 候选 overhead 510 样本的最终定向回归 | `77173f03…` |
| `raw/java-admin-offline-tests.txt` | 管理端 offline 与管线回归 | `2a296f07…` |
| `raw/environment.json` | 模型、二进制与 GPU 证据 | `9db41de2…` |
| `raw/harness/run_inline_smoke.py` | smoke 复现脚本 | `38bdb366…` |

## 7. 最终判定

**W3 产品边界：`INLINE_8_PASS / TIER_20_REJECTED / TIER_50_REJECTED`；
`ASYNC_NOT_IMPLEMENTED`；生产路径 `NOT_VERIFIED`。**
