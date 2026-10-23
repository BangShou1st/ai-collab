# P4-MODEL-QUICK-CONNECT-02 架构审计

任务起始基线：`71939d015bb23d9c78693d5f9b8173af5d05f42c`（dev = origin/dev，工作区干净；main 全程未触碰）。

## 1. 现状：接入流程盘点

共 **3 条接入流程 + 1 套用途分配层**，API 形态三种、测试语义四种：

| 流程 | API | Service | 测试语义 |
| --- | --- | --- | --- |
| 自定义 Provider | `/api/v1/user/ai-providers` CRUD + `/test` + `/test-capability?mode=` | `UserAiProviderService` | `/test`=同步 chat；`/test-capability`=4 种真实探针 |
| Zen 预设 | `/api/v1/user/ai-provider-presets/OPENCODE_ZEN_FREE*` | `UserAiProviderPresetService` | `/test`=`transport.validateCredential`（真实流式推理探测） |
| Kilo 预设 | `/api/v1/user/ai-provider-presets/KILO_GATEWAY_FREE*` | `KiloGatewayPresetService` | `/test`=仅模型目录可达（无真实生成） |

关键事实（已逐一核实源码）：

- `AiCapabilityProbeService`（Mode=CONNECTIVITY/PLANNING_JSON/STREAMING/NATIVE_TOOLS）通过 `AiConfigurationContext`（ThreadLocal）钉住 provider，再经 `RoutingChatModelGateway` 的 zen/kilo/自定义三分支路由——**探针天然支持预设行**，只是预设控制器未暴露入口、前端未接线。
- 探针结果**不落库**：`Evidence` 仅随响应返回，前端仅存会话内存（`probeOutcomes`），无 STALE/失效机制。
- `validateEndpoint`（`UserAiProviderService.java:171`）用 `URI.create(apiPath)`，URI 路径文法不允许 `{}`，`/v1beta/models/{model}:generateContent` 被拒；而 `GeminiModelAdapter.modelEndpoint`（:361）恰恰依赖 `{model}` 占位符——校验层与适配层互相矛盾。
- 前端 `UserAiSettingsView.vue`（690 行单文件）providerType 切换无 watch/@change，选 GEMINI 仍保留 OpenAI 端点；capabilities 固定提交 `['CHAT']`，无勾选入口；`ModelCapabilityPolicy` 要求流式问答必须声明 STREAMING → UI 创建的配置无法流式。
- 自定义 Provider 无模型发现 API；Zen/Kilo 有 models 端点但 select 不允许手输。
- `knowledge-api.ts askStream` 在 HTTP 错误时 `onError` 后正常 return（不 throw），`use-knowledge-view.ts submitQuestion` 的成功续流程照跑；消息区无错误渲染分支 → "提交后只看到空白页面/标题更新"。

## 2. 必须保留的安全边界（不可抹平的差异）

- Zen：上游白名单 `APPROVED_ZEN_BASES` + 共享库协议常量；免费模型准入（白名单 ∩ 上游实时列表）；按凭据 SHA-256 分桶的发现缓存；禁止用户覆写 preset 上游。
- Kilo：无凭据匿名语义；免费模型目录准入（`FreeModelPolicy` ∩ 目录）；共享库传输；停用预设拒绝出站。
- 自定义：`validateEndpoint` + `OutboundEndpointPolicy`（HTTPS/公网/DNS 解析防 SSRF）保存时与出站时双重校验；密钥加密落库、接口不回明文。
- `ModelCapabilityPolicy` 生产门控：流式需 STREAMING、JSON_OBJECT 需 STRUCTURED_OUTPUT、turn 需 NATIVE_TOOLS。
- 预设行不可走自定义通道改/删（`rejectPreset`）。

## 3. 重复与死代码（详见 code-cleanup.md，实施时逐项 grep 复核）

前端：Zen/Kilo 四对镜像函数（loadModels/save/test/disconnect）；初始 form 与 resetForm 双份硬编码；三套 provider 客户端样板。
后端：`requireAdmittedModel` 两份镜像；模型缓存两份；URL 拼接两份；adapter map 装配三份；`OpenAiCompatibleModelAdapter` 消息序列化双份 + 已死的同步流式等待路径。
死代码候选（生产+测试 0 调用已验证）：`parseTurnResponse`/`turnStreamingSync`/`completeStreamingSync`、`ProviderPresetRegistry.all()`、`ZenModelExecution.isZenConfig/runtimeEndpoint/zenHttp`、`ModelAssignmentRequest`、探针 4 参构造器等；前端 `knowledgeApi.ask`、`zenEnabled/kiloEnabled` 假状态、`ProbeOutcome.detail/.at` 写而不读（本轮改为真正渲染）等。

## 4. 重整方案（最小合理改动）

原则：**统一用户操作与测试结果，不强制统一凭据与传输协议**。复用 `AiCapabilityProbeService` 与现有三分支路由，不新建并行测试体系。

