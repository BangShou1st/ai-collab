# AI Collab JMeter performance baseline

本目录只覆盖 **后端/HTTP 性能**，不改产品功能、不改默认开关、不接真实模型。产物用于本地或 staging 的可复现基线，不作为生产容量承诺。

## 1. 场景

| JMX | 负载模型 | 默认时长 | 目的 |
| --- | --- | ---: | --- |
| `smoke.jmx` | 1 用户，顺序执行 | 1 轮 | 健康检查、鉴权、种子数据、核心读路径契约 |
| `read-baseline.jmx` | 10 用户，12 条读路径随机 | 60s | 只读基线 |
| `mixed-baseline.jmx` | 8 用户，12 读 + 1 个三请求写生命周期 | 60s | 读写混合；请求级约 80/20 |
| `spike.jmx` | 5 预热 + 35 突发 | 30s + 30s | 并发突增的错误率与尾延迟 |
| `soak.jmx` | 6 用户，混合读写 | 600s | 泄漏、连接池、长时间稳定性 |
| `ai-stub.jmx` | 5 用户，内部模型网关 | 60s | 只打本地 200ms OpenAI-compatible stub，测 Java 网关开销 |
| `sse-smoke.jmx` | 2 用户 × 5 轮 | 1 轮/用户 | 知识库 SSE 协议烟测（无文档、无模型调用） |

选中路由均来自 Controller 的 `@RequestMapping`/`@GetMapping` 等注解，详见 `docs/acceptance-evidence/2026-11-12/perf-baseline/route-inventory.txt`。不猜测 URL。

## 2. 测试数据

`run.ps1 -Action seed` 会：

1. 用 `.env` 的 `DEMO_OWNER_USERNAME/PASSWORD` 登录，token 只在进程内使用，不落盘、不回显；
2. 新建专用项目 `[perf] jmeter <timestamp>`；
3. 建 2 个里程碑、80 个任务、1 个知识库会话；
4. 把 `projectId/taskId/sessionId` 写入结果目录的 `seed.json` 与 `testdata.local.csv`。

清理：`-Action cleanup` 优先调用产品删除 API；失败才用同项目 ID 的 SQL 删除兜底。只删除本次创建的项目。`-Action all` 默认在报告后清理，`-KeepTestData` 可保留。

## 3. AI stub（不压真实模型）

`ai-stub.jmx` 只调用 Java 的 `POST /internal/ai/v1/model-calls`，由它转发到本机 stub：

```powershell
node tests/performance/jmeter/stub-server.mjs --port 18080 --delay-ms 200
```

本地测试 provider 用 `tools/seed-ai-stub.ps1` 写入测试库；它把用户级 `KNOWLEDGE_CHAT` 临时指向 `http://127.0.0.1:18080`，并写入 AES-GCM 加密的合成 key。该脚本拒绝覆盖已有非本脚本的 `KNOWLEDGE_CHAT` 绑定。这是测试夹具，不是产品推荐配置；用户 API 的出站策略仍只允许公网 HTTPS。

后端启动时还必须带：

```text
--rerank.service-token=<ephemeral-test-token>
--rerank.model-user-id=<owner-user-id>
```

JMeter 的 `-JaiServiceToken` 必须与 `rerank.service-token` 相同。提示词只含固定短文本，stub 不记录请求正文。

AI 开销口径：`overheadMs = JMeter elapsed - response.latencyMs`，其中 `latencyMs` 是 Java 适配器测得的 provider 调用耗时。`ai-stub.jmx` 的 P95 门槛是 800ms；stub 固定 200ms 仅用于让模型时间可识别，不代表真实模型延迟。

## 4. 运行

依赖：JDK 21、Node.js 22+、Apache JMeter 5.6.3。Windows 建议 PowerShell 7+；脚本使用 `Invoke-RestMethod -NoProxy` 时会自动探测版本。

### Windows

