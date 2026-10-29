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

用户反馈：服务器部署的 Agent 显示“未发现模型”，但用户已经配置过模型。经共享库版本侦察确认了最可能的根因：ai-collab 曾钉在 OpenCode Zen 共享库 v1.4.2，该版本的 JVM 直连出站与 2.0.29 客户端身份，在免费档自 2026-10 下旬起按传输指纹拒绝非 Bun 流量后不再可用（同一形状请求从 Bun 发出 200、从 JVM 直连 429/被拒）。设置页“免费模型 · 自动发现”是白名单与上游 `/models` 的交集，旧身份拿到的目录为空时页面即“无模型”。**尚未读取线上配置或日志确认，不能替代线上验证。**

本地已完成升级（提交 `66e3111`）：共享库升至 v1.6.1（JitPack 已发布），Zen wire 全部走共享库塑形与本地 Bun 中继（默认 auto，回环 127.0.0.1:18992）。**服务器部署硬性前提：Linux 容器/宿主必须预装 Bun（`ZEN_BUN`、`~/.bun/bin/bun` 或 `PATH`；库只内置 Windows x64 自动下载）**，并按需设置 `OPENCODE_ZEN_RELAY_URL`（留空=auto、`none`=直连、或显式地址）。共享库白名单本身在 v1.4.2→v1.6.1 零变化；若升级并预装 Bun 后免费模型列表仍为空，则是上游目录真实变化，需按 zen-sync 口径重审白名单并在共享库侧发版。服务器侧的其他可能（配置归属账号、默认/AGENT 用途分配、`enabled=false`、主密钥）见上文核查清单，仍然适用。

已核实的代码事实：

- Agent 每轮通过 `AgentModelConfigurationStore.require` 调用 `UserAiProviderService.resolve(userId, AGENT)`：先取 AGENT 用途分配，再取默认配置；两者都没有抛 `AI_PROVIDER_UNAVAILABLE`；解析到但 `enabled=false` 也抛同一错误。模型配置按用户隔离，跨账号不可见。
- 预设（Zen/Kilo）保存时 `makeDefault = setDefault || 首个配置 || 原本就是默认`，正常保存会成为默认；因此“配置存在但 Agent 找不到”更可能指向：服务器数据库与配置时不是同一库/同一账号、配置被停用，或 AGENT 用途分配与默认配置均缺失。
- 若显示的是“凭据无法解密”，属于 `AI_MODEL_CREDENTIAL_INVALID`，与主密钥（`MODEL_CONFIG_MASTER_KEY`）相关，和“未发现”是不同故障。
- 设置页“免费模型 · 自动发现”列表在 `HttpOpenCodeZenTransport.intersectWithAdmitted` 中等于白名单与上游 `/models` 的交集：上游不可达时回退白名单（非空），上游可达但返回列表与白名单无交集时会得到空列表，页面即“无模型”。这是与 Agent 启动失败并行的另一条独立链路。

服务器侧只读核查建议（均不涉及密钥输出）：在 Staging 页面确认当前登录账号的个人 AI 设置里存在已启用配置，且“设为默认”或 AGENT 用途分配已勾选；核对服务器使用的数据库实例/卷与配置时是否同一环境；如有数据库只读权限，检查 `user_ai_provider` 中该账号行的 `enabled` 与默认/用途字段；区分失败文案到底是“模型尚未配置或不可用”还是“凭据无法解密”。若为免费模型列表为空，则属白名单与上游交集问题，与 Agent 解析无关。修改线上配置或重启服务仍需用户单独授权。

后续先在本地从提示文案、Agent 模型选择请求及用户模型配置查询链路追踪，核对配置归属用户、可用状态、模型能力和实际返回的错误分类。可从 `agent/application/runtime/AgentModelConfigurationStore.java`、`infrastructure/ai/user/UserAiProviderService.java` 和 `ProviderModelDiscoveryService.java` 等相关文件入手，再根据代码事实确定原因；不要假定是未配置、密钥错误或某一部署变量导致。诊断实际服务器需先确认用户允许的只读操作范围，禁止输出密钥，且遵守不打开线上页面复现的现有约束。

## 已知发布与验收缺口

- 真正的 HTTPS 下载、内容 SHA-256、成员与签名负例、实际 Staging 配置、真实飞书授权、刷新、撤权清理、事件投递和备份恢复演练尚未执行。完成本地审查后须单独申请 Staging 窗口，不能把本地容器或 Mock 当作端到端验收。
- Zen 免费档在本地只能验证 wire 契约与装配（已全绿）；“升级即修复线上”仍需在服务器预装 Bun 后用真实 Zen 调用验证（如 `AI_ZEN_SMOKE=true` 冒烟或真实页面调用），且上游规则近期变动频繁，不能假设一次升级长期有效。
- 飞书事件可能延迟或丢失，无法保证外部撤权被即时发现；已有模型请求可能在撤权后仍向上游发送之前取得的上下文。Nginx 检查与上游转发之间存在极短窗口，已开始的响应无法撤回。仅移除项目成员不会撤销其已持有的 URL，剩余有效期内仍可能使用。
- 来源不完整的历史回答、Agent 事件和记忆目前隔离而非物理擦除；授权所有者被移出项目后缺少自助断开入口。独立不可变删除记录归档尚未自动交付，也没有实际备份恢复演练。这些限制和处置要求见生命周期设计与审查报告，发布前须明确解决或经授权评估。
- 实际回滚需另行批准：隔离下载和知识入口，暂停同步与清理，恢复已验证的应用及代理组合；保留 V70 结构与数据，恢复后先重放独立归档删除记录并验证，再另行批准开放。禁止删除数据、对象、卷或历史。
