# Agent MCP 收敛、测试限流与仓库 README 设计

## 1. 背景与问题证据

用户要求 Agent 读取 GitHub 仓库 `BangShou1st/ai-collab` 根目录并列出主要文件和目录。最近一次本地运行 `b1e6a252-709d-4eec-832b-ed34839ec9cd` 的非敏感运行元数据显示：

- MCP 连接和六次 `get_file_contents` 调用均成功；
- 调用路径依次为 `/`、`ai-collab-backend`、`ai-collab-frontend`、`ai-collab-deploy`、`docs`、`scripts`；
- 模型轮次输入 Token 从 1,452 增长至 12,692，累计 44,528；
- 最终 `steps_used=12/max_steps=12`，`tool_calls_used=6/max_tool_calls=8`，状态为 `BUDGET_EXCEEDED`。

根因不是 MCP 连接失败，而是默认 `PROJECT_RESEARCH` Skill 的研究型输出要求比用户的窄目标更宽，模型在取得根目录清单后继续遍历子目录；运行时未实际执行 `maxModelTurns`、`maxToolCalls` 和 `maxToolCallsPerTurn`，也缺少“已有成功工具结果后，用剩余预算完成最终回答”的确定性收敛阶段。

限流方面，知识问答当前硬编码为每用户每小时 30 次，无法配置；AI 规划配置默认值为 10，但服务类兜底值为 2。两者不利于本地连续测试且配置语义不一致。

## 2. 目标

1. 用户只要求仓库根目录时，Agent 在获得足够的 MCP 结果后直接回答，不递归遍历未请求的子目录。
2. 任意 MCP/内置只读工具场景在接近预算边界且已有成功结果时，保留一次无工具的最终汇总机会。
3. 真正执行模型轮次、工具总数和单轮工具数限制，避免定义存在但运行时未使用。
4. 知识问答和 AI 规划的每用户小时限制均可由环境变量配置，仓库默认值统一为 60，便于测试。
5. 新增中文为主的根目录 `README.md`，准确展示仓库当前能力和使用方式。

## 3. 非目标

- 不放宽 MCP HTTPS、host allowlist、DNS/SSRF、只读三重白名单、Schema Hash、超时、响应大小和结果清洗边界。
- 不把单次 Agent Run 的步骤或工具预算提高到 60；每小时测试次数与单次防循环预算是不同控制层。
- 不新增 GitHub 专用业务接口，不把通用 Agent Runtime 与单一 MCP 供应商耦合。
- 不自动执行 MCP 写工具，不绕过人工审批。
- 不修改 V1–V30 已提交迁移。

## 4. Agent 收敛设计

### 4.1 范围约束提示

Agent 系统提示增加通用范围规则：

- 严格遵守用户要求的查询深度和范围；
- 用户只要求根目录、当前层或列表时，不读取子目录或文件正文；
- 已有结果足以回答目标时立即输出最终答案；
- 不为满足 Skill 的通用输出模板而扩大用户目标。

该提示是第一层引导，不作为唯一防线。

### 4.2 持久化计数与有效限制

运行时以 `agent_step` 的持久化事实计算：

- 模型轮次数：`MODEL_TURN` 数量；
- 已完成工具数：`TOOL_CALL_COMPLETED` 数量；
- 是否已有成功工具结果：存在 `TOOL_CALL_COMPLETED` 且 `reason=TOOL_SUCCESS`。

每轮调用前同时检查：

- Run 持久化的 `maxSteps`、`maxToolCalls`、输入/输出 Token；
- 当前 Skill 的 `maxModelTurns`、`maxToolCallsPerTurn`。

模型返回超过单轮工具数时，不执行超额调用；运行以稳定预算错误结束，不能部分执行后静默忽略。

### 4.3 强制最终汇总

满足以下条件时进入 finalization：

1. 已有至少一个成功工具结果；
2. 继续暴露工具可能耗尽步骤、模型轮次或工具调用预算，或者当前已到工具预算边界；
3. 仍有一次模型调用的步骤和 Token 空间。

finalization 调用复用已持久化的工具历史，但工具定义传空列表，并附加系统指令：只能基于现有结果回答用户原始目标，不得请求新工具，不得扩大范围，不足信息必须明确说明。

