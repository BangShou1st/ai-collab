# AI Collab：AI 能力服务化（Java 业务控制面 + Python AI 服务）与 Redis / MQ 收敛设计

> **文档状态**：APPROVED FOR IMPLEMENTATION
> **编制日期**：2026-11-25
> **源码基线**：`dev @ 1aa53991`，Flyway `V1–V103`，下一迁移版本 **`V104`**
> **前置文档**：[architecture-fusion-and-maintenance-2026-11-24.md](architecture-fusion-and-maintenance-2026-11-24.md)（其中「Staging 不运行 RocketMQ」的决定被本设计 §9 推翻）

---

## 0. 一句话

**所有 AI 推理都在 Python，Java 只管业务数据、权限、状态机和 AI 治理。**
Python 决定「怎么想」，Java 决定「能不能做、做了算不算数」。

每个能力只有一个负责方、一条主路径，**不再保留 java/python 二选一开关和 shadow 对比**。

## 1. 现状（2026-11-25 代码盘点）

| 领域 | 现状 | 问题 |
|---|---|---|
| 模型调用 | Java 统一出口 `infrastructure/ai/ChatModelGateway`、`turn/ModelTurnGateway`，四类适配器（OpenAI 兼容、Anthropic、Gemini、ModelBridge），共约 40 个类 | AI 推理和业务混在 Java |
| 密钥 | `ModelSecretCipher`（AES-GCM，`MODEL_CONFIG_MASTER_KEY`）；Python `config.py` 拒绝任何 `*_API_KEY` | Python 只能经 `InternalModelCallController` 回调 Java，而且只支持非流式 `complete` |
| RAG | Java：Tika → `DocumentChunker` → `ProjectEmbeddingGateway` → `document_chunk`（V1，`embedding vector` 不定长，**没有向量索引**） | 精确扫描，规模上去后慢；检索逻辑分散在三条路径 |
| Agent | `AgentRuntimeCoordinator`（1717 行）里推理和租约、恢复、审批、委派混在一起；17 个工具；`NativeToolCallingExecutor` | 推理与控制面耦合 |
| 规划 | `TaskPlanGenerationOrchestrator` 内联四阶段提示词 | 同上 |
| Python worker | FastAPI + httpx，**没有 LangGraph**；端点有 decide / jobs / planning / prompts / query_plan / rerank / MCP host；默认大多走 java 模式或 shadow | 在主链路上几乎不干活 |
| 开关 | `RERANK_MODE`、`DECIDE_MODE`、`PLANNING_DRAFT_MODE`、shadow、`INTERNAL_AI_TOKEN_MODE`（static/ed25519）、MQ 回落等 | 组合多，说不清线上走的是哪条路径 |
| MQ | 只有文档链路用事务消息；规划和 Agent 只发普通通知；staging 没有 RocketMQ | MQ 在线上没有证明过任何东西 |
| 前端 | SSE 两条：知识问答流、Agent 事件流（`Last-Event-ID`）；规划用轮询 | 接口路径本次**保持不变** |
| Staging | `box-aae54f`，8 GB，已用约 2.2 GB；单后端实例 | 多实例相关设计（分布式锁、跨实例 SSE）没有落地基础 |

## 2. 已拍板的决策（实施中不再分叉）

1. **密钥：凭据租借。** Java 继续加密保管；Python 按次向 Java 租用短期凭据，只放在内存里（§4.2）。
2. **RAG 数据：按 schema 划分归属。** Python 独占 `ai_index` schema，用独立数据库角色，读不到业务表；可见范围由 Java 每次传入（§5）。
3. **Agent：** Java 现有推理循环**整体删除**。推理改用 Python LangGraph，Java 只保留控制面（§7）。
4. **规划：** 生成迁到 Python，前端继续轮询（§8）。
5. **Java → Python 任务派发用 Redis Stream**，Java 内部事件用 RocketMQ 事务消息（§6、§9）。
6. **切换策略：** 每个阶段整体切换，同一阶段内删除旧实现，不留并行路径和模式开关。

## 3. 目标架构

