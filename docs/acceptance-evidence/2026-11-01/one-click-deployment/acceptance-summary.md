# 本地与服务器双场景一键部署 —— 验收证据（2026-11-01）

任务：feat/one-click-deployment。全部证据在本机（Windows 11 + Docker Desktop 29.8.0 / WSL2 后端）
真实执行产生；未真实执行的项标记 NOT_TESTED。凭据一律不入库。

## A. 本地安装验收（Windows，PASS）

| 项 | 命令/方式 | 结果 | 证据 |
| --- | --- | --- | --- |
| 首次安装（默认镜像解析：GHCR 拉取失败 → 源码构建兜底） | `install.ps1 install -Port 18080` | PASS，exit=0 | 输出「安装完成！访问地址: http://localhost:18080」 |
| 幂等重装 + 端口切换 | 再次 `install.ps1 install -Port 18080` | PASS | 「已存在 local.env，保留现有凭据与数据配置」 |
| 数据库迁移 | 安装流程内 Flyway V1–V73 | PASS | `flyway_schema_history` count=73，全部 success |
| 首管理员初始化 | `install.ps1 bootstrap-admin` | PASS | 随机凭据仅终端显示一次；`/auth/me` 返回 systemAdmin=true |
| 防重复引导 | 再次 `bootstrap-admin` | PASS | 「系统已存在管理员，初始化通道保持关闭」 |
| 登录 | `POST /api/v1/auth/login` | PASS | code=SUCCESS，Bearer 访问 `/auth/me` 正常 |
| 创建项目 | `POST /api/v1/projects`（type=COMPETITION） | PASS | id=e28c0c82-… |
| 创建任务 | `POST /api/v1/projects/{id}/tasks` | PASS | HTTP 201 |
| 文档上传 | `POST /api/v1/projects/{id}/documents` | PASS | docId=92889519-… |
| 文档下载（预签名 + 同源 /storage 代理 + auth_request） | `GET …/download-url` → `GET /storage/...` | PASS | 返回内容与上传原文一致 |
| 停止重启数据保留 | `install.ps1 stop` → `up` → 登录 + 项目列表 | PASS | 登录 SUCCESS、项目仍在 |
| 重复安装不破坏数据 | 见上（重装后再验证） | PASS | 同一卷，数据保留 |
| doctor 诊断 | `install.ps1 doctor` | PASS | Docker/架构/端口/容器健康/磁盘 全部输出 |
| status / version | `install.ps1 status` / `version` | PASS | 5 服务 healthy；输出镜像 digest 与 revision=fe777365946e… |
| 无 AI Key 基础功能 | 未配置任何模型 | PASS | 项目/任务/上传/下载可用；文档索引明确降级提示「系统尚未配置嵌入模型」 |

## B. 服务器部署验收（生产拓扑冒烟，Windows Docker 引擎）

`bash ai-collab-deploy/scripts/p0-isolated-compose-smoke.sh`（隔离随机项目/卷/网络，合成凭据，自签 TLS）：

| 项 | 结果 |
| --- | --- |
| 前端镜像从源码真实构建并纳入生产 compose | PASS |
| HTTPS + TLS（自签证书） | PASS |
| 认证 Cookie Secure 基线 | PASS（REFRESH_COOKIE_SECURE=true 契约 + p0-https-test） |
| Nginx 转发与代理信任 IP（proxy-a/b、direct、register-a/b 共 5 种模式） | PASS |
| SSE | PASS（p0-sse-test.mjs） |
| 对象存储下载（auth_request + 预签名转发，含特殊字符路径） | PASS |
| 端口隔离扫描（仅 proxy 一个 127.0.0.1 绑定端口，数据面零端口） | PASS（p0-port-scan.mjs） |
| 日志敏感信息扫描 | PASS（p0-log-scan.mjs） |
| 依赖故障演练 | PASS（p0-dependency-outage.sh） |
| 备份 → 隔离恢复 → DB↔MinIO 一致性核验（源只读） | PASS，18 秒（app_user/project/project_member/project_document 行数与对象引用集合一致） |

真实 Linux 服务器 + 公网 HTTPS：NOT_TESTED（无对应环境）。复现命令：
`sudo ./ai-collab-deploy/install.sh install --server --domain <域名> --tls-cert <pem> --tls-key <key>`，
前置检查与配置渲染校验已在本机验证（`docker compose config -q` 渲染 OK）。

## C. AI 能力验收（模拟，无真实模型调用）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| AI Provider 配置保存 | PASS | `POST /api/v1/user/ai-providers` code=SUCCESS |
| 模型密钥加密 | PASS | `encrypted_api_key` 明文检索 0 命中；存储为密文前缀 `p1Dyte7y/4tz…`；API 响应不回显 |
| Embedding 配置保存 | PASS | `PUT /api/v1/admin/embedding-config` code=SUCCESS |
| Embedding 密钥加密 | PASS | 明文检索 0 命中；存储为密文前缀 `jN8DOwlCvnXD…`；响应不回显 |
| 出站安全策略 | PASS | 内网/环回 Base URL 被拒（VALIDATION_ERROR） |
| 知识库索引全流程 | NOT_TESTED（需真实 Embedding 凭据）；上传/解析/降级提示正确 | 文档状态 FAILED + 明确配置引导 |
| Agent Worker 领取任务 / SSE 实时状态 | NOT_TESTED（需真实模型凭据）；SSE 基础链路由 p0-sse-test 覆盖 | — |
| Agent 审批机制 | 未触及（业务代码零改动，既有 1600+ Testcontainers 用例为回归门） | CI backend job |

