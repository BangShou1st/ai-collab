# P3-OBS-02 SSE 诊断准确性及恢复闭环

日期：2026-10-20

仓库：`BangShou1st/ai-collab` · 分支：`dev`

开工基线：`969ab82ed8343dddc685a197562c68e5a3d03792`（开工时本地 `dev` 与 `origin/dev` 一致，工作区干净）。

环境：Windows；本机 Docker 提供 PostgreSQL 17 / pgvector、Redis、MinIO（沿用现有数据卷，未删除数据）；Java 21、Node 24、pnpm 11。浏览器验收使用现有 BrowserSkill（`bsk` 0.3.3）与 Microsoft Edge，标准桌面视口。

> 本报告不复述任何文档正文、Owner 身份、邀请链接、Token、Cookie、API Key 或请求/响应正文。业务对象以脱敏标签或类别描述出现；运行 ID 在导出证据中一律以占位符替代。

---

## 0. 本轮范围与结论速览

本轮完善 `p3-entry-01` 已交付的 Agent SSE 可观测性，**不改变** Agent Runtime、业务工具、审批、模型重试与既有 SSE 重连/退避策略。四个任务的结论：

| 任务 | 结论 |
|---|---|
| 一 · 断线归因规则修正 | 完成。删除"没有诊断记录 + `ERR_ABORTED` ⇒ 客户端主动取消"的错误推断；仅显式本地证据可判 `CLIENT_ABORT`，其余 `UNKNOWN`。历史 `ERR_ABORTED` 保持未归因。 |
| 二 · 恢复状态展示修正 | 完成。复现了"恢复后 `category=null` 导致 UI 无法展示已恢复"的问题；引入最小状态模型 `CONNECTED / RECONNECTING / TRANSPORT_RECONNECTED / RECOVERED / UNKNOWN`，并区分"仅收到 HTTP 200"与"事件/运行状态已对齐"。 |
| 三 · 真实恢复验证 | 完成。用本机受控 SSE 故障代理在**真实 Edge 页面 + 真实后端 + 真实免费模型**下，同一页面观察到 `disconnect → transport_reconnected → recovered`，无页面重载、无重复最终消息。 |
| 四 · 隐私与测试 | 完成。脱敏审查结论见 §5.1；新增纯函数/状态机/组件/续接测试；前后端全量回归通过。 |

---

## 1. 任务一 · 修正断线归因规则

### 1.1 问题

`p3-entry-01` 在报告中把"没有诊断记录 + 浏览器网络层 `ERR_ABORTED`"当作**可判定**客户端主动取消的依据（§3.2 归因路径、§6.3 归因行、§7 结论）。该推断证据不足：

1. 缺少诊断记录本身**不是证据**——记录可能因未触发的代码路径、仅网络层标签等而缺失；
2. `ERR_ABORTED` 在浏览器网络层既可由客户端主动取消产生，也可由意外传输中断产生；无侧录时两者不可区分；
3. 仅凭"页面重载恰好发生"也不足以把某个 `ERR_ABORTED` 归因到该操作。

### 1.2 修订后的规则

> 只有存在**显式的本地取消证据**（本端 AbortController 被中止 / 页面生命周期 / 用户操作）时，才可判定 `CLIENT_ABORT`；其余证据不足的情形一律 `UNKNOWN`。历史 `ERR_ABORTED` 保持**未归因**。

### 1.3 落地

| 位置 | 改动 |
|---|---|
| [agent-sse-diagnostics.ts](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/agent-sse-diagnostics.ts) | 模块头注释与 `CLIENT_ABORT` 语义：明确"仅在持有显式本地证据时可用"；`classifySseDisconnect(reason, localAbort)` 的参数由 `aborted` 更名 `localAbort` 并在文档中说明"缺少该证据时绝不判 `CLIENT_ABORT`"。 |
| [use-agent-workspace.ts](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/use-agent-workspace.ts) | 删除"无记录＝客户端取消"的注释；catch 分支注明"本端 AbortController 被中止＝显式本地取消证据；其余（含无记录的 `ERR_ABORTED`）不得据此判定客户端取消"。 |
| `p3-entry-01/README.md`、`network/agent-sse-break-sanitized.json` | 加**勘误**：把原先判为"客户端主动取消（页面重载）"的历史/复现事件改判 `UNKNOWN`（未归因），保留原始现象观察不动。 |
| [agent-sse-diagnostics.test.ts](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/agent-sse-diagnostics.test.ts) | 新增用例：`net::ERR_ABORTED` 字符串**不会**被判为 `CLIENT_ABORT`（落到 `UNKNOWN`），显式本地取消才判 `CLIENT_ABORT`。 |

**保留历史状态**：历史 `ERR_ABORTED`（`p2-business-e2e` E2E-04）仍为**未归因**，不因本次规则修订被翻案。

