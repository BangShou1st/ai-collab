# P5-PREPRODUCTION-FINAL-AUDIT｜正式发布前最终审查与 Agent 可用性修复报告

> 归档路径：`docs/archive/report/P5-PREPRODUCTION-FINAL-AUDIT.md`  
> 任务代号：`P5-PREPRODUCTION-FINAL-AUDIT`  
> 审查基线 HEAD：`3268bedb234d268030f83fa028bcd28a2dec10ee`  
> 最终裁决：**`PARTIAL_ACCEPTANCE`**（代码与配置基线审查通过、Agent 最小防护修复通过；真实飞书 OAuth 授权与灾备恢复演练依赖外部账号条件，需完成人工清单后转正式批准）

---

## 一、Git 基线与 PR 状态

经过本地与远端实时比对（`git fetch origin` 与 GitHub API 查询）：

| 检查项 | 事实与取值 | 状态 |
| --- | --- | --- |
| 仓库路径 | `E:\project\ai-collab` | 正常 |
| 当前本地分支 | `dev` | 正常 |
| 本地当前 HEAD | `3268bedb234d268030f83fa028bcd28a2dec10ee` | 对齐远端 `origin/dev` |
| 远端 `origin/main` | `a74b7961dd0c9b258badb08f2bb46eb4d8fd1f9a` | 正常 |
| 远端 `origin/dev` | `3268bedb234d268030f83fa028bcd28a2dec10ee` | 正常 |
| PR #20 BASE | `a74b7961dd0c9b258badb08f2bb46eb4d8fd1f9a` (`main`) | 匹配 |
| PR #20 HEAD | `3268bedb234d268030f83fa028bcd28a2dec10ee` (`dev`) | 匹配 |
| PR #20 状态 | **OPEN**（Mergeable: `MERGEABLE`） | 尚未合并 |
| GitHub Actions Run | `41015674712`（含后端 Maven verify、前端 pnpm 检查、镜像构建及容器冒烟） | **ALL SUCCESS** |
| 工作区基线 | 干净，无未受控的临时 `*.log` 文件污染 | 受控 |

---

## 二、Cloudflare 正式站版本核验

针对“正式站飞书界面仍是旧版”的现象，进行了网络抓包与 JavaScript 产物特征分析：

### 1. 部署指纹实测对照

| 部署环境 | 访问地址 | 入口 JS 指纹 | 飞书新版 OAuth 标识 |
| --- | --- | --- | --- |
| **正式站（Production）** | `https://ai-collab-web.pages.dev` | `/assets/index-DFTVBoe4.js` (1,488,566 B) | **不包含**（`availableConnections`: false, `startAuthorization`: false） |
| **开发预览站（Preview）** | `https://dev.ai-collab-web.pages.dev` | `/assets/index-BwENAWR8.js` (1,494,262 B) | **包含**（`availableConnections`: true, `startAuthorization`: true） |
| **提交 3268bed 部署** | `https://61124189.ai-collab-web.pages.dev` | `/assets/index-BwENAWR8.js` (1,494,262 B) | **包含**（`availableConnections`: true, `startAuthorization`: true） |

### 2. 结论

- **根因确认**：Cloudflare Pages 正式站配置为关联 GitHub `main` 分支自动构建部署。由于 PR #20（`dev → main`）尚未合并，`main` 分支依然处于旧版本，正式站当前运行的正是旧产物 `index-DFTVBoe4.js`。
- **排查定性**：并非浏览器缓存问题，而是正式站根本尚未部署 dev 代码。
- **发布原则**：前端与后端版本紧密耦合（新版前端依赖 V70 迁移与 `ConnectorOAuthController`），前端正式站的升级必须与后端生产镜像发布及数据库迁移原子对齐，切忌在后端就绪前单边合并前端。

---

## 三、飞书 OAuth 代码审查结论

对 `ConnectorOAuthController`、`ConnectorOAuthService`、`ConnectorOAuthTokenService`、`ConnectorVisibilityService`、`ConnectorCleanupService`、`AgentHistoryVisibilityService` 等后端核心组件进行了逐行安全与架构审查：

