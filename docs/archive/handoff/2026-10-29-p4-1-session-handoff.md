# P4.1 本地工作交接（未完成）

## 当前状态和授权边界

用户要求停止当前工作，整理阶段报告，并在另一个本地会话继续。此次只授权本地代码、测试、增量迁移文件及部署配置模板；未授权部署、重启现有服务、修改 Staging 配置、对共享数据库执行迁移、清理真实数据或撤销真实用户授权。不要打开 Staging 或生产页面复现认证问题。不要推送、合并 main、reset、rebase、强推、清理已有文件、卷或历史，也不要输出密钥。

本轮最后执行的后端测试进程已退出；未启动最终源码的下一轮完整回归。不能宣称交付完成、所有检查通过或真实环境验收通过。当前 `REMOTE_DOWNLOAD_ACCEPTED` 和知识生命周期验收均未通过。

## Git 和未提交工作

- 仓库：`E:\project\ai-collab`，分支 `dev`。开始时本地及远端 dev 均为 `f7b528ab91ea911b5bd2fedd48b02483473c1073`；此前查询的远端 main 为 `a74b7961dd0c9b258badb08f2bb46eb4d8fd1f9a`。本地 `origin/main` 是旧快照，不能作为远端当前值。继续前重新只读核实 `git status`、分支和远端 dev/main；不能安全同步时停止报告。
- 当前本地 HEAD 为 `5bf6fbddb143d02900c9622866b2631fbecbabb1`。此前已提交：`2dd5cd7` 下载配置解耦、`9123c68` 公网下载配置收紧、`08c2a60` 邀请流程隔离测试、`5bf6fbd` 下载签名前存储状态校验。均未推送。
- OAuth、知识生命周期、相关前端、测试、V70 增量迁移、部署模板和审查文档已有大量本地暂存改动，尚未提交；还包括两处应单独归入下载修复的启动门禁文件 `MinioConfiguration.java` 与 `MinioPublicEndpointValidatorTest.java`。本次新增的交接文件和审查记录更新也属于本地工作。请先核实 index 和工作区，保护全部现有内容，切勿执行 `git reset`、`git clean`、覆盖 checkout 或丢弃改动。
- 已有 [本地审查报告](../report/2026-10-29-p4-1-local-review.md)、[变更文件清单](../report/2026-10-29-p4-1-files.txt)、[飞书生命周期设计](../../design/feishu-knowledge-lifecycle.md)。清单、报告和提交边界须在最终提交前按实际 `git diff` 再核对。

## 已完成的本地工作

- 下载：分离内部 MinIO endpoint、公开下载 Origin 和认证 Origin；检查公开地址、对象状态和权限，针对签名请求保留代理 Host、路径和查询参数。隔离 MinIO/Nginx 测过正确下载的 SHA-256、无效签名、篡改路径、过期及错误凭据；隔离 HTTP 测过成员、非成员、未登录和知识被屏蔽的访问。新增生产及 Staging profile 强制验证公开 Origin 的更改尚需最终源码完整回归和独立提交。
- 邀请：`08c2a60` 使用合成账号、隔离数据库及本地 HTTP 测试，覆盖管理员创建、普通成员拒绝、邮箱不匹配、注册和登录接受、过期、重复及并发接受。尚未测试线上邮件、真实账号或真实浏览器流程。
- OAuth 和知识生命周期：保留原应用身份连接；实现用户授权、加密存储、提前刷新、数据库互斥与版本条件更新、令牌轮换、撤权先屏蔽、持久化清理任务与恢复重放设计。已新增 `V70__connector_authorization_lifecycle.sql`；在隔离 PostgreSQL 中验证 V69 升级和旧数据保留，没有执行共享数据库迁移。飞书原文、已索引文本副本和原始二进制概念已区分，二进制下载未实现。
- 前端本地 `lint`、`typecheck`、`test`、`build` 曾通过：53 个测试文件、412 项；lint 有 76 条警告，构建有体积警告。下载 Compose 契约测试 2 项、生产配置脚本测试 5 项通过；此前的专项后端检查和本地代理、HTTP 隔离测试通过。远端 CI 未运行。