---

## 2. 任务二 · 修正恢复状态展示

### 2.1 问题复现

修复前 `AgentSseDiagnosticTracker.opened()` 在断线区间内重连成功时立即产出 `recovered` 并把 `category` 置 `null`；而 [AgentView.vue](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/AgentView.vue) 的状态行以 `category` 为门槛：

```
if (!d.category) return timeline.value.connected ? '已连接' : '未连接'
...
<p v-if="sseDiagnostics.category">断线诊断：…</p>
```

因此恢复后 `category=null` ⇒ **UI 不再展示"已恢复"**，"已恢复"窗口一旦过去就无法体现。同时旧模型把"仅收到 HTTP 200"直接当作完整恢复。

### 2.2 最小状态模型

在 `agent-sse-diagnostics.ts` 引入：

| 状态 | 含义 |
|---|---|
| `CONNECTED` | 传输已连接，且不处于断线区间。 |
| `RECONNECTING` | 传输已断开，正在重试。 |
| `TRANSPORT_RECONNECTED` | HTTP/SSE 传输重新建立，但**尚未**确认事件/运行状态已对齐。 |
| `RECOVERED` | 运行状态或持久事件已成功对齐，断线区间真正恢复。 |
| `UNKNOWN` | 证据不足、无法判定。 |

关键行为：

- `opened()`：断线区间内重新拿到响应**只记 `transport_reconnected`**（不等于恢复）；无断线区间为普通连接，不产记录；已恢复后再次 `opened()` 不把状态退回，保留"已恢复"展示。
- `aligned()`：只有**持久事件被接受**（或运行状态对齐）时才产出 `recovered` 并结束区间。工作区在 `applyAgentEvent(...) === true` 时调用 `aligned()`。
- 区间收敛不变：**同一断线区间只产出一条 `disconnect`**；恢复后同一运行的**新断线可重新记录**（`aligned()` 结束区间、`attempts` 复位）。
- `category` 在恢复后**保留**用于展示，`snapshot()` 增加 `state` 字段。

### 2.3 UI 展示

`sseStatusLine` 按状态渲染：`重连中（… · 第 N 次尝试）` → `传输已重连 · 等待事件续接（…）` → `已恢复（…）`；诊断段落改为直接渲染 `sseStatusLine`，恢复后仍可见。

> 修复前：`category=null` ⇒ 无法展示"已恢复"。修复后：`state=RECOVERED` + 保留 `category` ⇒ 可稳定展示"已恢复"。

---

## 3. 任务三 · 真实恢复验证（受控 SSE 故障代理）

### 3.1 为什么不是"重启前端/后端"

`p3-entry-01` 记录过：停止承载页面的 Vite 开发服务器会**重载页面**（断线区间被重置），停止后端进程经 Vite 代理**不会**让客户端可见 SSE 断开。本轮进一步确认：在 Vite→后端这一跳上断开 socket，**不会**传播为浏览器可见的断线。

### 3.2 方法（可重复、安全）

本机受控故障代理（仓库外的测试夹具 `sse-fault-app.cjs`）：

- `127.0.0.1:5175` 静态托管**真实构建产物 `dist`**（不使用 Vite 开发服务器 ⇒ **不存在 HMR / 页面重载路径**）；
- `/api` 反向代理到**真实后端** `127.0.0.1:8080`，并在反代时把 `Origin` 覆写为后端 allowlist 中的 `http://localhost:5173`（使真实登录/鉴权可用）；
- **只对 `text/event-stream`** 注入传输中断：`GET /__fault/break?holdMs=N` 断开浏览器侧现存 SSE socket 并在 N 毫秒内拒绝新的 SSE 连接；`GET /__fault/restore` 恢复。

安全性核对：保留真实 Edge 页面与真实后端 Agent 运行；真实免费模型（`cohere/north-mini-code:free`，匿名无付费凭据）；不使用模拟模型；不修改生产业务数据（仅既有合成验收项目）；不耗尽 Provider 配额（逐次单轮）。

### 3.3 观察结果（三轮复现）

页面内标记 `window.__p3marker` 全程为 `keep` ⇒ **未发生页面重载**。

