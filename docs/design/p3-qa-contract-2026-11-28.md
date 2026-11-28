# P3 知识问答迁 Python：Java ↔ Python 接口契约（FROZEN v1）

> **状态**：FROZEN v1（2026-11-28 用户拍板：**§9 的 Q1–Q14 全部按各题推荐选项执行**，决议记录见 §10；两个工作包均以本文为准）。
> **日期**：2026-11-28（起草）；2026-11-28（冻结）
> **上位设计**：[ai-service-split-and-redis-2026-11-25.md](ai-service-split-and-redis-2026-11-25.md) §4（问答）、§13 P3、§15（评测）、§18。对它的修订集中写在本文各节"建议"与 §9 待决问题，冻结时汇总。
> **沿用契约**：[p1-ai-kernel-contract-2026-11-26.md](p1-ai-kernel-contract-2026-11-26.md)（令牌、scope、租借、用量、错误体）与 [p2-rag-contract-2026-11-27.md](p2-rag-contract-2026-11-27.md)（含 R1–R5）。本文只写 P3 增量；对 P2 决定的引用逐条标注出处。
> **代码基线**：分支 `ai-service-split` @ `ed8a0218`（P2 切换已合入，PR #51；`ca403772` feat(p2-switch)），Flyway 已到 `V106`，P3 的 Java 迁移从 **`V107`** 起。
> **编制方式**：只读调研，没有启动 Docker、Maven、pytest 或 worker。文中"现状"都标了 `类名:行号` 级出处；没在仓库里找到的写"未找到"。
> **与 P2 的关系**：P2 契约 §12.1 D2 已决定查询理解随 P3 迁；§12.1 D3 已决定 `/v1/knowledge/search` 作为内部检索接口保留、P3 复用；§3 硬约束"Python 不读写业务 schema"是否维持，本文 §2(b)/§5 给出评价与选项，由 §9 拍板。

---

## 0. P3 范围

| 做 | 不做 |
|---|---|
| 查询理解迁 Python：`QueryRouter`、`QueryUnderstandingService`、`MultiQueryDecomposer`、`MetadataFilterParser` 及 `retrieval/query/` 模型类（P2 契约 §12.1 D2、§8） | Agent 图与 Agent 工具链（P4）；Agent/规划侧的检索调用方不动 |
| 知识问答生成迁 Python：证据装配、提示词装配、chat 模型调用（含流式）、输出安全预检 | 会话 CRUD、反馈、限流、系统开关的归属变更默认不做（§9 Q6 拍板） |
| 流式输出：worker 侧新增流式 chat 能力与 SSE（或流式 JSON）输出，Java 侧中继方案按 §2(a) 拍板 | 规划生成（P5）；`TaskPlanContextAssembler` / `SearchProjectKnowledgePlanningTool` / Agent 工具的问答化改造 |
| 引用与消息落库方案（§2(b)、§5）：默认维持 Java 落库、Python finalize 回调 | 删除 `knowledge_*` 三表或改表归属；`document_chunk` 等旧表清理（P7） |
| `/v1/rag/plan` 调用路径进程内化（Java 客户端删除，worker 端点保留与否 §9 Q5） | embedding/索引/检索执行（P2 已完成，PR #51） |
| 删除 Java 问答生成与查询理解代码（§7） | 删除 `RoutingChatModelGateway` 全部用途（Agent/规划仍在用，P4/P5 处理） |

## 1. 现状盘点（只读调研结果）

### 1.1 查询理解面（Java，待迁）

**结论先行：`QueryRouter` 本身不依赖 LLM——它是纯正则/规则路由；它对 LLM 的唯一接触点是 COMPLEX 查询可选调用 Python worker 的 `/v1/rag/plan`，而该调用已经在 Python 侧执行模型调用。** 这意味着 P3 迁查询理解本质上是把规则路由器搬到 plan 同进程，把一次跨服务 HTTP 往返变成进程内调用。

| 类 | 现状职责 | 证据 | 依赖 |
|---|---|---|---|
| `QueryRouter` | 纯规则路由，优先级顺序为契约：STRUCTURED → COMPLEX → EXACT_IDENTIFIER → FAST → 默认 `DENSE_WITH_BOUNDED_FALLBACK`；路由错误回退 HYBRID 且日志掩码（只留前 6 字符） | `retrieval/query/QueryRouter.java:42`（`@Component`）、`:50-76`（5 组编译期正则）、`:30-32` javadoc 明示"Routing precedence (order is contractual)"、`:186-196`（异常回退 + 掩码） | `MetadataFilterParser`、`MultiQueryDecomposer`（构造器直接 new，`:96-110`）；可选 `QueryPlanGatewayClient` + `QueryPlanProperties`（`:81-84` `@Autowired(required=false)`） |
| 同上：COMPLEX 分支 | 触发条件：≥2 个问号 / 比较句式 / 并列连词；触发后先本地分解，再可选调 `/v1/rag/plan` 增强：`enabled=false`、client 缺失、**身份缺失（uid/pid）**、网关异常、`confidence < 0.5` 五种情况全部使用本地结果；采纳时合并改写查询、子查询与过滤（dateRange → `createdAt` 的 gte/lte `MetadataFilter`） | `QueryRouter.java:153-158`、`:199-267`（`routeComplexWithOptionalQueryPlan`）、`:209-220`（五重前置检查）、`:240-245`（低置信度回本地）、`:287-300`（`mergePlanFilters`） | 失败路径全部"回本地规则"，无进程内回落豁免 |
| `QueryPlanGatewayClient` | POST `{workerBaseUrl}/v1/rag/plan`；Ed25519 短令牌（`InternalTokenProvider.queryPlanToken(userId, projectId)`，空令牌 fail closed 不发请求）；connect 2s、read `deadlineMs+5s`、**不重试**；响应体上限 1MB；契约校验 extra=forbid（未知过滤键、越界、重复子查询一律 `SCHEMA` 违规拒绝） | `retrieval/queryplan/QueryPlanGatewayClient.java:56`（`PLAN_PATH="/v1/rag/plan"`）、`:160-169`（请求构造）、`:175-183`（令牌）、`:39-71`（超时与上限常量）、`:201-238`（解析）、`:279-280`（未知键拒绝） | `InternalTokenProvider`、`QueryPlanProperties` |
| `QueryPlanProperties` | `query.plan.enabled` **默认 false**；`workerBaseUrl` 默认 `http://ai-worker:9100`；`deadlineMs` 默认 8000、上限 30000 | `retrieval/queryplan/QueryPlanProperties.java:16-18`、`:20-27` | — |
| 请求/响应形状 | 请求 `{schemaVersion:1, query, locale, historySummary, documentIds[], deadlineMs}`（`historySummary` 为空整体省略）；响应 `{schemaVersion:1, rewrittenQuery, subQueries[](≤8、≤500 字、去重), filters{documentIds[](≤200), docTypes[](≤10), dateRange{from,to}} , confidence∈[0,1]}` | `QueryPlanGatewayClient.java:411-419`（`Payload` record）、`:62-71`（上限常量）、`:216-237`（响应解析） | — |
| `QueryUnderstandingService` | 多轮对话理解，**纯规则、无 LLM**：消息成对成 turn（USER+ASSISTANT）、窗口 5 turn / 3500 字符、意图分类 STANDALONE/FOLLOW_UP/TOPIC_SWITCH（话题切换/省略句/代词三组正则）、改写 = 最近用户问题 + `\n` + 原问题逐字保留（上限 2000 code points）、上下文提示词带证据隔离声明并剥离历史 `[S1]` 引用标记、XML 转义防注入 | `retrieval/query/QueryUnderstandingService.java:23`（**不是 Spring Bean**，普通类）、`:26-28`（预算常量）、`:31-47`（三组正则）、`:113-170`（`groupIntoTurns`）、`:195-218`（`detectIntent`）、`:223-258`（`rewriteQuery`）、`:266-301`（`buildContextPrompt`，`:271-276` 证据隔离声明） | 无 Spring 依赖；唯一使用者是 `KnowledgeConversationContext` |
| `KnowledgeConversationContext` | 适配器：把 `knowledge_message` 近几条转成 `ConversationMessage`，调 `QueryUnderstandingService.understand`，产出 `retrievalQuery` + `prompt`（进 user payload 前缀） | `knowledge/application/service/KnowledgeConversationContext.java:15-31`，其中 **`:17` 是 `private static final QueryUnderstandingService UNDERSTANDING_SERVICE = new QueryUnderstandingService()`**——静态实例、非注入 | `QueryUnderstandingService`；`KnowledgeQuestionApplicationService.java:103-106`、`KnowledgeStreamQuestionService.java:130-133` |
| `MultiQueryDecomposer` | 正则分解复杂查询为子查询（问句切分/比较/连词） | `retrieval/query/MultiQueryDecomposer.java:19-37` | 无 |
| `MetadataFilterParser` | 正则抽取元数据过滤：显式键值、书名号、文件名提及、标题提及、MIME、时间前后 | `retrieval/query/MetadataFilterParser.java:17-76` | 无 |
| 模型类 | `StructuredQuery`、`QueryRouteDecision`、`RetrievalStrategy`、`QueryComplexity`、`QueryIntentType`、`QueryUnderstandingResult`、`ConversationMessage`、`ConversationTurn`、`MetadataFilter`、`FilterOperator`、`DocumentFilterCriteria` | `retrieval/query/` 目录（15 个文件，P2 契约 §8"保留到 P3"清单） | 无 |

