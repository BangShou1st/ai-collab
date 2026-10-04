# 部署与恢复说明

2026-10-04：本轮仅验收隔离副本，没有发布授权；长对话质量尚未整体通过。正式库版本核对、正式备份恢复演练与正式迁移均未执行。

## 隔离验收入口

`AcceptanceDatabaseSupport` 必须显式设置 AI_UPGRADE_REHEARSAL=true，只允许 localhost:55432 的 .env 所指数据库；原业务容器保持停止。真实模型宿主另需 AI_REAL_ACCEPTANCE=true，执行 RealAcceptanceHostTest，使用现有合法配置的 space-bunny-free；停止标记为 backend/target/real-acceptance.stop。每次启动将修改隔离副本模型选择，正常停止 finally 恢复；进程硬杀不能依赖 finally，必须事先保存私有配置快照。

故障宿主另需 AI_RELIABILITY_ACCEPTANCE=true，执行 ReliabilityAcceptanceHostTest。它仅允许 127.0.0.1:18082 的测试模型 HTTP 端点，模型输出是受控脚本，不是提供商质量验收。控制文件 backend/target/reliability-fault-control.json 的 queueHold/mode 控制排队与详情响应；停止标记 backend/target/reliability-fault.stop。凭据、配置备份与原始日志仅放 target，不进 Git。

## 功能关闭与兼容

agent.enabled=false 关闭 Agent；agent.capabilities.planning=false 关闭规划工具；agent.capabilities.document-reading=false 关闭正文工具。agent.context.composer-v2=false 回退上下文组装，已有 working_state 可兼容读取。此次可靠性修复没有新增迁移，不回退 V54/V55。规划的排队操作保留同一 operation/attempt；运行中进程丢失允许明确失败后由用户对原规划重试，不恢复半截模型输出。正式确认仍只通过原规划服务对指定版本人工执行。

## 隔离副本停止与恢复

停止前保存运行/操作/版本与数据库不变量证据。正常停止宿主后核对监听消失，硬杀实验只终止已经核对为验收 JVM 的 18080 监听进程。恢复模型配置时使用验收前私有快照，恢复目的配置、默认标记和配置修改时间；测试提供商须禁用，历史引用保留。停止本轮转发器、模型端点、Ollama 及隔离容器，保留卷。不得启动原业务容器，也不得用隔离副本覆盖原库。

正式部署另行决定：先核对正式 Flyway 版本和未执行编号，备份数据库与对象存储，在另一副本实际恢复并断言，再形成确定启动配置和迁移步骤。本轮隔离库 V55 不代表正式库版本。
