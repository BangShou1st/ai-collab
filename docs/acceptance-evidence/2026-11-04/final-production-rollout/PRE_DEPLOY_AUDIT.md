# 正式生产发布前审计（PRE_DEPLOY_AUDIT）

- 日期：2026-11-04
- 任务：AI Collab 最终生产部署、正式站升级与全链路 UAT
- 授权：项目所有者在任务书内明确授权正式发布
- 结论（阶段一）：**生产拓扑已核实；强制备份门禁已执行（见 `BACKUP_AND_ROLLBACK.md`）；可进入 Git 发布**

## 1. GitHub 远端事实（重新获取，非沿用历史）

| 项 | 事实 |
| --- | --- |
| `origin/dev` | `56b0a06a3abd0b07db6c39ae3d283f5c8bea646e` |
| `origin/main` | `66a704dd367aff7672f6d849f0aa0f0ea96d2696` |
| 工作树 | 干净（分支 `dev`，与 `origin/dev` 一致） |
| dev→main 差异 | 3 个提交、79 个文件（+4586/−200） |
| 最新功能 CI | run `41820753096` SUCCESS，head_sha `80d0c6e8`（dev 的最后一个功能提交）；dev HEAD `56b0a06a` 为纯文档提交，按 CI `paths-ignore` 不触发 |
| 分支保护 | main 强制 PR；必需检查 `Backend (Maven verify)` + `Frontend (pnpm)`（strict=true、enforce_admins=true）；禁止 force push / 删除 |
| 现成标签 | 仅 `v1.0.0`（指向 2026-10-22 的旧提交），GitHub Releases 数量为 0 |
| 未完成发布流程 | 无 open PR、无在途发布 |
| 镜像 tag 规则 | main push → `sha-<12位>` / `main` / `latest`；v* 标签 → `v<版本>` / `sha-<12位>` |

## 2. 发布内容覆盖核对

`origin/main..origin/dev` 包含：

1. `1b983ee1` 管理员独立工作区 / 五项扁平导航 / 全站 AI 供应商共享 / 普通用户选用共享模型 / 独立通知中心 / 系统设置（含服务端权限隔离）
2. `80d0c6e8` V77 用途绑定级联删除触发器与方法契约兼容修复
3. `56b0a06a` 普通用户使用全站共享 ModelScope 真实 RAG 推理验收证据

更早的 D1 Connector 可靠性修复、全站 UI/UX 去说明书化、项目洞察业务正确性修复已包含在上一次 main 合并 `66a704dd` 中；本次 PR 不遗漏 dev 上的任何新成果。

迁移文件差异：仅新增 `V77__shared_ai_provider_and_admin_system.sql`，**未修改任何历史迁移**。

## 3. Cloudflare Pages 事实

| 项 | 事实 |
| --- | --- |
| 项目名 | `ai-collab-web`（Git 集成，账户 `8fddf3e2…`） |
| 正式域名 | `https://ai-collab-web.pages.dev` |
| 生产分支 | `main`（Preview 来自 `dev` 等分支） |
| 发布前 Production 部署 | ID `4c0644db-1bce-4dda-9b7d-a75e55399a6c`，commit `66a704d`（与当时 main 对齐） |
| 前一 Production 部署 | ID `86f7d1ca-1d40-487a-a90b-34005875c188`，commit `c863ac5`（回滚候选） |
| 发布前 dev Preview | ID `ce3198f4-e2cc-4c29-a5ec-de7d9f4c6580`，commit `56b0a06` |
| 缓存 | `Cache-Control: public, max-age=0, must-revalidate`，API 经 Functions `no-store` |
| 发布前静态资产哈希 | `index-U8TCdIOk.js` / `index-CPT9jqmZ.css` |
| dev Preview 资产哈希 | `index-C0FM3JfT.js`（用于发布后比对是否缓存旧版） |
| Pages Functions | `functions/api/[[path]].ts` 同源反代 `/api/*`；`functions/storage/[[path]].ts` 反代 `/storage/*` |
| BACKEND_ORIGIN | 部署级变量；生产分支缺失时代码明确返回 503，禁止静默回退 Staging。发布前线上 `/api/v1/auth/registration-policy` 返回 `{"enabled":true}`，证明代理已生效且指向真实后端 |
| 线上 HTTPS | HTTP 200，TLS 验证通过（`ssl=0`），`server: cloudflare`，HSTS 生效 |

## 4. 真实生产后端事实（关键判断）

**判断：`box-aae54f`（InstaBox，`https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`）就是当前正式站的生产后端。** 虽然容器名/compose 项目带 `staging-` 前缀，但：

