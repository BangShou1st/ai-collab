# P1 Python AI 内核：Java ↔ Python 接口契约（冻结）

> **状态**：FROZEN FOR P1（中心 agent 编制，Java / Python 两个工作包都以本文为准）
> **日期**：2026-11-26
> **上位设计**：[ai-service-split-and-redis-2026-11-25.md](ai-service-split-and-redis-2026-11-25.md) §4、§6、§13 P1、§14
> **代码基线**：`dev @ 75accdb4`，下一个 Flyway 版本 **V104**
> 本文与上位设计冲突时以上位设计为准，并回报中心 agent 修订本文；实现方不得自行分叉。

---

## 0. P1 范围

| 做 | 不做（后续阶段） |
|---|---|
| 凭据租借接口、用量回报接口 | RAG / `ai_index`（P2） |
| Redis Stream 任务派发框架（Java 生产 + 补偿，Python 消费） | 知识问答流式（P3） |
| Python 模型客户端（LangChain：OpenAI / Anthropic / Gemini；Ollama、ModelBridge 走 OpenAI 兼容） | Agent 图、工具调用接口（P4） |
| **把现有两个 Python 能力切到新内核**：记忆抽取任务（`MEMORY_EXTRACTION`）改走 Stream；`/v1/rag/plan` 与记忆抽取的模型调用改用凭据租借 + 本地模型客户端 | 规划（P5） |
| 删除被替代的旧路径（§7） | Java 自身的模型适配器（P2–P5 逐个迁走后在 P7 删） |

一个能力只有一条主路径：P1 合入后，Python 不再经 Java 代理调用模型，记忆抽取不再走 HTTP 推送。

## 1. 令牌（沿用 P0 的 Ed25519，扩展 claim）

- 仍由 Java `InternalTokenIssuer` 签发，`aud`、`iss`、`exp ≤ 300s` 规则不变。
- `scope`：**集合语义**。如果现有实现是单个字符串，改为空格分隔的多值字符串（RFC 8693 风格），校验时按"包含"判断。
- 新增 / 约定的 claim：

| claim | 含义 |
|---|---|
| `uid` / `pid` | userId / projectId（沿用现有字段名；没有就新增） |
| `purp` | 允许租借的用途列表（§2.1 的枚举值） |
| `tid` / `ep` | 任务令牌专用：taskId、epoch |

- 新 scope：

| scope | 允许访问 |
|---|---|
| `task:{taskId}` | `/internal/ai/tasks/{taskId}/**`（且 `tid` 必须等于路径里的 taskId） |
| `credentials:lease` | `POST /internal/ai/credentials/lease` |
| `usage:report` | `POST /internal/ai/usage` |

- Java 给 Python 的入站调用（如 `/v1/rag/plan`）所带的令牌，从 P1 起必须同时带 `credentials:lease`、`usage:report` 和对应 `purp`，Python 用这个令牌去租凭据。
- `InternalAiTokenFilter` 的 `PATH_SCOPES` 按上表登记，不得放宽为"任意 scope"。

## 2. 凭据租借 `POST /internal/ai/credentials/lease`

### 2.1 请求

```json
{ "purpose": "MEMORY_EXTRACTION", "userId": "uuid", "projectId": "uuid", "taskId": "uuid|null", "runId": "uuid|null" }
```

`purpose` 枚举：`KNOWLEDGE_CHAT`、`AGENT`、`PLANNING`、`QUERY_PLAN`、`MEMORY_EXTRACTION`、`EMBEDDING`。
Java 把它映射到现有路由用途（`ModelPurpose` / 能力分配）。**映射必须与当前 `/internal/ai/v1/model-calls` 对查询改写、记忆抽取实际用的路由完全一致**，Java 工作包在汇报里列出映射表。

### 2.2 校验（任一不满足 → 403 `LEASE_FORBIDDEN`，不区分具体原因）

