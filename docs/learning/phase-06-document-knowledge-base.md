# Phase 06：项目文档知识库基础

本文记录 AI Collab Phase 06 的实现思路、关键代码位置和验收方法。它只覆盖“文档进入系统并形成可检索向量”的基础闭环，不代表 RAG 问答、引用展示或 AI 任务规划已经完成。

## 1. 模块边界与分层

Document 模块负责上传、存储、解析、清洗、分块、向量化、内部检索基础、下载、重试和删除。Knowledge 模块将在后续阶段负责问答会话、检索编排、回答生成和引用展示；本阶段不创建 Knowledge API 或页面。

后端位于 `com.shitulelv.aicollab.document`，继续使用 package-by-feature：

| 层 | 本项目位置 | 职责 |
|---|---|---|
| Controller | `document/api/controller/DocumentController` | HTTP 路径、multipart 参数、JWT 身份、状态码 |
| Application Service | `document/application/service` | 用例、事务边界、事件、异步处理和删除编排 |
| View | `document/application/view` | 可安全返回客户端的元数据，不含对象键、正文和向量 |
| Domain | `document/domain/model`、`document/domain/service` | 状态、上传规则、清洗后分块和 contentHash |
| Entity | `document/infrastructure/entity/DocumentEntity` | `project_document` 的 MyBatis-Plus 映射 |
| Repository | `document/infrastructure/repository/DocumentRepository` | 面向业务表达持久化意图 |
| Mapper | `document/infrastructure/mapper/DocumentMapper` | 显式 SQL、项目作用域、CAS 和 pgvector |
| Parser | `document/infrastructure/parser` | UTF-8 与 Tika 文本提取 |
| Storage Gateway | `document/infrastructure/storage` | 私有 MinIO 对象读写和预签名 |
| Embedding Gateway | `document/infrastructure/ai` | OpenAI-compatible Embeddings API |

Controller 不调用 Mapper、MinIO 或 Embedding；业务层也不依赖供应商响应类型。这样更换对象存储或向量供应商时，不需要改变 HTTP 契约。

## 2. 上传校验与对象键

`DocumentFilePolicy` 同时校验：

1. 文件非空；
2. 大小不超过 20MB；
3. 原文件名和显示名称不超过 180 字符；
4. 扩展名属于 PDF、DOCX、Markdown、TXT；
5. 浏览器声明的 Content-Type 与扩展名匹配；
6. Apache Tika 探测出的真实类型与扩展名匹配；
7. 文件名不包含路径分隔符、`../` 或 NUL。

这仍不是恶意文件检测的绝对保证。类型探测是防御之一，不能替代病毒扫描、依赖漏洞治理和资源监控。

对象键由服务端生成：

```text
projects/{projectId}/documents/{documentId}/source.{extension}
```

原始文件名只保存为元数据，不直接成为对象完整路径。因此同名文件不会覆盖，路径穿越文本也不会控制存储位置。

## 3. MinIO Gateway 与上传补偿

`DocumentStorageGateway` 只暴露 `put`、`open`、`presign`、`delete`。`MinioDocumentStorageGateway` 在 `ApplicationReadyEvent` 时检查 bucket，不存在则创建；随后显式删除可能残留的 bucket policy。MinIO 默认拒绝匿名访问，而主动移除旧策略还能避免“bucket 早已存在且曾被公开”的配置漂移。

下载接口返回 5 分钟预签名 URL。URL 只在内存中使用，前端不写入 localStorage 或 sessionStorage；API 不返回 `objectKey`。

MinIO 和 PostgreSQL 是两个独立系统，Spring 数据库事务不能让 MinIO 自动回滚。上传流程因此采用补偿：

```text
权限检查与文件校验（无数据库事务）
  → 写入 MinIO
  → 调用独立 Bean 的短注册事务
      → 锁定 ACTIVE project 行
      → 检查 100 个有效文档上限
      → 插入 project_document
      → 写 DOCUMENT_UPLOADED 审计
      → 发布事件
      ├─ 提交成功：保留对象，AFTER_COMMIT 触发处理
      └─ 注册失败：外层补偿删除对象
```

