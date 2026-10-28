# 验收证据归档 ｜ P1-INSTABOX-STAGING-DEPLOY-01

- 任务编号: `P1-INSTABOX-STAGING-DEPLOY-01`
- 交付分支: `dev`
- 交付 SHA: `651c6f5`
- 目标环境: InstaBox Staging (`box-aae54f`)
- 远端地址: `https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`
- 日期: 2026-10-28

## 证据清单

1. `admin_center_staging.png`: 使用 `browser-skill` (`bsk`) 在真实浏览器中登录受控首管理员账号 `staging_admin` 后截取的「管理中心」界面。展示了系统管理员特权、AI Infrastructure 入口以及由公开注册与限流测试创建的普通用户列表（普通用户均为「成员」角色，无管理员权限）。
2. 全链路自动化验收脚本: `ai-collab-deploy/scripts/test_staging_lifecycle.mjs`
3. 注册限流压测脚本: `ai-collab-deploy/scripts/test_rate_limit.mjs`
4. 备份与隔离恢复演练: `ai-collab-deploy/backup/run-restore-drill.sh` 输出 100% 一致性核验通过
