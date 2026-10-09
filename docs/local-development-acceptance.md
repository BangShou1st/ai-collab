# 本地开发流程验收（2026-10-09）

基线 fetch 后为 `d01ef11`（PR #9 已合并），本轮从最新 main 建立
`codex/local-dev-workflow`。仅新增启动入口和维护文档、修正 README；未改 Agent、SSE、
后端业务配置、compose 镜像或 CI 门禁。`.freebuff/`、`.dsh-acl-recovery/` 与既有配置保留。

## 实际通过

| 检查 | 结果 |
| --- | --- |
| 首次启动 / 重复启动 / 停止后再启动 | 本机 JVM 与 Vite 健康；重复启动 PID、创建时间、命令摘要保持一致 |
| 后台生命周期 / 日志 | Windows PowerShell 调用返回后服务继续运行；stdout/stderr 直接写文件，不依赖调用终端的日志泵 |
| PowerShell 兼容 | Windows PowerShell 5.1 与 PowerShell 7 的解析、状态与重复启动通过；实际完整启停在 5.1 下通过 |
| 8080 / 5173 冲突 | 分别使用临时 Node TCP listener；启动失败报告端口、PID、可执行文件，未停止冲突进程 |
| 健康失败 / 恢复 | 临时受管 listener 冒充前端时健康检查超时，输出日志位置并保留进程；移除测试 listener 后再次启动恢复 |
| 默认停止范围 | 只停止身份匹配的受管 JVM/Node，两个端口释放；借用标记和命令摘要不匹配时均跳过 |
| 借用 | 暂时移除本入口的身份记录，健康的同仓库进程被标为借用；执行 stop 后仍在运行 |
| 配置缺失 | 在独立临时目录运行入口，缺 .env 时明确失败，未创建配置；未改现有 .env |
| 基础设施 / 数据保护 | 复用既有 PostgreSQL、Redis、旧 MinIO；启停前后三只容器 ID 与挂载卷保持一致，未启动容器 app |
| 数据库 | 启动前已有 Flyway V64，与本轮源码一致；没有本轮业务迁移或账号重置 |
| 后端源码编译 | 现有 Maven wrapper 的 compile 与 dependency:build-classpath 成功 |
| 浏览器登录 | bsk 后台桌面会话；浏览器旧自动填充凭据被拒，用现有根 .env 的账号凭据登录成功；未打印密码 |
| 项目 / 文档 / Agent | 读取已有项目、文档列表和正文、已有 Agent 历史；未发送 Agent 请求或修改已有业务资料 |
| 刷新恢复 | bsk reload 后文档与 Agent 页面恢复、登录会话有效；服务重启后也能继续读取 |
| Embedding | 用户启动现有 Ollama 后，管理页沿用当前表单测试成功：现有 qwen3-embedding:0.6b，实际 1024 维；一次复核耗时 450 ms |

Embedding 只调用连接/维度测试，没有保存配置、构建索引或下载模型；没有付费模型请求。
应用写入仅涉及正常登录会话；业务页面验收均为读取，没有新增业务测试数据。
测试 listener、身份记录探针与原始日志留在 Git 忽略的 `target/local-dev`，不作为公开环境归档。
Git 交付须经过现有 `Backend (Maven verify)`、`Frontend (pnpm)` 两个 required checks，
以 PR 检查记录及合并后 main 的 CI 运行作为完整回归证据，不修改或绕过管理员门禁。

## 环境阻碍与未验证项

- bsk 首次后台 daemon 自动启动失败，用持久前台任务恢复；中途会话失效后重新建立会话完成验收。
  截图调用发生 RPC timeout，页面结论来自 bsk 的实际 DOM 观察与刷新，不声称截图验收通过。
- 未销毁现有环境来测试全新空卷安装，未对 Docker 缺失/启动失败做破坏性故障注入。
  本轮首次启动指前后端均未运行、复用已有健康基础设施的实际启动。
- 未实停基础设施来验证 `stop-infra`，只检查其选择范围；默认 stop 不影响基础设施已实测。
- 未升级旧 MinIO，未把新镜像连接旧业务卷；旧数据升级兼容仍未验证，不把前轮空卷验证当成通过。
- 未验证公网生产部署或新增 Agent 实验；当前地址仅供本地开发。

日常操作与日志位置见 [本地开发维护说明](local-development.md)。
