# AI 模型配置与多能力 Provider 设计（2026-11-12）

- 日期：2026-11-12
- 状态：设计提案，待评审；本文不包含实现
- 范围：CHAT / EMBEDDING / RERANK 的统一模型配置、管理员生命周期、运行时解析与回退
- 关联：
  - [Python AI 能力平面](python-ai-capability-plane-2026-11-11.md)
  - [Rerank 延迟预算与可观测设计](rerank-latency-budget-2026-11-12.md)
  - [管理员工作区与共享 AI 供应商架构](admin-workspace-and-shared-ai-architecture.md)
  - [Provider 接入自动化与能力发现](provider-onboarding-and-capability-detection.md)
  - [W3.5 收口报告](../archive/report/w3-5-blockers-20261112.md)
  - [本地 Reranker Spike readiness](../archive/research/local-reranker-spike-2026-11-12.md)

## 0. 阅读口径

本文回答一个设计问题：

> **Reranker 和 Embedding 的模型配置能不能沿用同一套管理员配置思路？**

答案是：**可以共用同一套生命周期和配置框架，但必须使用不同的 capability schema。**

本文只定义设计，不修改代码、不新增迁移、不改变默认开关，也不设计“中心 agent 编排器”。
所有实现必须另开执行会话，并以本文为评审基线。

## 1. 背景与现状

### 1.1 当前已有的模型配置基础

项目已经有三层模型配置事实：

| 层 | 现有作用 |
| --- | --- |
| Provider | Provider 类型、Base URL、API Path、加密密钥、共享/个人归属 |
| 模型档案 | `ai_model_profile`：模型/端点级的上下文窗口、输出上限、usage 能力、来源与核实状态 |
| 用途绑定 | `user_model_purpose_assignment` / 共享 Provider 绑定：`KNOWLEDGE_CHAT`、`PLANNING`、`AGENT` 等用途解析 |

这套结构已经能解决“聊天模型从哪里来、用哪个、窗口多大、谁核实过”的问题。

但它当前主要围绕 **CHAT** 设计：

- `provider_type` 主要是 `OPENAI_COMPATIBLE` / `ANTHROPIC` / `GEMINI`；
- `ai_model_profile` 的 `context_window_tokens` 是 CHAT 语义；
- 用途枚举里没有独立的 `RERANK`；
- 没有 Reranker 的候选数、batch、协议、分数语义等字段。

### 1.2 当前 Reranker 的实际状态

当前项目并不是“没有 rerank”，而是有三条不同路径：

| 路径 | 实现 | 是否调用模型 | 说明 |
| --- | --- | --- | --- |
| `RERANK_MODE=java` + `LEXICAL_HEURISTIC` | `SemanticHeuristicReranker` | 否 | 标题匹配 + 词项覆盖 + RRF，当前默认生产路径 |
| `RERANK_MODE=java` + `NEURAL_MODEL` | `NeuralModelReranker` | 是 | Java 侧 LLM 重排器，默认未选中；内部 3s 超时，失败回退启发式 |
| `RERANK_MODE=python` | Python worker + Java 模型网关 | 是 | 当前 W3 评估路径；使用 `KNOWLEDGE_CHAT`，不是专用 cross-encoder |

因此：

- “Java 重排”首先是 **Java 进程内的重排器**，不等于“无模型”；
- 当前生产默认是纯本地启发式，稳定、快、零模型依赖；
- 专用 Reranker 还没有进入生产配置；
- 本地 Qwen3-Reranker-0.6B spike 只是 readiness，不是生产接入。

### 1.3 需要解决的问题

如果没有统一的配置模型，后续会出现：

- Reranker 模型名、endpoint、模型文件路径硬编码在脚本或 Java 代码里；
- 每个能力一套配置表和一套管理 API，无法审计和回滚；
- 管理员无法测试/发布/回滚 Reranker；
- 本地模型、云模型、不同协议（llama.cpp、TEI、Infinity、OpenAI-compatible）各自为政；
- Reranker 被错误挂到 `KNOWLEDGE_CHAT`，污染 chat 的超时、配额和评测口径；
- Embedding 维度变更与 Reranker 候选数变更混在一起，无法判断是否需要重建索引。

## 2. 设计目标与非目标

### 2.1 目标

