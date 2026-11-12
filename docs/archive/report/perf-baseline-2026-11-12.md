# AI Collab JMeter 性能基线（本地，2026-11-12）

> 仓库：`E:\project\ai-collab`
> 分支 / HEAD：`perf/jmeter`（worktree）基于 `dev @ bfd8fd53`
> 环境：本机 Windows + Docker Desktop；后端跑在宿主机，PostgreSQL/Redis/MinIO 跑在 Docker
> 证据等级：`TESTED (local, co-located baseline)`；不是 staging/production `LIVE_VERIFIED`，也不是容量上限
> 结论：**FAIL（仅一项冻结的 per-label P95 对比门禁）**；错误率、5xx、AI stub overhead 三项硬门禁 PASS

---

## 0. 一句话结论

本轮在本地完成 8 个 JMeter 场景、共 **522,806** 个 HTTP 样本：**0 errors、0 HTTP 5xx、无连接池/线程池耗尽**；AI stub 网关开销 **P95 6ms**（门槛 800ms）。唯一失败项是预先冻结的 **per-label P95 基线对比**：项目详情 `4ms → 5ms`（**+25%**，绝对差 1ms）越过 20% 线。场景级只读 P95 为 `63ms → 68ms`（+7.9%），在 20% 内，但这不能替代 per-label 门禁结论。

因此：

- **硬门禁总判定：FAIL**；不通过修改阈值、删场景或把 5xx 当成功来凑绿。
- 读写主路径、spike、10 分钟 soak 均未出现功能错误；本地性能表现稳定，但 **不能外推生产容量**。
- AI 路径只打本地 stub；真实模型 3s 诊断仍是 W3 的 FAIL，不混入本报告。

---

## 1. 场景与负载

所有 URL 均从 Controller 的 `@RequestMapping`/`@GetMapping`/`@PostMapping` 等注解核对，不猜路径。完整清单见 [route-inventory.txt](../../acceptance-evidence/2026-11-12/perf-baseline/route-inventory.txt)（43 个 Controller，261 条路由注解）。

| 场景 | JMX | 负载 | 结果 |
| --- | --- | --- | --- |
| smoke | `smoke.jmx` | 1 用户 × 1 轮；health + 13 个核心读路径 | 14 样本，0 错误 |
| read-baseline B1/B2 | `read-baseline.jmx` | 10 用户，60s，12 条读路径随机 | 51,620 / 48,970 样本，均 0 错误 |
| mixed-baseline | `mixed-baseline.jmx` | 8 用户，60s，12 读 + 1 个 create→update→delete 写生命周期 | 37,289 样本，0 错误 |
| spike | `spike.jmx` | 5 预热用户 + 35 突发用户（10s 后启动），各 30s | 28,714 样本，0 错误 |
| soak | `soak.jmx` | 6 用户，600s，混合读写 | 355,810 样本，0 错误 |
| ai-stub | `ai-stub.jmx` | 5 用户，60s，经 Java 内部模型网关打本地 200ms stub | 379 样本，0 错误 |
| sse-smoke | `sse-smoke.jmx` | 2 用户 × 5 轮，知识库无文档会话 | 10 样本，0 错误 |

选中路由：

```text
GET  /actuator/health
GET  /api/v1/home
GET  /api/v1/projects
GET  /api/v1/projects/{projectId}
GET  /api/v1/projects/{projectId}/dashboard
GET  /api/v1/projects/{projectId}/members
GET  /api/v1/projects/{projectId}/tasks
GET  /api/v1/projects/{projectId}/tasks/{taskId}
GET  /api/v1/projects/{projectId}/milestones
GET  /api/v1/projects/{projectId}/visualization/gantt
GET  /api/v1/projects/{projectId}/visualization/member-load
GET  /api/v1/projects/{projectId}/audit-logs?page=1&size=20
GET  /api/v1/notifications/unread-count
GET  /api/v1/projects/{projectId}/knowledge/sessions
POST /api/v1/projects/{projectId}/tasks
PATCH /api/v1/projects/{projectId}/tasks/{taskId}
DELETE /api/v1/projects/{projectId}/tasks/{taskId}
POST /api/v1/projects/{projectId}/knowledge/sessions/{sessionId}/questions/stream
POST /internal/ai/v1/model-calls
```

登录只用于 seed 阶段，不在压测场景内；没有对 auth 端点做吞吐结论。

---

## 2. 结果

