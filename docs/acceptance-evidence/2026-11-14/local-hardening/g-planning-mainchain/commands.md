# G 命令与退出码

工作目录：`E:\project\ai-collab-wt\g3-planning-mainchain\ai-collab-backend`

```powershell
# 1. 编译（exit=0）
.\mvnw.cmd -B -ntp -DskipTests compile

# 2. 定向 planning / router / gateway / shadow / orchestrator（59 tests，exit=0）
.\mvnw.cmd -B -ntp "-Dtest=PlanningDraftRouterTest,PlanningDraftGatewayClientTest,PlanningDraftPropertiesBindingTest,PlanningDraftShadowServiceTest,TaskPlanGenerationOrchestratorIntegrationTest,TaskPlanGenerationOrchestratorTest" test

# 3. 全量中失败的 4 个既有 Agent Postgres 类复跑（116 tests，exit=0）
.\mvnw.cmd -B -ntp "-Dtest=AgentCrashRecoveryTimingPostgresTest,AgentDelegationPostgresTest,AgentRemoteOutcomePostgresTest,AgentRepositoryIntegrationTest" test

# 4. 全量（第二轮，旧代码；3080 tests，1 failure + 5 errors，exit=1）
.\mvnw.cmd -B -ntp verify

# 5. 全量（第三轮，本 lane 最终代码）
.\mvnw.cmd -B -ntp verify
```

## 默认开关核对

```powershell
Select-String -Path ai-collab-backend/src/main/resources/application.yml `
  -Pattern 'RERANK_MODE|DECIDE_MODE|PLANNING_DRAFT_MODE|MEMORY_JOB_ENABLED|INTERNAL_AI_TOKEN_MODE|AI_WORKER_TOKEN_MODE|MQ_ENABLED|gateway.enabled'
```

期望：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、
`MQ_ENABLED=false`、`agent.mcp.gateway.enabled=false`。

## 原始退出码

| 命令 | exit | 说明 |
| --- | --- | --- |
| compile | 0 | `target/g-compile-final.exit.txt` |
| targeted 6 类 | 0 | `target/g-targeted-final4.exit.txt` |
| agent 4 类复跑 | 0 | `target/g-agent-flake-retest.exit.txt` |
| verify 第二轮 | 1 | `target/g-verify-round2.exit.txt`；失败与本 lane 无关 |
| verify 第三轮 | 1 | `target/g-verify-round3.exit.txt`；3090 tests，0 failures，3 errors（既有 Agent Postgres 时序），61 skipped |

## 资源与开关

- 后端端口：`18083`（本 lane 未启动长驻 Spring Boot；全量测试为 Testcontainers 临时端口）。
- 独立库：`ai_collab_lh_g`；未访问 `8080/5173/5432/9000`。
- 未启动/重启 Magpie，未改 provider，未使用 Gemini。
- 未新增迁移；V103 未占用。
