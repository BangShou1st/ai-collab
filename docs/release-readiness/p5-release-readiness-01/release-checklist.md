# release-checklist.md — 正式发布前检查清单

> 使用方式：进入发布候选（RC）阶段后逐项勾选。S1 整改完成是本清单的前置条件。发布负责人由人指定（本任务不指定、不代行）。

## 一、前置条件（来自 release-gates.json 条件）

- [ ] R-01：备份脚本就绪 + 隔离环境真实恢复演练报告归档
- [ ] R-02：生产 profile 可观测性基线生效（health/metrics + 日志滚动实测）
- [ ] R-03：发布 runbook 定稿 + 可追溯镜像产出
- [ ] R-04：compose REFRESH_COOKIE_SECURE 变量化 + 生产 TLS/暴露方式确认
- [ ] 发布范围声明：MCP（实验特性或已验收）、Zen Free（上游依赖）已书面明确

## 二、环境与配置

- [ ] .env 生产值全部就位（对照 .env.example + operations-readiness.md 缺口清单）：JWT_SECRET、MODEL_CONFIG_MASTER_KEY（≥16 字符高熵）、POSTGRES_PASSWORD、REDIS_PASSWORD、MINIO_*、REFRESH_COOKIE_SECURE=true
- [ ] 无占位符/change-me 残留（启动会失败，属预期防护）
- [ ] AUTH_ALLOWED_ORIGINS 与实际前端域名一致（CORS 启动期校验会拒绝通配符）
- [ ] AGENT_WORKER_CONCURRENCY / 限流变量按容量预期设定
- [ ] 出站网络白名单可达：opencode.ai、ai-gateway.kilo.ai、generativelanguage.googleapis.com（如用 Gemini）、JitPack（仅构建期）
- [ ] 服务器磁盘容量评估（PostgreSQL 卷 + MinIO 卷 + 日志滚动空间）

## 三、数据与迁移

- [ ] 发布前完成全量备份（pg_dump + MinIO mirror），备份文件可在隔离环境打开校验
- [ ] 目标库 flyway_schema_history 版本与镜像内迁移集合一致
- [ ] 确认无历史迁移修改（本仓库纪律良好，V5 违例已闭环）
- [ ] 迁移失败预案：Flyway 失败即停 → 回滚到备份 → 排查 → 修复后重试（不自跳版本）

## 四、部署执行

- [ ] 镜像 digest 记录在案（构建输出 + 部署记录）
- [ ] compose 服务 healthcheck 全部通过（postgres/redis/minio/app）
- [ ] /actuator/health UP；前端可访问且登录成功
- [ ] DEMO_OWNER（若启用）：首次登录立即改密或直接禁用该机制

## 五、发布后冒烟测试（覆盖核心闭环）

- [ ] 注册/登录/登出（闭环 1 入口）
- [ ] 创建项目 → 添加成员 → 创建任务 → 改状态（闭环 1）
- [ ] 上传文档 → 索引 READY → 提问 → 引用展示（闭环 2，需 Embedding 配置就绪）
- [ ] 模型配置探针（CONNECTIVITY）→ 知识问答/Agent 用途就绪度「可用」（闭环 7）
- [ ] Agent 提问 → 只读工具回答 → 刷新恢复（闭环 4）
- [ ] Agent 写提案 → 审批 → 落库（闭环 5）
- [ ] 规划生成 → 确认落库（闭环 3，视模型可用性）
- [ ] SSE 事件实时可见、无跨用户数据可见

## 六、回滚与事故

- [ ] 回滚条件书面明确（例：冒烟任一失败 / 错误率异常 / 数据不一致）
- [ ] 回滚步骤：镜像回退至上一 digest + 数据库回滚到发布前备份（先停写）
- [ ] 事故联系人与升级路径
- [ ] 发布时间窗与公告

## 七、发布后 24 小时

- [ ] 检查日志滚动与错误级别分布
- [ ] 检查 agent_run 无 FAILED 堆积、租约无僵尸
- [ ] 检查 Redis 限流计数、DB 连接池
- [ ] 完成一次备份并验证可恢复
