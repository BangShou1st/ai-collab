# P2-HARDEN-01 稳定性与遗留验收收尾记录

日期：2026-10-19

仓库：`BangShou1st/ai-collab` · 分支：`dev`

验收基线：`de19f57f0cb9a531d16e6a2519caf4c4b2e8f7d2`

## 结论

本轮确认并修复两项规划代码风险，补齐项目访问请求竞态保护，真实复测 Edge Member 私有项目 404，并完成本机 Ollama Embedding 服务的受控停机与恢复。后端全量 Maven、前端 lint/typecheck/test/build 均通过。

**P2 暂不能正式关闭。** 6 份历史失败文档尚无各自 Owner 的合法重试结果；旧 Agent `ERR_ABORTED` 未重现且未归因；Agent SSE 断线重连仅有自动化验证，尚无真实运行断线复测；真实 Provider 429 和线上式并发提交故障注入也未执行。绿色全量测试不覆盖这些未执行的外部服务场景。

没有使用额外付费凭证，没有删除 Docker 数据卷，也没有把请求正文、Cookie、Token、密钥或个人/业务 ID 写入本报告。

## 新发现代码风险与修复

| 风险 | 是否真实存在 | 修复及验证 |
|---|---|---|
| `generateDetailBatchesWithOneRepair()` 在 `startRepair()` 已切换到新 attempt 后，局部 `activeAttempt` 仍指向旧 attempt。之后模型调用、格式解析或业务校验抛错时，失败收尾会以旧 ID 调用 `repository.fail()`，新 attempt 可能留在 `DETAIL_GENERATING`，阻断重试。 | **是，已由回归测试复现。** | 将 active ID 更新为修复 attempt，并同步迁移取消 Future 的 key。新增模型调用失败、格式错误、领域校验失败三种回归场景，均验证状态进入失败、活动 attempt 清除且可重试。 |
| 分批骨架把每批任务数写成精确值：JSON Schema 的 `minItems` 等于批次上限，服务端也要求恰好输出该数量。因此“最大任务数”会被误当成必须生成的数量。 | **是，已由回归测试复现。** | Schema 与验证允许每批 1 到上限个任务；模型少于上限时结束后续批次。新增 8 任务上限下仅生成 2 个的用例、16 任务跨批依赖用例，以及取消后重试恢复用例。 |

此前已通过的 RAG、Agent 正常引用链及真实 8 任务确认闭环本轮没有无意义重构。新增规划回归均为自动化集成测试，不代表重新进行了真实模型生成。

## 项目上下文竞态与真实浏览器复测

项目上下文 store 为每次请求分配顺序号；切换项目、刷新、清空登出状态都会使旧请求失效。成功、404 和其他迟到响应只有仍属于当前请求时才可更新状态。`AppShell` 在权限检查完成前不挂载项目内容，404 显示中性拒绝页面。

自动化覆盖 A/B 快速切换和乱序响应、迟到 404、迟到成功、登出后响应，以及待权限确认期间隐藏项目内容。

真实 Edge Member 会话以正常桌面视口 `1544×847` 打开私有项目路由。服务端只返回项目访问 GET 404；页面显示“项目不存在或无权访问”，没有请求 dashboard 子资源，没有呈现受保护内容。控制台只有对应 404 的浏览器资源错误，没有未处理 Promise 或 watcher 异常。测试使用真实现有 Member 登录态，没有保存会话凭证。

### 侧栏白色背景问题

根据用户截图，将侧栏的固定 `100vh/100dvh` 高度改为固定定位的上下边界约束和 `height:auto`，避免用固定长度决定底部覆盖范围；背景由上下边界延伸，导航内容超出时在抽屉内滚动。`viewport-fit=cover` 和安全区 padding 一并保留。按用户要求 BSK 维持标准桌面尺寸；本轮没有进行窄屏浏览器仿真，因此该移动样式修复**未做窄屏视觉验收**。

## 可靠性验收矩阵

