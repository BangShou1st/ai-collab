# Lane A 证据：Reranker 配置平台 + 本地 Adapter

- 分支：`w3/reranker-config`
- worktree：`E:\project\ai-collab-w3-reranker-config`
- 基线：`d6fa5133`
- checkpoint commit：`2412b8be`
- 结论：**IMPLEMENTED / TESTED(STUB)**；管理端 API 与本地 stub `/rerank` 已走通。
- 生产用户路径：**NOT_VERIFIED**（`RERANK_MODE=java` 未变，默认管线仍为 `LEXICAL_HEURISTIC`）。
- 真实 GPU/llama.cpp：**NOT_RUN**；Lane B 的 8/20/50 正式测量需使用本分支部署后的 admin 测试端点。
- 前端 UI：**NOT_IMPLEMENTED**。
- 全量 `mvnw verify`：**NOT_RUN**；已跑 40 个定向单元/契约/Testcontainers 回归测试并完成 `package`。
- 后续集成补丁：`ModelCapability` 显式增加 `RERANK`；Admin provider 列表过滤
  `source_provider_id IS NULL` 的能力 Provider，避免 null-key 与 Chat 列表污染。补丁回归 59/59 PASS。

## 1. 交付范围

已实现四层配置：

1. 管理端共享 Provider（`shared_ai_provider` 扩展协议、鉴权、网络区域、健康路径）；
2. 模型身份档案（`ai_model_profile` 增加 `model_key` / `display_name` / `model_version` / `sha256` / `license`）；
3. 能力档案（`ai_model_capability_profile`，DRAFT → TESTED → PUBLISHED → DISABLED，不可变版本）；
4. 用途绑定（`ai_capability_assignment`，`GLOBAL/PROJECT`，固定 `RERANK/KNOWLEDGE_RERANK`，默认 `enabled=false`）。

运行时解析顺序为项目绑定 → 全局绑定 → Java 启发式；任何解析/Provider/Adapter 失败都回退
`SemanticHeuristicReranker`，记录 `rerank.fallback{reason=...}`，不向主请求抛出异常。

新增显式 `RerankerType.CONFIGURED_RERANK`，默认 pipeline 配置未改；生产默认仍是
`RerankerType.LEXICAL_HEURISTIC` 且 `RERANK_MODE=java`。

`LlamaCppRerankAdapter` 请求体发送 `top_n=documents.size()`，绝不用 `topK` 截断 llama.cpp
返回结果；`topK` 只在 Adapter 返回后用于排序/裁剪。9/10/20/50 文档的 stub 回归已验证。

## 2. 迁移文件

| 文件 | 内容 |
| --- | --- |
| `V92__shared_ai_provider_capability_metadata.sql` | Provider 协议/鉴权/网络区域/健康路径；允许 `LOCAL_CUSTOM`；本地 Provider 只能 `local`；直接创建的能力 Provider 名称唯一 |
| `V93__ai_model_profile_metadata.sql` | 模型身份元数据；`LOCAL_FILE` 来源；SHA-256 约束 |
| `V94__ai_model_capability_profile.sql` | 能力档案、版本唯一约束、单 PUBLISHED 部分唯一索引、已发布 settings 不可变触发器 |
| `V95__ai_capability_assignment.sql` | GLOBAL/PROJECT 绑定、RERANK 固定 purpose、唯一约束与 CHECK |

审计：`raw/audit/migrations.txt` 显示相对基线仅新增 V92–V95，历史迁移修改数为 `NONE`。

## 3. 管理端 API

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET/POST | `/api/v1/admin/ai/capability-providers` | 列出/创建能力 Provider |
| GET/PUT/DELETE | `/api/v1/admin/ai/capability-providers/{id}` | 查看/修改/删除 Provider（被引用时拒绝删除） |
| GET/POST | `/api/v1/admin/ai/capability-profiles` | 列出/创建能力档案（当前只开放 RERANK） |
| GET | `/api/v1/admin/ai/capability-profiles/{id}` | 查看能力档案 |
| POST | `/api/v1/admin/ai/capability-profiles/{id}/test` | 真实 Adapter 测试；支持 `query` + 1–50 `documents` + 可选 `topK` |
| POST | `/api/v1/admin/ai/capability-profiles/{id}/publish` | 仅 TESTED 可发布；旧 PUBLISHED 降为 DISABLED；重绑 assignment；写审计 |
| POST | `/api/v1/admin/ai/capability-profiles/{id}/rollback` | 回滚当前 PUBLISHED 到上一已核实版本；重绑 assignment；写审计 |
| POST | `/api/v1/admin/ai/capability-profiles/{id}/disable` | 禁用版本并停用其 assignment；写审计 |
| GET/POST | `/api/v1/admin/ai/capability-assignments` | 列出/新增或更新全局绑定 |
| DELETE | `/api/v1/admin/ai/capability-assignments/{id}` | 删除全局绑定 |
| GET/POST | `/api/v1/projects/{projectId}/ai/capability-assignments` | 项目管理员管理项目绑定 |
| DELETE | `/api/v1/projects/{projectId}/ai/capability-assignments/{id}` | 删除项目绑定 |

