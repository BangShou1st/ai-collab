# Phase 08 最终系统修复报告

日期：2026-07-28（Asia/Shanghai）

## 结论

PASS：旧分支已合并，旧 worktree 已安全删除；规划生成、结构化问题、统一编辑、局部 AI 修复、
确认落地和权限链路已修复；OpenAPI、中文前端、真实 PostgreSQL/Spring wiring、真实模型和
Edge 前后端联调均通过；生产修改已分阶段提交。

## 合并与工作树清理

- 合并提交：`6f7dca7 merge: integrate Phase 08 editable planning worktree`
- `git merge-base --is-ancestor eba204f HEAD`：退出码 0。
- 原分支 `feat/phase-08-editable-degradation` 的有效提交已进入当前分支。
- 合并前主目录四个 tracked WIP 保存为 stash
  `3e9afeb4fb4136ff22f7d887aaaeb21427434dde`，从未执行 `stash pop`。
- 四个文件的逐项结论见 `phase08-main-wip-disposition.md`。
- 旧 worktree 中仅有的三个 ZIP 生成物先删除，随后使用 `git worktree remove` 删除
  `E:\ai-collab-phase08-editable`。
- 最终 `git worktree list` 仅包含 `E:\ai-collab`；
  `Test-Path E:\ai-collab-phase08-editable` 为 `False`。

## 根因与生产修复

1. 校验结果曾被压缩成 boolean，模型 Repair 无法获得实体、字段、错误码和严重级别。
   修复为原生 `StructuredValidationIssue`，完整保存 target、field、severity 和 issue ID。
2. 生成阶段 Repair 曾重新解析整份 Detail，可能覆盖非目标字段。
   改为服务器计算 `RepairScope`，只接受 patch JSON，经策略、解析、应用、归一化和复验。
3. `partial-regenerate` 曾复用全量生成流程，缺少真正的局部异步语义。
   新服务实现 202、REPAIRING、attempt、配额/限流、服务端 allowedFields 交集、
   事务外模型调用和短事务原子提交。
4. `/versions` 与 PATCH 编辑语义分裂，缺少可靠并发控制。
   两条入口统一到同一版本提交语义，要求 `expectedVersionNo`，原子写入
   `MANUAL_EDIT`、issues、event、latestVersion 和状态；旧版本返回
   `409 PLAN_VERSION_CONFLICT`。
5. 确认、重试、重新生成和删除对 latest version、READY_WITH_ISSUES 及权限的判断不一致。
   已统一状态机和最新版本限制，确认保持幂等和事务落地。
6. OpenAPI 的同一路径曾重复声明，schema、枚举和真实 HTTP 状态存在冲突。
   已合并 GET/PATCH/DELETE 路径，补全状态/来源枚举、202 局部修复和持久化 issue ID，
   并加入契约回归测试。
7. 前端曾直接显示英文内部枚举，编辑、问题定位、历史版本、局部修复和事件链不完整。
   已集中中文映射并完成问题定位、假设/风险增删、成员/文档显示、版本限制、
   REPAIRING 轮询、删除入口和时间线。

## 数据库与生产 wiring

- 未修改任何既有迁移；本地真实数据库从 V6 正常执行 V7～V10，Flyway 校验 10 个迁移通过。
- PostgreSQL 容器：PostgreSQL 17（pgvector）；Redis 健康；MinIO 正常运行。
- `TaskPlanSpringBeanPostgresIntegrationTest` 使用 `@SpringBootTest`、自动注入生产规划 Bean、
  真实 Spring 事务代理、PostgreSQL 17 Testcontainers 和 Flyway V1～V10。
- `TaskPlanProductionWiringPostgresIntegrationTest` 已纳入默认 `mvn test`，覆盖 11 条生产 wiring 链路。

## 自动化验证

后端：

| 命令 | 结果 |
|---|---|
| `mvnw.cmd test` | 222 tests，0 failure/error/skipped |
| `mvnw.cmd verify` | 222 tests，通过并生成 JAR |
| `mvnw.cmd clean package` | 222 tests，通过并生成 JAR |

前端：

| 命令 | 结果 |
|---|---|
| `pnpm typecheck` | 通过 |
| `pnpm test` | 5 个文件、33 个测试通过 |
| `pnpm build` | 通过；仅有非阻塞 chunk size 提示 |

