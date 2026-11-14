# W4 Frontend Memory UI 证据摘要

- Baseline: `wiring/frontend-memory-ui` from `dev @ 1f95447e`
- Scope: `ai-collab-frontend/src/modules/agent/` only
- Status: `IMPLEMENTED`, `TESTED(UNIT)`
- Real browser closure: `NOT_RUN`（按设计留到 Wave 2 BSK 统一验收）

## 实现

- `agent-memory-api.ts`：按后端真实控制器封装记忆 CRUD、停用、Memory Job、候选列表/批准/拒绝。
- `AgentMemoryDrawer.vue`：三区抽屉（已发布记忆、待审候选、提取任务），管理员操作按 `members` 中的当前用户项目角色收紧；候选先审后批；Job 状态轮询在关闭/卸载/项目切换时清理。
- `AgentView.vue`：顶部增加“项目记忆”入口，挂载抽屉，不改现有会话/审批布局。

## 原始输出

| 命令 | Exit | 原始输出 |
| --- | --- | --- |
| `pnpm lint` | 0 | `lint.raw.txt`（既有 warning，新增文件无 error） |
| `pnpm typecheck` | 0 | `typecheck.raw.txt` |
| `pnpm test` | 0 | `test.raw.txt`（68 files / 515 tests） |
| `pnpm build` | 0 | `build.raw.txt` |

定向单测：

```text
pnpm exec vitest run src/modules/agent/agent-memory-api.test.ts src/modules/agent/AgentMemoryDrawer.test.ts
Test Files  2 passed (2)
Tests       11 passed (11)
```

## 后续验证

- Wave 2 使用 BSK 在隔离环境完成：发起提取 Job → 候选审批 → 下一轮 Agent 召回。
- 本轮未修改仓库默认开关，未新增 Flyway 迁移，未触碰 `main`。