**`/v1/rag/plan` 在 worker 侧的现状**：`api/query_plan.py:59`（router 前缀 `/v1/rag`）+ `:112-150`（POST `/plan`）；鉴权 `require_claims(request, scope="plan", audience="ai-worker")` 且令牌必须带 `uid/pid`（`:175-194`，缺失 403）；流程 = `PromptStore` 取 `query_plan.system/user` → `ModelKernel.complete`（租借 `purpose=QUERY_PLAN`）→ `parse_query_plan`（模型编造的 `filters.documentIds` 必须是请求白名单子集，否则丢弃并计数，不报错）；超时 504 `DEADLINE_EXCEEDED` 不返回半成品；`confidence < 0.5` 由 Java 使用原查询——**该降级只在 Java**（`query_plan.py:13-14` 模块注释）。租借路由：`LeasePurpose.QUERY_PLAN → ModelPurpose.KNOWLEDGE_CHAT`（`internal/ai/credential/LeasePurpose.java:21`）。

### 1.2 问答生成面（Java，待迁 / 待决）

| 组件 | 现状职责 | 证据 |
|---|---|---|
| `KnowledgeController` | 前端入口，`/api/v1/projects/{projectId}/knowledge/sessions`：list/create/detail/rename/delete、`POST /{sessionId}/questions`（同步 ask）、`POST /{sessionId}/questions/stream`（SSE，`SseEmitter(300_000L)`）、feedback 三件套 | `knowledge/api/controller/KnowledgeController.java:35`、`:93-111`（`:108` 超时 300s）、`:113-141` |
| `KnowledgeQuestionApplicationService` | 同步问答编排：校验（问题 ≤2000 code points、`documentIds` ≤20 去重）→ `requireMember` → 会话属主校验（`findOwn`）→ `isChatEnabled` → 限流 → 多轮消歧 → 检索（`KNOWLEDGE_QA`，`TOP_K=8`）→ 证据装配（`buildWithTokenBudget(candidates, 5, 2500)`，即 5 源/2500 token 预算）→ 空证据直接落库统一拒答文案 → `promptAssembler.system()/userPayload` → `chat.complete`（`ModelPurpose.KNOWLEDGE_CHAT`）→ `visibility.whileVisible` 包住校验与落库 → 引用校验 → 输出泄漏终检（k-gram）→ `aiLogs` → `persistence.saveExchange` | `knowledge/application/service/KnowledgeQuestionApplicationService.java:38`（TOP_K）、`:86-190`（主流程）、`:94-97`（开关+限流）、`:119-127`（空证据短路）、`:139-143`（chat 调用）、`:154-188`（复核+落库） |
| `KnowledgeStreamQuestionService` | 流式问答编排：与同步版同构，差异在于 (1) 专用线程池 **2 核心/2 最大、队列 32**，满了直接 `AI_PROVIDER_UNAVAILABLE`；(2) `emitter.onCompletion/onTimeout/onError` 断连即取消在途任务；(3) token 回调里维护"未发送缓冲 + k-gram 前瞻"：只发送留出 `K_GRAM-1` 尾码点后可确认安全的前缀，命中系统提示词指纹立即中止流并下发 error 事件；(4) 完成回调里落库后依次发 `citations`、`done(messageId)` | `knowledge/application/service/KnowledgeStreamQuestionService.java:68-73`（线程池）、`:100-283`（主流程）、`:116-122`（断连）、`:167-208`（流式预检）、`:210-261`（完成回调）、`:277-280`（拒绝执行） |
| `KnowledgePersistenceService` | 事务落库一轮交换：`lockOwn` 锁会话 → 插 USER + ASSISTANT 两条消息（`createdAt` 微秒错开保序）→ 引用逐条 `insertCitation`（quote 截 1000 code points）→ `touch` 会话；`promptKey/promptVersion` 只写助手消息且必须成对 | `knowledge/application/service/KnowledgePersistenceService.java:35-105`、`:67-69`（成对校验）、`:73-74`（时间错开） |
| `KnowledgeVisibilityGuard` | `whileVisible`：对 connector 三张表 `FOR SHARE` 持短锁 + blocked 存在性检查 + 逐文档 `documents.readable`，任一不满足抛 `CONNECTOR_REAUTH_REQUIRED`，包住"发流/落库"动作，防撤权竞态 | `knowledge/application/service/KnowledgeVisibilityGuard.java:26-52` |
| `KnowledgeRateLimiter` | 每用户每小时窗口限流：Redis Lua `INCR+EXPIRE`（默认 `knowledge.rate-limit-per-user-hour:60`），Redis 不可用降级进程内计数（`ConcurrentHashMap` 兜底） | `knowledge/application/service/KnowledgeRateLimiter.java:40`（默认值）、`:49-70`（Lua + 回退） |
| `KnowledgeSessionApplicationService` | 会话 CRUD + detail（消息 + 引用按 message 分组回填）；`requireMember` + 属主三要素（projectId+sessionId+userId）校验 | `knowledge/application/service/KnowledgeSessionApplicationService.java:36-100`、`:102-105` |
| `KnowledgeFeedbackService` | 消息级反馈 submit/remove/get | `knowledge/application/service/KnowledgeFeedbackService.java` |
| 引用校验 `KnowledgeCitationValidator` | 校验模型回答：统一拒答文案（`INSUFFICIENT_ANSWER = "当前项目资料不足以回答该问题"`）、引用标记 `[Sn]` 对照 sources、最短证据长度 20、可选 `FactEntityCoverageChecker` | `knowledge/domain/service/KnowledgeCitationValidator.java:24-48` |
| 证据装配 `KnowledgeContextBuilder` | 阈值常量：`MIN_SIMILARITY=0.15`（读 `scores.final` 的准入分，P2 契约 §12.3 R1-2）、`DEFAULT_MAX_SOURCES=5`、`MAX_SOURCES_LIMIT=12`、`PER_DOCUMENT_SOURCE_CAP=2`；`buildWithTokenBudget` 用 `TokenCounter` 精确预算 | `knowledge/domain/service/KnowledgeContextBuilder.java:31-42`、`KnowledgeQuestionApplicationService.java:196-201` |
| 输出安全 `KnowledgeOutputSafetyGuard` | 系统提示词泄漏检测：k-gram 指纹集 + `inspect`（全量终检）+ `fingerprintSet/containsAnyFingerprint`（流式前检）；泄漏时以 `BLOCKED_ANSWER` 替换或中止流 | `knowledge/domain/service/KnowledgeOutputSafetyGuard.java`；调用点 `KnowledgeQuestionApplicationService.java:159-168`、`KnowledgeStreamQuestionService.java:170-208`、`:220-229` |
| chat 网关 | `RoutingChatModelGateway`（`@Primary`）：按 `AiInvocationContext(userId+purpose)` 解析用户级/共享模型配置（`UserAiProviderService`，SharedAiProvider 是共享档），解密 apiKey，adapter 直连 provider；`complete` + `completeStream`（读 OpenAI 兼容 SSE 分片） | `infrastructure/ai/model/RoutingChatModelGateway.java:17-56`、`infrastructure/ai/OpenAiCompatibleChatModelGateway.java:69-134`（complete）、`:136+`（completeStream） |
| 调用日志 | `AiCallLogService` 写 `ai_call_log`（success/invalidOutput/failure），问答侧 caller 是 Java | `KnowledgeQuestionApplicationService.java:146-173`、`KnowledgeStreamQuestionService.java:231-238` |

### 1.3 SSE 流式现状（Java 侧）