权限：所有 `/api/v1/admin/**` 端点调用 `UserService.requireSystemAdmin`；项目绑定调用
`ProjectAccessGuard.requireAdmin`。响应不返回 Provider 密文或明文密钥。

## 4. RERANK settings Schema

Schema 资源：`ai-collab-backend/src/main/resources/ai/capability/rerank-settings.schema.json`。
Java 执行器：`RerankSettingsSchema`，字段白名单来自 schema，`additionalProperties=false` 等价的
未知字段直接拒绝，整数不接受浮点截断。

| 字段 | 约束/默认 |
| --- | --- |
| `protocol` | 仅 `LLAMACPP_RERANK` |
| `timeoutMs` | 100–30000，默认 3000 |
| `maxCandidates` | 1–50，默认 50 |
| `batchSize` | 1–`maxCandidates`，默认 8 |
| `maxLength` | 64–8192，默认 2048 |
| `scoreType` | `sigmoid` / `logit` / `normalized`，默认 `sigmoid` |
| `topK` | 1–50，默认 8 |
| `healthPath` | 以 `/` 开头，默认 `/health` |
| `fallback` | 仅 `java-heuristic` |

`maxOutputTokens`、`streaming`、`toolCalling`、`dimension` 等非 RERANK 字段均在测试中被拒绝。

## 5. 测试结果

| 测试 | 结果 | 覆盖 |
| --- | --- | --- |
| `RerankSettingsSchemaTest` | PASS (5) | 默认值、字段白名单、边界、类型、batch 约束 |
| `LlamaCppRerankAdapterTest` | PASS (11) | 正常、缺分、越界、重复、非有限、logit、超时、5xx、非法 JSON、凭据缺失、9/10/20/50 全量候选 `top_n` |
| `RerankCapabilityResolverTest` | PASS (5) | 项目优先、全局回退、失效项目不绕全局、Provider 停用、无绑定 |
| `ConfiguredRerankerTest` | PASS (3) | 稳定排序、启发式回退、非法响应回退与指标 |
| `CapabilityProfileServiceTest` | PASS (6) | 发布、禁止发布 DRAFT、回滚、测试失败不 TESTED、默认 smoke、50 候选单次调用 |
| `CapabilityAssignmentServiceTest` | PASS (3) | 仅 PUBLISHED、固定 RERANK/KNOWLEDGE_RERANK、默认 disabled、项目管理员 |
| `RerankCapabilityMigrationIntegrationTest` | PASS (3) | V1–V95、CHECK/唯一约束、发布不可变、发布/回滚重绑、50 候选一次 HTTP |
| `SharedAiProviderLifecycleTest` | PASS (4) | 共享 Provider 回归；能力 Provider 不进入 Chat 共享列表 |
| 组合定向测试 | **PASS 40/40** | 命令与结果见 `raw/tests/` |
| `mvnw -DskipTests package` | **PASS** | `target/ai-collab-backend-0.0.1-SNAPSHOT.jar` |

Testcontainers 实际日志：PostgreSQL 17.11，Flyway 应用 95 个迁移，最终版本 `v95`。

## 6. 管理端原始 API 证据（stub）

完整请求/响应在 `raw/admin-api/`，未保存 Authorization header、密码或 access token。
该批 raw 的 stub 对 documents 返回全量结果；真实的 `top_n=documents.size()` 回归由
`raw/tests/...LlamaCppRerankAdapterTest.txt` 的 9/10/20/50 测试证明，而不是用旧 stub 冒充。

| 证据 | 结果 |
| --- | --- |
| `capability-profile.v1.create.response.json` | DRAFT，version 1 |
| `capability-profile.v1.test.response.json` | TESTED，两条有限分数 |
| `capability-profile.v1.publish.response.json` | PUBLISHED，version 1 |
| `assignment.global.response.json` | GLOBAL / RERANK / KNOWLEDGE_RERANK，enabled=true |
| `capability-profile.v2.publish.response.json` | PUBLISHED version 2；审计记录 `repointedAssignments=1` |
| `capability-profile.v2.rollback.response.json` | 恢复 version 1；审计记录 `repointedAssignments=1` |
| `capability-profile.measure-8/20/50.response.json` | 候选数 8/20/50，outcome SUCCESS，每候选有限分数 |
| `capability-profile.one-call-50.response.json` + `one-call-50.json` | 50 候选请求前后 stub 调用数 6→7，`delta=1` |
| `capability-profile.v1.disable.response.json` | DISABLED |

stub-only 延迟（仅证明字段与链路，不代表模型性能）：

| 候选数 | modelLatencyMs | totalMs | overheadMs |
| ---: | ---: | ---: | ---: |
| 8 | 4 | 5 | 1 |
| 20 | 4 | 5 | 1 |
| 50 | 16 | 20 | 4 |

审计原始行见 `raw/audit/audit-log.txt`，覆盖 CREATE / TEST / PUBLISHED / ROLLED_BACK / DISABLED；
审计 detail 不含 settings、query、候选正文、密钥或模型路径。

