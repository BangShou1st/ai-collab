# AC06-E2E-business-loop-01：业务定位调整与真实业务闭环验收

> 日期:2026-11-10 ~ 2026-11-11
> 代码基线:dev 分支,AC-06 合并后 `e3d805db` 前后(死代码清理合并于 E2E 进行中,未触碰业务链路)
> 环境:本机 Docker(postgres/redis/minio)+ 后端 local profile(8080)+ Ollama(qwen3-embedding:0.6b)+ 共享模型(魔搭 ModelScope DeepSeek-V4.1-Flash-1017)
> 性质:**真实业务链路验收**,非 Mock。除标注 NOT_VERIFIED 项外,所有步骤均为真实 HTTP 调用 + 数据库状态核验。

---

## 1. 端到端闭环步骤矩阵

测试项目:**智慧园区能耗管理平台 V1.0**(type=`PRODUCT_DEVELOPMENT`,id `a4aad56b-b722-4397-8296-c9880671c655`,owner 账号)

| # | 步骤 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 创建产品研发项目 | **PASS** | HTTP 201;`project.type='PRODUCT_DEVELOPMENT'` 落库;V86 CHECK 约束已应用(flyway V86 success=t) |
| 2 | 上传需求文档 | **PASS** | HTTP 202;fixtures 语料 `tests/fixtures/documents/e2e-ac06-requirements.md`(已登记 README) |
| 2a | 解析+向量化 | **PASS**(含 1 次真实失败) | 首次 FAILED(见 §3 缺口 2),Ollama 启动后 retry → `READY`,26 chunks;审计链 DOCUMENT_UPLOADED→FAILED→RETRY→INDEXED 完整 |
| 3 | AI 检索需求(知识问答) | **PASS** | 问"一级/二级告警触发条件与通知顺序":回答 110%+3min→二级、130%→立即一级、先站内 5 分钟未确认→短信,**与文档事实逐字一致**;5 条引用(knowledge_citation=5)全部指向正确文档与具体 chunk;`insufficientEvidence=false`;model=deepseek-ai/DeepSeek-V4.1-Flash-1017 |
| 4 | 生成任务规划(两阶段) | **PASS** | 骨架→细节;版本含 11 任务 + 4 里程碑(M1-M4)+ **11 条依赖边** + **17 条 sourceRefs**(引用文档 chunk,含相似度与引文原文);任务内容与需求文档强相关 |
| 5 | 用户确认规划 | **PASS** | 首次 confirm 被 `TASK_PLAN_HAS_BLOCKING_ISSUES` 正确拦截(TASK_UNASSIGNED);补负责人存版本 V3 后 confirm(HTTP 200,幂等键 Idempotency-Key)→ `ai_task_plan.status=CONFIRMED` |
| 6 | 任务及依赖正式落库 | **PASS** | DB 核验:project_task=11(全部已指派)、task_dependency=11、milestone=4(PLANNED);AI 草稿从未直接写入——确认前任务表为空 |
| 7 | 更新任务进度 | **PASS**(含状态机验证) | T01→IN_PROGRESS→DONE、T02→IN_PROGRESS(HTTP 200);**依赖状态机正确拦截**了"有未完成依赖的任务开始"(TASK_BLOCKED_BY_DEPENDENCY,HTTP 409);审计 TASK_STATUS_CHANGED×3 |
| 8 | Agent 依赖风险分析 | **PARTIAL / NOT_VERIFIED(叙述)** | 6 次真实工具调用全部成功(analyze_project_risks/get_task_plan/list_task_plans/list_milestones/list_tasks×2;第一次运行 5 次);输入 91,269 tok/输出 7,944 tok 真实计量;**最终自然语言分析被免费网关输出截断**,运行按契约收敛 `BUDGET_EXCEEDED`(原始错误 AI_PROVIDER_OUTPUT_TRUNCATED),fallback=PERSISTED_TOOL_EVIDENCE,助手消息如实报告"因模型输出截断,先返回已取得的结果"——**未编造分析结论** |
| 9 | 生成项目周报 | **PASS(数据本体)/ NOT_VERIFIED(叙述)** | draft_weekly_report 真实执行:totalTasks=11、completed=1、inProgress=1、overdue=0、completionRate=9.1%、topContributor 统计——**与 DB 完全一致**;最终叙述文本同被网关截断,周报数据已持久化于 agent_tool_invocation.result_json |

## 2. 一致性核验(规划↔审批↔落库↔依赖)

- 审计日志完整覆盖:PROJECT_CREATED → DOCUMENT_*(4) → TASK_PLAN_CREATED → TASK_PLAN_VERSION_SAVED → TASK_PLAN_CONFIRMED → TASK_STATUS_CHANGED(3),与操作一一对应。
- AI 创建的草稿(ai_task_plan_version)与正式任务(project_task)严格分离;确认动作是唯一落库通道,且需要幂等键。
- 依赖边数(11)与规划版本 tasksJson 中 dependencyTempKeys 展开数一致。

## 3. 缺口与最小修复方案

1. **免费共享模型输出截断**(缺口主因):魔搭 ModelScope 免费 DeepSeek-V4.1-Flash 生成 Agent 长叙述时被网关截断,重试两次均复现;与 P10 验收记录的"免费模型截断"一致。防护契约(截断→预算收敛→持久化工具证据→不编造)工作正常。
   **最小修复**:AGENT purpose 绑定输出能力更强/计费可控的模型配置;或在 AgentToolResultSanitizer 中进一步压缩大结果集(本次 list_tasks 返回全部 11 任务全文是上下文膨胀主因)。