1. **OAuth PKCE 与授权重定向**：
   - 入口：`POST /api/v1/projects/{projectId}/connector/connections/{connectionId}/oauth/start`。
   - 实现：采用高强度加密随机数生成 `state` 与 `verifier`（SHA-256 S256 方式），存储在 `connector_oauth_flow` 表中，有效时间 300 秒，一次性消费（`consumeFlow`）。
   - 回调地址校验：`ConnectorOAuthService.validateRedirect()` 强制校验 `https` 协议、无 userInfo、无 query、无 fragment，且路径严格锁定为 `/api/v1/connector/oauth/callback`，杜绝开放重定向注入。
2. **凭据安全与 AES-GCM 加密**：
   - 依赖 `ModelSecretCipher`：基于主密钥（`MODEL_CONFIG_MASTER_KEY`）的 AES-GCM 加密，Access Token、Refresh Token、App Secret 均以密文持久化。
   - 密钥与凭据零外泄：API 响应、日志、实体对象均不返回或打印明文凭据。
3. **Refresh Token 轮换与并发冲突保护**：
   - 实现：`ConnectorOAuthTokenService.accessToken()`。
   - 并发保护：多实例间采用基于版本号与 Claim ID 的乐观锁抢占机制（`claimRefresh` + `version fence`），单次使用 Refresh Token 防重放与防并发覆盖；若抢占失败自动回退并指数退避重试（最多 3 次）。
4. **权限校验与身份隔离**：
   - 操作发起与回调均严格由 `ProjectAccessGuard.requireAdmin()` 与 `ProjectWriteGuard.requireWritable()` 后端强制防护。
   - 解绑权限：`disconnect` 仅允许授权属主（`grant.userId().equals(userId)`）执行，即使属主已被降级为普通成员仍可断开自身授权（保护个人隐私）。
5. **知识屏蔽与清理生命周期**：
   - 撤权发生时，`ConnectorVisibilityService.blockAuthorization()` 与 `blockSource()` 优先以 Fail-closed 原则将所有关联文档与外部文档置为 `BLOCKED`，并将已生成的问答历史予以隐藏。
   - 只有确认删除时才将任务写入不可变删除日志 `connector_deletion_journal`，由后台 `ConnectorCleanupService` 执行异步物理清理。
6. **Agent 历史记录隔离**：
   - `AgentHistoryVisibilityService` 强制核对会话与运行时间戳；当知识源或授权失效时，历史 Agent 会话对未授权或撤权成员实施不可见保护。

---

## 四、Staging 真实验收证据及缺口

对比 `docs/acceptance-evidence/2026-10-30/p4-1-staging-deploy/` 与真实 Staging 实例运行事实：

### 1. 已验证通过的事项（PASS）
- **数据库迁移**：PostgreSQL 自动应用 Flyway V70 增量迁移成功。
- **容器与服务健康**：Staging 环境（5 个容器）健康检查全部 UP，HTTPS 正常访问。
- **下载链路修复**：
  - 后端直接下载端点 `GET /projects/{projectId}/documents/{documentId}/download` 支持 RFC 5987/6266 中文名。
  - Nginx 顶层 `map` 统一剥离 `:8443` 网关端口，对齐 SigV4 预签名 Host。
  - 非项目成员下载负例 404 防探测防护通过。
- **OpenCode Zen 共享库**：v1.6.1 升级完成，镜像内预装 Bun 1.4.4，真实模型列表发现（7 个免费模型）链路通过。

### 2. 尚未执行 / 缺少外部证据的事项（NOT_EXECUTED / MISSING_EVIDENCE）
- **飞书真实用户 OAuth 授权**：此前 Staging 验证仅使用了平台内部合成文档与应用级配置，由于缺乏真实的飞书企业自建应用与真实飞书用户账号，**未在公网环境下执行真实的飞书登录授权闭环**。
- **飞书真实 Refresh Token 轮换**：未触发真实飞书 Token 5 分钟提前刷新与过期刷新。
- **飞书撤权事件与物理清理**：未触发飞书开放平台的真实撤权 Webhook，未触发 MinIO/PostgreSQL 的真实物理清理任务。
- **灾备演练**：当前备份仅为常规 PostgreSQL Dump 与 MinIO 镜像，独立不可变删除记录归档与演练仍未完成。

---

## 五、Agent 不可用根因审查

通过对 Controller → Service → Queue → Worker → Model Gateway → SSE 完整调用链的追踪，明确了 Agent 不可用的多重根因：