`DocumentApplicationService.upload` 本身不使用 `@Transactional`，MinIO put 不持有 project 行锁；`DocumentRegistrationService.registerUploadedDocument` 通过另一个 Spring Bean 代理进入事务。补偿也可能因为 MinIO 故障失败，所以日志只记录对象键 SHA-256 的 12 位短摘要，不记录完整对象键、正文、凭据或服务端路径。生产环境仍应增加孤儿对象巡检。

项目删除事务与注册事务锁定同一 `project` 行。删除锁定后统计全部 `project_document`；只要仍有一条就返回 `PROJECT_DOCUMENTS_EXIST`，要求用户先删除所有文档。这避免数据库级联删除元数据后留下无法追踪的 MinIO 文件，也没有假装 PostgreSQL 事务能够删除对象存储。

## 4. afterCommit 异步调度

上传和 retry 在事务中发布 `DocumentUploadedEvent`。`DocumentProcessingListener` 只保留：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

数据库提交成功后，监听器同步调用 `DocumentTaskDispatcher.dispatch`。Dispatcher 使用 `@Qualifier("documentTaskExecutor")` 注入有界线程池并提交 Runnable；Runnable 内捕获未预期 RuntimeException，正常 claim 后的业务失败仍由 `DocumentProcessingService` 与 `DocumentFailureRecorder` 处理。

`DocumentAsyncConfiguration` 使用 2 个核心线程、最多 4 个线程、100 个队列槽位，明确采用 `AbortPolicy`、关闭时等待任务并最多等待 30 秒。Dispatcher 捕获 `TaskRejectedException` 后只记录 projectId、documentId 和“将由恢复任务重试”的 warning，不向 AFTER_COMMIT 调用方传播。于是上传和 retry 仍按已提交事实返回 202，文档保持 UPLOADED，MinIO 补偿不会被误触发，周期恢复会重新领取。不能改用 CallerRunsPolicy，否则上传请求线程可能执行完整解析和 Embedding；也不能使用 DiscardPolicy 静默丢弃。

## 5. 状态机与 CAS 领取

状态为：

```text
UPLOADED → PARSING → INDEXING → READY
                ↘         ↘
                  FAILED

FAILED → UPLOADED → PARSING
任意非 DELETING → DELETING → 物理删除
```

V4 为文档增加 `processing_token` 与 `processing_heartbeat_at`。每次 `process` 都先生成新 UUID，并用带预期状态的条件 UPDATE 领取：

```sql
UPDATE project_document
SET status = 'PARSING',
    processing_token = :token,
    processing_heartbeat_at = now()
WHERE project_id = :projectId
  AND id = :documentId
  AND status = 'UPLOADED';
```

受影响行数为 1 才表示领取成功。之后 heartbeat、PARSING→INDEXING、失败、最终锁检查和 READY 都必须匹配同一 token。retry 会先清除旧 token，下一次领取生成新 token；delete 也会清除 token。于是旧 worker 即使遇到“FAILED→UPLOADED→PARSING”相同状态序列，也不能写入新尝试，这就是对 ABA 的防护。

最终向量写入前，`DocumentIndexWriter` 在短数据库事务内对文档行执行 `FOR UPDATE`，再次确认状态为 INDEXING 且 token 相同，再验证 chunk/vector 数量、替换分块、按 token 写 READY、清空 token/heartbeat 并写成功审计。Embedding HTTP 调用发生在这个事务之前，避免在外部网络等待期间长期持有数据库锁。若 token 不匹配，事务在删除旧块前返回 false。

## 6. 解析、安全限制与清洗

解析策略：

| 格式 | 实现 | 说明 |
|---|---|---|
| TXT | 严格 UTF-8 Decoder | 非法字节直接失败 |
| Markdown | 严格 UTF-8 Decoder | 保留 `#` 标题文本，不渲染危险 HTML |
| PDF | Tika AutoDetectParser | 只提取文本；扫描版图片 PDF 无文本时失败，不做 OCR |
| DOCX | Tika AutoDetectParser | 提取正文、段落和表格文本 |

安全边界包括：

- 最多提取 2,000,000 个字符；
- MinIO 输入流只在 worker 当前线程读取，最多读取 20MB + 1 字节后关闭；解析线程只接收 byte[] 并创建自己的 `ByteArrayInputStream`；
- 解析任务使用独立的两线程执行器和两个并发槽位；等待槽位最多 5 秒，实际执行最多等待 60 秒，避免高并发请求无限堆入解析队列；
- TXT/Markdown 读取也有对应字节上限；
- `EmbeddedDocumentExtractor` 拒绝递归附件；
- 不渲染原始 Markdown/HTML，不执行脚本、宏或外部链接；
- 清除 NUL、BOM 和除换行/制表符外的控制字符；
- 合并行内多余空白和连续空行；
- 解析异常只转成安全业务摘要，Tika 异常原文不返回前端。

