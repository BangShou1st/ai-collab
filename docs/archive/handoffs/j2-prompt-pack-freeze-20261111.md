# J2-PromptPack · Phase 0 冻结（2026-11-11）

> 本文件是 **W2 / J2 的施工契约**：映射表、字节口径、回退目标、追溯落点、文件归属都冻结于此。
> 施工方（S1–S5）只按本文件实现，不得自行扩大纳入范围；发现本文件与代码事实冲突时先停下并在
> 会话内报给编排 Agent。
>
> 基线：`dev @ 7c52d961`（P0 已交付、CI 全绿）。设计依据：
> [`docs/design/python-ai-capability-plane-2026-11-11.md`](../../design/python-ai-capability-plane-2026-11-11.md)
> §1.4 / §4 / §5.1 / §5.2 / §5.3 / §7.2 / §9 / §10。

---

## 1. 取证方法（为什么这份表可信）

1. **不抄文档、不抄常量**：在改动前的代码上跑了一次真实调用，把出站正文按 UTF-8/LF 落盘。
   - `KnowledgePromptProvider.systemPrompt()` / `buildUserPayload(...)`；
   - 六个 `com.shitulelv.aicollab.agent.domain.model.builtin.*Scenario` 实例的
     `instruction()` / `outputContract()`。
   - 导出物已在 `ai-collab-backend/src/test/resources/promptpack/golden/`（17 个文件），
     口径与生成方式见该目录 `README.md`。
2. **模板从 golden 反推而非重打**：`knowledge.system` / `knowledge.user` 的模板是
   把 golden 里的动态部分替换成占位符得到的，并在生成脚本里当场校验「把占位符换回去 == golden」
   逐字节成立（3/3 user 用例 + system 用例）。
3. 因此 §3 的模板与 §4 的 sha256/chars 都可被两件事独立复核：
   Python 侧重算 manifest；Java 侧用 golden 断言渲染结果。

### 1.1 取证发现（两处，都影响设计）

| # | 发现 | 处置 |
| --- | --- | --- |
| F1 | `KnowledgePromptProvider.systemPrompt()` = `TimeContext.beijingTimeContext()`（**自带一个换行**）+ `"\n\n"` + 正文，即时间行与正文之间是**三个换行**；P0 落地的 `knowledge.system@2026-11-11.1` 写的是 `【本次调用上下文】/locale/docScope` 头，与线上正文**不同**，无法满足 J2 的逐字节退出条件 | 新增 `knowledge.system@2026-11-11.2`（`{{ timeContext }}` + 正文），**删除** `@2026-11-11.1`（仅存在于 dev 一天、从未部署，版本不可原地改写） |
| F2 | 六个 Scenario 的正文（Java 文本块）**自身以 `\n` 结尾**；PromptPack 原口径「以且仅以一个换行结尾」无法表达 | 契约**宽松化**一处：正文 = 文件去掉末尾唯一一个换行，**允许**正文自身以换行结尾（文件则以两个换行结尾）。不新增字段、不改既有资产语义；`knowledge.system` / `knowledge.user` 不受影响 |

---

## 2. 纳入 / 排除清单（全仓 Prompt 取证结果）

### 2.1 纳入 J2（本次删除 Java 正文）

| # | 来源文件 | 消费方（在线路径） | 目标 key | 纳入 |
| --- | --- | --- | --- | --- |
| 1 | `knowledge/domain/service/KnowledgePromptProvider.java:41-63`（`SYSTEM_PROMPT_TEMPLATE`） | `KnowledgeQuestionApplicationService:128`、`KnowledgeStreamQuestionService:160` → `ChatCompletionCommand.systemPrompt` | `knowledge.system` | 是 |
| 2 | 同文件 `:66-68`（`TRUST_BOUNDARY_FOOTER`）+ `buildUserPayload` 的固定骨架 | 同上 → `ChatCompletionCommand.userPrompt` | `knowledge.user` | 是 |
| 3 | `agent/domain/model/builtin/ProjectResearchScenario.java` `INSTRUCTION` / `OUTPUT_CONTRACT` | `AgentModelMessageComposer:682/706` → 每轮 Agent system prompt | `scenario.project_research.{instruction,output_contract}` | 是 |
| 4 | `WeeklyReportScenario` 同名字段 | 同上 | `scenario.weekly_report.*` | 是 |
| 5 | `ProjectHealthScenario` 同名字段 | 同上 | `scenario.project_health.*` | 是 |
| 6 | `MeetingToTasksScenario` 同名字段 | 同上 | `scenario.meeting_to_tasks.*` | 是 |
| 7 | `IterationPlanningScenario` 同名字段 | 同上 | `scenario.iteration_planning.*` | 是 |
| 8 | `DeliveryReadinessScenario` 同名字段 | 同上 | `scenario.delivery_readiness.*` | 是 |