0. 令牌含 `credentials:lease`：scope 由内部令牌过滤器按路径校验，缺失返回 **401**，不是 403（见 R2 §10.1）。
1. `purpose ∈ purp`；`userId == uid`；`projectId == pid`。
2. 带 `taskId` 时：令牌 `tid` 相同，且任务处于 `LEASED/RUNNING`、epoch 等于 `ep`。
3. userId 是 projectId 的成员（复用现有成员校验）。
4. 路由解析复用现有逻辑（用户自有 → 共享 → 能力分配）及 `requireSnapshotAuthorized` 等授权校验。解析不到可用提供商 → 503 `AI_PROVIDER_UNAVAILABLE`。

### 2.3 响应（200，`Cache-Control: no-store`）

```json
{
  "leaseId": "uuid",
  "protocol": "OPENAI_CHAT | ANTHROPIC_MESSAGES | GEMINI_GENERATE_CONTENT | OPENAI_EMBEDDINGS",
  "providerType": "现有 providerType 原值",
  "baseUrl": "https://...", "apiPath": "可空",
  "model": "...", "apiKey": "明文，仅此一处出现",
  "params": { "temperature": 0.2, "maxOutputTokens": 4096 },
  "capabilities": ["STREAMING", "TOOLS", "JSON"],
  "expiresAt": "ISO-8601，≤ 签发后 300s"
}
```

### 2.4 Java 侧约束

- V104 新表 `ai_credential_lease(id, user_id, project_id, purpose, provider_id, model, task_id, run_id, issued_at, expires_at)`，**不存任何密钥或密文**。用户、项目外键 `ON DELETE SET NULL`。
- 日志只记 `leaseId/purpose/model`，绝不记 `apiKey`、请求头 Authorization、响应体。
- 删除 `InternalModelCallController` 及其 DTO（§7）。

### 2.5 Python 侧约束

- `Lease` 模型里 `apiKey` 用 `SecretStr`；`repr/str/model_dump` 都不出明文；禁止 pickle / JSON 序列化（`__reduce__`、`__getstate__` 直接抛错），保证以后进不了 LangGraph checkpoint。
- 进程内缓存，键为 `(purpose, userId, projectId, taskId|runId)`，在 `expiresAt − 30s` 时失效，不写盘。
- structlog 加脱敏处理器：(a) 键名匹配 `api[_-]?key|authorization|x-api-key|x-goog-api-key|secret|password|token`（不区分大小写）的值一律替换为 `***`；(b) 当前所有活跃 lease 的 apiKey 原值按子串替换为 `***`。
- 厂商 SDK / httpx 抛出的异常在跨出模型客户端边界前包装成 `AppError`，消息里不得带请求头或 URL 查询串里的 key。
- 继续拒绝 `*_API_KEY` 环境变量。

## 3. 用量回报 `POST /internal/ai/usage`

```json
{ "items": [ {
  "callId": "uuid", "leaseId": "uuid", "purpose": "...", "model": "...",
  "inputTokens": 0, "outputTokens": 0, "latencyMs": 0,
  "status": "SUCCESS | TIMEOUT | QUOTA_EXCEEDED | PROVIDER_ERROR | INVALID_OUTPUT | CANCELED",
  "errorCode": "可空"
} ] }
```

- 每批 1–100 条。令牌须含 `usage:report`。
- 每条的 `leaseId` 必须存在，且这个 lease 的 user/project 与令牌的 `uid/pid` 一致；否则该条拒收。响应是逐条结果 `{callId, accepted, reason?}`，整批返回 200；整批格式错误返回 400。
- V104：`ai_call_log` 增加 `call_id uuid UNIQUE`、`lease_id uuid`、`caller_type varchar(16) NOT NULL DEFAULT 'JAVA'`（值为 `JAVA|PYTHON`），状态 CHECK 加上 `CANCELED`。`feature` 写 purpose，`request_id` 写 callId，provider 从 lease 表回填。
- 幂等：`INSERT … ON CONFLICT (call_id) DO NOTHING`，重复上报算 accepted。
- Python：LangChain 回调收集 `usage_metadata`，内存队列攒批（≤ 50 条或 ≤ 2s 刷一次），失败用同一 callId 指数退避重试；进程退出前尽力 flush。上报失败不影响业务结果，但要有指标 `ai_usage_report_failures_total`。
- 指标都带 `purpose`、`caller_type` 标签。

