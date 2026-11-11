# J0-3 AgentSkill → AgentScenario 重命名与路由收敛

- 日期：2026-11-11
- 分支：dev
- 起点提交：726cafa5（J0-1/J0-2 之后）
- 范围：仅后端 Agent 场景模型、注册表、工具授予命名、API DTO 与相关测试/文档；未改 Flyway、前端、J0-4 检索代码。

## 1. 旧名 → 新名映射

| 旧 | 新 |
| --- | --- |
| `AgentSkill` | `AgentScenario` |
| `AgentSkillRegistry` | `AgentScenarioRegistry` |
| `ProjectHealthSkill` | `ProjectHealthScenario` |
| `WeeklyReportSkill` | `WeeklyReportScenario` |
| `MeetingToTasksSkill` | `MeetingToTasksScenario` |
| `IterationPlanningSkill` | `IterationPlanningScenario` |
| `DeliveryReadinessSkill` | `DeliveryReadinessScenario` |
| `ProjectResearchSkill` | `ProjectResearchScenario` |
| `AgentSkillResponse` | `AgentScenarioResponse` |
| `AgentSkillController` | `AgentScenarioController` |
| `AgentSkillRegistryTest` | `AgentScenarioRegistryTest` |
| `AgentSkillToolMappingTest` | `AgentScenarioToolMappingTest` |
| `recommendedRoutes()` | `entryRoutes()` |
| `allowedTools()` | `grantedTools()` |
| `AgentRuntimeLimits.forSkill` | `AgentRuntimeLimits.forScenario` |
| `AgentRuntimeCoordinator.isChildResearchSkill` | `AgentRuntimeCoordinator.isChildResearchScenario` |

新增 `AgentScenarioCategory { SCENARIO, WORKFLOW }`；`AgentScenario` 新增 `category()`（默认 `SCENARIO`）与 `childScope()`（默认 `false`）。`MeetingToTasksScenario`、`IterationPlanningScenario` 标记为 `WORKFLOW`。

文件重命名全部使用 `git mv`，历史保留。所有后端 Java 源中的大写 `Skill` 标识符/注释已改为 `Scenario`；以下兼容面保持不变：

- DB 表/列 `agent_run.skill_code` 与历史值
- API 路径 `/api/v1/projects/{projectId}/agent/skills`
- 请求/响应中的 `code`、运行视图与事件中的 `skillCode`
- 事件枚举 `SKILL_SELECTED` 与字符串值
- `skills` 变量名与 API 方法名

## 2. API 兼容

`GET /api/v1/projects/{projectId}/agent/skills` 的响应对象新增字段，既有字段不删除、不改名：

```json
{
  "code": "MEETING_TO_TASKS",
  "displayName": "会议纪要转任务",
  "description": "...",
  "entryRoutes": ["DOCUMENT_DETAIL", "TASK_BOARD", "AGENT"],
  "recommendedRoutes": ["DOCUMENT_DETAIL", "TASK_BOARD", "AGENT"],
  "scenario": "MEETING_TO_TASKS",
  "category": "WORKFLOW",
  "inputSchema": { "...": "..." }
}
```

- `code`：保持原值，前端既有读取逻辑不变。
- `entryRoutes`：新名称。
- `recommendedRoutes`：`entryRoutes` 的兼容别名，值完全相同，标注 `@Deprecated`，后续版本移除。
- `scenario`：`code` 的同值别名。
- `category`：`SCENARIO` 或 `WORKFLOW`。
- `inputSchema` 不变。
- 未修改前端；前端继续读取 `recommendedRoutes` 可正常工作。
- 错误码常量 `AGENT_SKILL_NOT_FOUND` / `AGENT_SKILL_NOT_ALLOWED` 等保持原值；仅错误文案中的术语改为 Scenario。

## 3. 行为变化

1. **移除 goal 关键词隐式路由**
   - 删除 `GOAL_KEYWORD_SKILLS` 及 `select` 中的关键词分支。
   - `select` 签名由 `select(explicitCode, userGoal, page)` 改为 `select(explicitCode, page)`。
   - “生成周报”“创建任务”“规划下一个迭代”“从会议纪要提取任务”“检查交付就绪度”等目标文本，在没有显式 `skillCode` 且无页面上下文时不再隐式进入写工作流，统一兜底 `PROJECT_RESEARCH`（只读）。
   - 写工作流（`MEETING_TO_TASKS`、`ITERATION_PLANNING`）必须由用户/调用方显式选择 code 才能进入。

2. **移除 DOCUMENT → MEETING_TO_TASKS 硬映射**
   - `DOCUMENT_DETAIL` 等文档路由现在落到兜底 `PROJECT_RESEARCH`，不再自动进入“会议纪要转任务”写场景。

3. **保留的确定性 route 推断**
   - `TASK` / `MILESTONE` / `PLANNING` → `ITERATION_PLANNING`
   - `DASHBOARD` → `PROJECT_HEALTH`
   - 其他 route、无 page、无显式 code → `PROJECT_RESEARCH`

