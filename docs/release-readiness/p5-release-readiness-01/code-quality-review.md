# code-quality-review.md — 代码架构与可维护性审计

> 原则：不为审计而重构；不因类行数多判必须拆分；不把风格问题列 P0；不提无实际收益的大规模抽象。基线 15f4905。

## 1. 分层合规 — 总体合规，1 处违例

- **Controller 注入 Mapper：0 处**（29 个 Controller）。唯一违例：`infrastructure/ai/embedding/ProjectEmbeddingController.java:32` 直连 ProjectEmbeddingConfigRepository 且位于 infrastructure 层（R-21，P3）——有 accessGuard.requireAdmin 兜底，无安全漏洞，属结构债；修复为抽 Application Service，不阻断发布。
- **domain 依赖红线未破**：agent/document/knowledge/planning/project/work 六个 domain 包 grep infrastructure/Mapper 零命中。
- 响应统一：所有 Map.of 均包在 ApiResponse.success 内。两处 Controller 手写 catch（ProjectEmbeddingController.java:70、AuthController.java:137/164）均翻译为 BusinessException 再抛出，未绕过统一出口（P3 建议下沉 Service）。

## 2. 超大类与循环依赖 — 无实质风险

| 类 | 行数 | 评估 |
| --- | --- | --- |
| AgentRunEventRecorder | 1857 | 纯 SQL 事件落库，耦合低（2 依赖），不为行数拆分 |
| AgentRuntimeCoordinator | 1401 | 编排器角色，14 依赖均合理；长期可拆状态机（P3） |
| TaskPlanGenerationOrchestrator | 1350 | 两阶段生成编排，职责内聚 |
| AgentContextSummarizer | 1091 | 内聚 |
| AgentRepository | 1022 | 数据访问聚合 |

循环依赖：无 @Lazy 解环；唯一 ObjectProvider（KiloGatewayClientTransport.java:23）是客户端懒加载非断环。应用可启动本身证明无环。**不建议现在拆分。**

## 3. 双份写权限判定（R-15，P3）

- 定位：agent/domain/policy/AgentToolPolicy.java（26 行）vs agent/infrastructure/tool/AgentToolRegistry.java:177-184。
- 事实：**AgentToolPolicy 在 main 源码中零引用**，仅 AgentToolRegistryTest 作断言基准；生产唯一执行路径是 Registry（更严：ControlledWriteAgentTool 额外要求 OWNER/ADMIN + !scheduled）。两者 WRITE_PROPOSAL_ROLES 常量一致（OWNER/ADMIN/MEMBER/SUPERVISOR）。
- 风险仅在「未来误把 Policy 接入生产」会放宽角色限制。修复：Policy 类头加注释声明仅测试对照物，或 Registry 复用 Policy 常量。小时级成本。

## 4. 死代码 / 影子数据

- 前端 agentApi.resume（agent-api.ts:53-55）：生产零调用，测试显式断言不调用（R-16，P3）。
- agent_run/agent_step.model_name/model_provider：只写不读（R-14，P3，详见 database review）。
- 其他明显不可达代码：本轮抽查未发现新实例（p4-model-quick-connect-02 已清理一轮 Zen/Kilo 旧端点与 ProjectModel 遗留体系）。

## 5. 权限判定一致性 — 抽查通过

29 个 Controller 中 27 个不直接持 Guard（权限在 Application Service，符合约定）；两处直接持 Guard（AgentSkillController 多层防御可接受、ProjectEmbeddingController 位置不对但等效安全）。**未发现绕过 guard 直查数据的项目级接口**（抽查级结论，未逐接口穷举）。

## 6. 数据事实来源一致性

- 模型身份：有效来源唯一（model_configuration_snapshot + agent_step.MODEL_TURN + 事件）；agent_run 影子列是唯一漂移点（R-14）。
- 项目角色：唯一来源 project_member.role；agent_run.role 是「运行角色」概念（V22，CHECK 区分），同名不同义，非冗余。**无真实双源问题。**

## 7. 前端架构 — 边界清晰

- 14+ 模块间零跨模块 import；共享件收敛于 shared/ 与 stores/。
- 唯一注意点：api/http-client.ts（axios，23 处）与 api/authenticated-fetch.ts（fetch+SSE，5 处）是两份并行 token 刷新实现（R-25，P3）——当前均复用 auth-store.refresh() 逻辑同步，建议文件头互标引用关系。

## 8. 汇总

| # | 问题 | 定位 | 级别 | 阻断发布 |
| --- | --- | --- | --- | --- |
| 1 | Controller 直连 Repository | ProjectEmbeddingController.java:32 | P3 | 否 |
| 2 | 双份写权限判定 | AgentToolPolicy vs AgentToolRegistry:177 | P3 | 否 |
| 3 | agentApi.resume 死代码 | agent-api.ts:53 | P3 | 否 |
| 4 | 模型名影子列只写不读 | AgentRunEventRecorder.java:888/928/1116 | P3 | 否 |
| 5 | 双 HTTP 封装刷新逻辑并行 | http-client.ts / authenticated-fetch.ts | P3 | 否 |
| 6 | Controller 内异常翻译未下沉 | ProjectEmbeddingController.java:70 等 | P3 | 否 |

**真实风险：无。** 全部 6 项为可延期的维护性改进，处理成本均为小时级，建议列入发布后第一批技术债任务。未发现安全逻辑绕行、不可达的暴露 API 或不一致的数据事实来源（除已记录的影子列）。