| 项 | 现状 | 证据 |
|---|---|---|
| 传输 | Spring MVC `SseEmitter`，`POST .../questions/stream`，超时 300s | `KnowledgeController.java:102-111` |
| 响应头 | `SseResponses.stream`：`text/event-stream` + `Cache-Control: no-cache, no-transform` + **`X-Accel-Buffering: no`**（关 Nginx 缓冲） | `common/api/SseResponses.java:18-26` |
| 事件 | 四种：`token{text}`、`citations{citations[]}`、`done{messageId}`、`error{code,message}`；载荷是 `KnowledgeStreamEvent` record（`@JsonInclude(NON_NULL)`），先 `event:` 后 `data:` | `knowledge/application/view/KnowledgeStreamEvent.java:10-33`、`KnowledgeStreamQuestionService.java:290-300` |
| **事件 id / 断线续传** | **知识问答没有**：不发 `id:` 字段、不读 `Last-Event-ID`、无重放缓冲；断线即本轮作废。全仓只有 Agent 事件流实现了 `Last-Event-ID` 游标续传 | `KnowledgeStreamQuestionService.java`（全文无 id 字段）；对照 `agent/api/controller/AgentEventController.java:35-45`（`resolveCursor(afterSequence, lastEventId)`） |
| done 语义 | `done` 带落库后的 `messageId`——**落库必然发生在 done 事件之前**，这是 P3 中继方案的关键时序约束 | `KnowledgeStreamQuestionService.java:240-255` |
| 取消 | 断连/超时 → `Future.cancel(true)`，在途 chat 调用被中断；**不持久化未完成的交换** | `KnowledgeStreamQuestionService.java:116-122`、`:134`、`:148-149` |

### 1.4 提示词装配

- **Prompt 正文已经在 Python worker**（P1/J2 迁移完成）：`ai-collab-ai-worker/prompts/` 目录含 `knowledge.system`、`knowledge.user`、`query_plan.system/user`、`memory.extract.system/user`、`planning.draft.system/user`、`decide.system/user`、6 组 `scenario.*`，manifest 为 `prompts/manifest.json`。
- worker 提供只读查询端点：`GET /v1/prompts/manifest` 与 `GET /v1/prompts/{key}/{version}`（取用时复算 sha256/variables），错误统一信封（503 资产损坏 / 404 不存在 / 400 非法）。`ai-collab-ai-worker/src/ai_collab_worker/api/prompts.py:21-60`。
- Java 侧 `RemotePromptCatalog`：启动同步预取（**不阻断启动**，失败只记错误+指标）+ 单线程周期刷新 + **14 条全过校验才整批原子替换**；缓存为空时 `require` 快速失败 `AI_PROMPT_UNAVAILABLE`。这是设计明文允许的唯一降级例外。`common/ai/prompt/RemotePromptCatalog.java:20-52`。
- Java 侧**唯一装配点** `KnowledgePromptAssembler`：`KNOWLEDGE_SYSTEM` 渲染 `{{timeContext}}`（`TimeContext.beijingTimeContext()` 由 Java 生成）；`KNOWLEDGE_USER` 渲染 `question`/`sources`，问题与证据块先经 `KnowledgePromptText.escapeXmlText` 转义——**安全不依赖模板资产**；会话上下文以 `context + "\n\n"` 前置。`knowledge/domain/service/KnowledgePromptAssembler.java:13-29`、`:44-68`。
- key 契约冻结：`knowledge.system` / `knowledge.user`（`common/ai/prompt/PromptKeys.java:18,21`）。P3 后装配点位置是 §9 Q12。

### 1.5 会话与引用存储

| 表 | 关键字段 | 出处 |
|---|---|---|
| `knowledge_session` | `project_id/user_id/title(≤120)`，索引 `(project_id,user_id,updated_at DESC)` | `V1__init_schema.sql:180-188` |
| `knowledge_message` | `session_id/role ∈ {USER,ASSISTANT}/content/insufficient_evidence/model_provider/model_name/latency_ms/prompt_tokens/completion_tokens`；V90 增 `prompt_key varchar(128)`、`prompt_version varchar(32)` | `V1__init_schema.sql:190-204`、`V90__knowledge_message_prompt_identity.sql:15-16` |
| `knowledge_citation` | V106 后：`chunk_id` 可空只留历史（外键已删）；新增 `document_id NOT NULL REFERENCES project_document ON DELETE CASCADE`、`chunk_no`、`content_hash char(64)`、`body_snapshot_id`；CHECK `chunk_id IS NOT NULL OR (chunk_no IS NOT NULL AND content_hash IS NOT NULL)`；索引 `(document_id, chunk_no)` | `V106__p2_citation_identity.sql:37-56`；原表 `V1__init_schema.sql:206-215` |

读写方（**全部 Java；Python worker 不读写这三张表**）：

| 操作 | 谁执行 | 证据 |
|---|---|---|
| 引用写入 | `KnowledgeCitationMapper.insertScoped`：`INSERT … SELECT … FROM document_body_chunk c JOIN document_body b JOIN project_document d …`，WHERE 带 `chunk_no + content_hash + body_snapshot_id` 匹配、`status='READY' AND NOT access_blocked`、连接器授权有效性子查询，最后 `FOR SHARE OF d,b,c`——**写时复核 + 行锁**，不匹配则 0 行插入失败 | `knowledge/infrastructure/mapper/KnowledgeCitationMapper.java:9-45` |
| 引用读取 | `listOwn`：JOIN `document_body_chunk`（按 `document_id+chunk_no+content_hash`）+ `document_body`（`body_snapshot_id` 为空或相等），带 READY/未封锁/连接器授权/隐藏答案过滤 | `KnowledgeCitationMapper.java:47-95` |
| 消息与会话 | `KnowledgeRepository` → `sessions/messages` Mapper（insert/update/deleteOwn/touch/recentContext/listMessages） | `knowledge/infrastructure/repository/KnowledgeRepository.java:30-75` |
| 用量与调用日志 | `ai_call_log`（V104 已带 `call_id/lease_id/caller_type JAVA|PYTHON`） | `V104__p1_ai_kernel_lease_and_usage.sql:34-48` |

### 1.6 Python worker 现状（P2 之后）

| 能力 | 现状 | 证据 |
|---|---|---|
| API 路由 | health、prompts、query_plan（`/v1/rag/plan`）、index_admin、knowledge_search（`/v1/knowledge/search`）五组 router | `ai_collab_worker/main.py:196-200` |
| chat 内核 | `ModelKernel.complete`：`lease`（`purpose`）→ `build_chat_model`（langchain-openai/anthropic/google-genai）→ `ainvoke` **一次非流式调用**；`UsageCallback` 汇总用量并在 finally 提交；AUTH 失败会作废缓存 lease。**没有流式方法**（无 `astream`/SSE 封装） | `ai_collab_worker/models/kernel.py:144-164`（complete）、`:164-199`（_complete） |
| CallScope | `purpose + user_id + project_id + bearer_token`；lease 请求由 scope 生成 | `models/kernel.py:55-76` |
| 检索管线 | P2 已迁：`KnowledgeSearchPipeline.execute`（dense-only / hybrid / multi-query-hybrid / dense-with-bounded-fallback 四路策略、租借 EMBEDDING/RERANK、融合与重排） | `ai_collab_worker/retrieval/pipeline.py:84-543` |
| SSE 输出 | **无**：worker 全仓搜不到 `text/event-stream`/`StreamingResponse`，所有响应都是一次性 JSON | 全仓 grep（只命中无关 `assert_env_allowlist` 等） |
| Redis Stream 消费 | P1 框架：XREADGROUP/claim/heartbeat 20s/XAUTOCLAIM 30s min-idle 120s/优雅停机/并发 4 | `ai_collab_worker/tasks/stream.py:1-32`（模块注释即协议） |
| 依赖 | fastapi、langchain-core/openai/anthropic/google-genai、redis、psycopg[binary,pool]、httpx、pydantic、prometheus-client | `ai-collab-ai-worker/pyproject.toml:7-20` |
| 部署 | compose 服务 `ai-collab-ai-worker-api`（`AI_WORKER_IMAGE`），**无外部端口映射**，仅内网；出口走 egress-proxy | `ai-collab-deploy/docker-compose.yml:233-237`、`:280-285` |
| 鉴权 | Ed25519 短令牌：aud 必须 `ai-worker`、scope 按端点要求（plan 端点 scope=`plan`）、uid/pid 必须存在 | `ai_collab_worker/auth/token.py:143-178`、`api/query_plan.py:126` |

### 1.7 前端消费方式（ai-collab-frontend）

- **不是 EventSource，是 POST fetch-stream**：`authenticatedFetch(POST /api/v1/projects/{p}/knowledge/sessions/{s}/questions/stream)` → `response.body.getReader()` 手动按行解析 SSE（`event:`/`data:` 前缀），事件类型 `token/citations/done/error`；`done/error` 置 `terminalReceived` 后忽略后续；流结束但没收到终止事件时抛 `STREAM_INCOMPLETE`。
- 不处理 `id:` 字段、不发 `Last-Event-ID`、不支持重连续传——与 Java 侧现状对等。
- 错误处理：非 2xx 时尝试读 `ApiResponse` JSON 取 `message`；`error` 事件抛 `KnowledgeStreamError(code,message)`。
- 证据：`ai-collab-frontend/src/modules/knowledge/knowledge-api.ts:68-159`（`:76-86` 请求、`:99-159` 流解析、`:107-141` 事件分发）。
- 渲染约定：Markdown 经 dompurify 净化后 v-html（AGENTS.md §7；`knowledge-render.ts` 为渲染层），P3 不改变该约定。