```text
浏览器
  │  REST / SSE（路径不变）
  ▼
Nginx ──► Java app-1 / app-2（业务 + AI 控制面）
            │  ├─ PostgreSQL 业务 schema（项目、任务、文档元数据、会话、Agent run、规划、审计）
            │  ├─ Redis（缓存、Redisson、Stream、Pub/Sub、ZSET/Hash/HLL、限流）
            │  ├─ RocketMQ（文档注册、项目事件，均为事务消息）
            │  └─ MinIO（文档原件）
            │
            │  Redis Stream 派发长任务 ▼        ▲ 内部 HTTP（Ed25519 短令牌）
            │                                   │
            └─ 同步流式 HTTP ──► Python ai-worker（FastAPI + LangGraph）
                                   ├─ PostgreSQL ai_index schema（chunk、向量、HNSW）
                                   ├─ PostgreSQL ai_runtime schema（LangGraph checkpoint）
                                   ├─ 模型厂商（凭据由 Java 租借）
                                   └─ reranker（llama.cpp，已有容器）
```

| Python（AI 推理，不碰业务表） | Java（业务 + AI 控制面） |
|---|---|
| 模型客户端：四类厂商、流式、工具调用 | 模型 / 提供商配置的增删改查，密钥加密保管，凭据租借 |
| 切块、向量化、`ai_index` 索引维护 | Tika 解析、文档元数据、正文 `document_body`、权限 |
| 检索（Dense 优先 + 有界回退、词法、RRF）、rerank、查询改写、问答生成 | 问答会话落库、SSE 对外出口、限流、引用复核 |
| Agent 图（LangGraph）、子 Agent、上下文摘要、记忆候选抽取 | run 状态机、租约与 epoch、配额、工具执行、写操作审批、事件落库与重放 |
| 规划生成（骨架 → 细节 → 修复 → 反思） | 规划状态、版本、尝试代次、Schema 校验、确认落库 |
| 提示词（统一由 Python 管理） | 用量与成本记账（`ai_call_log`） |

## 4. Java ↔ Python 契约

### 4.1 鉴权

- 只保留 **Ed25519 短令牌**，删除 static 模式。
- 令牌声明包含 `iss`、`aud`、`exp`（≤ 5 分钟）、`scope`（如 `agent:run:{runId}`、`knowledge:answer`、`plan:attempt:{attemptId}`、`index:document:{docId}`），以及 `userId` 和 `projectId`。
- Java 内部接口按 `scope` 做最小授权，令牌不能跨 run 或跨项目使用。

### 4.2 凭据租借

- `POST /internal/ai/credentials/lease`，请求体 `{purpose, userId, projectId, runId?}`。
- Java 复用现有路由解析：用户自有提供商 → 共享提供商 → 能力分配。校验调用者是项目成员、用途允许后，解密并返回：

  ```text
  {leaseId, provider, baseUrl, model, apiKey, params, expiresAt(≤5min)}
  ```

- Python 侧约束：
  - 凭据只放在进程内存，按 `leaseId` 缓存到过期；
  - 不写盘、不进 LangGraph checkpoint、不进日志（structlog 加脱敏处理器）；
  - 继续拒绝 `*_API_KEY` 环境变量。
- 删除 `InternalModelCallController` 这条模型代理路径。

### 4.3 用量回报

- `POST /internal/ai/usage`，批量上报：

  ```text
  {callId, leaseId, purpose, model, inputTokens, outputTokens, latencyMs, status}
  ```

- Java 写入 `ai_call_log`，按 `callId` 幂等。
- 指标带 `purpose` 和 `caller_type` 标签。

### 4.4 Java 提供给 Python 的内部接口

