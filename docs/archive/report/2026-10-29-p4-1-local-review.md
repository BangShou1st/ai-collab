# P4.1 本地代码审查与验证记录

## 边界与基线

本次只修改本地 `dev` 代码、测试、迁移文件和部署模板。没有部署、重启现有服务、执行共享数据库迁移、操作真实文档或真实用户授权。开始时本地及远端 `dev` 均为 `f7b528ab91ea911b5bd2fedd48b02483473c1073`；查询到的远端 `main` 为 `a74b7961dd0c9b258badb08f2bb46eb4d8fd1f9a`。本地下载修复提交为 `2dd5cd7`、`9123c68` 和 `5bf6fbd`；[变更文件清单](2026-10-29-p4-1-files.txt) 覆盖本次相对基线的改动，提交明细以 `git log`、`git show --stat` 为准，没有推送或改写历史。

GitHub 提交比较显示，远端 `main` 相对基线 `dev` 超前一个提交、没有文件差异；本地 `origin/main` 仍指向较旧的 `11dfcfc`，不能当作远端实际状态。本次没有将 `main` 合并或改写进 `dev`。邀请隔离测试独立提交为 `08c2a60`。

## 审查重点

- 仓库基线证据：原 Staging Compose 将 `MINIO_PUBLIC_ENDPOINT` 直接取自 `AUTH_ALLOWED_ORIGINS`；基线 Staging Nginx 配置已有 `/storage/` 路由，而 Pages Function 位于 `functions/api/` 并处理 `/api/*`。这证明仓库配置存在下载 Origin 绑定风险，不能据此推断当前实际 Staging 的环境变量、镜像或网关路由；本次授权不允许访问线上页面复现。
- 下载：`MINIO_ENDPOINT` 保持容器内部地址；`MINIO_PUBLIC_ENDPOINT` 与 `AUTH_ALLOWED_ORIGINS` 独立，并在 Compose 和生产、Staging profile 启动时校验公开 HTTPS Origin。生成地址前校验项目成员，并对对象执行内部只读检查，区分凭据、对象不存在、连接和签名错误。Nginx 保留签名 Host 和查询参数，去除 `/storage/` 前缀后转发私有 MinIO，下载路径及 OAuth 回调禁用 Nginx 访问日志；文件请求时还校验当前知识可见状态。对象检查完成后存储或网络仍可能变化，真实文件请求的结果需独立验证。
- OAuth：保留应用身份连接；新增用户授权、PKCE、服务端 AES-GCM 加密、令牌提前刷新、数据库领取与版本条件更新。单次使用的 Refresh Token 遇网络超时或 5xx 时，不盲目重试；暂时限流可有界重试。授权失效优先阻断知识，暂不删除。
- 飞书事件：加密事件、原始请求签名、Verification Token、时间和身份均经检查，事件 ID 去重；数据库锁与授权时间防止迟到回调覆盖撤销。回调地址完成验证才允许开始用户授权。变更事件凭据会先屏蔽对应授权且不删除数据。外部事件的订阅和持续送达必须在实际环境独立验证。
- 知识：在服务端屏蔽文档、检索、问答、引用、历史回答和 Agent 历史；项目概览、审计详情及通知也屏蔽文档标题，已签发 URL 的新请求受代理校验。确定断开后先事务内屏蔽并记录删除，再由持久任务以租约和退避重试清理对象、分块和向量，并清除文档标题、原文链接、外部定位符及内容哈希；审计和通知保留记录身份与时间、去除文档详情。历史内容来源不完整时采用保守隔离，可能影响部分无关历史。
- 迁移：新增 `V70__connector_authorization_lifecycle.sql`，不修改 V1–V69，不自动重建索引。旧应用身份数据在隔离 PostgreSQL 中从 V69 升级到 V70 验证保留。
- 飞书原文、已索引 `.txt` 副本、原始二进制文件有明确区分；当前未实现原始二进制下载。
- 邀请：仅在隔离数据库和本地 HTTP 服务器中使用合成用户与邀请码，检查项目管理员创建、成员权限、邮箱匹配、登录或注册后接受、过期及并发接受；不代表线上邮件或真实用户流程已验收。

## 本地验证

