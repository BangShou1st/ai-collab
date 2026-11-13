# Lane C：本地只读 MCP E2E + D4 最小准入证据

> 日期：2026-11-13（Asia/Shanghai）
> 分支：`p1/e2e-d4`
> worktree：`E:\project\ai-collab-worktrees\p1-e2e-d4`
> 运行基线：`540a029d`（冻结 handoff 基线；最终提交 SHA 见交接回复）
> MCP E2E 证据等级：**`TESTED(STUB)`**（本地契约 stub，不是真实 Java Gateway）

## 1. 结论摘要

| 项 | 结果 | 证据等级 |
| --- | --- | --- |
| 本地只读测试 MCP Server | initialize / tools/list / tools/call 通过；4 工具、随机端口、退出后端口关闭 | TESTED |
| 冻结 fixture 逐工具 Hash / catalogVersion | 与 `tests/fixtures/mcp/catalog-v1.json` 完全一致 | TESTED |
| 只读调用成功语义 | `outcome=SUCCEEDED`、`untrusted=true`、`truncated=false`；token 字段脱敏、注入中和并 warning | TESTED(STUB) |
| 截断语义 | `outcome=TRUNCATED`、`truncated=true`，预览不含合成 token | TESTED(STUB) |
| 失败分类 | `TOOL_DISABLED`、`SCHEMA_DRIFT`、`NOT_EXECUTED`、`DEADLINE_EXCEEDED`、`OUTCOME_UNKNOWN` 均可区分 | TESTED(STUB) |
| D4 发布准入 | 多次采样记录 P50/P95、超时数、失败数；绑定 `capability_config_version`；超时/失败/版本变化拒绝发布 | TESTED(UNIT + POSTGRES) |
| 真实 Java Gateway E2E | **NOT_RUN**：Lane A 未合并；harness 已提供 `--mode real` / `--gateway-command` 入口 | NOT_RUN |
| 生产容量 / SLO | **NOT_CLAIMED**：3 个样本只用于拦截明显超时，不是容量结论 | NOT_CLAIMED |

## 2. 环境与版本

| 项 | 版本 |
| --- | --- |
| OS / Shell | Windows / PowerShell |
| Java | 21.0.10 |
| Maven | 仓库 `mvnw.cmd`（Spring Boot 4.1.0 项目） |
| Python | 3.13.0（`uv 0.12.2`） |
| Docker | 29.8.0（Testcontainers PostgreSQL 17） |

## 3. 命令与结果

### 3.1 Python 定向测试

```bash
cd ai-collab-ai-worker
uv run ruff check tests/e2e
uv run ruff format --check tests/e2e
uv run pytest tests/e2e -q
```

结果：`3 passed`；ruff check / format 均通过。原始输出见 `raw/python-pytest.txt`。

### 3.2 只读 E2E harness（当前为 stub）

```bash
cd ai-collab-ai-worker
uv run python tests/e2e/run_readonly_e2e.py --mode stub \
  --output ../docs/acceptance-evidence/2026-11-13/p1-default-off/lane-c/raw/e2e-stub.json
```

结果：`pass=9, skip=0, fail=0`，`evidenceLevel=TESTED(STUB)`。原始 JSON 见 `raw/e2e-stub.json`。

覆盖场景：

1. `fixture-hash`：toolSchemaHash / catalogVersion 与冻结 fixture 一致；
2. `local-mcp-server`：本地 Server 初始化、4 工具、readOnly 与非 readOnly 探针；
3. `gateway-list-and-success`：只暴露 `mcp.local_readonly.echo_text`，结果清洗与注入 warning；
4. `gateway-truncated`：显式截断，预览不含 token；
5. `gateway-denied-non-readonly`：`TOOL_DISABLED`；
6. `gateway-schema-drift`：`SCHEMA_DRIFT`；
7. `gateway-not-executed`：`NOT_EXECUTED`；
8. `gateway-deadline-exceeded`：`DEADLINE_EXCEEDED`；
9. `gateway-outcome-unknown`：上游 `isError=true` 映射为 `OUTCOME_UNKNOWN`，未返回成功正文。

### 3.3 Java D4 定向测试

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp "-Dtest=ProviderPublishAdmissionTest,SharedAiProviderLifecycleTest,AdminAiProviderSecurityTest,AiCapabilityProbeResultTest,ProviderAdmissionPostgresIntegrationTest" test
```

结果：`Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`。原始输出见 `raw/java-targeted-tests.txt`。

明细：

- `ProviderPublishAdmissionTest`：12/12，覆盖正常 P50/P95、超时、失败计数、单次成功不足以准入、P95 超阈、配置版本过期、无准入记录、准入落库失败 fail-closed、编辑请求参数自动撤销共享（含已停用但状态仍为 PUBLISHED 的行）；
- `SharedAiProviderLifecycleTest`：5/5，既有发布/撤销生命周期不回归；
- `AdminAiProviderSecurityTest`：1/1，普通用户仍被 403 拦截；
- `AiCapabilityProbeResultTest`：8/8，既有探针分类/STALE 不回归；
- `ProviderAdmissionPostgresIntegrationTest`：1/1，Testcontainers 真实执行 V98，`PUBLISH_ADMISSION` 可 upsert/读取。

### 3.4 编译

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp -DskipTests compile
```

结果：`BUILD SUCCESS`（918 个主源文件）。

## 4. 冻结 Hash

本次未修改 `tests/fixtures/mcp/catalog-v1.json`，E2E 计算值与其 `expected` 一致：

```text
toolSchemaHash = sha256:6e0e51dc1473a487caf37f66d09b14a1027a037c9fb357b032d1c7513dc82054
catalogVersion = sha256:e38d33918b09368d0553ad8d6c936bdd3fff4d1bdea5e748b546ae85f10d1441
```

## 5. D4 实现口径

- 复用 `user_ai_provider_probe_result`，`probe_mode='PUBLISH_ADMISSION'`；V98 仅扩展 CHECK 约束，不新增表。
- 默认阈值：`samples=3`、`p95-max-ms=10000`、`max-timeouts=0`、`max-failures=0`，可由 `AI_PROVIDER_ADMISSION_*` 覆盖。
- 发布要求：存在当前 `capability_config_version` 的 PASS 摘要，P95/超时/失败均达标；否则拒绝。
- 已共享 Provider 修改请求参数（模型/端点/凭据/温度/输出上限）会推进版本并自动转 `REVOKED`，必须重新测试/发布。
- 测试响应保留原 `content` 等字段，新增 `admission`；未改前端。

说明：`raw/e2e-stub.json` 中的 `commit=540a029d...` 是运行基线；最终提交 SHA 在交接回复中给出。

## 6. 残余风险与未执行

- **真实 Java Gateway 未跑**：本分支没有 Lane A 代码；当前只证明 Python 契约 stub 与冻结 fixture 一致。中心 cherry-pick Lane A 后使用 `--mode real --gateway-url ... --gateway-token ...`（或 `--gateway-command`）重跑，才能升级证据等级。
- **不是容量结论**：3 个样本、单机、合成请求；未做并发、长稳压测或 Provider 真实计费调用。
- **无时间型准入过期**：配置版本不变时摘要持续有效；如需定期复测需另立规则。
- **未跑全量门禁**：本泳道只跑定向 Java/Python；`mvnw verify`、前端 lint/typecheck/test/build、CI 由中心统一执行。
- **JMeter / N5 UI**：本泳道未做；per-label 文档收口留待 Must-have 全绿后。