### 1.8 部署拓扑与网络边界

- Nginx：`/api/v1/` → `app:8080`（`proxy_buffering off`）；`/internal/` 直接 404；**没有任何 location 指向 worker**。`ai-collab-deploy/nginx/production.conf:22-36`（staging/local 同构）。
- worker 只在内网可达；P2 契约 §4.4 Q3 明确"前端看不到这个接口，Nginx 不对外暴露 worker"。
- Java 内网接口挂 `/internal/ai/**`，`InternalAiTokenFilter.PATH_SCOPES` 按路径登记必需 scope，未登记路径默认拒绝（`internal/ai/InternalAiTokenFilter.java:69-78`）。

### 1.9 P2 遗留偏差（供人工核对，不下结论）

| # | P2 契约陈述 | 代码事实 | 说明 |
|---|---|---|---|
| 偏-1 | §4.1 令牌与 scope 表列了 `task:{taskId}` / `index:document:{docId}` / `knowledge:search` / `index:admin` / `credentials:lease` / `usage:report`，**没有 `plan`** | worker 的 `/v1/rag/plan` 实际要求 scope=`plan`（`api/query_plan.py:126`），Java 由 `InternalTokenProvider.queryPlanToken(userId, projectId)` 签发（`internal/ai/token/InternalTokenProvider.java:25`） | 该 scope 来自 P1/W4 波次契约，先于 P2 冻结存在；P2 §4.1 未收录。是"契约未记载"还是"P2 有意收编为表外项"，需人工确认；若维持，建议在本文 §3.1 正式登记 |
| 偏-2 | §1.3 把 `EvidenceSufficiencyProbe` 列入"查询理解"面现状；§8 保留清单未提它 | Java `src/main` 已无此类；worker 有 `ai_collab_worker/retrieval/probe.py` | 这是 P2 按 §4.4"随执行器移植"执行后的正常结果，不是冲突；列出仅为对齐 §1.3 的历史陈述，P3 的迁移面（§7）不再包含它 |
| 偏-3 | §6 说 5 处复核点要改成 `documentId+chunkNo+contentHash(+bodySnapshotId)` 身份复核 | 切换包已实施：`KnowledgeCitationMapper.insertScoped` 写时复核（`:9-45`）、`listOwn` JOIN `document_body_chunk`（`:47-95`）、`KnowledgeHitVerifier` 已建（`retrieval/remote/KnowledgeHitVerifier.java:32-114`，指标 `knowledge.citation.rejected`） | 与 R3-1 一致（复核器已就绪），无冲突；P3 直接复用这套复核，不重建 |

## 2. 职责划分

### 2.1 迁移建议总表

| # | 能力 | 现状（Java 类） | P3 建议 | 依据 | 风险 |
|---|---|---|---|---|---|
| 1 | 查询路由（策略选择） | `QueryRouter` | **迁 Python** | 纯规则无 Java 特有依赖（§1.1）；P2 D2 已定；迁后与检索执行同进程，路由→执行零 HTTP 往返 | 39 题 E2 基线余量为 0（P2 §2），路由正则移植若引入行为差异直接掉分；必须逐字节移植 + §8 的等价测试 |
| 2 | 多轮消歧 | `QueryUnderstandingService`（静态实例） | **迁 Python** | 无任何 Spring/数据依赖（§1.1）；`KnowledgeConversationContext` 只喂历史消息 | 同上；历史消息输入形状要跨服务传递，事件契约里要带最近若干条消息 |
| 3 | 子查询分解 / 元数据过滤 | `MultiQueryDecomposer` / `MetadataFilterParser` | **迁 Python** | 与 QueryRouter 同构、无依赖 | 同 1 |
| 4 | `/v1/rag/plan` 调用 | `QueryPlanGatewayClient`（HTTP+Ed25519） | **进程内化**：worker 内 routing 服务直接调 `cognition/query`（QueryPlanService），删 Java 客户端 | 现状 LLM 调用本来就在 worker（§1.1）；迁后省一次 HTTP+令牌+deadline 往返 | worker 端点是否保留给评测/回滚 → §9 Q5 |
| 5 | 检索执行 | worker `/v1/knowledge/search`（Java 调） | **Python 进程内直调** `KnowledgeSearchPipeline`（问答场景）；HTTP 接口保留给 Java 侧调用方（Agent 工具/规划） | P2 Q3 定接口保留；问答迁到 worker 后自调 HTTP 是自找往返 | §9 Q3 拍板；注意进程内调用仍须走 `visibleDocumentIds` 由 Java 下发的口径（见 11 行） |
| 6 | 证据装配（阈值/预算/截断） | `KnowledgeContextBuilder`（0.15 准入分、5 源、2500 token、单文档 2 上限） | **迁 Python**（装配参数作为契约常量下发或冻结进 worker） | 装配直接决定提示词内容，与生成强耦合；`TokenCounter` 在 worker 需等价实现 | 阈值属 39 题 E2 口径一部分，动一行就可能掉分；冻结常量写进契约 |
| 7 | 提示词装配（转义/时间行/上下文前缀） | `KnowledgePromptAssembler` | **迁 Python**（模板本就在 worker；escapeXmlText + timeContext + 前缀规则随迁） | 装配点与模板分离是 P1/J2 的过渡形态；P3 合并 | §9 Q12；转义规则必须逐字节等价（防注入语义） |
| 8 | chat 模型调用（含流式） | Java `RoutingChatModelGateway.complete/completeStream` | **迁 Python**：kernel 新增流式 complete（`purpose=KNOWLEDGE_CHAT` 租借），worker 侧产出流 | P1 已建租借链路（QUERY_PLAN 同路由 KNOWLEDGE_CHAT）；共享/用户模型解析在 Java `CredentialLeaseService.lease()` 已就绪 | worker 需支持 OpenAI/Anthropic/Gemini 三家流式差异（AGENTS.md §6 Provider 差异红线）；usage 回报按 P1 §3 |
| 9 | 引用校验（`[Sn]` 对照/拒答判定） | `KnowledgeCitationValidator` | **迁 Python**（校验逻辑与生成同侧），Java 保留落库时复核 | 输入是 answer 文本 + sources 身份，无 Java 特有依赖 | `INSUFFICIENT_ANSWER` 文案是落库/前端契约，必须逐字不变 |
| 10 | 输出安全（k-gram 泄漏预检） | `KnowledgeOutputSafetyGuard` | **迁 Python**（流式预检必须在发送侧做；Java 中继不再二次缓冲） | 预检本质是"发送前"语义；若 Java 中继仍做缓冲预检则流式优势尽失 | k-gram 指纹与 `K_GRAM` 常量跨语言移植需等价测试（§8） |
| 11 | 可见性计算 / 引用身份复核 | `RemoteProjectKnowledgeSearch`（visible 集合）+ `KnowledgeHitVerifier` | **留 Java**：入站前算 `visibleDocumentIds` 下发；落库前按 P2 §6 复核 | P2 §3 硬约束 2/3；复核器已就绪（偏-3） | 若 Q3 选进程内直调，`visibleDocumentIds` 仍由 Java 在问答请求里下发，口径不变 |
| 12 | 引用/消息落库 | `KnowledgePersistenceService` + `insertScoped` | **留 Java**（Python finalize 回调，见 §2(b)） | P2 §3 硬约束"Python 不读写业务 schema"；`insertScoped` 的 SQL 级复核+FOR SHARE 已含撤权防护 | 见 §2(b) |
| 13 | SSE 对前端网关 | `KnowledgeController` + `SseEmitter` | **留 Java**（中继方案 §2(a)） | Q3 已定 worker 不对外；鉴权/限流/开关/会话校验都在 Java 入站侧 | 见 §2(a) |
| 14 | 会话 CRUD / 反馈 | `KnowledgeSessionApplicationService` / `KnowledgeFeedbackService` | **留 Java**（默认） | 纯业务 schema 读写，无 AI 参与 | §9 Q6 |
| 15 | 限流 / 系统开关 | `KnowledgeRateLimiter` / `SystemConfigService.isChatEnabled` | **留 Java**（入站前置） | 拒绝发生在分配任何资源之前，成本最低 | §9 Q7 |
| 16 | 调用日志 | Java `AiCallLogService` | **改 Python 上报为主**（`caller_type=PYTHON`，P1 §3 用量上报），Java 不再写问答链路日志 | P2 已建立 PYTHON caller 口径 | §9 Q13 |
| 17 | PromptPack 分发 | worker `/v1/prompts/**` + Java `RemotePromptCatalog` | Java 侧 `RemotePromptCatalog` 与 `PromptKeys` 中 `knowledge.*` 项随问答迁走而收缩；scenario/decide 等仍需 Java 拉取 | Agent/规划仍走 Java prompt | 删除清单见 §7 |

