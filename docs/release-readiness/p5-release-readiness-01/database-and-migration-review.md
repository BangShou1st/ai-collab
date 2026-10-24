# database-and-migration-review.md — 数据库与升级兼容性审计

> 审计方式：迁移文件源码审计 + git 历史核查 + 本地只读 SQL 实测（2026-10-24，容器 ai-collab-postgres，未执行任何写操作/破坏性操作）。

## 1. 迁移历史 — 良好

- **66 个迁移，V1–V66，无跳号**，命名全部符合 `V<版本>__<说明>.sql`。最高版本 V66（`add_user_ai_provider_probe_result`）。
- **历史篡改核查**：仅 V5 有 3 次提交历史——d7897ab 创建 → fb944f1 **曾修改已发布迁移** → b1f9619 恢复原始内容并以 V6 修复。`git diff d7897ab:V5 HEAD:V5` 为空，当前 checksum 与原始一致，违例已闭环、无遗留漂移。其余 65 个文件均单次提交。
- 本地实测：flyway_schema_history 66 版本全部 success，0 失败；PostgreSQL 17.11 + pgvector 0.8.6。

## 2. Agent / Provider 表约束 — 成熟

| 表 | 关键约束 | 评价 |
| --- | --- | --- |
| agent_run | ck_status 11 态（V35/V61 扩展）、ck_depth 父子互斥、ck_budgets used≤max、`ux_agent_run_retried_from` 部分唯一索引（WHERE NOT NULL）、retried_from ON DELETE SET NULL | 重试幂等成立；状态机 DB 兜底 |
| agent_step | UNIQUE(run_id, sequence_no)、ck_type、usage_basis CHECK | 良好 |
| agent_run_event | UNIQUE(run_id, sequence_no)、type 白名单 CHECK、FK 双 CASCADE | 支撑 Last-Event-ID 重放 |
| agent_tool_invocation | PK(run_id,turn_sequence,ordinal) + invocation_id 全局 UNIQUE + status 7 态 | 良好 |
| agent_approval | UNIQUE(step_id)、UNIQUE(project_id, idempotency_key)、5 态 CHECK | 审批幂等 DB 兜底 |
| agent_usage_settlement | UNIQUE(run_id, call_id) 幂等入账 | 良好 |
| model_configuration_snapshot | JSONB（V51），读路径 AgentRepository | 可接受 |
| user_ai_provider_probe_result（V66） | UNIQUE(provider_id, probe_mode)、双 CHECK、FK CASCADE；STALE 由读取时配置版本对比推导 | 良好 |

## 3. agent_run.model_name/model_provider 影子列（R-14，P3）

- 引入：V22:25-26（agent_run）、V27:19-20（agent_step）；写点 AgentRunEventRecorder.java:888/928/1116（v2 路径的 recordFinal/recordToolResult/MODEL_TURN 均写入——**注意：并非完全不写，而是只写不读**）。
- 读路径为零：AgentRunView/AgentRunDetailView/AgentRepository 查询均无该字段；权威来源为 model_configuration_snapshot（21/21 覆盖）+ agent_step.MODEL_TURN（68/68）+ MODEL_COMPLETED 事件。
- 处置建议：下次涉及 agent_run 的迁移一并删列（勿单独迁移热点表），或标注「仅审计用途」。不阻塞发布，不回填、不伪造历史数据。

## 4. 知识库表

- `document_chunk.embedding vector NOT NULL`（V1:173），**未硬编码维度**（pg_attribute typmod=-1 实测），符合 AGENTS.md 要求；维度一致性由 embedding_fingerprint（V43，sha256(provider|model|dimension)）+ `uk_document_chunk_generation` NULLS NOT DISTINCT（V46）在应用层保证，**DB 层无维度兜底**（R-13 相关，P3，应用层已拦截）。
- **无 ivfflat/hnsw 向量索引**（pg_indexes 实测仅 btree 主键 + generation 唯一索引）——当前 18 行分块无感知，数据量增长后为性能债（R-09，P2）。
- 级联删除链完整：project→document→chunk→knowledge_citation 全 CASCADE。

## 5. 本地数据库实测（只读）

| 项 | 结果 |
| --- | --- |
| Flyway | 66/66 success |
| PostgreSQL / pgvector | 17.11 / 0.8.6 |
| 行数规模 | agent_run 24 · agent_step 199 · agent_run_event 435 · agent_tool_invocation 93 · agent_approval 2 · agent_usage_settlement 73 · project_task 46 · project_document 15 · document_chunk 18 · user_ai_provider 8（均为开发量级） |

## 6. 备份与恢复 — **DOCUMENTED_NOT_DRILLED（R-01，P1，发布阻断条件）**

| 项 | 现状 |
| --- | --- |
| 备份脚本 | **全仓库 pg_dump/pg_restore/mc mirror 零命中，无可执行备份脚本** |
| 恢复演练 | **无生产备份恢复演练证据。** 已有的是 2026-10 验收隔离副本演练（隔离容器 55432/16379/18090，restore-reliability-config.ps1 事务恢复+逐行断言）——对象是验收库，不等于生产备份恢复实测 |
| 文档 | docs/deployment-recovery.md:27 把「备份数据库与对象存储、在另一副本实际恢复并断言」列为正式部署**前置待办**；docs/deployment-recovery.md 自述「正式部署另行决定」 |
| 卷 | postgres/minio 均命名卷（ai_collab_postgres_data / ai_collab_minio_data），restart: unless-stopped |
| 密钥备份 | MODEL_CONFIG_MASTER_KEY / JWT_SECRET 丢失 = 加密数据不可解密，文档有提及但无备份操作流程 |

**结论：不宣称备份恢复已实测。** 整改见 remediation-plan.md S1：备份脚本 + 隔离环境真实恢复演练（含 flyway_schema_history、关键表行数、MinIO 对象数、加密数据可解密性核对）。

## 7. 升级兼容性

- 多版本部署/回滚：deployment-recovery.md:82 提及镜像回退受镜像仓库可用性制约；app 镜像 `ai-collab-backend:local` 未锁 digest（P3）。无跨版本并存部署设计（单体 + 会话无状态，滚动升级风险低，但未文档化）。
- 迁移失败收口：无专门文档，仅 local-development.md:69 一句排查指引（P3）。Flyway 默认行为（失败即停、不自动跳过）可接受。
- MinIO 旧数据卷升级兼容：文档自述未验证项（R-13，P2）。

## 8. 问题清单

| # | 级别 | 问题 | 证据 |
| --- | --- | --- | --- |
| 1 | P1 | 备份/恢复 DOCUMENTED_NOT_DRILLED（R-01） | 见第 6 节 |
| 2 | P2 | 无向量索引（R-09） | pg_indexes 实测 |
| 3 | P3 | vector 列 DB 层无维度兜底（应用层 fingerprint 已拦截） | typmod=-1 实测 |
| 4 | P3 | agent_run/agent_step 影子列只写不读（R-14） | AgentRunEventRecorder.java:888/928/1116 |
| 5 | P3 | AGENTS.md V65→V66 文档漂移（R-22，本轮已修正） | AGENTS.md:36 |
| 6 | P3 | 历史 V5 违例已闭环，流程曾破例（记录在案） | git log V5 |
| 7 | P3 | app 镜像未锁版本，回滚依赖镜像可用性 | docker-compose.yml:80 |
