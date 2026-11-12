# A/B/C/D 集成后最终门禁报告

> 日期：2026-11-12（Asia/Shanghai）
> 仓库：`E:\project\ai-collab`
> 分支 / HEAD：`dev @ cfaf9d5f6b6b7a817a1f7a3889a7b8e5f04a55c7`
> 证据等级：本地门禁 `TESTED(offline/Testcontainers)`；不代表 staging 或生产 `LIVE_VERIFIED`
> 范围：只运行验证命令并新增本证据目录；未修改业务代码、默认开关、迁移或前端，未 commit/push，未创建分支/worktree

## 1. 总结论

| 门禁 | 结论 | 说明 |
| --- | --- | --- |
| 后端 `mvnw verify` | **PASS** | exit 0，`2900` tests / `0` failures / `0` errors / `61` skipped |
| Worker 全量 pytest | **PASS** | 清理宿主环境污染后 exit 0，`1177 passed` |
| Worker ruff check / format / mypy | **PASS** | 三项均 exit 0 |
| Prompt pack 校验 | **PASS** | `check` 与 `export-java --check` 均 exit 0 |
| 分支 / worktree / 迁移 / 默认开关 / 临时产物 | **PASS** | 未发现越界改动或默认值漂移 |

**总判定：PASS（本地集成门禁）。**  
首次 worker pytest 因宿主环境中的 `SPEC_AGENT_TAVILY_API_KEY` 命中 worker 的 P0 密钥禁入规则，在测试收集阶段退出 2；该变量与仓库无关。去掉此单一环境变量后的正式全量重跑通过，详细口径见 §3。

## 2. 后端全量验证

| 项 | 值 |
| --- | --- |
| 命令 | `cd ai-collab-backend; .\mvnw.cmd -B -ntp verify` |
| Exit code | `0` |
| Maven 结果 | `BUILD SUCCESS` |
| Tests run | `2900` |
| Failures | `0` |
| Errors | `0` |
| Skipped | `61` |
| 总耗时 | `20:20 min` |
| 失败用例 | 无 |

Surefire XML 独立汇总与 Maven 输出一致：422 个报告文件，`2900/0/0/61`。原始日志：[maven-verify.txt](raw/maven-verify.txt)，计数汇总：[test-counts.txt](raw/test-counts.txt)。

### Flaky 观察

此前由 D 泳道报告的时序敏感用例 `ConnectorSyncReliabilityIntegrationTest.activeLongTaskRenewsLeaseThroughHeartbeat` 所在测试类，在本次全量运行中完成 12 个用例，`Failures=0, Errors=0, Skipped=0`。**本次未复现失败，也未单独复跑该用例**；上述全量 PASS 以其在全量套件中实际通过为准。

### 非阻断观察

Maven 日志在测试完成、`BUILD SUCCESS` 之前出现：

`Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`

该信息没有改变测试结果或 Maven exit code（仍为 0），但说明测试 fork JVM 退出阶段曾有约 30 秒等待。本报告不将其写成 FAIL，也不据此推断功能缺陷。

## 3. Worker / Python 门禁

### 3.1 全量 pytest

正式命令：

```powershell
cd ai-collab-ai-worker
$env:SPEC_AGENT_TAVILY_API_KEY = $null  # 仅清理宿主环境，未改仓库或测试
uv run --frozen pytest -q
```

| 项 | 值 |
| --- | --- |
| Exit code | `0` |
| 结果 | `1177 passed, 1 warning in 212.54s (0:03:32)` |
| 失败用例 | 无 |

原始日志：[worker-pytest.txt](raw/worker-pytest.txt)。

#### 首次环境阻断（如实记录）

在未清理宿主环境时，原命令 exit `2`，9 个测试模块在 collection 阶段被拒绝，0 个测试实际执行。原因是宿主环境存在 `SPEC_AGENT_TAVILY_API_KEY`，命中 `assert_env_allowlist` 的 `_API_KEY` P0 后缀规则：

`ai_collab_worker.config.UnsafeEnvironmentError`

首次原始输出保留在 [worker-pytest-initial-env-block.txt](raw/worker-pytest-initial-env-block.txt)。变量值未读取、未回显、未写入任何证据文件。该阻断不是测试断言失败，也不是仓库内的默认配置；正式重跑只在子 PowerShell 进程中移除该单一变量，命令和测试集合未变。

### 3.2 静态检查

| 命令 | Exit code | 结果 | 原始日志 |
| --- | --- | --- | --- |
| `uv run --frozen ruff check .` | `0` | `All checks passed!` | [worker-ruff-check.txt](raw/worker-ruff-check.txt) |
| `uv run --frozen ruff format --check .` | `0` | `164 files already formatted` | [worker-ruff-format-check.txt](raw/worker-ruff-format-check.txt) |
| `uv run --frozen mypy` | `0` | `Success: no issues found in 63 source files` | [worker-mypy.txt](raw/worker-mypy.txt) |

## 4. Prompt pack 校验

| 命令 | Exit code | 结论 | 原始日志 |
| --- | --- | --- | --- |
| `uv run --frozen ai-prompts check` | `0` | PASS | [ai-prompts-check.txt](raw/ai-prompts-check.txt) |
| `uv run --frozen ai-prompts export-java --check` | `0` | PASS | [ai-prompts-export-java-check.txt](raw/ai-prompts-export-java-check.txt) |

两项命令均按仓库实际入口执行，没有发现需要替代命令名的情况。

## 5. 仓库只读核对

