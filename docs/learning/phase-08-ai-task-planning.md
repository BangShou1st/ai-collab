# Phase 08：AI 任务规划学习笔记

Phase 08 把模型建议变成一条可审查、可编辑、可追踪且可安全落地的工作流。创建接口先保存不可变输入和骨架 attempt，再返回 202；模型调用在独立执行器和数据库事务外完成。

## 两阶段生成与安全边界

第一阶段只生成摘要、假设、风险、里程碑和任务身份骨架。第二阶段补全描述、工时、日期、负责人建议、依赖和来源，同时必须保持骨架数量、tempKey、标题、目标和所属里程碑。解析或校验第一次失败时只携带安全错误码和 schema 修复一次，第二次失败进入对应失败状态。

项目数据、用户输入和文档片段都不是指令。Prompt 对 `</SOURCES>` 等边界进行转义，并明确拒绝资料中的角色声明、系统提示和格式要求。来源按 content hash 去重，使用 Unicode code point 计算 16000 预算，保存 S1–S12 的不可变快照；引用只能指向该快照。

## 版本、校验与并发

AI 骨架、AI 完整计划、人工编辑和历史恢复都产生新版本，旧版本不更新。人工保存锁定 plan 并要求 `baseVersionId` 等于 latest；否则返回 `PLAN_VERSION_CONFLICT`。统一校验器检查项目/规划/任务日期、规模、tempKey、成员、来源、每任务依赖上限和 DAG。

DAG 校验以任务 tempKey 为节点执行深度优先搜索；路径集合再次遇到当前节点即为环。前置任务截止晚于后续任务开始也是硬错误。未分配、无来源和疑似重名属于 warning，不会被静默修正或合并。

## 取消、恢复与确认

取消不仅中断 Future，还锁定 plan、标记 attempt、增加 generation sequence 并清除 active attempt。任何迟到写回必须再次匹配 plan、sequence、attempt 和状态，失败时标记 DISCARDED。恢复任务扫描十分钟未更新的生成/确认并以 `PROCESS_RESTARTED` 安全收尾。

确认使用 UUID `Idempotency-Key`。数据库 confirmation 的项目级 key 唯一与 plan 唯一约束是事实来源；Redis 不参与正确性。认领后进入 CONFIRMING，正式里程碑、任务、依赖、来源字段、plan CONFIRMED、confirmation SUCCESS 和审计结果在原子事务中提交，异常时全部回滚并恢复 READY。

## 手工验收

1. 配置空值之外的 `PLANNING_ENABLED`、`PLANNING_BASE_URL`、`PLANNING_API_KEY`、`PLANNING_MODEL`；未提供的单项沿用相应 `CHAT_*`。
2. 启动 PostgreSQL、Redis、MinIO、后端和前端，以 OWNER/ADMIN 打开“AI 任务规划”。
3. 创建日期位于项目范围内的规划，观察骨架、细节和 READY；测试取消、细节重试与重新生成。
4. 编辑并保存新版本，切换历史版本并恢复，制造旧 baseVersionId 保存冲突。
5. 添加循环依赖或越界日期，确认保存被拒绝；以 MEMBER 确认页面只读，以非成员验证 404。
6. 勾选检查声明并确认，网络超时后复用同一 key；进入任务看板核对 `sourcePlanId` 高亮。
