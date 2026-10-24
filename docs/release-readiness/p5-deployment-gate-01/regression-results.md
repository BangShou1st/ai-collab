# P5-DEPLOYMENT-GATE-01 回归结果

> 日期：2026-10-24。本轮改动面：Compose YAML、`.env.example`、CI 一个步骤、
> 新增 shell 脚本与文档。**无 Java / TypeScript 代码变更**。

## 已执行

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| `docker compose --env-file .env.example -f ai-collab-deploy/docker-compose.yml config -q` | ✅ PASS | CI 同命令 |
| 配置契约回归（`compose-config-contract.sh`，4 场景） | ✅ PASS | 正向透传 / 默认值 / 遗留不注入 + 安全基线 / 特殊字符 URL |
| 隔离容器环境变量注入断言 | ✅ PASS | 真实容器 `printenv` 与合成输入一致；遗留变量不存在；已清理 |
| JSON 解析器校验（deployment-gates.json、risk-closure.json） | ✅ PASS | node JSON.parse |
| 遗留变量判定核对（`.env.example` 与源码映射一致） | ✅ PASS | 见 config-mapping.md 映射表 |

## 按影响面判定无需执行

| 检查 | 判定 | 理由 |
| --- | --- | --- |
| 后端 `mvnw verify` | 无需 | 未改任何 Java 代码、迁移或 Spring 配置文件 |
| 前端 lint/typecheck/test/build | 无需 | 未触碰前端 |
| Embedding/Planning 真实模型验收 | 无需 | 上轮已完成真实 Embedding 验收；本轮只验证配置透传链路 |

## 业务回归确认（静态核对）

- **登录安全配置**：JWT / Refresh Cookie / CORS 相关 Compose 条目未改动；
  契约回归持续断言 Cookie 安全默认值。✅
- **Provider 设置**：用户级 AI Provider 路由（`RoutingChatModelGateway` + `ModelPurpose.PLANNING`）
  未触碰，无环境变量绕过权限的路径。✅
- **Agent 模型配置**：`AGENT_ENABLED` / `AGENT_MCP_ALLOWED_HOSTS` / `MODEL_CONFIG_MASTER_KEY`
  条目原样保留。✅
- **Embedding 配置**：生产配置路径（管理端入库）不变；`EMBEDDING_LOCAL_ALLOWED_TARGETS`
  白名单行为不变（默认 `host.docker.internal:11434` 保持）。✅
- **Planning 用途解析**：`TaskPlanModelClient.providers.resolve(actor, ModelPurpose.PLANNING)`
  不变；仅新增调优参数透传。✅
- **无新增未授权写入 / 敏感信息泄露**：改动均为配置透传与文档；测试值全合成；
  未提交任何 `.env`、API Key、密码。✅

## CI

| Run | 触发 | 结论 | Job 明细 |
| --- | --- | --- | --- |
| `40109053755` | dev push（a19ae2d） | ✅ success | Backend ✅ / Frontend ✅ / Image 按既有条件 skip |
| `40109057039` | PR #18（dev→main，Draft） | ✅ success | Backend ✅ / Frontend ✅ / **Image build & container smoke ✅（含新增契约回归步骤）** |
