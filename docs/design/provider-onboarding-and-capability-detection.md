# Provider 接入自动化与能力发现设计（P7-PROVIDER-ONBOARDING-01）

> 日期：2026-10-25 · 状态：已实施 · 基线：`dev@4c98633`
> 任务书：P7-PROVIDER-ONBOARDING-01 · 交付报告见 `docs/archive/reports/p7-provider-onboarding-01-delivery-20261025.md`

## 1. 问题与根因

P7-BUSINESS-E2E-01 复现的用户痛点：模型实际支持原生 Tool Calling，但创建 Provider 时未手动勾选 `NATIVE_TOOLS`，系统即判定"模型不支持工具调用"。根因（I-3）：

```
Provider.capabilities（用户声明）
  → RoutingModelTurnGateway.turnWithConfig：未声明 NATIVE_TOOLS 即抛
      "Agent 模型必须支持 NATIVE_TOOLS"（提前拒绝，无真实请求）
  → RoutingChatModelGateway：未声明 STREAMING 同样短路
  → AiCapabilityProbeService.classify 把该消息启发式归为 NOT_SUPPORTED
  → UI 显示"不支持"
```

"尚未声明"被当成了"模型实际不支持"。次要问题：Base URL 带 `/v1` 与默认 API Path `/v1/...` 拼接成 `/v1/v1/models`（无组合检查）；模型发现必须先保存 Provider；表单把能力枚举直接暴露给普通用户。

## 2. 设计原则

1. **声明与验证彻底分离**：能力勾选只表示"允许尝试调用"；"可用"必须来自真实探针证据。
2. **最小可靠改动**：复用现有探针服务、套件面板与落库结论机制；不新建第二套测试系统。
3. **生产门控不动**：`ModelCapabilityPolicy` 全局语义不变；Agent 审批、UNKNOWN、工具配额、恢复逻辑不受影响。
4. **写回只增不减**：自动配置绝不移除用户已声明的能力，绝不自动改用途分配/默认模型。

## 3. 探针专用执行边界（任务 B）

新增 `CapabilityProbeContext`（`infrastructure/ai/model`，ThreadLocal，与 `AiConfigurationContext` 同一信任模型）：

- **仅**由 `AiCapabilityProbeService.test()` 在探针调用期间设置（try-with-resources 保证清理）；绝不来自模型输出、HTTP 请求或客户端参数。
- `activeFor(provider)` 按 id + userId 双重匹配，跨 Provider 不享受豁免。
- 两个网关在自定义协议分支按此豁免**能力声明门控**这一项：
  - `RoutingChatModelGateway`：跳过 `ModelCapabilityPolicy.require`（连接/流式/规划探针受益）；
  - `RoutingModelTurnGateway.turnWithConfig`：跳过 NATIVE_TOOLS 声明检查（工具探针受益）。
- 其余一切不变：enabled 检查、`requireSnapshotAuthorized`（凭据轮换/停用即拒）、出站端点安全（HTTPS/公网/防 SSRF）、结构化输出校验、Zen/Kilo 预设走服务端策略（不受影响，也无需豁免）。
- 探针固定合成工具 `capability_probe`，校验 tool name/arguments/协议格式，不执行任何真实业务工具。
- 生产 Agent/规划/问答路径永不处于该上下文，声明门控保持原样（`CapabilityProbeBoundaryTest` 覆盖三种边界：上下文内放行、上下文外拒绝、跨 Provider 拒绝）。

## 4. 结果分类与展示语义（B3）

落库状态保持四个（V66 迁移不变）：`PASS` / `FAIL` / `NOT_SUPPORTED` / `INCONCLUSIVE`；读取时动态派生：

| 展示态 | 来源 | 语义 |
| --- | --- | --- |
| 已验证可用 | PASS 且未过期 | 真实探针通过 |
| 未测试 | 无落库行 | 尚未发起 |
| 已声明（未验证） | 已声明但无探针证据 | 允许尝试 ≠ 已验证 |
| 已过期（配置已变更） | stale 判定 | 旧结论不描述当前配置 |
| 真实测试失败 | FAIL | 调用/契约明确失败 |
| 暂时无法判断 | INCONCLUSIVE | 429/5xx/超时/网络，不形成结论 |
| 已确认不支持 | NOT_SUPPORTED | 足够证据（预设服务端策略拒绝等） |
| 未启用（未声明） | 未声明且无证据 | 引导检测并配置，不再显示"不支持" |

"未勾选 NATIVE_TOOLS"从此不再被显示为"模型不支持 Tool Calling"。

