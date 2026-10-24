# 密钥与配置（secrets-and-configuration）

> 红线：严禁提交生产密钥、真实 API Key 或 `.env` 到仓库；密钥仅经环境变量/密管下发注入；日志与异常不得包含密钥（已有验证：日志 grep 真实 `POSTGRES_PASSWORD`/`JWT_SECRET` 计数 0，verification-results.md R-02）。

## 1. 密钥清单

| 密钥 | 用途 | 生成/取得 | 注入位置 | 轮换 | 泄露应急 |
| --- | --- | --- | --- | --- | --- |
| `POSTGRES_PASSWORD` | 数据库口令 | `openssl rand -base64 32` 类强随机 | 生产 `.env` → compose postgres + app | 需同步改容器与应用并重启；数据不依赖该密钥，可安全轮换 | 改密 + 排查访问日志 |
| `REDIS_PASSWORD` | Redis 口令（限流/锁） | 同上 | `.env` → compose redis 启动参数 + app | 同上，重启即生效 | 同上 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO 管理凭据 | 同上 | `.env` → compose minio + app（`MINIO_ACCESS_KEY/SECRET_KEY`） | 换凭据需同步 app 配置 | 同上 |
| `JWT_SECRET` | Access Token 签名（≥32 字节） | `openssl rand -base64 48` | `.env` → compose app → `security.jwt.secret`（application.yml:67，空值启动即失败） | 轮换 = 全部已签发 token 失效 → 全员重新登录；可接受 | 立即轮换，强制全员下线 |
| `MODEL_CONFIG_MASTER_KEY` | 用户私有模型 API Key 的 AES-256-GCM 加密主密钥（≥16 字符，占位符黑名单硬校验） | 强随机；**一旦使用不可更换**——更换后已加密数据不可解（ModelSecretCipher，`VERIFIED`） | `.env` → compose app → `model.config.master-key`（application.yml:113-115） | **不可轮换**；必须离线加密归档备份（脚本集尚未实现该归档，`NOT_VERIFIED`） | 无法轮换止损：需评估已加密 Key 重新录入；立即排查泄露面 |
| `CHAT_API_KEY` / `EMBEDDING_API_KEY` / `PLANNING_API_KEY` | 全局模型 Provider Key | 从 Provider 控制台取得 | `.env` → **注意 compose 透传缺口（见 §3）** | Provider 侧吊销重发 | Provider 控制台吊销 + 换新 |
| 用户私有模型 Key | 每用户/项目模型配置 | 用户在界面录入 | 加密入库（ModelSecretCipher） | 用户自行更换 | 库泄露时按 master key 泄露处置 |
| TLS 证书私钥 | HTTPS | CA 签发 | 仅反向代理层，app 不接触 | 按证书周期 | 重签 + 重载反代 |

## 2. 生产 `.env` 基线（以根 `.env.example` 为基底的差异）

生产 `.env` 只存在于目标主机，来源为 `.env.example` 复制后逐项替换。必改项：

```
POSTGRES_PASSWORD=<强随机>          REDIS_PASSWORD=<强随机>
MINIO_ROOT_PASSWORD=<强随机>        JWT_SECRET=<≥32 字节强随机>
MODEL_CONFIG_MASTER_KEY=<强随机，生成后归档，永不更换>
REFRESH_COOKIE_SECURE=true          REFRESH_COOKIE_ALLOW_INSECURE=false
AUTH_ALLOWED_ORIGINS=https://<生产域名>        # 必须显式精确 Origin，禁通配符（CorsProperties 校验）
AUTH_PUBLIC_REGISTRATION_ENABLED=<按运营策略，默认建议 false>
DEMO_OWNER_USERNAME / DEMO_OWNER_PASSWORD=   # 留空：DEMO 初始管理员仅 local profile 生效
CHAT_ENABLED=<...> CHAT_PROVIDER=<...> CHAT_BASE_URL=<...> CHAT_API_KEY=<...> CHAT_MODEL=<...>
AGENT_ENABLED=<...> AGENT_MCP_ALLOWED_HOSTS=<只读白名单，默认空>
```

配置静态校验：`docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml config -q`（release-runbook §2）。

**现状缺口（`VERIFIED`）**：`ai-collab-deploy/.env` 目前仅 7 个基础键，缺 `JWT_SECRET`、`MODEL_CONFIG_MASTER_KEY`、`AUTH_ALLOWED_ORIGINS` 等 12 个变量；仓库根 `.env` 为本地开发配置（含 DEMO_OWNER），**不可复制到生产**。

## 3. compose 透传缺口（本轮新发现，`VERIFIED`）

compose app 服务 `environment` 为**显式枚举**（docker-compose.yml:90-119），`.env` 中未列入该块的变量**不会进入容器**。当前缺失且 application.yml 会读取的变量：

| 缺失变量组 | 影响 | 严重度 |
| --- | --- | --- |
| `EMBEDDING_ENABLED / EMBEDDING_PROVIDER / EMBEDDING_BASE_URL / EMBEDDING_API_KEY / EMBEDDING_MODEL / EMBEDDING_DIMENSIONS / EMBEDDING_BATCH_SIZE` | **RAG 知识库在生产 compose 部署下无法启用**（向量表维度约束见 AGENTS.md §5，维度必须与模型实际输出一致） | 部署阻断（若启用知识库） |
| `PLANNING_PROVIDER / PLANNING_BASE_URL / PLANNING_API_KEY / PLANNING_MODEL` | 任务规划无法使用独立于 Chat 的模型配置 | 功能受限 |
| `CHAT_TEMPERATURE / CHAT_MAX_OUTPUT_TOKENS` | 采默认值，可用但不可调 | 低 |
| `SERVER_FORWARD_HEADERS_STRATEGY`（N-02 处置载体） | 见 tls-and-networking.md | 部署日决策 |

**最小修复建议**（不改逻辑，仅补 environment 条目）：在 app `environment` 增加上述 `EMBEDDING_*`、`PLANNING_*` 条目（形如 `EMBEDDING_ENABLED: ${EMBEDDING_ENABLED:-false}`）；`SERVER_FORWARD_HEADERS_STRATEGY` 是否加入待 N-02 决策。**本轮不擅自修改**，列入部署前门禁 G-1，需在 PR #18 合并前或合并后单独提交。

## 4. 注入与分发方式

- 推荐顺序：密管系统/密码管理器下发 → 目标主机 `.env`（`chmod 600`，属主限定运维账号）→ compose 注入。无密管时用离线渠道人工传递，禁止 IM 明文。
- 参数文件方式启动（dev.ps1 的做法）：密钥不进命令行/进程状态（`VERIFIED`，dev.ps1 头注释）。
- 日志脱敏：RequestIdMdcFilter + 敏感 Mapper 强制 INFO 基线已内置；运维侧 `docker logs app | grep -iE 'password|apikey|secret'` 作为发布后检查项（release-runbook §9）。

## 5. 明确禁止

- 禁止把生产 `.env`、真实 Key 提交进任何分支或受忽略文本文件（AGENTS.md §9 零容忍）。
- 禁止在文档、工单、聊天中传输明文密钥；`[部署环境补充]` 占位符一律不代表真实值。
- 禁止为通过启动校验而把 `MODEL_CONFIG_MASTER_KEY` 设为占位符黑名单值（`local-model-config-key-change-me` 等，会被拒绝）。
