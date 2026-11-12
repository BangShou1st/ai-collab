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

---

## 5. 瓶颈与资源

本次采样点（soak 期间）：

| 资源 | 观察 | 解释 |
| --- | --- | --- |
| PostgreSQL | CPU **97.24%**，mem 150.4MiB / 1.859GiB | 本地最明显瓶颈；读写混合和 40 用户 spike 都把 DB 推到接近单核饱和 |
| Redis | CPU 1.75%，mem 6.2MiB | 不是瓶颈；启动时有一次 `Redis command timed out`，负载期间未重复 |
| MinIO | CPU 0.07% | 本场景未覆盖文档上传/下载，不能据此判断对象存储容量 |
| App JVM | G1 used 88.6MB / total 266.2MB；Metaspace 113MB | 无 OOM；堆不是瓶颈 |
| Tomcat | soak 时 41 个 `http-nio-8090-exec` 线程 | spike 后线程池保留线程；远低于 200，无拒绝 |
| Hikari | 配置 max 10；soak 时 DB 会话 11（10 app + psql） | 无 `Connection is not available`、无池耗尽 |
| 日志 | 本地 DEBUG 产生 **5.4GB** backend log | 这是本次基线的一部分，也是明显 I/O 成本；生产 INFO 下数字会不同 |

证据：[post-run-checks.txt](../../acceptance-evidence/2026-11-12/perf-baseline/post-run-checks.txt)、[environment.txt](../../acceptance-evidence/2026-11-12/perf-baseline/environment.txt)。

官方运行窗口（21:44:30–21:59:37）内后端 **0 条 WARN/ERROR**；见 [backend-warnings-official.txt](../../acceptance-evidence/2026-11-12/perf-baseline/backend-warnings-official.txt)。调试阶段曾出现的手工 400 请求不在官方窗口内，已排除。

### 环境不等于 4C/8G

实际环境是：Intel i9-14900HX，**32 logical CPUs / 16GB host**；Docker VM 是 **8 vCPU / 1.86GiB**。同一 Docker VM 还跑着 AI-CostOps 的 MySQL/Redis/MinIO。
因此本轮**不是 4C/8G 基线**；更不能当生产容量。若要用 4C/8G 结论，必须在该规格上重跑，并记录 DB 内存/CPU 上限。

---

## 6. 基线 / 容量上限 / NOT_VERIFIED

| 类别 | 结论 |
| --- | --- |
| 本地基线 | read 10 用户/60s：P95 63–68ms；mixed 8 用户/60s：P95 68ms；soak 6 用户/600s：P95 55ms；spike 40 用户：P95 148ms；全部 0 errors/0 5xx |
| 容量上限 | **未建立**。没有 step-load 到失败，没有打到 Tomcat/Hikari 上限；40 用户 spike 仍健康，不能据此推断更高并发 |
| AI 容量 | **未建立**。只测内部网关到 stub 的 overhead（P95 6ms），provider 时间是隧道成本；真实模型并发、限流、超时均未验证 |
| SSE 容量 | **未建立**。只验证 10 次无文档会话的协议烟测；有文档/有模型 token 流的 SSE 未压 |
| 4C/8G | **NOT_VERIFIED**；本轮实际规格不同 |
| staging/production | **NOT_VERIFIED**；本机单实例、无 TLS ingress、无多实例/网关/生产 DB 规模 |
| 真实模型 3s | **NOT_VERIFIED / W3 FAIL 不在本轮范围**；不得把 W3 的 provider 诊断混入 JMeter 容量结论 |
| Redis/MQ 开启路径 | **NOT_VERIFIED**；本次 `MQ_ENABLED` 未设置（默认 false），Redis 只作为现有缓存/同步依赖 |

---

## 7. 产物与 CI

测试资产：

- [JMeter README](../../../tests/performance/jmeter/README.md)
- 7 个计划：`smoke.jmx`、`read-baseline.jmx`、`mixed-baseline.jmx`、`spike.jmx`、`soak.jmx`、`ai-stub.jmx`、`sse-smoke.jmx`
- `run.ps1` / `run.sh`、`thresholds.csv`、`testdata.example.csv`
- `stub-server.mjs`、`tools/generate-jmx.mjs`、`tools/summarize.mjs`、`tools/seed-ai-stub.ps1`

证据：本目录（[perf-baseline](../../acceptance-evidence/2026-11-12/perf-baseline/)）。

原始 JTL 体积：read 两份约 23MB、mixed 8.4MB、spike 6.4MB、soak 76.6MB、ai 61KB、sse 2.8KB；**未提交**，只在本地 `target/perf/official` 保留，按仓库规则不进入 Git。

CI：

- **未新增 workflow，默认 CI 未变化**；全量压测不进入默认 CI。
- 本轮没有 push，因此没有触发 GitHub Actions；也没有把 JMeter 结果写成 CI gate。
- 如后续要手动 perf-smoke，可只跑 `smoke.jmx`，但仍应由人工触发并明确目标环境。

---

## 8. 复测建议

1. 先解决日志干扰：用 `logging.level.com.shitulelv.aicollab=INFO` 重跑 read B1/B2，确认项目详情 4ms→5ms 是否仍是 25%；当前 DEBUG SQL 日志是 5.4GB，明显影响 I/O。
2. 在专用 4C/8G（或目标 staging）上重跑同一组场景；不要拿本机结果外推。
3. 若仍要用 per-label 20% 门禁，建议先固定测试数据规模、预热 JIT、并至少做 2 组 B1/B2，取复现结果再判定；本轮不修改已冻结阈值。
4. 容量测试需另设 step-load（例如 10→20→40→80 用户），记录首个 5xx/超时/池等待点；不要用本轮 40 用户 spike 当上限。

---

## 9. 合规与清理

- 未改产品功能、默认开关、迁移或前端；只新增 `tests/performance/jmeter/**` 与本报告/证据。
- 测试项目：`[perf] jmeter 20261112-214428`，80 个任务、2 个里程碑、1 个知识库会话；API 删除成功，DB 计数 0。
- AI stub provider / `KNOWLEDGE_CHAT` 临时绑定已清理，DB 计数 0。
- 没有提交真实 token、登录态、密码或 JTL；登录 token 只在进程内使用。
- cloudflared、本地 stub、本次启动的 worktree 后端和 JMeter 进程在收尾时停止；主工作树另有一个非本轮的长期 Java 进程（PID 44312，未监听 8090/8091），未操作。最终只保留 `dev/main` 与主 worktree。
- `Qwen3-Reranker-0.6B` 权重下载是独立泳道 F，不占 GPU；本轮 JMeter 收口后继续，BGE 未下载。
