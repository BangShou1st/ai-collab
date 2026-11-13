# P1 W6 default-off 垂直切片冻结（2026-11-13）

> 状态：**FROZEN / IN_PROGRESS**
> 基线：`dev @ e160f6d2`（以实际 HEAD 为准）
> 性质：一轮会话内的 W6 P1 最小闭环，不是完整 W6，不进入生产灰度
> 证据目录：`docs/acceptance-evidence/2026-11-13/p1-default-off/`

## 1. 目标与边界

本轮只闭合以下 Must-have：

| 编号 | 必达项 | 完成口径 |
| --- | --- | --- |
| M1 | W6 P0 | Java Gateway ↔ Python Host 内部契约、只读工具 schema/hash、错误码冻结并有双语言测试 |
| M2 | W6 P1 | 一个本地只读测试 MCP Server；Python 与 Java `tools/list` 对同一 fixture 逐项一致；连接/项目白名单、描述与 Schema 清洗、审计 |
| M3 | W6 最小 P2 | 一个只读工具经 Java Gateway 执行；结果清洗；成功/拒绝/未执行/超时/未知等失败可区分 |
| M4 | 安全最小项 | 私网/回环/元数据拒绝、描述与 Schema 注入清洗、在途撤权 fence，各至少一条测试 |
| M5 | D4 最小准入 | provider test/publish 记录 P50/P95、超时、失败；明显超时拒绝发布 |
| M6 | 默认关闭 | `agent.mcp.gateway.enabled=false`；关闭时继续使用现有 `McpAgentToolProvider` |
| M7 | 交付门禁 | Java/Python 定向与全量门禁、CI、文档同步、临时资产清理 |

Stretch，只有 Must-have 全绿后才可启动：

- N5 Reranker 管理 UI 最小版；
- JMeter per-label 报告收口（仅文档，不重跑容量）。

明确不做：

- W6-B 完整安全/E2E、多 MCP Server、多租户；
- 生产 canary、TLS/ingress、W3/W6 生产验证；
- Testcontainers 本机抖动根治；
- 111 MB 历史重写；
- MCP 写工具、STDIO、Python 直连外部 MCP、Python 业务库访问。

## 2. 前置事实

- W5 Memory C：**PASS**；sourceRef、删除 500 与泄漏矩阵已收口。
- W4 decide 连续调优：**PASS**；R5 golden 20/20、JSON 20/20、invalidToolCalls 0。
  - 这是调优目标达成，不切换 `DECIDE_MODE`，不替代生产 canary。
- W3 分层延迟：用户已接受现状口径。
  - inline：8 候选 / 3s；
  - async/offline：20 / 50 候选；
  - `RERANK_MODE=java` 不变，不把 30s functional 写成 3s PASS。
- Magpie：已登录并运行；中心只执行一次 `magpie healthcheck`，退出码 **0**。
  - 不 logout、不重启、不改 provider、不重新扫码。
  - 本轮不调用 Gemini。