### 2.2 难题 (a)：SSE 流式在 Java 网关与 Python 之间如何中继

| 选项 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **A（推荐）：Java 中继 SSE** | 前端不变（POST fetch-stream → Java）。Java 收到问答请求后调 Python 新端点 `POST /v1/knowledge/qa:stream`（Python 返回 `text/event-stream` 或逐行 JSON 流），Java 逐事件转发 `token`；Python 流以 `finalize` 载荷结束（完整回答、引用身份、usage、promptKey/Version），Java 做引用复核 + 落库 + 写 audit，然后向前端发 `citations` + `done(messageId)` | 前端零改动；鉴权/限流/开关/会话校验留在 Java 入站；落库时序与现状完全一致（done 前落库，§1.3）；worker 保持不可对外（P2 Q3）；`X-Accel-Buffering: no` 约定不变 | Java 仍是长连接宿主（现状 2 线程/队列 32 的容量模型需复核，`KnowledgeStreamQuestionService.java:68-73`）；多一次 Java↔Python 转发跳 |
| B：Python 直连前端（Nginx 加 location → worker） | Nginx 新增 `location /ai/v1/ → worker:9100`，前端改调 worker；worker 校验用户 JWT 或 Java 签发的短期用户令牌 | Java 不占长连接；少一跳 | **违背 P2 Q3**（Nginx 不暴露 worker），需重开 P2 决定；鉴权/限流/会话属主校验在 worker 重复实现（要读业务 schema 校验会话，与 P2 硬约束冲突）；前端与 Java 侧错误契约（`ApiResponse` 信封）要在 worker 复刻 |
| C：轮询/无流式 | Python 只出同步接口，前端降级为等待完整回答 | 实现最简单 | 功能倒退，直接否决 |

**推荐 A**，理由：与 P2 Q3、§3 硬约束零冲突；事件形状（token/citations/done/error）对前端不变；落库复核天然回到 Java（难题 (b) 随之消解）。A 的契约核心是 **finalize 事件**：Python 流的最后一个事件必须携带落库所需的全部载荷，且 Java 必须校验 finalize 里累积的全文与逐 token 拼接一致（防中继丢字），见 §3.3。

### 2.3 难题 (b)：引用落库放谁手上

P2 §3 已定"Python 不读写业务 schema"，且 §9.2 用数据库角色 + 双侧越权测试固化。**评价：P3 应维持该约束**。理由：

1. 引用写入不是单纯 INSERT——`insertScoped` 是一条含 READY/未封锁/连接器授权/快照匹配复核的 SQL，并 `FOR SHARE` 锁行（§1.5）。把它搬到 Python 意味着 Python 要在业务 schema 上实现同等复核 SQL、复制连接器可见性语义，等于把 P2 隔离约束拆掉重来；
2. `knowledge_citation` 的外键（`project_document`）与 `ai_worker` 角色权限互斥（P2 §5.2），Python 直写需要大改 `db-init.sql` 与两侧越权测试基线；
3. 会话/消息/引用三表的生命周期管理（会话删除级联、V90 prompt 身份、touch 排序）都是业务语义，留在 Java 与 P4/P5 的 Agent 记忆、规划落库路径一致。

| 选项 | 描述 | 结论 |
|---|---|---|
| **1（推荐）：Python finalize 回调 Java 落库** | Python 流结束时把 `{answer 全文, insufficientEvidence, citations[](documentId+chunkNo+contentHash+bodySnapshotId+rank+similarity), usage, promptKey/Version, modelProvider/Model, latencyMs}` 发给 Java（中继流的 finalize 事件，或独立内部端点二选一，见 §9 Q2）；Java 复核（visible 集合 + `KnowledgeHitVerifier` 口径 + 全文一致性）后走现有 `saveExchange` | 引用复核、撤权竞态防护、`insertScoped` 写时复核全部原样复用；回滚语义与 P2 一致 |
| 2：Python 直写业务 schema | `ai_worker` 角色获得 `knowledge_*` 写权限 | 破坏 P2 硬约束 1 与 §9.2 越权测试基线；复核 SQL 双份维护。**不推荐** |
| 3：Java 聚合 token 流落库 | Python 只发 token，Java 拼全文 | Java 中继变成有状态聚合者，且全文一致性无法独立校验；不如选项 1 的 finalize 自带全文。**不推荐** |

### 2.4 难题 (c)：QueryRouter 的模型调用怎么走

**勘探结论：`QueryRouter` 不直接依赖 LLM**（§1.1 证据）。它对模型的唯一间接依赖是 COMPLEX 查询可选调用的 `/v1/rag/plan`，而该端点的模型调用已经在 worker 内完成（`QueryPlanService.plan → ModelKernel.complete`，`api/query_plan.py:89-96`）。因此"迁 Python 后模型调用怎么走"不存在租 Java 的选项：

- 迁移后 routing 服务与 QueryPlanService 同进程，`routeComplexWithOptionalQueryPlan` 的 HTTP+令牌+deadline 包装整体删除，改为直接调用（或合成一个服务）；
- 模型凭据仍按 P1 走租借：`CallScope(purpose=QUERY_PLAN)` → Java `/internal/ai/credentials/lease` → `LeasePurpose.QUERY_PLAN → ModelPurpose.KNOWLEDGE_CHAT` 路由（`LeasePurpose.java:21`）——这条链路 P1 已在用，P3 不改；
- 现状五重回退（disabled/client 缺失/身份缺失/网关异常/低置信度，`QueryRouter.java:209-245`）中前两类随进程内化消失，保留：身份缺失 → 本地规则（fail closed）、模型调用异常 → 本地规则、`confidence < 0.5` → 本地规则。语义与现状等价，回退行为写进 §8 等价测试。

## 3. 接口清单

### 3.1 令牌与 scope（在 P1/P2 §4.1 基础上扩展）

| scope | 方向 | 允许访问 | 额外 claim | 状态 |
|---|---|---|---|---|
| `knowledge:search` | Java → Python | `POST /v1/knowledge/search`（P2 §4.4，保留给 Java 侧 Agent/规划调用方） | `uid`、`pid`、`purp=["EMBEDDING","RERANK"]` | 已有，不变 |
| `plan` | Java → Python | `POST /v1/rag/plan` | `uid`、`pid` | 现状已存在但未登记进 P2 §4.1（偏-1）；若 Q4/Q5 决定删除 Java 客户端则此 scope 仅剩历史意义 |
| **`knowledge:qa`（新增，草案）** | Java → Python | `POST /v1/knowledge/qa:stream`（§3.3） | `uid`、`pid`、`purp=["KNOWLEDGE_CHAT"]` | 待 §9 Q14 拍板 |
| `credentials:lease` / `usage:report` | Python → Java | 沿用 P1 | — | 不变 |

- 问答入站令牌 `scope` 写法（草案）：`knowledge:qa knowledge:search credentials:lease usage:report`——若 Q3 选"进程内直调检索"，`knowledge:search` 仅在 Java 侧调用方保留，问答令牌可不带（避免把"可调用检索"与"可生成回答"耦合，**推荐不带**，最终以 Q14 决定为准）。
- worker 校验：`knowledge:qa` + `uid/pid` 与请求体一致；`purp` 用于租借授权（`CredentialLeaseService.authorize` 校验 `claims.purposes()` 含 purpose 名，`CredentialLeaseService.java:221-229`）。
- Java 签发：`InternalTokenProvider` 新增 `knowledgeQaToken(userId, projectId)`（对齐现有 `queryPlanToken`），`InternalAiTokenFilter` 不涉及（worker 端点不走 `/internal/ai/**`）。

### 3.2 `/v1/rag/plan`（现状冻结形状，P3 后去留见 Q5）

请求（Java → Python；现状 `QueryPlanGatewayClient.java:411-419` 与 worker `contract/query_plan.py` 一致）：

```json
{ "schemaVersion": 1, "query": "用户问题", "locale": "zh-CN",
  "historySummary": "可空，空则整体省略",
  "documentIds": ["调用方已限定范围"], "deadlineMs": 8000 }
```

响应（worker `parse_query_plan` 产出的契约；Java 侧校验见 `QueryPlanGatewayClient.java:201-238`）：

