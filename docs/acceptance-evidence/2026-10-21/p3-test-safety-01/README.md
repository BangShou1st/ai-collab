# P3-TEST-SAFETY-01 — 真实 Provider 测试安全验收

## 范围与历史

本任务只改动测试代码和本验收记录，没有修改 Gemini 生产适配器、Agent/RAG/规划业务、数据库、迁移或默认 Provider。

上一轮 `P3-GEMINI-COMPAT-03` 已实际发生过一次意外真实 Gemini 请求并成功返回。本任务没有读取桌面 `key2.txt`，也没有发起任何真实 Provider 请求。

## 发现与修复

原 `GeminiLiveAcceptanceTest` 的 `@BeforeAll` 会读取固定个人桌面路径 `C:\Users\32962\Desktop\key2.txt`；API Key 非空即可让七项 Live 用例继续调用 Provider。该入口还会修改 `https.proxyHost`、`https.proxyPort`、`http.proxyHost`、`http.proxyPort`、`java.net.useSystemProxies`，而清理逻辑会直接清除部分属性。若重试耗尽返回 `null`，部分用例又会直接 `return`，产生绿色但未完成的验收结果。

现状如下：

- 删除固定桌面路径读取、自动发现凭据、`@BeforeAll`/`@AfterAll` 网络生命周期以及所有代理系统属性修改。
- 凭据只从 `GEMINI_API_KEY` 环境变量读取；读取前必须同时满足开关严格等于 `true`、Surefire 目标明确选择 `GeminiLiveAcceptanceTest`。没有目标过滤的普通 `verify` 即使继承了开关和 API Key，也不能访问凭据或 Provider。
- 未授权或缺少凭据时以 JUnit `SKIPPED` 处理。离线错误分类用例不经过 Live 凭据门控，继续执行。
- 503 最多尝试 3 次，两次重试间隔为 3 秒和 6 秒；持续 503 以带 `INCONCLUSIVE` 原因的 JUnit aborted 结束，报告为 skipped 而非 PASS。模型超时和 SSE 20 秒未回调也标为 `INCONCLUSIVE`；其他错误继续失败。移除了把空结果直接 `return` 的路径。
- 测试的 Provider 客户端只在门控通过后创建。JVM 全局代理状态不再被修改。

## 有意运行 Live 测试

先通过受控方式把 `GEMINI_API_KEY` 注入运行测试的进程环境，不要放进命令参数、仓库、日志或验收记录。然后显式打开开关并只选 Live 类：

```powershell
$env:AI_GEMINI_LIVE_TESTS = 'true'
.\mvnw.cmd -B -ntp "-Dtest=GeminiLiveAcceptanceTest" test
```

完成后可在当前 PowerShell 会话中移除开关：

```powershell
Remove-Item Env:AI_GEMINI_LIVE_TESTS -ErrorAction SilentlyContinue
```

只有开关、目标类选择和环境变量凭据三项同时满足才会发出真实请求。显式选择该类且开关为 `true`、但不提供 API Key 时，七项 Live 用例仍会在读取到空凭据后安全跳过。

## 同类测试有界检查

检查了其余真实 Provider opt-in 测试和网络相关测试辅助代码。`OpenCodeZenSmokeTest` 由 `AI_ZEN_SMOKE=true` 显式开启，Ollama smoke 由 `AI_OLLAMA_SMOKE=true` 开启；其余 `AI_REAL_*` 相关用例也使用精确 `true` 条件。该范围内没有发现仅凭 API Key 自动调用、硬编码个人凭据路径、耗尽后静默通过或改写全局代理属性的同类问题，因此没有扩展修改范围。

## 本地验证

| 检查 | 结果 |
| --- | --- |
| `GeminiLiveTestSupportTest` | 16 项通过，0 failures/errors/skipped；覆盖无授权零 Provider 回调、开关严格值、缺凭据、授权、非 Live 目标拒绝、503 重试耗尽、成功重试、超时及其他错误状态、类加载时不修改代理状态 |
| `GeminiLiveAcceptanceTest` 默认路径 | 8 项：7 项 Live `SKIPPED`，离线错误分类 1 项通过 |
| 显式开关 + 只选 Live 类 + 测试进程移除 `GEMINI_API_KEY` | 8 项：7 项因缺少凭据跳过，离线分类通过；确认 Surefire 目标选择检查已进入 fork 测试 JVM；未发起请求 |
| 后端 `mvnw -B -ntp verify` | `BUILD SUCCESS`，1598 项，0 failures、0 errors、25 skipped；耗时 8:24 |
| Testcontainers CI 守卫同一 10 个必需类 | 10/10 类均执行，0 整类跳过，0 failures/errors；`RetrievalCoveragePostgresTest` 的 16 项中有 1 项条件跳过 |
| `git diff --check` | 通过 |

完整验证报告中的 25 项 skipped 已与通过数分开统计：7 项 Gemini Live 未授权跳过，17 项既有 opt-in/主机能力门控验收用例跳过，另有 `RetrievalCoveragePostgresTest` 中 1 项条件跳过。跳过项均未计作 PASS。

本地完整 `verify` 在子进程范围内移除了 `AI_GEMINI_LIVE_TESTS` 开关，没有排除 Gemini Live 测试类，也没有读取桌面凭据文件。静态门控证明 API Key 单独存在不构成授权；报告同时确认离线分类测试确实执行。因此本任务验证没有真实 Gemini 出站请求。

## Git 与 CI

- 起始 `dev`：`eb2449d59cca0894c8ed83b1702d082c40d000c2`。
- `main` / `origin/main` 始终为：`6303b4a8011ebb1b3f9617df3e910ef7c7f65df7`，未修改。
- 代码提交：`0be78549bcd671a711fcd6a6d7adc63fd504029f`（移除固定 Key 路径并实现显式授权与失败状态收口）。
- 目标选择补强提交：`2b34d97cbb21d9cdbbd1df8acd2a6eb4535db30d`（环境开关之外再要求 Surefire 明确选择 Live 类）。该 SHA 已推送 `origin/dev`。
- 该代码 SHA 的 GitHub Actions CI：[Run `39546204105`](https://github.com/BangShou1st/ai-collab/actions/runs/39546204105)，attempt 1，head SHA `2b34d97cbb21d9cdbbd1df8acd2a6eb4535db30d`，结论 `success`。Backend job `119044839462` 和 Frontend job `119044839793` 均成功；Backend Surefire 为 1598 项、25 skipped、206 个测试类，10 类 Testcontainers 守卫通过。

任务完成后的代码与本记录可分别用普通 `git revert <commit>` 回退；无需改写历史或影响 `main`。
