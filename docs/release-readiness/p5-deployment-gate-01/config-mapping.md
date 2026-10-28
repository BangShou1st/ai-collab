# P5-DEPLOYMENT-GATE-01 配置映射审计

> 审计基线：dev @ `b0c5dcac97fd5162733f2b2b5de3a1bb49d7ec4a`，审计日期 2026-10-24。
> 所有结论以源码为准（后端 `src/main/java` 与 `src/main/resources/application.yml`），
> 不以 `.env.example` 或历史文档的表述为准。

## 1. 结论速览

G-1 原始表述（`p5-deployment-readiness-01/deployment-gates.json`）认为 "app environment 缺
EMBEDDING_*/PLANNING_* 条目，.env 配置无法到达容器，RAG 知识库生产不可用"。**源码审计表明
该表述一半成立、一半是误判**：

1. **误判部分**：生产运行的 Embedding 配置**不来自环境变量**。V2 Embedding 运行时
   （`ProjectEmbeddingGateway`）只读取数据库中的系统级配置（`system_embedding_config` 表，
   管理员在「AI Infrastructure」界面配置，API Key 经 `MODEL_CONFIG_MASTER_KEY` 加密存储）。
   绑定 `EMBEDDING_*` 环境变量的 `EmbeddingProperties` 唯一消费者是
   `document/infrastructure/ai/OpenAiCompatibleEmbeddingGateway`——该类在全代码库中
   **没有任何注入点（死代码）**。因此"缺 EMBEDDING_* 透传导致知识库不可用"不成立，
   RAG 知识库在容器部署下的启用路径是管理端配置，环境变量透传与否均不影响。
2. **成立部分**：Planning 侧确有 7 个**真实生效**的调优变量未透传
   （`PLANNING_MAX_OUTPUT_TOKENS` / `PLANNING_MAX_SOURCES` /
   `PLANNING_SOURCE_CODEPOINT_BUDGET` / `PLANNING_RETRIEVAL_THRESHOLD` /
   `PLANNING_EXECUTOR_CORE_SIZE` / `PLANNING_EXECUTOR_MAX_SIZE` /
   `PLANNING_EXECUTOR_QUEUE_CAPACITY`），生产运维无法通过 `.env` 覆盖；
   且 `.env.example` 第 6/7 段把遗留变量当作有效配置展示，构成部署误导。

## 2. 配置调用链（源码证据）

### Embedding（现行 V2 链路）

- 运行时入口：`infrastructure/ai/embedding/ProjectEmbeddingGateway.java` ——
  类注释明确 "只读取系统 Embedding 配置"；配置来自
  `SystemEmbeddingConfigRepository`（数据库），密钥解密用 `ModelSecretCipher`。
- 出站白名单：`infrastructure/ai/embedding/EmbeddingEndpointPolicy.java:15`
  `@Value("${embedding.local-allowed-targets:...}")` —— **`EMBEDDING_LOCAL_ALLOWED_TARGETS`
  是唯一真实生效的 Embedding 环境变量**，约束 Ollama 等本地 endpoint 目标。
- 配置缺失行为：未配置系统级 Embedding 时，文档索引/检索返回
  `EMBEDDING_NOT_CONFIGURED` 语义错误（`DocumentProcessingService`），**不会出现
  错误的"已就绪"状态**；就绪与否由数据库配置决定，与环境变量无关。
- 死代码：`OpenAiCompatibleEmbeddingGateway`（消费 `EmbeddingProperties` 全部字段）
  无任何引用；`application.yml:17-27` 的 `EMBEDDING_*` 占位符因此只服务于死代码。

### Planning（现行链路）

- 模型解析：`planning/application/TaskPlanModelClient.java:83`
  `providers.resolve(actor, ModelPurpose.PLANNING)` —— 规划模型按**用户级 AI Provider
  分配**（个人设置 → AI 设置）经 `RoutingChatModelGateway`（`@Primary`）路由；
  provider/baseUrl/apiKey/model 等来自用户配置（数据库，加密），**不是** `PLANNING_*` 环境变量。
- 真实消费的环境变量（经 `application.yml` 占位符）：
  - `PLANNING_MAX_OUTPUT_TOKENS` → `TaskPlanModelClient.java:84-88` /
    `PlanningModelConfigurationStore`
  - `PLANNING_MAX_SOURCES` / `PLANNING_SOURCE_CODEPOINT_BUDGET` /
    `PLANNING_RETRIEVAL_THRESHOLD` → `TaskPlanContextAssembler.java:47-52`
  - `PLANNING_EXECUTOR_CORE_SIZE` / `PLANNING_EXECUTOR_MAX_SIZE` /
    `PLANNING_EXECUTOR_QUEUE_CAPACITY` → `PlanningAsyncConfiguration.java:14-16`（`@Value`）
  - `PLANNING_GENERATION_LIMIT_PER_USER_HOUR` → `PlanningGenerationQuotaService.java:40`（`@Value`）