```json
{ "schemaVersion": 1, "rewrittenQuery": "≤4000 字",
  "subQueries": ["≤8 条、各 ≤500 字、去重"],
  "filters": { "documentIds": ["≤200"], "docTypes": ["≤10"], "dateRange": { "from": "ISO 日期|null", "to": "ISO 日期|null" } },
  "confidence": 0.0 }
```

- 错误：非 2xx 分类为 `TIMEOUT`(408/504) / `UNAVAILABLE`(429/5xx) / `HTTP`(其他)；传输异常分类 `TIMEOUT/UNAVAILABLE/UNEXPECTED`；响应违约 `SCHEMA`（`QueryPlanGatewayClient.java:362-395`）。**不重试**，调用方决定是否用本地结果。
- P3 后：`QueryPlanGatewayClient` 删除（§7）；worker 端点是否保留 → §9 Q5（保留则现状形状冻结不变，仅调用方消失）。

### 3.3 问答生成接口草案（Python 提供，`POST /v1/knowledge/qa:stream`）

> 形状为**草案**，随 Q1/Q2/Q14 的拍板调整；`schemaVersion` 从 1 起。

请求（Java 中继 → Python）：

```json
{
  "schemaVersion": 1,
  "requestId": "uuid",
  "projectId": "uuid",
  "userId": "uuid",
  "sessionId": "uuid",
  "question": "≤2000 code points，非空",
  "documentIds": ["≤20、去重，可空"],
  "visibleDocumentIds": ["由 Java 计算并求交后的可见集合，≤5000"],
  "topK": 8,
  "history": [ { "role": "USER|ASSISTANT", "content": "...", "insufficientEvidence": false, "createdAt": "ISO" } ],
  "deadlineMs": 90000
}
```

- `history` 由 Java 从 `repository.recentContext` 读取后下发（现状 `KnowledgeConversationContext.from` 的输入，`KnowledgeQuestionApplicationService.java:103-104`）；条数/预算裁剪在 Python 侧按 `QueryUnderstandingService` 现规则执行（5 turn / 3500 字符）。
- `visibleDocumentIds` 沿用 P2 §4.4 语义：空集合 → 直接走"证据不足"路径，**不能理解成全项目**；上限 5000。
- `sessionId` 仅作关联日志与调用追溯；**worker 不校验会话属主**（属主校验在 Java 入站，P2 隔离约束的延伸），这是"Python 不读业务 schema"的直接推论。

响应：`text/event-stream`（或逐行 NDJSON，Q1 附带决定），事件序列固定为：

| 顺序 | 事件 | 载荷 | 说明 |
|---|---|---|---|
| 0..n | `token` | `{"text": "增量"}` | Python 侧已完成 k-gram 泄漏预检（职责 10） |
| n+1 | `finalize` | 见下 | **终止性事件之一**；Java 落库成功后才发 done |
| 或 | `error` | `{"code": "...", "message": "..."}` | 终止性；错误码沿用 `ErrorCode` 命名 + P2 §4.4 的检索错误码 |

`finalize` 载荷（草案）：

```json
{
  "requestId": "uuid",
  "answer": "与逐 token 拼接逐字节一致的全文",
  "insufficientEvidence": false,
  "citations": [ { "documentId": "uuid", "chunkNo": 12, "contentHash": "sha256-hex",
                   "bodySnapshotId": "uuid", "rank": 1, "similarity": 0.83, "quote": "≤1000 code points" } ],
  "retrieval": { "strategy": "DENSE_WITH_BOUNDED_FALLBACK", "effectiveQuery": "改写后查询",
                 "routeReason": "...", "subQueries": ["..."] },
  "prompt": { "key": "knowledge.system", "version": "vN" },
  "model": { "provider": "...", "name": "...", "latencyMs": 1234, "promptTokens": 100, "completionTokens": 50 }
}
```

- `answer` 一致性：Java 校验 finalize 全文与其转发的 token 拼接**逐字节相等**，不等 → 丢弃不落库并向客户端发 `error(INTERNAL_ERROR)`（防中继丢字导致历史会话与实际回答不一致）。
- `citations` 身份即 P2 §6 引用身份 + 排名/相似度/quote；Java 落库前按 P2 §6 六条复核（复用 `KnowledgeHitVerifier` 口径），全部被拒时按"证据不足"落库（对齐现状空引用路径 `KnowledgeQuestionApplicationService.java:119-127`）。
- `insufficientEvidence=true` 时 `citations` 为空、`model` 为 null（现状未调模型轮次的语义，`KnowledgePersistenceService.java:29-34`）。
- 错误码表（Python → Java）：沿 P2 §4.4 风格——401 `UNAUTHORIZED` / 403 `qa` 令牌不符 / 400 `VALIDATION_ERROR`（超限、未知字段）/ 503 `AI_PROVIDER_UNAVAILABLE`（租借失败，含系统开关关闭时 Java 侧早已拦截，Python 不重复判断）/ 504 `QA_DEADLINE_EXCEEDED` / 5xx `INTERNAL_ERROR`。Java 对 503/5xx 重试 1 次（生成非幂等，**重试必须终止上一条流**，前端表现与现状"连接断开重试"一致）。

### 3.4 会话接口（不变）

`/api/v1/projects/{projectId}/knowledge/sessions` 全部端点维持现状（§1.2 Controller 清单）；前端零改动。唯一变化是 `POST /{sessionId}/questions` 与 `/stream` 的**实现**从 Java 服务换成 Java 中继（Q1=A 时连 URL 都不变）。

### 3.5 内部维护接口（新增，供对账）

- `GET /v1/knowledge/qa/capabilities`（草案）：返回 worker 问答能力开关与冻结常量（`MIN_SIMILARITY`、`MAX_SOURCES`、`PER_DOCUMENT_SOURCE_CAP`、token 预算、k-gram），供 Java 侧健康检查与 §8 对账。幂等只读。

## 4. 数据库

- **无新表、无 Flyway 迁移**（默认方案）。`knowledge_session/message/citation` 三表属主、外键、V90/V106 语义全部不变；`ai_credential_lease.purpose` 的 CHECK 已含 `KNOWLEDGE_CHAT`（`V104__p1_ai_kernel_lease_and_usage.sql:18-21`），无需 V107。
- 若 Q2 选"Python 直写业务 schema"（不推荐），才需要 V107 调整角色授权——该选项同时触发 P2 §9.2 越权测试基线重写，成本最高。
- `ai_call_log`：问答链路的 chat 调用经 P1 用量上报写入，`caller_type='PYTHON'`；Java 侧 `AiCallLogService` 在问答链路的写点删除（§7）。`ai_credential_lease` 继续记录 `purpose=KNOWLEDGE_CHAT` 租借。
- Alembic 侧无变更（worker 不新增存储；问答是无状态流式服务）。

## 5. 引用与会话

### 5.1 引用（迁移后）

1. 生成：Python 在回答文本里产出 `[Sn]` 标记并在 finalize 里给出 `citations[]`（身份 = `documentId + chunkNo + contentHash + bodySnapshotId`，P2 §6/V106）。
2. 复核（Java）：finalize 到达后按 P2 §6 六条复核（visible 集合、READY/未封锁/可读、快照相等、`document_body_chunk` 哈希相等、`chunkRefs` 逐行、fail-closed 丢弃计数）；撤权竞态防护由 `insertScoped` 的 `FOR SHARE` 复核兜底（§1.5），与现状双保险结构一致。
3. 落库（Java）：`KnowledgePersistenceService.saveExchange` 原样保留——USER/ASSISTANT 两条消息 + 逐条引用 + touch；`promptKey/promptVersion` 取 finalize 的 `prompt` 字段（V90 语义不变）。
4. 展示：`KnowledgeCitationView` 组装仍在 Java（文件名回填、chunkId 历史字段等业务字段不进 worker）。

### 5.2 会话（迁移后）

- 会话属主校验（`findOwn`/`lockOwn`）、`requireMember`、限流、系统开关、CRUD/反馈全部留在 Java 入站侧（§2.1 行 13-15）；worker 假定请求已通过全部校验，只校验令牌。
- `history` 快照由 Java 在请求前读取；问答过程中 Python 不读会话表（隔离约束）。一轮交换的持久化只在 finalize 之后发生一次，中途断连/取消不落库（对齐现状 §1.3"不持久化未完成的交换"）。

## 6. 切换方案（一次性，不留开关）

与 P2 §7 同构：不做 shadow、不做双写、不做进程内回落。要删除的是 §7 清单里的 Java 问答/理解代码与 `QueryPlanGatewayClient`。

