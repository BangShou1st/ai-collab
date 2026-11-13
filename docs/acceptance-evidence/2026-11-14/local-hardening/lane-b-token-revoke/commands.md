# 复现命令

工作目录：`E:\project\ai-collab-wt\p0-token-revoke\ai-collab-backend`

```powershell
# 主源码编译
.\mvnw.cmd -B -ntp -DskipTests compile

# 单元与定向服务测试（46 项）
.\mvnw.cmd -B -ntp "-Dtest=AccessTokenServiceTest,AuthServiceTest,PasswordResetServiceTest,RefreshTokenGeneratorTest,RefreshTokenServiceTest,UserSessionRevocationServiceTest,UserSessionStateServiceTest,AuthSessionGuardFilterTest,AccountServiceTest,AdminServiceAuthorizationTest" test

# 真实 HTTP + Testcontainers 集成（7 项）
.\mvnw.cmd -B -ntp "-Dtest=AccountLifecycleIntegrationTest,TokenRevocationIntegrationTest" test

# 默认值、迁移和 diff 复核
Select-String -Path ai-collab-backend\src\main\resources\application.yml -Pattern 'access-token-minutes:','RERANK_MODE:java','DECIDE_MODE:java','PLANNING_DRAFT_MODE:java','MEMORY_JOB_ENABLED:false','INTERNAL_AI_TOKEN_MODE:static','AGENT_MCP_GATEWAY_ENABLED:false'
Get-ChildItem ai-collab-backend\src\main\resources\db\migration -Filter '*.sql' | Sort-Object Name | Select-Object -Last 5
git diff --check
```

集成测试只使用 synthetic 凭据；未记录真实密码、JWT、Refresh Token 或 API Key。