## 4. 任务派发：Redis Stream

### 4.1 数据

- 沿用 `ai_job` 表（V91），任务状态以它为准。P1 只有 `MEMORY_EXTRACTION` 一种 job_type；P2 以后各阶段用新迁移扩展 CHECK。
- Stream `aicollab:ai:tasks`，`XADD … MAXLEN ~ 10000`；消费组 `ai-worker`（不存在就用 `XGROUP CREATE … $ MKSTREAM` 创建，两端都要幂等处理 BUSYGROUP）。
- 消息字段：`taskType`、`taskId`、`epoch`、`token`。`token` 是 `task:{taskId}` 令牌（含 `credentials:lease`、`usage:report`、`purp`、`tid`、`ep`），exp 300s。
  - 取舍：Redis 是内网带密码的实例，令牌只对一个任务、一个 epoch 有效，且 5 分钟过期，所以可以放。**apiKey 永远不进 Redis。**
  - 令牌过期时 Python 认领会被拒（401），这时直接 XACK，由补偿扫描重新派发。

### 4.2 Java 生产端

- 在业务事务里写 `ai_job(status=QUEUED)`，`AFTER_COMMIT` 再 `XADD`。XADD 失败只记日志和指标 `ai_task_dispatch_failures_total`，不回滚业务，由补偿扫描兜底。
- 删除 `AiJobDispatcher` 的 HTTP 推送。

### 4.3 Java 回调接口（新路径，替换 `/internal/ai/v1/jobs/**`）

| 接口 | 语义 |
|---|---|
| `POST /internal/ai/tasks/{taskId}/claim` `{taskType, epoch, workerId}` | 条件更新 `status='QUEUED' AND lease_epoch=:epoch` → `LEASED`，生成 `lease_token`，`lease_expires_at = now+60s`。成功返回 200 `{leaseToken, leaseExpiresAt, token(新签的任务令牌)}`；不满足条件返回 409 `TASK_CLAIM_CONFLICT` |
| `GET /internal/ai/tasks/{taskId}/snapshot` | 沿用现有 snapshot 内容 |
| `POST /internal/ai/tasks/{taskId}/heartbeat` `{epoch, leaseToken}` | 续租 60s，并换发新令牌。返回 `{leaseExpiresAt, cancelRequested, token}` |
| `POST /internal/ai/tasks/{taskId}/result` `{epoch, leaseToken, payload}` | 沿用现有结果的处理与校验，置 `SUCCEEDED` |
| `POST /internal/ai/tasks/{taskId}/fail` `{epoch, leaseToken, errorCode, retryable}` | retryable 且还有剩余次数 → `FAILED_RETRYABLE`（交给补偿扫描重排），否则 → `FAILED` |

- 除 claim 以外，都要校验 `epoch == lease_epoch AND lease_token == :leaseToken`；不符返回 **409 `TASK_EPOCH_STALE`**。
- result 和 fail 幂等：同一 epoch、同一 leaseToken 对已终态的任务重复提交，返回 200 且不改变任何状态；epoch 不同返回 409。
- 首次 heartbeat 时 `LEASED → RUNNING`。

### 4.4 Java 补偿扫描

- 每 30s 执行一次，用 **Redisson** 锁 `aicollab:lock:ai-task-compensation`（看门狗模式）保证只有一个实例在跑。即使锁丢了，靠 epoch 和条件更新也保证结果正确。
- 规则：
  1. `QUEUED` 且 `updated_at < now−60s` → 用原 epoch 重新 XADD（重复消息无害，认领是条件更新）。
  2. `LEASED/RUNNING` 且 `lease_expires_at < now` → `epoch+1`，回到 `QUEUED`，`attempt_count+1` 后重新 XADD；超过 `max_attempts` 置 `FAILED`。
  3. `FAILED_RETRYABLE` 且 `next_attempt_at <= now` → 同第 2 条。
  4. 超过 `deadline_at` → `EXPIRED`。
