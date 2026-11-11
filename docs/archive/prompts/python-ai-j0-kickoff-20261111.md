# 开工提示词：Java 侧 J0 地基批次

> 用法：新会话第一条消息整段粘贴。它自带边界、事实基线与验收标准，不依赖上一轮对话。
> 生成日期：2026-11-11 ｜ 对应设计：[Python AI 能力平面 V2.0](../../design/python-ai-capability-plane-2026-11-11.md)

---

## 你的任务

你在 `E:\project\ai-collab` 仓库，执行 **J0 地基批次**：为后续 Python 认知面迁移扫清地基。**本批次不引入 Python、不写 Python 代码、不改前端行为。**

J0 只做四件事，按顺序一次一件：

| 步骤 | 目标 | 一句话判据 |
| --- | --- | --- |
| **J0-1** | 统一 Token 计量 | 全仓库只剩一处"把文本折成 token"的实现 |
| **J0-2** | 取消 256K 绝对顶 | 触发线只由模型档案与比例决定 |
| **J0-3** | `Skill → Scenario` 改名与去关键词路由 | 类型名与语义一致，隐式关键词选择消失 |
| **J0-4** | 统一检索门面 | 四个调用方只依赖一个入口，缺 Bean 时启动失败而非静默降级 |

## 开始前必须做（不要跳过，也不要读全仓库）

1. 完整读 `AGENTS.md`（仓库硬规则，优先于本提示词）。
2. 读设计文档 `docs/design/python-ai-capability-plane-2026-11-11.md` 的 §1、§2、§5、§9。
3. 读审计清单 `docs/archive/prompts/python-ai-architecture-review-prompt-20261111.md` 的 §2.1、§2.2、§2.6。
4. 用 `git log -1` 确认 HEAD；基线是 `dev @ 61be76e2`，迁移最新 V89。若 HEAD 已前进，先确认这四步是否已经有人做过。

## 已核实的事实基线（直接可用，但动手前复核一次）

**环境**

- Java 21，Maven 3.9.16，离线依赖已就绪。
- `cd ai-collab-backend && bash ./mvnw -B -ntp -o -DskipTests compile` 在基线可成功（exit 0，约 90 秒）。
- 完整 `verify` 含 Testcontainers：**若 Docker 不可用，不要谎报全绿**，跑定向单测 + 编译，并在报告里写 `NOT_VERIFIED: 集成测试未执行（Docker 不可用）`。

**计量现状（J0-1 的关键）**

- 已有抽象：`common/ai/token/TokenCount.java`（`count/source/basis/estimated`）、`TokenCountSource`（`NATIVE/TOKENIZER/ESTIMATED`）、`TokenCounter` 接口、`ModelAwareTokenCounter` 实现。
- 重复实现：`agent/domain/model/TokenEstimator.java`（CJK 系数法）、`planning/domain/PlanningTokenEstimator.java`（同一公式复制）。
- 字面估算：`AgentModelMessageComposer.java:236`（`availableInputTokens * 3L` 折字符）、`AgentContextSummarizer.java:355/464/983`（`chars/3`）。
- 持久化：`AgentRunEventRecorder.java:1700-1715` 的 `usageBasis(...)` 只写 `PROVIDER/ESTIMATED/UNKNOWN`，由"有没有 provider tokens"推导；DB CHECK 也只有这三值。
- **结论：优先扩展现有 `TokenCount/TokenCountSource` 并在应用层做映射，不要新建平行类型 `TokenMeasurement`；默认不新增 Flyway 迁移。** 只有当你确认必须持久化 `TOKENIZER` 时才新增 V90 扩 CHECK，并说明理由。

**窗口现状（J0-2）**

- `AgentContextBudget.java:33`：`SOFT_COMPACTION_TRIGGER_TOKENS = 256_000`；`:18-22` 注释承认口径不一致。
- 已有 `ModelWindowResolver`（`agent/application/runtime/`）与 V89 `ai_model_profile`；解析顺序为档案 → `window-overrides` → 未知。

**场景现状（J0-3）**

- `AgentSkill` 接口 7 个职责：`code/displayName/description/recommendedRoutes/allowedTools/inputSchema/allowWriteTools/allowExternalTools/instruction/outputContract/coreActionTools`。
- 关键词表：`AgentSkillRegistry.java:25-74`，`select()` 在 `:113-141`；`"规划"/"迭代"` 重复且后者不可达；`"创建"/"新建"/"添加"/"任务"` 过宽；`DOCUMENT → MEETING_TO_TASKS` 硬映射在 `:129-137`。
- 工具集合已语义漂移：`AgentToolRegistry.java:113-119` 主运行 = `BASE_READ_ONLY_TOOLS ∪ skill.allowedTools() ∪ 澄清工具`，即 `allowedTools` 对只读**只能加不能减**，对写工具是必需授权（`AgentToolCallExecutor.java:558`）。
- 已有隐藏缺陷：`AgentRuntimeCoordinator.java:853` 的 `skill instanceof AgentRuntimeCoordinator ChildResearchSkillViewMarker` 是模式变量写法，恒为 false，实际靠字符串 `CHILD_DOCUMENT_RESEARCH` 兜底。
- 前端只有 3 个文件引用 `skillCode`：`ai-collab-frontend/src/modules/agent/{agent-api.ts,types.ts,use-agent-workspace.ts}`。

**检索现状（J0-4）**

- 调用方：`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService`、`KnowledgeSearchAgentTool`、规划链路（`TaskPlanContextAssembler` 等），分别直接依赖 `DocumentSearchService` / `RetrievalPipeline` / `QueryRouter`。
- `KnowledgeSearchAgentTool.java:39-42` 可选注入、`:149-153` 静默降级。
- `DocumentSearchService.java:31-43、147-173` 用中文正则解析来源范围。