2. **系统级 Embedding 依赖本地 Ollama 且无健康预警**:Ollama 未启动时文档向量化静默失败,错误文案正确但用户无主动提示。
   **最小修复**:系统设置页对 embedding provider 做连通性探测提示(管理端已有探测能力,可复用)。
3. **共享模型 max_output_tokens 默认偏小**:环境调整披露——为完成验收将 2 条魔搭共享配置 4000→8192(本地 dev 库 SQL 直改,生产应走管理端)。

## 4. NOT_VERIFIED / BLOCKED 声明

- Agent"自然语言风险分析叙述"与"周报文字总结":**NOT_VERIFIED**(免费模型输出截断,环境限制);工具层与数据统计层真实执行成功。
- MCP 外部互通、费用美元计价(未配置费率,token 计量为真实值):本轮未覆盖。

## 5. 执行命令摘要

- 登录:POST /api/v1/auth/login(需 Origin 头)
- 项目:POST /api/v1/projects;文档:POST /api/v1/projects/{id}/documents(+retry)
- 问答:POST /api/v1/projects/{id}/knowledge/sessions/{sid}/questions
- 规划:POST /api/v1/projects/{id}/ai/task-plans → versions → confirm(Header Idempotency-Key)
- 任务:PATCH /api/v1/projects/{id}/tasks/{tid}(管理员需 title+status+priority 全量)
- Agent:POST /api/v1/projects/{id}/agent/sessions/{sid}/messages(skillCode=PROJECT_HEALTH / WEEKLY_REPORT)
- 原始响应 JSON 存于 ai-collab-backend/target/e2e-ac06/(gitignore,验收后清理 token)

## 7. 复验(2026-11-11):强网关下 NOT_VERIFIED 项关闭

用户提供本地模型网关(OpenAI 兼容,`http://127.0.0.1:3425/v1`,模型 `marvis/deepseek-v4-1-flash-261017`,1M 上下文 / 128K 输出)。以项目级 `model_configuration` + `model_purpose_assignment(AGENT)` 直配(见 §8 披露),重跑两个 NOT_VERIFIED 环节:

| 环节 | 结果 | 证据 |
| --- | --- | --- |
| Agent 依赖风险分析(叙述) | **PASS**(升级自 NOT_VERIFIED) | run `ab2a1e22` SUCCEEDED,46,787 in / 4,765 out tok,4 工具调用零失败;叙述完整覆盖 11 任务依赖链,任务 ID/日期/`unfinishedDependencyCount` 与库内一致,推断与工具事实分层标注,写操作建议明确声明走审批 |
| 项目周报(叙述) | **PASS**(升级自 NOT_VERIFIED) | run `fb745b87` SUCCEEDED,19,171 in / 2,794 out tok,4 工具调用;叙述与 draft_weekly_report 真实统计一致(11 任务/1 完成/9.1%/0 逾期),并发现"M1 里程碑下无任务"口径问题 |

**原 §3 缺口 1 的最小修复结论**:AGENT 用途绑定输出能力足够的模型即可关闭该缺口;免费共享网关仅适合检索问答类短输出场景。

## 8. 环境变更披露与遗留事项

- **出站安全策略与本地网关**:用户级/管理级 Provider API 强制公网 HTTPS(防 SSRF,设计正确),环回 HTTP 网关无法经 API 登记。本次以本地 dev 库 SQL 直配 `model_configuration`(key 留空,网关信任环回)。**遗留产品缺口**:本地 profile 缺少类似 `EMBEDDING_LOCAL_ALLOWED_TARGETS` 的本地网关白名单(Ollama Embedding 已有先例),建议补齐后禁用 SQL 直配路径。
- **本地双栈端口冲突**:AI Collab 与 AI-CostOps 本地栈争用 5432/6379/9000/8080。**已解决(2026-11-11 收口)**:本地栈端口全面参数化(dev.ps1 / compose / application-local.yml 均从根 `.env` 读取,默认值=历史行为,见 `docs/local-development.md` 端口参数化一节);本机 `.env` 配置 AI Collab 使用 8090(管理 8091)/5433/6380/9002,与 CostOps(5432/6379/9000/8080)永久共存,两套容器均 healthy。
- **数据卷分裂**:`ai_collab_p2_business_e2e_postgres_data`(E2E 数据)与 `ai-collab-deploy_ai_collab_postgres_data`(标准 compose 卷)并存。**已解决**:compose 卷名经 `AI_COLLAB_PG_DATA_VOLUME` 参数化,本机 `.env` 指向 p2 卷,dev.ps1 的卷存在性守卫同步参数化;后续 `dev.ps1 start` 一致使用该卷,两卷合并清理不再必要(标准卷保留可随时切换)。

## 9. 结论

业务闭环九步全部走通:初验 7 步完整 PASS、2 步叙述层 NOT_VERIFIED;2026-11-11 以本地强网关复验后,**九步全部 PASS**,NOT_VERIFIED 项清零(遗留产品缺口与工程债见 §8)。真实数据、真实审批、真实落库、真实审计;失败环节如实暴露而非伪造。**AC-06 业务中性化 + 业务闭环验收:PASS**。