| 运行（脱敏） | 控制台 `[agent-sse]` 记录（依次） | 断线期间 UI | 恢复后 UI |
|---|---|---|---|
| `<redacted-run-A>` | `disconnect(NETWORK, seq 4, attempt 1)` → `transport_reconnected(NETWORK, seq 4)` → `recovered(NETWORK, seq 4)` | `SSE 重连中（传输中断 · 断线前 seq 4 · 第 1 次尝试）` | `SSE 已恢复（传输中断 · 断线前 seq 4）` |
| `<redacted-run-B>` | `disconnect(NETWORK, seq 5, attempt 1)` → `transport_reconnected(NETWORK, seq 5, attempt 5)` → `recovered(NETWORK, seq 5, attempt 5)` | `重连中（传输中断 · 断线前 seq 5 · 第 5 次尝试）` | `SSE 已恢复（传输中断 · 断线前 seq 5）` |
| `<redacted-run-C>` | `disconnect(NETWORK, seq 5, attempt 1)` → `transport_reconnected(NETWORK, seq 5, attempt 6)` → `recovered(NETWORK, seq 5, attempt 6)` | `SSE 重连中（传输中断 · 断线前 seq 5 · 第 5 次尝试）` | `SSE 已恢复（传输中断 · 断线前 seq 5）` |

证据：

- 截图 [02-agent-sse-reconnecting.png](screenshots/02-agent-sse-reconnecting.png)（断线期间：`运行 · 运行中`、`SSE 重连中（传输中断 · 断线前 seq 5 · 第 5 次尝试）`、`断线诊断：重连中`）；
- 截图 [03-agent-sse-recovered.png](screenshots/03-agent-sse-recovered.png)（恢复后：`运行 · 已完成`、`SSE 已恢复（传输中断 · 断线前 seq 5）`、`断线诊断：已恢复`；左侧最终回答**仅一次**且带真实来源标记）；
- 脱敏证据 [agent-sse-recovery-sanitized.json](network/agent-sse-recovery-sanitized.json)。

### 3.4 关键不变量

| 不变量 | 证据 |
|---|---|
| 同一断线区间只产出 **1 条** `disconnect` | 断开期间出现多次被拒的重连请求（`events?afterSequence=5` 多次），控制台仅 1 条 `disconnect`；`attempt` 只累加（B/C 达 5、6） |
| 恢复后新断线可重新记录 | 单元测试 `恢复后同一运行的新断线能重新记录` |
| 续接游标正确 | 断开前 `lastSequence` 记入后续 `afterSequence` / `Last-Event-ID`；断开到恢复全程 `lastSequence` 锚定同一位点（4/5） |
| 无重复事件 / 无重复最终消息 | 会话中同一运行的最终 ASSISTANT 消息仅出现一次（DOM 断言：`article` 列表中每个运行的 assistant 内容唯一） |
| 仅 HTTP 200 ≠ 完整恢复 | 记录顺序为 `disconnect` → `transport_reconnected` → `recovered`；`transport_reconnected` 只在 `onOpen` 产生，`recovered` 只在事件被接受后产生 |

### 3.5 诚实边界

- **未**注入真实 Provider 429（不以耗尽额度制造错误）⇒ 仍 `NOT_TESTED`。
- 真实故障注入只用**传输层中断**（客户端可见的真实断线），不使用对 `fetch` 的桩替或模拟模型。
- 页面由受控代理托管**构建产物**（非 Vite 开发服务器），这是为消除 HMR/重载路径所做的受控选择；应用代码与后端均未改动，SSE 重连/退避策略不变。

---

## 4. 任务四 · 隐私与测试

### 4.1 脱敏审查（控制台 runId）

- 诊断记录契约仅含 `scope, phase, category, runId, lastSequence, attempt[, httpStatus]`，**不含**正文、Header、Cookie、Token、API Key、文档内容或用户身份。
- `runId` 是高基数**追踪 ID**（后端亦以 `runId` 记录可观测字段），不含用户身份；据此，**内部调试控制台保留完整 `runId` 用于运行关联**，与项目既有做法一致。
- **导出证据一致匿名化**：本报告与 `agent-sse-recovery-sanitized.json` 中运行 ID 一律以 `<redacted-run-A/B/C>` 占位符替代；`p3-entry-01` 的脱敏网络记录同样使用 `<redacted-run-uuid>`。结论：**无需修改代码**，证据脱敏口径一致。

### 4.2 新增测试

| 层 | 文件 | 覆盖 |
|---|---|---|
| 纯函数 | `agent-sse-diagnostics.test.ts` | 归类（含"无本地证据不判 `CLIENT_ABORT`"）、`transport_reconnected` ≠ `recovered`、`aligned()` 才产出 `recovered`、非 `TRANSPORT_RECONNECTED` 不对齐、恢复后新断线重记、`STREAM_ENDED` 收口、`reset` |
| 状态机 | 同上 | 区间收敛、`attempts` 复位与再次累计 |
| 组件显示 | `AgentView.restore.test.ts` | 同一页面依次 `disconnect → transport_reconnected → recovered`，并在恢复后断言 `[data-test="agent-sse-diagnostic"]` 含"已恢复 · 传输中断" |
| 事件续接 | `AgentView.restore.test.ts`（既有）| 断线后按 `afterSequence` / `Last-Event-ID` 从最后接受序号重连，不重复事件 |