**部署前置检查（运维只读执行，任一不满足即停止）：**
1. P2 的全部前置检查仍然成立（pgvector ≥0.8、db-init 角色、`AI_INDEX_DATABASE_URL`、对账基准）——P3 上线时 P2 已在生产，只需复跑第 1、2 条确认无漂移；
2. worker 版本含问答端点且 `/readyz` 通过；Java 版本含中继客户端与新 scope 签发；
3. staging 已配 `query.plan.enabled=true` 的等价配置下 `/v1/rag/plan` 进程内路由生效（对比记录一次 COMPLEX 查询的 outcome=ADOPTED 日志）；
4. 记录切换前 7 天知识问答调用量与平均流式时长，作为容量对照（现状线程池 2/32 是 §2.2 方案 A 的容量输入）。

**切换步骤（staging）：**
1. 进入维护模式，关闭问答入口（会话 CRUD 可用）；`pg_dump` 全库备份并记录 SHA-256；
2. 部署新版本（Java：中继 + finalize 复核落库 + scope 签发；Python：routing 服务 + qa:stream + kernel 流式 + 装配迁入）。本版本已删除 §7 清单代码；
3. 冒烟：同步 ask、流式 ask（含 token/citations/done 全事件序）、断连取消、限流命中、系统开关关闭、`INSUFFICIENT_ANSWER` 短路、泄漏预检（构造触发样本）各一次；
4. 抽样 10 轮问答，对照切换前同问题（冻结题集）比较回答与引用一致性；
5. 跑 §8 质量门与越权冒烟，退出维护模式。

**回滚（不靠开关）：**
- 代码回退到切换前 `dev` 提交并重新部署。三表结构无变化（无新迁移），旧代码直接可跑；
- 回滚期间新产生的问答数据与现状兼容（表结构未动），无 P2 式"引用失效"损失；
- worker 侧新增端点成为死代码，无害；`knowledge:qa` scope 令牌无人校验，无害；
- 问答链路中断窗口 = 维护模式时长。

## 7. P3 删除清单（Java）

**查询理解（P2 §8 保留到 P3 的部分）：**
- `retrieval/query/QueryRouter.java`、`QueryUnderstandingService.java`、`MultiQueryDecomposer.java`、`MetadataFilterParser.java`；
- `retrieval/queryplan/` 整目录（`QueryPlanGatewayClient`、`QueryPlanConfiguration`、`QueryPlanProperties`、`QueryPlanRequest/Response`、`QueryPlanGatewayException`）；
- `retrieval/query/` 其余模型类随用途归并（`StructuredQuery`、`QueryRouteDecision`、`RetrievalStrategy`、`QueryComplexity`、`QueryIntentType`、`QueryUnderstandingResult`、`ConversationMessage`、`ConversationTurn`、`MetadataFilter`、`FilterOperator`、`DocumentFilterCriteria`）——注意 `RetrievalStrategy` 名称仍是 `/v1/knowledge/search` 协议字段（P2 §4.4），Java 侧 `KnowledgeSearchModels` 保留同名枚举；
- `KnowledgeConversationContext`（适配层随两侧一起消失：history 下发后消歧在 worker）。

**问答生成：**
- `KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService` 的编排体（`ask`/`askStream` 主逻辑）；`KnowledgeController` 的两个问答端点改为中继实现（Controller 不删）；
- `KnowledgePromptAssembler` + `KnowledgePromptText` + `PromptKeys` 中 `knowledge.system/user` 两项 + `RemotePromptCatalog` 的对应预取项（scenario/decide 等其余 key 保留）；
- `KnowledgeContextBuilder`、`KnowledgeCitationValidator`、`KnowledgeOutputSafetyGuard`（校验逻辑迁 Python；`INSUFFICIENT_ANSWER` 文案常量移到共享契约）；
- `RoutingChatModelGateway`/`OpenAiCompatibleChatModelGateway` 的**问答调用点**删除；网关本体保留（Agent/规划/能力探测仍用，P4/P5 处理）；
- `KnowledgeVisibilityGuard`、`KnowledgeRateLimiter`、`KnowledgeSessionApplicationService`、`KnowledgeFeedbackService`、`KnowledgePersistenceService`、`KnowledgeCitationMapper`、`KnowledgeHitVerifier`：**保留**（§2.1 行 11-15）。

**删除后的证明（grep 断言，对齐 P2 §8 风格）：** `src/main` 不再出现 `QueryRouter`、`QueryPlanGatewayClient`、`query.plan.enabled`、`KnowledgeOutputSafetyGuard`、`KnowledgePromptAssembler`、`completeStream`（问答调用点）、`ModelPurpose.KNOWLEDGE_CHAT`（Java 侧仅剩租借路由枚举定义）。

## 8. 验收（P3 门）

### 8.1 质量门

- **检索层回归（硬门槛）**：E2 39 题评测以现 harness 重跑（`test(p2-e2)` 提交已建索引 harness 与评测报告），**门槛 ≥52/54 维持**（P2 §9.1 Q12 口径）。P3 改动了路由/消歧/过滤解析，这是最可能掉分的层。
- **问答层指标（新增，选项见 Q11）**：
  - 选项 A：E2 只保检索口径，问答层新造 **N 面**面试指标并如实记录不卡门——引用忠实度（引用身份与回答 `[Sn]` 的一致率）、拒答正确率（空证据轮必须 `insufficientEvidence=true`）、泄漏率（必须 0，硬门槛）、流式完整性（finalize 全文 = token 拼接，必须 100%，硬门槛）；
  - 选项 B：新造小规模回答质量评测集（人工/模型评审），成本高、主观性强，P3 不建议作为门槛。
- 每轮如实记录进 `docs/benchmark/`（沿用 P2 §9.1 纪律）；报告标注冻结常量与模型配置。

### 8.2 流式与断线

1. 事件序：正常流严格 `token* → citations → done`；错误流 `error` 终止且客户端收到后忽略后续（前端现状语义）；
2. 断线：token 中途断开 → 无落库（`knowledge_message` 无新行）、worker 侧流被取消（租借释放、用量按 CANCELED 上报）；
3. **Last-Event-ID 续传**：现状无（§1.3）。Q8 决定本期是否实现；若选"不实现"，把"断线即本轮作废"写成已知限制并验证重试路径幂等可接受；
4. 取消竞态：done 事件与断连竞争时，落库与发 done 至多一个发生（对齐现状 `terminal` CAS 语义，`KnowledgeStreamQuestionService.java:309-313`）；
5. finalize 一致性：构造中继丢字场景，Java 必须 `error(INTERNAL_ERROR)` 且不落库。

### 8.3 越权与隔离

1. 数据库角色不变：`ai_worker` 仍不能读 `knowledge_*`（复跑 P2 §9.2 第 1 组断言）；
2. 令牌：无 `knowledge:qa` scope → 401；`uid/pid` 与请求体不符 → 403；空 `visibleDocumentIds` → finalize 的 insufficientEvidence 路径且不发起检索租借；
3. 引用复核：finalize 里混入集合外 documentId / 过期 bodySnapshotId / 不存在 chunkNo / 哈希不符的引用 → Java 全部丢弃、计数（`knowledge.citation.rejected`）、按证据不足落库；
4. 撤权竞态：检索后、finalize 落库前封锁文档 → `insertScoped` 0 行 → 该引用不落库（现状语义保持）；
5. 租借：`KNOWLEDGE_CHAT` 租借失败 → 503 `AI_PROVIDER_UNAVAILABLE`，不回落 Java 网关。

### 8.4 查询理解等价（对应 §2.1 行 1-4 的风险）

1. 路由决策等价：对冻结题集 39 题逐题比对迁移前后 `strategy/effectiveQuery/subQueries/filters`（导出 Java 侧金标准，Python 逐题比对，方法同 P2 §9.3 第 7 条）；
2. 多轮消歧等价：构造 FOLLOW_UP/TOPIC_SWITCH/STANDALONE 样本比对 rewrittenQuery 与 contextPrompt 逐字节一致；
3. `/v1/rag/plan` 回退等价：模型超时/异常/低置信度 → 本地规则结果，与现状 `routeComplexWithOptionalQueryPlan` 各分支一致。

### 8.5 其他

- 后端 `mvnw verify`、前端四件套、Python 全量 pytest 通过；`test_forbidden_dependencies.py` 白名单不放宽；
- 全文转义等价：`escapeXmlText`/k-gram 指纹/时间行格式逐字节对拍（安全语义不能漂移）。

## 9. 待决问题清单（需用户逐项拍板）

