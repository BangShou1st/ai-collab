# MCP 部署、管理与浏览器验收

本文只描述当前代码已经实现的行为。接口契约以 `docs/api/openapi.yaml` 为准，数据结构来自 V29/V30 Flyway 迁移。

## 1. 当前架构与安全边界

ai-collab 后端是 MCP Host。系统管理员通过 `/api/v1/admin/agent/mcp-connections` 保存连接，后端用 MCP `initialize`、`tools/list`、`resources/list` 做健康检查和发现，再对工具定义计算 SHA-256 Schema Hash。项目 OWNER 通过 `/api/v1/projects/{projectId}/agent/mcp-bindings` 绑定连接并设置项目级白名单。Agent 实际可见工具是“已发现且声明只读的工具 ∩ 连接级确认只读白名单 ∩ 项目级白名单”，名称带连接命名空间，MCP 输出按不可信数据清洗后才进入 Agent 上下文。

当前 MCP 集成只执行只读工具。工具必须同时满足：发现结果含 `annotations.readOnlyHint=true`、系统管理员把它加入连接级确认只读白名单、项目 OWNER 把它加入项目白名单。缺失或为 `false` 的只读标记一律不暴露；绑定、连接、白名单或已确认 Schema 在执行前发生变化时，请求会在调用外部 MCP 前失败。对 GitHub 必须同时使用官方 `/readonly` 端点，不能只依赖工具注解作为远端写保护。

当前传输约束：

- 实际支持 `STREAMABLE_HTTP`；DTO 虽保留 `SSE`，当前 HTTP facade 仍以 POST JSON-RPC 方式工作；`STDIO` 在工厂中明确拒绝。
- Endpoint 必须是绝对 `https://` URL，主机必须在 `agent.mcp.allowed-hosts` 中，不能带 user-info 或 fragment。
- 每次请求前重新解析 DNS；任一结果为回环、链路本地、站点私网、组播、CGNAT 或保留地址即拒绝。
- HTTP 重定向不跟随；Location 会再次校验，然后请求仍被拒绝，需管理员保存最终 URL。
- `timeoutMs` 同时约束连接/读取请求，范围 1000–60000 ms。
- `maxResultBytes` 在 HTTP 响应流读取阶段强制执行，范围 1024–262144 bytes；超限返回 `AGENT_TOOL_RESULT_TOO_LARGE`。
- Bearer 凭据用 `MODEL_CONFIG_MASTER_KEY` 经 `ModelSecretCipher` 加密入库；API 从不返回明文。
- MCP 内容会被标记为 `untrusted`，敏感字段被脱敏，疑似 Prompt Injection 产生 warning，不能成为系统指令。
- MCP 发现所需的网络调用不持有数据库事务；发现结果落库后才写审计。

当前没有本地 HTTP 开关，也不允许连接 `localhost`、Docker 私网地址或内网 IP。开发环境的 MCP Server 也必须通过可信 HTTPS 域名暴露；不要通过扩大 allowlist 绕过私网检查。

## 2. 配置清单

主要配置文件：

- `ai-collab-backend/src/main/resources/application.yml`：通用配置和 MCP host allowlist。
- `ai-collab-backend/src/main/resources/application-local.yml`：local profile，导入仓库根目录 `.env`。
- `.env`：本地 secret，仅保存在开发机，不提交。
- `ai-collab-deploy/docker-compose.yml`：PostgreSQL、Redis、MinIO；不包含后端、前端或 MCP Server。
- `ai-collab-frontend/vite.config.ts`：开发服务器把 `/api` 代理到 `http://localhost:8080`，前端不需要 API base URL 环境变量。

核心变量：