- 返回非空文本：保存最终消息与 `RUN_SUCCEEDED`；
- 仍返回工具调用或空响应：保存稳定错误并结束，不能重新排队；
- 没有成功工具结果：保持现有预算终止语义，不伪造答案。

### 4.4 正常工具轮次

普通轮次继续使用现有跨 Tick 工具消息恢复、参数 Schema、Skill 白名单、角色策略、Loop Guard、审批和取消逻辑。每次工具执行后重新读取最新 Run/step 事实；只有仍有继续空间时才重新排队。

## 5. 每小时测试限流

### 5.1 知识问答

新增配置：

```yaml
knowledge:
  rate-limit-per-user-hour: "${KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR:60}"
```

`KnowledgeRateLimiter` 使用注入值，不再硬编码 30。Redis 和进程内降级计数共享同一限制。合法值必须大于 0。

### 5.2 AI 规划

保留：

```yaml
planning:
  generation-limit-per-user-hour: "${PLANNING_GENERATION_LIMIT_PER_USER_HOUR:60}"
```

将 `application.yml` 默认值与 `PlanningGenerationQuotaService` 的 `@Value` 兜底值统一为 60。`PlanningGenerationRateLimiter` 和成功生成配额继续读取同一个属性；短时防重复点击节流保持不变。

### 5.3 Agent

Agent 当前没有每小时运行次数限制，本次不新增。每小时可以发起多次测试 Run，每个 Run 仍受独立步骤、工具、模型轮次、Token、费用、总时长和审批限制。

## 6. GitHub README

根目录新增中文为主的 `README.md`，结构如下：

1. 项目定位与截图占位规则（没有真实截图时不放假图）；
2. 已实现核心能力；
3. 技术栈与模块架构；
4. 根目录结构；
5. 环境要求和快速启动；
6. `.env` 关键配置，包括两个 60 次限流变量；
7. 后端、前端和 OpenAPI 验证命令；
8. 权限、Agent/MCP 与隐私安全边界；
9. 当前限制和文档导航。

README 以当前源码、V1–V30、40 张业务表、`docs/feature-matrix.md` 和 OpenAPI 为准，不把历史规格或外部环境待验收能力写成已完成。

## 7. 错误与前端行为

本次不新增 HTTP 路径。若新增或调整稳定错误码，必须同步 `ErrorCode`、OpenAPI、TypeScript 中文错误映射和相关测试。强制汇总成功时前端按普通 `RUN_SUCCEEDED` 展示；无法汇总时展示具体错误，不再只给笼统的预算耗尽提示。

## 8. TDD 与验收

### 8.1 先失败的回归测试

1. 已有成功 MCP 工具结果且只剩最终模型预算时，旧实现仍向模型暴露工具；新测试必须先因此失败。
2. finalization 模型返回文本时，Run 保存答案并成功，不执行新工具。
3. finalization 返回工具调用/空响应时，Run 稳定失败且不重新排队。
4. 达到 `maxModelTurns`、`maxToolCalls` 或超过 `maxToolCallsPerTurn` 时不再执行额外工具。
5. 系统提示包含“根目录/当前层不得递归”的通用范围规则。
6. 知识问答配置 60 时前 60 次允许、第 61 次拒绝；自定义小值仍生效，Redis 降级路径语义一致。
7. 规划配置默认和服务兜底均为 60，已有自定义额度测试继续通过。

### 8.2 完整验证

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build

Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
git status --short
```

验收时还需确认 `README.md` 的命令、目录、迁移版本和环境变量均可从仓库事实交叉验证。

## 9. 文件范围

预计修改：

- `ai-collab-backend/src/main/java/.../agent/application/runtime/AgentRuntimeCoordinator.java`
- `ai-collab-backend/src/main/java/.../agent/application/AgentWorker.java`（仅当预算入口需协调）
- `ai-collab-backend/src/main/java/.../agent/domain/policy/AgentLoopGuard.java` 或新增聚焦的收敛策略类
- `ai-collab-backend/src/main/java/.../knowledge/application/service/KnowledgeRateLimiter.java`
- `ai-collab-backend/src/main/java/.../planning/application/PlanningGenerationQuotaService.java`
- `ai-collab-backend/src/main/resources/application.yml`
- 对应后端测试
- `README.md`
- 与实际行为相关的权威 docs；若契约未变化则不改 OpenAPI

明确不处理未跟踪的 `机器码.txt`，也不提交 `.env`、ZIP、构建产物或测试报告。