| 接口 | 用途 |
|---|---|
| `POST /internal/ai/tasks/{taskId}/claim` `{taskType, epoch, workerId}` | 条件更新认领，失败即放弃 |
| `POST /internal/ai/tasks/{taskId}/heartbeat` | 续租；响应里携带 `cancelRequested` |
| `POST /internal/ai/tasks/{taskId}/fail` | 失败上报 |
| `GET /internal/ai/projects/{p}/visible-documents?userId=` | 可见文档 ID 集合（ACL 由 Java 计算） |
| `GET /internal/ai/documents/{id}/body?cursor=` | 分页读取解析后的正文，用于建索引 |
| `GET /internal/ai/agent/runs/{runId}/context` | 会话消息、页面上下文、项目记忆、工具清单（含 JSON Schema） |
| `POST /internal/ai/agent/runs/{runId}/tool-invocations` | 工具调用（§7.3） |
| `POST /internal/ai/agent/runs/{runId}/events` | 批量事件（§7.4） |
| `POST /internal/ai/agent/runs/{runId}/complete` | run 结束 |
| `POST /internal/ai/plans/{planId}/attempts/{attemptId}/progress` · `/result` | 规划进度与结果 |
| `POST /internal/ai/documents/{id}/index-result` | 索引完成或失败 |

### 4.5 Python 提供给 Java 的接口

| 接口 | 用途 |
|---|---|
| `POST /v1/knowledge/answer:stream` | 同步流式问答，NDJSON：`delta` / `sources` / `done` / `error` |
| `GET /healthz` · `/readyz` · `/metrics` | 已有 |

## 5. RAG：迁到 Python，并补上 HNSW

### 5.1 数据归属

- 部署初始化脚本创建两个角色：`ai_worker`（只拥有 `ai_index` 和 `ai_runtime`）和 Java 业务角色（不授予 `ai_index` 权限）。
- Python 用 Alembic 管理自己的表，Java Flyway 不碰这两个 schema。
- `ai_index` 下的表：
  - `index_generation(id, project_id, embedding_model, dimension, status, created_at)`
  - `chunk(id, project_id, document_id, generation_id, chunk_no, chunk_type, parent_id, heading, content, content_hash, embedding vector(1024), created_at)`

  索引：
  - `HNSW (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)`
  - `GIN (content gin_trgm_ops)`
  - `(project_id, document_id)`
- 维度固定为当前 embedding 模型的维度（qwen3-embedding:0.6b → 1024）。换模型时新建 generation 并重建索引。

### 5.2 写入链路

1. RocketMQ `document-registered`（事务消息，已有）→ Java 消费者完成 Tika 解析，写入 `document_body`。
2. Java 在提交后执行 `XADD aicollab:ai:tasks {type=INDEX_DOCUMENT, taskId, epoch}`。
3. Python 认领任务，分页拉取正文，切块（迁移现有 `DocumentChunker` 规则：TARGET=1000、OVERLAP=120、按章节切分），批量向量化，同一事务内替换该文档的旧 chunk，然后调用 `index-result`。
4. Java 把文档状态置为 `READY`，并发送项目事件。

### 5.3 检索链路（Python）

- 每次检索都必须带上 Java 给出的 `visibleDocumentIds`：

  ```sql
  WHERE project_id = $1 AND document_id = ANY($2)
  ```

- 默认采用 **Dense 优先、证据不足时有界回退到 Hybrid**。依据是 DuRetrieval 消融：Dense Recall@10 为 0.809，Hybrid RRF 为 0.729。
- 词法检索沿用修复后的 SQL：`%` 运算符走 GIN，阈值在连接级设置。
- RRF 融合（k=60）之后接 llama.cpp reranker。
- **过滤与 HNSW 的冲突**：带 ACL 过滤时，HNSW 先找近邻再过滤，可能召回不足。
  - 需确认镜像内 pgvector ≥ 0.8，然后开启 `SET hnsw.iterative_scan = relaxed_order` 并设置 `hnsw.max_scan_tuples`；
  - 如果版本低于 0.8，回退为「可见文档占比 < 阈值时走精确扫描」，阈值由压测决定。
- 输出 `sources`，每条带 `documentId`、`chunkId`、`sourceRef`。

### 5.4 切换与清理

