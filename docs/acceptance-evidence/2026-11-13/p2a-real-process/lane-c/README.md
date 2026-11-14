# P2-A Lane C 证据索引

> 分支：`p2a/security-e2e`；中心集成基线：Lane A/B/C + center follow-ups
> 证据等级：9 项 `REAL_PROCESS` + 1 项 `PREFLIGHT/STATIC` + 1 项 `JAVA_UNIT` + 1 项 `CONFIG`
> 结论：**P2-A CONDITIONAL PASS**；真实三进程 E2E 已执行，但不把 M-01/M-02/M-12 写成真实外部调用证据。

## 1. 当前结论

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 本地 TLS MCP Server | PASS（离线） | `raw/offline-tls.json` |
| 真实 Java + Python + MCP 三进程 | **PASS（TESTED(REAL_PROCESS)）** | `raw/real-process.json` |
| Java/Host `tools/list` 逐项与 Hash | PASS（REAL_PROCESS） | `raw/real-process.json` 的 `M-11.*` |
| 真实只读调用/清洗/warning | PASS（REAL_PROCESS） | `raw/real-process.json` 的 `M-04.*` |
| 撤权与在途 fence | PASS（REAL_PROCESS） | `raw/real-process.json` 的 `M-08.revocation-fence` |
| M-01～M-12 | **CONDITIONAL PASS（证据等级分层）** | `security-matrix.md`；M-01/M-02/M-12 非全真实进程 |
| M-01 preflight 证据 | PASS（PREFLIGHT/STATIC） | `raw/real-process.corrected.json`；`center/raw/java-m01-targeted.txt` |

## 2. 本轮进程与端口

> 运行时间：2026-11-13 17:25（Asia/Shanghai）；证据文件生成于 `17:25:27`。
> 三个进程均被 harness 的 `taskkill /T /F` 回收，因此日志中的退出码为 1 是**预期的强制终止**，
> 不是应用自行崩溃；端口回收已由脚本检查。

| 组件 | PID | 端口 | 启动 → 停止 |
| --- | --- | --- | --- |
| Java Spring Boot Gateway | `20520` | gateway `54486` / management `54487` | `17:25:08` → `17:25:26` |
| Python Uvicorn Host | `2376` | `54488` | `17:25:20` → `17:25:25` |
| 本地 TLS MCP Server | `56560` | `54489` | `17:25:08` → `17:25:26` |

调用链按冻结契约执行：`Python Host -> Java Gateway -> 本地 TLS MCP Server`；Java 仍是唯一外部
MCP 出网点。Host 只调用固定 Java 内部端点，不持有外部 MCP 凭据、不直连业务库。

## 3. 运行命令

```powershell
cd ai-collab-ai-worker
uv run pytest tests/e2e/test_p2a_security_fixture.py tests/e2e/test_mcp_gateway_contract_e2e.py -q
uv run python tests/e2e/run_p2a_real_process.py --mode offline
uv run python tests/e2e/run_p2a_real_process.py --mode real `
  --java-jar E:\project\ai-collab\ai-collab-backend\target\ai-collab-backend-0.0.1-SNAPSHOT.jar `
  --env-file E:\project\ai-collab\.env `
  --pg-container ai-collab-postgres --pg-database ai_collab_p2a_e2e
```

真实模式见 [`runbook.md`](runbook.md)。

## 4. 产物

- `raw/real-process.json`：真实进程表、端口、21 个已执行 case、矩阵、Java/Host 原始 `tools/list`；
  其中 M-01 的 `PASS/NOT_EXECUTED` 只按 preflight/static 解释；
- `raw/real-process.corrected.json`：不覆盖 raw 的口径修正 sidecar；旧 raw hash
  `E37D3A7B...` 固定；
- `raw/offline-tls.json`：离线 TLS/故障夹具原始结果；
- M-01 定向输出：`../center/raw/java-m01-targeted.txt`；
- M-02/M-12 的 Java 原始输出：`../center/raw/java-m02-m12.txt`；
- 证书、私钥、truststore、进程日志只在 `target/p2a-e2e/`，不进入证据提交。