- 后端完整 `verify`：较早一轮共 1958 项、0 失败、跳过 44 项；之后补充了审计与通知元数据屏蔽。最新一轮共 1958 项、0 断言失败、1 错误、跳过 44 项，整体失败：`AgentDelegationPostgresTest.delegationCreatesChildRunAndParentWaitsThenCollectsResult` 无法连接隔离 PostgreSQL，根异常为 `java.net.BindException: Address already in use: getsockopt`。原因尚未完全确定，不能将其归结为业务缺陷或单纯环境故障；日志在 `ai-collab-backend/target/codex-backend-verify-delivery.out`，详情见 `target/surefire-reports/com.shitulelv.aicollab.agent.application.runtime.AgentDelegationPostgresTest.txt`。这轮编译后又增加了生产和 Staging profile 的下载 Origin 启动校验，因此最终源码仍须重新执行完整回归。更早一次执行中，有一个测试仍预期迁移版本 69，另一个隔离 PostgreSQL 连接出现端口绑定错误；修正断言并在 Windows Docker 环境单独复跑，两处均通过。
- 定向后端检查：22 项通过，包含隔离 PostgreSQL V69→V70、事件解密和签名、事件乱序及旧回调防覆盖、OAuth、Nginx/MinIO 签名代理、Origin 校验、邀请 HTTP 流程及上述两个失败测试。
- 后续专项检查：扫描、屏蔽和后台索引相关 33 项通过；审计、通知、项目概览和下载 HTTP 相关 8 项通过。独立真实 MinIO 容器检查验证无效凭据、对象不存在、签名 Host、篡改和过期；仅使用合成数据。
- 前端 `pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm build` 通过；测试为 53 个文件、412 项。lint 有 76 条既有警告，构建有既有打包体积警告。一次与其他检查并发运行的测试遇到依赖加载失败；依赖文件存在，单独重跑通过。
- 配置脚本：`node --test scripts/minio-compose-contract.test.mjs` 2 项通过；`node --test scripts/prod-config-check.test.mjs` 5 项通过；后者使用本机 OpenSSL 路径。

容器和 Mock 检查不构成真实环境端到端验收。`REMOTE_DOWNLOAD_ACCEPTED` 未通过；知识生命周期验收未通过。
本地执行了仓库规定的检查命令；因为没有推送分支，GitHub Actions 远端 CI 尚未运行。

## Zen 共享库升级（v1.4.2 → v1.6.1）

应用此前钉在共享库 v1.4.2：JVM 直连出站、2.0.29 客户端身份。免费档自 2026-10 下旬按传输指纹拒绝非 Bun 流量，这正是“服务器上免费模型列表为空/Agent 报模型未配置”的最可能根因。升级（提交 `66e3111`）后：`pom` 钉 v1.6.1（JitPack 已构建）；Zen 请求体经 `OpenCodeZenProtocolPolicy.shapeEnvelope` 统一塑形——注入官方 2.x 系统提示与完整官方工具集、`max_completion_tokens`、`stream_options.include_usage` 与键序指纹；身份头与身份窗口由共享工厂生成，经 `opencode.zen.relay-url`（默认 auto）的本地 Bun 中继出站；业务工具出线为 `clint_` 名，响应侧按 `OpenCodeZenToolRenames.restore` 还原为业务名，官方工具调用仍拒绝进入运行时；`completeWithSession` 统一走流式同步（2.x wire 只有流式出口）；`ProviderPresetRegistry` 的 User-Agent 注解默认值因库侧常量改为方法组合而调整为运行时回退。白名单本身在两个版本间零变化。

本地验证：定向 91 项测试通过（`ModelProtocolHttpTest`、Zen 装配/规划/知识流契约、`ZenUpstreamRoutingTest`、预设与 Kilo 侧无涟漪），并新增 wire 名还原与既有保留名拒绝的断言；最终源码完整回归 **1960 项、0 失败、0 错误、跳过 44 项，BUILD SUCCESS（14:41 min）**，日志 `ai-collab-backend/target/codex-backend-verify-zen2.out`。期间修正一处测试口径：`completeWithSession` 在 2.x wire 下统一走流式，非流式 POST 桩改为流式桩并保留“截断时 usage 随异常携带”的契约。运行时行为变化：Zen 请求体不再“极简”（含补全上限与用量协商），此为 2.x wire 契约要求；本地测试通过 `opencode.zen.relay-url=none` 保持封闭，生产默认 auto 走中继。