原始聚合见 [summary.csv](../../acceptance-evidence/2026-11-12/perf-baseline/summary.csv) 和 [labels.csv](../../acceptance-evidence/2026-11-12/perf-baseline/labels.csv)。下表 elapsed 为场景级聚合；AI 的 elapsed 含公网隧道 provider 时间，不是模型容量。

| 场景 | 样本 | 错误 | 5xx | P50 | P95 | P99 | Max | 场景门禁 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| smoke | 14 | 0 | 0 | 6ms | 42ms | 42ms | 42ms | PASS |
| read-baseline B1 | 51,620 | 0 | 0 | 4ms | 63ms | 82ms | 212ms | PASS |
| read-baseline B2 | 48,970 | 0 | 0 | 5ms | 68ms | 85ms | 127ms | PASS |
| mixed-baseline | 37,289 | 0 | 0 | 5ms | 68ms | 86ms | 185ms | PASS |
| spike | 28,714 | 0 | 0 | 25ms | 148ms | 213ms | 411ms | PASS |
| soak | 355,810 | 0 | 0 | 4ms | 55ms | 69ms | 302ms | PASS |
| ai-stub | 379 | 0 | 0 | 750ms | 1,374ms | 1,865ms | 2,414ms | PASS |
| sse-smoke | 10 | 0 | 0 | 50ms | 3,321ms | 3,321ms | 3,321ms | PASS |

补充：

- 场景级只读 P95：`63ms → 68ms`（**+7.9%**），在 20% 内；这是背景指标，不替代 per-label 门禁。
- SSE 的 P95 被前 2 个冷样本抬高：`3,321ms / 2,874ms`，其余 8 个样本为 `45–67ms`；全部 200、`event:done` 存在。见 [sse-latency.txt](../../acceptance-evidence/2026-11-12/perf-baseline/sse-latency.txt)。

---

## 3. 硬门禁

门禁定义固定在 `tests/performance/jmeter/thresholds.csv`，运行前已写入仓库，未在结果出来后调整。

| 门禁 | 门槛 | 实测 | 判定 |
| --- | --- | --- | --- |
| 错误率 | ≤1% | 0 / 522,806 = **0%** | **PASS** |
| 非预期 5xx | 0 | **0** | **PASS** |
| AI stub overhead P95 | ≤800ms | **6ms** | **PASS** |
| 基线 per-label P95 不劣化 | ≤20% | 项目详情 **4ms → 5ms（+25%）** | **FAIL** |

失败项细节：

- 对象：`GET /api/v1/projects/{projectId}`，B1 4,371 样本 / B2 4,150 样本。
- P95：`4ms → 5ms`，绝对差 **1ms**，相对 **+25%**。
- 同一对比中其他 11 个 label 均为 `0%` 或 `+2.17%`；场景级 P95 为 `+7.9%`。
- 这是预先冻结的 per-label 规则下的真实 FAIL；1ms 的绝对差很可能来自计时粒度、缓存/JIT 或本机噪声，但**没有证据证明它不是回归**，所以不写成 PASS。复测建议见 §8。

证据： [gate-summary.csv](../../acceptance-evidence/2026-11-12/perf-baseline/gate-summary.csv)、[comparison-baseline.csv](../../acceptance-evidence/2026-11-12/perf-baseline/comparison-baseline.csv)。

混合读写相对只读的诊断性对比（**不是门禁**）中，dashboard P95 `14ms → 18ms（+28.57%）`；见 [comparison-mixed-vs-read.csv](../../acceptance-evidence/2026-11-12/perf-baseline/comparison-mixed-vs-read.csv)。

### 3.1 Follow-up re-measurement（独立复测，不覆盖原 FAIL）

只复测 `GET /api/v1/projects/{projectId}`：先 warm-up 500 样本，再跑 3 轮 × 10,000 样本（20 threads × 500 loops），全部 0 错误。P95 用 1,000 次 bootstrap 估计 95% CI；原 `gate-summary.csv` 和 `comparison-baseline.csv` 未改动。

| 数据集 | 样本 | P95 | P95 95% CI | 对 B1（4ms） | 对 B2（5ms） |
| --- | ---: | ---: | --- | ---: | ---: |
| baseline B1 | 4,371 | 4ms | [4, 5] | — | — |
| baseline B2 | 4,150 | 5ms | [5, 5] | — | — |
| warm-up | 500 | 5ms | [5, 6] | +25% | 0% |
| follow-up R1 | 10,000 | 6ms | [6, 6] | +50% | +20% |
| follow-up R2 | 10,000 | 5ms | [5, 5] | +25% | 0% |
| follow-up R3 | 10,000 | 7ms | [7, 7] | +75% | +40% |
| pooled baseline | 8,521 | 5ms | — | — | — |
| pooled follow-up | 30,000 | 6ms | — | +20%（+1ms） | +20%（+1ms） |

