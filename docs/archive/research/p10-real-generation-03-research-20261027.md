# P10-REAL-GENERATION-03 研究记录（research）

> 归档位置：`docs/archive/research/p10-real-generation-03-research-20261027.md`
> 基线：`dev@ff83477`（评分器 v6 已合入）

## 1. 基线核实结论（实测）

- `dev@ff834775a5faf235277b9df81c4cffef5a850eff` = `origin/dev`，工作区干净；`origin/main@9b4f7c5`；PR #18 OPEN+Draft、CI 全绿（run 40388649131 / 40388641057）。
- 生产 KNOWLEDGE_CHAT 路由实测复算：`p3u04_admin` → GEMINI `gemini-3.5-flash-lite`，路由 SHA-256 `df17eb97…3cbbc` 与 P10-02 记录逐字节一致（node 脚本按 Jackson LinkedHashMap 序列化语义复算）。
- AGENT purpose 在生产库无显式分配 → `UserAiProviderService.resolve` 回退默认 Provider = Kilo Gateway Free / `nvidia/nemotron-3.5-lightning:free`（`encrypted_api_key` 为 NULL 的匿名网关，`preset_code=KILO_GATEWAY_FREE`）。
- 基础设施：Docker（postgres/redis/minio healthy）、Ollama `qwen3-embedding:0.6b`（本轮手动启动 `D:\Ollama\ollama.exe serve`）、系统代理 127.0.0.1:7897 启用。

## 2. 生产链路结构研究

- QA：`KnowledgeQuestionApplicationService.ask` → `DocumentSearchService.search`(limit 8) → `KnowledgeContextBuilder.buildBalanced`(≤5 源, ≤8000 chars) → `RoutingChatModelGateway.complete`(KNOWLEDGE_CHAT) → `KnowledgeCitationValidator` → `knowledge_message`/`knowledge_citation`。
- Agent：`AgentRepository.createRun` → 生产 `AgentRuntimeJob`（`@Scheduled` 1s tick → `recovery.claim` → `AgentWorker.process`，测试侧 worker-delay 500ms）→ `AgentRuntimeCoordinator` → `RoutingAgentModelExecutor`（`AgentModelConfigurationStore.require` → AGENT purpose → 默认 Provider）→ `NativeToolCallingExecutor` → 生产 `ModelTurnGateway`（`RoutingModelTurnGateway`，两参 `turn(command, metadata)` 是被重写的入口——spy/stub 必须落在同一重载上，这是 attempt-1 的关键教训）→ `KiloGatewayModelExecution`（`isKilo` 按 `presetCode==KILO_GATEWAY_FREE` 判定）。
- 工具：`KnowledgeSearchAgentTool`(SAFE_READ, 候选池 20, 外部 limit 1–12)、`DocumentAgentTools`（list/outline/section）、写类工具全部走审批（ApprovalWrite 系）。
- 正文读取：`DocumentContentService.read` 对 legacy 索引（无 document_body）回退 `document_chunk`，因此 P10 索引方式无需正文表即可支撑 outline/section 工具。

## 3. 测量工具设计决策

- 独立批次门禁：`P10RealGeneration03Gate`（QA 17 预算）/ `P10RealAgent03Gate`（20 场景 × ≤12 模型调用），与 02 批次门禁并存，互不修改。
- Agent 侧选择**生产 Spring context**（`agent.enabled=true` + 生产 `AgentRuntimeJob` 自动调度 + 真实 `DocumentSearchService` 向量检索 + 真实 `KnowledgeContextBuilder` + 真实工具注册表），模型网关以 `@MockitoSpyBean ModelTurnGateway` 记账（真实模式 callRealMethod + 预算熔断；预检模式合成交互）。权限用真实 `DatabaseProjectAccessGuard` 生效路径（隔离库 OWNER 成员），不 mock 权限；审批服务保持真实 bean（写触发即审批路径，AG20 断言业务表零新增）。
- 预检（合成传输）先行：QA 17 次、Agent 40 次（20×2）合成出站，零外部调用，验证整链与记账。