```powershell
# 1) 启动后端（8090/8091）和基础设施；JMeter 5.6.3 可在 target/perf-tools 下
# 2) 首次：建测试数据
.\tests\performance\jmeter\run.ps1 -Action seed -ResultsDir .\target\perf\results\baseline

# 3) 全量基线（含两次 read-baseline、spike、soak、AI stub、SSE）
.\tests\performance\jmeter\run.ps1 -Action run `
  -ResultsDir .\target\perf\results\baseline `
  -AiServiceToken '<ephemeral-test-token>'

# 4) 生成 summary.csv / labels.csv / gate-summary.csv 并清理
.\tests\performance\jmeter\run.ps1 -Action summary -ResultsDir .\target\perf\results\baseline
.\tests\performance\jmeter\run.ps1 -Action cleanup -ResultsDir .\target\perf\results\baseline -CleanupAiStub
```

`-Action all` 等价于 seed → run → summary → cleanup。`-Quick` 只适合调试：缩短读场景并跳过 soak，报告会显示 `NOT_RUN`，不能当门禁证据。

### Linux/macOS

```bash
export JMETER_HOME=/opt/apache-jmeter-5.6.3
export TOKEN='<short-lived-access-token>'
export PROJECT_ID='...' TASK_ID='...' SESSION_ID='...'
export AI_SERVICE_TOKEN='<ephemeral-test-token>'   # 仅在跑 ai-stub 时需要
tests/performance/jmeter/run.sh
```

`run.sh` 不负责建测试数据；请先用 API 或 Windows 版 `run.ps1 -Action seed` 准备。JTL 原始文件保留在结果目录，**不提交**。

### 项目详情定向复测

```powershell
# 先 seed（或复用已有 seed.json），再做 warm-up + 3×10k 项目详情样本
.\tests\performance\jmeter\run.ps1 -Action followup -ResultsDir .\target\perf\followup -Threads 20 -Loops 500
node tests/performance/jmeter/tools/p95-followup.mjs `
  .\target\perf\official\read-baseline-b1.jtl `
  .\target\perf\official\read-baseline-b2.jtl `
  .\target\perf\followup
```

该复测只用于判断 per-label 4ms→5ms 是否是计时噪声/门禁敏感性；不会覆盖原始 `gate-summary.csv`。`tools/measure-stub.mjs` 可在不经过 Java/隧道的情况下测本地 stub 基线。

## 5. 门禁

| 门禁 | 判定 |
| --- | --- |
| 错误率 | ≤ 1% |
| 非预期 5xx | 0 |
| AI stub overhead P95 | ≤ 800ms |
| 基线 P95 对比 | `read-baseline-b2` 相对 `b1` 的同一 label P95 不劣化 >20% |

`tools/summarize.mjs` 只汇总，不修改阈值，不重跑失败样本，不把 5xx 归为成功。任一硬门禁失败时 exit 1，报告必须如实写 FAIL。

> 2026-11-13 口径收口：原始 `项目详情 4ms → 5ms（+25%）` per-label FAIL 继续有效，
> 不因场景级 `+7.9%` 或 follow-up 的 1ms 差异改判 PASS。完整说明见
> [`docs/archive/report/perf-baseline-per-label-closure-2026-11-13.md`](../../../docs/archive/report/perf-baseline-per-label-closure-2026-11-13.md)。

## 6. 环境与边界

- JMeter、后端、PostgreSQL 同机运行，存在资源竞争；结果只是 **local baseline**。
- 本地 Docker VM 的实际内存/CPU 与 4C/8G 目标环境可能不同，必须在报告中记录实测值。
- 不接真实模型；`w3-magpie-diagnostic` 的 3s 真实 provider FAIL 不能混入本目录容量结论。
- 不在默认 CI 中运行全量压测。需要手动性能门禁时，只运行 `smoke.jmx`，并且由人工触发。
- 访问 token 使用短期凭据；不要把 token、登录态、JTL 或大日志提交到仓库。
