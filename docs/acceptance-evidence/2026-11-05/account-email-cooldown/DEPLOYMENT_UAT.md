# 账号邮件冷却发布验收

日期：2026-11-05（Asia/Shanghai）

## 发布

- PR：[ #33：统一账号邮件 60 秒冷却](https://github.com/BangShou1st/ai-collab/pull/33)，已合并。
- 合并提交：`d4533d13760e6e17b9866bff4e50046942721857`。
- 主分支 CI：[run #379](https://github.com/BangShou1st/ai-collab/actions/runs/42006118680)，结论 `success`。前端、后端三个测试分片、Maven 汇总检查、生产契约和镜像 smoke/topology 检查均通过。
- 后端部署镜像：`ghcr.io/bangshou1st/ai-collab/ai-collab-backend:sha-d4533d13760e`。
- 运行容器标签中的 revision 与合并提交一致；`app` 健康检查为 `healthy`。

## 部署前备份

- PostgreSQL：`/data/ai-collab-staging/backups/account-email-cooldown-20261105/postgres/pg_ai_collab_staging_20261105T095643Z.dump`，544382 字节；`pg_restore --list` 成功，清单含 74 项表数据，SHA256 文件已生成。
- MinIO：`/data/ai-collab-staging/backups/account-email-cooldown-20261105/minio/ai-collab-staging`，20 个对象；`mc diff` 未发现差异，清单已生成。
- 生产密钥仅在备份进程内使用，未回显或记录。

## 生产验收

- 后端应用、PostgreSQL、Redis、MinIO 均健康；反向代理仍在运行。
- 通过 Browser-skill 打开生产账号设置页，确认提示显示：更换邮箱保存后会自动发验证邮件，注册验证、邮箱重发和忘记密码邮件共享账号的 60 秒间隔。
- 生产 `/forgot-password` 页面正常加载，截图见 [forgot-password.png](forgot-password.png)。
- 未提交任何表单，未向真实邮箱发送邮件。

## 本地检查记录

- 前端 lint、typecheck、489 项测试与 build 均通过。
- 后端账号生命周期集成测试 3/3 通过。
- 本机完整 `verify` 的 2125 项中有两个与本次改动无关的 Testcontainers 用例异常（基准测试连接中断、RAG 数据重置死锁）；两例单独重跑均通过。GitHub PR 与合并后主分支 CI 的完整检查均为绿色。
