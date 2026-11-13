# F2 复现命令

```powershell
cd E:\project\ai-collab-wt\f2-decide-mainchain\ai-collab-backend
.\mvnw.cmd -B -ntp "-Dtest=DecideRouterTest,DecidePropertiesBindingTest,AgentDecideShadowServiceTest,AgentRuntimeCoordinatorTest,DecideGatewayClientTest" test
.\mvnw.cmd -B -ntp "-Dtest=AgentRuntimeCoordinatorTest#pythonDecideCallToolSkipsJavaModelAndEntersNormalToolExecutor" test
.\mvnw.cmd -B -ntp verify
```

真实 worker 双跑不在本 lane 执行；由中心 E1 在 `19100` worker 与本地流量下补测。
