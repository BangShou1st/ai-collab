# 最终部署验收、GHCR 发布闭环 —— 验收报告（2026-11-01）

任务：AI Collab 一键部署工程最终收口。基线 `dev`=`05b91d2`（PR #24 合并，CI 全绿），
验收分支工作经 PR #25 合入 `dev`，发布合并 PR #26（`main`=`6e0745f`）触发首次正式镜像发布。
所有结论基于真实执行；未执行项如实标注 NOT_TESTED / BLOCKED。

## A. 基线与发布链路（PASS）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| dev 稳定基线 | PASS | `origin/dev=05b91d2`，CI run 41303944573 success（14m25s） |
| 验收基建合入 dev | PASS | PR #25（acceptance 脚本 + workflow_dispatch 工作流），CI 全绿后合并 |
| 发布合并 main | PASS（用户已授权） | PR #26（dev→main）CI 全绿（Backend 13m59s / Frontend 1m19s / Image smoke 3m53s / P0 8s），合并为 `6e0745f` |
| Git 状态与秘密 | PASS | 全程工作树干净；仓库无硬编码凭据（本地 `.env` 已 gitignore 且未跟踪） |

## B. InstaBox box-aae54f 只读检查（PASS；结论：不可用作验收环境）

CLI `npx instabox@latest exec box-aae54f -- bash -lc '<cmd>'` 严格只读检查：

- Debian 13.7 (trixie) x86_64，4 vCPU，7.8GiB 内存（available 6.4GiB），Docker 29.8.2 + Compose v5.6.0 完全可用。
- **机器上运行着现役 AI Collab staging**（5 容器全 healthy，含 4 天数据卷），宿主 8443 被其占用。
- 根分区仅剩 **2.9G（81%）**，承载 18 个后端镜像 tag；InstaBox 套餐 `maxMachines=1` 无法新建独立机器。
- 结论：任何构建都可能撑爆 / 分区危及现役 staging；按预案改用 GitHub Actions 干净 Runner。全程未对该机做任何变更。

## C. Linux 真实引擎安装验收（PASS，GitHub Actions ubuntu Runner）

新增 `ai-collab-deploy/scripts/install-acceptance.sh` + `.github/workflows/install-acceptance.yml`（workflow_dispatch），
在干净 Runner（Ubuntu 26.04.1，Docker 29.4.2，amd64）上执行 install.sh 全生命周期：

**发布镜像路径（run 41316823325，21/21 PASS，总耗时 90s）**：

| 项 | 结果 |
| --- | --- |
| 首次安装（GHCR 拉取，无任何源码编译） | PASS，35s |
| 镜像来自 GHCR 且 RepoDigests 与发布记录一致 | PASS |
| doctor / status | PASS |
| 首管理员初始化 + 防重复引导 | PASS |
| 登录 + /auth/me systemAdmin=true | PASS |
| 创建项目 / 创建任务 | PASS |
| 文档上传 → 预签名下载，SHA256 与原文一致 | PASS |
| stop 后入口不可达（数据卷保留）→ up 后数据保留 | PASS |
| 备份产物齐备（pg dump + SHA256 + minio/） | PASS |
| 覆盖式恢复后登录 + 文档内容与原上传一致 | PASS |
| 坏镜像升级被拒 → 自动回滚 → APP_IMAGE 复原 → 应用健康 | PASS |
| server-check：正向通过；占位符/弱 JWT/证书域名不匹配全拒绝 | PASS |

**源码构建兜底路径（run 41316820294，20/20 PASS，总耗时 92s）**：安装 36s（GHCR latest 命中拉取；
兜底选择逻辑已验证——首次发布前的 run 41314908381 在镜像不存在时自动转入源码构建流程；
构建本身由 CI「Image build & container smoke」job 在每次 push 于 Linux Runner 上持续验证）。

**资源占用（Runner 实测，空闲态）**：app 475.6MiB、postgres 56.8MiB、minio 58.9MiB、redis 4.1MiB、web 5.5MiB（合计约 600MiB），CPU 全部 <1%；磁盘占用与镜像体积与上轮 Windows 实测一致（约 1.7GB 镜像）。

**真实公网服务器 + 域名 HTTPS：NOT_TESTED**（无可用环境；生产拓扑 HTTPS/TLS/Cookie/SSE/端口隔离/备份恢复
由 CI 每次 push 在 Linux Runner 上以 `p0-isolated-compose-smoke.sh` 隔离演练覆盖）。

