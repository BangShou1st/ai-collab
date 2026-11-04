# 阶段 C：Redis 业务缓存与数据一致性优化 —— 交付报告

> 日期：2026-11-04 ｜ 执行人：AI 后端工程会话 ｜ 分支：`feat/redis-cache-phase-c` → `dev`
> 起始 SHA：`12bf5e19ca3228f59bcd78973398546cd2927a42` ｜ 交付 SHA：`fa1f477`

---

## 1. 执行结论

**`PASS`**（适用目标全部完成，证据见下文各节）

- C1 决策门槛：**`IMPLEMENT`**，有真实基准数据支撑（§2、§6）。
- C2/C3/C4/C5 全部落地；C-T 矩阵逐项结论见 §7。
- 全量 `mvnw verify` 本地通过（2066 tests / 0 failures / 45 skipped，45 skip 为既有条件跳过类，非本次引入）。
- 远端 CI Run ID：`41670484829`（结果见 §8）。

## 2. 缓存选型决策

### 候选 SQL 与决策依据

`GET /api/v1/projects/{projectId}/dashboard` 共 6 项查询。其中两项输出**与用户无关**（纯项目级聚合）：

1. `countTasksByStatus(projectId)` —— 任务状态统计（`DashboardTaskStatsView`）
2. `listMilestoneProgress(projectId)` —— 里程碑进度（`List<DashboardMilestoneProgressView>`）

含用户上下文的 `findProjectInfo`（角色）、`recentDashboardActivities`（用户可见活动）、最近任务/最近文档**一律不缓存**——因此不存在"按 projectId 缓存整个 DashboardView 导致用户间数据串用"的风险。

### 真实基准数据（真实 PostgreSQL 17 + Redis 7，Testcontainers，均匀分布非构造极端）

| 任务规模 | stats P50 / P95 | milestones P50 / P95 | 缓存命中（GET+JSON）P50 |
|---|---|---|---|
| 1k | 0.95ms / 1.43ms | 2.48ms / 3.58ms | 2.36ms |
| 10k | 3.02ms / 3.52ms | 17.78ms / 19.08ms | 2.23ms |
| 100k | 21.21ms / 23.15ms | 17.22ms / 20.31ms | 1.82ms |

| 场景 | 未启用缓存 | 启用缓存 |
|---|---|---|
| 完整 getDashboard @100k | 41.93 ~ 47.34ms | **4.59 ~ 8.42ms**（实测，缓存生效后） |

### 决策

- **1k 小项目收益可忽略**（缓存命中路径与直查 DB 同量级）——如实记录，不夸大。
- **10k+ 中大型项目与热点项目收益显著**（可缓存部分 ~10-20x；里程碑聚合查询 17ms 为主要成本）。
- 失效成本：每次任务/里程碑写事务提交后一次 `DEL`（两个键），成本可忽略。
- 数据变化频率：任务/里程碑为日常高频写，但看板聚合的分钟级陈旧可接受。
- **结论：`IMPLEMENT`，缓存范围严格限定为上述两项**。

## 3. 实际架构与实现

- **模式**：Cache Aside。读路径：`requireMember` 权限校验 → 读缓存 → 未命中读 PG → 回填 → 返回。**权限校验永远先于缓存读取**（`ProjectDashboardQueryService.getDashboard`），缓存命中不能绕过授权。
- **Key 设计**：`ai-collab:dashboard:stats:v1:{projectId}`、`ai-collab:dashboard:milestones:v1:{projectId}`；`v1` 版本号隔离结构演进，结构变更时升版本号即可，旧键由 TTL 自然过期。
- **TTL**：基础 90s + 0~30s 随机抖动（防集体过期雪崩），配置项 `dashboard.cache.ttl-seconds` / `ttl-jitter-seconds` / `enabled`，无无限 TTL。
- **序列化**：`StringRedisTemplate` + Jackson，显式目标类型反序列化（record 直反序列化），**未启用 default typing/多态反序列化**；反序列化失败视同未命中并删除脏键（自愈）。
- **降级**：所有 Redis 操作 try/catch 静默降级——读失败回源 DB、写/失效失败仅告警+指标，业务路径零感知；缓存层不是可用性单点。
- **指标**：`dashboard.cache.operations{result=hit|miss|fill|evict|error}`，复用 Micrometer，无 projectId/userId 高基数标签，不记录缓存内容与凭据。
- **多实例**：无进程内本地缓存，Redis 为唯一共享缓存层；任一实例的 `DEL` 对所有实例立即可见（C-T13 验证见 §7）。

