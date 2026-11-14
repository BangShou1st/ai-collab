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

## Wave 2 进展

- E1：`MEASURED`。worker 镜像构建、`/healthz`/`/readyz`、真实 Magpie rerank 均有原始输出；2 并发 30/30、P95 4.841s；8 并发 42/50、8 个 Java 网关 503。知识主链固定 `DENSE_LEGACY`，`rerank.shadow.*` 无触发路径，一致率 `NOT_RUN`。证据：`docs/acceptance-evidence/2026-11-14/local-hardening/e1-rerank-shadow/`。
- F2：分支 `7aea28da` 已 cherry-pick 为 `c6eff5b1`；全量 Java 3072 项 exit=0，集成分支定向 76 项 exit=0。受限主链只覆盖根运行、只读 `PROJECT_RESEARCH`，默认仍 `java`；真实 worker 双跑 `NOT_RUN`。
- G：已启动，分支 `hardening/g3-planning-mainchain`，默认仍 `java`。

## Wave 3

- G：提交 `e581e222` 已 cherry-pick 为 `bec580e4`。
- `PlanningDraftMode.PYTHON` 可启动且默认仍 `java`；`PlanningDraftRouter` 只接管 skeleton，Python 草稿必须经 `PlanningDraftParser` + `TaskPlanDraftValidator(AI_SKELETON)`，detail 仍 Java。
- G 定向 59/59，集成分支 59/59；G 分支第三轮全量曾被 3 个既有 Agent Postgres 时序用例拖成 exit=1，4 类单独复跑 116/116。
- 主分支最终全量复跑 exit=0：3090 tests，0 failures/errors，61 skipped；最终前端 504 tests、Python 1284 tests 均 exit=0。
- 真实 Python planning worker 双跑仍为 `NOT_RUN`；不写规划质量结论。

## 最终状态与剩余动作

- 本地门禁：Java 3090（exit=0）、前端 lint/typecheck/test/build（exit=0）、Python 1284（exit=0）。
- 六开关/Gateway/MQ 未改；V102/V103 未占用；迁移仍 V1–V101。
- 已完成：7 条 lane 分支打 `archive/20261114/*` tag；git branch 仅剩 `dev/main`；worktree list 仅剩主 worktree。`E:\project\ai-collab-wt` 下最后残留的 `p1-approval-inbox` 空目录被外部句柄占用，非 git worktree，已记录。
- 待执行：一次 push `dev`、GitHub Actions CI、最终报告。
- 未执行/缺口：C 后台标签真实暂停、F2/G 真实 Python worker 双跑、E1 Java shadow 一致率（无生产主链触发）、D HTTP 层 P95 已记录高于服务层。
