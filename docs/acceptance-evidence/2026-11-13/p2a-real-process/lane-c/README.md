# P2-A Lane C 证据索引

> 分支：`p2a/security-e2e`
> 冻结基线：`dev @ 720c601d`（P2-A handoff 提交）
> 证据等级：夹具 `FIXTURE_READY`；真实三进程 `NOT_RUN`（待中心合并 Lane A/B 后执行）

## 1. 当前结论

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 本地 TLS MCP Server | PASS（离线） | `raw/offline-tls.json` |
| 429 / 500 / 206 夹具 | PASS（离线） | `raw/offline-tls.json` |
| 超大/二进制/路径/注入载荷夹具 | PASS（离线） | `raw/offline-tls.json` |
| 真实 Java + Python + MCP 三进程 | **NOT_RUN** | 需中心合并 A/B 后按 `runbook.md` 执行 |
| M-01～M-12 | 见 `security-matrix.md` | 不把离线夹具写成真实 PASS |

离线模式只证明测试 Server、TLS、故障注入和目录夹具可用；不证明 Java Gateway、
Python Host、授权、撤权、清洗和回退已经通过真实进程验收。

## 2. 运行命令

```powershell
cd ai-collab-ai-worker
uv run pytest tests/e2e/test_p2a_security_fixture.py tests/e2e/test_mcp_gateway_contract_e2e.py -q
uv run python tests/e2e/run_p2a_real_process.py --mode offline
```

真实模式见 [`runbook.md`](runbook.md)。

## 3. 产物

- `raw/offline-tls.json`：离线 TLS/故障夹具原始结果；
- 真实模式运行后生成 `raw/real-process.json`（进程表、端口、矩阵、Java/Host 原始 tools/list）；
- 证书、私钥、truststore、进程日志只在 `target/p2a-e2e/`，不进入证据提交。