### 后端

1. **`ProviderProfileCatalog`（新增）**：三种自定义协议的接入档案（默认 baseUrl/apiPath、是否必填 Key、能力候选、是否支持模型发现、apiPath 说明），经 `GET /api/v1/user/ai-providers/profiles` 暴露——端点预设的单一事实源，消除前端两份漂移的默认表。
2. **`validateEndpoint` 放行 `{model}` 占位符**：仅允许精确 `{model}` 且至多一次，先替换为 URI 合法 token 再 `URI.create`；其余花括号组合明确拒绝。错误信息按 baseUrl/apiPath/出站策略三类细化。**不放松任何出站安全校验**。
3. **自定义 Provider 模型发现**：`GET /{id}/models?refresh=`，OPENAI_COMPATIBLE 由 apiPath 推导（`/chat/completions` → `/models`）、ANTHROPIC `/v1/messages` → `/v1/models`、GEMINI `GET /v1beta/models`（`x-goog-api-key` 头，解析 `models[].name`）。内存缓存 5 分钟；失败返回分类错误，前端降级手输。
4. **探针结果落库（V66 新表 `user_ai_provider_probe_result`）**：每 (provider, mode) 一行，status=PASS/FAIL/NOT_SUPPORTED/INCONCLUSIVE；`tested_config_updated_at` 记录测试时配置版本，读取时与 `user_ai_provider.updated_at` 不一致 → 动态判 STALE（不落库）。`GET /{id}/probe-results` 返回各模式最新结果。INCONCLUSIVE 用于网络/超时/429/5xx 等临时故障；能力未声明（policy 拒绝）→ NOT_SUPPORTED；契约不满足/鉴权失败 → FAIL。
5. **预设行探针打通**：不新增预设控制器端点——预设保存后即为 `user_ai_provider` 行，前端直接复用统一端点 `POST /user/ai-providers/{id}/test-capability`（`AiCapabilityProbeService` 不 rejectPreset，预设行经 zen/kilo 分支真实出站）。Zen/Kilo 与自定义获得**完全相同**的能力测试体验。
6. **错误分类补 404**：`JsonHttpModelClient.checkStatus` 增加 `AI_PROVIDER_NOT_FOUND`（模型或路径不存在），404 不再泛化为 AI_PROVIDER_ERROR。
7. **Zen/Kilo 旧 `/test` 端点下线**：语义分别被 CONNECTIVITY 探针（真实生成，更强）与 models 端点（目录可达）覆盖；删除控制器端点、service 方法与前端调用（保留逻辑详见 code-cleanup.md）。

### 前端

1. **表单重整**：Base URL 提升为普通字段、API Path 留在高级设置；providerType 切换时按 profile 填默认端点，但**不覆盖用户已编辑的值**（与旧 profile 默认值比对）；capabilities 勾选（候选来自 profile，默认 CHAT+STREAMING）；模型 ID 输入框 + "获取模型列表"（已保存配置）→ select 可手输。
2. **统一"测试连接与能力"套件**（`use-capability-suite.ts` + 面板组件）：静态检查（本地）→ 模型发现/准入 → 连接(CHAT) → 流式 → 规划(按需) → 原生工具(按需) → 用途就绪聚合。串行执行、逐步展示、单步重试、配置变更后结果标 STALE。三个入口（自定义/Zen/Kilo）同一编排。
3. **用途就绪聚合**：知识问答=STREAMING 探针 PASS；规划=PLANNING_JSON PASS；Agent=NATIVE_TOOLS PASS（Agent 全链路走 turn 网关）。显示 可用/不可用/未验证/不支持，CONNECTIVITY 通过不外推。
4. **SSE 错误可见**：`askStream` 错误一律 throw（去掉吞错误 return）；`submitQuestion` catch 后在消息区渲染错误气泡（含分类文案），不再只更新标题。
5. **Zen/Kilo 镜像逻辑合并**为单一 composable；死代码清理见 code-cleanup.md。

## 5. 兼容性风险与对策

| 风险 | 对策 |
| --- | --- |
| 旧配置（capabilities 仅 CHAT、apiPath 硬编码模型名）加载退化 | 只加不改：旧配置照常加载；硬编码 path 继续可用（适配器 replace 找不到 `{model}` 时原样使用） |
| `{model}` 放行扩大攻击面 | 占位符仅允许精确 `{model}`，替换后仍过完整 URI + 出站策略校验 |
| 探针落库引入新表 | V66 新迁移，不改历史迁移；无外部数据依赖 |
| 删除 Zen/Kilo `/test` 端点破坏兼容 | 该 API 唯一消费者是本前端（内部 API），前端同步改造；Testcontainers 契约测试同步迁移 |
| 前端表单默认值变化覆盖用户数据 | 仅新建/切换 providerType 时应用默认值，编辑已有配置严格回填存量值 |