1. **一套配置框架**：Provider、模型档案、能力档案、用途绑定、测试、发布、回滚、审计共用一套生命周期。
2. **能力可扩展**：新增 `EMBEDDING`、`RERANK` 不需要复制一套 Provider 管理。
3. **管理员可配置**：模型名、endpoint、协议、超时、候选数、batch、维度等由管理员配置，不写死在代码里。
4. **运行时统一解析**：Java `ModelRouter` 按 capability + scope 解析模型，Python 不持 Provider 密钥。
5. **默认安全**：所有新能力默认关闭；`RERANK_MODE=java` 不变。
6. **可审计、可回滚**：每次测试、发布、绑定、回滚都有操作者、版本、时间和证据。
7. **可评测**：Embedding 和 Reranker 有各自的评测口径，不共用 chat 的指标。

### 2.2 非目标

- 不在本文实现代码、不新增迁移、不修改默认开关；
- 不设计中心 agent 编排器、Lane Registry、Resource Lock；
- 不决定是否采用专用 Reranker，只定义“如果采用，如何配置和接入”；
- 不改变现有 Agent/审批/租约/SSE/账本边界；
- 不把本地模型文件、进程管理或密钥写进 Java 代码。

## 3. 设计原则

### 3.1 同一生命周期，不同 capability schema

```text
Provider
  → Model Profile
    → Capability Profile
      → Test
        → Publish
          → Assignment
            → Runtime Resolution
              → Fallback / Metrics / Audit
```

生命周期相同：

- 创建 Provider；
- 创建模型档案；
- 为模型创建 capability 档案；
- 测试；
- 发布（不可变版本）；
- 绑定用途/项目/租户；
- 运行时解析；
- 回滚与审计。

字段不同：

- CHAT 需要上下文窗口、输出上限、流式、thinking、工具调用；
- EMBEDDING 需要维度、归一化、距离函数、最大输入、batch、索引版本；
- RERANK 需要协议、最大候选数、batch、最大文本长度、分数语义、超时、topK、fallback。

### 3.2 Provider 与 capability 解耦

Provider 只表达“怎么连”：

- 端点；
- 鉴权；
- 网络区域；
- 供应商类型；
- 健康检查路径。

Capability Profile 表达“这个模型能做什么、怎么调用”：

- capability 类型；
- API 协议；
- 模型键；
- 能力参数；
- 超时/并发/配额；
- 分数或向量的语义；
- 版本与来源。

### 3.3 协议优先于厂商名

不要用 `provider_type` 直接决定调用方式。建议显式定义 `protocol`：

| protocol | 用途 |
| --- | --- |
| `OPENAI_CHAT` | OpenAI-compatible chat completions |
| `ANTHROPIC_MESSAGES` | Anthropic Messages |
| `GEMINI_GENERATE_CONTENT` | Gemini generateContent |
| `OPENAI_EMBEDDINGS` | OpenAI-compatible embeddings |
| `TEI_EMBEDDINGS` | HuggingFace TEI embeddings |
| `RERANK_HTTP` | 通用 `POST /rerank` |
| `LLAMACPP_RERANK` | llama.cpp 原生 `/rerank` |
| `TEI_RERANK` | TEI rerank endpoint |

这样本地 llama.cpp、TEI、Infinity、云厂商都走同一套 Provider + Protocol，而不是不断扩 `provider_type`。

### 3.4 默认关闭与安全回退

- `RERANK_MODE=java` 默认不变；
- 新 Reranker capability 默认 `enabled=false`；
- 没有测试/发布/绑定时，运行时不可见；
- 运行失败回退到 `SemanticHeuristicReranker`，不能让主请求失败；
- 回退必须记录原因，不能静默。

## 4. 分层设计

### 4.1 Provider

逻辑字段：

| 字段 | 说明 |
| --- | --- |
| `id` | Provider ID |
| `name` | 管理员可见名称 |
| `provider_type` | `OPENAI_COMPATIBLE` / `ANTHROPIC` / `GEMINI` / `LOCAL_CUSTOM` |
| `protocol` | 见 3.3；一个 Provider 可以声明多个 protocol，但推荐一个 Provider 一个主协议 |
| `base_url` | 服务根地址 |
| `api_path` | 能力调用路径模板 |
| `auth_type` | `NONE` / `API_KEY` / `BEARER` / `CUSTOM_HEADER` |
| `secret_ref` | 加密密钥引用，不返回明文 |
| `network_zone` | `public` / `internal` / `local` |
| `health_path` | 健康检查路径 |
| `enabled` | Provider 级开关 |

### 4.2 Model Profile

模型档案表达“这是哪个模型、哪个版本、什么来源”：