`Future.cancel(true)` 只发送协作式中断。若 Tika/POI 不响应中断，JVM 不会强制终止该线程，槽位会一直到真实任务 finally 才释放。当前没有 OCR、病毒扫描和独立解析沙箱；真正硬隔离需要把解析放进可强制终止的独立进程。

## 7. 标题与段落感知分块

`DocumentChunker` 先识别 Markdown 标题，并把短的独立行作为候选标题，再按段落和句号选择边界。目标块长约 1,200 字符，块间最多保留约 150 字符重叠；边界会避开 UTF-16 代理对，heading 按 Unicode code point 截断到 300。每个文档最多生成 1,000 个非空块，超过后返回“文档内容过长，生成的分块数量超过上限”，不会把正文写入异常。

每个块保存：

- 从 0 开始的 `chunkNo`；
- 可空 `heading`；
- 清洗后的 `content`；
- 正文 SHA-256 `contentHash`；
- 近似 `tokenEstimate`；
- JSON metadata：projectId、documentId、chunkNo、filename。

`contentHash` 是确定性的正文摘要，不是密码哈希。它可用于后续结果去重，但不能代替访问控制。

## 8. Embedding Gateway、批处理和重试

`OpenAiCompatibleEmbeddingGateway` 用 Spring Framework `RestClient` 调用配置的 Embeddings 端点。配置来自环境变量：

```text
EMBEDDING_ENABLED
EMBEDDING_PROVIDER
EMBEDDING_BASE_URL
EMBEDDING_PATH
EMBEDDING_API_KEY
EMBEDDING_MODEL
EMBEDDING_DIMENSIONS
EMBEDDING_BATCH_SIZE
EMBEDDING_CONNECT_TIMEOUT
EMBEDDING_READ_TIMEOUT
```

未配置时应用仍能启动，但上传文档会在索引阶段进入 FAILED，并给出“检查模型配置后重试”的安全提示。

文本按 `batchSize` 分批。网络超时、HTTP 5xx 和 429 每批最多尝试 3 次，退避 250ms、500ms；其他 4xx 直接失败，避免对无效凭据或请求反复施压。响应按 `index` 排序后，必须同时满足：

- 向量数量等于输入文本数量；
- `index` 从 0 连续递增且不重复；
- 每个向量非空；
- 每个向量长度等于配置维度。
- 每个元素非 null 且 `Double.isFinite`，禁止 NaN 和 Infinity；
- provider、model 非空，batchSize 必须在 1～256。

每次供应商请求前和成功后执行 `EmbeddingProgressListener`。文档处理回调用 projectId、documentId、processingToken 更新 INDEXING 心跳；影响 0 行说明任务已删除、取消或替代，立即停止后续批次。

供应商 JSON record 只存在于基础设施适配器内，业务层只接收 `EmbeddingBatch`。

## 9. pgvector 写入与内部检索

向量通过 MyBatis 参数传入，再由 PostgreSQL `CAST(#{embedding} AS vector)` 转换；SQL 没有拼接向量、正文或文件名。分块 metadata 使用 Jackson 序列化为 JSON，再参数化转换为 jsonb。

索引完成事务先删除同一 `project_id + document_id` 的旧块，再写入新块，因此 retry 不会累积分块，数据库唯一约束 `(document_id, chunk_no)` 是第二道防线。

内部精确余弦检索使用：

```sql
1 - (embedding <=> CAST(:queryEmbedding AS vector))
```

应用层 `DocumentSearchService.search(projectId, query, documentIds, topK)` 校验 query 非空且不超过 2,000 个 code point、topK 为 1～20；指定 documentIds 时必须全部属于当前项目且 READY。查询同时限定 projectId、READY、可选 documentIds、provider、model、dimension，返回 originalFilename、contentHash，并按 similarity 降序。它是 Phase 07 的内部基础，不公开知识问答或检索调试 HTTP 接口。