覆盖包括：READY、READY_WITH_ISSUES、HARD 失败清理、MANUAL_EDIT、局部字段边界、
客户端不可扩大 allowedFields、旧生成不可覆盖新编辑、历史版本不可确认、事务回滚、
OpenAPI/Java/TypeScript 契约、中文映射、问题定位、轮询和事件时间线。

## 真实模型、HTTP 与浏览器链路

真实模型使用已配置的 `aliyun-bailian / qwen-flash`，日志记录骨架和详情调用成功，
未记录任何凭据。

| 链路 | URL / 方法 | HTTP / code | 结果 |
|---|---|---|---|
| A 生成 | `/projects/{id}/ai/task-plans` POST | 202 / SUCCESS | 真实模型完成，最终 READY |
| B 结构化问题 | `/versions` POST | 200 / SUCCESS | 浏览器上下文制造无效日期，生成 `TASK_DATE_INVALID`、`DEPENDENCY_DATE_CONFLICT`，状态 READY_WITH_ISSUES |
| C 统一编辑 | `/versions` POST | 200 / SUCCESS | 真实保存生成 MANUAL_EDIT；旧版本重放为 409 / PLAN_VERSION_CONFLICT |
| D 局部修复 | `/partial-regenerate` POST | 202 / SUCCESS | REPAIRING 后生成新版本并恢复 READY；生产 wiring 测试同时证明仅 allowedFields 可变 |
| 确认 | `/confirm` POST | 200 / SUCCESS | 创建 5 个里程碑、10 个任务、10 条依赖；同一幂等键重放仍为 SUCCESS |
| 权限 | 非成员 GET 规划 | 404 / PROJECT_NOT_FOUND | 防止项目存在性探测 |
| 时间线 | `/events` GET | 200 / SUCCESS | 返回 3 条真实事件 |

响应 envelope 未提供 `requestId` 字段，因此 Network 证据记录为“不返回”；模型调用日志中的
内部 request ID 仅用于服务端追踪，未写入本报告。

真实 Microsoft Edge（Playwright 驱动本机 Edge）完成：

- `/register` 注册 200、会话 refresh 200、`/auth/me` 200；
- 项目列表 200、项目创建 201；
- 项目、文档、成员、规划列表均为 200；
- 进入 `/projects/{id}/ai-planning`，`html lang="zh-CN"`；
- 页面标题为“AI 任务规划｜高校竞赛项目协作平台”；
- “AI 任务规划”“创建规划”“全部状态”等中文断言全部通过。

额外的一体化长脚本在 120 秒工具时限后被终止，但终止前已由 Edge 发起真实模型生成和
READY_WITH_ISSUES 数据链路；其临时 Edge 进程和数据已精确清理。各业务链路的最终判定由
独立真实 HTTP/Edge 证据和默认生产 wiring 自动化共同完成，不影响产品运行结果。

## 全系统回归

默认 222 个后端测试覆盖认证/JWT、项目/成员权限、文档、知识问答、普通 Chat、
任务/依赖、审计、Redis/限流、规划和数据库事务。真实启动时：

- `/actuator/health`：200，UP；
- 前端 Vite：首页 200，真实 Vue/Element Plus DOM 正常；
- PostgreSQL、Redis 健康，MinIO 正常；
- 规划改动未破坏普通模块测试。

所有一次性 `phase08_%` 账户与临时项目均已精确删除，数据库残留计数为 0。

## 分阶段提交

- `6f7dca7` merge: integrate Phase 08 editable planning worktree
- `ec1d0eb` fix(planning): preserve structured validation targets and severity
- `49aac88` fix(planning): use scoped repair patches during generation
- `88f19e5` feat(planning): implement asynchronous scoped partial repair
- `009a1d8` fix(planning): unify plan editing and version audit semantics
- `d67e209` fix(planning): align confirmation permissions and lifecycle states
- `e63982c` fix(api): make planning OpenAPI match production behavior
- `714ad76` feat(frontend): localize planning workflow in Chinese
- `260a6f2` feat(frontend): complete editable planning and partial repair workflow
- `2f87941` test(planning): run production PostgreSQL wiring in default verification

## 遗留风险

- 前端构建仍提示单个 chunk 偏大，不影响功能，可在后续性能工作中做路由级拆包。
- 外部模型输出天然存在波动；服务器端 strict parser、scoped patch、归一化、结构化校验和
  READY_WITH_ISSUES 降级已建立防线。