- 其他默认开关不变：`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、
  `AI_WORKER_TOKEN_MODE=static`。

## 3. 契约与资源冻结

跨语言契约唯一来源：

- `docs/api/mcp-gateway-internal-v1.md`
- `tests/fixtures/mcp/catalog-v1.json`

所有泳道必须遵守，不得自行改字段名、Hash 输入或错误码。确需变更时停止并回报中心，
由中心统一修改后再继续。

### 3.1 分支与 worktree

| 泳道 | 分支 | worktree | 写入范围 |
| --- | --- | --- | --- |
| A Java Gateway | `p1/mcp-gateway` | `E:\project\ai-collab-worktrees\p1-mcp-gateway` | Java Gateway、内部端点、安全与审计、默认开关 |
| B Python Host | `p1/mcp-host` | `E:\project\ai-collab-worktrees\p1-mcp-host` | Python Host 客户端、归一化、契约与测试 |
| C E2E + D4 + 本地 Server | `p1/e2e-d4` | `E:\project\ai-collab-worktrees\p1-e2e-d4` | 本地只读测试 Server、E2E、D4、证据 |
| D Stretch | `p1/stretch` | 最后创建 | 仅 N5 UI / JMeter 文档 |

子 agent 只在自己 worktree 提交，不 push、不合并 `dev`、不改主工作树。
中心负责 cherry-pick、冲突处理、全量门禁、CI 与清理。

### 3.2 资源锁

| 资源 | 规则 |
| --- | --- |
| `magpie-auth` | 中心独占；子 agent 不得调用 Magpie 管理命令、logout、重启或扫码 |
| `repo-main` | 中心独占；子 agent 不进入主工作树写文件 |
| `local-gpu` / `local-docker` | 本轮不启动 Reranker；仅 E2E 需要时由 Lane C 独占 |
| `staging` | 本轮不做 staging 写操作，除非中心明确批准 |
| 本地测试 MCP 端口 | 每种测试使用临时端口，禁止固定占用或遗留进程 |

冲突一律排队；不得并行硬抢。

## 4. 泳道交付要求

### Lane A：Java MCP Gateway

- 新增 internal MCP Gateway，默认关闭；端点复用 `/internal/ai/` 内部鉴权。
- 实现 `initialize`、`ping`、`tools/list`、`tools/call` 的 Java 侧最小契约。
- 逐工具 `toolSchemaHash` 与 `catalogVersion`；算法严格按冻结契约。
- 只读三重条件：连接启用且确认 Hash、连接白名单、项目绑定/白名单；不新增写路径。
- 描述/Schema 进入上下文前 NFC、控制字符、限长、限深、注入文本中和；原始值不向模型暴露。
- 调用前与结果返回前各校验一次 catalog/binding/lease fence；撤权后不得返回成功正文。
- 统一审计字段，不记录完整参数/结果/凭据；错误分类稳定。
- `agent.mcp.gateway.enabled=false` 时旧 `McpAgentToolProvider` 行为不变；测试覆盖开关回退。
- 如需迁移只允许 **V98+ 新文件**，不得修改历史迁移；优先无迁移。

### Lane B：Python MCP Host

- 新增内部 MCP Host 客户端，只调用 Java `/internal/ai/v1/mcp`，不直连外部 MCP。
- 实现 `initialize`、`ping`、`tools/list`、`tools/call` 客户端与规范化。
- 与 Java 共用 `tests/fixtures/mcp/catalog-v1.json`，证明工具名、描述、Schema、逐工具 Hash、
  聚合 Hash 逐字节一致。
- 无 Provider 密钥、无业务库/Redis/MinIO、无外部 URL 出网；非 Java Gateway 地址应拒绝。
- Python Host 不重试、不判定授权、不自动补默认参数；失败分类原样保留。
- 默认关闭由调用方控制，不新增生产路由；如提供测试端点，必须测试态启用。

### Lane C：E2E + D4 + 本地测试 MCP Server

- 提供本地只读测试 MCP Server，仅使用合成数据，无第三方凭据、无写操作。
- 至少一条经 Java Gateway 的只读工具调用 E2E，覆盖结果清洗与成功语义。
- 失败分类至少覆盖：拒绝、未执行、deadline、结果未知；不得把失败洗成成功。
- D4：provider test/publish 记录 P50/P95、超时数、失败数；准入样本版本必须匹配当前配置；
  明显超时拒绝发布。不得把单次成功冒充稳定容量。
- 证据放 `docs/acceptance-evidence/2026-11-13/p1-default-off/lane-c/`。

### Lane D：Stretch

- 仅在中心确认 Must-have 全绿后启动。
- N5 只做最小可用 UI，不改变 `RERANK_MODE=java`；
- JMeter 只收口已有 per-label 报告，不重跑容量、不外推生产。

## 5. 停止条件

出现以下任一情况，立即停止扩张并写 checkpoint：

1. Magpie 健康检查失败：`WAITING_FOR_AUTH`，不得重配。
2. W6 设计存在未决安全决策：`BLOCKED_DECISION`。
3. Must-have 5 小时未闭环：停止扩展，交垂直切片与未完成清单。
4. 需要生产 canary、多租户或外部真实 MCP 凭据：停，交 P2。
5. 需要改默认开关或 `main`：停，交用户决策。
6. 需要修改冻结契约：停，回报中心统一处理。

## 6. 证据与清理

- 每个泳道只写自己的证据子目录，附命令、时间、版本、原始结果摘要。
- 日志只写入各 worktree 的 `target/` 或证据目录，禁止生成在仓库根或源码目录。
- 不提交密钥、token、登录态、二维码、截图中的凭据或完整外部结果正文。
- 收尾删除临时 worktree；分支如不保留，先打 `archive/20261113/<lane>` tag。
- 中心在 `dev` 完成最终报告后再决定是否清理临时分支。

## 7. 当前判定

```text
P1 W6 default-off：IN_PROGRESS
生产 W6：NOT_READY / BLOCKED
W3：3S_FAIL / FUNCTIONAL_PASS（用户已接受分层口径）
W4：调优目标 PASS（不切换生产模式）
W5：PASS
```