| 字段 | 说明 |
| --- | --- |
| `model_key` | 稳定模型键，例如 `Qwen3-Reranker-0.6B` |
| `display_name` | 管理员可见名称 |
| `version` | 模型版本或发布日期 |
| `source` | `PROVIDER_DOCUMENT` / `ADMIN_VERIFIED` / `LOCAL_FILE` |
| `sha256` | 本地模型文件校验和（可选） |
| `license` | 许可证标识 |
| `common_facts` | 通用事实，如最大输入、上下文窗口、pricing 版本 |

### 4.3 Capability Profile

建议新增能力档案，而不是把 Reranker 字段塞进 chat 档案：

```text
ai_model_capability_profile
  id
  model_profile_id
  capability            -- CHAT / EMBEDDING / RERANK
  protocol              -- 该能力的 API 形状
  settings_json         -- capability-specific 参数，JSON Schema 校验
  status                -- DRAFT / TESTED / PUBLISHED / DISABLED
  version               -- 不可变版本号
  source                -- 来源与核实状态
  verified_at / verified_by
  created_at / updated_at
  unique(model_profile_id, capability)
```

`settings_json` 示例：

```json
{
  "protocol": "LLAMACPP_RERANK",
  "timeoutMs": 3000,
  "maxCandidates": 50,
  "batchSize": 8,
  "maxLength": 2048,
  "scoreType": "sigmoid",
  "topK": 8,
  "healthPath": "/health"
}
```

不建议把 `maxCandidates`、`batchSize`、`dimension` 直接加成一堆顶层列；能力差异会继续增长，JSON + Schema 更适合。

### 4.4 Purpose / Assignment

保留现有用途绑定思路，但增加能力维度：

| 字段 | 说明 |
| --- | --- |
| `scope` | `GLOBAL` / `TENANT` / `PROJECT` / `USER` |
| `capability` | `CHAT` / `EMBEDDING` / `RERANK` |
| `purpose` | 例如 `KNOWLEDGE_CHAT`、`AGENT`、`PLANNING`、`KNOWLEDGE_RERANK` |
| `model_capability_profile_id` | 指向已发布版本 |
| `fallback_profile_id` | 可选回退 |
| `enabled` | 绑定开关 |

解析顺序建议：

```text
项目级绑定 → 租户/用户级绑定 → 全局共享绑定 → 默认实现
```

### 4.5 Runtime Router 与 Adapter

Java 侧统一入口：

```text
ModelRouter.resolve(capability, purpose, scope)
  → published capability profile
  → ChatAdapter / EmbeddingAdapter / RerankAdapter
  → provider protocol
  → fallback / metrics / audit
```

约束：

- Python worker 不读取 Provider 密钥，只调用 Java 内部模型网关；
- Reranker 调用使用独立 `RERANK` purpose，不复用 `KNOWLEDGE_CHAT`；
- 本地 Reranker 服务只暴露 `/rerank`，Java 不感知模型文件路径；
- Embedding 适配器负责维度/归一化；Reranker 适配器负责候选数/batch/分数语义；
- 所有调用带 `callId`、模型版本、profile id，便于审计与对账。

## 5. Capability 字段定义

### 5.1 CHAT

保留现有字段：

- context window；
- max output；
- streaming；
- thinking；
- native tools；
- JSON/structured output；
- usage 支持；
- 价格版本。

### 5.2 EMBEDDING

| 字段 | 说明 |
| --- | --- |
| `dimension` | 向量维度；必须与 pgvector 索引一致 |
| `maxInputTokens` | 单条文本上限 |
| `normalize` | 是否归一化 |
| `distanceMetric` | cosine / inner product / l2 |
| `batchSize` | 批量编码大小 |
| `indexVersion` | 维度/模型变更后的索引版本 |

重要：**Embedding 维度变更必须触发重建索引**，不能用“切换模型”悄悄完成。

### 5.3 RERANK

| 字段 | 说明 |
| --- | --- |
| `protocol` | `RERANK_HTTP` / `LLAMACPP_RERANK` / `TEI_RERANK` |
| `timeoutMs` | 从 Java 到 Reranker 的总超时 |
| `maxCandidates` | 单次最多送多少候选 |
| `batchSize` | 模型批大小 |
| `maxLength` | 单条候选最大字符/token |
| `scoreType` | `sigmoid` / `logit` / `normalized` |
| `topK` | 返回数量 |
| `healthPath` | 健康检查 |
| `fallback` | 失败回退策略，例如 `java-heuristic` |

Reranker 的配置不能包含 chat 的 `maxOutputTokens`、`streaming`、`toolCalling` 等字段；这些字段应被 Schema 拒绝。

## 6. 管理端生命周期

### 6.1 创建与测试

管理员流程：

