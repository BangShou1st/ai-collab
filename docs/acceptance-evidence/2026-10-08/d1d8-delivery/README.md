# D1-D8 收口红绿证据

- 日期：2026-10-08/09；分支 `codex/context-foundation`；基线 HEAD `2cf4c51`。
- 报告：[D1-D8 交付](../../../agent-context-capacity-d1d8-delivery-20261008.md)。
- 上轮探针证据（只读复核）：[c1c5-followup-review](../c1c5-followup-review/README.md)、
  [c1c5-expanded-review](../c1c5-expanded-review/README.md)。本轮把其中 9 个探针断言
  正式化为行为回归，并在未修基线上实测红灯（见 `baseline-red-run.log`）。

## 归档内容

- `baseline-red-mock-run.log`：未修基线（stash 修复后）上 mock 正式回归的红灯实测输出
  （8/8 失败）。
- `baseline-red-postgres-run.log`：未修基线生产代码 + 本轮测试文件上 D2 两个
  并发/租约用例的红灯实测（2 失败，既有 6 项绿灯）。
- `green-affected-run.log`：修复后五个直接相关回归类绿灯输出（26 项，0 失败）。
- `green-full-backend-run.log`：修复后后端全量输出（1309 项，0 失败 0 错误，11 跳过）。

## 正式回归（本轮新增/扩充）

- `AgentContextReliabilityD1D8RegressionTest`（8 项，mock）：D1/D3×2/D4/D5/D6/D7/D8。
  使用真实 Composer/Summarizer/Coordinator/Routing/Legacy/MemoryService；
  仅 Repository、native 出站、ChatModelGateway、配置存储、记忆仓库为受控替身。
  D7 断言捕获实际 `ChatCompletionCommand`；D8 捕获实际出站模型序列。
- `AgentRunContextCommitPostgresTest`（6→8 项）：新增 D2
  `expiredClaimWithoutEpochChange…`（租约过期未换 epoch）与
  `cancelCommittedBetweenCheckAndPublish…`（检查与发布之间取消，受控交错 +
  真实事务 + 真实连接）。Testcontainers pgvector:pg17 + 真实 Flyway v1-v64。

## 关键实测输出

未修基线 mock 类红灯（节选，完整见 `baseline-red-mock-run.log`）：

```text
FAIL-assert partialTail…: [partial 尾部必须保留在实际主请求中] … "UNSENT_TAIL_4907"
FAIL-assert lengthTerminatedRunContext…: expected false but was true
FAIL-assert childV2…: [depth=1 v2 请求不得包含父项目记忆]
FAIL-assert nearWindowV2…: Expecting actual: 0 to be greater than: 0
FAIL-assert fullToolLayer…: Expecting actual: 0 to be greater than or equal to: 180011
FAIL-assert legacyOutbound…: [Legacy 出站必须保留有效 RUN_CONTEXT 摘要层]
FAIL-assert failedAuxiliary…: expected: "model-B" but was: "model-A"
AgentRunContextCommitPostgresTest.expiredClaimWithoutEpochChange…:332
AgentRunContextCommitPostgresTest.cancelCommittedBetweenCheckAndPublish…:400
```

PostgreSQL 未修基线：`Tests run: 8, Failures: 2`（新增 D2 两项失败，既有 6 项通过）；
修复后 `Tests run: 8, Failures: 0`（见 `green-affected-run.log`）。

修复后：mock 类 8/8、CommitPostgres 8/8、受影响既有回归 138 项 0 失败（见报告）。

## 口径

- 红灯实测是本轮在未修基线上执行的正式回归，不是历史探针重放；历史探针输出
  （6+3 断言失败）保留在上一轮证据目录，两者独立统计。
- 全量、浏览器与真实模型验收口径见交付报告；未触发压缩则多周期真实质量记为未验证。
