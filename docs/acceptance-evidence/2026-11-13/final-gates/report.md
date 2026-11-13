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
| GitHub CI | **success** | run `43299787847` @ `2275c1cf`；最终 HEAD run `43301480075` @ `476c8679`（含 Image build & container smoke）全绿 |

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

---

## 5. W3/W4 收口复核（HEAD `d909c60c`）

### 5.1 结果

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java 本地全量首跑 | **1 failure（已定位）** | 2955 tests；`RerankOverheadBudgetTest` 仍按 20/50 inline 采样，期望 522 实得 182；是策略变更后的测试口径漂移 |
| Java Rerank 定向（修复后） | **28/28 PASS** | 8 候选 510 样本 overhead P95 22.992ms / P99 29.902ms；9/20/50 拒绝 inline |
| Python 全量 | **1182 passed, 1 warning** | 1051.72s |
| Python lint / format / type | **PASS** | ruff check、ruff format --check、mypy 均 0 |
| 前端（CI） | **PASS** | lint / typecheck / test / build |
| Python worker（CI） | **PASS** | lint / format / type / test |
| Java 三片 Testcontainers（CI） | **PASS** | shard 1/2/3 全绿；Maven verify 汇总与 container-test 断言通过 |
| 镜像构建 / 发布（CI） | **PASS** | `Image build & container smoke`、`Publish images to GHCR` 全绿 |
| CI run | **success** | `43350669200` @ `d909c60c` |
| W4 Magpie live | **SKIPPED / NOT_VERIFIED** | 用户要求暂跳过扫码；不写 PASS/FAIL |

### 5.2 本机 Java 首跑失败归属

`RerankOverheadBudgetTest` 原先断言 `samples(510) + warmup(12) = 522` 条 overhead；
W3 策略生效后 20/50 候选不再进入 inline，因此实际只有 8 候选的 182 条样本。
这是测试没有跟随产品边界更新，不是运行期缺陷。

已改为 8 候选 510 次 inline 样本，并保留 8/20/50 候选拒绝测试；修复后
`RerankOverheadBudgetTest + RerankModeRouterTest + ConfiguredRerankerTest +
RerankPropertiesBindingTest` 共 28/28 PASS。

### 5.3 原始证据

- `raw/ci-run-d909c60c.json`：CI run/job 原始 JSON
- `raw/java-verify-r1-summary.txt`：本机全量首跑摘要
- `raw/java-verify-r1-tail.txt`：本机全量首跑尾部
- `raw/java-rerank-targeted.txt`：修复后 Java 定向
- `raw/python-pytest-summary.txt`：Python 全量结果
- `raw/python-ruff-check.txt`、`raw/python-mypy.txt`
