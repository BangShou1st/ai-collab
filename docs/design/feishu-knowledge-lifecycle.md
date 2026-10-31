# 飞书知识生命周期与恢复约束

本方案保留现有应用身份连接及文档处理、pgvector 管线。用户身份授权是新增的可选路径；现有知识源的 `user_authorization_id` 默认为空。V70 是增量迁移，不修改 V1–V69，也不触发全量索引。

## 数据与用户可见内容

- 连接器调用飞书 Docx `raw_content`，以 UTF-8 `.txt` 形式保存到私有 MinIO；文档、版本、来源和同步状态保存在 PostgreSQL，解析后的正文、分块和向量使用既有管线。
- “在飞书中打开”跳转飞书原文；“下载已索引文本”下载平台保存的文本副本。当前不提供飞书原始二进制文件的导出和下载。
- 用户授权所需范围包括 `offline_access`、`drive:drive:readonly`、`docx:document:readonly`。实际飞书应用必须在开发者后台配置权限和精确回调 URL，并在授权发布后验证；这些条件不能由本地 Mock 证明。
- 用户授权还要求配置飞书 `auth:user_access_token:read` 权限和 `auth.user_access_token.revoked_v4` 事件。系统管理员为应用连接加密保存事件 Encrypt Key 和 Verification Token，并在飞书后台将请求地址设置为后端 HTTPS 网关加 `/api/v1/connector/events/{connectionId}`；成功完成飞书的加密地址校验后才能开始用户授权。凭据更换会在同一事务中屏蔽关联授权、要求重新授权并保留数据，校验完成前不能创建新授权。
- 飞书授权码和令牌只在服务端交换。Access Token、Refresh Token 和 PKCE verifier 使用现有 AES-GCM 密钥加密；状态只保存 SHA-256 摘要；接口和日志不返回 Token。`MODEL_CONFIG_MASTER_KEY` 应保存在受控的密钥系统并随备份独立保护，不能写入仓库。

## 授权、屏蔽和清理

`ACTIVE` 授权在 Access Token 到期前五分钟尝试刷新。刷新前写入持久化领取标记并增加版本号；同一授权的其他实例不能同时领取。新 Token 对以一条条件更新原子保存，授权断开或另一代授权更新后，旧结果不能覆盖。授权码回调也通过启动时的授权版本防止迟到结果覆盖断开操作。

授权所有者仍是项目成员时，即使已被降级或项目已归档，也可断开自己的授权；断开不受项目普通写入锁限制。用户已被移出项目时，当前项目接口不再可用，仍需通过飞书侧撤销令牌使事件触发屏蔽；提供成员退出后的自助清理入口需要另行设计。

飞书刷新 Token 单次使用。明确的 429 等限流返回可有界退避重试；网络超时或 5xx 在服务端是否已轮换未知，不能安全重放旧 Token，进入 `REFRESH_UNCERTAIN` 并屏蔽知识，保留数据等待重新授权。Token 失效或刷新 Token 被撤销进入 `REAUTH_REQUIRED`；刷新 Token 被撤销本身不足以证明用户已撤销完整授权，不自动删除知识。确定撤权或用户主动断开时，授权状态、访问屏蔽、删除记录和清理任务在事务中提交。

飞书撤销事件按官方协议验证原始请求签名、时间、加密负载与 Verification Token；用户身份仅保留应用、租户和 Open ID 的密钥摘要。事件幂等入库并屏蔽所有对应授权，但不会单凭令牌撤销清理文档。事件时间与授权码交换之间设置保守的时间余量，阻止并发或迟到的旧回调覆盖撤销；刚发生撤销后立即重授权可能需要稍后重试。校验成功只证明回调地址可达，不能证明事件已订阅或后续持续送达。真实环境必须验证事件权限、订阅、网关和重试，并监控送达延迟、失败及系统时钟；若事件丢失或延迟，本系统无法保证外部撤权被立即发现，这是发布门禁。

事件握手使用飞书要求的原始 `challenge` 响应，下载授权子请求仅使用 Nginx `auth_request` 所需的状态码；这两个协议入口不使用普通业务接口响应包装。

