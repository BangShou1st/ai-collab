# Wave 0 基线快照

## 仓库

- 分支：`dev`
- 基线提交：`8436ebebe25e0c4d649339f28e82e0dba326eabf`
- 最新历史迁移：`V99__agent_mcp_provider_admission.sql`
- 主 worktree：`E:\project\ai-collab`

## 默认开关事实

| 开关 | 仓库默认 | 当前 `.env` | 结论 |
| --- | --- | --- | --- |
| `RERANK_MODE` | `java` | 未覆盖 | `java` |
| `DECIDE_MODE` | `java` | 未覆盖 | `java` |
| `PLANNING_DRAFT_MODE` | `java` | 未覆盖 | `java` |
| `MEMORY_JOB_ENABLED` | `false` | 未覆盖 | `false` |
| `INTERNAL_AI_TOKEN_MODE` | `static` | 未覆盖 | `static` |
| `AI_WORKER_TOKEN_MODE` | `static` | 未覆盖 | `static` |
| `AGENT_MCP_GATEWAY_ENABLED` | `false` | 未覆盖 | `false` |
| `MQ_ENABLED` | `false` | 未覆盖 | `false` |

`RERANK_MODE` / `DECIDE_MODE` / `PLANNING_DRAFT_MODE` / `MEMORY_JOB_ENABLED` /
`INTERNAL_AI_TOKEN_MODE` 均从 `application.yml` 的缺省值核验；`AI_WORKER_TOKEN_MODE`
按 Compose 契约核验为 `static` 缺省。详细原始输出见同目录 `defaults.txt`。

## 运行时事实

- 本机 PostgreSQL：`ai_collab`，宿主机端口 `5433`。
- 本机 Redis：宿主机端口 `6380`。
- 本机 MinIO：宿主机端口 `9002`，控制台 `9003`。
- 当前占用但不得抢占：`8080`（Java 进程）、`5173`（Node/Vite）、`3425`（Magpie）。
- `ai-worker` 当前不在运行中的 Compose 拓扑内。
- 问答主链代码固定构造 `ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY`。

## 敏感信息

未记录任何凭据明文。`.env` 中凭据只登记“configured”状态与 SHA-256 指纹，见
`secret-fingerprints.txt`；指纹仅用于确认同一配置，不可还原凭据。