## 10. 删除一致性、retry 与重启恢复

删除仅允许 OWNER/ADMIN：

1. CAS 标记 DELETING；
2. 删除 MinIO 原对象；
3. 在短数据库事务中删除 chunk 和 document；
4. `knowledge_citation.chunk_id ON DELETE CASCADE` 清理引用；
5. 写 `DOCUMENT_DELETED` 审计。

如果对象删除失败，数据库记录保留为 DELETING；相同删除请求可继续完成，不会先向前端乐观报告成功。对象已不存在时 MinIO remove 仍可安全重试。

retry 仅允许 FAILED，复用原 documentId 和原对象。`DocumentApplicationService` 在事务外确认对象可读取，再调用独立 `DocumentRetryService` 的短事务，以条件 UPDATE 重置为 UPLOADED、写审计并发布事件；afterCommit 后重新处理。READY、PARSING、INDEXING、DELETING 都不能普通 retry。

`DocumentRecoveryService` 使用 `@Scheduled` 在应用启动后立即运行，并在每一整批完成 30 秒后继续扫描：

- 重新处理仍为 UPLOADED 的记录；
- 把 processing heartbeat 超过 15 分钟、且 status 与 token 仍和扫描结果一致的 PARSING/INDEXING 标记为 FAILED；
- READY 不重复处理。

每批最多取 100 条，并优先处理陈旧任务。恢复更新再次匹配 projectId、documentId、status、processingToken 和 `processing_heartbeat_at < staleBefore`，所以扫描后刚续过心跳的任务不会被误杀。恢复调度不使用 `@Async`，fixedDelay 会等待整批真正完成；UPLOADED 积压由后续批次继续排空。

文档事件统一使用 `PROJECT_DOCUMENT` entityType：上传写 `DOCUMENT_UPLOADED`，retry 写 `DOCUMENT_RETRY_REQUESTED`，索引成功写 `DOCUMENT_INDEXED`，token 条件失败更新成功后写 `DOCUMENT_PROCESSING_FAILED`，删除写 `DOCUMENT_DELETED`。失败审计 operator 使用上传者，错误摘要最多 1,000 个 code point，不含正文、对象键、供应商响应或 API Key。

## 11. RBAC、项目隔离与 IDOR

所有 API 先调用 `ProjectAccessGuard.requireMember(projectId, userId)` 或基于它的管理员检查：

| 操作 | OWNER | ADMIN | MEMBER |
|---|---:|---:|---:|
| 列表、详情、下载 | 是 | 是 | 是 |
| 上传、retry、删除 | 是 | 是 | 否 |

前端隐藏 MEMBER 的写按钮只是体验优化；后端仍独立返回 403。文档详情、状态变更、分块写入和删除 SQL 都携带 projectId 与 documentId。非成员与跨项目文档统一使用 404 语义，避免泄露资源是否存在。

## 12. 前端轮询

文档页仅在列表存在 UPLOADED、PARSING、INDEXING 或 DELETING 时轮询。每次请求结束后等待约 3 秒再发下一次请求，所以慢请求不会叠加。全部进入 READY/FAILED 等终态，或组件卸载时，定时器立即停止。

上传、重试、删除期间使用 loading 与操作锁。下载 URL 只交给 `window.location.assign`，不持久化。删除成功前不从本地列表移除记录。

## 13. 关键 Java 注解

### `@RestController`

- 谁读取：Spring MVC 组件扫描。
- 何时生效：应用启动创建 Controller Bean；方法返回值自动写成 JSON。
- 本项目位置：`DocumentController`。
- 删除后：路由不会注册，文档 API 返回 404。
- 常见误用：在 Controller 直接调用 Mapper、MinIO SDK，导致 HTTP 层承担事务和基础设施职责。

### `@RequestPart`

- 谁读取：Spring MVC multipart 参数解析器。
- 何时生效：处理 `multipart/form-data` 请求时，把 `file` part 绑定为 `MultipartFile`。
- 本项目位置：上传接口的 `@RequestPart("file")`。
- 删除后：Spring 不再按 multipart part 的明确名称绑定文件，契约可能失配。
- 常见误用：把文件写成 JSON `@RequestBody`，或信任 `MultipartFile.getContentType()` 作为唯一类型证据。

### `@RequestParam`

