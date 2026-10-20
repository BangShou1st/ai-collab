# P3-SCOPE-01 交付记录

日期：2026-10-20；分支 dev；开工 SHA `09d80639185fbb89e74b71d37d3af272bf43ad5f`，工作区干净，远端 dev 已核对一致。

## 交付与范围

- [正式规划入口](../../../p3-scope-01.md)：能力盘点、候选状态、优先级/价值/成本/依赖、验收合同、里程碑、P2 风险台账及非目标。
- [首轮完整提示词](../../../p3-first-batch-prompt.md)：推荐 U-01 工作台待办直达任务详情，作为候选等待下一轮明确实施。
- 更新能力矩阵的最新状态入口，保留历史记录，不篡改旧验收结果。
- 两项诊断技术债仅修改 agent-sse-diagnostics.ts 和测试，无 Runtime/后端/迁移/模型/重连策略变更。

## 两项先红后绿的回归

命令：在 ai-collab-frontend 执行 `pnpm exec vitest run src/modules/agent/agent-sse-diagnostics.test.ts`。

先只改测试，未改实现：19 项中 **3 failed、16 passed，exit 1**。

| 技术债 | 修复前实际失败 | 最小修复 | 修复后保护 |
| --- | --- | --- | --- |
| T-01 | localAbort=false，AbortError 返回 CLIENT_ABORT，期望 UNKNOWN | 无显式本地证据的 AbortError 改 UNKNOWN | 显式 true 仍 CLIENT_ABORT；Error/DOMException 无证据不推断 |
| T-02 | 重连未对齐再次错误/提前结束，状态实际 TRANSPORT_RECONNECTED，期望 RECONNECTING（两条失败） | 活跃断线区间再次断开先退回 RECONNECTING 再返回 null | 区间只一条 disconnect、次数累加、aligned 不提前恢复；下一次 opened+aligned 正常收口 |

随后修改实现并运行前端全量测试，**43 文件、271 项通过**（基线 269，加2个参数化分支，原错误分类测试已纠正预期）。对应诊断文件19项全通过。未把纠正错误预期解释为削弱产品规则；新预期直接来自 OBS-02 的显式证据要求。

## 本轮检查

| 检查 | 结果 |
| --- | --- |
| pnpm test | exit 0，43文件271项通过；含既有 SSE 续接和恢复 UI 测试；存在组件 stub 的 Vue warning |
| pnpm lint | exit 0，0 errors、78 warnings；改动诊断文件无新 warning |
| pnpm typecheck | exit 0 |
| pnpm build | exit 0；既有 PURE 注释、大 chunk 提示保留 |
| git diff --check | 通过；新增文档本地 Markdown 链接目标也已全部核对存在 |
| 后端 | 本机未重跑（无后端变更）；完整 Maven/PG 回归由本提交 CI 核实 |
| 浏览器/真实模型 | 本轮未执行；盘点引用旧证据，T-01/T-02 的新增边界仅自动化验证 |

没有为了研究启动服务、注入真实 429、操作历史文档或改变模型配置。R-3 受控真实 PASS 来自 OBS-02，不是本轮重新验收；R-1 BLOCKED、R-2 UNKNOWN、R-4 NOT_TESTED、R-5 opt-in 跳过保持。

## Git 与 CI

代码与规划提交：`d34e55379e943aaf88f7521d5b027a9a544a398b`，已按仓库分支策略普通推送至 dev（`09d8063..d34e553`）。对应 [CI run 39384684083](https://github.com/BangShou1st/ai-collab/actions/runs/39384684083)，head SHA 与本提交一致；前端 lint/typecheck/test/build、后端 Maven verify/Testcontainers 守卫均由该工作流执行。CI 的最终核对结论在最终交付消息记录，不能使用基线历史 CI 代替。

本节为独立的文档元数据补记，不包含新运行代码。补记提交的最终 SHA/CI 同样在最终交付消息列明。不合并 main、不 force push、不改写历史或用户数据。诊断修复可通过回退 d34e553 中两处诊断文件变更撤销，不需要数据库回滚。