| Spring 属性 | 环境变量 | 必填 | 本地示例 | 说明 |
|---|---|---:|---|---|
| `spring.datasource.*` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | 是 | `ai_collab`, `ai_collab`, 随机强密码 | Compose 和 local profile 共用 |
| `spring.data.redis.password` | `REDIS_PASSWORD` | 是 | 随机强密码 | 不要写入日志 |
| `storage.minio.*` | `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_BUCKET` | 是 | `minioadmin-local`, 随机强密码, `ai-collab` | 生产应使用独立应用凭据 |
| `security.jwt.secret` | `JWT_SECRET` | 是 | 至少 32 字节随机值 | 轮换会使现有 Access Token 失效 |
| `demo.owner.*` | `DEMO_OWNER_USERNAME`, `DEMO_OWNER_PASSWORD` | local 必填 | `owner`, 随机强密码 | local 启动用管理员账号 |
| `model.config.master-key` | `MODEL_CONFIG_MASTER_KEY` | MCP 必填 | 至少 32 字节随机值 | 加密模型和 MCP 凭据；丢失后旧密文不可解密 |
| `agent.enabled` | `AGENT_ENABLED` | 是 | `true` | 未设置时继承 `CHAT_ENABLED` |
| `agent.worker-delay-ms` | `AGENT_WORKER_DELAY_MS` | 否 | `1000` | Worker 轮询间隔 |
| `agent.schedule-delay-ms` | `AGENT_SCHEDULE_DELAY_MS` | 否 | `30000` | 定时 Skill 扫描间隔 |
| `agent.mcp.allowed-hosts` | `AGENT_MCP_ALLOWED_HOSTS` | MCP 必填 | `api.githubcopilot.com,mcp.example.com` | 仅主机名，逗号分隔，不带 scheme/port/path |
| `chat.*` | `CHAT_ENABLED`, `CHAT_PROVIDER`, `CHAT_BASE_URL`, `CHAT_PATH`, `CHAT_API_KEY`, `CHAT_MODEL`, `CHAT_CONNECT_TIMEOUT`, `CHAT_READ_TIMEOUT`, `CHAT_TEMPERATURE`, `CHAT_MAX_OUTPUT_TOKENS`, `CHAT_JSON_MODE_ENABLED` | Agent 必填 | 依供应商 | Agent 模型必须支持 native tools |
| `planning.*` | 同名前缀 `PLANNING_*` | 仅规划功能 | 可继承 `CHAT_*` | 详见 application.yml |
| `embedding.*` | 同名前缀 `EMBEDDING_*` | 仅语义检索 | 默认关闭 | 详见 application.yml |
| `security.cors.allowed-origins` | `AUTH_ALLOWED_ORIGINS` | 生产必填 | `https://collab.example.com` | 多个 origin 按项目属性格式配置 |
| `security.refresh-token.cookie-secure` | `REFRESH_COOKIE_SECURE` | 生产必填 | `true` | HTTPS 生产环境必须为 true |

`timeoutMs` 和 `maxResultBytes` 是每条 MCP Connection 的数据库字段，由管理界面/API 设置，不是全局环境变量。

PowerShell 生成 secret 示例：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
```

`.env` 最小补充示例（值必须替换）：

```properties
AGENT_ENABLED=true
AGENT_MCP_ALLOWED_HOSTS=api.githubcopilot.com
MODEL_CONFIG_MASTER_KEY=replace-with-generated-random-secret
AUTH_ALLOWED_ORIGINS=http://localhost:5173
REFRESH_COOKIE_SECURE=false
```

## 3. 本地启动

前置：JDK 21、Docker Desktop、pnpm 11。仓库根目录执行：

```powershell
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --wait
Set-Location ai-collab-backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

另开终端：

```powershell
Set-Location E:\ai-collab\ai-collab-frontend
pnpm install --frozen-lockfile
pnpm dev
```

健康检查：

```powershell
curl.exe -f http://localhost:8080/actuator/health
curl.exe -f http://localhost:5173/
```

Flyway 在后端启动时先执行 V1–V30 并 validate；迁移失败时应用不会进入可用状态。

### 推荐的 GitHub 只读 MCP

最省运维的方式是 GitHub 官方 remote MCP，连接 URL 使用：

```text
https://api.githubcopilot.com/mcp/x/repos/readonly
```

将 `api.githubcopilot.com` 放入 `AGENT_MCP_ALLOWED_HOSTS`。创建 fine-grained PAT 时只选择测试仓库，Repository permissions 仅授予 `Contents: Read-only`；Metadata 的只读访问由 GitHub 自动提供。若要读 Issues 或 Pull requests，再单独增加相应只读权限和 toolset，不要给写权限。服务端的 `/readonly` 与 ai-collab 两级工具白名单要同时启用，形成纵深限制。GitHub 官方说明见：

- https://github.com/github/github-mcp-server/blob/main/docs/server-configuration.md
- https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/set-up-the-github-mcp-server

本地 `ghcr.io/github/github-mcp-server` 默认常用于 STDIO，而 ai-collab 禁用 STDIO；若使用其 HTTP 模式，必须再放到公开可解析的 HTTPS 反向代理后面。Token 只通过容器 secret/env 注入，不能写入镜像、源码、前端变量或 Compose 文件。

## 4. 浏览器管理 MCP

