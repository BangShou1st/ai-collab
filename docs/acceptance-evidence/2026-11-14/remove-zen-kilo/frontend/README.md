# Lane B 前端移除 Zen/Kilo 预设证据

- 日期：2026-11-14（Asia/Shanghai）
- 分支：`cleanup/frontend-remove-zen-kilo`
- Worktree：`E:\project\ai-collab-wt\cleanup-frontend`
- 基线：`c18b9f7b`
- 实现提交：`6b41abfe`
- 范围：仅 `ai-collab-frontend/` 与本证据目录
- 结论：**PASS**；无 `BLOCKED`，无 `NOT_RUN`

提交统计：13 files changed, 74 insertions(+), 408 deletions(-)。

## 改造摘要

- 删除 `ai-preset-api.ts`、`use-preset-connection.ts`，清理全部 import。
- `UserAiSettingsView.vue` 删除推荐连接卡片及其状态、加载、保存、断开和 Provider 过滤，只保留全站共享模型与个人自定义模型。
- 前端 Provider 契约由 `presetCode: string | null` 改为必填 `shared: boolean`；共享用途就绪度直接按 `shared` 判定。
- `CapabilitySuitePanel` 与 `useCapabilitySuite` 删除预设准入分支，所有个人自定义模型统一走模型发现与完整能力套件。
- 清理孤立连接卡样式与 `zen-*` / `kilo` 类名；保留仍被其他页面引用的通用样式。
- 删除 Zen/Kilo 预设测试，保留并强化自定义模型、共享模型、用途就绪度和探针断言。

## 门禁结果

| 命令 | Exit | 结果摘要 | 原始输出 |
| --- | ---: | --- | --- |
| `pnpm install --frozen-lockfile` | 0 | Lockfile 未变化；依赖安装完成 | `pnpm-install.log` |
| `pnpm lint` | 0 | `0 errors, 69 warnings` | `pnpm-lint.log` |
| `pnpm typecheck` | 0 | `vue-tsc -b` 无诊断 | `pnpm-typecheck.log` |
| `pnpm test` | 0 | `68 passed (68)`；`515 passed (515)` | `pnpm-test.log` |
| `pnpm build` | 0 | 1908 modules transformed；`built in 6.14s` | `pnpm-build.log` |

> `*.log` 受仓库 `.gitignore` 规则忽略，原始日志保留在本 worktree 的当前证据目录，不随提交进入 Git。

## 零残留扫描

扫描根为 `ai-collab-frontend/`，排除 `node_modules`、`dist`、`coverage`、`.vite` 与 `test-results`。

| 扫描 | 命中 |
| --- | ---: |
| JitPack / Zen / Kilo / Bun / `presetCode` / `preset_code` 精确组合 | 0 |
| `opencode` / `kilo` / `zen` / JitPack / sharedlibs 等宽口径 | 0 |
| 已删除连接卡样式类 | 0 |

`rg` 在无命中时返回 exit 1，原始记录见 [`residual-grep.txt`](residual-grep.txt)。

## 限制

- 本 lane 仅执行前端静态门禁与组件测试，未启动浏览器做端到端验收。
- 未执行真实模型网络调用；探针与共享模型行为由组件/单元测试覆盖。