合计 **14 个 key 条目**。`KnowledgePromptProvider` 本类、六个 Scenario 的两个常量字段全部删除。

### 2.2 明确排除（不得顺手删、不得迁）

| 对象 | 位置 | 理由 |
| --- | --- | --- |
| `NeuralModelReranker` 的打分 Prompt 与分数解析 | `retrieval/**/NeuralModelReranker.java` | J3/P1 才有 rerank 端点（设计 §5.1 前置条件未满足） |
| Agent 单轮 decide Prompt | 现状无独立 Prompt 常量（决策在模型原生 tool calling 内） | P2 由 Python 接管，本波不动 |
| Planning 骨架/详情/补丁 Prompt | `planning/application/**`、`PlanningModelProperties` 相关 | P3（J5） |
| Memory / Dreaming 提取 Prompt | `agent/**/memory/**` | P3（J5） |
| `AgentModelMessageComposer` 内的框架性指令（身份行、"参考步骤"、"通用回答结构约定"、子研究回传协议、Legacy 只读说明） | `AgentModelMessageComposer:666-760` | 不是 Scenario 资产，含运行时数据插值；迁走会把 Java 的上下文装配语义搬到 Python（超出 J2） |
| `TimeContext` 时间行格式本身 | `common/ai/TimeContext.java` | 契约只搬正文，时间由 Java 生成并通过 `{{ timeContext }}` 传入 |
| 纯 eval / 测试专用 Prompt（`RagV2QualityEvaluationPostgresTest.v1FallbackSystemPrompt()` 等） | `src/test/**` | 测试对照基线，不是生产副本 |
| 前端任何文案 | `ai-collab-frontend/**` | 本波不改前端 |

---

## 3. 冻结的 PromptPack 资产（正文口径）

文件位置：`ai-collab-ai-worker/prompts/<key>/<version>.md`。
正文 = 文件去掉末尾唯一一个换行；文件必须 UTF-8 无 BOM、LF。

| key | version | variables | 正文来源（等价于旧代码） |
| --- | --- | --- | --- |
| `knowledge.system` | `2026-11-11.2` | `timeContext` | `{{ timeContext }}` + `\n\n` + 旧 `SYSTEM_PROMPT_TEMPLATE.strip()`；`timeContext` 的取值就是 `TimeContext.beijingTimeContext()`（**自带一个换行**），替换时**逐字原样**写入 |
| `knowledge.user` | `2026-11-11.1` | `question`,`sources` | `<QUESTION>\\n{{ question }}\\n</QUESTION>\\n\\n<SOURCES>\\n{{ sources }}\\n</SOURCES>` + 信任边界重申段（与旧 `TRUST_BOUNDARY_FOOTER` 逐字一致）；会话上下文前缀**仍由 Java 拼接**（`conversationContext.strip() + "\\n\\n"`，为空则不加） |
| `scenario.<code>.instruction` | `2026-11-11.1` | — | 六个 Scenario 的 `INSTRUCTION` 逐字 |
| `scenario.<code>.output_contract` | `2026-11-11.1` | — | 六个 Scenario 的 `OUTPUT_CONTRACT` 逐字 |

`<code>` ∈ `delivery_readiness` / `iteration_planning` / `meeting_to_tasks` / `project_health` / `project_research` / `weekly_report`。

### 3.1 占位符替换规则（Java 侧唯一合法实现）

- 语法 `{{ name }}`（允许花括号内空白）。
- 替换 = **把 value 原样写入**：不 trim、不转义、不改换行。
- 模板中的每个占位符都必须被提供；**提供了模板里没有的变量视为错误**（防拼写漂移）。
- 替换后仍存在 `{{ name }}` 形态的占位符 → 错误（不静默留空）。
- 安全问题：`question` / `sources` 的值必须先经 `KnowledgePromptText.escapeXmlText`，
  **转义永远在 Java 侧做，模板里不含任何转义逻辑**。

### 3.2 版本选择规则

- PromptPack 允许同一 key 有多个版本（历史版本保留可追溯）。
- Java 取「当前版本」= **按 `(日期, 序号)` 数值比较取最大**，例如
  `2026-11-11.10` **大于** `2026-11-11.2`（不得退化为字符串序）。
- 版本格式非法（不匹配 `^\d{4}-\d{2}-\d{2}\.\d+$`）→ 该条目视为损坏。

---

## 4. 冻结指纹（sha256 = 正文 UTF-8 字节；chars = Unicode 码点）