- 切换方式：P2 阶段从 `document_body` 对全部 READY 文档重新建索引，校验每个文档的 chunk 数非零后切换。
- 清理：Java 的 `document_chunk`，以及 `embedding_index_generation` 和 `embedding_index_document`，在 P7 通过 V10x 迁移删除。
- 评测迁移：39 题质量评测（dev/holdout）和 DuRetrieval 基准改写为 Python 脚本或 pytest，结果写入 `docs/benchmark/`。

## 6. 长任务派发：Redis Stream

- Stream `aicollab:ai:tasks`，`MAXLEN ~ 10000`，消费组 `ai-worker`。消息只做通知，**任务状态以 Java 数据库为准**。
- Java 在业务事务 AFTER_COMMIT 之后执行 `XADD {taskType, taskId, epoch}`。
- Python 端：
  1. `XREADGROUP BLOCK` 读取消息；
  2. 调用 `claim`（条件更新：`status = QUEUED AND epoch = ?`）；
  3. 认领失败（已被别人拿走或 epoch 过期）就直接 `XACK` 丢弃；
  4. 执行完成后 `XACK`。
- 消费者崩溃：消息留在 pending 列表里，其他消费者用 `XAUTOCLAIM` 接管（空闲超过 2 分钟）。
- 补偿：Java 定时任务（Redisson 锁保证单实例执行）扫描 `QUEUED` 超过 60 秒、或租约过期的任务。租约过期时 `epoch + 1` 后重新 `XADD`。
- 为什么不用 RocketMQ 做这件事：
  - RocketMQ 5.x 的 Python 客户端依赖 Proxy/gRPC，Redis Stream 的 Python 客户端成熟；
  - 派发只是通知，可靠性由「数据库状态 + 补偿扫描 + epoch」保证，用不上事务消息。

## 7. Agent：LangGraph 推理 + Java 控制面

### 7.1 图结构（Python）

- `StateGraph` 的节点：
  - `prepare`：拉取 context，超出预算时先做摘要；
  - `agent`：LLM 绑定工具；
  - `tools`：所有工具调用都经过 Java，唯一例外是 `search_project_knowledge` 在 Python 本地检索，范围仍由 Java 给出；
  - `finalize`。
- 写工具返回 `PENDING_APPROVAL` 时，调用 LangGraph `interrupt()` 挂起。
- 文档调研子 Agent 在图内用 `Send` 并行展开，默认最多 3 个。子 Agent 的工具调用同样经过 Java，配额天然生效。Java 侧的委派账本（delegation ledger）停用，在 P7 删除。
- Checkpointer 使用 `PostgresSaver`（`ai_runtime` schema），`thread_id = runId`。图状态里不放任何凭据。

### 7.2 run 生命周期（Java，沿用现有表）

```text
QUEUED → RUNNING(epoch, 租约 6min, 心跳 2min) → WAITING_APPROVAL → RUNNING → COMPLETED | FAILED | CANCELLED
```

- 准入：沿用现有的 run 并发限制与 `AgentToolScheduler` 三级配额（全局 16 / 用户 4 / 单 run 2，队列 64），作用点改为工具调用接口。
- 取消：用户取消后，Java 置 `CANCEL_REQUESTED`。心跳响应带回取消信号，Python 在下一步停下；Java 同时拒绝该 run 后续的所有工具调用。
- 崩溃恢复：租约过期 → Java 恢复任务（Redisson 锁）执行 `epoch + 1` 并重新派发 → 新 worker 从最新 checkpoint 续跑。旧 epoch 的一切回调都返回 409。

### 7.3 工具调用接口

```text
POST /internal/ai/agent/runs/{runId}/tool-invocations
{epoch, invocationId, toolName, arguments}
```

1. 校验 `epoch == run.epoch`、租约有效、令牌 scope 匹配。
2. 按 `invocationId` 查重：已有结果就直接返回，同一调用不会执行两次。
3. 配额准入。
4. 只读工具：执行后持久化结果并返回。
5. 写工具：通过现有审批服务生成提案，返回 `{status: PENDING_APPROVAL, proposalId}`，run 置为 `WAITING_APPROVAL`。
6. 用户审批后，Java 执行写操作，再 `XADD {type=AGENT_RESUME, runId, epoch, proposalId}`。Python 用 `Command(resume=审批结果)` 从 checkpoint 恢复。
7. MCP 工具：MCP 连接配置和密钥仍在 Java，作为一类工具走同一个接口。

