# Lane A 命令记录

所有命令工作目录为 `E:\project\ai-collab-wt\p0-doc-delete\ai-collab-backend`，日志写入
`target/lane-a-evidence/`，再复制到本目录 `raw/`。

| 步骤 | 命令 | 退出码 | 原始输出 |
| --- | --- | --- | --- |
| 编译 | `.\mvnw.cmd -B -ntp -DskipTests compile` | 0 | `target/lane-a-evidence/compile-initial.txt` |
| 测试编译 | `.\mvnw.cmd -B -ntp -DskipTests test-compile` | 0 | `raw/test-compile-final.txt` |
| 定向测试 | `.\mvnw.cmd -B -ntp "-Dtest=DocumentDeletionLifecycleIntegrationTest,DocumentDeletionMinioOutageIntegrationTest,DocumentDeletionResponseTest,DocumentApplicationServiceWriteStatusTest,DocumentDownloadAuthorizationTest" test` | 0 | `raw/targeted-tests-final.txt` |
| 文档域回归 | `.\mvnw.cmd -B -ntp "-Dtest=com.shitulelv.aicollab.document.**.*Test" test` | 0 | `raw/document-domain-tests.txt` |
| 打包 | `.\mvnw.cmd -B -ntp -DskipTests package` | 0 | `raw/package.txt` |
| 真实进程中断恢复 | `pwsh -NoProfile -ExecutionPolicy Bypass -File ..\docs\acceptance-evidence\2026-11-14\local-hardening\lane-a-doc-delete\scripts\run-real-process.ps1` | 0 | `raw/real-process-command.txt`、`raw/real-process-state.txt`、两份 app 日志 |

## 关键 SQL 状态查询

```sql
SELECT status || '|' || attempts
FROM document_deletion_job
WHERE project_id=? AND document_id=?;

SELECT count(*)
FROM project_document
WHERE project_id=? AND id=?;
```

真实进程结果：杀进程前 `PENDING|0`；重启后 `DONE|0`；文档行数 `0`。

## 清理

- 停止真实进程 PID 与独立 MinIO 容器；
- 删除专用数据库 `ai_collab_lh_a`；
- Testcontainers 容器由测试框架回收；
- 未停止共享 `ai-collab-postgres` / `ai-collab-redis` / `ai-collab-minio`。