| key | version | chars | sha256 |
| --- | --- | --- | --- |
| `knowledge.system` | `2026-11-11.2` | 1352 | `5b04499561a3493b29017ca613c67b7d47f091a11fb8abf64e9b4e239d38b936` |
| `knowledge.user` | `2026-11-11.1` | 163 | `721bd080b481fc05f1abf5a9a2cc72d9e05f5a6a62199e068bbf8d65b0ec9230` |
| `scenario.delivery_readiness.instruction` | `2026-11-11.1` | 518 | `ec43599b473250ddd7d1bdd173f368b46973bdd7a8a4eedbd8902b1f8f055a37` |
| `scenario.delivery_readiness.output_contract` | `2026-11-11.1` | 164 | `d928effba818191564441d94d9c76e74e43f3087f857f916841f20ad18102275` |
| `scenario.iteration_planning.instruction` | `2026-11-11.1` | 1203 | `332829024bf9b2dfc08e6ec5a0556e4d39549ec0de8d63bb641ce062d99702a8` |
| `scenario.iteration_planning.output_contract` | `2026-11-11.1` | 57 | `ea27f207479e1a34c97252dc247104963b9c56bc3bbf2eff344e67141b63ff2e` |
| `scenario.meeting_to_tasks.instruction` | `2026-11-11.1` | 239 | `bec30515abeb8400ecdfe6e0a10deb0fc7234415d4870186e27a31233470bb42` |
| `scenario.meeting_to_tasks.output_contract` | `2026-11-11.1` | 58 | `296a4004968c111674c21604216884a290f5162b66c4c9f4241509b170ac260a` |
| `scenario.project_health.instruction` | `2026-11-11.1` | 474 | `c7435aae1941cf35f3ba1cf28ea9d84d5131f561f288d023930ec78c2b23bbbf` |
| `scenario.project_health.output_contract` | `2026-11-11.1` | 177 | `4cae374645d6fe9c6b4f05b2a54f461cec171a81c5e5053e5d57cdacb05102cb` |
| `scenario.project_research.instruction` | `2026-11-11.1` | 236 | `ebfb67e4516973eb7f385159f39d4776f8866b2b4ca14159a0ee3725ab37981c` |
| `scenario.project_research.output_contract` | `2026-11-11.1` | 263 | `76b04c74244d4229701d9342ee9f0afa5e6a4dcc43c59a793661f91558a6cf4c` |
| `scenario.weekly_report.instruction` | `2026-11-11.1` | 212 | `037dd0ea3a5e48006a09b994925f625eb328c2a733ad07ebf7efeddb37f15f13` |
| `scenario.weekly_report.output_contract` | `2026-11-11.1` | 78 | `56fd24fbe5910ce2e76d369b7724374657f97da7ea7f57eecbf297afc3591b1c` |

---

## 5. 回退目标（真实可用，禁止空开关）

`prompt.registry.enabled` 的语义：

| 取值 | Prompt 来源 | 网络 | 与今天的关系 |
| --- | --- | --- | --- |
| `false`（**默认**） | classpath 只读 fallback 包 `ai-collab-backend/src/main/resources/promptpack/` | 零外呼 | 正文与今天逐字节相同（同一份冻结资产导出） |
| `true` | `ai-worker` 的 `GET /v1/prompts/*`，进程内缓存 + 定时刷新 | 仅内部网 | 首次让 Prompt 归属 Python |

- fallback 包**由 PromptPack 生成**：`uv run ai-prompts export-java`，禁止手改；
  `uv run ai-prompts export-java --check` 与 Python 侧 pytest 双向守护漂移。
- 因此 `enabled=false` **不是**"关掉功能"，而是"回到内置的同一份正文"，是可验证的回退：
  一致性用例在两种取值下都必须逐字节通过。
- 发布回滚 = 把 `enabled` 改回 `false` 并重启（无需回滚代码）；代码级彻底回滚仍走回滚发布。

---

## 6. 追溯落点（`promptVersion`）

| 链路 | 落点 | 是否新增迁移 |
| --- | --- | --- |
| Agent Run | `agent_run.model_configuration_snapshot` 增加 `prompts` 键：`{source: BUNDLED\\|REMOTE, manifestFingerprint, keys:{<key>:<version>}}`。该字段是 V51 已存在的 JSONB，语义仍是"本次运行解析出的请求配置快照（诊断用）" | 否 |
| 知识问答 | `knowledge_message` 增加 `prompt_key` / `prompt_version`（可空） | **是（V90）** |

**为什么知识问答需要迁移**：知识问答链路（`knowledge_session` → `knowledge_message` →
`knowledge_citation`）没有任何 JSON/无类型列可承载 Prompt 身份，而 `knowledge_message` 已经
逐条记录"这次换了哪个模型、多久、多少 token"，Prompt 身份属于同一组事实；用日志代替不可查询，
用 `ai_call_log` 又只有规划链路在写。故新增两列（可空、无 CHECK、无回填），是满足
"`promptVersion` 可在调用链追溯"的最小改动。