- 谁读取：Spring MVC 参数解析器。
- 何时生效：请求进入上传方法时绑定可选的 `displayName`。
- 本项目位置：`DocumentController.upload`。
- 删除后：参数不再按查询/form 字段语义绑定。
- 常见误用：把可选参数错误标成 required，或未经长度和空白规范化直接持久化。

### `@AuthenticationPrincipal`

- 谁读取：Spring Security MVC 参数解析器。
- 何时生效：Bearer Token 认证完成后，把当前 `Jwt` 注入方法参数。
- 本项目位置：所有 Document Controller 方法。
- 删除后：方法拿不到当前用户身份，不能执行用户级权限判断。
- 常见误用：相信前端传入 userId；本项目始终从 JWT subject 解析 UUID。

### `@Service`

- 谁读取：Spring 组件扫描。
- 何时生效：启动时注册应用服务 Bean，可参与代理、事务和依赖注入。
- 本项目位置：`DocumentApplicationService`、`DocumentProcessingService`、`DocumentIndexWriter` 等。
- 删除后：构造器注入失败，或类失去事务代理入口。
- 常见误用：把所有职责放进一个巨大 Service，或在同类内部调用 `@Transactional` 方法并误以为一定经过代理。

### `@Transactional`

- 谁读取：Spring Transaction AOP。
- 何时生效：外部通过代理调用公开方法时，在调用前开启事务，返回时提交或异常时回滚。
- 本项目位置：短上传注册、短 retry CAS、失败记录、最终索引写入、数据库物理删除和项目删除检查。
- 删除后：文档行、审计行、分块替换可能部分提交。
- 常见误用：把 Embedding HTTP 或 MinIO 长操作放在数据库长事务中；本项目的 MinIO put 先在事务外完成，注册失败由外层补偿，Embedding 也在最终短事务之外。

### `TaskExecutor` 与 `@Qualifier`

- 谁读取：Spring BeanFactory 与 `DocumentTaskDispatcher`。
- 何时生效：构造 Dispatcher 时，`@Qualifier("documentTaskExecutor")` 精确选择有界文档线程池。
- 本项目位置：`DocumentTaskDispatcher`；Listener 本身刻意不使用 `@Async`。
- 删除后：无法区分或注入指定执行器，文档处理也会退化为请求线程同步执行。
- 常见误用：把 `@Async` 直接放在 AFTER_COMMIT 监听器上，再试图在方法体内捕获拒绝；线程池拒绝发生在异步代理进入方法体之前，catch 根本没有机会执行。

### `@TransactionalEventListener`

- 谁读取：Spring 事务事件基础设施。
- 何时生效：事件在事务中发布，并在指定的 AFTER_COMMIT 阶段回调。
- 本项目位置：`DocumentProcessingListener.afterUpload`。
- 删除后：若改为普通事件监听，任务可能在数据库提交前启动。
- 常见误用：在没有活动事务时发布且期待必然触发；默认没有事务就不会执行。

### `@ConfigurationProperties`

- 谁读取：Spring Boot Binder。
- 何时生效：应用启动把 YAML/环境变量绑定到类型安全 record。
- 本项目位置：`MinioProperties`、`EmbeddingProperties`。
- 删除后：配置类无法注入，客户端无法构造。
- 常见误用：记录或返回包含 API Key 的完整配置对象；本项目不输出凭据。

### `@Mapper`

- 谁读取：MyBatis Mapper 扫描器。
- 何时生效：启动时生成接口代理，调用注解 SQL。
- 本项目位置：`DocumentMapper`。
- 删除后：Repository 无法注入 Mapper。
- 常见误用：遗漏 projectId 作用域，或用字符串拼接构造 pgvector/JSON SQL。

### `@TableName`

- 谁读取：MyBatis-Plus 元数据解析。
- 何时生效：BaseMapper 生成通用 SQL 时把 Entity 映射到指定表。
- 本项目位置：`DocumentEntity` 映射 `project_document`。
- 删除后：会按类名推导表名，可能访问不存在的 `document_entity`。
- 常见误用：把 View 当 Entity，导致安全响应字段与持久化字段混在一起。

### `@TableField`