### 7.4 事件与 SSE（跨实例）

- Python 批量上报事件 `{seq, type, payload}`，Java 按 `(runId, seq)` 去重，然后：
  - 非增量事件（步骤、工具调用、审批、最终消息）写入 `agent_run_event`（V28）；
  - 所有事件（含 token delta）`XADD aicollab:agent:run:{runId}`，`MAXLEN ~ 2000`；run 结束后 key 保留 1 小时；
  - `PUBLISH aicollab:agent:run:{runId}:live`。
- SSE 连接处理（前端路径不变）：
  1. **先 SUBSCRIBE**；
  2. 再按 `Last-Event-ID` 执行 `XRANGE` 补发；
  3. 之后转发实时消息，按事件 ID 去重。

  先订阅后补发，是为了消除补发与订阅之间的漏洞窗口。
- Stream key 过期后，按游标从数据库重放。
- 后端 2 个实例时，SSE 连在 A、事件由 B 写入，前端仍能完整收到。

## 8. 规划：迁到 Python

- Java 创建 attempt（`generation_seq`、`active_attempt_id`，行锁 + 幂等键，已有），然后 `XADD {type=PLAN_GENERATE}`。
- Python 依次执行骨架 → 分批细节 → 修复 → 反思（提示词迁到 Python），进度经 `progress` 回报，最后调用 `result`。
- Java 用现有 Schema 校验器把关，拒收过期 attempt，再落库。前端继续轮询。
- 规划所需的知识证据由 Python 本地检索（§5.3），范围由 Java 给出；Agent 传入的 `seedEvidence` 沿用 11-24 的契约。

## 9. RocketMQ：只保留两个事务消息用途

| Topic | 本地事务 | 消费者 | 幂等 |
|---|---|---|---|
| `document-registered`（已有） | 文档记录 | Java 解析消费者 | 状态条件更新 + `processing_token` |
| `project-events`（新增） | 业务写操作与 `audit_log` 写入同一事务；回查按 `eventId` 查 `audit_log` | `feed-fanout`、`unread-counter`、`notification` 三个消费组 | `SET aicollab:evt:{group}:{eventId} 1 NX EX 86400` |

- 删除「MQ 不可用时在进程内直接执行」的回落路径。
- 事务消息发送失败时，接口返回 503，由前端重试。
- Staging 部署 namesrv 和 broker（broker 堆内存 256–512 MB），默认开启。

## 10. Redis 全景（落地后）

| 用法 | 场景 | 八股点 |
|---|---|---|
| Cache Aside + 逻辑过期 + **Redisson 互斥重建** | 看板聚合缓存（替代进程内 SingleFlight） | 缓存击穿、分布式锁、看门狗 |
| Redisson 锁 + 看门狗 | 补偿扫描、恢复任务、未读回刷等定时任务的单实例执行 | 锁续期、主从切换丢锁 → epoch / 唯一约束兜底 |
| Stream + 消费组 | AI 长任务派发；Agent 事件重放缓冲 | XREADGROUP / XACK / XAUTOCLAIM、MAXLEN |
| Pub/Sub | 跨实例 SSE 实时推送 | 至多一次投递，配合 Stream 补发 |
| ZSET | 项目动态流收件箱（score = 时间戳），游标分页 | Feed 推拉模式、重复 score 时的游标处理 |
| Hash | 成员未读计数，读时清零，定期回刷数据库 | 计数器一致性、Redis 丢数据后从数据库重建 |
| String + TTL / HyperLogLog | 在线心跳（TTL 60s）/ 项目日活 | 过期策略、基数估计 |
| Lua 滑动窗口 | 按用户 + 用途限制 AI 调用频率，防止刷模型费用 | Lua 原子性、限流算法 |
| SET NX | MQ 消费幂等 | 幂等设计 |