## 5. 检测并配置与自动写回（任务 C/D）

`POST /api/v1/user/ai-providers/{id}/detect-and-configure`，后端串行编排：

1. 每 Provider JVM 内互斥锁（`tryLock`，双窗口/重复点击第二次直接拒绝）；前端另有防重复。
2. 绑定检测开始时的配置版本（`updated_at`）。
3. 串行探针：连接(1) → 流式(1) → 规划(≤4，按需) → 原生工具(1，按需)；`FAIL/NOT_SUPPORTED` 中止后续（不再消耗请求），`INCONCLUSIVE` 继续。预算恒定且前端确认框明示：全面检测最多 7 次生成请求，模型发现为只读请求另计。
4. **CAS 写回**（`UserAiProviderRepository.addCapabilitiesIfVersionMatches`）：
   `UPDATE ... WHERE id=? AND user_id=? AND updated_at=? RETURNING updated_at` —— 版本不匹配（检测期间改模型/Base URL/Key/能力声明或另一窗口并发修改）返回空，明确报错且不覆盖新配置。
5. 写回集合：连接 PASS→CHAT、流式 PASS→STREAMING、工具 PASS→NATIVE_TOOLS；**规划 PASS 不启用 STRUCTURED_OUTPUT**（生产规划走 `PROMPT_JSON`，不触发该门控，机械映射会造成假声明）。
6. 只增不减：合并用数组并集；待写回能力已全部存在时不执行 UPDATE（不无谓推进版本，同轮 INCONCLUSIVE 结论不被误标 STALE）。
7. 写回成功后 `rebaseProbeTestedVersion` 把本次运行落库结论的版本锚点迁移到新版本——刚通过的探针不会立即过期；同时 `updated_at` 推进让 `AgentModelConfigurationStore` 在下一轮重新快照 NATIVE_TOOLS 模式。
8. 预置连接（Zen/Kilo）不参与写回：能力由服务端策略持有，探针结论仅展示。

单模式 `test-capability` 端点保留原行为（不写回），供高级用户单独重测。

## 6. 地址与路径自动化（任务 A）

- `ProviderEndpointRules`（后端）/`endpoint-rules.ts`（前端镜像，同一判定口径）：识别"Base URL 末段 == API Path 首段且为版本段（`v<数字>[后缀]`）"的拼接重复；**不做任何通用路径去重**，Gemini `/v1beta`、Anthropic `/v1`、自定义网关特殊路径不受影响。
- 三层防线：前端表单保存前警告 + 两个方向的一键修正 + 端点预览（推理端点 + 模型发现端点，0 次请求）；后端 `validateEndpoint` 保存与未保存发现前硬拒绝（出站前即拦截，日志证据见验收报告）。
- `ProviderProfileCatalog` 增加 `code` 唯一键并新增 **魔搭 ModelScope** 档案（Base URL 与 Path 按 `/v1` 拆分预填，天然避免重复）；通用 OpenAI Compatible 自定义接入保留。
- 模型发现：新增 `POST /model-discovery`（未保存配置，输入 Key 即可发现；Key 只用于本次只读请求，不落库、不进日志）；发现失败 ≠ 连接失败 ≠ 模型不存在，始终可手输模型 ID（实测 ModelScope `/models` 免鉴权、Gemini 需有效 Key 均真实走通）。

## 7. 界面（任务 E）

表单普通路径收敛为：服务商 → API Key → 模型（可一键发现）→ 保存；Base URL / API Path / Temperature / Max Output Tokens / 能力声明 / 启用全部收进"高级设置"。保存后面板主操作为「检测并配置」：确认框列出请求预算，用途预设（知识问答 / AI 规划 / Agent / 全面检测）只预选深度测试项，不自动发起请求。能力验证状态表按第 4 节七种展示态区分，用途就绪度新增 `NOT_DECLARED`（未启用所需能力）与 `VERIFIED_NOT_ENABLED`（已验证未启用，写回被并发打断时的中间态）。

## 8. 并发与安全要点回顾

- 探针绑定版本 + CAS 写回：旧探针无法覆盖新模型/新 Key/新声明（T05）。
- 检测期间凭据轮换：`requireSnapshotAuthorized` 在每次出站前校验，轮换即 FAIL 且结论锚定旧版本（自动 STALE）。
- 锁为 JVM 内实现：当前部署为单体应用（docker-compose 单 app），跨实例部署需演进为 DB/Redis 锁，已在交付报告"残余风险"登记。
- 密钥零暴露：前端预览不含 Key；发现请求 Key 仅入站一次；日志与证据均无凭据。