## D. 数据安全验收

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 重启数据不丢失 | PASS | 见 A |
| 非破坏性重复安装 | PASS | 见 A |
| 备份成功且可验证 | PASS | pg dump 251KB + pg_restore --list manifest + SHA256 + summary；MinIO tgz + SHA256 |
| 本地栈恢复（覆盖式，停 app 后执行） | PASS | 恢复后 flyway=73、doc1 可下载、doc2 消失 |
| 隔离恢复演练（源只读） | PASS | p0 冒烟内置 run-restore-drill，18 秒，一致性核验通过 |
| 管理员引导不可重复执行 | PASS | 见 A |
| 凭据不入日志 | PASS | 脚本输出不含任何生成凭据；p0-log-scan 通过 |
| 本地/生产 Cookie 策略不串用 | PASS | compose.local.yml 显式 HTTP 例外（成对），compose.prod.yml 固定 Secure=true；契约测试双向锁定 |
| 升级失败不误删数据卷 | PASS | 回滚演练全程无 `down -v`；数据卷不动 |
| 未触碰现有线上环境 | PASS | 全部操作隔离于 `ai-collab-local` / 随机 p0 项目；开发容器（ai-collab-deploy）未动 |

## E. 平台兼容性

| 平台 | 结果 | 说明 |
| --- | --- | --- |
| Windows 11 + Docker Desktop | PASS | 全部 A/B/D 实测 |
| Ubuntu LTS + Docker Engine | NOT_TESTED | WSL Ubuntu 24.04 实测因宿主 WSL 服务异常（`Wsl/Service/CreateInstance/0x8007274c`）中断；已完成：`bash -n` 语法检查（GNU bash 5.2）、install.sh 维护命令功能冒烟（Git Bash 同语义）。复现：`./ai-collab-deploy/install.sh install && ./ai-collab-deploy/install.sh bootstrap-admin` |
| macOS + Docker Desktop | NOT_TESTED | 无设备。架构兼容性已内置（arm64 启动时告警 + amd64 模拟层提示）。复现同上 |

## F. 资源占用（实测）

| 项 | 值 |
| --- | --- |
| 镜像大小 | 后端 680MB、前端 95.3MB、pgvector 627MB、Redis 57.8MB、MinIO 235MB（合计约 1.7GB） |
| 空闲内存 | app 227MB、postgres 48MB、minio 67MB、web 7MB、redis 3MB（合计约 352MB） |
| 空闲 CPU | 全部 <1%（0.00%–0.53%） |
| 启动耗时 | 基础设施 healthy ≈ 30–60s；应用就绪（含 Flyway 全量迁移）≈ 1–2 分钟 |
| 持久化磁盘 | 空库+空桶卷 < 100MB；本验收全量数据 < 300MB |

结论：4GB 内存主机可运行（实测空闲 ~350MB，含系统开销留有余量）；未做极限压测，不宣称更高配置。

## G. 升级演练

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 失败回滚（坏镜像） | PASS | upgrade → 健康检查快速失败 → 自动回滚 → 应用恢复=True；exit=10；upgrade-history.log 记录 ROLLBACK |
| 成功升级 | PASS | upgrade → 备份核验 → 镜像更新 → 健康冒烟 → flyway=73→73 → 登录正常；RESULT=OK |
| 迁移不可回退提示 | PASS | 升级输出与文档明确说明，历史记录含 flyway before→after |

## H. 契约与回归

| 项 | 结果 |
| --- | --- |
| compose-config-contract.sh（开发 compose 契约） | PASS，4 场景 |
| prod-compose-contract.mjs（生产网络/安全契约） | PASS |
| local-compose-contract.mjs（本地拓扑契约，新增） | PASS |
| backup-safety-contract.sh | PASS |
| prod-config-check.test.mjs | PASS，5/5 |
| 后端 `mvnw verify`（全量 Testcontainers） | 见 CI；本地后台执行结果记于交付报告 |
| 前端 `pnpm lint` | PASS（0 errors） |
| 前端 `pnpm test` | PASS（59 个文件 / 441 用例全过） |
| 前端 `pnpm build`（含 vue-tsc） | PASS（前端镜像构建内执行通过） |

## I. 浏览器 UI 验收（browser-skill，本地栈 http://localhost:18080）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 登录页渲染（前端镜像提供 SPA，nginx 路由回退） | PASS | 无障碍树完整（标题/表单/链接） |
| UI 登录流程（表单 → Cookie → 工作台） | PASS | 「登录成功」，工作台显示 System Administrator、最近项目、最近活动时间线 |
| 管理员页面（管理中心） | PASS | 动态配置中心 / AI Infrastructure / 账号管理（admin 显示管理员角色）完整渲染 |
| 工作台数据与 API 一致 | PASS | 显示此前 API 验收创建的「部署验收项目」与活动记录 |
| 登出 | PASS | `POST /auth/logout` SUCCESS（撤销 Refresh Cookie，Access Token 自然过期，符合既有设计） |

截图：ui-admin-center.png（管理中心，本目录）。

## J. install.sh（Linux/macOS 脚本）功能验证

| 环境 | 结果 |
| --- | --- |
| WSL Ubuntu 24.04（GNU bash 5.2）`bash -n` 语法检查 | PASS |
| Git Bash 功能冒烟：status / bootstrap-admin（识别已初始化）/ backup（复用仓库成熟脚本，含 mc diff 校验）/ version | PASS |
| 真实 Linux 引擎 + 服务器模式端到端 | NOT_TESTED（WSL 服务异常 `Wsl/Service/CreateInstance/0x8007274c` 且无真实服务器；复现命令见 E 节） |