4. **工具语义命名**
   - `grantedTools()` 是“追加授予”而不是白名单：主运行仍可使用基础只读集合，只读工具只能加不能减；写工具必须显式授予，并继续走审批与角色/深度校验。
   - `AgentToolRegistry`、`AgentToolCallExecutor` 的有效集合运算逻辑未改变，仅统一命名与注释。

5. **提示词与文案术语同步**
   - 内置场景指令、Composer 系统提示、工具说明与错误文案中的 Skill 统一改为 Scenario；提示结构与约束语义未改。
   - 事件名 `SKILL_SELECTED`、字段 `skillCode`、API 路径 `/agent/skills` 保持原样。

## 4. childScope 缺陷修复

原实现：

```java
private interface ChildResearchSkillViewMarker extends AgentSkill {}
public static boolean isChildResearchSkill(AgentSkill skill) {
    return skill instanceof AgentRuntimeCoordinator ChildResearchSkillViewMarker
            || "CHILD_DOCUMENT_RESEARCH".equals(skill.code());
}
```

局部 record 从未实现该 marker，`instanceof` 永远为 false，实际身份判定退化为比较 code 字符串。

现实现：

- 受限子场景 record 覆盖 `grantedTools()` / `entryRoutes()`，并显式覆盖 `childScope() { return true; }`。
- `isChildResearchScenario(AgentScenario scenario)` 仅返回 `scenario != null && scenario.childScope()`。
- 删除 marker 接口与 code 字符串兜底；`code()` 仍返回 `CHILD_DOCUMENT_RESEARCH`，仅用于观测/事件，不再参与身份判定。
- `AgentToolRegistry`、`AgentToolCallExecutor`、`AgentModelMessageComposer` 统一通过 `childScope` 识别受限子运行。
- 负向测试覆盖：code 为 `CHILD_DOCUMENT_RESEARCH` 但 `childScope=false` 的伪造场景不会被识别。

## 5. 测试

新增/重写：

- `AgentScenarioRegistryTest`：显式 code 优先且大小写不敏感；route 映射；`DOCUMENT_DETAIL` 兜底研究；旧关键词目标（提案、周报、创建任务、迭代、会议、交付）全部兜底研究；六个场景注册、code 唯一、`grantedTools` 非空、WORKFLOW/SCENARIO 分类、内置场景 `childScope=false`。
- `AgentScenarioResponseTest`：`code` 保留，`scenario` 同值，`category` 正确，`entryRoutes` 与 `recommendedRoutes` 别名同值，JSON 序列化字段存在。
- `AgentScenarioToolMappingTest`：按新类型/方法名验证授予工具均存在于真实工具注册表。
- `AgentContextReliabilityD1D8RegressionTest`：子运行 mock 改为按 depth 设置 `childScope()`，不再依赖 code 字符串。
- `AgentDelegationPostgresTest`：受限子场景显式 `childScope=true`；新增同 code 但 `childScope=false` 的负向用例。

### 验证命令与结果

```bash
cd ai-collab-backend && bash ./mvnw -B -ntp -o -DskipTests compile
# BUILD SUCCESS（777 个主源文件）

cd ai-collab-backend && bash ./mvnw -B -ntp -o \
  -Dtest=AgentScenarioRegistryTest,AgentScenarioToolMappingTest,AgentScenarioResponseTest,\
AgentToolRegistryDepthTest,AgentContextReliabilityD1D8RegressionTest,\
AgentModelMessageComposerV2Test,AgentRuntimeCoordinatorTest,\
AgentRunServiceRetryTest,AgentRunRestoreTest -DfailIfNoTests=false test
# Tests run: 124, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS

git diff --check
# 无输出（通过）
```

审计结果：

- `AgentSkill` / `ChildResearchSkillViewMarker` / `GOAL_KEYWORD_SKILLS` / `forSkill` / `isChildResearchSkill` 在 `ai-collab-backend/src` 均为零命中。
- 后端 Java 源中大写 `Skill` 零命中。
- `recommendedRoutes` 在主源码中仅剩 DTO 的兼容别名与注释。
- `allowedTools` 仅剩 planning 与 MCP 连接白名单等无关代码。

`Docker/Testcontainers` 集成测试本轮未运行（标记 NOT_VERIFIED，包括新增负向用例所在的 `AgentDelegationPostgresTest`），仅保证测试源码可编译。

## 6. 回滚

- 回滚方式：`git revert <J0-3 提交>` 或反向应用本次改动。
- 无数据库变更（未新增/修改 Flyway 迁移），无列名/历史值变更。
- API 路径与 `code`、`skillCode`、`SKILL_SELECTED` 均未改变；回滚不会造成前端或历史数据不兼容。
- 行为回滚点：关键词隐式路由与 `DOCUMENT → MEETING_TO_TASKS` 硬映射会随回滚恢复；`childScope` 显式标记会回退为旧的 code 字符串判定。