1. 用 system admin 登录 `http://localhost:5173`，进入“管理中心”。
2. 在“MCP 连接”卡片点“添加 MCP 连接”。输入代码 `github-readonly`、名称、上面的 HTTPS Endpoint、认证方式 `Bearer Token`、PAT、超时 `10000`、最大结果 `65536`，连接级工具白名单先填计划允许的精确工具名。
3. 保存后点“测试”，预期健康状态更新；Network 为 `POST /api/v1/admin/agent/mcp-connections/{id}/test`。
4. 点“发现”，预期出现 Schema Hash；Network 为 `POST .../{id}/discover`。
5. 检查发现结果与白名单后点“确认并启用”；Network 携带当前 `version`。
6. 复制 Connection ID，进入测试项目 → “项目协作 Agent” → “MCP 工具”。填 Connection ID 和更小的项目工具/资源白名单，保存。只有 OWNER 成功；普通成员应收到 403。
7. 修改 endpoint、凭据或 allowlist 使用“编辑/轮换凭据”。保存会自动停用，必须重新测试、发现、检查新 Hash、确认启用。
8. 当前没有删除系统连接的 API。用“停用”阻止使用；项目页“解除绑定”会删除项目绑定。文档和 UI 不把停用冒充物理删除。

## 5. curl 管理流程

以下为 PowerShell + `curl.exe`。登录要求合法 Origin：

```powershell
$base = 'http://localhost:8080/api/v1'
$login = curl.exe -sS -H 'Origin: http://localhost:5173' -H 'Content-Type: application/json' `
  -d '{"username":"owner","password":"REPLACE_ME"}' "$base/auth/login" | ConvertFrom-Json
$token = $login.data.accessToken
$headers = @('-H', "Authorization: Bearer $token", '-H', 'Content-Type: application/json')
```

创建连接（凭据只放请求体/secret manager，不保存此示例文件）：

```powershell
$body = @{
  code='github-readonly'; name='GitHub repositories (read only)'
  transport='STREAMABLE_HTTP'; endpoint='https://api.githubcopilot.com/mcp/x/repos/readonly'
  stdioCommand=$null; authType='BEARER'; credential='github_pat_REPLACE_ME'
  timeoutMs=10000; maxResultBytes=65536
  toolAllowlist=@('get_file_contents','search_code','get_repository_content')
  resourceAllowlist=@(); version=0
} | ConvertTo-Json -Depth 8 -Compress
$created = curl.exe -sS @headers -d $body "$base/admin/agent/mcp-connections" | ConvertFrom-Json
$connectionId = $created.data.id
$version = $created.data.version
```

测试、发现、读取 Hash、确认启用：

```powershell
curl.exe -sS -X POST -H "Authorization: Bearer $token" "$base/admin/agent/mcp-connections/$connectionId/test"
$discovered = curl.exe -sS -X POST -H "Authorization: Bearer $token" "$base/admin/agent/mcp-connections/$connectionId/discover" | ConvertFrom-Json
$discovered.data.schemaHash
$version = $discovered.data.version
curl.exe -sS -X POST -H "Authorization: Bearer $token" "$base/admin/agent/mcp-connections/$connectionId/enable?version=$version"
```

绑定项目：

```powershell
$projectId = 'REPLACE_PROJECT_UUID'
$binding = '{"enabled":true,"allowedTools":["get_file_contents","search_code"],"allowedResources":[],"configuration":{},"version":0}'
curl.exe -sS -X PUT @headers -d $binding "$base/projects/$projectId/agent/mcp-bindings/$connectionId"
curl.exe -sS -H "Authorization: Bearer $token" "$base/projects/$projectId/agent/mcp-bindings"
```

停用和解除绑定：

```powershell
$list = curl.exe -sS -H "Authorization: Bearer $token" "$base/admin/agent/mcp-connections" | ConvertFrom-Json
$version = ($list.data | Where-Object id -eq $connectionId).version
curl.exe -sS -X POST -H "Authorization: Bearer $token" "$base/admin/agent/mcp-connections/$connectionId/disable?version=$version"
curl.exe -sS -X DELETE -H "Authorization: Bearer $token" "$base/projects/$projectId/agent/mcp-bindings/$connectionId"
```

## 6. 服务器、Docker 与 Nginx

现有 Compose 只启动依赖。若把应用容器化，容器间必须使用服务名，例如 `jdbc:postgresql://postgres:5432/...`、`redis:6379`、`http://minio:9000`；容器内 `localhost` 只指当前容器。MCP Endpoint 仍必须是公开 HTTPS hostname，且该 hostname 写入 `AGENT_MCP_ALLOWED_HOSTS`。当前 SSRF 策略会拒绝解析到 Docker 私网的 hostname，这是设计行为。

Nginx 关键片段：

```nginx
location /api/ {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
    add_header X-Accel-Buffering no;
}
```

