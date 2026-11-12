# W3 Reranker 配置平台交付报告（2026-11-12）

- Lane：A
- 分支：`w3/reranker-config`
- worktree：`E:\project\ai-collab-w3-reranker-config`
- 基线：`d6fa5133`
- checkpoint commit：`2412b8be`
- 证据目录：`docs/acceptance-evidence/2026-11-12/w3-reranker-config/`
- 结论：**IMPLEMENTED / TESTED(STUB)**；管理端 API 已可用，前端 UI **NOT_IMPLEMENTED**，
  真实 GPU/llama.cpp **NOT_RUN**，全量 `mvnw verify` **NOT_RUN**。

## 交付内容

- `V92`–`V95`：Provider 能力元数据、模型身份元数据、能力档案（不可变版本/单 PUBLISHED/
  settings 保护触发器）、GLOBAL/PROJECT assignment。
- RERANK settings JSON Schema 严格校验：字段白名单、`additionalProperties=false`、冻结边界；
  拒绝 chat/embedding 字段。
- `LlamaCppRerankAdapter`：`POST {base_url}{api_path}`，body
  `{"query":...,"documents":["title\ntext",...],"top_n":...}`；校验每个 index 恰好一次、
  分数有限；超时/5xx/非法 JSON/缺分/重复/越界均返回可回退失败，不重试。
- 管理端 API：Provider、Model Profile、Capability Profile、Assignment；测试/发布/回滚/禁用/审计。
- 运行时：项目绑定 → 全局绑定 → `SemanticHeuristicReranker`；失败回退并记录
  `rerank.fallback{reason=...}`；新增显式 `RerankerType.CONFIGURED_RERANK`，默认管线不变。

## 验证

- 35/35 定向测试 PASS：5 schema + 10 adapter + 5 resolver + 3 configured reranker +
  6 lifecycle + 3 assignment + 3 Testcontainers。
- Testcontainers：PostgreSQL 17 + pgvector，Flyway V1–V95，约束/发布不可变/发布回滚重绑/
  50 候选一次 HTTP 全部 PASS。
- `mvnw -B -ntp -DskipTests package` PASS，jar SHA-256 见
  `raw/tests/jar-sha256.txt`。
- 管理端原始 API（stub）覆盖创建/测试→TESTED/发布 v1→绑定/发布 v2 并重绑/回滚到 v1/
  8/20/50 候选/50 候选单次调用/禁用；证据在 `raw/admin-api/`。
- 六开关：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`；
  与基线默认配置文件 `NO_DIFF`。
- 新 Java/资源无模型名、模型文件路径、llama.cpp 端口/endpoint 硬编码；应用日志无 query/
  候选正文。

## Lane B

使用 `POST /api/v1/admin/ai/capability-profiles/{id}/test`，传
`query`、1–50 条 `documents[]`（id/title/text）和可选 `topK`。一次请求只调用一次 `/rerank`；
响应含逐候选有限分数、`modelLatencyMs`、`totalMs`、`overheadMs`、`profileId`、`providerId`、
`outcome`。50 档需将 capability profile 的 `maxCandidates` 配为 50。

## 未完成与风险

- 前端管理 UI：`NOT_IMPLEMENTED`。
- 真实 GPU/llama.cpp 8/20/50：`NOT_RUN`；B 的本地预检 >3s 不能由本 Lane 判 PASS/FAIL。
- 全量 `mvnw verify`：`NOT_RUN`；不能据此宣称全量测试 PASS。
- 生产用户路径仍 `NOT_VERIFIED`；`RERANK_MODE=java` 未变，admin test 通过不等于生产可灰度。

完整原始证据与调用示例见
[`docs/acceptance-evidence/2026-11-12/w3-reranker-config/report.md`](../../acceptance-evidence/2026-11-12/w3-reranker-config/report.md)。
