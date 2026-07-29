# 现有功能冒烟检查

该脚本验证已经实现的关键只读业务链路，适用于本地联调和提交前回归。它不会创建、修改或删除业务数据。

## 前置条件

- PostgreSQL、Redis、MinIO 已启动。
- 后端以 `local` 配置运行在 `http://localhost:8080`。
- 根目录 `.env` 中存在 `DEMO_OWNER_USERNAME` 和 `DEMO_OWNER_PASSWORD`，或通过参数显式传入测试账号。
- 测试账号至少属于一个项目；没有项目时，脚本会通过登录和项目列表检查，但跳过项目内接口。

## 运行

在仓库根目录执行：

```powershell
.\scripts\smoke-existing.ps1
```

自定义后端地址或账号：

```powershell
.\scripts\smoke-existing.ps1 `
  -BaseUrl "http://localhost:8080/api/v1" `
  -Origin "http://localhost:5173" `
  -Username "owner" `
  -Password "<本地测试密码>"
```

脚本依次检查健康状态、登录、当前用户、项目列表，以及首个项目的详情、概览、审计日志、任务、里程碑、文档、知识问答会话和 AI 任务规划列表。任一步骤失败都会返回非零退出码；无论成功或失败，已建立的登录会话都会尝试退出。

脚本不会输出访问令牌、密码、Cookie 或 API Key。