动态流的推拉策略：成员数 ≤ 200 的项目用推模式（写扩散）；大于 200 时，项目级 ZSET 拉取并合并（读扩散）。阈值写进配置，不做开关组合。

## 11. 做减法清单

**删除**
- 模式与开关：`RERANK_MODE`、`DECIDE_MODE`、`PLANNING_DRAFT_MODE` 及其 shadow 路由，`INTERNAL_AI_TOKEN_MODE=static`，进程内 MQ 回落。
- 模型调用：Java 四类模型适配器、`ChatModelGateway` / `ModelTurnGateway` 的实现、`InternalModelCallController`。
- Agent 推理：`NativeToolCallingExecutor`、`LegacyReadOnlyAgentExecutor`、`AgentContextSummarizer` 的模型调用、`AgentRuntimeCoordinator` 中的推理部分。
- 规划与检索：`TaskPlanGenerationOrchestrator` 的生成 / 修复 / 反思，Java 侧的检索、重排、查询理解（`retrieval/`、`NeuralModelReranker`、`LlamaCppRerankAdapter`、`ProjectEmbeddingGateway`），`document_chunk` 相关表。

规模预估：删除 110–140 个类，新增约 15–20 个薄接口类。

**保留，生产默认开启**
- 总开关 `CHAT_ENABLED`、`AGENT_ENABLED`、`EMBEDDING_ENABLED`、`PLANNING_ENABLED`、`MQ_ENABLED`，只用于运维紧急关闭。

**前端**
- 接口路径和 SSE 协议不变。规划仍然轮询。

## 12. 部署（staging `box-aae54f`）

- `compose.staging.yml` 的变更：
  - 增加 `app-2`，Nginx upstream 改为两个实例，SSE 位置设置 `proxy_buffering off` 和 `proxy_read_timeout 1h`；
  - 增加 `rocketmq-namesrv` 和 `rocketmq-broker`；
  - `ai-worker` 内存上限从 512 MB 调到 1 GB；
  - 初始化脚本创建 `ai_worker` 角色和两个 schema。
- 内存预算：当前约 2.2 GB，加上 app-2（约 0.6）、RocketMQ（约 0.8）、worker 增量（约 0.3），合计约 3.9 GB，8 GB 的机器够用。部署后以 `docker stats` 实测为准。
- 安全：
  - 严禁回显 `/data/ai-collab-staging/config/.env` 中的任何密钥；
  - 先用 `pg_dump` 备份再升级；
  - 验收后确认维护模式已关闭。
- **决策记录（2026-11-27，用户选定）：ai-worker 出网走白名单代理。**
  - 背景：P1 起 Python 租借凭据后直连厂商，但 worker 只在 `data`（`internal: true`）上，staging 实测解析不了厂商域名。
  - 方案：新增 `egress-proxy`（tinyproxy，固定版本），同时接 `data` 与 `edge`；`FilterDefaultDeny Yes` + 域名白名单
    `ai-collab-deploy/egress/allowlist`，只放行 CONNECT 443，只接受 `data` 子网来源，内存上限 64m。worker 仍只接
    `data`，经 `HTTPS_PROXY`/`HTTP_PROXY` 指向代理，`NO_PROXY` 覆盖全部内部主机名。新增厂商时必须同步加白名单。
  - 否决：worker 直接加入 `edge` —— 等于给 Python 进程开放任意公网出口，持有租借密钥的进程可被诱导外发到任意地址，
    且与"出网策略只在受控出口"的红线冲突；只部署 Java（worker 留在 P0）—— P1 的主路径（Stream 派发 → 租借 → Python
    调模型）根本跑不起来，记忆抽取和接管验收都无法进行。

## 13. 阶段与验收门