部署前提（本地与服务器不同）：本机 Windows 已装 Bun 1.4.4，auto 模式直接复用；**Linux 服务器没有 Bun 自动下载兜底，应用镜像或宿主必须预装 Bun**（`ZEN_BUN`、`~/.bun/bin/bun` 或 `PATH`），中继仅绑定回环 `127.0.0.1:18992`，无需额外开放端口；`OPENCODE_ZEN_RELAY_URL` 可显式指定或置 `none`（当前会破坏免费档调用）。已写入 `docs/deployment/production-foundation.md` 与部署模板。

剩余风险：上游规则近期变动频繁（库注释实测 2026-10-26，上游三天内多个 tag），一次升级不保证长期有效；v1.6.1 的语义同步位落后上游 FreeX 约 5 个 tag；“升级即修复线上”必须在服务器预装 Bun 后以真实 Zen 调用验证；若届时免费模型列表仍为空，则属上游目录真实变化，需按 zen-sync 口径重审白名单并在共享库侧发版。

## 配置与上线前提

Staging 必须独立配置浏览器可访问的 `MINIO_PUBLIC_ENDPOINT`，并核实外层 HTTPS 网关将 `/storage/` 转发至仓库中的 Nginx；`AUTH_ALLOWED_ORIGINS` 只配置应用认证允许的来源。生产下载 Origin 依实际公网拓扑配置。应用需配置精确的 `FEISHU_OAUTH_REDIRECT_URI`；系统管理员在飞书应用配置所需权限和事件订阅，并通过应用界面加密保存事件凭据。`CONNECTOR_CLEANUP_ENABLED=false` 可用于离线恢复期暂停清理。相关值通过受控配置或密钥系统注入，禁止写入 Git、日志或验收证据。

下载授权子请求默认只信任 Compose 代理地址 `172.30.173.2`，该地址必须与实际容器网络及后端 `storage.minio.trusted-proxy-address` 一致；如环境网段冲突，需一并调整并重新验证。生产应用账号还需要对私有桶具备对象读取及获取桶区域的权限，禁止公开 MinIO API 或控制台端口。

仓库没有自动交付删除记录至独立不可变归档；发布前必须建立并演练带连续水位与完整性校验的归档和恢复重放。外层网关不得缓存对象或记录签名查询、OAuth 授权码。事件丢失或延迟会使外部撤权发现延迟；Nginx 校验后、转发开始前仍存在极短窗口，已经开始的下载响应也不能被普通代理逐字节撤回。以上均须按 [生命周期设计](../../design/feishu-knowledge-lifecycle.md) 评估，不能凭本地测试宣称已解决。

知识屏蔽会使已签发 URL 的后续请求失效；但单独移除一个项目成员不会撤销其已经持有的预签名 URL，该 URL 最多在剩余有效期内仍可能使用。若要求成员身份变更即时撤销，需要额外的按用户逐次鉴权设计。

没有可靠来源标注的旧回答、Agent 事件和记忆会持续被隔离，但不会被自动物理擦除；若要求彻底删除派生历史，需先解决来源识别、备份保留及误删无关记录的问题。授权所有者被移出项目后，当前项目接口也不能让其自助断开；可以在飞书侧撤销并依赖事件屏蔽，独立的退出后自助清理入口仍待设计。

输出屏蔽不保证取消已在途的模型调用：请求可能在撤权前检索、在撤权后才发送先前取得的上下文。严格的上游调用中断需要额外设计，不能把当前本地检查解释为已解决这一边界。

## 回滚原则

本地通过新提交回滚代码，禁止 reset、强推或改写共享历史。实际环境回滚必须先另行获批：先隔离下载和知识入口，暂停同步和清理，再恢复经验证的应用与代理组合；不能让旧代理绕过撤权屏蔽。V70 为增量迁移，回滚代码时不自动回退或删除数据库结构、对象和卷。涉及备份恢复时先在隔离环境重放独立归档中截至最新确认水位的删除记录，验证屏蔽与清理任务，完成后再另行批准开放入口。

真实 HTTPS 下载与 SHA-256、成员及签名负例、飞书授权刷新和撤权清理、外部事件交付、独立备份与恢复演练均需要单独授权的目标环境验证。