## 每步的完成定义

### J0-1 统一计量

1. 只保留一个"文本 → token"的实现位置；`TokenEstimator`、`PlanningTokenEstimator`、所有 `chars/3` 与 `*3L` 折算全部删除或委托。
2. 计量结果必须能表达来源（provider 实际 / 精确 tokenizer / 估算 / 未知），且**估算不得冒充精确**。
3. 压缩触发、Composer 预算、摘要记账、结算四处使用同一口径；同一输入在四处得到同一数值。
4. 为中文、英文、中英混合、工具 Schema JSON、超长文本各加样本级单测，钉住数值。
5. 产出"新旧口径差异报告"：对同一批样本，旧实现与新实现的 token 数差异分布，说明哪些行为会变化。

**禁止**：顺手改压缩策略参数；顺手改预算上限；新增第三方 tokenizer 依赖（除非离线可用且能证明必要性）。

### J0-2 取消 256K

1. 删除 `256_000` 常量及其引用；触发线 = `f(H, 活跃上下文压力, 可删减内容)`，其中 `H = verified_window - reserved_output - safety_margin`。
2. 窗口未知时的行为必须显式：进保守模式 + 可观测告警，不得静默套用某个魔数。
3. 旧 Run 恢复必须按其持久化的策略版本解释，不得用新公式重算历史。
4. 补测试：已知窗口/未知窗口/小窗口（如 8K）/大窗口（如 200K）四类。

### J0-3 Scenario 改名与去关键词

1. `AgentSkill → AgentScenario`、`AgentSkillRegistry → AgentScenarioRegistry`、`allowedTools → grantedTools`、`recommendedRoutes → entryRoutes`；`builtin/*Skill.java` 同步改名。
2. **不改** DB 列 `agent_run.skill_code`、不改历史值、不改 API 路径 `/agent/skills`；响应可加 `scenario`/`category` 别名，不得删除 `code`。
3. 删除关键词表与 `select()` 的关键词分支、删除 `DOCUMENT → MEETING_TO_TASKS` 硬映射；显式 `skillCode` 与页面显式选择保留。
4. 修复 `AgentRuntimeCoordinator.java:853` 的 `instanceof` 缺陷：改为显式标记（如 `Scope` 值对象或 `childScope` 标志），删除 `ChildResearchSkillViewMarker` 与字符串兜底。
5. 更新类注释：`allowedTools` 的"白名单"描述改为"授予"语义，说明只读工具由基础集合提供、写工具必须显式授予。

**禁止**：改数据库迁移；改前端路由与文案（本批次不要求）；顺手删除任何一个 Scenario。

### J0-4 检索门面

1. 新增唯一门面（建议 `ProjectKnowledgeRetrieval`），统一：项目权限、`READY` 状态、撤权过滤、Embedding 快照、候选池、引用身份。
2. 四个调用方改为依赖门面；`KnowledgeSearchAgentTool` 的可选注入与静默降级删除（缺 Bean 即启动失败）。
3. `DocumentSearchService` 的中文来源正则**本批次不删**（它属于 J1，依赖前端文档选择器）；只在门面内标注 `TODO(J1)` 与现有行为边界。

**禁止**：改检索算法与排序；改 pgvector 查询；改引用格式。

## 工作方式

- 分支：直接提交 `dev`（仓库规则允许，禁止 force push、禁止改写历史）。
- 节奏：**一步一提交**，提交信息 `refactor(ai-token): …` / `refactor(agent): …` / `refactor(retrieval): …`，正文用中文写动机与影响面。
- 每步结束先跑 `bash ./mvnw -B -ntp -o -DskipTests compile`；能跑测试就跑与改动直接相关的测试类。
- 不夹带：不重构无关文件、不调整格式、不改依赖版本。
- 不做 J1–J6、不做 Python、不做 MCP、不做记忆。
- 如果某一步你判断**不该做或做不了**，先给证据（代码/测试/依赖），停下来说明，不要硬做。

## 停止条件（遇到就停并报告）

1. 发现同一能力已被别人改过，与本文事实基线冲突。
2. 删除 `TokenEstimator` 会导致 `planning → agent` 出现循环依赖，且无法用 `common` 解决。
3. 改名引发超过 3 个模块的编译错误且不是机械替换（说明抽象本身有问题）。
4. 检索门面需要改动权限语义（`ProjectAccessGuard` 行为）才能成立。
5. 集成测试必须依赖 Docker 而环境不可用——继续用单测推进，但报告里必须标 `NOT_VERIFIED`。

## 交付报告格式（每步一份，简洁）

```
## J0-x 标题
- 改动：文件清单 + 一句话作用
- 为什么：对应设计哪一条
- 验证：命令 + 结果（compile / 单测 / 集成测试状态）
- 行为变化：有/无；若有，列出并说明回滚方式
- 遗留：TODO 与归属批次（J1/J2…）
- 证据等级：IMPLEMENTED / TESTED / LIVE_VERIFIED / NOT_VERIFIED
```

## 红线（违反即回滚）

- 不改历史 Flyway 迁移；不改 DB 列名与历史值。
- 不删除审批、租约、幂等、撤权、密钥、SSE 相关代码。
- 不让估算值冒充 Provider 实际用量；未知就标 `UNKNOWN`。
- 不在日志里输出 Prompt 正文、密钥、Cookie。
- 不提交任何 `*.log`、临时 JSON、本地绝对路径。
