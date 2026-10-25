# P7-BUSINESS-E2E-01 交付报告：真实业务链路与发布前验收

日期：2026-10-25 · 仓库：`BangShou1st/ai-collab` · 分支：`dev`
证据目录：[docs/acceptance-evidence/2026-10-25/p7-business-e2e-01/](../../acceptance-evidence/2026-10-25/p7-business-e2e-01/README.md)

## A. Git 与环境

- 开工 SHA：`dev=5aa30bac09061e51f9abbb6a2c5e496e0221deb0`、`main=9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（fetch 后核实）；PR #18 OPEN/Draft 未合并；工作区干净。最终 SHA 见本文末尾补记。
- 启动方式：`dev.ps1 start`（复用既有 postgres/redis/minio 容器与数据卷，未清卷/未重建/未重置数据库）；浏览器为真实 Microsoft Edge（browser-skill 0.3.3）。
- 模型真实可用性：Embedding=系统级本地 Ollama `qwen3-embedding:0.6b`（真实调用，fingerprint 与 P4 一致）；QA/Agent Chat=Kilo Gateway 免费 `cohere/north-mini-code:free`（既有匿名免费配置，P2/P3 轮同口径）；规划与能力探针=魔搭 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`（用户当轮提供 Key 并明确授权，四项能力探针 4/4 通过）；Gemini 未调用（无新授权）。

## B. E01–E12 验收矩阵

| 场景 | 裁决 | 真实浏览器 | 说明 |
| --- | --- | --- | --- |
| E01 登录与项目工作区 | PASS | 是 | 登录/登出/项目列表/详情/刷新保持；错误密码 401+正确 toast；新建项目 POST 201 |
| E02 文档上传解析与索引 | PASS | 是 | 5 篇冻结语料全部 READY；分块数 UI=DB；Embedding 模型/维度/fingerprint 一致；非法文件被拒；刷新持久 |
| E03 知识问答与引用 | PASS | 是 | Q03 三阶段证据 [S1]–[S5] 全进上下文；Q09 双合同独立来源；诚实拒答；文档范围限定生效；Q02 已知限制如实复现；刷新与 DB 一致 |
| E04 Agent 只读检索 | PASS | 是 | SUCCEEDED、2×SAFE_READ 工具调用、事件 1–13 无缺口、无业务写入、刷新重载 |
| E05 SSE 断线与恢复 | PASS | 是 | 受控故障代理注入：disconnect→transport_reconnected→recovered，游标锚定 seq 5，无页面重载、无重复最终消息 |
| E06 审批与写操作安全 | PASS | 是+API | 提案先审后写；批准前 0 写入、批准后恰好 1 任务；重复批准 409、旧 revision 409；工具结果四态区分（SUCCEEDED/REJECTED 均复现） |
| E07 AI 规划与确认 | PASS | 是 | DeepSeek 真实生成 v2（免费模型截断失败如实展示）；人工编辑 v3；确认落库 5 里程碑/15 任务/14 依赖；刷新持久；重复确认入口消失 |
| E08 任务协作与权限 | PASS | 是+API | Member 只读状态渲染；创建 403、改他人任务 403（后端强制）；私有项目 404 中性页无泄漏；邀请/接受/移除全链路；移除后访问失效 |
| E09 错误恢复 | PASS | 是 | 截断失败+重试、审批冲突、SSE 恢复、刷新持久化真实发生；429/Embedding 停机 NOT_EXECUTED（延续历史边界） |
| E10 P6-C 诊断 | PASS | API+DB | Actuator 仅 health/info（401/404 行为正确）；事件 0 缺口；历史 UNKNOWN 1 条（1d+）可发现；过期租约 0；MDC 关联实证 |
| E11 数据库一致性 | PASS | 只读 | 文档/分块/引用/事件/审批/任务/里程碑/成员资格全部一致（database/ 目录） |
| E12 发布门禁 | PASS（复核） | 否 | 6 项门禁仍阻断生产（见 deployment-gates.md）；G-1 维持关闭不重复修复 |

## C. 真实缺陷与修复

1. **I-1（P2，已修复）未知路径/未暴露端点 500 冒充内部错误**：持 token 访问未暴露的 `/actuator/metrics` 返回 500 `INTERNAL_ERROR`（后端日志 `NoResourceFoundException`），与 `AgentActuatorSecurityTest` 期望的 404 矛盾。最小修复：`GlobalExceptionHandler` 新增 `NoResourceFoundException → RESOURCE_NOT_FOUND(404)`。修复后实测 404；定向测试 2/2 通过。修复前 500 证据在本轮操作记录中，修复后 404 见 network/。
2. **I-2（P2，已修复）AI 设置 Provider 卡片按钮悬空/重叠**（用户截图报告）：长模型 ID 使 `.model-main` 被挤压至 0 宽（实测 w=0，按钮 x=405 与文字 x=342 重叠）。最小修复：`styles.css` 两行（flex-wrap + flex-basis 240px）。修复后文字块 663px、按钮右置、面板换行（截图）。
3. **I-3（P3，记录不改）探针"不支持"语义混淆**：未声明 NATIVE_TOOLS 时探针在声明门控短路，UI 显示"不支持"而非"未声明（未实测）"。勾选声明后真实探针通过（DeepSeek 原生 tool calling 可用，与用户直连实测一致）。建议后续改文案，本轮不改行为。
4. **I-4（P3，环境限制）免费模型规划截断**：cohere 免费模型两次 `PLANNING_MODEL_OUTPUT_TRUNCATED`，UI 均如实展示并提供重试；换 DeepSeek 一次成功。应用行为正确，属模型能力差异。

