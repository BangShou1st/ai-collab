# P4-MODEL-QUICK-CONNECT-02 代码清理记录

每项删除均已 grep 验证调用方（生产 + 测试），原则：**确定无调用方才删；承载安全契约断言的访问器保留；测试脚手架构造器保留**。

## 后端删除

| 删除项 | 位置 | 验证依据（删除前 grep） |
| --- | --- | --- |
| `completeStreamingSync` | OpenAiCompatibleModelAdapter（原 :86-123） | 生产 0、测试 0 调用（被使用的 `completeStreamingSyncWithSession` 是另一个方法，保留） |
| `turnStreamingSync` | 同上（原 :277-357） | 生产 0、测试 0 调用（`turnStreamingSyncWithSession` 另有使用，保留） |
| `parseTurnResponse` | 同上（原 :797-814） | 生产 0、测试 0 调用 |
| `isZenConfig` | ZenModelExecution（原 :57-59） | 生产 0、测试 0 调用（`isZen(UserAiProvider)` 是生产方法，保留） |
| `ProviderPresetRegistry.all()` | 原 :120 | 生产 0、测试 0 调用 |
| `ProviderPresetRegistry.zenBaseUrl()` / `approvedBaseUrls()` | 原 :104/:108 | 仅 ZenUpstreamRoutingTest 引用；断言迁移到等价公开 API `require(...).baseUrl()` 后删除 |
| `ModelAssignmentRequest` DTO | model/api/ | 生产 0、测试 0 调用（Controller 用自己的 `AssignModelRequest`） |
| `AiCapabilityProbeService` 4 参构造器 | 原 :45-47 | 生产 0、测试 0 调用（Spring 用 @Autowired 6 参构造器） |
| **Zen 预设 `/test` 端点** | UserAiProviderPresetController + `PresetService.test` + `OpenCodeZenTransport.validateCredential` + `HttpOpenCodeZenTransport.validateCredential`/`ProbeClient` | 语义被统一探针 `POST /user/ai-providers/{id}/test-capability`（真实生成，更强）覆盖；`validateCredential` 唯一生产调用方是被删的 `PresetService.test`；同步删除 ModelProtocolHttpTest 中对应测试与 TestZenRequest DTO |
| **Kilo 预设 `/test` 端点** | 同上（Kilo 侧）+ `KiloGatewayTransport.validateModel` + `KiloGatewayClientTransport.validateModel` | 语义（目录可达）与 models 端点重复，且不构成真实生成验收；同步删除 KiloGatewayPresetServiceTest / KiloGatewayModelTest 对应测试与 TestKiloRequest DTO |
| **ProjectModelController（整文件）** | model/api/ | 前端唯一消费者是本轮删除的不可达页面 ProjectModelSettings（路由无任何导航入口，grep 验证）；`@Deprecated` 注释自称"仅保留只读兼容"但实际暴露全部写端点，与事实不符 |
| **ProjectModelConfigurationService（整文件）** | model/ | 唯一生产调用方是被删的 ProjectModelController |
| **ModelConfigurationRepository（整文件）** | model/ | 唯一生产调用方是被删的 Controller/Service 与 RoutingModelTurnGateway 的死分支（见下）；ProjectModelScopingTest 同步删除 |
| **RoutingModelTurnGateway 的 configurationId/projectId 路由分支** | 原 :91-103 | 生产仅有的两个 `turn()` 调用方（NativeToolCallingExecutor、AiCapabilityProbeService）都携带 `callerUserId`，两个 legacy 分支不可达；删除后 `callerUserId == null` 直接抛 AI_PROVIDER_UNAVAILABLE。`turnWithConfig`（含 NATIVE_TOOLS 生产门控）保留 |

**保留但验证过的项**（有明确存在理由）：`ZenModelExecution.runtimeEndpoint()` / `zenHttp()`（ZenStarterWiringTest/ZenModelWiringTest 用它们断言"Zen 端点合法 + HTTP 走 Direct 无代理"的安全契约）；两个网关的便捷构造器（6 处测试脚手架）；`ModelTurnCommand` 的兼容构造器（46 处测试调用）。

## 前端删除

| 删除项 | 位置 | 验证依据 |
| --- | --- | --- |
| `knowledgeApi.ask` + `KnowledgeAnswer` 类型 | knowledge-api.ts / types.ts | 全仓库 0 调用（流式路径早已取代同步问答） |
| `StreamCallbacks.onError` 回调 | knowledge-api.ts | 错误改为一律 throw `KnowledgeStreamError`——历史缺陷根源是"onError 后正常 return，成功续流程照跑" |
| `StreamingMessage.role` / `.streaming` 字段 | use-knowledge-view.ts | 模板写死标签，字段从不读取（grep 验证） |
| composable 导出 `submitFeedback` | use-knowledge-view.ts return | 无外部消费者（内部函数保留） |
| `PresetStatus.displayName` / `.hasApiKey` 类型字段 | ai-preset-api.ts | UI 从不读取；后端字段继续返回（响应兼容） |
| `zenEnabled` / `kiloEnabled` 假状态 | UserAiSettingsView.vue（原 :31/:37/:177-180/:240/:280） | 无任何 UI 开关，永远为 true 的读-改-写死状态 |
| **ProjectModelSettings 页面 + 路由** | ProjectModelSettings.vue、router.ts | 路由无任何导航入口（grep 全仓库仅 router 与组件自身）；被"个人 AI 设置 + 系统 Embedding + 集成与 MCP 页"体系取代 |
| **use-project-model-settings.ts** | project/ | 唯一消费者是被删页面 |
| **projectModelApi 的模型 CRUD / 用途分配 / 项目级 Embedding 端点** | project-model-api.ts | 仅被被删页面/composable 使用；MCP 部分被 ProjectIntegrationsView 使用，保留；项目级 embedding-config 端点无其余消费者 |

## 重整（非删除的收敛）

- Zen/Kilo 四对镜像函数（loadModels/save/test/disconnect）合并为 `usePresetConnection` composable，差异（code、文案、是否支持 Key）以配置表达。
- 初始 form 与 resetForm 双份硬编码合并为 `resetForm` 单一来源（ProviderFormDialog）。
- `probeOutcomes` 会话内存态被"后端落库 + STALE 判定 + probe-results 查询"取代，消除前后端两套结果状态源。
- 端点默认值从前端两份漂移表（user 级无表 / project 级有表）收敛为后端 `ProviderProfileCatalog` 单一事实源 + 前端降级快照。

## 回归证据

- 删除后后端 `mvnw compile`/`test-compile` 通过；全量 `verify` 结果见 regression-results.json。
- 前端 lint 0 错误 / typecheck / 393 tests / build 全绿。