| 场景 | 结果 | 范围和限制 |
|---|---|---|
| 429 限流响应 | **自动化通过；真实 Provider 未测试** | Maven 的 HTTP 状态映射和 Kilo Gateway 429 分类测试通过（`ModelProtocolHttpTest` 25 项、`KiloGatewayModelTest` 16 项）。没有对真实外部 Provider 制造限流，也没有消耗额外配额。 |
| Ollama Embedding 停机与恢复 | **本机真实服务通过** | 受控停止本机 Ollama，API 探测得到 HTTP 000；重新启动原服务后 `/api/tags` 恢复 HTTP 200，真实 `qwen3-embedding:0.6b` `/api/embed` 返回 1 个 1024 维向量。最初一次 20 秒 Embedding 请求超时，原因不明，不作推断。未改 Docker 数据。 |
| Agent SSE 断线重连 | **前端自动化通过；真实 Agent 运行未测试** | 模拟已接受序号 6 后 SSE 断开，验证重连带 `afterSequence=6` 与 `Last-Event-ID: 6`。未在真实 Agent 模型运行中主动断网或验证服务端恢复。 |
| 并发与幂等 | **PostgreSQL 自动化通过；真实多会话注入未测试** | 全量测试覆盖 Agent worker 抢占、规划确认及迁移后的并发/幂等保护。相关报告包括 `AgentRepositoryIntegrationTest` 40 项、`PlanningMigrationIntegrationTest` 8 项，均通过；未从真实浏览器并行提交或对外部 Provider 做并发故障注入。 |
| RetrievalCoverage `TRUNCATE` 死锁 | **未复现，无代码改动** | `RetrievalCoveragePostgresTest.reset()` 每次测试在自己的 Testcontainers 数据库执行一次 CASCADE TRUNCATE；本次 16 项中 15 项通过、1 项跳过、0 错误。没有发现 Maven/JUnit 并行配置或共享该容器的并行清理路径，未观察到清理竞争证据，因此没有增加不必要的隔离或削弱 Testcontainers 覆盖。 |
| 历史 Agent `ERR_ABORTED` | **未重现，仍未归因** | 复核既有验收证据：约 34–36 秒的旧异常不在当前运行中复现；已保存的后续 capture 从已完成运行开始，只能证明历史恢复请求成功，不能证明原始模型/工具流发生了什么。不得推断为主动取消、页面切换、网络断线或服务端失败。 |

## 六份历史失败文档

6 份文档此前在 Embedding 不可用期间失败。本轮没有各文档 Owner 的登录权限或授权会话，没有代替 Owner 重试，也没有通过数据库或管理员权限绕过项目权限。当前每份文档的状态和可恢复数**未验证**；六份均标记为 **BLOCKED — 等待各自合法 Owner 登录并重试**。不能把先前失败推定成当前仍失败，也不能提前标记恢复成功。

## 自动化检查

| 检查 | 命令/报告 | 结果 |
|---|---|---|
| 后端完整验收 | Windows Maven Wrapper：`mvnw.cmd clean verify`（工作目录 `ai-collab-backend`） | `BUILD SUCCESS`；1533 项，0 failures，0 errors，18 skipped。Testcontainers/PostgreSQL 集成覆盖保留。18 项为显式 opt-in 的真实 AI/服务测试，本轮未启用。 |
| 规划 attempt / 分批回归 | `TaskPlanGenerationOrchestratorIntegrationTest`；`TaskPlanSkeletonSchemaTest` | 12 + 6 项，全部通过；包含失败收尾可重试、上限语义、跨批依赖及取消恢复。 |
| 前端 lint | `pnpm lint` | 通过，0 errors、79 warnings。 |
| 前端类型检查 | `pnpm typecheck` | 通过。 |
| 前端测试 | `pnpm test` | 42 个文件、249 项通过。 |
| 前端生产构建 | `pnpm build` | 通过；保留已有依赖 PURE 注释及大 chunk 提示。 |
| 工作区差异 | `git diff --check` | 通过。 |

自动化测试证明回归行为，不替代本报告标为真实验收的浏览器、Ollama 或未来真实 Agent SSE 场景。

## P2 与 P3 决策

- **P2 正式关闭：否。** 当前真实修复已通过，但 Owner 文档恢复、旧 `ERR_ABORTED` 归因、真实 Agent SSE 断线恢复以及真实 Provider 429 等证据仍缺。18 个 opt-in 服务测试跳过也是已知限制。
- **P3 可开展：** 与这些风险无依赖的 UI、文档、可回滚的非模型功能；补充监控/可观测性、测试夹具和 Owner 自助重试流程；不改变既有模型重试/权限标准的独立改进。
- **仍受风险限制：** 扩大 Agent 长运行/自动重试行为、依赖 SSE 中断恢复的关键流程、批量重试六份文档、提升并发写入规模、依赖真实 Provider 限流预算或产生外部模型费用的功能。应先补齐 Owner 结果和相应真实故障证据，再把相关能力作为 P3 可靠性前置项。

## Git 与 CI

代码提交 SHA、推送结果及最终 SHA 的 CI 状态将在提交后补记。不得以本地通过替代远端 CI 结果。
