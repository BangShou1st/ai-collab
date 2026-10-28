# 环境要求（environment-requirements）

> 结论按证据状态标注：`VERIFIED` / `DOCUMENTED` / `ENVIRONMENT_DEPENDENT` / `NOT_VERIFIED`。
> 凡仓库无法确定的目标环境事实一律标 `ENVIRONMENT_DEPENDENT` 并列入 [open-questions.md](open-questions.md)，不得虚构。

## 1. 主机与运行时

| 项 | 要求 | 状态 |
| --- | --- | --- |
| 操作系统 | Linux（自有服务器或云主机均可；发行版不限，需能装 Docker） | `ENVIRONMENT_DEPENDENT`（目标主机未提供） |
| 容器运行时 | Docker Engine + Compose v2（`docker compose` 子命令可用） | `DOCUMENTED`（release-runbook §0） |
| 时间同步 | NTP 就绪（JWT/限流窗口/审计时间戳依赖正确时钟） | `DOCUMENTED` |
| 磁盘 | 数据卷分区剩余 ≥ 现网数据体积 ×2（发布前检查项） | `DOCUMENTED`（release-runbook §1）；首署规模 `ENVIRONMENT_DEPENDENT` |
| 内存 | 经验基线：4 服务（pg/redis/minio/app）+ 反代，建议 ≥4 GB；app 由 `-XX:MaxRAMPercentage=75.0` 按容器限额自适应（`ai-collab-backend/Dockerfile:32`） | `NOT_VERIFIED`（无负载测试证据，R-11；发布说明不承诺并发容量） |

## 2. 端口规划

| 端口 | 用途 | 生产建议 | 状态 |
| --- | --- | --- | --- |
| 443 (HTTPS) | 反向代理对外入口 | 对公网开放 | `ENVIRONMENT_DEPENDENT` |
| 80 (HTTP) | 仅用于跳转 HTTPS（可选） | 对公网开放或关闭 | `ENVIRONMENT_DEPENDENT` |
| 8080 | app（Spring Boot） | **不得直接暴露公网**；反代转发；compose 现发布到宿主 `0.0.0.0:8080`，生产建议改绑 `127.0.0.1:8080` | `DOCUMENTED`（release-runbook §0"不允许绕过代理直接暴露 8080"） |
| 5432 / 6379 / 9000 / 9001 | postgres / redis / minio API / minio 控制台 | compose 现均发布到宿主 `0.0.0.0`；生产建议改绑 `127.0.0.1` 或删除端口映射（仅容器网络内访问） | `VERIFIED`（docker-compose.yml:10-11,32-33,61-63,120-121）+ 硬化建议 |
| 11434 | Ollama（若启用本地 embedding，经 `host.docker.internal`） | 仅内网 | `ENVIRONMENT_DEPENDENT` |

## 3. 出网白名单（应用主机 → 外部）

后端出网一律直连，**无任何代理配置点**（`VERIFIED`：`HttpOpenCodeZenTransport.java:47-48` 显式 `NO_PROXY`；`ProviderPresetRegistry` 预设策略 `directNoProxy=true`）。白名单：

| 目标 | 用途 | 说明 |
| --- | --- | --- |
| `opencode.ai` | OpenCode Zen 模型网关 | upstream 固定 `https://opencode.ai/inference/v1`，可用 `OPENCODE_ZEN_UPSTREAM` 覆盖但必须命中代码白名单（application.yml:83-88） |
| `ai-gateway.kilo.ai` | Kilo Gateway 免费模型 | `kilo.gateway.free-only` 生产必须保持 true；匿名、不配 Key（docs/deployment/kilo-gateway.md） |
| `generativelanguage.googleapis.com` | Gemini | 经用户/管理员 ModelConfiguration 配置 base URL，非硬编码 |
| 用户配置的 OpenAI 兼容 / Anthropic base | Chat/Planning/Embedding | 由 ModelConfiguration 决定，部署时按实际清单放行 |
| Ollama 目标（默认 `host.docker.internal:11434`） | 本地 embedding（可选） | 受 `EMBEDDING_LOCAL_ALLOWED_TARGETS` 白名单约束 |
| MCP 主机 | Agent MCP 工具 | 仅只读白名单 `AGENT_MCP_ALLOWED_HOSTS` |
| `jitpack.io`、Maven Central | **仅构建期**（Docker build 阶段） | 若生产主机不构建镜像（改用导入镜像），可不放行 |

**关键约束**：若生产环境必须经代理出网（如海外 Provider 需代理），**当前代码不支持**（R-24 已登记）。部署环境必须提供直连出网，或在部署前新增代理配置能力——二者取其一，需用户决策。

## 4. 域名、DNS 与 TLS 证书

| 项 | 状态 |
| --- | --- |
| 是否已有域名 | `ENVIRONMENT_DEPENDENT`（open-questions Q1） |
| DNS 解析 | 域名 → 服务器公网 IP，`ENVIRONMENT_DEPENDENT` |
| TLS 证书来源（Let's Encrypt / 商业证书 / 云 LB） | `ENVIRONMENT_DEPENDENT`（open-questions Q2） |
| 证书部署位置 | 反向代理层（见 production-architecture.md），app 容器保持 HTTP |

## 5. 镜像来源

无公共镜像仓库（CI 只本地构建冒烟、不推送，`VERIFIED`：ci.yml 无 push 步骤）。两种方式二选一：

1. **生产主机本地构建**：主机可出网（Maven Central + JitPack），从 Git 检出目标 SHA 后 `docker build`（release-runbook §4，`DOCUMENTED`）。
2. **自建私有 registry 或离线导入**：`docker save`/`docker load` 传输 CI 构建的同 SHA 镜像，`ENVIRONMENT_DEPENDENT`（open-questions Q3）。

## 6. 备份与异地副本

| 项 | 状态 |
| --- | --- |
| 备份脚本与保留策略 | `DOCUMENTED`（`ai-collab-deploy/backup/` 7 脚本，`BACKUP_RETENTION_DAYS=14` 默认） |
| 备份产物位置 | 默认 `<仓库根>/backups/`（宿主机本地盘）；生产位置 `ENVIRONMENT_DEPENDENT`（open-questions Q4） |
| 定时调度 | **无 cron/systemd timer 配置**（`VERIFIED`：仓库无任何调度文件），需部署环境提供 cron，`ENVIRONMENT_DEPENDENT` |
| 异地副本 | 未实现、未演练（R-01 边界：同机 Docker 演练 ≠ 异机异地恢复），`ENVIRONMENT_DEPENDENT`（open-questions Q4） |
| 密钥备份（MODEL_CONFIG_MASTER_KEY / JWT_SECRET 加密归档） | 仅在 R-01 整改计划中提及，脚本集未实现，`NOT_VERIFIED` |