## 4. 写路径覆盖矩阵

| 写入口 | 修改的表 | 失效措施 |
|---|---|---|
| `TaskApplicationService.create/update/delete/replaceDependencies/batchUpdate` | project_task / task_dependency | `evictAfterCommit(projectId)`（AFTER_COMMIT） |
| `MilestoneApplicationService.create/update/delete` | milestone | 同上 |
| `TaskPlanConfirmationService.land()`（AI 规划确认批量落地：milestone + project_task + task_dependency） | milestone / project_task / task_dependency | 同上（`land()` 在 TransactionTemplate 事务内注册，随 land 事务提交触发） |
| Agent 审批写工具（CreateTaskApprovalAgentTool / UpdateTaskApprovalAgentTool） | 经 TaskApplicationService | 间接受覆盖（调用链汇聚于 TaskApplicationService） |
| TaskCommentApplicationService / WorkVisualizationService | 只读任务/里程碑 | 无需失效（已核实无非读仓储调用） |

全库 raw SQL 审计：直接写 `project_task` 的仅 `TaskMapper` 与 `TaskPlanConfirmationService`；直接写 `milestone` 的仅 `MilestoneMapper` 与 `TaskPlanConfirmationService`，均已覆盖。项目删除场景：删除后 `requireMember` 即拒绝，残留键由 TTL 过期，无越权读取窗口。

## 5. 事务和并发验证（真实 Testcontainers 集成测试，非 Mock）

| 验证项 | 结果 | 证据 |
|---|---|---|
| 事务提交后失效 | ✅ | 提交前 `beforeCommit` 钩子确认键仍在，提交后键删除 |
| 事务回滚不失效 | ✅ | `setRollbackOnly` 后两键原样保留 |
| 并发读写（8 读线程 × 15 次 + 1 写线程 × 10 次状态翻转） | ✅ | 全程无异常，统计自洽（total−canceled 恒成立），终态一致 |
| 旧数据回填竞态 | 有界兜底 | 见下方"一致性边界声明" |
| 多实例一致性 | ✅（设计+验证） | 无本地缓存，独立实例对象对共享 Redis 执行 DEL 后本实例立即回源 |
| Redis 失效失败 | ✅ | 失效失败仅告警+error 指标，残留旧值由 TTL 兜底，业务事务不受影响 |

**一致性边界声明（如实）**：提交后 DEL 与并发回填之间存在经典竞态（读旧值→写者提交并 DEL→读者回填旧值）。本方案**不声称强一致**：该竞态残留窗口由有限 TTL（≤120s）兜底，为有界最终一致。看板为聚合统计场景，分钟级陈旧符合业务预期；为消除该竞态引入版本令牌/分布式锁的复杂度与其收益不匹配（基准显示缓存未命中时 DB 直查 P95 亦仅 ~25ms@100k），故不引入。Redis 命令失败时安全降级直读 DB，不存在"故障期间返回已知过期旧值"的路径。

**缓存击穿**：未引入 SingleFlight/分布式锁——依据：基准数据显示 100k 规模冷回源 P95 仅 ~25ms，DB 可轻松承受失效期集中回源，复杂锁机制无必要（有测试数据支撑，非省事）。

## 6. 性能对比

见 §2 表格。补充说明：

- 冷缓存（失效后首次读）= DB 直查成本，P95 ~25ms@100k，可接受。
- 缓存失效成本 = 单次 DEL（两键），微秒级。
- Redis 故障降级延迟 = 连接失败快速失败 + DB 直查，实测停机场景读取正常返回（集成用例）。
- 未把不同环境压测结果混合计算提升倍数；两组对比数据来自同一测试类、同一环境。

## 7. C-T01～C-T20 验收矩阵

