# P2-A 真实进程 E2E 与安全矩阵冻结（2026-11-13）

> 状态：**FROZEN / IN_PROGRESS**
> 基线：`dev @ 5d77d31c`（以实际 HEAD 为准）
> 证据目录：`docs/acceptance-evidence/2026-11-13/p2a-real-process/`
> 性质：在 P1 default-off 垂直切片上补齐真实 Java/Python 进程 E2E 与安全最小矩阵；
> 不是生产 canary、灰度或上线证明。

## 1. 目标与边界

本轮只闭合以下 Must-have：

| 编号 | 必达项 | 完成口径 |
| --- | --- | --- |
| P2A-1 | 真实进程 E2E | 分别启动真实 Java Gateway、真实 Python Host（Uvicorn/FastAPI）和本地只读 MCP Server，记录 PID、端口、请求 ID、日志路径与时间戳；不得用 in-process/stub 替代跨进程 HTTP |
| P2A-2 | 双端目录一致性 | Python Host 与 Java Gateway 的 `tools/list` 在工具名称、描述、Schema、`toolSchemaHash`、`catalogVersion` 上逐项一致 |
| P2A-3 | 真实只读调用 | 至少一条调用经过 Python Host、Java Gateway、本地 MCP Server 并返回；验证结果清洗、截断与 warning；失败不得伪装成功 |
| P2A-4 | 安全矩阵 | M-01 至 M-12 的适用项有 PASS/FAIL/NOT_RUN 与原始证据；至少包含一条真实进程调用和一条撤权/fence |
| P2A-5 | default-off 与回退 | `agent.mcp.gateway.enabled=false` 时端点不装配，旧 `McpAgentToolProvider` 可用，行为无变化 |
| P2A-6 | 门禁与交付 | Java/Python 定向与全量测试、前端既有门禁、CI、文档与清理完成 |

Stretch（Must-have 全绿后才做）：

- 本地进程重启与回滚演练；
- 观测指标/告警字段的最小验证。

明确不做：

- W6-B 多 MCP Server、多租户；
- 生产 canary、TLS/ingress、W3/W6 生产验证；
- N5 Reranker 管理 UI；
- JMeter per-label 重跑；
- Java Testcontainers 本机抖动根治；
- 仓库历史重写；
- MCP 写工具、STDIO、Python 直连外部 MCP、Python 访问业务库/Redis/MinIO。

## 2. 基线事实

- W4 已 PASS：Prompt v5，golden 20/20、JSON 20/20、`invalidToolCalls=0`。
  - 本轮**不重跑、不修改 Prompt**，也不切换 `DECIDE_MODE`。
- W6 P1 default-off 已落地：P0/P1/最小 P2 服务级通过；代码 HEAD 为 `bdaa128f`，
  文档收口 HEAD 为 `5d77d31c`。
- P1 证据等级为 `TESTED(STUB)` / `TESTED(offline)`；最大缺口是真实 Java Gateway
  进程与 Python Host 进程之间的 E2E，当前为 `NOT_RUN`。
- P1 的本地 MCP Server 是 HTTP 回环夹具；生产 `McpEndpointPolicy` 只允许 HTTPS 443、
  allowlist 精确 host 且拒绝私网/回环/元数据地址，因此不能直接把 stub 结果当成真实 E2E。
- Java 本机全量测试有 1 个 Testcontainers 存量断言抖动；CI 绿。P2-A 不负责根治。
- W3 分层延迟口径已由用户接受；W6 仍 default-off。
- Magpie 已登录并运行。本轮预计不需要模型调用，因此**不碰 Magpie**；若确有模型调用，
  只允许 Magpie，不允许 Gemini。

## 3. 信任边界与调用方向

真实 E2E 遵循冻结设计，不把 Python Host 插入 Java Agent 同步主路径：

```text
Python Host 进程
  -> Java Gateway 进程（唯一外部 MCP 出网、凭据、授权、审计出口）
  -> 本地只读 MCP Server
```

`Python Host -> Java Gateway` 是内部 MCP 协议客户端调用；Java Gateway 仍是唯一发起
本地 MCP Server 连接的组件。禁止 Python 直接连接测试 MCP Server、持有外部 MCP 凭据、
读取业务库或决定授权。若产品要求 Java 主动调用 Python Host 并让其进入执行主路径，
属于设计变更，停止并交用户决策。

## 4. Lane、worktree 与写入范围

| Lane | 分支 | worktree | 写入范围 |
| --- | --- | --- | --- |
| A Java Gateway | `p2a/java-gateway` | `E:\project\ai-collab-worktrees\p2a-java-gateway` | Java Gateway 的 local/test 真实进程测试支持、TLS/回环测试守卫、契约/审计/default-off 测试 |
| B Python Host | `p2a/python-host` | `E:\project\ai-collab-worktrees\p2a-python-host` | 真实 Uvicorn/FastAPI Host 进程、Host 契约/清洗/失败分类测试 |
| C Security E2E | `p2a/security-e2e` | `E:\project\ai-collab-worktrees\p2a-security-e2e` | 本地 TLS 只读 MCP Server、真实进程编排、安全矩阵 M-01～M-12、原始证据 |
| D Center | 主工作树 `dev` | `E:\project\ai-collab` | 中心集成、cherry-pick、全量门禁、CI、文档、清理、最终判定 |

Lane A/B/C 只在自己的 worktree 提交；不得 push、合并 `dev`、修改主工作树。
中心负责统一 cherry-pick、冲突处理、门禁与 push。

### 4.1 测试专用安全例外

