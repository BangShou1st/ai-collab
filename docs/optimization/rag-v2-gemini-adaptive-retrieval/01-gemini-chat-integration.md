# 01｜Gemini Chat 接入优化与真实冒烟验证

> 起始 SHA：`40c0bde4ea25556187f4f368b03ef7f8205702cf`（origin/dev，CI success run 42371367155）
> 日期：2026-11-07 ｜ 阶段裁决：**PASS**

## 1. 修改原因（诊断驱动）

1. **thinkingConfig 缺陷（真实验测发现）**：`GeminiModelAdapter` 的 generationConfig 只发送 temperature/maxOutputTokens（GeminiModelAdapter.java 原 :194-196/:293-295）。实测请求 `gemini-3.5-flash` 时服务返回 `modelVersion=gemini-3.6-flash`（思考型模型），思考链消耗 242 thoughtsTokenCount，将 256 的 maxOutputTokens 预算全部吃掉 → `finishReason=MAX_TOKENS`，适配器按契约抛 `AI_PROVIDER_OUTPUT_TRUNCATED`，内容被截断。生产 KNOWLEDGE_CHAT 配置 maxOutputTokens=1024/2048 同样有被挤占风险，且思考破坏 temperature=0 确定性契约。
2. **错误语义缺陷（真实验测发现）**：Google 对无效 API Key 返回 **HTTP 400**（`API key not valid`），而非 401；`JsonHttpModelClient.checkStatus` 原逻辑将其归入泛化 `AI_PROVIDER_ERROR`，运维无法凭错误码识别凭据问题（401 才映射 `AI_MODEL_CREDENTIAL_INVALID`）。
3. 任务要求将 gemini-3.5-flash 纳入本地优先候选前，必须先真实验证 8 类场景与错误分类。

## 2. 修改内容（最小修改，未重写框架）

| 文件 | 修改 |
| --- | --- |
| `ModelCapability.java` | 新增 `THINKING` 能力位（capabilities 为 CSV 存储，无需迁移） |
| `GeminiModelAdapter.java` | ① 未声明 THINKING → generationConfig 显式携带 `thinkingConfig.thinkingBudget=0`；声明 → 不发送（保持模型默认）。② `postWithThinkingFallback`：旧模型 400 拒绝 thinkingConfig 时去除该字段有界重试一次（仅 400 触发）。complete/turn 双链路生效 |
| `JsonHttpModelClient.java` | checkStatus 识别 400 + `API key not valid` → `AI_MODEL_CREDENTIAL_INVALID` |
| `GeminiThinkingPolicyContractTest.java`（新增） | 5 个契约测试：budget=0 发送/省略（chat+turn）、400 回退重试、非 400 不重试 |
| `Gemini35FlashLiveSmokeTest.java`（新增） | 8 场景真实冒烟套件（门控 `AI_GEMINI_LIVE_TESTS=true` + `GEMINI_API_KEY` + 显式 `-Dtest`），含 15s 场景节流与 429 退避（30s×2，实测免费层 flash 系 RPM 很低） |
| `GeminiLiveTestSupport.java` | live 门控白名单放行新冒烟类 |

不删除任何现有 Provider；Ollama/端砚（OpenAI 兼容）/ModelScope/Anthropic 支持原样保留。

## 3. 真实测量（非预估）

### 3.1 冒烟结果（gemini-3.5-flash-lite，9/9 通过，0 失败，7 次成功请求 + 2 次错误分类注入）

| 场景 | 结果 | 关键实测 |
| --- | --- | --- |
| 1 中文知识问答 | PASS | 2032ms（前序轮次 7.6s 首请求）；**事实性注记**：lite 在一次运行答"13天"（错误）、一次答"11天"（正确），free 层 temperature=0 亦非严格确定——已在 metrics 如实记录 |
| 2 中英混合 | PASS | 1431ms，回答含"事务" |
| 3 SSE 流式 | PASS | **TTFT 882ms**，总 1125ms，61 completion tokens |
| 4 JSON 结构化 | PASS | 1211-2573ms，answer/confidence 字段符合 schema |
| 5 单轮 Function Calling | PASS | 正确发起 search_project_tasks 调用 |
| 6 多轮工具调用 | PASS | functionResponse 回传后正确汇总（含 toolCall 回放） |
| 7 无证据拒答 | PASS | 按契约拒答 |
| 8a MAX_TOKENS 分类 | PASS | 8-token 预算触发 → `AI_PROVIDER_OUTPUT_TRUNCATED` |
| 8b 无效 Key 分类 | PASS | 400 API_KEY_INVALID → `AI_MODEL_CREDENTIAL_INVALID`（修复后） |

原始逐请求指标（含 tokens/s、prompt/completion tokens）归档：`evidence/01-gemini35-smoke-metrics.json`。

### 3.2 模型版本与配额实测

- 请求 `gemini-3.5-flash` → 服务实际返回 `modelVersion=gemini-3.6-flash`；请求 `gemini-3.5-flash-lite` → 返回 `gemini-3.5-flash-lite`（版本透传正确）。
- **配额实测**：flash（思考型）免费层 RPM 极低，连续 7-8 请求即触发 429（`AI_PROVIDER_QUOTA_EXCEEDED` 映射正确）；flash-lite 相对宽松。429 无法稳定复现打爆，以真实触发记录为准。
- **兼容性行为实测**：`gemini-3.5-flash-lite` 拒绝 thinkingConfig（400 INVALID_ARGUMENT，非思考模型）；`gemini-3.5/3.6-flash` 接受 thinkingBudget=0。能力位设计恰好覆盖两类模型。
- Key 状态：有效（AQ. 新式 Key，`x-goog-api-key` 头，经系统代理 127.0.0.1:7897 路由，项目 `EnvironmentProxySelector` 原生支持，零代码改动）。

### 3.3 工程测试

- `mvnw test -Dtest='GeminiThinkingPolicyContractTest,GeminiTurnContractTest,GeminiStreamContractTest'`：**42 tests, 0 failures**（26 turn 契约 + 11 stream 契约 + 5 thinking 策略）。
- live 冒烟：**9 tests, 0 failures, 0 skipped**。

## 4. 质量基线

- P9/P10 冻结资产未触碰；生产检索代码未改动；P10 拒答纪律经场景 7 真实验证。
- 模型默认策略：Gemini 适配能力齐备且经真实验证；**本地环境优先候选的运行时切换属运维操作**（ProviderProfileCatalog GEMINI 档位 `https://generativelanguage.googleapis.com` + `/v1beta/models/{model}:generateContent` 已就绪，经管理端配置 Key 即可启用；建议 THINKING 能力位按模型类型勾选）。本轮不改任何正式站路由。

## 5. 已知问题与风险

1. 免费层 flash 系 RPM 限制严格（实测连续请求即 429），生产化需绑定付费层或以 flash-lite 为默认——进入阶段 2/3 的模型选择时以此为准。
2. lite 模型存在事实性波动（temperature=0 下仍观察到答案不一致），QA 事实质量依赖 RAG 证据约束，恰与阶段 3 的证据路由方向一致。
3. 流式链路无 thinkingConfig 400 回退（流式重试语义复杂，未实现）；非思考模型配置正确能力位即可规避，已记录。

**提交 SHA：见 git log；推送后 CI 状态见 final-acceptance-report.md。**
