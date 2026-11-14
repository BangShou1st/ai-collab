# Zen / Kilo 共享库移除 Lane Registry

基线：`dev @ cd5269ee2f93a2be9c8b0659e8334264389dbf9d`

| Lane | 分支 | Worktree | 证据目录 | 状态 |
| --- | --- | --- | --- | --- |
| A 后端 | `cleanup/backend-remove-zen-kilo` | `E:/project/ai-collab-wt/cleanup-backend` | `docs/acceptance-evidence/2026-11-14/remove-zen-kilo/backend/` | COMPLETED（集成 `8aa4811d`） |
| B 前端 | `cleanup/frontend-remove-zen-kilo` | `E:/project/ai-collab-wt/cleanup-frontend` | `docs/acceptance-evidence/2026-11-14/remove-zen-kilo/frontend/` | COMPLETED（集成 `278974cd`） |
| C 文档与契约 | 主 worktree | `E:/project/ai-collab` | `docs/acceptance-evidence/2026-11-14/remove-zen-kilo/docs/` | IN_PROGRESS |

## 纪律

- Lane A/B 并行施工；Lane C 在后端、前端集成后执行。
- 每个 Lane 只在各自 worktree 内修改，禁止在主 worktree 并行改文件。
- 中心复核 diff 与原始门禁输出后，按 A → B → C 顺序 cherry-pick 到 `dev`。
- 同一失败最多调整 3 轮；仍失败记录 `BLOCKED` 与原始输出。
- 不改写 `docs/archive/**`、`docs/acceptance-evidence/**` 历史记录，不修改 `V1`–`V101`。