只开放 80/443；PostgreSQL 5432、Redis 6379、MinIO 9000/9001 和 MCP 内部端口不应对公网开放。生产 secret 用平台 Secret/只读挂载文件注入。部署顺序是备份数据库 → 启动新后端并让 Flyway 迁移 → 健康检查 → 切换流量 → 部署前端。V27–V30 是前向迁移，回滚应用前必须确认旧版本能容忍新表/列；不要回滚或修改已提交迁移。检查 `actuator/health`、应用启动日志中的 Flyway 状态及 MCP 管理页健康状态；日志不得包含 Token、Prompt、正文或完整 MCP 输出。

## 7. 浏览器完整验收顺序

所有场景都打开 DevTools → Network，保留日志；后台日志只检查错误码、runId、event sequence，不应出现正文/Token。

### 基础 Agent 与 SSE

1. OWNER/MEMBER 登录，进入一个自己是成员的项目 → “项目协作 Agent” → “协作对话”。确认页面上下文 chips 只显示当前项目路由上的合法 UUID。
2. 新建会话，发送只读目标，例如“检查项目状态并列出来源”。预期先出现 QUEUED/RUNNING，再出现计划和严格递增时间线事件，最终 SUCCEEDED 或明确业务错误。
3. Network 应看到 `GET .../events` 流；刷新页面后历史事件恢复。Offline 10 秒再 Online，预期从最后 sequence 重连，不重复时间线项，不产生并行重复流。

### 运行中取消

1. 启动持续足够久的只读 Run，在 RUNNING 点“停止运行”，可连续点击验证幂等。
2. UI 先提示取消请求，再由 SSE 进入 CANCELED；Network 为 `POST .../runs/{runId}/cancel` 和一个 `RUN_CANCELED` 事件。
3. 刷新后仍为 CANCELED，数据库 `agent_run` 不再回到 RUNNING，之后无工具副作用。若失败，按 runId 检查 Worker/Recovery 日志和 `cancellation_requested/version/status`。

### 取消与审批竞态

1. 让 Agent 提议一个任务、里程碑或项目记忆写入，等审批卡出现。
2. 先取消 Run，再点批准。预期批准失败并显示“Agent 运行已取消”，Network 返回稳定错误码 `AGENT_RUN_CANCELED`，任务/里程碑/记忆不变化，Approval 仍不产生 APPROVED 副作用。
3. 新建另一 Run，先批准并等待请求成功提交，再取消。已提交的业务写入不会被事后撤销；Run 的后续执行可被取消。这是明确的提交顺序语义。

### MCP

1. 按第 4 节以 system admin 完成创建、测试、发现、Hash 确认和启用。
2. OWNER 在项目 Agent → “MCP 工具”只绑定只读工具。MEMBER 可读取绑定但修改/解除应为 403。
3. 在 Agent 问仓库文件或代码。时间线应出现 MCP 工具调用；请求未加入连接级或项目级白名单的工具应不可见/被拒绝。
4. MCP Server 工具 Schema 改变后再次 Discover，Hash 应变化、连接停用或待重新确认，旧绑定不能绕过确认。

### 项目记忆、定时 Skill、权限隔离

1. 让 Agent 提议写项目记忆，批准后确认记忆可加载；取消 Run 后批准相同类型写入必须失败。
2. 在“定时运行”创建数分钟后触发的稳定 `skillCode` 任务，确认生成标准 Run 和事件。移除创建者项目成员资格后再次等待，预期不再执行；检查 schedule 状态和审计日志。
3. 用项目 A 成员把 URL 中 projectId/runId/approvalId 改为项目 B 资源，Run、事件、审批、记忆和 MCP binding 都不得泄露存在性。预期 `PROJECT_NOT_FOUND`/404 或契约规定的 403，不应返回项目 B 数据。

## 8. 故障定位

- 400 `AGENT_MCP_ENDPOINT_FORBIDDEN`：检查 HTTPS、精确 host allowlist、DNS 是否解析到私网、URL 是否含 user-info/fragment/重定向。
- 409 `AGENT_MCP_SCHEMA_CHANGED`：重新 Discover，核对精确工具名和两级白名单，再用最新 version 启用/绑定。
- 504 `AGENT_MCP_TIMEOUT`：检查 MCP 服务健康和 Connection `timeoutMs`，不要用任意放大超时掩盖故障。
- `AGENT_TOOL_RESULT_TOO_LARGE`：缩小查询结果或在允许范围内调整 `maxResultBytes`。
- 401/403：检查 JWT、system admin/OWNER 角色和 PAT；不要在日志或截图中粘贴 Token。
- SSE 无更新：检查 Nginx buffering/read timeout、浏览器是否保留单一连接、Network response 是否持续到达。

外部依赖只有真实模型凭据、GitHub 账号/PAT、可用的官方或自建 HTTPS MCP、生产域名/证书和部署平台 secret；这些不能由仓库测试替代。
