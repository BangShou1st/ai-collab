# AI Collab 删除优先代码审计（2026-11-16）

> 基线：`main` c8604a95（`dev` a8c1aab7 是其父提交）。范围为远程仓库静态检查，未获得生产调用遥测，不将「未发现调用」等同于「无人调用」。本次不改数据库迁移、外部 API 协议、生产配置或数据内容。

## 决策优先级

删除 > 简化 > 优化 > 自动化。仅当可证明没有当前产品依赖、运行时兼容义务或审计保留义务时才删除。不能证明安全的候选项留在待验证清单，不用猜测代替证据。

## 本次已执行：确定无用的代码删除

1. `ai-collab-frontend/src/modules/planning/PlanningView.vue`：删除未使用的 `pendingConfirmation`、`project`、`openVersion`、`refresh` 解构绑定。
2. `ai-collab-frontend/src/modules/agent/AgentView.vue`：删除未使用的 `summaries`、`approvals`、`time` 解构绑定及 `SemanticDiff` 导入。没有删除组件文件；组件可能被其他页面使用。
3. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/service/PublicRegistrationService.java`：删除从未读取的、可选注入的旧 `SystemConfigService` 字段及其过期说明。公开注册开关仍只由 `PublicRegistrationProperties` 决定，不触碰注册、邮件、Token 或事务路径。

以上属于静态可确认的死引用清理，不涉及对外接口与数据语义。修改前对三个文件全文检查，待删标识符在对应使用文件中仅出现于声明/导入处；修改后检查标识符不再存在。

## 高价值但暂不执行的删除

| 候选 | 事实与薄弱假设 | 决策/删除条件 |
| --- | --- | --- |
| 旧项目 Embedding 接口 | `ProjectEmbeddingController` 文档说「只读兼容、运行时不再路由」，源码却仍声明 `@RestController`、`GET`、`POST /test` 与 `PUT`。依靠注释认定旧接口已关闭是错误假设。 | **暂不删除**。先核实运行环境映射与请求日志、对外客户端、历史项目配置迁移和授权测试；若退役，应独立版本化处理。 |
| 旧任务规划 PATCH | `TaskPlanController` 中 `PATCH /{planId}` 仍公开，`UpdateTaskPlanRequest` 已标为 Deprecated。前端当前没有明显调用，并不能证明外部客户端无调用。 | **暂不删除**。需要 API 流量与消费者盘点、废弃通知和契约测试。 |
| Agent Legacy JSON 执行链 | `LegacyReadOnlyAgentExecutor` 面向 CHAT-only 模型，`docs/archive/report/j1-cleanup-20261111.md` 明确因缺生产用量证据且历史 `source_mode IS NULL` 安全兜底未删除。 | **保留**。先证明没有 CHAT-only 调用，并处理历史运行恢复语义。 |
| Python/Java 双认知链及开关 | 目前 query plan、rerank、decide、planning、memory 等按阶段引入 Worker；部分默认仍为 Java 或 default-off，回退兼具可用性与安全含义。 | **暂不合并/删除**。待生产 canary 成功、故障回退观测及协议测试覆盖后逐路径收敛。 |
| 多套 compose 与 CI 分片 | 开发、staging、生产环境约束不同；CI 3 分片用并行换取单次等待时间。 | **不凭文件数删除**。先做部署目标与 CI 时长/成本测量。 |

## 仓库过程产物与证据债

在基线树上统计：5,408 个追踪文件，`docs/` 为 3,162 个，其中 `docs/acceptance-evidence/` 为 2,775 个，约 97,615,294 字节；后者有 883 份 `.log/.txt/.out` 风格文件，约 56,661,807 字节。全仓库追踪内容大小的简单合计约 125,386,448 字节（**不是 Git pack 压缩后大小**）。

有 94 组按 blob SHA 完全相同的文件。重复路径会增加工作树噪音，但 Git blob 已按内容寻址去重，**不能把重复文件长度直接当作 Git 仓库可节省字节数**。许多 JSON/JSONL 属于冻结评测、实验追溯或审计回执，不能批量删除。

建议后续先建立按用途分类的保留清单：长期冻结基准/必要证据保留；可再生成且无引用的构建原始日志改用带有效期的 CI artifact 或受控归档。逐项验证报告链接、复现脚本、审计保存期限和敏感信息脱敏情况后，再单独批次清除。

## 验证与上线边界

- 本次执行的是读取完整文件、对修改点进行精确匹配以及删除后标识符缺席的**静态检查**。
- 此环境无法连接 GitHub 进行本地 clone，也没有完成 Maven/Vitest 构建；不宣称本地单测或集成测试已通过。
- 合入 `dev` 前必须以 PR CI 通过为最低门槛，并复核前端 lint/typecheck/test/build、后端 Maven verify、部署契约与审批/注册等关键流程。
- 不直接推送 `main`、不合并 PR、不修改历史 Flyway SQL、不删除生产兼容 API。