- 新增依赖 `redisson-spring-boot-starter`，版本要与 Spring Boot 匹配，并且不能破坏现有 `spring-boot-starter-data-redis` 的用法。

### 4.5 Python 消费端

- `XREADGROUP GROUP ai-worker {workerId} BLOCK 5000 COUNT n`，`workerId = {hostname}-{pid}`。
- 收到消息：claim → 成功则执行；返回 409 或 401 → 直接 `XACK`。执行完成（无论成功还是业务失败）→ 调 result 或 fail，再 `XACK`。
- 执行期间每 20s 发一次 heartbeat；返回 `cancelRequested=true` 就中止并上报 fail(`CANCELED`, retryable=false)；heartbeat 返回 409 就立即放弃，不再回调。
- 后台循环每 30s 执行一次 `XAUTOCLAIM … min-idle 120000`，接管崩溃消费者的消息，走同样的 claim 流程（epoch 过期会被 409 拒，正常 XACK）。
- 并发上限可配置（默认 4）；优雅停机：不再读新消息，等在途任务完成或超时。
- Redis 断连：指数退避重连，`/readyz` 返回 not ready。

## 5. Python 模型客户端

- 依赖：`langchain-core`、`langchain-openai`、`langchain-anthropic`、`langchain-google-genai`、`redis>=5`（asyncio）。版本锁进 `uv.lock`。
- `models.factory.chat_model(lease) -> BaseChatModel`：`OPENAI_CHAT → ChatOpenAI`（Ollama、ModelBridge 也走这条，`base_url` 取自 lease）；`ANTHROPIC_MESSAGES → ChatAnthropic`；`GEMINI_GENERATE_CONTENT → ChatGoogleGenerativeAI`。`embedding_model(lease)` 先建好工厂，给 P2 用。
- 统一能力：`ainvoke`、`astream`（逐 token）、`bind_tools`（工具调用，含流式下的工具调用分片合并）、结构化 JSON 输出。超时、重试由内核统一设置，不依赖各 SDK 的默认值。
- 每次调用生成 `callId`，结束时把用量放进 §3 的上报队列。

## 6. 测试要求（P1 验收门）

**契约测试（Python）**：4 类厂商（OpenAI、Anthropic、Gemini、OpenAI 兼容），每类都要覆盖：非流式文本、流式文本、工具调用（非流式和流式）、用量字段解析、错误映射（401/429/5xx/超时）。使用录制的 HTTP/SSE 夹具，通过 httpx transport 注入，不访问真实网络。

**凭据安全（双端都要有自动化测试）**：
- Python：用一个高熵的哨兵 apiKey 跑完整调用链（包括报错路径）后，在 structlog 输出、标准 logging、异常 `str/repr`、`tmp_path` 下的全部文件里都搜不到哨兵值；`pickle.dumps(lease)` 和 `json.dumps` 都会抛错。
- Java：用 OutputCapture 跑 lease 接口（成功与各种 403 路径），日志里搜不到解密后的 key；响应头带 `no-store`。

**Java（Testcontainers：PostgreSQL 17 + pgvector、Redis 7）**：
- 20 个线程并发 claim 同一任务，只有 1 个成功，其余返回 409。
- 旧 epoch 调 heartbeat/result/fail 返回 409；对同一终态重复 result 返回 200 且状态不变。
- 补偿扫描：QUEUED 超时会重新 XADD；租约过期会让 epoch+1 并重新派发；超过最大次数置 FAILED；两个扫描器并发执行时，同一个任务只被推进一次。
- 租借：非成员、purpose 不在 `purp` 里、uid 或 pid 不符、taskId 的 epoch 不符，都返回 403。
- 用量：重复 callId 只记一条；lease 与令牌不匹配的条目被拒；状态 CHECK 接受 CANCELED。

**Python（testcontainers Redis 7）**：
- 消费成功后 XACK；claim 返回 409 时 XACK 丢弃。
- 消费者执行中被杀掉后，另一个消费者经 XAUTOCLAIM 接管（测试里把 min-idle 调小）。
- heartbeat 返回 cancel 时中止；heartbeat 返回 409 时放弃且不再回调。
- Python 侧测试里的 Java 回调用进程内假服务模拟，但假服务的 claim 必须实现"条件更新"语义。端到端的真实并发语义由 §6 的集成测试证明。