**Follow-up 判定：FAIL / not a clean pass。** 复测没有回到 4ms，端点实际落在 **5–7ms**；合并 P95 为 `5ms → 6ms`（+1ms，正好 20% 边界），但 R3 对 B2 为 `+40%`（+2ms）。因此：

- 原始 `B2 vs B1 = +25%` 的 **FAIL 保留**，不覆盖。
- 这不是稳定的代码回归证据，也不能写成 measurement-noise-only PASS；更准确的口径是 **1ms 计时粒度/门禁敏感性 + 本机 run variance**。
- SQL/索引/序列化检查：实际 mapper 的 `project JOIN project_member` 为 **0.075ms**、3 shared buffers，37/42 行小表上的 nested loop + seq scan（小表正常）；`ProjectView` 只有 12 个标量字段。当前没有证据指向 SQL/索引/序列化缺陷。

证据：[followup-p95.csv](../../acceptance-evidence/2026-11-12/perf-baseline/followup-p95.csv)、[followup-comparison.csv](../../acceptance-evidence/2026-11-12/perf-baseline/followup-comparison.csv)、[followup-notes.txt](../../acceptance-evidence/2026-11-12/perf-baseline/followup-notes.txt)、[project-detail-sql.txt](../../acceptance-evidence/2026-11-12/perf-baseline/project-detail-sql.txt)。

---

## 4. AI stub 口径

真实模型调用被明确排除。`ai-stub` 的链路是：

```text
JMeter -> Java POST /internal/ai/v1/model-calls
       -> 公网 HTTPS Cloudflare quick tunnel
       -> 本机 Node OpenAI-compatible stub（固定 200ms）
```

为什么走隧道：产品出站策略 `OutboundEndpointPolicy` 只允许公网 HTTPS，直接写 `http://127.0.0.1` 会被 `VALIDATION_ERROR` 拒绝；隧道让真实 Java 适配器/HTTP 客户端链路保持不变，同时不调用任何真实模型。

| 指标 | P50 | P95 | P99 |
| --- | ---: | ---: | ---: |
| elapsed（含隧道） | 750ms | 1,374ms | 1,865ms |
| provider `latencyMs`（含隧道） | 746ms | 1,371ms | 1,863ms |
| **网关 overhead（elapsed - provider）** | **4ms** | **6ms** | **15ms** |

379 次调用全部 200，deadline 3000ms 未触发；provider P95 1,371ms 是公网隧道成本，不是模型时间。证据：[ai-stub-notes.txt](../../acceptance-evidence/2026-11-12/perf-baseline/ai-stub-notes.txt)、[ai-stub-console.txt](../../acceptance-evidence/2026-11-12/perf-baseline/ai-stub-console.txt)。

**不能**把该 6ms 写成“真实模型网关开销”或“生产 AI 容量”；它只证明 Java 内部模型网关在 stub 链路上的额外开销很低。

拆解 1,374ms elapsed：

| 组成 | P50 | P95 | 说明 |
| --- | ---: | ---: | --- |
| 本地 stub 固定延迟 | 200ms | 200ms | Node stub 代码常量 |
| 本地 stub 直连，1 并发 | 202.33ms | 204.65ms | 不经过隧道/Java |
| 本地 stub 直连，5 并发 | 203.34ms | 217.12ms | 5 并发无明显排队 |
| 官方 provider `latencyMs` | 746ms | 1,371ms | 含 Cloudflare quick tunnel |
| 官方网关 elapsed | 750ms | 1,374ms | JMeter 总耗时 |
| **官方网关 overhead** | **4ms** | **6ms** | elapsed - provider |

官方 5 个线程的首样本为 `1,545 / 1,567 / 824 / 610 / 588ms`；前两个约 1.5s 是隧道/TLS 冷启动，其余热样本约 580–840ms provider 时间。`grpThreads=5` 覆盖 375/379 样本，没有队列增长；Java 内部端点使用虚拟线程，HTTP stream 执行器 16 槽。**1,374ms 是 provider/隧道时间，不是服务 overhead；服务 overhead 仍是 6ms。** 证据：[ai-stub-decomposition.txt](../../acceptance-evidence/2026-11-12/perf-baseline/ai-stub-decomposition.txt)、[ai-stub-decomposition.csv](../../acceptance-evidence/2026-11-12/perf-baseline/ai-stub-decomposition.csv)。