| 阶段 | 内容 | 验收门（全部满足才进下一阶段） |
|---|---|---|
| P0 | 删除 mode / shadow / static / 进程内回落；部署 RocketMQ 和双实例；Ed25519 统一 | 全量测试通过；staging 双实例健康；现有功能冒烟通过 |
| P1 | Python AI 内核：LangChain 模型客户端（openai / anthropic / google-genai，Ollama 与 ModelBridge 走 OpenAI 兼容）、凭据租借、用量回报、Redis Stream 消费框架 | 四类厂商流式和工具调用的契约测试；凭据不落盘、不进日志的测试 |
| P2 | RAG 迁移、`ai_index`、HNSW、重建索引、删除 Java 检索 | 39 题评测必要事实保留率 ≥ V1 基线 52/54；E1 评测完成 |
| P3 | 知识问答流式改走 Python | 前端问答流、引用、断开取消正常；E9 通过 |
| P4 | Agent：LangGraph 图 + Java 控制面 + Redis Stream / PubSub SSE | 审批 interrupt / resume、取消、崩溃恢复、子 Agent 全部有集成测试；E3、E4 通过 |
| P5 | 规划迁移 | 生成、部分重生成、确认、过期 attempt 拒收测试通过 |
| P6 | Redis 协作功能（§10 中的 Redisson、Feed、未读、在线、日活、限流）+ `project-events` 事务消息 | E5–E8 通过 |
| P7 | 删除死代码和废弃表，更新文档，staging 部署，bsk 端到端验收，补全面试追问卡 | 全量测试和构建通过；bsk 清单全部勾选 |

每个阶段单独提交，只推送到 `dev`，不合并 `main`。

## 14. 测试要求

- Java：Testcontainers（PostgreSQL 17 + pgvector、Redis 7、RocketMQ）。Python：pytest + testcontainers。
- 关键用例：
  1. 凭据租借：越权项目或用途被拒；过期后不可用；日志和 checkpoint 中搜不到密钥。
  2. 任务派发：同一任务并发认领只成功一次；消费者崩溃后经 XAUTOCLAIM 接管；补偿扫描重新派发；旧 epoch 回调返回 409。
  3. 工具调用：同一 `invocationId` 重复调用只执行一次；配额不超限；写工具生成提案后 interrupt，审批后 resume 成功。
  4. 崩溃恢复：在工具调用前、中、后三个点 kill worker，都能从 checkpoint 续跑，工具重复执行次数为 0。
  5. SSE：订阅与补发交界处不漏不重；跨实例完整；Stream 过期后从数据库重放。
  6. RAG：ACL 过滤后结果不越界；引用复核拦截越界的 documentId；HNSW 过滤召回（开 / 关 iterative scan）。
  7. 事务消息：本地事务提交后、提交消息前 kill，回查后孤儿记录为 0、孤儿消息为 0。
  8. 缓存：双实例热 key 过期，Redisson 下只回源一次；Redis 故障时直读数据库。
  9. Feed：不漏不重；未读计数在 Redis 清空后能从数据库重建。
  10. 限流：Lua 滑动窗口在并发下准确。

## 15. 压测与评测（本机 Docker，数字只写实测值）

| 编号 | 场景 | 指标 |
|---|---|---|
| E1 | DuRetrieval 10 万语料：精确扫描 vs HNSW；`ef_search` 取 40 / 80 / 160；ACL 只放 10% 文档时 iterative scan 开 / 关 | Recall@10、P50 / P99 |
| E2 | 39 题质量评测（Python 管线） | 必要事实保留率、空上下文数（如实标注为自建合成题集、字符串匹配判定） |
| E3 | Agent 运行中 kill worker × 20 次 | 恢复耗时 P50 / P99、工具重复执行次数、最终成功率 |
| E4 | 100 个 SSE 客户端连 A 实例、事件由 B 写入，随机断线重连 | 丢失数、重复数、增量端到端延迟 P99 |
| E5 | 一次上传 50 份文档 | 全部索引耗时；期间其他接口 P99 与空闲时对比（削峰效果） |
| E6 | 双实例看板热 key 过期，200 并发：无锁 / 进程内单飞 / Redisson | 回源次数、P99 |
| E7 | 50 人项目每秒写 10 个事件 | 扇出吞吐、收件箱读 P99、分页不漏不重 |
| E8 | 事务消息故障注入 | 孤儿记录 / 孤儿消息数 |
| E9 | 知识问答首 token 延迟（Java → Python 流式中转） | TTFT P50 / P99，与迁移前对比 |