**未削弱既有校验**：既有断线重连断言（重连游标、`afterSequence`、不重复事件）全部保留。

---

## 5. 自动回归

### 5.1 前端

| 检查 | 命令 | 结果 |
|---|---|---|
| Lint | `pnpm lint` | 通过；**0 errors、78 warnings**（均为既有 warning，本轮新增/修改文件未引入新 warning） |
| 类型 | `pnpm typecheck`（vue-tsc -b） | 通过 |
| 单测 | `pnpm test` | **43 个文件、269 项通过**（较 P2 基线 42/249，较 `p3-entry-01` 的 43/264 增加 5 项） |
| 构建 | `pnpm build` | 通过；仅既有 PURE 注释与大 chunk 提示 |
| 差异空白 | `git diff --check` | 通过 |

### 5.2 后端

命令：Windows Maven Wrapper `mvnw.cmd clean verify`（工作目录 `ai-collab-backend`；运行前已停止占用 `target/` 的本机 dev 后端进程）。

结果：**`BUILD SUCCESS`**；`Tests run: 1533, Failures: 0, Errors: 0, Skipped: 18`（与 `p3-entry-01` 一致；18 项均为显式 `AI_*` opt-in 真实服务测试，本轮未开启）。

---

## 6. 结论（对应五个必答问题）

**1. 断线归因是否可信？**
**是（且更克制）。** 已删除"无记录 + `ERR_ABORTED` ⇒ 客户端主动取消"这一证据不足的推断：只有显式本地取消证据才判 `CLIENT_ABORT`，其余 `UNKNOWN`。`p3-entry-01` 的相关过度归因已**勘误改判 `UNKNOWN`**；历史 `ERR_ABORTED` 保持**未归因**，未被翻案。

**2. UI 已恢复状态是否真实可见？**
**是。** 复现了 `category=null` 导致无法展示"已恢复"的问题，并以最小状态模型修复：`TRANSPORT_RECONNECTED` 与 `RECOVERED` 分离，恢复后保留 `category`，UI 稳定展示"已恢复"。组件测试与真实浏览器截图均见 `SSE 已恢复（传输中断 · 断线前 seq 5）`。

**3. 同一页面的 recovered 是否在真实浏览器得到证明？**
**是。** 在真实 Edge 页面 + 真实后端 + 真实免费模型下，用受控 SSE 故障代理三轮复现同一页面的 `disconnect → transport_reconnected → recovered`；页面标记证明**未重载**，`attempt` 证明多次重连失败仍只记 1 条 `disconnect`，DOM 证明最终回答**只出现一次**，续接游标与 `Last-Event-ID` 一致。

**4. 有没有新增风险或回归？**
**没有新增阻断问题。** 改动为前端附加式（诊断状态机 + 展示 + 注释/文档），不改后端/数据库/契约，不改 Agent Runtime、工具、审批、模型重试与既有 SSE 重连/退避策略；前端 lint/typecheck/test/build 与后端 `clean verify` 全绿，既有测试保留且未削弱。可回滚：删除 `p3-obs-02` 证据、回退诊断状态机与三处接线即复原。

**5. P2 剩余风险清单是否发生变化？**

| 编号 | 遗留项 | 本轮变化 |
|---|---|---|
| R-1 | 6 份历史失败文档 | **未变**（BLOCKED，需各自 Owner 合法重试） |
| R-2 | Agent 历史 `ERR_ABORTED` 未归因 | **收敛但未关闭**：新增可信归因规则；`p3-entry-01` 的过度归因已勘误为 `UNKNOWN`；历史事件**仍为未归因** |
| R-3 | Agent SSE 断线恢复仅自动化验证 | **收敛**：已在真实浏览器证明 `disconnect → transport_reconnected → recovered`（不再是 NOT_TESTED） |
| R-4 | 真实 Provider 429 未注入 | **未变**（NOT_TESTED，不以 Mock 冒充） |
| R-5 | 18 项 opt-in 测试被跳过 | **未变**（已知限制，未开启） |

---

## 7. Git 与 CI

- 开工基线：`969ab82ed8343dddc685a197562c68e5a3d03792`。
- 变更文件：`ai-collab-frontend/src/modules/agent/`（`agent-sse-diagnostics.ts`、`use-agent-workspace.ts`、`AgentView.vue` 及对应测试）、`docs/acceptance-evidence/2026-10-20/p3-entry-01/`（勘误）、新增 `docs/acceptance-evidence/2026-10-20/p3-obs-02/`。
- 测试夹具（受控 SSE 故障代理 `sse-fault-app.cjs`）位于仓库外，方法已在 §3.2 完整记录以便复现，未进入仓库。
- 提交与 CI 结果见交付消息（commit SHA、GitHub Actions run）。