---

## 5. 瓶颈与资源

本次采样点（soak 期间）：

| 资源 | 观察 | 解释 |
| --- | --- | --- |
| PostgreSQL | DEBUG soak CPU **97.24%**；INFO short soak **114.50%**，mem 115.5–150.4MiB / 1.859GiB | 本地最明显瓶颈；两种日志级别都接近/超过单核饱和 |
| Redis | CPU 1.75%，mem 6.2MiB | 不是瓶颈；启动时有一次 `Redis command timed out`，负载期间未重复 |
| MinIO | CPU 0.07% | 本场景未覆盖文档上传/下载，不能据此判断对象存储容量 |
| App JVM | G1 used 88.6MB / total 266.2MB；Metaspace 113MB | 无 OOM；堆不是瓶颈 |
| Tomcat | soak 时 41 个 `http-nio-8090-exec` 线程 | spike 后线程池保留线程；远低于 200，无拒绝 |
| Hikari | 配置 max 10；soak 时 DB 会话 11（10 app + psql） | 无 `Connection is not available`、无池耗尽 |
| 日志 | DEBUG **5.4GB**；INFO short soak **12,030 bytes** | DEBUG 是明显 I/O 成本；INFO 下日志量下降约 5 个数量级 |

证据：[post-run-checks.txt](../../acceptance-evidence/2026-11-12/perf-baseline/post-run-checks.txt)、[environment.txt](../../acceptance-evidence/2026-11-12/perf-baseline/environment.txt)。

官方运行窗口（21:44:30–21:59:37）内后端 **0 条 WARN/ERROR**；见 [backend-warnings-official.txt](../../acceptance-evidence/2026-11-12/perf-baseline/backend-warnings-official.txt)。调试阶段曾出现的手工 400 请求不在官方窗口内，已排除。

### INFO short soak 诊断

为剥离 DEBUG SQL 日志 I/O，另跑 183s（6 用户、10s ramp、混合读写）INFO soak：**141,443 样本、0 errors、P50 4ms、P95 39ms、P99 48ms**。同点 PostgreSQL CPU 为 **114.50%**，仍是最明显的资源；INFO backend log 只有 **12,030 bytes**，App heap 140.5MB/301.1MB，Tomcat 10 个 worker 线程。Hikari 仍为 max 10，未出现池耗尽。

这说明 DEBUG 日志确实压低了吞吐/抬高了 P95（原 600s soak 590/s、P95 55ms；INFO 短跑约 773/s、P95 39ms），但 **DB 仍是瓶颈**。INFO 短跑只有 183s，不能直接替代 600s 基线；也没有为此扩大 Hikari。证据：[info-soak-diagnostic.txt](../../acceptance-evidence/2026-11-12/perf-baseline/info-soak-diagnostic.txt)。

### 环境不等于 4C/8G

实际环境是：Intel i9-14900HX，**32 logical CPUs / 16GB host**；Docker VM 是 **8 vCPU / 1.86GiB**。同一 Docker VM 还跑着 AI-CostOps 的 MySQL/Redis/MinIO。
因此本轮**不是 4C/8G 基线**；更不能当生产容量。若要用 4C/8G 结论，必须在该规格上重跑，并记录 DB 内存/CPU 上限。

---

## 6. 基线 / 容量上限 / NOT_VERIFIED

