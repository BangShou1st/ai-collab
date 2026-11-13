# Lane A：H-1 文档删除任务化

## 归属与提交

- 分支：`hardening/p0-doc-delete`
- Worktree：`E:\project\ai-collab-wt\p0-doc-delete`
- 实现提交：`1c2372a97c4fc54fd52b5d73ef5a74ee45dc254a`
- 迁移：`V100__document_deletion_job.sql`
- 关联设计：`docs/design/local-demo-hardening-2026-11-14.md` §2

## 实现状态

| 能力 | 状态 | 事实 |
| --- | --- | --- |
| 同事务标记 `DELETING` + 写 Job | `IMPLEMENTED` | `DocumentApplicationService.delete` 使用外层事务；Job 仓储要求 `MANDATORY` 事务 |
| DELETE 返回 202 | `IMPLEMENTED` | 返回 `{deletionId,status}`，现有前端只消费 HTTP 成功，无需改类型 |
| 重复 DELETE 幂等 | `IMPLEMENTED` | `document_id` 唯一约束 + upsert；重复请求复用同一 `deletionId` |
| `FAILED` 重试 | `IMPLEMENTED` | 对同一文档再次 DELETE 会将 Job 重置为 `PENDING`、`attempts=0` |
| purge worker | `IMPLEMENTED` | `FOR UPDATE SKIP LOCKED` 批量认领、60s 租约、对象删除、行删除+审计+`DONE` 同事务 |
| 退避与 `FAILED` | `IMPLEMENTED` | attempts 1–4 的退避为 30/60/120/240 秒；第 5 次置 `FAILED` |
| reconciler | `IMPLEMENTED` | 启动与每小时执行：补孤儿 `DELETING` Job、回收过期 RUNNING 租约、清理 7 天前 `DONE` |
| 对象不存在 | `IMPLEMENTED` | `DOCUMENT_STORAGE_OBJECT_NOT_FOUND` 按删除成功处理 |
| 错误脱敏 | `IMPLEMENTED` | `last_error` 只写错误码或异常类名，截断至 500 字符 |
| 对象键安全边界 | `IMPLEMENTED` | 仅允许 `projects/{projectId}/documents/{documentId}/` 前缀 |

## 测试状态

| 范围 | 状态 | 原始结果 |
| --- | --- | --- |
| 编译与测试编译 | `TESTED(UNIT)` | `EXIT=0` |
| 新增/受影响的 17 个定向用例 | `TESTED(UNIT)` + `TESTED(INTEGRATION)` | `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` |
| 文档域回归 | `TESTED(UNIT)` + `TESTED(INTEGRATION)` | `Tests run: 92, Failures: 0, Errors: 0, Skipped: 0` |
| 真实 MinIO 容器停机/重启 | `TESTED(INTEGRATION)` | 停机后 Job `PENDING|1`；重启后 `DONE` 且文档行消失 |
| 真实后端进程中断恢复 | `TESTED(REAL_PROCESS)` | DELETE 返回 202、杀进程前 Job `PENDING|0`；重启后 `DONE|0`，文档行数 0 |

## 失败注入结果

1. **MinIO 停**：使用独立 MinIO 容器与固定测试端口，`stop()` 后 Job 保持 `PENDING`、`attempts=1`、
   `last_error=ERROR:DOCUMENT_STORAGE_UNAVAILABLE`；同容器 `start()` 并恢复后转 `DONE`。
2. **DB 删除失败**：用 PostgreSQL `BEFORE DELETE` 触发器注入异常；对象删除已发生后事务回滚，
   文档仍为 `DELETING`、Job 为 `PENDING`；移除触发器后重试转 `DONE`。
3. **进程中断**：真实 Spring Boot 进程在 DELETE 202 后强制停止；重启后原始 `PENDING|0` Job
   被新进程处理为 `DONE|0`，`project_document` 行数为 0。
4. **重复删除**：连续两次 DELETE 返回同一 `deletionId`，表中仅一行 Job，purge 前对象删除调用为 0。
5. **5 次失败**：前 4 次为带退避的 `PENDING`，第 5 次为 `FAILED`；同一 DELETE 重入队后最终 `DONE`。
6. **孤儿与过期租约**：无 Job 的 `DELETING` 文档被 reconciler 补 Job；过期 `RUNNING` 租约回 `PENDING`。
7. **Job 插入失败**：PostgreSQL 触发器使插入失败，外层事务回滚，文档保持 `READY` 且无 Job。

## 边界与未执行项

- `NOT_RUN`：全仓 `mvnw verify`、前端 lint/typecheck/test/build、Python 全量、demo 场景 1–6、CI；
  这些属于中心集成门禁。
- `NOT_RUN`：管理端独立“删除任务列表”页面。本 lane 按设计 §2.4 提供同文档再次 DELETE 的
  `FAILED` 重试路径，未新增额外查询契约。
- `NOT_RUN`：多实例并发压测；本轮实现使用 `FOR UPDATE SKIP LOCKED` 与租约，但未对多实例做负载验证。
- `BLOCKED`：无。

## 默认值核对

六开关仍为 `java/java/java/false/static/static`，`AGENT_MCP_GATEWAY_ENABLED=false`，
`MQ_ENABLED=false`；未修改历史迁移。原始核对见 `raw/defaults-and-migration.txt`。

## 证据索引

| 文件 | 内容 |
| --- | --- |
| `raw/summary.txt` | 测试与真实进程摘要 |
| `raw/test-compile-final.txt` | 测试编译原始输出 |
| `raw/targeted-tests-final.txt` | 17 个定向用例原始输出 |
| `raw/document-domain-tests.txt` | 文档域 92 个用例原始输出 |
| `raw/minio-outage-integration.txt` | 真实 MinIO 容器停机/重启测试 |
| `raw/real-process-state.txt` | 202、杀进程前后状态、重启后 DONE |
| `raw/real-process-delete-202.json` | 真实 DELETE 202 响应 |
| `raw/real-process-app-one.log` | 杀进程前后端原始日志 |
| `raw/real-process-app-two.log` | 重启后后端原始日志 |
| `raw/defaults-and-migration.txt` | 默认开关与迁移尾部 |
| `scripts/run-real-process.ps1` | 真实进程复现脚本（从仓库根 `.env` 读取，不落凭据） |

## 复现命令

```powershell
cd E:\project\ai-collab-wt\p0-doc-delete\ai-collab-backend
.\mvnw.cmd -B -ntp "-Dtest=DocumentDeletionLifecycleIntegrationTest,DocumentDeletionMinioOutageIntegrationTest,DocumentDeletionResponseTest,DocumentApplicationServiceWriteStatusTest,DocumentDownloadAuthorizationTest" test
.\mvnw.cmd -B -ntp "-Dtest=com.shitulelv.aicollab.document.**.*Test" test
pwsh -NoProfile -ExecutionPolicy Bypass -File ..\docs\acceptance-evidence\2026-11-14\local-hardening\lane-a-doc-delete\scripts\run-real-process.ps1
```

> 真实进程脚本使用独立数据库 `ai_collab_lh_a` 与独立 MinIO 容器 `ai-collab-minio-lh-a`
> （19002/19003）；结束后停止进程与容器，并删除该数据库，不修改共享基础设施。