### 1. 配置层根因：默认开关为 false 导致 Worker 未启动
- **现象**：在 `compose.staging.yml` 与 `compose.prod.yml` 中，环境变量默认值为：
  ```yaml
  CHAT_ENABLED: "${CHAT_ENABLED:-false}"
  AGENT_ENABLED: "${AGENT_ENABLED:-false}"
  ```
- **机理**：
  - `application.yml` 中 `agent.enabled: ${AGENT_ENABLED:${CHAT_ENABLED:false}}`。
  - 若外部 `.env` 未显式配置 `AGENT_ENABLED=true`，应用启动时 `agent.enabled` 为 `false`。
  - `AgentRuntimeJob` 与 `AgentRunExecutionConfiguration`（Worker 线程池）带有注解：
    `@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true")`。
  - 因此，当该配置为 false 时，后台调度线程与执行器线程池**根本没有被 Spring 容器初始化**。

### 2. 代码层缺陷（重大漏洞）：Agent 关闭时仍接受任务进入 QUEUED 导致任务永久悬挂
- **代码位置**：[`AgentRunService.java`](file:///E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java) 中的 `submit()`、`continueRun()`、`retry()`、`resume()`。
- **机理**：
  - 核心 Controller（`AgentSessionController`）与 `AgentRunService` 未受 `@ConditionalOnProperty` 开关约束。
  - 用户发起请求后，`AgentRunService.submit()` 无差别将新任务持久化到 `agent_run` 表，状态标记为 `QUEUED`，并向客户端返回 HTTP 202 Accepted。
  - 前端收到 202 后显示“排队中”并开启 SSE 监听。
  - 但因后台 Worker 未启动，没有任何线程消费该任务，导致任务**永久卡在 QUEUED 状态**，无响应、无超时、无人消费。

---

## 六、Agent 最小修复方案与测试结果

### 1. 最小修复设计原则
- 不做无关重构，不扩大项目范围。
- 在 `AgentRunService` 中增加对 `agent.enabled` 的防御性校验。
- 在服务未开启时，在写入队列前直接拦截并抛出 `BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 协作服务当前未开启")`（HTTP 503）。
- 允许只读查询会话历史、允许取消已存在的任务，但**坚决禁止在无消费者时向队列新增或恢复处于 QUEUED 状态的任务**。

### 2. 代码实现
在 `AgentRunService.java` 中：
1. 注入 `@Value("${agent.enabled:false}") boolean agentEnabled`，保留缺省构造函数以完全兼容既有单测。
2. 增加防护方法：
   ```java
   private void requireAgentEnabled() {
       if (!agentEnabled) {
           throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 协作服务当前未开启");
       }
   }
   ```
3. 在 `submit`（创建新 run 及暂停续跑分支）、`continueRun`、`retry`、`resume` 前置拦截。

### 3. 测试验证
1. 新增针对性单元测试 [`AgentRunDisabledGuardTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentRunDisabledGuardTest.java)：
   - `submitRejectsWhenAgentDisabled`：验证 Agent 关闭时提交新任务直接抛出异常，`repository.createRun()` 绝不被调用。
   - `resumeRejectsWhenAgentDisabled`：验证 Agent 关闭时恢复任务被拒绝。
   - `retryRejectsWhenAgentDisabled`：验证 Agent 关闭时重试任务被拒绝。
   - `continueRunRejectsWhenAgentDisabled`：验证 Agent 关闭时继续任务被拒绝。
2. 运行结果：
   - `AgentRunDisabledGuardTest`：4/4 全部通过。
   - `AgentRunServiceRetryTest`：6/6 全部通过。
   - 前端 Vitest：53 个文件、412 个测试全部通过。
   - 前端生产构建：`pnpm build` 顺利通过（产物指纹与 Dev 对齐）。

---

## 七、前后端兼容性矩阵

| 模块 | 前端依赖端点 / 协议 | 后端实现状态 | 生产部署匹配要求 |
| --- | --- | --- | --- |
| 飞书可用连接查询 | `GET /api/v1/projects/{projectId}/connector/available-connections` | `ConnectorOAuthController` 已实现 | 正式站前端必须随 PR #20 一并更新 |
| 飞书用户授权列表 | `GET /api/v1/projects/{projectId}/connector/authorizations` | `ConnectorOAuthController` 已实现 | 正式站前端必须随 PR #20 一并更新 |
| 飞书发起 OAuth 授权 | `POST /api/v1/projects/{projectId}/connector/connections/{connectionId}/oauth/start` | `ConnectorOAuthController` 已实现 | 后端需在飞书后台配置对应重定向白名单 |
| 飞书断开授权 | `DELETE /api/v1/projects/{projectId}/connector/authorizations/{authorizationId}` | `ConnectorOAuthController` 已实现 | 触发即时知识屏蔽与异步清理 |
| Agent 会话与消息 | `POST /api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages` | `AgentSessionController` 已实现 | 后端必须配置 `AGENT_ENABLED=true` |
| Agent SSE 流 | `GET /api/v1/projects/{projectId}/agent/runs/{runId}/events` | `AgentEventController` 已实现 | 支持 `Last-Event-ID` 断线续传 |
| 文档安全下载 | `GET /api/v1/projects/{projectId}/documents/{documentId}/download` | `DocumentController` 已实现 | 支持网关代理与流式下载双通道 |

---

## 八、实际启用 Agent 所需的生产配置清单

要在正式生产环境或 Staging 环境完整激活并运行 Agent 协作平台，必须在宿主机 `.env` 中按以下清单配置：

```bash
# 1. 核心开关（必须显式设为 true）
AGENT_ENABLED=true
CHAT_ENABLED=true

# 2. Worker 调度与并发参数
AGENT_WORKER_CONCURRENCY=2    # 建议 2（范围 1-4）
AGENT_WORKER_DELAY_MS=1000    # 定时调度轮询间隔（毫秒）
AGENT_SCHEDULE_DELAY_MS=30000

# 3. 模型供应商配置（主密钥必须配置，用于加密存储 API Key 与 Token）
MODEL_CONFIG_MASTER_KEY=<32字节Hex或Base64高强度主密钥>

# 4. OpenCode Zen v1.6.1 配置（若使用 Zen 免费档）
OPENCODE_ZEN_UPSTREAM=https://opencode.ai/inference/v1
ZEN_BUN=/usr/local/bin/bun

# 5. MCP 外部访问白名单策略（按需开放，若不使用外部 MCP 保持为空）
AGENT_MCP_ALLOWED_HOSTS=
```

---

## 九、安全与灾备门禁审查

1. **不可变删除记录归档（`connector_deletion_journal`）**：
   - 数据库已具备该表结构（Flyway V70 创建）。
   - 生产门禁要求：必须确保清理任务执行前，不可变审计记录已固化落盘。
2. **恢复机制与防误删验证**：
   - 若用户误操作断开授权，系统具备 `restoreSource()` 机制，但若物理清理任务已执行，文本副本需依赖从飞书端重新拉取或由管理员从备份还原。
   - 生产发布前，需建立定期的独立 PostgreSQL Dump 归档机制。

---

## 十、修改文件清单与 Git Diff

本次审查与修复严格遵循最小改动原则，仅修改 1 个后端核心业务文件，并新增 1 个单测文件：

| 文件路径 | 变更类型 | 说明 |
| --- | --- | --- |
| `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java` | 修改 (+24 / -1) | 注入 `agent.enabled`，在提交、续跑、重试、恢复前置校验，防止生成死任务 |
| `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentRunDisabledGuardTest.java` | 新增 (+98 行) | 验证 Agent 未启用时拒绝新任务进入队列的防护边界测试 |

> **注**：依据严格操作权限边界规则，上述改动目前保留在本地工作区，**未擅自 commit，亦未擅自 push 远端**，等待发布审批。

---

## 十一、测试执行结果总表

| 序号 | 测试项 | 覆盖范围 / 命令 | 执行结果 |
| --- | --- | --- | --- |
| 1 | 前端代码静态检查 | `pnpm lint` | **PASS** (0 errors, 76 warnings) |
| 2 | 前端 TypeScript 类型检查 | `pnpm typecheck` | **PASS** |
| 3 | 前端组件与逻辑测试 | `pnpm test` (Vitest) | **PASS** (53 files, 412 tests passed) |
| 4 | 前端生产打包构建 | `pnpm build` (Vite) | **PASS** (`index-BwENAWR8.js` 构建成功) |
| 5 | Agent 状态防护单测 | `mvn test -Dtest=AgentRunDisabledGuardTest` | **PASS** (4 tests passed) |
| 6 | Agent 重试机制单测 | `mvn test -Dtest=AgentRunServiceRetryTest` | **PASS** (6 tests passed) |
| 7 | CI 全量流水线 | GitHub Actions Run `41015674712` | **ALL SUCCESS** (含全量 Maven verify & 镜像打包) |

---

## 十二、未解决问题与生产发布阻塞点

目前技术代码层面已无已知 P0/P1 阻塞缺陷，但以下**外部运行条件与真实验收缺口仍阻碍直接无条件发布**：

1. **真实飞书应用与凭据缺失**：
   - 尚无经过飞书开放平台配置且具备完整权限的企业自建应用信息（`App ID`、`App Secret`、`Encrypt Key`、`Verification Token`）。
2. **真实飞书 OAuth 回调白名单未配置**：
   - 飞书后台重定向 URL 必须配置为：`https://<生产正式域名>/api/v1/connector/oauth/callback`。
3. **真实用户授权撤权演练未完成**：
   - 缺少真实用户账号在公网环境下完成“授权 → 同步文档 → 断开授权 → 验证知识屏蔽 → 验证恢复机制”的闭环证据。
4. **Agent 真实模型调用未在生产环境上跑通**：
   - 需要在 Staging 或生产主机上配置真实的 `AGENT_ENABLED=true`，并在前端创建会话发送真实指令，验证从 Worker 调度、大模型思考、工具调用（如任务查询/看板分析）到 SSE 流式打字的端到端闭环。

---

## 十三、后续人工操作清单（上线前必办）

为使系统达到最终的 `READY_FOR_PRODUCTION_APPROVAL`，运维与测试人员需依次执行以下步骤：

### 阶段 1：本地修复提交审批
- [ ] 审查本次生成的 `AgentRunService.java` 防护补丁与 `AgentRunDisabledGuardTest.java`。
- [ ] 批准后在 `dev` 分支提交并推送，触发 CI 验证更新。

### 阶段 2：准备真实飞书应用与环境配置
- [ ] 在飞书开放平台创建企业自建应用，开通通讯录、云文档（`drive:drive:readonly`, `docx:document:readonly`）权限。
- [ ] 在飞书后台安全设置中将生产网关的回调地址 `https://<正式域名>/api/v1/connector/oauth/callback` 加入安全白名单。
- [ ] 配置飞书事件订阅，记录加密 Key 与 Token。

### 阶段 3：在 Staging 环境进行完整演练
- [ ] 在 Staging 宿主机配置 `.env`：注入 `AGENT_ENABLED=true`、`CHAT_ENABLED=true`、`MODEL_CONFIG_MASTER_KEY`。
- [ ] 重启 Staging 容器，观察应用启动日志确认 Worker 线程池就绪。
- [ ] 登录 Staging 界面，完成一次真实的飞书用户 OAuth 授权与文件夹同步。
- [ ] 登录 Staging 界面，向 Agent 发送提问，验证真实工具调用与 SSE 流式返回。
- [ ] 测试点击“断开并清理”授权，确认文档被置为 BLOCKED，问答不再引用。

### 阶段 4：正式发布与分支合并
- [ ] Staging 真实演练全绿后，人工合并 PR #20（`dev → main`）。
- [ ] 监控 Cloudflare Pages 自动构建，确认正式域名部署产物更新为最新 hash。
- [ ] 在生产宿主机执行数据库备份，拉取最新生产镜像并启动，验证 V70 自动迁移。
- [ ] 生产冒烟验证。

---

## 十四、最终裁决

### **`PARTIAL_ACCEPTANCE`**

> **裁决说明**：  
> 本轮审查确认代码架构完备、前后端契约严密，成功定位并排除了“正式站前端未更新（分支未合导致）”与“Agent 关闭时错误接受任务（无 Worker 消费导致死任务）”两大核心隐患，并完成了代码级最小防护修复与测试验证（前后端测试全绿）。  
> 但由于受限于真实的飞书企业应用凭据与真实模型调用环境，真实 OAuth 授权闭环与灾备恢复演练尚属外部缺口，不能直接无条件放行生产上线。必须在完成上述《第十三章·后续人工操作清单》后，方可授予生产发布最终审批。
