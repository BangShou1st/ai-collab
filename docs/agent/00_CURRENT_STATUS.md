# Agent 当前状态

> 本页只记录当前工作区可由源码和新鲜验证证明的事实。历史阶段设计见本目录其他文档；测试数字必须在最终验证后更新。

## 当前实现

- Agent 运行链路已包含 session/message/run/step/approval/schedule、native tool calling、只读 legacy fallback、可信页面上下文校验、持久化事件与 SSE、项目记忆、定时 Skill 和受控 MCP。
- 数据库最新迁移为 V30；V27–V30 分别补充 runtime、event、MCP、memory/schedule skill 数据结构。已提交迁移不可修改。
- 前端 Agent 工作台使用 SSE 时间线并支持断线续传、审批、取消、定时运行和项目 MCP 白名单；管理中心支持 MCP 连接创建/更新、凭据轮换、测试、发现、Schema 确认和启停。
- OpenAPI 已包含 Agent event、memory、MCP connection/binding 契约。

## 2026-08-05 并发与 MCP 修复

1. RUNNING 取消不再要求 Worker 用领取时的旧 version 完成 `CANCELED`。取消请求在行锁事务中幂等写入；最终取消只允许 RUNNING→CANCELED，已取消重复落库为幂等，其他状态拒绝。
2. 审批服务在同一事务中依次锁定 Approval 和所属 Run，并只允许 WAITING_FOR_APPROVAL 执行写工具。取消先提交时，审批返回 `AGENT_RUN_CANCELED` 且不产生业务副作用；审批先提交时，其已提交写入不会被事后取消回滚，后续 Run 可被取消。
3. Runtime 的模型/工具异常路径保留取消业务异常，并在取消与并发 CAS 冲突时重新读取取消标志后安全落为 CANCELED。
4. MCP HTTP 响应在读取流时执行每连接 `maxResultBytes` 限制；超限返回 `AGENT_TOOL_RESULT_TOO_LARGE`。
5. `agent.mcp.allowed-hosts` 已显式映射 `AGENT_MCP_ALLOWED_HOSTS`。详细部署和浏览器验收见 `MCP_DEPLOYMENT_AND_BROWSER_TESTING.md`。
6. 原生写工具审批保留模型完成元数据；OWNER/ADMIN/MEMBER 的顶层运行可发起审批，最终执行仍只允许项目管理员。
7. MCP 仅暴露服务端声明 `readOnlyHint=true`、系统管理员确认只读且项目 OWNER 授权的工具；执行前重新读取连接、绑定、双层白名单和 Schema，未分类或写工具默认拒绝。
8. MCP Discover 的外部网络调用已移出数据库事务；定时任务因创建者失去成员身份而自动停用时，状态更新与审计处于同一事务。
9. `FAILED_RETRYABLE` 运行可直接取消；前端 SSE 能正确处理跨字节块拆分的 CRLF 帧边界。

## 当前安全边界

- MCP 仅允许 allowlist 内、解析到公网地址的 HTTPS Endpoint；STDIO、本地 HTTP、私网地址和自动重定向不可用。
- MCP 凭据加密存储且 API 不回传；工具结果按不可信内容清洗。
- 系统 MCP 连接只允许 system admin 管理；项目绑定只允许 OWNER 修改；实际工具取连接确认只读白名单、项目白名单与只读发现结果三重交集，并在每次执行前重校验。
- 项目资源始终按 `projectId + entityId` 校验，不能信任前端传入的页面 ID。

## 尚需外部环境验证

- 真实 NATIVE_TOOLS 模型冒烟需要有效模型配置和供应商凭据。
- GitHub MCP 联调需要有效 GitHub 账号/PAT、可用 remote MCP、DNS/网络和 HTTPS。
- 生产部署需要域名、证书、反向代理和平台 Secret；仓库自动化不能替代这些外部资源。

## 最终验证记录

最终测试、构建、迁移和 OpenAPI 校验结果在本次任务最终回复中报告；不要引用本页旧的历史数字作为完成证据。
