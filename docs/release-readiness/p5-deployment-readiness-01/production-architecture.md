# 生产架构（production-architecture）

> 推荐拓扑基于仓库既有实现（compose 4 服务 + 反代），不引入新组件形态。所有"未实现/未确认"项均显式标注。

## 1. 推荐拓扑（单机同源部署）

```
                    ┌─────────────────────────────────────────────┐
 用户浏览器 ──HTTPS──▶ 反向代理（Nginx / Caddy，TLS 终止）            │
                    │   /            → 前端静态文件 dist/           │
                    │   /api/v1/...  → http://127.0.0.1:8080       │
                    └─────────────────────────────────────────────┘
                                     │ HTTP（仅回环）
        ┌────────────────────────────┼──────────────────────────────┐
        │  Docker Compose（ai-collab-deploy/docker-compose.yml）     │
        │                                                            │
        │  app (ai-collab-backend, 8080)                             │
        │    ├─ depends_on healthy ─▶ postgres (pgvector/pg17:5432)  │
        │    │                          卷 ai_collab_postgres_data   │
        │    ├─ depends_on healthy ─▶ redis (redis:7-alpine:6379)    │
        │    │                          卷 ai_collab_redis_data      │
        │    └─ depends_on started ─▶ minio (chainguard, 9000/9001)  │
        │                               卷 ai_collab_minio_data      │
        └────────────────────────────────────────────────────────────┘
                              出网（直连，无代理）：opencode.ai /
                              ai-gateway.kilo.ai / Gemini 等
```

## 2. 组件职责与依据

| 组件 | 职责 | 依据（状态） |
| --- | --- | --- |
| 反向代理 | TLS 终止；托管前端静态产物；`/api/v1` 反代到 app；设置 `X-Forwarded-*` 头（见 tls-and-networking.md） | 形态 `DOCUMENTED`（release-runbook §0）；**具体实现（nginx conf / Caddyfile）未提供，`ENVIRONMENT_DEPENDENT`** |
| 前端 | `pnpm build` 产出 `dist/`；生产请求走相对路径 `/api/v1`（同源），由反代转发 | `VERIFIED`：`.env.production` 仅含 `VITE_API_BASE_URL=/api/v1`；`http-client.ts:11` 默认 `/api/v1`；vite 无 `base` 配置 |
| app（后端） | Spring Boot 默认 profile，Flyway 启动迁移，Actuator 健康探针 | `VERIFIED`（Dockerfile 多阶段 + 非 root + HEALTHCHECK；compose app 服务） |
| postgres | PostgreSQL 17 + pgvector，数据卷 `ai_collab_postgres_data` | `VERIFIED` |
| redis | 限流/缓存/分布式锁，AOF 持久化，需密码 | `VERIFIED`；故障可降级（重启容器即可，无需恢复——rollback-runbook §4） |
| minio | 文档原文件对象存储，镜像按 digest 固定（Chainguard） | `VERIFIED`（docker-compose.yml:53）；R-13 旧数据卷升级兼容未验证 |

**明确不存在的组件（避免误假设）**：
- 无前端容器、无 nginx 镜像/配置文件（全仓库无 nginx* 文件）、后端 `static/` 目录为空——前端托管**完全依赖外部反代**，这是部署前必须落实的一项（open-questions Q5）。
- 无镜像 registry：CI 构建 `ai-collab-backend:rc-<sha>` 仅存在于 runner 本地，不推送。
- 无 Prometheus/micrometer 依赖：指标端点未启用（observability-and-alerting.md）。

## 3. 端口矩阵（生产形态）

| 来源 → 目标 | 协议 | 生产硬化建议 |
| --- | --- | --- |
| 公网 → 反代 443 | HTTPS | 唯一公网入口 |
| 反代 → app 8080 | HTTP | compose 端口发布建议改 `"127.0.0.1:8080:8080"` |
| app → postgres/redis/minio | 容器网络内 | 5432/6379/9000/9001 的宿主发布建议移除或改绑 127.0.0.1（compose 现状为 `0.0.0.0`，`VERIFIED`） |
| 运维 → MinIO 控制台 9001 | — | 仅经 SSH 隧道或内网访问，不对公网开放 |

## 4. 镜像与版本对齐

- 后端镜像 tag：`rc-<sha 前 12 位>`，必须带 `org.opencontainers.image.revision` label 并与发布 commit 核对（release-runbook §4，`DOCUMENTED`；该 label 逻辑由 ci.yml image job 与 Dockerfile 之外的构建命令保证）。
- compose `app.image` 当前为 `ai-collab-backend:local`（`VERIFIED`：docker-compose.yml:80）：生产发布时建议以 `image:` 指向按 SHA 标记的镜像名，或保持本地构建并 retag。这是发布日操作，不强制改仓库文件。
- 前端静态产物与后端同 SHA 构建后部署到反代托管路径 `[部署环境补充]`。

## 5. 数据流要点

- **文档知识库**：上传文件 → app → MinIO 原文件 + PostgreSQL 分块/向量（pgvector）。因此 **PostgreSQL 与 MinIO 必须作为整体备份/恢复**（backup-restore-runbook 硬要求，`DOCUMENTED`）。
- **模型凭据**：用户私有 API Key 经 `MODEL_CONFIG_MASTER_KEY`（AES-256-GCM）加密入库，全局默认 Key 走环境变量（secrets-and-configuration.md）。
- **Agent 写操作**：全部经审批流落库；MCP 仅只读白名单——生产无需为 Agent 额外开权限面。

## 6. 备选拓扑（不推荐但需记录）

- **云负载均衡终止 TLS**：可行（app 仍 HTTP），但 X-Forwarded 头语义需与 LB 提供方核对，N-02 决策同样适用；`ENVIRONMENT_DEPENDENT`。
- **前后端分离域名**（前端 CDN + `VITE_BACKEND_ORIGIN=https://api.example.com`）：`.env.production` 有注释示例，但 CORS/Cookie 的跨站影响需重新评估（Cookie Path=/api/v1/auth、SameSite=Strict 下跨站携带行为受影响），**未验证，不建议首署采用**。