为了在本地完成真实 TLS 进程 E2E，允许新增**仅 local/test 可启用**的测试回环端点支持：

- 新配置默认 `false`，生产 profile 配置为 `true` 必须启动失败；
- 只允许 `https`、精确 `127.0.0.1`/`::1` 或 `localhost`，端口不做生产放宽；
- 仍要求 URI 无 user-info/fragment，执行 pinned DNS；不得允许任意私网、元数据或 HTTP；
- 生产 `McpEndpointPolicy` 的 HTTPS 443、allowlist、公网解析规则不得改变；
- 测试证书、私钥、truststore 只放临时证据目录或 `target/`，不得提交。

这是测试夹具边界，不是生产 egress 能力。M-01/M-02 必须证明该例外不能放行元数据、
私网或 allowlist 外主机。

## 5. 资源锁

| 资源 | 规则 |
| --- | --- |
| `p2a-process` | 真实 Java/Python 进程 E2E 独占；Lane C 运行 E2E 时其他 Lane 不得抢启动同组进程 |
| `p2a-mcp-server` | 本地 MCP Server 独占临时端口；退出必须回收；禁止固定端口遗留 |
| `magpie-auth` | 中心独占；不 logout/重启/改 provider；本轮默认不使用 |
| `staging` | 本轮不做 staging 写操作；如确需使用，同一时刻只允许一个 Lane |
| `repo-main` | 中心独占；子 agent 不进入主工作树写文件 |
| 本地 PostgreSQL | E2E 使用独立测试库或明确测试项目；禁止污染现有业务项目数据 |

资源冲突排队，不并行硬抢。Lane C 未拿到 `p2a-process` 时只能做离线 fixture 与断言开发，
不得启动真实 E2E 并宣称 PASS。

## 6. 证据与进程记录

每个 Lane 只写自己的证据子目录：

```text
docs/acceptance-evidence/2026-11-13/p2a-real-process/
  lane-a/
  lane-b/
  lane-c/
  center/
```

真实进程表至少记录：

- 组件、启动命令、PID、容器名（如使用）、监听端口；
- 启动/就绪/停止时间戳（Asia/Shanghai）；
- 请求 ID / `X-Mcp-Call-Id` / runRef；
- 日志相对路径、stdout/stderr 摘要；
- 调用链与原始 JSON 结果（脱敏）。

禁止记录完整 Bearer、数据库密码、证书私钥、MCP 凭据、Prompt 正文或完整外部结果正文。
临时 token 只经环境变量传递，不落盘；如必须写入测试脚本，使用明显的 synthetic 固定值，
并在清理时确认不进入提交。

## 7. 安全矩阵判据

| 编号 | 判据 | 最低证据 |
| --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外 host 拒绝，零外部连接 | 单测 + 真实 Gateway 拒绝场景 |
| M-02 | 校验后解析变化仍使用固定地址 | 单测/契约测试，记录 resolver 调用次数 |
| M-03 | 工具描述含 injection 只作为不可信数据并清洗 | 单测 + catalog 原始输出断言 |
| M-04 | 结果含假 system/tool 指令不可执行，warning 标记 | 真实进程调用 |
| M-05 | Schema 变化自动停用并审计 | 服务/集成测试 + 审计事件 |
| M-06 | 未确认/未白名单/未绑定工具不暴露、直调拒绝 | 真实进程 `tools/list` + `tools/call` |
| M-07 | 请求体伪造 `projectRef/userRef/projectId/userId` 被拒 | 真实进程请求 + 错误码 |
| M-08 | 撤权后新调用拒绝；在途结果标 revoked/unknown | 真实进程慢调用 + DB 撤权 |
| M-09 | 429/timeout/5xx/partial 分类，不伪造成功 | 单测 + 可控上游故障 |
| M-10 | 超大/二进制/路径结果截断或拒绝 | 真实进程截断调用 + 清洗断言 |
| M-11 | 双端 `tools/list`/Hash 一致 | 真实 Python Host 与 Java Gateway 原始 JSON 对比 |
| M-12 | Gateway 关闭回退旧 Provider，无行为变化 | 单测/配置测试 + default-off 启动记录 |

不适用的项必须写明 `NOT_RUN` 与原因，不能用 `SKIP` 掩盖关键安全失败。

## 8. 停止条件

1. 真实进程 E2E 无法启动：写 `NOT_RUN`，不得回退 stub 冒充 PASS。
2. 安全矩阵任一项失败且无法在范围内修复：写 `FAIL`，不得写 PASS。
3. 需要改默认开关、W4 Prompt、标签或 `main`：停止，交用户决策。
4. 需要第三方 MCP 凭据、外部生产数据或 staging 写操作：停止，改用本地只读测试 Server。
5. 需要修改冻结内部契约或把 Python Host 插入主路径：停止，回报中心统一决策。
6. 一天内未完成：收口 Must-have 已完成部分，剩余写 Deferred，不开新范围。

## 9. 中心验收

中心在集成后必须独立复核：

- 三条 Lane 的提交与证据可追溯；
- 真实进程 E2E 原始 JSON、PID/端口/日志齐全；
- `tools/list` 双端逐项与冻结 fixture 一致；
- 至少一条真实只读调用与一条撤权/fence；
- `agent.mcp.gateway.enabled=false` 与旧 Provider 回退；
- Java/Python 全量门禁、前端门禁、CI 全绿（存量 Testcontainers 抖动如出现，必须附单类复跑与 CI 证据）；
- 文档、证据目录、临时进程/日志/证书/测试库清理完成。

生产 W6 在所有证据齐备前始终保持 `NOT_READY`，不得宣称可 canary 或灰度。