产出目录：`docs/benchmark/2026-11-xx-ai-service-split/`，包含 README、原始数据和环境说明。

## 16. 简历映射（数字由压测填写）

- **LangGraph Agent 与 Java 控制面**：Python 用 LangGraph 实现 Agent 推理与并行调研子 Agent；写操作通过 interrupt 挂起，人工审批后从 checkpoint 恢复。Java 负责工具执行鉴权、三级并发配额、租约与 epoch fencing。Worker 崩溃后用新 epoch 重派、旧 epoch 回调一律拒绝，kill 20 次工具重复执行 0 次，恢复 P99【E3】。
- **AI 服务化与凭据租借**：AI 推理全部迁到 Python 无状态服务。Java 加密保管密钥并按 run 租借短期凭据，凭据只存于内存；索引数据按 schema 隔离，ACL 由 Java 下发、引用由 Java 复核。
- **跨实例 SSE 与任务派发**：长任务经 Redis Stream 消费组派发，XAUTOCLAIM 接管崩溃消费者，补偿扫描兜底。Agent 事件采用「先订阅 Pub/Sub、再 XRANGE 补发」，双实例断线重连丢失 0、重复 0，增量延迟 P99【E4】。
- **事务消息与动态流**：项目事件与审计日志同事务发送 RocketMQ 事务消息，回查保证一致；三个消费组 SET NX 幂等，扇出到成员收件箱 ZSET 游标分页，未读数用 Hash 计数并回刷数据库【E7、E8】。
- **向量检索调优**：补建 HNSW 索引，在 10 万语料上权衡 `ef_search` 的召回与延迟；针对 ACL 过滤导致的 HNSW 召回不足开启 iterative scan。Recall@10 / P99 由【E1】。
- **缓存击穿治理**：看板热 key 用逻辑过期 + Redisson 互斥重建，双实例 200 并发只回源 1 次【E6】。

## 17. 面试追问卡（P7 补全答案与 file:line）

1. 为什么 AI 放 Python、业务放 Java？边界怎么划？Python 挂了会影响什么？
2. 为什么不把主密钥交给 Python？凭据租借怎么防泄漏？
3. 长任务为什么用 Redis Stream，项目事件为什么用 RocketMQ？两者的可靠性分别靠什么？
4. XREADGROUP、XACK、XAUTOCLAIM 的语义是什么？消息丢了怎么办？
5. epoch fencing 解决什么问题？和 Redisson 锁丢失有什么关系？
6. LangGraph 的 interrupt / checkpoint 怎么实现人在环？审批期间 worker 重启怎么办？
7. 同一工具调用为什么不会执行两次？
8. 跨实例 SSE 为什么要先订阅再补发？Pub/Sub 和 Stream 各负责什么？
9. HNSW 的原理和参数 m、ef_construction、ef_search 是什么？带过滤条件时召回为什么会下降？
10. 为什么默认 Dense 而不是混合检索？（消融数据）
11. 事务消息的半消息和回查流程？回查查什么？
12. Feed 推模式和拉模式怎么选？ZSET 游标分页时 score 重复怎么办？
13. 未读计数以 Redis 为准，丢了怎么办？
14. Redisson 看门狗原理？主从切换丢锁的后果和兜底？
15. 做减法：为什么删除 shadow 和 mode 开关？删之前怎么确认没有回归？

## 18. 风险与停止条件

- **E2 不达标**：39 题保留率低于 52/54 时，停在 P2 排查，不进入 P3。
- **staging 资源**：内存或磁盘不足、RocketMQ 无法稳定运行时，暂停部署并报告，不降级回进程内执行。
- **兼容性**：pgvector 版本低于 0.8 时，按 §5.3 的回退方案执行，并在报告中写明。
- **安全**：任何测试或日志中出现明文密钥，立即停下修复。
- **回滚**：每个阶段都在 `dev` 上留有可回退的提交；数据库迁移只增不改，废弃表在 P7 统一删除。