## D. GHCR 首次发布闭环（PASS）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| release job 触发与完成 | PASS | main push `6e0745f` → run 41312543653「Publish images to GHCR」success |
| 权限 | PASS | job 级 `packages: write`，GITHUB_TOKEN 完成推送（PR/不满足条件时 skipping，双断言防路径漂移） |
| 发布产物 | PASS | `ghcr.io/bangshou1st/ai-collab/ai-collab-{backend,frontend}`，标签 `sha-6e0745ff74b0`、`main`、`latest` |
| 镜像 Digest ↔ 源码 SHA | PASS | backend `sha256:d0e1de4a6c31…4b4130`、frontend `sha256:fb9165ae1d5e…1f1c7c`（CI 日志记录）；本地拉取后 RepoDigests 逐一比对一致；backend OCI label `revision=6e0745ff74b0…fd216d` 与发布 SHA 全等 |
| 真实拉取 | PASS | ① GH Runner 发布镜像路径（docker login GITHUB_TOKEN）35s 完成；② 本机 `docker pull` 同 digest 成功。注意：Runner 上未登录 GITHUB_TOKEN 时匿名拉取也成功（GitHub 基础设施内行为），公网匿名拉取按 Private 设计应失败 |
| 干净环境部署无需源码编译 | PASS | 发布镜像路径安装全程无「从本地源码构建」字样（脚本断言），36s 完成 |
| 升级与回滚 | PASS | 坏镜像升级 exit≠0、自动回滚、数据卷未动、应用恢复健康（见 C 表） |
| 镜像可见性 | 用户裁决 Private | 文档已补充：匿名拉取失败自动回退源码构建；公开化需在 GitHub Packages 网页手动操作（无 API） |

## E. 数据安全验收（PASS）

- 备份 → 覆盖式恢复 → 内容 SHA256 一致（Runner 实测 + 本地发布镜像栈实测）。
- 升级失败自动回滚，全程无 `down -v`，数据卷不动，upgrade-history 记录 ROLLBACK。
- 防重复引导、凭据零落盘（脚本断言 + 日志扫描）、server-check 拒绝非法配置（4 场景）。
- 未触碰任何既有环境：box-aae54f 只读；本机开发容器（ai-collab-postgres/redis/minio）未动。

## F. 本轮发现并修复的部署缺陷（3 项，均已回归）

| 缺陷 | 修复 | 回归 |
| --- | --- | --- |
| `install.sh restore` 覆盖式恢复必然被 restore-postgres.sh「目标库已存在」守卫拒绝（die 14），`ALLOW_SOURCE_OVERWRITE=1` 只放行容器级守卫 | 显式覆盖时终止目标库连接并删库重建；未显式覆盖时守卫不变 | 本地发布镜像栈实测备份→覆盖恢复→登录 SUCCESS；Runner 21/21 PASS |
| 验收脚本 JSON 提取使用 grep 括号表达式 `[^{}]*`，GNU grep 将 `{}` 当作区间语法导致整模式失配 | 改用 `[^}]*` | 本地双环境复现→修复→活栈回归通过 |
| 验收脚本要求本地模式镜像引用为 digest 固定，与本地模式按设计使用标签引用冲突 | digest 断言改为按 RepoDigests 内容比对 | Runner PASS |

## G. 过程事故记录（透明披露）

- 嵌套 Docker 引擎验收尝试（docker-in-docker，Ubuntu 24.04）因宿主 `.wslconfig` 限制 WSL VM 仅 2GB 内存
  而失败，且构建写入导致 **E 盘写满**、Docker 引擎短暂不可用。处置：用户授权后清理本实验全部产物
  （容器/镜像），fstrim + diskpart 压实数据盘 30.9GB→19.2GB，**E 盘可用空间恢复至 12GB+**，
  引擎与用户开发栈（ai-collab-postgres/redis/minio）恢复正常；实验期间 staging 数据盘未被格式化、数据无损。
- 临时挪动的可重建缓存（`.pnpm-store`、`backend/target`、前端 `node_modules`）已全部归位。

## H. 最终交付裁决

**ACCEPTED** —— 四项硬性门槛全部真实通过：
① Linux 安装（GH Runner 真实 Docker Engine 全生命周期 21/21）；② GHCR 真实发布与拉取（digest/revision 全链一致）；
③ 关键业务冒烟（登录/项目/任务/文档上传下载）；④ 数据安全（备份/覆盖恢复/升级回滚/防重复引导/凭据零落盘）。

NOT_TESTED 项（不影响裁决，已获用户认可用 Runner 替代）：真实公网服务器 + 公网域名 HTTPS 端到端；
box-aae54f 复用验收（环境限制）；真实 AI Provider 凭据下的知识库索引与 Agent 执行（沿用上轮结论）。

后续建议：dev→main 常规同步 PR 合并后 `latest/main` 标签将指向新合并提交（代码与已发布版本一致，
仅多验收工具与文档）；正式版本标签（如 v1.0.0）待用户另行授权。
