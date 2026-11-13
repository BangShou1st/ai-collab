# P2-A 中心证据索引

## 1. 真实进程

- `lane-c/raw/real-process.json`：真实 Java Gateway + Uvicorn Host + 本地 TLS MCP Server；
  21/21 已执行 case PASS；进程表、端口、PID、日志相对路径、tools/list 与安全矩阵原始结果。
- `lane-c/raw/real-process.corrected.json`：不覆盖 raw 的口径修正 sidecar；
  M-01 的 `PASS/NOT_EXECUTED` 按 `PREFLIGHT/STATIC` 解释，不是 external-negative 真实调用。

## 2. 非真实进程补充

- `raw/java-m02-m12.txt`：
  - `McpEndpointPolicyTest`：M-02 pinned DNS / allowlist / 私网/元数据拒绝；
  - `McpGatewayConfigurationTest`：default-off 不装配 Gateway Controller，旧
    `McpAgentToolProvider` 仍存在；
  - `McpGatewayStaticBindingTest`：静态测试绑定只允许完整 local/test 配置。
- `raw/java-m01-targeted.txt`：
  - M-01 定向 `McpEndpointPolicyTest` + `McpGatewayServiceTest`，23/23 PASS；
  - 证明策略在外部 socket 创建前拒绝元数据/allowlist 外目标。

`real-process.json` 的聚合矩阵把 M-02/M-12 标为 `NOT_RUN` 是准确的，因为它们没有在
三进程场景中执行。最终 `security-matrix.md` 把 M-02/M-12 的 Java 单测/配置证据与真实
进程证据合并为 `CONDITIONAL PASS`，并在表中单独标明证据等级。
旧 raw 的 M-01 总表口径标记为 `CORRECTED_FOR_REPORTING` / `SUPERSEDED_FOR_CLAIM_ONLY`。

## 3. 复核命令

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp "-Dtest=McpEndpointPolicyTest,McpGatewayConfigurationTest,McpGatewayStaticBindingTest" test
```

原始输出见 `raw/java-m02-m12.txt`（11/11 PASS）。
