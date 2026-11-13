# P2-A 真实进程 E2E 与安全矩阵报告（2026-11-13）

> 基线：`dev @ 5d77d31c`；P2-A 代码/证据集成见最终提交
> 结论：**P2-A PASS（真实进程 E2E + M-01～M-12 最小矩阵）；生产 W6 仍 `NOT_READY`**

## 1. 完成表

| 范围 | 结果 | 证据等级 | 证据 |
| --- | --- | --- | --- |
| 真实 Java Gateway | PASS | TESTED(REAL_PROCESS) | PID `20520`，端口 `54486/54487` |
| 真实 Python Host | PASS | TESTED(REAL_PROCESS) | PID `2376`，端口 `54488` |
| 本地 TLS MCP Server | PASS | TESTED(REAL_PROCESS) | PID `56560`，端口 `54489` |
| Java/Host tools/list 一致 | PASS | TESTED(REAL_PROCESS) | 11 tools，`catalogVersion=sha256:dc67...` |
| 真实只读调用/清洗 | PASS | TESTED(REAL_PROCESS) | `echo_text` 成功；`warnings` 非空、凭据脱敏 |
| 安全矩阵 M-01～M-12 | PASS 12/12 | 混合 | `lane-c/security-matrix.md` |
| default-off/旧 Provider | PASS | CONFIG | `McpGatewayConfigurationTest` 3/3 |
| Python 全量 | PASS | TESTED | 1273 passed |
| 前端 | PASS | TESTED | 491 tests + lint/typecheck/build |
| Java 全量 | 本机存量抖动 | TESTED(PARTIAL) | 见证据报告；CI 为最终判据 |

## 2. 关键证据

- 三进程原始 JSON：`docs/acceptance-evidence/2026-11-13/p2a-real-process/lane-c/raw/real-process.json`
- 离线 TLS 夹具：`docs/acceptance-evidence/2026-11-13/p2a-real-process/lane-c/raw/offline-tls.json`
- M-02/M-12 Java 原始输出：`docs/acceptance-evidence/2026-11-13/p2a-real-process/center/raw/java-m02-m12.txt`
- 完整验收报告：`docs/acceptance-evidence/2026-11-13/p2a-real-process/report.md`

## 3. 明确限制

- 真实进程 E2E 使用 local/test 静态测试绑定，生产 Ed25519 身份链路未真实跑通；
- MCP Server 为本地合成 TLS 夹具，未使用外部 MCP Provider；
- M-02 为 pinned-DNS 单测，M-12 为配置/Bean 测试；
- 生产 canary、TLS/ingress、多租户、W6-B、N5 UI、JMeter 重跑均未做。

## 4. 结论

P2-A 只证明 W6 具备真实进程 E2E 和安全最小矩阵，**不代表生产可 canary、可灰度或可上线**。