## 最新后端测试失败与继续步骤

最新完整 `mvnw verify` 结果为 **1958 项、0 断言失败、1 错误、44 跳过，BUILD FAILURE**：`AgentDelegationPostgresTest.delegationCreatesChildRunAndParentWaitsThenCollectsResult` 在第 310 行报 `CannotGetJdbcConnectionException`，底层为 `java.net.BindException: Address already in use: getsockopt`。未证明根因，先检查本机测试环境和该测试，再决定必要的修复或重跑；不能直接标记为通过。日志：`ai-collab-backend/target/codex-backend-verify-delivery.out`；单测报告：`ai-collab-backend/target/surefire-reports/com.shitulelv.aicollab.agent.application.runtime.AgentDelegationPostgresTest.txt`。

该轮运行开始后又修改了 `MinioConfiguration.java` 和 `MinioPublicEndpointValidatorTest.java`，因此即使修复上述失败，仍需对最终源码执行完整 `mvnw verify`。更早一轮 1958 项曾通过，但不覆盖全部最终改动。测试应在本机 Windows Maven + Docker Desktop 环境，使用隔离数据；不要连接共享数据库。前端代码如果继续修改，应重新执行相应检查。

测试达标并审阅后，按用户要求分开提交下载启动校验与 OAuth/知识生命周期改动，再更新审查报告、文件清单和 SHA。`git commit --only` 指定下载配置和测试两个路径可以避免一并提交已暂存的生命周期内容，但操作前必须复核 index。不要推送；不要将不完整的真实环境验证描述为验收通过。

## 新问题：服务器 Agent 显示未发现模型

用户反馈：服务器部署的 Agent 显示“未发现模型”，但用户已经配置过模型。本会话仅记录该反馈，**尚未复现、未读取线上配置或日志，根因未知**；这不能被当前隔离测试结果证明已解决。

后续先在本地从提示文案、Agent 模型选择请求及用户模型配置查询链路追踪，核对配置归属用户、可用状态、模型能力和实际返回的错误分类。可从 `agent/application/runtime/AgentModelConfigurationStore.java`、`infrastructure/ai/user/UserAiProviderService.java` 和 `ProviderModelDiscoveryService.java` 等相关文件入手，再根据代码事实确定原因；不要假定是未配置、密钥错误或某一部署变量导致。诊断实际服务器需先确认用户允许的只读操作范围，禁止输出密钥，且遵守不打开线上页面复现的现有约束。

## 已知发布与验收缺口

- 真正的 HTTPS 下载、内容 SHA-256、成员与签名负例、实际 Staging 配置、真实飞书授权、刷新、撤权清理、事件投递和备份恢复演练尚未执行。完成本地审查后须单独申请 Staging 窗口，不能把本地容器或 Mock 当作端到端验收。
- 飞书事件可能延迟或丢失，无法保证外部撤权被即时发现；已有模型请求可能在撤权后仍向上游发送之前取得的上下文。Nginx 检查与上游转发之间存在极短窗口，已开始的响应无法撤回。仅移除项目成员不会撤销其已持有的 URL，剩余有效期内仍可能使用。
- 来源不完整的历史回答、Agent 事件和记忆目前隔离而非物理擦除；授权所有者被移出项目后缺少自助断开入口。独立不可变删除记录归档尚未自动交付，也没有实际备份恢复演练。这些限制和处置要求见生命周期设计与审查报告，发布前须明确解决或经授权评估。
- 实际回滚需另行批准：隔离下载和知识入口，暂停同步与清理，恢复已验证的应用及代理组合；保留 V70 结构与数据，恢复后先重放独立归档删除记录并验证，再另行批准开放。禁止删除数据、对象、卷或历史。