1. 创建/选择 Provider；
2. 创建 Model Profile；
3. 创建 Capability Profile（CHAT / EMBEDDING / RERANK）；
4. 执行能力测试；
5. 发布；
6. 绑定 purpose / project / tenant；
7. 观察 shadow；
8. 需要时回滚。

能力测试不能只看 HTTP 200：

| 能力 | 测试内容 |
| --- | --- |
| CHAT | 一次最小 completion，检查 content/usage/finish reason |
| EMBEDDING | 两条文本编码，检查维度、归一化、有限值 |
| RERANK | 一条 query + 两条 document，检查两个有限分数、顺序合理、延迟 |

测试失败不能发布；测试通过也不能自动证明生产可用，仍需门禁和 shadow。

### 6.2 发布与回滚

- 发布生成不可变版本；
- 绑定只指向已发布版本；
- 回滚切换回上一已发布版本；
- 发布/回滚必须写审计：操作者、时间、profile、版本、原因；
- 不允许管理员直接编辑已发布版本的 settings。

### 6.3 权限与租户

- 只有管理员可创建/发布全局 Provider；
- 项目级绑定必须校验项目权限；
- 普通用户只能看到脱敏的模型名称/能力，不看到密钥、endpoint 私网地址；
- 共享 Provider 的密钥只由服务端解密；
- 本地 Provider 只允许内网地址，禁止暴露公网。

## 7. 运行时流程

### 7.1 RERANK 流程

```text
Python worker / Java retrieval
  → Java ModelRouter.resolve(RERANK, purpose, scope)
  → RerankAdapter
  → Provider protocol
  → local llama.cpp/TEI/Infinity or remote rerank API
  → scores + model version + latency
  → 若失败：fallback SemanticHeuristicReranker
```

禁止：

- Python 直接读取 Reranker endpoint/密钥；
- Java 代码硬编码模型名、模型文件路径、端口；
- 复用 `KNOWLEDGE_CHAT`；
- 在请求里临时选择未发布模型。

### 7.2 EMBEDDING 流程

```text
Indexing / retrieval
  → Java ModelRouter.resolve(EMBEDDING, purpose, scope)
  → EmbeddingAdapter
  → provider protocol
  → vectors + dimension + indexVersion
```

维度不一致时：

- 拒绝写入；
- 新模型使用新索引版本；
- 不允许混写旧维度；
- 迁移必须可回滚。

### 7.3 默认行为

- `RERANK_MODE=java`；
- `MEMORY_JOB_ENABLED=false`；
- 没有 RERANK assignment 时，使用 `SemanticHeuristicReranker`；
- 新 Provider/Capability 默认 `DISABLED`/`DRAFT`；
- 任何默认开关变更必须另立审批，不在本文范围。

## 8. 本地 Reranker 接入

### 8.1 推荐形态

本地 Reranker 作为独立服务：

```text
llama.cpp / TEI / Infinity
  POST /rerank
  { query, documents: [{id, text}], top_k }
  → { results: [{id, score}], model, latency_ms }
```

Java 通过 `RerankAdapter` 调用它；模型文件、进程、GPU/CPU 资源由部署层管理。

### 8.2 配置边界

- Provider 配置：`base_url=http://reranker:8080`、`protocol=LLAMACPP_RERANK`、`auth=NONE`；
- Model Profile：`Qwen3-Reranker-0.6B`、版本、SHA-256、license；
- Capability Profile：超时、候选数、batch、max length、分数语义；
- 不在 Java 代码中写 `D:\models\...`；
- 不在仓库提交模型权重；
- 本地服务只监听内网/回环地址。

### 8.3 当前 Lane F 的定位

`tests/performance/reranker/qwen_llamacpp_rerank.py` 是 **research harness**：

- 允许有默认 endpoint/model 以便复现；
- 不能作为生产调用路径；
- 不能作为 `RerankAdapter` 的实现；
- 8/20/50 全量数据仍是 `NOT_RUN`；
- 只有完成配置化接入和门禁后，才能讨论生产切换。

## 9. 可观测性与评测

### 9.1 运行指标

| 指标 | 说明 |
| --- | --- |
| `model.calls{capability,profile,outcome}` | 调用数与结果 |
| `model.latency{capability,profile}` | P50/P95/P99 |
| `model.fallback{capability,reason}` | 回退次数与原因 |
| `rerank.overhead` | 不含模型的服务开销，沿用 W3 口径 |
| `rerank.model.duration` | Reranker 模型耗时 |
| `rerank.total.duration` | 端到端 |
| `embedding.dimension.mismatch` | 维度不匹配拒绝数 |