## D. P6-A～D 回归结论

- **P6-A（并发隔离）**：本轮单用户浏览器会话未构造并发跨用户场景；无任何串结果迹象，既有 Postgres 并发测试未回退（本轮未改相关代码）。
- **P6-B（UNKNOWN/恢复）**：E06 审批、E07 规划、E05 SSE 恢复均正常；历史遗留 1 条 UNKNOWN（非本轮产生，年龄 1 天+）仍可被诊断查询发现，未出现新增 UNKNOWN。
- **P6-C（可观测性）**：Actuator 安全姿态正确（health/info 仅暴露）；I-1 修复消除了诊断噪音；MDC 关联字段在日志实证。
- **P6-D（RAG 检索与引用）**：Q03 三阶段证据全进上下文（S2 名额均衡的生产效果首次在真实浏览器确认）；Q09 双合同身份独立保留；引用按 rank 持久化、刷新一致；Q02 已知限制如实复现（未翻案）。**未发现 P6-D 新回归。**

## E. 自动化与 CI

- 后端定向：`AgentActuatorSecurityTest` 2/2 通过（含 I-1 的 404 断言）。
- 前端：`pnpm lint` 0 errors（75 既有 warnings）/ `pnpm typecheck` 通过 / `pnpm test` 51 文件 393 全通过（CSS 修复回归）。
- 未执行完整后端 verify：本轮后端改动为异常映射+枚举新增，定向测试覆盖；完整 verify 由最终 SHA 的 push CI 承担。最终 SHA 的 CI 链接在本报告末尾补记，未确认 CI 前不声称最终 ACCEPTED 的 CI 证据。
- 边界：18 项 opt-in 真实 AI/服务测试未开启；未以 Mock 冒充任何浏览器/模型结论。

## F. 发布门禁

- 已具备：CI 三 Job、备份脚本与同机演练、compose 生产形态、G-1（配置透传历史误判关闭，本轮实机验证与之一致）、404 错误映射、模型发现/能力探针链路。
- 仍阻断（全部环境依赖，需用户）：G-2/G-N02 反代注册限流拓扑、G-TLS 域名证书、G-SECRETS 生产密钥下发、G-FRONTEND-HOSTING 前端托管、G-BACKUP-OPS 调度与异地副本、G-REGRESSION-ITEMS 知情接受。
- G-OBSERVABILITY：NOT_CONNECTED（不阻断首署、阻断就绪结论）。
- **PRODUCTION_DEPLOYMENT_NO_GO**：浏览器 E2E 通过不等于部署门禁关闭；未经授权不更新为 GO。

## G. 最终裁决

**`P7_BUSINESS_E2E_01_ACCEPTED`**——E01–E12 核心浏览器验收全部达到通过标准，两处真实缺陷（I-1/I-2）已最小修复并通过定向回归；无未关闭的本轮阻断缺陷。分层说明：

1. 真实浏览器已通过：E01–E08、E09、E10（诊断的 API/日志部分）、E11 浏览器侧；E12 为只读复核。
2. 仅有自动化/数据库证据：E10 的指标与 UNKNOWN 年龄（Micrometer 未暴露 HTTP）、E08 后端 403 强制（API 直调）、既有 P6-B Postgres 故障测试（未重跑）。
3. 受模型/环境阻断（NOT_EXECUTED）：真实 Provider 429 注入、Embedding 停机恢复、过期审批的浏览器级验证；Gemini 未获授权未调用。
4. P6-A～D 回归：未发现真实回归（见 §D）。
5. P0/P1 缺陷：无。两处 P2 已修复，两处 P3 记录在案。
6. 发布前还缺：G-2/G-TLS/G-SECRETS/G-FRONTEND-HOSTING/G-BACKUP-OPS/G-REGRESSION-ITEMS（均为用户环境/授权事项）。
7. 建议：**停止 P7 功能侧修复，进入生产环境门禁准备**（域名/证书/密钥/反代/备份调度由用户提供参数后按 14 步部署流程执行）。

## 补记（提交后填写）

- 代码+证据提交 SHA：`<待提交后填写>`
- CI run：<推送后填写>
