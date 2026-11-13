# 本地 Demo 加固 Lane Registry

> 基线：`dev @ 8436ebebe25e0c4d649339f28e82e0dba326eabf`（2026-11-14）
> 本文件只登记执行事实；最终判定由复核者给出。

## 全局约束

- 长期分支仅 `dev` / `main`；全部 lane 从基线 `dev` 独立建 worktree，不在主 worktree 并行改文件。
- 不碰 `main`、不 force push、不改写历史、不做生产/staging 部署、不启用生产 Gateway。
- 仓库默认六开关保持：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。
- `AGENT_MCP_GATEWAY_ENABLED=false`；仓库默认 `MQ_ENABLED=false`，不启用双轨。
- 模型只用本机 Magpie `:3425`；不 logout、不重启、不改 provider、不重新扫码、不使用 Gemini。
- 证据目录仅写本任务目录；敏感值只记录 SHA-256 指纹或“configured=yes/no”。

## 迁移号预留

| 版本 | 归属 | 状态 |
| --- | --- | --- |
| V100 | A / H-1 文档删除 Job | 预留，未占用不创建 |
| V101 | D / H-4 任务索引 | 预留，未占用不创建 |
| V102 | F2 / decide 主链（如确需） | 预留，未占用不创建 |
| V103 | G / planning 主链（如确需） | 预留，未占用不创建 |

当前最新历史迁移为 `V99__agent_mcp_provider_admission.sql`；不修改历史迁移。

## 端口与资源锁

| 资源 | 归属 | 端口 / 名称 | 说明 |
| --- | --- | --- | --- |
| A 主端口 | A | `18081` | 独立后端 |
| A 前端 | A | `15175` | 如需页面验收，辅助端口 |
| B 主端口 | B | `18082` | 独立后端 |
| C 前端 | C | `15173` | Vite |
| C 后端 | C | `18084` | 仅本 lane 验收，辅助端口 |
| D 前端 | D | `15174` | Vite |
| D 后端 | D | `18085` | 仅本 lane 验收，辅助端口 |
| E 主端口 | E1 | `19100` | ai-worker API |
| F 主端口 | F2 | `18083` | 独立后端 |
| G 主端口 | G | `18083` | F2 完成后串行复用；不并行占用 |
| 数据库 | 全部后端 lane | `local-db`（本机 PostgreSQL `5433`） | 每 lane 使用独立数据库名/库内数据，不删除共享基础设施 |
| 对象存储 | A / 集成 | `local-minio`（本机 MinIO `9002`） | A 的停机注入完成后恢复 |
| 前端进程 | C / D | `frontend-15173` / `frontend-15174` | 不抢 `5173` |
| AI worker | E1 | `ai-worker` | 单实例占锁 |
| 模型提供方 | 全部 | `model-provider` | 只读使用本机 Magpie `:3425` |

禁止抢占 `8080`、`5173`、`5432`、`9000`。资源冲突时在 lane 证据中记录等待，不硬抢。

## Lanes

| Lane | 目标 | 分支 | Worktree | 主端口 | 迁移 | 证据目录 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | H-1 文档删除 Job 化 | `hardening/p0-doc-delete` | `..\ai-collab-wt\p0-doc-delete` | 18081 | V100 | `docs/acceptance-evidence/2026-11-14/local-hardening/lane-a-doc-delete/` | PLANNED |
| B | H-2 Token 撤销 | `hardening/p0-token-revoke` | `..\ai-collab-wt\p0-token-revoke` | 18082 | 无 | `docs/acceptance-evidence/2026-11-14/local-hardening/lane-b-token-revoke/` | PLANNED |
| C | H-3 审批收件箱 | `hardening/p1-approval-inbox` | `..\ai-collab-wt\p1-approval-inbox` | 15173 | 无 | `docs/acceptance-evidence/2026-11-14/local-hardening/lane-c-approval-inbox/` | PLANNED |
| D | H-4 任务列表批量化 + 分页 | `hardening/p1-task-list` | `..\ai-collab-wt\p1-task-list` | 15174 | V101 | `docs/acceptance-evidence/2026-11-14/local-hardening/lane-d-task-list/` | PLANNED |
| F1 | decide 冻结集候选扩展（50–100 例） | `hardening/f1-decide-dataset` | `..\ai-collab-wt\f1-decide-dataset` | 无 | 无 | `docs/acceptance-evidence/2026-11-14/local-hardening/lane-f1-decide-dataset/` | PLANNED |
| E1 | rerank shadow bring-up（0 代码） | 无（只采数） | 无（主 worktree/容器） | 19100 | 无 | `docs/acceptance-evidence/2026-11-14/local-hardening/e1-rerank-shadow/` | WAIT_WAVE1 |
| F2 | decide Python 主链 | `hardening/f2-decide-mainchain` | `..\ai-collab-wt\f2-decide-mainchain` | 18083 | V102（如需） | `docs/acceptance-evidence/2026-11-14/local-hardening/f2-decide-mainchain/` | WAIT_WAVE1 |
| G | planning Python 主链 | `hardening/g3-planning-mainchain` | `..\ai-collab-wt\g3-planning-mainchain` | 18083（串行） | V103（如需） | `docs/acceptance-evidence/2026-11-14/local-hardening/g-planning-mainchain/` | WAIT_WAVE2 |

## 状态词

仅使用 `IMPLEMENTED` / `TESTED(UNIT|INTEGRATION|REAL_PROCESS)` / `MEASURED` / `BLOCKED` / `NOT_RUN`；
不写 `PASS` / `FAIL` / “验收通过”。同一失败最多修复 3 轮；仍失败必须写 `BLOCKED` 和原始输出。

## Wave 门禁

- Wave 1 每条 lane：编译、定向测试、失败注入、原始输出证据、中心复核；按 A→B→C→D→F1 顺序 cherry-pick。
- Wave 1 集成后：全量 Java、前端 lint/typecheck/test/build、Python 全量、demo 场景 1–6、CI。
- Wave 2 只允许在 Wave 1 全绿后开始；E1 只写 `MEASURED`，不改默认；F2 默认保持 `DECIDE_MODE=java`。
- Wave 3 只在 Wave 2 全绿后开始；planning 默认保持 `java`，失败回退 Java。

## 执行日志

| 时间（Asia/Shanghai） | 事件 | 证据 |
| --- | --- | --- |
| 2026-11-14 00:50 | Wave 0 基线登记；最新迁移 V99；本机 PostgreSQL/MinIO 为 5433/9002；8080/5173/3425 已有外部进程 | `baseline/` |