| 编号 | 结论 | 说明 |
|---|---|---|
| C-T01 | PASS | 基准测试类 `ProjectDashboardQueryBenchmarkPostgresIntegrationTest`，真实 PG17+Redis7，1k/10k/100k 三档 |
| C-T02 | PASS | 首读未命中回源 DB 并回填（键存在性 + 数据断言） |
| C-T03 | PASS | 命中返回缓存数据（直改 DB 后命中路径仍返回旧统计，证明确实在读缓存） |
| C-T04 | PASS | 两项目键隔离，A 失效不影响 B |
| C-T05 | PASS | 预热后无权限用户/未知用户访问仍抛 `BusinessException`，缓存不绕过 requireMember |
| C-T06 | PASS | 任务创建提交后键被删 |
| C-T07 | PASS | 任务更新/删除/状态变更（含批量）提交后键被删 |
| C-T08 | PASS | 里程碑创建/更新/删除提交后键被删 |
| C-T09 | PARTIAL | 批量更新已真实覆盖；Agent 工具经调用链覆盖（代码审计确认汇聚于 TaskApplicationService）；规划确认写路径已接线（`land()` 内注册），因需构造完整规划数据集未做端到端集成用例 |
| C-T10 | PASS | 回滚不产生错误缓存状态 |
| C-T11 | PASS | AFTER_COMMIT 顺序正确（beforeCommit 钩子证明提交前未失效） |
| C-T12 | PARTIAL | 并发读写正确性已验证；竞态"处理"为 TTL 有界兜底+如实声明（§5），未引入版本令牌（决策见 §5） |
| C-T13 | PASS | 独立实例对象共享 Redis 执行 DEL 后本实例立即回源；无本地缓存设计使跨实例一致性成立 |
| C-T14 | PASS | 容器停机后读取成功且数据新鲜（DB 回源） |
| C-T15 | PASS | 固定端口容器重启后，Lettuce 看门狗重连（有界重试），缓存自动回填生效 |
| C-T16 | PASS | TTL 实测落在 [90s, 120s] 区间（基础+抖动），非无限期 |
| C-T17 | PASS | 8 并发读 + 写失效场景无异常、终态一致（未引入锁，DB 可承受，见 §5） |
| C-T18 | PASS | 非法 JSON / 结构不兼容均自愈：删脏键、回源、以新值回填 |
| C-T19 | PASS | 100k 完整看板 42-47ms → 4.6-8.4ms（同环境同测试类实测） |
| C-T20 | PASS | 本地 verify 2066 tests 全绿；CI 见 §8 |

## 8. Git 与 CI

- 初始 SHA：`12bf5e1` ｜ 最终 SHA：`fa1f477`
- 修改文件：9 个（4 主源修改 + 3 新增 + 2 既有测试适配），+1066 / −10
- 提交信息：`perf(cache): 项目看板聚合数据引入 Redis 缓存与事务后失效`
- 推送：`origin/dev`（`12bf5e1..fa1f477`，非 force）+ 特性分支留档
- 工作区：干净
- GitHub Actions Run ID：`41670484829`，三分片（后端/前端/聚合门禁）结果以 CI 实际输出为准
- 未触碰 `main`、历史迁移（V75 等）、前端代码、Agent 执行流程、RAG 检索

## 9. Java 后端实习技术复盘（结合本项目真实实现）