文件夹扫描只在完整分页后进行差异比较。截断、缺少续页标记、超时、429 和 5xx 不删除已有知识；无法访问的文件或文件夹优先屏蔽，暂不物理删除。同步写入与授权断开通过数据库锁串行化。后台任务根据持久租约和退避调度重复执行，删除对象失败时保留屏蔽状态和待处理任务；数据库行与向量仅在对象删除成功后清理。删除记录保留最小身份和对象定位信息，不包含令牌或文档正文。

确认断开的清理完成时，外部文档记录保留内部审计标识和状态，原文标题、链接、外部文件定位符、内容哈希和修改时间被清除或替换为不可用于访问原文的内部标识；历史版本记录保留内部身份，不保留内容哈希和原文修改时间。知识源级文件夹标识用于重新授权后的同步，仍属于受控元数据，其保留期限须由部署环境的数据治理策略规定。

扫描要求响应包含明确的 `files` 数组、`has_more` 和每个条目的身份及类型；缺失或矛盾时按不完整响应处理，保留已有知识。对于飞书实际空文件夹是否可能省略 `files` 字段，仍需真实授权环境核对；若省略，当前实现会安全地拒绝该次扫描而不是推断删除。

历史回答可能没有完整来源，Agent 消息、事件和项目记忆也可能含未标注的来源内容。当前实现保守屏蔽受影响项目内已有的相关历史集合；这可能暂时隐藏无关内容。流式输出在发送前检查并短暂持有读锁，使已提交的撤权不能被旧流继续发送。尚未发送完成的网络包和已经发往模型提供方的数据不能撤回。

由于旧回答和 Agent 历史缺少可靠的逐段来源，当前清理不会物理删除或重写这些历史正文，而是持续隔离；其数据库与备份副本仍须按受控保留策略管理。如果业务或法规要求彻底擦除所有派生历史，需先解决来源识别及避免误删无关记录的问题，不能把当前屏蔽称为彻底擦除。

项目概览的最近文档、审计详情和站内通知也根据同一持久化可见状态隐藏文档标题；暂时失效时保留原记录，重新授权后可恢复展示。确认断开的清理事务保留审计和通知记录身份与时间，但去除其中的文档标题等详情；迟到的文档通知在写入时再次检查可见状态。

文档列表及可见状态会查询外部文档与删除记录，生产大数据量下需要通过执行计划和负载测试评估关系型索引及查询成本；本次不会自动重建已有向量索引。

文档处理任务在领取、心跳、进入向量化和提交索引时检查可见状态；候选索引在发送内容前和保存向量前再次检查。任务被屏蔽后不会提交新的可见索引；屏蔽与外部请求开始之间仍有并发窗口，已经发出的模型请求无法撤回。问答或 Agent 在途请求如果已检索出内容，可能在撤权提交后才将先前取得的上下文发送给模型；当前输出屏蔽不等于可中断上游请求。如需严格禁止这一情况，必须增加可中断的模型调用协调并在目标环境验证。

## 下载代理

`MINIO_ENDPOINT` 是内部 MinIO 地址，`MINIO_PUBLIC_ENDPOINT` 是独立 HTTPS 下载 Origin，`AUTH_ALLOWED_ORIGINS` 仅用于应用认证。Nginx 对 `/storage/` 每次 GET/HEAD 发起内部授权子请求，只转发无查询参数的路径；拒绝后不访问对象。通过后才保留外部 Host 和原签名查询参数转发至私有桶。请求开始前已签发的五分钟 URL 在屏蔽后再次请求也必须被拒绝；代理或数据库故障时应失败关闭。

已开始的对象响应无法由普通 Nginx `auth_request` 在整个传输期间逐字节撤回。授权检查到开始转发之间也存在极短并发窗口；如业务要求对在途响应达到更强的即时中止保证，需要设计可中断下载网关并单独验证。部署前必须确认外层 HTTPS 网关不记录签名查询、授权码，也不缓存对象响应；仓库中的 Nginx 禁用这两类路径的访问日志不足以证明外层网关行为。

对象代理可以在知识授权被屏蔽后拒绝所有针对该对象的旧签名 URL。预签名 URL 本身不绑定平台成员身份，因此单个成员被移出项目时，若对象仍对项目可访问，该成员已持有的 URL 仍可能在有效期内使用；若业务要求成员移除也立即失效，需要另行设计按用户绑定、逐次鉴权的下载凭据。

## 备份和灾难恢复