| 检查项 | 结论 | 实测 |
| --- | --- | --- |
| `git status --short --branch` | **PASS** | `## dev...origin/dev [ahead 15]`；唯一未跟踪项为本次证据目录 |
| `git worktree list` | **PASS** | 仅 `E:/project/ai-collab cfaf9d5f [dev]` |
| `git branch --list` | **PASS** | 仅有 `dev`、`main` |
| `git diff --name-only HEAD` | **PASS** | 空 |
| 前端改动（相对冻结基线 `d4247c34`） | **PASS** | `ai-collab-frontend` 无 diff |
| 历史迁移改动（相对 `d4247c34`） | **PASS** | 迁移目录无 diff |
| 迁移目录 | **PASS** | `V1–V91`，共 91 个；最新 `V91__ai_job.sql` |
| 根目录临时产物 | **PASS** | `*.log/*.tmp/*.bak` 计数 0 |
| 源码树临时产物 | **PASS** | `ai-collab-{backend,frontend,ai-worker}/src` 下计数 0 |
| 证据目录外未跟踪文件 | **PASS** | 计数 0 |
| `git log -15` | **PASS** | 原始输出见 [final-audit.txt](raw/final-audit.txt) |

证据目录自身为未跟踪状态，符合“只新增指定证据目录”的约束；未创建分支/worktree，未 commit，未 push。

## 6. 六个默认开关

| 开关 | 要求 | 当前默认 | 与 `d4247c34` 对比 |
| --- | --- | --- | --- |
| `RERANK_MODE` | `java` | `java` | 相同 |
| `DECIDE_MODE` | `java` | `java` | 相同 |
| `PLANNING_DRAFT_MODE` | `java` | `java` | 相同 |
| `MEMORY_JOB_ENABLED` | `false` | `false` | 相同 |
| `INTERNAL_AI_TOKEN_MODE` | `static` | `static` | 相同 |
| `AI_WORKER_TOKEN_MODE` | `static` | `static` | 相同 |

来源与逐项基线对比见 [default-switches.txt](raw/default-switches.txt)、[final-audit.txt](raw/final-audit.txt)。

## 7. 原始证据索引

- [maven-verify.txt](raw/maven-verify.txt)：Maven 全量原始输出及 exit code
- [test-counts.txt](raw/test-counts.txt)：Maven / pytest 计数汇总
- [worker-pytest.txt](raw/worker-pytest.txt)：worker 正式全量 pytest 原始输出
- [worker-pytest-initial-env-block.txt](raw/worker-pytest-initial-env-block.txt)：首次宿主环境阻断原始输出
- [worker-ruff-check.txt](raw/worker-ruff-check.txt)
- [worker-ruff-format-check.txt](raw/worker-ruff-format-check.txt)
- [worker-mypy.txt](raw/worker-mypy.txt)
- [ai-prompts-check.txt](raw/ai-prompts-check.txt)
- [ai-prompts-export-java-check.txt](raw/ai-prompts-export-java-check.txt)
- [repo-audit.txt](raw/repo-audit.txt)：运行期间只读审计快照
- [final-audit.txt](raw/final-audit.txt)：测试完成后的最终只读审计
- [default-switches.txt](raw/default-switches.txt)：六项开关来源与基线值
- [environment.txt](raw/environment.txt)：Java、Docker、uv、HEAD 版本信息

## 8. 未覆盖范围

本报告回答本地集成 HEAD 上的编译、全量测试、静态检查、Prompt pack 一致性、仓库边界核对，以及 GitHub Actions CI 门禁。未重新执行 staging/live provider 评测、真实模型调用或生产灰发验证，因此不将本次 PASS 外推为 `LIVE_VERIFIED`。

## 9. GitHub Actions CI

| 项 | 值 |
| --- | --- |
| Run | [43220484571](https://github.com/BangShou1st/ai-collab/actions/runs/43220484571) |
| Workflow | `CI` #412（`dev` push） |
| HEAD | `97c0ea7c88cec0dbb5bfe2c2774f198238b727e7` |
| Status / conclusion | `completed` / `success` |
| `gh run watch --exit-status` | exit `0` |
| Jobs | 9：7 success、2 skipped、0 failure |
| 失败 job / 失败 step | 无 |

| Job | 结论 | 耗时 |
| --- | --- | --- |
| P0 production configuration and backup contracts | **success** | 11s |
| Python worker (contract + prompts + eval) | **success** | 54s |
| Frontend (pnpm) | **success** | 1m32s |
| Backend tests (shard 1/3) | **success** | 5m26s |
| Backend tests (shard 2/3) | **success** | 8m43s |
| Backend tests (shard 3/3) | **success** | 6m28s |
| Backend (Maven verify) | **success** | 10s |
| Image build & container smoke | skipped | 0s |
| Publish images to GHCR | skipped | 0s |

两个 skipped job 在 `dev` push 下不满足 workflow 的运行条件，属于预期跳过，不计为失败：镜像冒烟 job 只在 PR、手动触发、`main` push 或版本 tag 时运行；GHCR 发布 job 只在 `main` push 或版本 tag 时运行。

`gh run view` 还显示了若干 annotation，包括 Node.js 20 / `setup-java` v4 / Ubuntu 26 迁移弃用提示、`AgentView.vue` 的 lint/XSS 相关提示，以及 Maven Wrapper bootstrap 第一次尝试失败后自动重试成功。所有实际 step 的最终结论均为 success，未发现失败 step；这些 annotation 不改变本 run 的 `success` 结论。

原始 `gh run watch`、最终 `gh run view` JSON/标准输出及解析摘要：[ci-run-43220484571.txt](raw/ci-run-43220484571.txt)。