## 7. 默认开关与硬编码审计

`raw/audit/default-switches.txt`、`raw/audit/default-pipeline.txt`：

| 开关 | 证据值 |
| --- | --- |
| `RERANK_MODE` | `java` |
| `DECIDE_MODE` | `java` |
| `PLANNING_DRAFT_MODE` | `java` |
| `MEMORY_JOB_ENABLED` | `false` |
| `INTERNAL_AI_TOKEN_MODE` | `static` |
| `AI_WORKER_TOKEN_MODE` | `static` |

相对基线，三个默认配置文件（`application.yml`、compose、ai-worker）`git diff` 为 `NO_DIFF`。
默认检索管线仍引用 `RerankerType.LEXICAL_HEURISTIC`；`CONFIGURED_RERANK` 只能显式选择。

`raw/audit/model-hardcode.txt` 与 `raw/audit/hardcoded-model-endpoint.txt`：
新增 Java/资源中未出现 `Qwen3-Reranker`、`D:\models`、`18081`、`llama-server`、
`reranker:8080` 等模型名/路径/端口硬编码。`raw/audit/log-redline.txt` 证明应用日志未出现
synthetic query 或候选正文。

## 8. Lane B 调用方式

1. 使用中心部署的 V92+ 后端与管理员 token；
2. 创建/选择 Provider 与 Model Profile；
3. 创建 RERANK capability profile（`maxCandidates` 至少覆盖待测档位，50 档填 50）；
4. 先 `POST .../{id}/test` 跑默认两文档 smoke，状态变 TESTED；
5. `POST .../{id}/publish`；
6. 对同一 profile 的 `POST .../{id}/test` 传入 8/20/50 条候选。

示例（不含真实 token；`$ID` 为 capability profile id）：

```powershell
$headers = @{ Authorization = "Bearer $TOKEN"; Origin = "http://localhost:5173" }
$body = @{
  query = "lane-b frozen query"
  topK = 8
  documents = @(
    @{ id = "case-001-0"; title = "doc 0"; text = "candidate 0 text" },
    @{ id = "case-001-1"; title = "doc 1"; text = "candidate 1 text" }
  )
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Method Post -ContentType application/json -Headers $headers `
  -Uri "http://localhost:8080/api/v1/admin/ai/capability-profiles/$ID/test" -Body $body
```

响应字段：`profileId`、`providerId`、`profileStatus`、`profileVersion`、`outcome`、
每个候选的 `scores[].index/id/score`、`modelLatencyMs`、`totalMs`、`overheadMs`、
`candidateCount`、`topK`。一次请求只调用一次 `/rerank`；实证 `one-call-50.json`。

注意：这是 **admin capability test**，不是生产用户请求路径；生产仍为 `RERANK_MODE=java`。

## 9. 未完成项与风险

- 前端管理 UI：**NOT_IMPLEMENTED**；本交付只有管理端 REST API。
- 真实 llama.cpp/Qwen GPU 验证：**NOT_RUN**；本报告所有 Adapter/API 证据来自本地 stub。
- 全量 `mvnw verify`：**NOT_RUN**；只跑定向测试与 package，不得写成全量 PASS。
- 共享本地开发 DB 在制作证据时从 V91 前滚至 V95；未提交任何 DB 数据、密钥或日志。
- Lane B 的 50 档已知本地 GPU >3s 风险不由本 Lane 判断；正式门禁仍由 B 的实测决定。
- `CONFIGURED_RERANK` 只有显式选择才生效；本分支没有修改任何生产默认开关。

## 10. 原始证据索引

- `raw/admin-api/`：创建、测试、发布、回滚、禁用、8/20/50、one-call 50 的 request/response
- `raw/audit/audit-log.txt`：数据库审计原文
- `raw/audit/default-switches.txt`、`default-pipeline.txt`：六开关与默认管线
- `raw/audit/migrations.txt`：V92–V95 与历史迁移审计
- `raw/audit/model-hardcode.txt`、`hardcoded-model-endpoint.txt`、`log-redline.txt`
- `raw/runtime/`：health、启动与 V95 迁移、端口
- `raw/tests/`：Surefire 原始文本报告、package 日志、jar SHA-256

## 11. 后续集成补丁（2026-11-13）

- 现象：dev@99c47d5e 部署后 admin providers 列表 500，原因是
  `SharedAiProviderRepository.parseCapabilities` 对 `RERANK` 抛 `No enum constant`。
- 修复：`ModelCapability.RERANK` 显式加入枚举；`AdminAiProviderService.listAdminProviders`
  过滤 `source_provider_id IS NULL` 的能力 Provider，避免 `Collectors.toMap` null key，
  同时保持 Chat 共享列表排除 `LOCAL_CUSTOM`。
- 回归：`SharedAiProviderLifecycleTest`、`RerankCapabilityMigrationIntegrationTest`、
  `ModelCapabilityPolicyTest`、`ModelProviderAdapterContractTest`、`ModelProtocolHttpTest`、
  `ProviderExtensionParamsTest` 共 **59/59 PASS**。