| 类别 | 结论 |
| --- | --- |
| 本地基线 | read 10 用户/60s：P95 63–68ms；mixed 8 用户/60s：P95 68ms；soak 6 用户/600s：P95 55ms；spike 40 用户：P95 148ms；全部 0 errors/0 5xx |
| 项目详情 follow-up | 3×10,000 样本，0 errors；P95 6/5/7ms；未回到 4ms；原 FAIL 保留，follow-up 判 FAIL/不干净 |
| 容量上限 | **未建立**。没有 step-load 到失败，没有打到 Tomcat/Hikari 上限；40 用户 spike 仍健康，不能据此推断更高并发 |
| AI 容量 | **未建立**。只测内部网关到 stub 的 overhead（P95 6ms），provider 时间是隧道成本；真实模型并发、限流、超时均未验证 |
| SSE 容量 | **未建立**。只验证 10 次无文档会话的协议烟测；有文档/有模型 token 流的 SSE 未压 |
| 4C/8G | **NOT_VERIFIED**；本轮实际规格不同 |
| staging/production | **NOT_VERIFIED**；本机单实例、无 TLS ingress、无多实例/网关/生产 DB 规模 |
| 真实模型 3s | **NOT_VERIFIED / W3 FAIL 不在本轮范围**；不得把 W3 的 provider 诊断混入 JMeter 容量结论 |
| Redis/MQ 开启路径 | **NOT_VERIFIED**；本次 `MQ_ENABLED` 未设置（默认 false），Redis 只作为现有缓存/同步依赖 |
| INFO 600s soak | **NOT_VERIFIED**；只做了 183s 短诊断，方向性结果不等于 600s 基线 |

---

## 7. 产物与 CI

测试资产：

- [JMeter README](../../../tests/performance/jmeter/README.md)
- 8 个计划：`smoke.jmx`、`read-baseline.jmx`、`mixed-baseline.jmx`、`spike.jmx`、`soak.jmx`、`ai-stub.jmx`、`sse-smoke.jmx`、`project-detail-followup.jmx`
- `run.ps1` / `run.sh`、`thresholds.csv`、`testdata.example.csv`
- `stub-server.mjs`、`tools/generate-jmx.mjs`、`tools/summarize.mjs`、`tools/seed-ai-stub.ps1`、`tools/p95-followup.mjs`、`tools/measure-stub.mjs`

证据：本目录（[perf-baseline](../../acceptance-evidence/2026-11-12/perf-baseline/)）。

原始 JTL 体积：read 两份约 23MB、mixed 8.4MB、spike 6.4MB、soak 76.6MB、ai 61KB、sse 2.8KB；**未提交**，只在本地 `target/perf/official` 保留，按仓库规则不进入 Git。

CI：

- **未新增 workflow，默认 CI 未变化**；全量压测不进入默认 CI。
- 本报告在 push 前定稿；push 后等待 GitHub Actions，结果另记。没有把 JMeter 结果写成 CI gate。
- 如后续要手动 perf-smoke，可只跑 `smoke.jmx`，但仍应由人工触发并明确目标环境。

---

## 8. 复测建议

1. 项目详情 follow-up 已做：5–7ms，未回到 4ms；SQL 0.077ms、无序列化异常。按 1ms 门禁敏感性和 run variance 处理，但原 FAIL 保留；若需要干净结论，用 INFO 重跑 2 组 B1/B2，而不是只跑一轮。
2. INFO 短 soak 已做：P95 39ms，但 PostgreSQL 仍 114.5%；如需正式结论，补 600s INFO soak，再决定是否仍是 DB 瓶颈。
3. 在专用 4C/8G（或目标 staging）上重跑同一组场景；不要拿本机结果外推。
4. 若继续使用 per-label 20% 门禁，建议固定测试数据规模、预热 JIT、至少 2 组 B1/B2；本轮不修改已冻结阈值。
5. 容量测试需另设 step-load（例如 10→20→40→80 用户），记录首个 5xx/超时/池等待点；不要用本轮 40 用户 spike 当上限。

---

## 9. 合规与清理

- 未改产品功能、默认开关、迁移或前端；只新增 `tests/performance/jmeter/**` 与本报告/证据。
- 原测试项目：`[perf] jmeter 20261112-214428`，80 个任务、2 个里程碑、1 个知识库会话；API 删除成功，DB 计数 0。
- Follow-up 测试项目：`[perf] jmeter 20261112-222311`（`14796ca3-...`），80 个任务、2 个里程碑；INFO soak 复用，已清理，DB 计数 0。
- AI stub provider / `KNOWLEDGE_CHAT` 临时绑定已清理，DB 计数 0。
- 没有提交真实 token、登录态、密码或 JTL；登录 token 只在进程内使用。
- cloudflared、本地 stub、本次启动的 worktree 后端和 JMeter 进程在收尾时停止；主工作树另有一个非本轮的长期 Java 进程（PID 44312，未监听 8090/8091），未操作。最终只保留 `dev/main` 与主 worktree。
- Lane F（本地 reranker spike）与 JMeter 结论完全分开；模型、候选数与延迟只写入 `docs/archive/research/local-reranker-spike-2026-11-12.md`，不进入本报告的容量结论。