---

## 7. 失败语义（与设计 §5.3 / P0 §4 对齐）

| 场景 | worker 侧 | Java 侧 |
| --- | --- | --- |
| 未知 key/version | 404 `PROMPT_UNAVAILABLE` | 视为不可用（**不**保留旧值以外的猜测） |
| 资产损坏（sha256/chars/variables 不符） | 503 `PROMPT_UNAVAILABLE` | `AI_PROMPT_UNAVAILABLE` |
| worker 不可达 / 超时 | — | 启动不阻断进程；请求快速失败 `AI_PROMPT_UNAVAILABLE`；刷新失败保留**上次成功版本**并打点告警 |
| 返回正文与 manifest `sha256` 不一致 | — | 拒绝该条目（不写入缓存），`AI_PROMPT_UNAVAILABLE` |

- 启动：`enabled=true` 时尝试拉 manifest + 预取；失败只告警 + 指标，**不让主站起不来**。
- 运行期：每次取用读进程内缓存；缓存为空才失败。绝不"用旧 Prompt 静默降级"以外的猜测——
  保留"上次成功版本"是设计 §5.3 明文允许的唯一例外。
- 指标：`prompt.catalog.refresh{result=success\|failure}`、`prompt.catalog.unavailable{key}`；
  日志**不得**包含 Prompt 正文。

---

## 8. 文件归属（避免并行冲突）

| 施工方 | 独占文件 |
| --- | --- |
| **S1 Python** | `ai-collab-ai-worker/**`（`contract/prompts.py`、`prompts/build.py`、`prompts/README.md`、`tests/**`） |
| **S2 Java 基础设施** | 新增 `common/ai/prompt/**`；`common/exception/ErrorCode.java`；`src/main/resources/application*.yml`（只加 `prompt.registry.*`） |
| **S3 Java 消费方** | `knowledge/domain/service/KnowledgePromptProvider.java`（删除）、`knowledge/application/service/Knowledge*Service.java`、`agent/domain/model/**`（接口默认 + 六个 builtin）、`agent/application/runtime/AgentModelMessageComposer.java`、`AgentRuntimeCoordinator.java`、`AgentModelConfigurationStore.java`、`agent/infrastructure/repository/**`、`knowledge/infrastructure/**` + `V90` 迁移 |
| **S4 测试** | `src/test/java/**`（新增 J2 用例）+ 修订受影响的既有用例 |
| **S5 文档** | `docs/**`、`README.md`、`ai-collab-ai-worker/README.md` |

S1 与 S2 可并行；S3 需要 S2 的 API 面（接口签名已在本文件 §9 冻结），S4 需要 S3 的最终形状。

---

## 9. 冻结的 Java API 面（S2 必须按此实现，S3 按此调用）

```java
package com.shitulelv.aicollab.common.ai.prompt;

/** 一条已校验的 Prompt（模板正文，未渲染）。 */
public record PromptTemplate(String key, String version, String sha256, int chars,
                             String text, List<String> variables) {}

/** Prompt 来源（端口）。domain/application 只依赖本接口。 */
public interface PromptCatalog {
    /** 取当前版本；不可用时抛 BusinessException(AI_PROMPT_UNAVAILABLE)。 */
    PromptTemplate require(String key);
    /** 当前全部条目的 (key -> version)，供追溯记录。 */
    Map<String, String> versions();
    /** 来源标识：BUNDLED / REMOTE。 */
    String source();
    /** manifest 指纹（条目 (key,version,sha256) 的稳定摘要）。 */
    String manifestFingerprint();
}

/** 占位符渲染（纯函数）。 */
public final class PromptRenderer {
    public static String render(PromptTemplate template, Map<String, String> variables) { ... }
}

/** key 常量。 */
public final class PromptKeys {
    public static final String KNOWLEDGE_SYSTEM = "knowledge.system";
    public static final String KNOWLEDGE_USER = "knowledge.user";
    public static String scenarioInstruction(String scenarioCode);
    public static String scenarioOutputContract(String scenarioCode);
    public static List<String> all();
}
```

配置（`@ConfigurationProperties("prompt.registry")`）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `prompt.registry.enabled` | `false` | `true` 才走 worker |
| `prompt.registry.base-url` | `http://ai-worker-api:9100` | 仅内部网；不得配公网 |
| `prompt.registry.refresh-interval` | `PT5M` | 运行期刷新间隔；`0`/负值 = 不刷新 |
| `prompt.registry.connect-timeout` | `PT2S` | |
| `prompt.registry.read-timeout` | `PT5S` | |