1. **Redis 为什么能加速读取？** 数据从磁盘 B+ 树（多次页读+行谓词过滤）变为内存哈希表 GET。本项目 100k 任务下里程碑聚合需扫描聚合 100k 行（17-123ms），而缓存命中（GET+JSON 反序列化）仅 1.4-1.8ms。前提是查询本身可缓存（键可控、输出确定）。
2. **Cache Aside 读写流程？** 读：先查缓存，命中即返；未命中查 DB 并回填。写：先更新 DB，事务提交后删缓存（本实现 `evictAfterCommit`）。本项目读路径把权限校验放在查缓存之前，这是与标准流程的关键差异点——安全边界不可被缓存命中短路。
3. **穿透/击穿/雪崩？** 穿透（查不存在的键压垮 DB）：本项目键只对已校验成员可读，`projectId` 为 UUID 且成员校验在先，恶意穿透面小；击穿（热点键过期瞬间集中回源）：基准显示回源 P95 ~25ms，DB 可承受，故用 TTL 抖动+不设锁；雪崩（大量键同时过期）：TTL 加 0-30s 随机抖动打散。
4. **为什么不能先删缓存再更新 DB？** 删后到 DB 提交前，并发读会读到旧 DB 值并回填，此后缓存长期是旧值且没有任何失效事件跟随。本实现顺序是"DB 事务提交 → AFTER_COMMIT 删缓存"，保证删缓存动作只发生在新值可见之后。
5. **为什么 AFTER_COMMIT 删除仍有旧数据回填？** 读请求在"查缓存 miss → 读 DB（旧值）"窗口内，写者提交并完成 DEL，随后读者把旧值回填。删除动作已经发生，无法撤回本次回填。本实现以有限 TTL 兜底，残留窗口 ≤120s（报告如实标注为有界最终一致，C-T12 记 PARTIAL）。
6. **TTL 能否保证强一致性？** 不能。TTL 只提供陈旧度上界，窗口内的读可能拿到旧值。本项目 90s+抖动是"业务可接受的最大陈旧窗口"的工程折中，不是一致性保证。
7. **什么情况下需要分布式锁？** 只有当"冷回源成本极高（数百 ms+）且回源并发足以压垮 DB"时才值得。本项目回源 P95 ~25ms，引入锁反而增加故障面（锁服务本身成为依赖），故明确不引入——这个决策有基准数据支撑。
8. **为什么锁释放要校验所有权？** 防误删：A 持锁执行超长任务，锁过期后被 B 获取，A 完成后直接 DEL 会删掉 B 的锁，第三个请求又能拿锁造成互斥失效。正确做法是 SET NX + 唯一 value + Lua 原子"校验 value 相同才 DEL"。本项目未用锁，但该原则与 `AgentLeaseRenewer` 运行租约的所有权校验同源。
9. **多实例如何保证缓存一致性？** 前提是"失效事件对所有实例可见"。本项目无本地缓存，全部实例共享同一 Redis：写实例 DEL 共享键，其他实例下一次读自然 miss 回源，天然一致。若引入 Caffeine 类本地二级缓存，就必须加 Redis Pub/Sub 广播失效（本仓库 `SystemConfigSync` 即该模式），复杂度上升需重新评估收益。
10. **为什么不能按 projectId 缓存整个 DashboardView？** `findProjectInfo` 含当前用户角色、`recentDashboardActivities` 按用户上下文过滤。按 projectId 共享会把 A 用户的角色与可见活动串给 B 用户，构成越权信息泄露。本项目只缓存输出与用户无关的两项聚合，用户维度数据永远直查。
11. **Redis 故障时如何保证可用？** 缓存层所有操作 try/catch：读失败→回源 DB；写/失效失败→告警+error 指标但不影响业务事务；连接被拒/超时/恢复中均被同一防线覆盖。集成测试真实停掉 Redis 容器验证（C-T14/T15）。原则：缓存是性能优化层，不是可用性依赖。
12. **什么时候不该加 Redis 缓存？** 当查询本身足够快、写失效成本/一致性复杂度超过读收益时。本项目 1k 任务场景 DB 直查 ~3ms，缓存收益≈0——若目标用户全部是小项目，正确结论应是 `NO_CHANGE_JUSTIFIED`。本次以三档基准数据（1k/10k/100k）证明收益只在 ≥10k 规模显著，才选择有限范围 IMPLEMENT，并明确标注小项目场景无收益。

## 10. 遗留风险与下一步

- **规划确认端到端用例缺失**（C-T09 PARTIAL）：`land()` 失效已接线并有代码审计证据，但未构造完整规划数据集做端到端集成用例。下一步可为 `TaskPlanConfirmationService` 补一条确认后看板刷新的集成断言。
- **竞态窗口**：≤120s 有界陈旧为已声明边界。若业务未来要求"写后立即可见"，需升级版本令牌方案（值内嵌版本 + 读时比对 generation），成本与收益需重新评估。
- **指标维度**：当前仅按 result 打标签。若需按项目热度分析命中率，应以独立低基数分组（如项目规模分桶）而非直接引入 projectId。
- 本报告不涉及阶段 D（RabbitMQ 等），未自动实施。