- Pages 生产部署的 `/api` 反代经 `BACKEND_ORIGIN` 指向该网关，线上 API 真实响应；
- 该后端 `AUTH_ALLOWED_ORIGINS` 同时包含网关域名与正式站 `https://ai-collab-web.pages.dev`；
- 运行 profile 为 `production`，承载 35 个真实用户、20 个项目、24 个任务、12 个个人 Provider、1 个用途绑定、1 个管理员；
- 历史验收文档 `docs/archive/report/P7.3-FEISHU-CONNECTION-UX-SECURITY-ACCEPTANCE.md` 与 `docs/archive/report/PRODUCTION-AGENT-QUEUE-INCIDENT.md` 明确记载该拓扑为"正式站形态 / 生产环境"。

因此"Staging"只是历史命名，本次按真实生产资源处理，未把它误判为隔离测试环境，也未把测试数据灌入。

| 项 | 发布前事实 |
| --- | --- |
| 主机 | InstaBox `box-aae54f`（4 vCPU / 8GiB，Debian，Docker 29.x） |
| 编排 | `docker compose` 项目 `ai-collab-staging`，文件 `/data/home/repo/ai-collab-deploy/compose.staging.yml`，env `/data/ai-collab-staging/config/.env` |
| 容器 | app / proxy(nginx) / postgres(pgvector/pgvector:pg17) / redis(7) / minio 共 5 个，app/pg/redis/minio 均 healthy |
| 运行镜像 | `ghcr.io/bangshou1st/ai-collab/ai-collab-backend:main` |
| 旧镜像 digest | `sha256:68befe03e5bed2d9730a862bb0977803b81d558540206d1343a926e1ba2db36a` |
| 旧镜像 revision | `c863ac55a59d4282a0979f5cdaa87d935762d2a2`（PR #30 合并） |
| 容器内仓库 HEAD | `c863ac55`（落后于 main `66a704dd`，但运行时代码由镜像提供） |
| Flyway | 76 条已应用迁移全部 success，最新 `V76 external document revision object key` |
| PostgreSQL | 17.11，数据库 `ai_collab_staging`，72 张业务表 |
| Redis | 7.4.9，AOF appendonly + `dump.rdb`，作用为缓存/会话/限流辅助，非唯一数据源 |
| MinIO | chainguard/minio，bucket `ai-collab-staging`，20 个对象 |
| 磁盘 | `/` 16G 用 11G 剩 4.2G（73%）；`/data` 20G 用 1.1G 剩 19G（6%）——足够本次镜像升级 |
| 健康 | 应用容器内 `/actuator/health` = `{"status":"UP"}`；nginx 对 `/actuator` 返回 404 属预期安全收敛；线上 `/api/v1/auth/registration-policy` 200 |
| 凭据来源 | 生产 env 文件（仓库外，`/data/ai-collab-staging/config/.env`，权限 0664，含 17 个配置键；本次未打印任何值） |
| 生产/Staging 是否共用 | 是：同一单机同时承担正式站后端与历史 staging 命名/预览用途； Pages Preview 分支才允许回退该网关，生产分支由 `BACKEND_ORIGIN` 显式配置 |

## 5. 发布前镜像与部署版本基线

| 项 | 值 |
| --- | --- |
| GHCR backend `:main` / `:latest` | `sha256:68befe03e5bed2d9730a862bb0977803b81d558540206d1343a926e1ba2db36a` |
| GHCR frontend `:main` | `sha256:cbb83f767a7c323d0f12a03f92fd3c21e99b7ac3c4d061118aebc2bd134d5b85` |
| 容器架构 | linux/amd64（OCI index 含 attestation manifest） |
| GHCR 包列表 API | 当前 token 缺 `read:packages`，403；改用匿名 `docker buildx imagetools inspect` 核实 digest（可行） |
| 旧 Pages Production 部署 | `4c0644db…` @ `66a704d`（回滚目标） |
| 旧 Pages 前一部署 | `86f7d1ca…` @ `c863ac5` |

## 6. 风险与门禁结论

- V77 为纯增量：新建 2 表、为用途绑定表加 `is_shared` 带默认值列（PostgreSQL 17 不重写全表）、删除一个 FK 并用 2 个 AFTER DELETE 触发器接替级联语义；无 `DROP TABLE` / `TRUNCATE` / 数据改写；不重建管理员账号、不影响既有项目数据。锁表风险低。
- 旧镜像 + V77 库：V77 结构对新旧代码向后兼容（新增表/列不被旧代码触碰），历史同类演练（V69 镜像 + V70 库）已验证 Flyway 仅输出 newer-version WARN 后正常启动。
- 数据库迁移不可逆：若发生数据级回退，必须独立评估备份恢复，**不得**简单降级镜像或改 Flyway checksum。
- 备份有效性：PG dump / MinIO mirror 均通过 SHA256、manifest、隔离恢复；详见 `BACKUP_AND_ROLLBACK.md`，含 1 条部署前既有孤儿对象引用的已知异常。

**门禁裁决：GO**（在 PR CI 全绿后合并；任何 CI 失败、Flyway 失败、健康检查失败立即阻断）。