| # | 问题 | 选项 | 推荐 |
|---|---|---|---|
| Q1 | SSE 中继方案（§2.2） | A. Java 中继（前端/鉴权/落库零移位）；B. Nginx 直连 worker（需重开 P2 Q3）；C. 降级无流式 | **A** |
| Q2 | 引用与消息落库归属（§2.3） | 1. Python finalize 回调 Java 落库（finalize 作为流事件 vs 独立内部端点两个子选项）；2. Python 直写业务 schema（破坏 P2 硬约束）；3. Java 聚合 token 落库 | **1**，且 finalize 走流内事件（少一个内部端点、少一份令牌面） |
| Q3 | 问答对检索的调用方式（§2.1 行 5） | a. worker 进程内直调 `KnowledgeSearchPipeline`（问答流量不再自调 HTTP）；b. 仍走 `POST /v1/knowledge/search` 自调用 | **a**（省往返与令牌面；接口本身按 P2 Q3 保留给 Java 调用方） |
| Q4 | 查询理解迁移边界（§2.1 行 1-2） | a. `QueryRouter` + `QueryUnderstandingService` 全迁；b. 消歧留 Java（history 在 Java 侧处理） | **a**（消歧输入是纯消息文本，无 Java 依赖；留 Java 会把 history 语义劈成两半） |
| Q5 | worker `/v1/rag/plan` 端点去留（§3.2） | a. 保留（评测与未来调用方可复用，无维护负担）；b. 随迁移删除 | **a**（端点 + parser 已测试过，删除收益为零） |
| Q6 | 会话 CRUD/反馈是否迁 Python（§2.1 行 14） | a. 留 Java；b. 迁 Python（需直写业务 schema） | **a**（纯业务读写，与 AI 无关，迁移零收益） |
| Q7 | 限流与系统开关执行层（§2.1 行 15） | a. 全部 Java 入站前置；b. 限流下沉 worker | **a**（拒绝要发生在分配资源前；worker 侧无需 Redis 限流新面） |
| Q8 | 流式断线续传（§1.3、§8.2） | a. 本期不实现，登记已知限制；b. 本期实现（事件 id + Java 缓冲重放 + Last-Event-ID） | **a**（前端现状无续传语义，续传需重放已生成 token + 引用状态机，复杂度进入 P4 更合适；若要 b 需先定重放缓冲的存储与 TTL） |
| Q9 | 输出安全预检位置（§2.1 行 10） | a. 全迁 Python（发送侧预检，Java 转发不缓冲）；b. Python 预检 + Java 终检双保险 | **a**（Java 若再缓冲整流，方案 A 的流式收益归零；`insertScoped` 与落库复核已兜住数据面） |
| Q10 | 检索引用复核位置 | a. 维持 Java（visible 下发 + `KnowledgeHitVerifier` + `insertScoped`，P2 已建）；b. 复核迁 Python | **a**（复核器已就绪且有越权单测，偏-3；P3 不重建） |
| Q11 | 问答质量门（§8.1） | A. E2 检索门槛 + 新 N 面指标（引用忠实度/拒答正确率记录、泄漏率与流式完整性硬门槛）；B. 另造回答质量评测集作门槛；C. 本期无问答层门槛 | **A**（主观质量评测不适合做冻结门槛；泄漏率/流式完整性是确定性断言，可硬卡） |
| Q12 | 提示词装配归属（§1.4、§2.1 行 7） | a. 装配整体迁 Python（转义/时间行随迁，Java 只发原始材料）；b. Java 保留装配（继续从 RemotePromptCatalog 拉模板、拼好 user payload 下发） | **a**（模板在 worker、生成在 worker，装配留在 Java 只剩跨语言搬运；b 的唯一好处是回滚面小） |
| Q13 | 问答链路调用日志归属（§2.1 行 16） | a. Python 用量上报为主（`caller_type=PYTHON`），Java 删除问答 `aiLogs` 写点；b. 双写（Java 中继也记一条） | **a**（P1 用量链路已带 lease 关联与 token 数，双写必然计数不一致） |
| Q14 | 新 scope 设计（§3.1） | a. 新增 `knowledge:qa`，问答令牌不带 `knowledge:search`；b. 复用 `knowledge:search` 一个 scope 通吃；c. 新增并连带把 `plan` scope 补登进令牌表（修偏-1） | **a**，并顺手做 c（`plan` 已在代码存在，登记是零成本修正） |

## 附：与上位设计的冲突点（冻结时需在修订节汇总）

1. 设计 §4.4 预告的 `GET /internal/ai/projects/{p}/visible-documents`（P2 §4.5 推迟到"对应阶段契约另定"）：Q3 选 a 时 P3 仍不需要（visible 由 Java 在请求体下发），建议明确"P4 若 worker 主动拉取再实现"，避免悬置。
2. 设计把"知识问答"整体划入 Python 分阶段目标；本文把会话 CRUD/限流/反馈留在 Java——这是对"整体"的收窄，冻结时应在修订节写明边界。

---

## 10. 决议记录（FROZEN v1）

**P3-D1（2026-11-28，用户拍板）**：§9 的 Q1–Q14 **全部按各题推荐选项执行**：

| 问题 | 决议 |
|---|---|
| Q1 SSE 中继 | A：Java 中继（前端/鉴权/落库零移位） |
| Q2 引用与消息落库 | 1：Python finalize 回调 Java 落库，finalize 走流内事件 |
| Q3 检索调用方式 | a：worker 进程内直调 `KnowledgeSearchPipeline`；HTTP 接口按 P2 Q3 保留 |
| Q4 查询理解边界 | a：`QueryRouter` + `QueryUnderstandingService` 全迁 |
| Q5 `/v1/rag/plan` | a：端点保留 |
| Q6 会话 CRUD/反馈 | a：留 Java |
| Q7 限流与开关 | a：全部 Java 入站前置 |
| Q8 断线续传 | a：本期不实现，登记已知限制（复杂度进 P4 再议） |
| Q9 输出安全预检 | a：全迁 Python，Java 转发不缓冲 |
| Q10 引用复核位置 | a：维持 Java（`KnowledgeHitVerifier` + `insertScoped`） |
| Q11 质量门 | A：E2 检索门槛 + 泄漏率/流式完整性硬门槛 + 忠实度/拒答率记录 |
| Q12 提示词装配 | a：整体迁 Python |
| Q13 调用日志 | a：Python 用量上报为主（`caller_type=PYTHON`），删 Java 问答 `aiLogs` 写点 |
| Q14 scope | a：新增 `knowledge:qa`，并连带补登 `plan` scope（修 P2 偏-1） |

**附：上位设计冲突点处置**（对应附录两条）：visible-documents 端点维持"P4 若 worker 主动拉取再实现"；知识问答的会话 CRUD/限流/反馈边界永久留在 Java，已在 §0 范围表写明。

---

## 11. 修订记录

### P3-R1（2026-11-28，阶段 3 集成验证执行中裁决）：qa 令牌 purp 扩展

- **问题**：§3.1 表格把 qa 令牌 `purp` 冻结为 `["KNOWLEDGE_CHAT"]`，但 Q3=a（进程内直调 `KnowledgeSearchPipeline`）使 QA 管线必然触发 `EMBEDDING`（rerank 绑定时还有 `RERANK`）租借；Java `CredentialLeaseService.authorize` 按 `purp` 校验 → 403 `lease_forbidden`，QA 链路整体阻断（E2 实测复现）。
- **裁决**：`issueKnowledgeQaToken` 的 `purp` 修订为 `["KNOWLEDGE_CHAT","EMBEDDING","RERANK"]`，与 P2 检索令牌的租借面对齐。`scope` 仍不带 `knowledge:search`（Q14 的"检索与生成解耦"指 HTTP 端点访问权，租借 purpose 是资源授权，两个机制分离）；§3.1 表格与 §3.3 草案说明以本修订为准。
- **影响**：Java 签发面（单处）；worker 不校验 `purp`（`TokenVerifier` 只校验 scope），无 Python 侧改动。

### P3-R2（2026-11-28，同日裁决）：代际绑定 EMBEDDING 租借接受 knowledge:qa scope

- **问题**：P2 切换"按代际绑定 embedding 租约"（`ca403772`）后，`CredentialLeaseService.authorize` 对**带 `generationId` 且无 `taskId`** 的 EMBEDDING 租借要求令牌含 `knowledge:search` scope（该语义当时唯一调用方是检索端点）。QA 管线（Q3=a）的进程内检索同样按代际绑定租借（worker 侧 `_check_model` 强制代际/模型一致，不可省略 generationId），但 Q14=a 冻结 qa 令牌不带 `knowledge:search` → 403 `generation_not_allowed`，QA 链路阻断（E2 实测复现，独立于 P3-R1 的 purp 缺口）。
- **裁决**：authorize 对（generationId ≠ null ∧ taskId == null）的 EMBEDDING 租借接受 `knowledge:search` **或** `knowledge:qa` scope。代际绑定是模型解析语义（worker 强制一致性校验），不是端点访问权；qa 管线是 Q3=a 决议下的合法调用方。
- **影响**：Java authorize 单处条件；检索端点语义不变（仍只接受 knowledge:search）。