**端到端集成（P1-I 工作包）**：本机 Docker 起真实的 Java + Python + PostgreSQL + Redis，记忆抽取任务走 Stream → claim → lease → 模型调用（OpenAI 兼容的假厂商容器或夹具服务）→ usage → result 全链路；再演练一遍 kill worker 后由 XAUTOCLAIM 或补偿扫描接管。

## 7. P1 删除清单

- Java：`InternalModelCallController`、`InternalModelCallRequest`、`InternalModelCallResponse`，以及 `/internal/ai/v1/model-calls` 的 scope 登记；`AiJobDispatcher` 的 HTTP 推送；`/internal/ai/v1/jobs/**` 改为 §4.3 的新路径（旧路径删除，不保留别名）。
- Python：`clients/java_gateway.py` 里的模型代理调用；`api/jobs.py` 的推送入口 `POST /v1/jobs`；`clients/java_jobs.py` 改为调用新路径。对应的契约快照重新生成。
- 删完后用 grep 证明：`src/main` 和 `ai_collab_worker` 下已经没有 `model-calls`、`/v1/jobs` 的引用。

## 8. 部署相关变量（P1-OPS 负责落到 staging）

- Python：`REDIS_URL`（含密码，属于敏感值，脱敏规则要覆盖）、`AI_WORKER_ID`（可选）、`AI_TASK_CONCURRENCY`、`JAVA_INTERNAL_BASE_URL`（必须是能同时到达 app-1 和 app-2 的地址，例如 nginx 内部 upstream，不能写死某一个实例）。
- Java：`AI_TASK_COMPENSATION_INTERVAL`（默认 30s）、Redisson 复用现有 Redis 配置。

## 9. 修订 R1（2026-11-26，按 Python 实际实现对齐，Java 以此为准）

依据：对 `ai-service-split @ 3675478c` 的 Python worker 做的只读核对。下面各条覆盖前文中对应的条目。

### 9.1 通用约定
- 所有 JSON 字段一律 camelCase。成功响应只能是 2xx；3xx 会被 Python 当作可重试错误。
- claim、heartbeat、lease、usage、snapshot 的 2xx 必须带 JSON 体；result、fail 可以返回空体。
- 错误体：`{"code":"UPPER_SNAKE", ...}`。Python 只根据 HTTP 状态做分支，code 只写进日志。
- 本阶段不传 `traceparent`，链路追踪放到 P4 再做。

### 9.2 Stream 消息
- 四个字段 `taskType`、`taskId`、`epoch`、`token` 都是字符串。taskId 用带连字符的规范 UUID，epoch 是十进制整数。缺任何一个字段，Python 直接 XACK 丢弃。

### 9.3 claim / heartbeat / result / fail
- claim 条件不满足时（已被认领、epoch 过期、状态不对、任务不存在），**只能返回 401/403/404/409**，Java 默认用 409。不要返回 400，也不要返回 422。
- claim 的 200 响应：`token` 必须非空，且令牌里带 `uid`、`pid`（UUID 字符串）、`tid`、`ep`、`purp`、`scope`（包含 `task:{taskId} credentials:lease usage:report`）。`leaseExpiresAt` 用带时区的 ISO-8601。
- 第一次 heartbeat 在 claim 之后立即发送，Java 在这次把状态从 LEASED 推进到 RUNNING。heartbeat 判定租约已丢失时，只能返回 401/403/404/409。heartbeat 的 200 响应里应总是带一个新的 `token`。
- result 和 fail 必须幂等：同一个 epoch 加同一个 leaseToken 重复提交，返回 200，状态不变。Python 在遇到可重试错误时最多重复提交 3 次。
- snapshot 的响应体沿用现有的 `MemoryJobSnapshot`（camelCase：`schemaVersion=1`、`jobId == taskId`、`projectId`、`attempt ≥ 1`、`leaseEpoch`、`snapshotHash`、`facts[]`、`existingMemories[]`），数值字段不能用 bool。

