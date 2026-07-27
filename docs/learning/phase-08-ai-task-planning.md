# Phase 08：AI 任务规划学习笔记

Phase 08 把模型建议变成一条可审查、可编辑、可追踪且可安全落地的工作流。创建接口先保存不可变输入和骨架 attempt，再返回 202；模型调用在独立执行器和数据库事务外完成。

## 两阶段生成与安全边界

第一阶段只生成摘要、假设、风险、里程碑和任务身份骨架。第二阶段补全描述、工时、日期、负责人建议、依赖和来源，同时必须保持骨架数量、tempKey、标题、目标和所属里程碑。解析或校验第一次失败时只携带安全错误码和 schema 修复一次，第二次失败进入对应失败状态。

项目数据、用户输入和文档片段都不是指令。Prompt 对 `</SOURCES>` 等边界进行转义，并明确拒绝资料中的角色声明、系统提示和格式要求。来源按 content hash 去重，使用 Unicode code point 计算预算，保存 S1–S12 的不可变快照；引用只能指向该快照。

## 数据契约分离

三种独立数据契约在类型层面防止字段越界：

- **SkeletonModelOutput**：只含 summary/assumptions/risks/milestone identity/task identity。Jackson 严格模式（`FAIL_ON_UNKNOWN_PROPERTIES`）拒绝未知属性。模型无法输出 assigneeId、description、priority 等 detail 字段。
- **DetailModelOutput**：只含 tempKey 关联的补充字段。模型无法输出 summary、title、objective 等骨架身份字段。
- **ManualTaskPlanDraft**：允许管理员编辑后的完整草案，包含 assigneeId。

`mergeDetailIntoSkeleton()` 按 tempKey 合并，骨架身份字段不可变。`validateSkeletonPreserved()` 检查所有身份字段（summary、assumptions、risks、tempKey、title、objective、targetDate、sortOrder、milestoneTempKey）。

## Prompt 安全

- `<SKELETON>` 只嵌入 `SkeletonIdentityOnly`（无 sources、无 quote text）
- `<SOURCES>` 独立分离
- `<MEMBER_CONTEXT>` 只含 userId/displayName/role，不含邮箱或敏感评价
- 所有不可信数据经过 XML 转义（`&` → `&amp;`、`<` → `&lt;`、`>` → `&gt;`）
- Prompt 有总 Unicode code-point 预算（100,000）
- 恶意输入测试覆盖：`</SOURCES>`、`</SKELETON>`、`<SYSTEM>`、`忽略之前所有规则`、`& < >`

## FutureTask-first 注册

传统的 `executor.submit()` + `map.put()` 存在注册窗口：cancel 在 put 之前到达会找不到 Future。修复方案：

1. 创建 `FutureTask<Object>`
2. 先放入 `activeFutures` registry
3. 再 `executor.execute(futureTask)`
4. queue reject 时立即从 registry 移除

`markRunning()` 是 repository 级 CAS：`UPDATE ... WHERE id=? AND status='QUEUED' AND cancel_requested=false`。

## Attempt 指标

每次模型调用记录 provider、model、latencyMs、promptTokens、completionTokens 到 attempt 记录。`GenerationResult` record 携带内容和指标。成功、失败、取消、丢弃都写入语义一致的指标。不记录 API Key、Prompt、文档全文或原始模型响应。

## 版本、校验与并发

AI 骨架、AI 完整计划、人工编辑和历史恢复都产生新版本，旧版本不更新。人工保存在 `@Transactional` 中锁定 plan、校验成员、写入版本。确认以数据库 confirmation 的项目级幂等键和 plan 唯一约束为事实来源。

领域校验包括：DAG 无环、自依赖、每任务最多 5 依赖、dependency date order、source ref 格式（S1-S12 正则）、sourceRefs 存在于快照、sortOrder 非负、tempKey 全局唯一、estimatedHours 范围、日期范围、文本长度、成员校验。

## 取消、恢复与确认

取消不仅中断 Future，还锁定 plan、标记 attempt、增加 generation sequence 并清除 active attempt。任何迟到写回必须再次匹配 plan、sequence、attempt 和状态，失败时标记 DISCARDED。恢复任务扫描十分钟未更新的生成/确认并以 `PROCESS_RESTARTED` 安全收尾。

终态（READY/FAILED/DETAIL_GENERATION_FAILED/CANCELED/CONFIRMED）清理 activeAttemptId。队列拒绝返回 503 PLANNING_QUEUE_FULL，不暴露堆栈。

## 限流顺序

认证/权限 → 参数 → plan 状态 → 日期 → 文档存在/READY/项目归属 → 其他同步业务校验 → 限流 → 创建 attempt/调度。无效请求不消耗额度。

## 手工验收

1. 配置空值之外的 `PLANNING_ENABLED`、`PLANNING_BASE_URL`、`PLANNING_API_KEY`、`PLANNING_MODEL`；未提供的单项沿用相应 `CHAT_*`。
2. 启动 PostgreSQL、Redis、MinIO、后端和前端，以 OWNER/ADMIN 打开"AI 任务规划"。
3. 创建日期位于项目范围内的规划，观察骨架、细节和 READY；测试取消、细节重试与重新生成。
4. 编辑并保存新版本，切换历史版本并恢复，制造旧 baseVersionId 保存冲突。
5. 添加循环依赖或越界日期，确认保存被拒绝；以 MEMBER 确认页面只读，以非成员验证 404。
6. 勾选检查声明并确认，网络超时后复用同一 key；进入任务看板核对 `sourcePlanId` 高亮。
7. 测试 DETAIL 修改骨架字段被拒绝（targetDate、sortOrder、title）。
8. 测试 source ref 格式校验（X1、S13 被拒绝）。