- 谁读取：MyBatis-Plus 元数据解析。
- 何时生效：生成列映射时决定字段对应列或是否持久化。
- 本项目位置：`uploadedByDisplayName` 使用 `exist = false`，只承接 JOIN 查询。
- 删除后：BaseMapper 可能尝试写不存在的 `uploaded_by_display_name` 列。
- 常见误用：把 `objectKey` 也标成非持久化，导致原对象失去引用；或把敏感字段放进 View。

### `@EventListener` 与 `ApplicationReadyEvent`

- 谁读取：Spring ApplicationEventMulticaster。
- 何时生效：应用上下文已启动完成时处理 ready 事件。
- 本项目位置：MinIO bucket 初始化。
- 删除后：bucket 不会自动创建，也不会主动移除历史公开策略。
- 常见误用：在 ready 事件里执行无界耗时任务，拖慢应用可用性。

### `@Scheduled`

- 谁读取：Spring Scheduling 基础设施。
- 何时生效：应用启用 `@EnableScheduling` 后，按 `fixedDelay` 在上一次完整调用结束后等待再触发。
- 本项目位置：`DocumentRecoveryService.dispatchRecoverableWork`，启动后立即执行，随后默认在每批结束 30 秒后补偿扫描。
- 删除后：线程池溢出或进程崩溃时遗留的 UPLOADED 记录只能等下一次重启。
- 常见误用：把 `@Async` 叠在 fixedDelay 方法上，导致调度器只等待任务入队而不是实际完成；本项目保持同步 single-flight，并仍用 CAS 处理与上传事件的竞争。

## 14. 手工验收

构建：

```powershell
cd E:\ai-collab\ai-collab-backend
.\mvnw.cmd clean package -DskipTests

cd E:\ai-collab\ai-collab-frontend
npx -y pnpm@11.9.0 typecheck
npx -y pnpm@11.9.0 build
```

运行环境可用时，按以下顺序验收：

1. 启动 PostgreSQL、Redis、MinIO，确认 bucket 存在且没有公开策略；
2. 启动 local 后端与前端；
3. OWNER 上传 UTF-8 TXT、带标题/表格的 Markdown、DOCX、文本 PDF；
4. 观察 UPLOADED → PARSING → INDEXING → READY，确认 chunkCount、provider、model、dimension；
5. 上传扫描 PDF、空文件、超大文件、改后缀文件，确认稳定中文错误且无 500；
6. 关闭 Embedding 后上传，确认最终 FAILED 且 token/heartbeat 清空；retry 后确认领取生成不同 token，旧 token 的 heartbeat、FAILED、READY 更新均影响 0 行；
7. MEMBER 可列表、详情、下载，但强制调用上传/retry/delete 返回 403；
8. 用另一项目的 documentId 拼接 URL，确认统一 404 且无元数据泄露；
9. 下载 URL 约 5 分钟有效，bucket 仍 private，浏览器存储中无 URL；
10. 删除失败时记录仍保留；成功后对象、chunk、document 消失；
11. 项目保留文档时删除返回 409；删除全部文档后项目可删除；归档项目上传注册失败且 MinIO 对象得到补偿；
12. 处理途中删除，确认 token 被清除且旧 worker 不能写 READY；把 heartbeat 刷新到当前时间后，恢复扫描不能将其判为超时；
13. 上传可产生超过 1,000 块的长文本，确认进入带安全中文摘要的 FAILED；用模拟 Embedding 响应返回 null、NaN 或 Infinity，确认统一拒绝且不写 pgvector；
14. 检查 Network、日志、API 响应，不出现对象键、密钥、Authorization、Cookie、正文、向量、供应商原始响应或堆栈。

## 15. 当前风险

- 未配置真实 Embedding 服务时，无法证明供应商兼容性、额度、限流和维度行为；
- Tika 与底层 PDF/OOXML 解析器不能被宣称识别所有恶意文件；并发槽位和 60 秒超时能限制排队与调用方等待，但若底层解析器不响应中断，槽位和工作线程仍可能被长期占用，硬隔离仍需独立进程；
- 没有独立进程解析沙箱、病毒扫描、OCR 或消息队列；
- MinIO 与数据库补偿是最终一致策略，极端故障下仍可能出现需巡检的孤儿对象或 DELETING 记录；
- 未新增自动化测试；并发 CAS、删除竞态、补偿和重启恢复需要真实环境手工验证；
- 本阶段内部 `DocumentSearchService` 已具备，但 RAG 问答、引用展示和在线重建索引尚未实现。
