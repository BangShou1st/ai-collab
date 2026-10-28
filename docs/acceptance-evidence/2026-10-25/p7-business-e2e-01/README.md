# P7-BUSINESS-E2E-01 验收证据：真实业务链路与发布前验收

> **裁决：`P7_BUSINESS_E2E_01_ACCEPTED`（核心浏览器验收全部通过；生产部署门禁未关闭，另报 PRODUCTION_DEPLOYMENT_NO_GO，详见交付报告）**
> 基线 SHA：`5aa30bac09061e51f9abbb6a2c5e496e0221deb0`（开工时 dev == origin/dev，工作区干净）
> 交付报告：[docs/archive/reports/p7-business-e2e-01-delivery-20261025.md](../../../archive/reports/p7-business-e2e-01-delivery-20261025.md)

## 1. 验收矩阵（E01–E12）

| 场景 | 裁决 | 真实浏览器 | 关键证据 |
| --- | --- | --- | --- |
| E01 登录与项目工作区 | PASS | 是 | 登录/登出/项目列表/详情/刷新保持；错误密码 → 401 + toast「登录失败：用户名或密码错误」（e01-07）；创建 P7 项目 POST 201 |
| E02 文档上传解析与索引 | PASS | 是 | 5 篇冻结语料 202 上传 → 全部 READY；分块数 UI=DB（3/4/1/1/2，共 11 向量行）；Embedding 模型/维度/fingerprint 与系统配置一致；刷新持久（e02-03/04）；非法 .xyz 被拒（e02-05） |
| E03 知识问答与真实引用 | PASS | 是 | Q03 三阶段证据 [S1]–[S5] 全进上下文（P6-D S2 效果在真实浏览器复现）；Q09 双合同独立来源 [S1]/[S2]（相似度均 81.3%）；无答案诚实拒答；指定文档范围仅引《合同模板甲》；Q02 已知检索限制如实复现；刷新后 10 条消息与 DB 一致；引用按 rank 持久化 |
| E04 Agent 只读检索 | PASS | 是 | 真实运行 SUCCEEDED；2× search_project_knowledge（SAFE_READ）；事件序列 1–13 无缺口；0 审批/0 任务（无业务写入）；刷新重载 |
| E05 SSE 断线与恢复 | PASS | 是 | 受控故障代理（p3-obs-02 方法）：`disconnect(NETWORK, seq 5) → transport_reconnected(attempt 6) → recovered(seq 5)`；页面标记 keep 无重载；最终回答唯一；游标全程锚定 seq 5 |
| E06 审批与写操作安全 | PASS | 是+API | 非法参数 → REJECTED（工具结果区分清晰）；批准前 0 写入；批准后恰好 1 任务；重复批准 → 409 `AGENT_APPROVAL_CONFLICT`；旧 revision → 409；批准入口消费 |
| E07 AI 规划与人工确认 | PASS | 是 | 真实模型（魔搭 DeepSeek）生成 v2 READY（截断失败路径如实呈现，见 issues I-4）；人工编辑 → v3；确认创建 5 里程碑/15 任务/14 依赖（DB 16 任务含 1 既存）；刷新持久；确认入口消失 |
| E08 任务协作与权限 | PASS | 是+API | Member 看板状态只读渲染；Member 创建任务 → 403 `PROJECT_ADMIN_REQUIRED`；改他人任务 → 403 `AUTH_FORBIDDEN`；私有项目 → 404 + 中性拒绝页无泄漏；邀请/接受/移除全链路；被移除成员刷新即失去访问 |
| E09 错误恢复 | PASS | 是 | 非法文件拒绝、规划截断失败+重试、审批冲突、SSE 断线恢复、刷新持久化均在本轮真实发生；429/Embedding 停机 NOT_EXECUTED（延续历史边界） |
| E10 P6-C 诊断能力 | PASS | 部分（API+DB） | Actuator 仅暴露 health/info：未认证 401、认证后 404（I-1 修复后）；事件序列 0 缺口；历史 UNKNOWN 1 条（年龄 1d+）可被诊断查询发现；过期租约 0；日志 MDC 关联字段（requestId/agentRunId）在日志实证 |
| E11 数据库一致性 | PASS | 只读核验 | 见 `database/read-only-verification-results.txt`：文档/分块/引用/事件/审批/任务/里程碑/成员资格全部一致 |
| E12 发布门禁 | PASS（复核） | 否 | 见 `deployment-gates.md`：6 项门禁仍阻断生产（环境依赖），G-1 维持关闭 |

## 2. 本轮真实缺陷与修复（均最小修复，详见 issues.md）

| 编号 | 优先级 | 缺陷 | 修复 |
| --- | --- | --- | --- |
| I-1 | P2 | 未知路径/未暴露端点 500 冒充内部错误 | `GlobalExceptionHandler` + `ErrorCode.RESOURCE_NOT_FOUND` → 404 |
| I-2 | P2 | AI 设置 Provider 卡片按钮与长模型名重叠（用户截图报告） | `.model-row` flex-wrap + `.model-main` flex-basis（纯 CSS 两行） |
| I-3 | P3 | 探针"不支持"与"未声明"语义混淆（记录，未改行为） | — |
| I-4 | P3 | 免费模型规划截断（应用行为正确，模型能力限制） | — |

## 3. 目录结构

- `browser/`：E01–E08 真实浏览器截图（对应本轮操作，非历史截图）
- `network/`：脱敏网络证据（SSE 控制台序列、审批拒绝响应）
- `database/`：只读一致性核验 SQL 与结果
- `regression/`：定向测试结果（AgentActuatorSecurityTest 2/2 通过；前端 lint/typecheck/test 见交付报告）
- `environment.json`：环境、工具版本、起止 SHA、模型真实可用性
- `issues.md` / `deployment-gates.md`

## 4. 模型与授权边界

- Embedding：系统级本地 Ollama `qwen3-embedding:0.6b`，真实调用。
- QA/Agent/E05 Chat：Kilo Gateway 免费 `cohere/north-mini-code:free`（既有匿名免费配置，P2/P3 轮同口径）。
- 规划 + 能力探针：魔搭 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`（用户当轮提供 Key 并明确授权；Key 加密存储于本地库，证据脱敏不含 Key）。
- Gemini：未调用（无新授权）。