日志不得包含 prompt 全文、候选正文、密钥、token。

### 9.2 评测口径

Reranker：

- 8 / 20 / 50 候选；
- NDCG@8、引用支持率；
- P50/P95/P99；
- 3s 端到端成功率；
- fallback 率；
- 8/20/50 样本不足时标 `LOW_CONFIDENCE`。

Embedding：

- Recall@K；
- 维度/归一化校验；
- 索引版本；
- 切换前后召回对比；
- 维度变更必须重建索引后再比较。

不能把 Reranker 的质量指标和 Embedding 的召回指标混在一张门禁表里。

## 10. 迁移与灰度

### Phase 0：设计冻结

- 本文评审通过；
- 确定 provider protocol、capability schema、assignment 解析顺序；
- 不写代码。

### Phase 1：配置面

- 新增 capability profile 与管理员测试/发布/回滚；
- 默认关闭；
- 增加审计与 Schema 校验；
- 不接运行时。

### Phase 2：Adapter

- `RerankAdapter` / `EmbeddingAdapter`；
- 本地 llama.cpp/TEI 接入；
- 统一错误/超时/fallback；
- 单测与契约测试。

### Phase 3：影子与评测

- 管理员发布但 `enabled=false`；
- shadow 采样；
- 8/20/50 评测；
- 与 Java 启发式对比；
- 不改变默认。

### Phase 4：放行决策

- 只有证据达标才考虑切换；
- 否则保持 `RERANK_MODE=java`；
- 回滚路径必须已验证。

## 11. 当前状态与阻塞

截至 2026-11-12：

| 项 | 状态 |
| --- | --- |
| 仓库 | `dev @ 7014aba0`；CI success；`main` 未动 |
| JMeter | 本地基线已收口；per-label 门禁仍 FAIL；不是生产容量 |
| Lane F | Qwen3-Reranker-0.6B readiness；8/20/50 `NOT_RUN` |
| Reranker 配置 | **尚无生产级管理员配置**；当前脚本是 research harness |
| W3 3s | 真实模型端到端 FAIL；`RERANK_MODE=java` 保持 |
| W4 decide | agreement 0.80、JSON 0.90，仍 FAIL |
| W5 memory | Job/审批 PASS；C `NOT_VERIFIED` |
| W6 | 设计 PASS；实现 BLOCKED |

这里的“卡住”不是所有事情都坏了，而是：

- **W3** 卡在 provider/3s 预算与 reranker 方案选择；
- **W4** 卡在标签复核和 prompt/policy；
- **W5** 卡在 memory C 与来源治理；
- **W6** 卡在前置条件；
- **配置平台** 卡在设计未落地、管理员配置不存在；
- **JMeter** 已完成基线，不是当前阻塞；
- **Lane F** 只完成 readiness，尚未全量测量。

## 12. 待决策问题

1. Reranker 的首选形态：本地 cross-encoder、云 rerank API，还是继续 Java 启发式？
2. 本地 Reranker 是否允许作为共享 Provider，还是只作为单租户/实验 Provider？
3. Reranker 的分数语义统一为 sigmoid，还是保留各协议原生分数？
4. Embedding 维度变更的索引迁移策略与回滚方式。
5. 是否允许同一模型同时声明 CHAT + RERANK，还是必须不同 profile？
6. Reranker 的超时是统一 3s，还是按候选数分档？
7. 本地模型文件的生命周期由谁管理（部署脚本/模型仓库/运维）？

## 13. 验收标准

本文设计评审通过的最低标准：

- [ ] Provider / Model Profile / Capability Profile / Assignment 四层职责清晰；
- [ ] CHAT / EMBEDDING / RERANK 的 schema 差异明确；
- [ ] 管理员创建、测试、发布、绑定、回滚、审计流程完整；
- [ ] 运行时解析与 fallback 明确；
- [ ] 本地 Reranker 不硬编码模型名/路径/端口；
- [ ] 默认关闭，`RERANK_MODE=java` 不变；
- [ ] Embedding 维度变更与 Reranker 切换的风险分开；
- [ ] 明确当前状态与未完成项，不把设计写成完成声明。

## 14. 明确不做

- 不实现本文；
- 不新增迁移；
- 不修改 `RERANK_MODE`、`DECIDE_MODE`、`PLANNING_DRAFT_MODE`、`MEMORY_JOB_ENABLED`；
- 不设计中心 agent / Lane Registry / Resource Lock；
- 不把 Lane F research harness 接入生产；
- 不宣称 W3 3s 已通过；
- 不把 JMeter 本地基线外推为生产容量。