删除记录所在的数据库快照与其要删除的数据可能一起回滚，单靠该表不能提供恢复后的删除保证。生产必须在独立故障域保留经过完整性保护的连续 PostgreSQL WAL/变更归档或等价的不可变删除记录，并保留至所有包含已撤权数据的数据库和对象备份过期之后。记录必须具备可核对的连续位置或水位；最新已确认的水位要独立保存。密钥管理、归档复制确认、保留时间和演练由部署环境实际落实，不能由当前仓库代码假定已配置。

恢复环境必须先隔离公网入口并停止同步、文档处理及清理调度；恢复数据库和对象后，从独立归档取得覆盖恢复点至故障前最新确认水位的完整删除记录，校验身份、完整性、连续性和水位。`DeletionJournalReplayCli` 在无 Web 服务器、无 Flyway、无调度器的独立进程中校验 HMAC、记录数和操作员提供的水位，再调用 `ConnectorDeletionReplayService` 写入屏蔽、历史隔离、删除记录和可重试任务。记录按最多 1000 条事务分批，失败后保持隔离，核对并幂等重放。恢复期可设置 `CONNECTOR_CLEANUP_ENABLED=false`；完成重放和一致性检查后，再经批准启用清理及对外入口。

回放输入为 UTF-8 JSON，字段为 `watermark`、`count`、`records`，每条记录包含 `projectId`、`sourceId`、`documentId`、`objectKey`。HMAC-SHA256 覆盖整个文件原始字节；数据库连接、HMAC 密钥、预期 MAC 和独立确认的水位通过进程环境注入，禁止写入仓库或终端。工具要求 `AI_COLLAB_RECOVERY_CONFIRM_OFFLINE=YES`。运维必须独立验证归档覆盖范围，HMAC 和记录数本身不能证明缺失的历史记录从未存在。

工具读取 `AI_COLLAB_RECOVERY_DATABASE_URL`、`AI_COLLAB_RECOVERY_DATABASE_USER`、`AI_COLLAB_RECOVERY_DATABASE_PASSWORD`、`AI_COLLAB_RECOVERY_JOURNAL_HMAC_KEY`、`AI_COLLAB_RECOVERY_JOURNAL_MAC` 和 `AI_COLLAB_RECOVERY_EXPECTED_WATERMARK`。这些值通过受控密钥注入提供；主类为 `com.shitulelv.aicollab.connector.recovery.DeletionJournalReplayCli`，唯一位置参数为已验证输入文件路径。运行时需要项目编译类及其 Spring JDBC、PostgreSQL 和 Jackson 依赖；该主类不会执行 Flyway 或启动 Web 服务。

目前仓库没有自动向独立不可变归档交付删除记录的实现，也没有完成真实备份/恢复演练。这是发布门禁，不能将本地回放测试当作恢复能力验收。

## 连接作用域与项目隔离

项目使用连接（发起用户 OAuth、创建知识源）必须经过 `ConnectorConnectionService.requireAvailableForProject(projectId, connectionId)`：SYSTEM 连接对所有项目可用，PROJECT 连接仅 `ownerProjectId` 对应项目可用；不存在、非 ACTIVE 或他项目私有的连接一律按 `CONNECTOR_CONNECTION_NOT_FOUND` 处理，避免泄露其他项目的连接信息。管理操作（验证凭据、配置事件凭据、删除）由 `requireManageable` 单独约束：PROJECT 连接要求归属项目 Owner/Admin，SYSTEM 连接要求系统管理员。前端隐藏入口不是权限边界；新增任何接受 `connectionId` 的项目级接口都必须复用该作用域校验，不得直接 `connections.find` 后仅判断状态。

用户授权（grant）本身已按 `projectId + connectionId + userId` 隔离：`list/disconnect` 校验项目成员与所有者，`saveAuthorization` 以三元组唯一约束落库，事件凭据更换会对该连接全部非撤销授权触发 `REAUTH_REQUIRED` 并通过可见性服务屏蔽关联知识源。

## 官方协议依据

- [飞书用户令牌刷新](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/refresh-user-access-token-v3)
- [获取用户 Access Token](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3)
- [读取 Docx 文档纯文本内容](https://open.feishu.cn/document/server-docs/docs/docs/docx-v1/document/raw_content)
- [用户令牌撤销事件](https://open.feishu.cn/document/authentication-management/auth-v4/revoked)
- [获取当前用户信息](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)
