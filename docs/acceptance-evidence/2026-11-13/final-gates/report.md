# 最终集成门禁（2026-11-13）

> 基线：`dev @ c166292d`（报告提交前）
> 范围：Java / Python 全量；前端未改，未跑前端门禁
> 证据等级：以原始日志为准；Java 全量 `verify` **未绿**，不写成 PASS

## 1. 结果

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java `mvnw -B -ntp verify` | **FAIL（Testcontainers 环境抖动）** | 2949 tests / 4 errors / 0 failures / 61 skipped；4 个错误均为 Testcontainers Postgres 连接型 `NoSuchElement`，失败类每轮变化 |
| Java 失败类定向复跑 | **116/116 PASS** | `AgentCrashRecoveryTimingPostgresTest`、`AgentDelegationPostgresTest`、`AgentRemoteOutcomePostgresTest`、`AgentRepositoryIntegrationTest` |
| `RerankCapabilityMigrationIntegrationTest` | **3/3 PASS** | 动态读取 Flyway，V1–V97 全部应用，无 pending |
| Python `uv run --frozen pytest -q` | **1180 passed, 1 warning** | 261.61s |
| 前端 | **NOT_RUN** | 本轮无前端代码改动 |
| GitHub CI | **success** | run `43299787847` @ `2275c1cf`：Backend 3 shards、Python、Frontend、P0 全绿 |

## 2. Java 全量失败归属

第二轮全量 `verify` 的错误：

- `AgentCrashRecoveryTimingPostgresTest.normalRoundEndAndRetryWaitUseTheSameAccumulation`
- `AgentDelegationPostgresTest.legacyDelegationRecordWithoutCoverageIsInjectedAsUnknown`
- `AgentRemoteOutcomePostgresTest.t07OldClaimIsFencedAndTakeoverDoesNotResend`
- `AgentRepositoryIntegrationTest.refreshedOptimisticVersionCannotBypassOldClaimEpoch`

日志中为 `PGStream.createSocket` 连接失败 / `NoSuchElement`；同一批类单独复跑 116/116 PASS。
第一轮全量的迁移计数失败已由 A 的 `a30d2a63` 修复并验证，不是本轮剩余失败。

**结论：代码侧无新增失败；全量 Java 门禁受本机 Testcontainers/Docker 并发连接抖动影响，记录为 FLAKY，不得写成全绿。**

## 3. 原始证据

- `raw/java-verify-full-tail.txt`：全量失败摘要尾部
- `raw/java-targeted-116.txt`：4 个失败类 + 迁移测试定向复跑
- `raw/python-pytest-1180.txt`：Python 全量

## 4. 限制

- 全量 Java 未绿，发布前若要求 CI 同命令全绿，需要在 CI/更稳定 Docker 环境重跑；本机结果不能替代 CI。
- 前端未改，未跑 lint/typecheck/test/build；若后续改前端必须补跑。
