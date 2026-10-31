# P4.1 Staging 部署与验证记录（2026-10-30，部分通过/一项受阻）

## 部署记录

- 发布基线：`83674d4`（PR #20 CI 全绿，含镜像冒烟）；镜像 `ai-collab-backend:staging-83674d4`（ID `3ef5b34b7061`），镜像内 Bun 1.4.4（musl，SHA256 校验安装）。
- 前置备份：`/data/ai-collab-staging/backups/postgres/pg_ai_collab_staging_20261029T173548Z.dump`（317268 字节，pg_restore --list + SHA256 `07b1b6482118146349137c829a5c2b39619e98b5e32ce5851583c9191644fdd5`）；MinIO 6 个对象镜像（mc diff 零差异）。旧镜像 `ai-collab-backend:staging-3a4fa7e` 保留未删除。
- Flyway：真实 Staging 库自动应用 V70，日志 `Successfully applied 1 migration to schema "public", now at version v70`；应用健康 UP。
- 运行时核验：容器内 `CONNECTOR_CLEANUP_ENABLED=false`、`MINIO_PUBLIC_ENDPOINT=https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`（独立于 AUTH_ALLOWED_ORIGINS）；`connector_cleanup_job`=0、`connector_deletion_journal`=0，应用日志无清理活动。

## 验证结果

| 项 | 结果 |
| --- | --- |
| 网关 `/api/` 与 `/storage/` 路由 | PASS（api 200；`/storage/` 未匹配路径 404、非授权对象 403，均非 SPA 回退） |
| 注册/登录/建项目/上传（经网关） | PASS（测试账号 `p41acceptance`，合成文档 2 个） |
| 下载地址 Host | PASS（返回 URL Host 为网关域名，浏览器不接触内部 MinIO） |
| 非成员下载地址负例 | PASS（第二账号请求他人项目下载地址 → HTTP 404 拒绝） |
| Zen 免费模型列表（真实链路） | PASS（7 个模型：beam-free、big-pickle、deepseek-v4.1-flash-free、glm-5.3-flash-free、mimo-v2.6-flash-free、nemotron-3.5-lightning-free、nemotron-3.5-super-vl-free）——"未发现模型"根因修复证实 |
| 基础回归（文档列表/知识会话） | PASS |
| 真实 HTTPS 预签名下载 + SHA-256 | **受阻**：经网关下载返回 MinIO `SignatureDoesNotMatch`（链路转发后签名失配）；签名本身有效——盒内直连 MinIO 并以签名 Host 请求为 200。盒上 Nginx 配置调试期间被 `sed -i`（bind-mount 单文件 inode 失效）污染，已用 git 版本恢复（`e33c3f3`，`$host`）并重建 proxy，经网关仍失配。 |
| 飞书 OAuth 授权/刷新/撤权 | 未执行（需用户提供飞书测试应用并人工完成授权，且依赖下载门禁解除后一并验证） |

## 受阻项分析与下一步

1. 签名有效而经 Nginx/边缘转发的请求失配，嫌疑集中在转发链对 Host/查询串的改写：盒上调试曾被 bind-mount inode 失效干扰（`sed -i` 替换文件导致容器内配置未更新），已恢复 git 版本；下一步需在可靠配置装载的前提下（重建容器后 `nginx -T` 核对）用边缘与盒内两组请求做对照捕获。
2. 本地 `MinioProxyIntegrationTest` 对同版本 Nginx conf 的签名代理测试通过，差异在真实边缘层（instabox gateway）或真实 Host 形态；需以受控抓包定位后修复 conf（候选：固定签名 Host、或在应用侧对签名 host 与转发 host 显式对齐）。
3. 下载门禁未通过前，`REMOTE_DOWNLOAD_ACCEPTED` 保持未通过；OAuth/清理验证顺延。部署保持现状（新镜像 + V70 + 清理关闭），无需回滚。

## 回滚状态

未回滚。如需回滚：按 `docs/deployment/staging-rollout-p4-1.md` 关闭知识相关入口后切回 `staging-3a4fa7e`（实测旧镜像对 V70 库可直接启动，无需 Flyway 开关），V70 结构与数据保留。