### 9.4 凭据租借
- 请求体里总会带 `taskId` 和 `runId` 两个键，值可以是 null；query plan 场景下两个都是 null。
- Python 按 `(purpose, userId, projectId, taskId|runId)` 缓存 lease，到 `expiresAt − 30s` 失效。这段时间里即使令牌换了也不会重新租借。Java 不能假设"每换一次令牌就租一次"。
- 响应里的 `protocol` 只能取 4 个枚举值之一；`baseUrl` 必须以 http(s) 开头；`expiresAt` 必须带时区。

### 9.5 用量回报
- 请求体 `{"items":[...]}`，每批不超过 50 条。**请求里没有 `callerType` 字段**，Java 自己写 `caller_type='PYTHON'`。`errorCode` 可以是 null，也可以是小写的 `provider_auth|provider_rate_limited|provider_timeout|provider_unavailable|provider_rejected`，Java 原样落库，不做校验。
- 响应统一用 `{"results":[{"callId","accepted","reason?"}]}`，**每个 callId 都要有一条结果**。重复的 callId 返回 `accepted=true`。
- Python 在整批失败时会用同一个 callId 重试，最多 5 次（R1 起只对 429、5xx、超时、传输错误重试，400/401/403/409 不再重试）。

### 9.6 Java → Python 入站令牌（`/v1/rag/plan`）
- `iss=ai-collab-backend`，`sub=backend`，`aud` 包含 `ai-worker`，JWS 头是 `alg=EdDSA`、`kid` 在 worker 公钥环里。`exp`、`iat` 都是整数。
- `scope` 是空格分隔的字符串，必须包含 **`plan credentials:lease usage:report`**；`purp=["QUERY_PLAN"]`；`uid`、`pid` 是 UUID 字符串。Python 自己校验 `plan`、`uid`、`pid`，然后把令牌原样转发给 Java，后面三项由 Java 的租借接口和用量接口校验。

### 9.7 Python 侧对应修正（R1）
- claim 返回 400/422 以及其他不可重试的 4xx（429 除外）时，改为 XACK 丢弃，不再留在 PEL 里反复被接管。
- 用量上报在 400/401/403/409 时不再重试。

## 10. 修订 R2（2026-11-26，Java 侧交叉审查后修订）

### 10.1 凭据租借缺 scope 的状态码
- 令牌缺 `credentials:lease` 时，由内部令牌过滤器按 `PATH_SCOPES` 拒绝，返回 **401**（与其他内部端点鉴权失败一致），不是 §2.2 原写的 403。租借接口内部不再重复校验 scope。
- 403 `LEASE_FORBIDDEN` 只用于令牌有效、scope 齐全之后的业务校验失败：purpose 不在 `purp`、uid/pid 不符、任务绑定不符、非项目成员。

### 10.2 取消入口
- P1 不提供取消入口。heartbeat 响应里的 `cancelRequested` 字段保留，P1 中恒为 `false`；到 P4 再接 Agent run 的取消（设计 §7.2，取消属于 P4 验收门）。
- P4 备注（方案，P1 不实现）：在管理面加 `cancel(projectId, jobId, userId)`，先做成员校验。不能复用现有的围栏跃迁（它会 `epoch+1` 并清空 `lease_token`，持有租约的 worker 之后心跳只能拿到 409，收不到取消信号），而是新增 `casCancel`：
  - 处于租约状态（`LEASED/RUNNING`）：只把状态改为 `CANCELED`，**保留 `lease_token` 与 `lease_epoch`**，当前 attempt 收尾为 `CANCELED`。持有者下次 heartbeat 仍能通过租约校验，拿到 `cancelRequested=true`，随后按 §4.5 上报 fail(`CANCELED`, retryable=false)。
  - 处于非租约状态（`QUEUED/FAILED_RETRYABLE`）：直接置 `CANCELED` 并 `epoch+1`，Stream 里残留的消息 claim 时得到 409。
  - 之后的 result/fail 按终态幂等规则处理（同 epoch + 同 leaseToken 返回 200、状态不变）。
