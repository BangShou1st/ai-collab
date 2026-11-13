# 本地 Demo 加固交接

> 基线：`dev @ 8436ebeb`；按 `docs/design/local-demo-hardening-2026-11-14.md` 执行。
> 状态词：`IMPLEMENTED` / `TESTED(UNIT|INTEGRATION|REAL_PROCESS)` / `MEASURED` / `BLOCKED` / `NOT_RUN`。

## Wave 0

- 完成：lane registry、端口/迁移预留、六开关/MQ/worker/DENSE_LEGACY 基线。
- 基线提交：`873fc6f0`。
- 证据：`docs/acceptance-evidence/2026-11-14/local-hardening/baseline/`。

## Wave 1

| Lane | 集成提交 | 状态 | 主要证据 |
| --- | --- | --- | --- |
| A H-1 | `76937ab9` + 证据/文档提交 | TESTED(UNIT\|INTEGRATION\|REAL_PROCESS) | `lane-a-doc-delete/`，MinIO 停机与杀进程恢复 |
| B H-2 | `8cd23576` + 证据/文档提交 | TESTED(UNIT\|INTEGRATION)；OS 重启 NOT_RUN | `lane-b-token-revoke/`，46 unit + 7 真实 HTTP 集成 |
| C H-3 | `56077897` + 证据提交 | TESTED(UNIT\|INTEGRATION\|REAL_PROCESS)；后台标签 REAL_PROCESS NOT_RUN | `lane-c-approval-inbox/`，BSK 1.197s 可见变化 |
| D H-4 | `12a649c7` | TESTED(UNIT\|INTEGRATION)；MEASURED | `lane-d-task-list/`，服务层 10k P95 53ms / SQL 2 |
| F1 decide 候选 | `194dd755` | TESTED(UNIT\|INTEGRATION)；候选 PENDING_REVIEW | `lane-f1-decide-dataset/`，80 例待审候选 |

### Wave 1 中心门禁

- Java：第二轮 `mvnw verify` exit=0，3066 tests，0 failures/errors，61 skipped。
- 前端：lint/typecheck/test/build 均 exit=0；504 tests / 66 files。
- Python：1284 tests exit=0；1 个既有 StarletteDeprecationWarning。
- demo：MinIO 停机/恢复、DB 删除失败重试、停用/刷新/改密撤销、10k 任务与 BSK 看板均有证据。
- 待最终执行：push 后的 GitHub Actions CI。

### 遗留/复核提示

- D 的服务层 10k P95 为 53ms，但合并进程 API 实测 HttpClient P95 346ms、curl P95 1356.5ms；未调整阈值，原始数字见 `center-wave1/raw/demo6-*.json`。
- C 的 BSK 非活动标签仍报告 `visibilityState=visible`，后台暂停/focus 真实分支为 NOT_RUN；单元测试覆盖。
- F1 的 80 条候选全部 `PENDING_REVIEW`，未冻结、不能用于质量门禁。

## 下一步

1. Wave 2：E1 rerank shadow bring-up（0 代码）+ F2 decide Python 主链。
2. Wave 2 全绿后再开 G planning 主链。
3. 最终清理 lane worktree/分支归档、一次 push `dev`、dispatch/核对 CI。