- 遗留变量：`planning/infrastructure/ai/PlanningFallbackConfiguration.java` 定义的
  `planningFallbackChatModelGateway` bean **在全代码库中没有任何注入点**。它消费了
  `PlanningModelProperties` 的 `enabled/provider/baseUrl/path/apiKey/model/connectTimeout/
  readTimeout/temperature` 字段，因此对应的 `PLANNING_ENABLED / PLANNING_PROVIDER /
  PLANNING_BASE_URL / PLANNING_PATH / PLANNING_API_KEY / PLANNING_MODEL /
  PLANNING_CONNECT_TIMEOUT / PLANNING_READ_TIMEOUT / PLANNING_TEMPERATURE`
  均为**历史遗留，无运行时效果**（bean 会被构造但结果无人使用）。

### 参考：CHAT_*（本轮不改动，仅记录）

`CHAT_*` 已被 Compose 透传。`ChatModelProperties` 的存活消费者是
`infrastructure/ai/AiCallLogService.java:15`（调用日志）与死 bean
`PlanningFallbackConfiguration`；对话/规划/Agent 的模型路由均走 `@Primary` 的
`RoutingChatModelGateway`（用户级分配）。CHAT_* 的取舍不在本任务范围内，保持现状。

## 3. 配置映射表

| 环境变量 | Spring 配置项 | 当前是否生效 | Compose 是否透传（修复前） | 是否需要修改 |
|---|---|---|---|---|
| `EMBEDDING_LOCAL_ALLOWED_TARGETS` | `embedding.local-allowed-targets`（`EmbeddingEndpointPolicy`） | ✅ 生效（Ollama 本地 endpoint 白名单） | ✅ 已透传（默认 `host.docker.internal:11434`） | 否，保留 |
| `EMBEDDING_ENABLED` | `embedding.enabled` → 死代码 `OpenAiCompatibleEmbeddingGateway` | ❌ 历史遗留 | ❌ 未透传 | 否（不透传，`.env.example` 移除并说明） |
| `EMBEDDING_PROVIDER` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_BASE_URL` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_PATH` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_API_KEY` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_MODEL` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_DIMENSIONS` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_BATCH_SIZE` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_CONNECT_TIMEOUT` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `EMBEDDING_READ_TIMEOUT` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_MAX_OUTPUT_TOKENS` | `TaskPlanModelClient` / `PlanningModelConfigurationStore` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_MAX_SOURCES` | `TaskPlanContextAssembler` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_SOURCE_CODEPOINT_BUDGET` | `TaskPlanContextAssembler` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_RETRIEVAL_THRESHOLD` | `TaskPlanContextAssembler` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_EXECUTOR_CORE_SIZE` | `PlanningAsyncConfiguration` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_EXECUTOR_MAX_SIZE` | `PlanningAsyncConfiguration` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_EXECUTOR_QUEUE_CAPACITY` | `PlanningAsyncConfiguration` | ✅ 生效 | ❌ 未透传 | **是，新增** |
| `PLANNING_GENERATION_LIMIT_PER_USER_HOUR` | `PlanningGenerationQuotaService` | ✅ 生效 | ✅ 已透传 | 否，保留 |
| `PLANNING_ENABLED` | 仅死 bean `PlanningFallbackConfiguration` | ❌ 历史遗留 | ❌ | 否（不透传，`.env.example` 移除并说明） |
| `PLANNING_PROVIDER` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_BASE_URL` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_PATH` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_API_KEY` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_MODEL` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_CONNECT_TIMEOUT` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_READ_TIMEOUT` | 同上 | ❌ 历史遗留 | ❌ | 否 |
| `PLANNING_TEMPERATURE` | 同上 | ❌ 历史遗留 | ❌ | 否 |

## 4. 网络地址原则（Ollama）

`EMBEDDING_LOCAL_ALLOWED_TARGETS` 的 Compose 默认值为 `host.docker.internal:11434`：
app 容器内 `127.0.0.1` 指容器自身，访问宿主机 Ollama 必须用 `host.docker.internal`。
部署时按实际拓扑选择：

- 宿主机 Ollama：保持默认 `host.docker.internal:11434`；
- 独立 Ollama 容器（需自行组网，本任务不擅自新增服务）：改为该服务在 Compose 网络内的
  服务名，如 `ollama:11434`；
- 外部 Embedding Endpoint（经管理端配置，非本变量）：配置实际可达的 host:port。

本任务只保证透传机制正确，不新增 Ollama 服务、不修改 Docker 网络架构。