## 4. 执行过程中的缺陷与修复（全部记账并归档）

1. **attempt-1（Agent）**：测试构造 Provider 缺 `presetCode=KILO_GATEWAY_FREE` → `isKilo=false` → 通用 OpenAI 适配器解密 null → 20 次 `AI_MODEL_CREDENTIAL_INVALID` 本地即时失败（未出网）。修复后归档失败账本。
2. **attempt-2**：AG01 真实成功后内嵌评分 NPE（AGENT 协议 `agentCitations` 传 null）。修复为从工具返回 `citations`（AgentCitation 成对结构）构造。
3. **attempt-3**：受检异常编译失败，0 调用。
4. **内嵌评分对接缺陷**：Agent 工具返回的摘录字段为 `quote`（非 `content`），`read_document_section` 返回 `data.items`（无 filename/documentId，需从 list_project_documents 建映射）——修正由 `P10ScorerReplay03Test` 离线回放完成（评分器规则 v6 未动，权威评分 `scorer-results.json`：34 条高严重度 0）。
5. **裁判脚本**：首轮 QA 来源文档未注入（expectedFacts 应从冻结题集读取）+ Agent 无编号协议未说明 → 判定全部作废归档；修复后重判。5xx 退避、幂等续跑、增量写盘随之补齐。
6. **外部配额**：`gemini-3.5-flash` 免费配额耗尽（429 quota exceeded）→ Judge-A 改 flash-lite；Kilo 匿名免费额度被 Agent 真实测量（53 调用）+ 早期裁判尝试耗尽（`PROMOTION_MODEL_LIMIT_REACHED`，per-account 全局）→ Judge-B 改本地 Ollama `qwen2.5:7b-instruct`（独立权重，能力局限如实标注）。

## 5. 数据发现（供后续专项）

- **检索层确定性**：P10-02 与 P10-03 同 8 题入选来源逐 rank 相同（向量检索 + 同语料 + 同 embedding 完全可复现）。
- **QA vs Agent 检索深度差异（机制性）**：同一"驳回后怎么处理"问题，Agent 工具（候选池 20）召回了 B2 驳回处理分块并完整回答（AG02），QA（buildBalanced top5）未召回而诚实拒答（Q09）。两路径上下文构建策略不同是回答差异的可证实机制原因之一。
- **Agent 回答风格**：倾向在正文直接引用 documentId/相似度等来源身份 → 评分器数字候选把 UUID 片段列为需人工项（编码助手未判定为编造）。QA 与 Agent 协议的呈现差异值得在评分器后续演进中考虑（如来源身份掩码），本轮不做（评分器冻结）。
- **评分器 verbatim 局限**：Markdown 结构（粗体+冒号）、语气词插入（"可以/将/调整为"）打断连续匹配——全部转人工后语义等价。与 P10-02 已知局限同源，v6 的保守转人工路径工作正常。
- **Kilo 免费模型输出截断**：3/20 场景 `AI_PROVIDER_OUTPUT_TRUNCATED`（拒答类长文场景），免费模型生成上限是 Agent 真实测量的现实约束。

## 6. 值得后续专项的项（登记，不在本轮修改）

1. QA 上下文选择（buildBalanced）对跨文档复合问题（AQ09 类）的召回不足——"退回发起节点"分块未进 top5；建议评估候选池/重排策略（生产算法改动需单独论证）。
2. 评分器对 Agent 来源身份呈现（UUID/文件名）与有序列表序号的掩码处理，减少保守转人工噪声。
3. Agent 长回答在 Kilo 免费模型的截断：生产 `max_output_tokens` 与模型能力匹配问题。
4. `P10ScorerRobustnessTest` 证据重写键序噪声（历史遗留）迁移至运行时目录。
