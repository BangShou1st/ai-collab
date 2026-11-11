# J2 交付报告：PromptPack 接入 Java 并删除 Java 内 Prompt 正文（W2）

> 日期：2026-11-11 开工、2026-11-12 交付（跨零点，批次名沿用开工日）
> 基线：`dev @ 7c52d961`（P0 已交付、CI 全绿）→ 本批提交见 §10
> 设计依据：[`docs/design/python-ai-capability-plane-2026-11-11.md`](../../design/python-ai-capability-plane-2026-11-11.md)
> §1.4 / §4 / §5.1 / §5.2 / §5.3 / §7.2 / §9 / §10
> 施工契约（冻结映射与口径）：[`docs/archive/handoffs/j2-prompt-pack-freeze-20261111.md`](../handoffs/j2-prompt-pack-freeze-20261111.md)

---

## 0. 结论摘要

J2 的四个退出条件全部达成，且每一项都有可独立复核的证据：

| 退出条件（设计 §5.2） | 结论 | 证据 |
| --- | --- | --- |
| **同文本一致性**：新旧路径同输入下出站 system/user prompt 逐字节相同 | **达成** | `J2PromptPackConsistencyTest` 7 项，比对的是**改动前从旧实现导出的 golden 夹具**（不是新实现自证），§5.2 |
| **`promptVersion` 可在 Run 中追溯** | **达成** | Agent Run：`agent_run.model_configuration_snapshot.prompts`（复用 V51 JSONB，无新列）；知识问答：`knowledge_message.prompt_key/prompt_version`（V90）；§5.3 |
| **Prompt 不可用快速失败 `AI_PROMPT_UNAVAILABLE`，不静默用旧 Prompt；但保留"上次成功版本"** | **达成** | `J2PromptPackFailFastTest`（同步 + 流式两条真实服务路径，一次模型请求都不发）、`RemotePromptCatalogTest`（12 项，含整批原子性与上次成功版本保留）、§5.4 |
| **`prompt.registry.enabled=false` 是真实回退，不是空开关** | **达成** | 回退目标是**由 PromptPack 生成的只读 fallback 包**（`src/main/resources/promptpack/`），Python 侧 `ai-prompts export-java --check` + `test_java_promptpack_sync.py` 双向防漂移；`remoteAndBundledCatalogsYieldIdenticalPrompts` 断言两种来源产出同一份正文；§6 |
| 旧 Prompt 正文按清单删除 | **达成** | 10 个探针在 `src/main/java` **全部 0 命中**；六个 Scenario 类不再含任何文本块；§5.5 |
| 门禁 | **达成** | Java `mvnw -B -ntp -o verify`：**2657 项，0 失败 / 0 错误 / 61 跳过，BUILD SUCCESS**（28:30）；Python 7 条命令全绿；§5.1 |

**新增**：`PromptCatalog`（端口 + 内置/远端两种实现）、`PromptRenderer`、`KnowledgePromptAssembler`、V90 迁移。
**删除**：`KnowledgePromptProvider`（96 行）与六个 Scenario 的 `instruction`/`outputContract` 正文常量（共约 700 行 → 之后 0 行）。
**零行为变化**：开关默认 `false` 时出站 Prompt 与迁移前逐字节相同；开关打开时正文来自同一份资产。

---

## 1. Prompt 清单与映射（Phase 0 冻结）

取证方法：在**改动前**的代码上跑一次真实调用，把 `KnowledgePromptProvider.systemPrompt()` /
`buildUserPayload(...)` 与六个 `builtin/*Scenario` 的 `instruction()` / `outputContract()`
按 UTF-8/LF 落盘为 golden 夹具（17 个文件，见
[`ai-collab-backend/src/test/resources/promptpack/golden/README.md`](../../../ai-collab-backend/src/test/resources/promptpack/golden/README.md)），
PromptPack 模板由这些字节**反推**并在生成脚本里当场校验「换回去 == golden」。

### 1.1 纳入 J2（14 个 key）

| 目标 key@version | chars | 来源（改前的 Java 位置） | 消费方（在线路径） |
| --- | --- | --- | --- |
| `knowledge.system@2026-11-11.2` | 1352 | `KnowledgePromptProvider.SYSTEM_PROMPT_TEMPLATE` | `KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService` |
| `knowledge.user@2026-11-11.1` | 163 | 同类的 `buildUserPayload` 骨架 + `TRUST_BOUNDARY_FOOTER` | 同上 |
| `scenario.delivery_readiness.instruction@2026-11-11.1` | 518 | `DeliveryReadinessScenario.INSTRUCTION` | `AgentModelMessageComposer.buildSystemPrompt` |
| `scenario.delivery_readiness.output_contract@2026-11-11.1` | 164 | 同类 `OUTPUT_CONTRACT` | 同上 |
| `scenario.iteration_planning.instruction@2026-11-11.1` | 1203 | `IterationPlanningScenario` | 同上 |
| `scenario.iteration_planning.output_contract@2026-11-11.1` | 57 | 同上 | 同上 |
| `scenario.meeting_to_tasks.instruction@2026-11-11.1` | 239 | `MeetingToTasksScenario` | 同上 |
| `scenario.meeting_to_tasks.output_contract@2026-11-11.1` | 58 | 同上 | 同上 |
| `scenario.project_health.instruction@2026-11-11.1` | 474 | `ProjectHealthScenario` | 同上 |
| `scenario.project_health.output_contract@2026-11-11.1` | 177 | 同上 | 同上 |
| `scenario.project_research.instruction@2026-11-11.1` | 236 | `ProjectResearchScenario` | 同上 |
| `scenario.project_research.output_contract@2026-11-11.1` | 263 | 同上 | 同上 |
| `scenario.weekly_report.instruction@2026-11-11.1` | 212 | `WeeklyReportScenario` | 同上 |
| `scenario.weekly_report.output_contract@2026-11-11.1` | 78 | 同上 | 同上 |

完整 sha256/variables 见冻结文档 §4，并由 Python 的
`tests/contract/test_prompt_pack_frozen.py` 硬编码守护（14 条 + 无多余文件）。

### 1.2 明确排除（未动，也不应顺手动）

| 对象 | 理由 |
| --- | --- |
| `NeuralModelReranker` 的打分 Prompt 与分数解析 | J3/P1 才有 rerank 端点（设计 §5.1 前置条件未满足） |
| Planning 骨架/详情/补丁 Prompt（`planning/**`） | P3 / J5 |
| Memory / Dreaming 提取 Prompt（`agent/**/memory/**`） | P3 / J5 |
| `AgentModelMessageComposer` 内的框架性指令（身份行、"参考步骤"、"通用回答结构约定"、子研究回传协议、Legacy 只读说明） | 不是 Scenario 资产，且含运行时数据插值；迁走会把 Java 的上下文装配语义搬到 Python |
| `TimeContext` 时间行格式本身 | 契约只搬正文；时间由 Java 生成并经 `{{ timeContext }}` 传入 |
| 纯 eval / 测试专用 Prompt（`RagV2QualityEvaluationPostgresTest.v1FallbackSystemPrompt()` 等） | 测试对照基线，不是生产副本 |
| 前端任何文案 | 本波不改前端 |

---

## 2. 改动

### 2.1 Python（`ai-collab-ai-worker/`）

| 改动 | 说明 |
| --- | --- |
| PromptPack 资产 | 14 条：新增 `knowledge.system@2026-11-11.2`、`knowledge.user@2026-11-11.1`、十二个 `scenario.*`；**删除** `knowledge.system@2026-11-11.1`（见 §3 F1） |
| 正文口径宽松化一处 | 「正文 = 文件去掉末尾唯一一个换行」原本额外禁止正文以换行结尾；六个 Scenario 正文（Java 文本块）**自身带尾换行**，故放宽。不新增字段、不改既有资产语义（见 §3 F2） |
| `ai-prompts export-java [--check]` | 由 PromptPack 生成/校验 Java 只读 fallback 包（`manifest.json` + `texts/<key>/<version>.md`，字节相同） |
| 测试 +37 | `test_prompt_pack_frozen.py`（冻结指纹 14 条）、`test_prompt_export_java.py`（导出形状/幂等/清多余/四类差异）、`test_java_promptpack_sync.py`（与仓库内 fallback 包逐字节，缺目录即失败不 skip），以及换行口径的边界用例 |

### 2.2 Java（`ai-collab-backend/`）

| 文件/包 | 改动 |
| --- | --- |
| `common/ai/prompt/**`（新增 13 个类/记录） | `PromptCatalog`（端口）、`PromptTemplate`、`PromptRenderer`、`PromptKeys`、`PromptVersion`、`BundledPromptCatalog`、`RemotePromptCatalog`、`PromptCatalogs`、`PromptRegistryProperties`、`PromptCatalogConfiguration`、`PromptCatalogMetrics`、`PromptPackJson`、`PromptAssetSupport` |
| `ErrorCode` | 新增 `AI_PROMPT_UNAVAILABLE(503, "Prompt 资产不可用，请稍后重试")` |
| `application.yml` | 新增 `prompt.registry.{enabled=false, base-url, refresh-interval, connect-timeout, read-timeout}`（带中文注释与环境变量覆盖） |
| `KnowledgePromptAssembler`（新增） | 唯一装配点：时间行注入、XML 转义、会话上下文前缀 |
| `KnowledgePromptProvider` | **删除**（96 行） |
| `KnowledgeQuestionApplicationService` / `KnowledgeStreamQuestionService` | 构造器依赖换成 assembler；出站 Prompt 由其产出；保存时带上 Prompt 身份 |
| `KnowledgePersistenceService` | 新增带 Prompt 身份的重载；半截身份直接拒绝；无模型调用的轮次保持 NULL |
| `KnowledgeMessageEntity` / `KnowledgeMessageMapper` | 两个新列（V90） |
| `AgentScenario` | `instruction()`/`outputContract()` 改为**默认抛错**（正文已不在接口实现里；绕过注册表会立刻失败而不是静默发空指令） |
| 六个 `builtin/*Scenario` | 删除 Prompt 正文常量与两个 override（−698 行） |
| `AgentScenarioRegistry` | 注册时用 `PromptBackedScenario` 包装，从 `scenario.<code>.*` 取用；保留无 Spring 的构造器走内置 fallback 包（测试/eval） |
| `AgentModelConfigurationStore` | 把 PromptPack 身份并入 `agent_run.model_configuration_snapshot.prompts`（复用 V51 JSONB，幂等写入） |
| `V90__knowledge_message_prompt_identity.sql` | `knowledge_message` 增加 `prompt_key` / `prompt_version`（可空、无 CHECK、不回填） |
| 测试 +14、改造 5 | `J2PromptPackConsistencyTest`(7)、`J2PromptPackFailFastTest`(4)、`J2PromptIdentityPersistenceTest`(3)、`RemotePromptCatalogTest`(12) 等；`KnowledgePromptSafetyContractTest` 改为断言 PromptPack 正文的安全契约（11 项断言未变） |

---

## 3. 为什么（三个关键决策）

### F1：为什么新增 `knowledge.system@2026-11-11.2` 并删除 `.1`

Phase 0 取证发现：旧 `systemPrompt()` = `TimeContext.beijingTimeContext()`（**自带一个换行**）
+ `"\n\n"` + 正文，即时间行与正文之间是**三个换行**；而 P0 一天前落的
`knowledge.system@2026-11-11.1` 头部是 `【本次调用上下文】/ locale / docScope` 段，
**与线上正文不同**。J2 的退出条件是"逐字节相同"，所以：

- 不能复用 `@2026-11-11.1`；
- 不能原地改写它（版本不可变，改 Prompt 走代码评审 + 发布）；
- 因此新增 `@2026-11-11.2`（`{{ timeContext }}` + 原正文），并**删除** `.1`——它只存在于 dev
  一天、从未部署，留着只会让"哪个是线上文本"变成需要考古的问题。

`timeContext` 的取值就是 `beijingTimeContext()` 的返回值（含尾换行），替换规则是**逐字原样写入**，
所以渲染结果与旧实现的字符串拼接完全同构。

### F2：为什么放宽"正文不得以换行结尾"

Java 文本块会保留末尾行终止符，六个 Scenario 的正文**自身以 `\n` 结尾**（已用 golden 夹具证实）。
PromptPack 原口径「以且仅以一个换行结尾」会直接拒绝这 12 个模板，导致无法逐字节保真。

放宽只删了一个拒绝分支，语义仍是"文件去掉末尾**唯一一个**换行"：

| 文件 | 正文 | 说明 |
| --- | --- | --- |
| `hello\n` | `hello` | 不变 |
| `hello\n\n` | `hello\n` | 新增允许的形状（六个 Scenario） |
| `\n` | — | 仍拒绝（正文为空） |
| 无尾换行 / BOM / 非 UTF-8 | — | 仍拒绝 |

### F3：为什么知识问答需要 V90，而 Agent Run 不需要

- **Agent Run**：`agent_run.model_configuration_snapshot` 是 V51 就存在的 JSONB，语义上本来就是
  "本次运行解析出的请求配置快照（诊断用）"，加一个 `prompts` 键是**只加字段**，故零迁移。
- **知识问答**：链路只有 `knowledge_session → knowledge_message → knowledge_citation`，
  没有任何 JSON/无类型列；`ai_call_log` 只有规划链路在写；用日志代替不可查询。
  而 `knowledge_message` 本来就已经逐条记录"这一轮换了哪个模型、多久、多少 token"，
  Prompt 身份属于同一组事实。故新增两个**可空**列，是满足"可在调用链追溯"的最小改动，
  且不改变任何既有列的语义、不回填历史行。

---

## 4. 行为变化

| 面 | 变化 | 是否有意 |
| --- | --- | --- |
| 出站 Prompt 正文 | **无变化**（`enabled=false` 默认路径与迁移前逐字节相同；`enabled=true` 是同一份资产） | 是（同文本一致性） |
| Prompt 缺失时的语义 | 迁移前：缺 Bean 即启动失败；迁移后：缺 Prompt → 请求级 `AI_PROMPT_UNAVAILABLE`(503) 快速失败，进程仍存活 | 是（设计 §5.3） |
| 启动 | `enabled=true` 且 worker 不可达时**不阻断主站启动**，只告警 + 指标，AI 功能快速失败 | 是（见 §7 遗留 1） |
| 六个 Scenario | `instruction()`/`outputContract()` 不再由实现类提供；绕过 `AgentScenarioRegistry` 直接调用会抛 `IllegalStateException` | 是（防静默空指令） |
| API/前端 | 无变化（`AgentScenarioResponse` 不含 Prompt 字段；无前端改动） | 是 |
| 数据库 | 新增 V90 两列（可空）；Agent Run 快照多一个 `prompts` 键 | 是 |
| 依赖 | 无新增（复用 `spring-webmvc` 的 `RestClient` + JDK `HttpClient`） | 是 |

---

## 5. 验证（命令 + 实际结果）

### 5.1 门禁

| 命令 | 结果 |
| --- | --- |
| `cd ai-collab-backend && bash ./mvnw -B -ntp -o verify` | **BUILD SUCCESS**，`Tests run: 2657, Failures: 0, Errors: 0, Skipped: 61`，总耗时 **28:30**（含 Testcontainers；Docker 可用，未出现环境抖动，未做隔离复跑） |
| `cd ai-collab-ai-worker && uv sync --frozen` | `Checked 37 packages` |
| `uv run ruff check .` | `All checks passed!` |
| `uv run ruff format --check` | `55 files already formatted` |
| `uv run mypy --strict src` | `Success: no issues found in 20 source files` |
| `uv run pytest -q` | `224 passed, 1 warning`（既有 Starlette 弃用提示） |
| `uv run ai-prompts check` | `prompts OK（14 条）` |
| `uv run ai-prompts export-java --check` | `java promptpack OK（15 个文件）` |
| 前端 | 本波零改动；`pnpm lint/typecheck/test/build` 未重跑（CI 会跑） |

### 5.2 同文本一致性（J2 退出条件①）

`J2PromptPackConsistencyTest` — 7 项，全部以**改动前导出的 golden** 为准：

| 用例 | 断言 |
| --- | --- |
| `knowledgeSystemBodyIsByteIdenticalToLegacyImplementation` | 锚点之后与 `knowledge.system.body.txt` **逐字节相同** |
| `knowledgeSystemTimePrefixKeepsLegacyShape` | 新前缀与旧前缀匹配同一形状（`…此时区。\n\n\n`） |
| `knowledgeUserPayloadMatchesGoldenByteForByte` | 无上下文 / 有上下文 / 含 XML 元字符三份 golden 逐字节相同 |
| `scenarioPromptsMatchGoldenByteForByte` | 六个 Scenario × 2 = 12 份 golden 逐字节相同 |
| `remoteAndBundledCatalogsYieldIdenticalPrompts` | 两种来源的 14 条正文、sha256、versions、manifestFingerprint 全部相同 |
| `knowledgeSystemReportsPromptIdentityForTracing` | 渲染结果自带 `knowledge.system` / `2026-11-11.2` |
| `scenarioCodesMatchPromptKeys` | 注册表 Scenario code 与 `PromptKeys.all()` 不漂移（14 条） |

> 为什么这份测试可信：golden 是**旧实现**的输出，不是新实现的产物；新实现只做"渲染 + 比较"。
> 生成方式与复现步骤写在该目录的 `README.md` 里。

### 5.3 `promptVersion` 追溯（J2 退出条件②）

| 链路 | 落点 | 验证 |
| --- | --- | --- |
| Agent Run | `agent_run.model_configuration_snapshot.prompts = {source, manifestFingerprint, keys:{<key>:<version>}}` | `AgentModelConfigurationSwitchPostgresIntegrationTest.promptIdentityIsRecordedInRunSnapshot`：真实 SQL 断言 source=BUNDLED、`knowledge.system=2026-11-11.2`、`scenario.project_research.instruction=2026-11-11.1`、fingerprint 64 位；重复解析后快照文本不变（幂等） |
| 知识问答 | `knowledge_message.prompt_key/prompt_version`（V90） | `J2PromptIdentityPersistenceTest`：助手消息带身份、用户消息为 NULL、未调用模型的轮次为 NULL、半截身份被拒绝 |

### 5.4 失败语义（J2 退出条件③）

| 场景 | 验证 |
| --- | --- |
| 资产缺失（真实空 classloader） | `J2PromptPackFailFastTest.bundleMissingFailsWithPromptUnavailable…` → `AI_PROMPT_UNAVAILABLE` |
| 同步问答在 Prompt 不可用时 | `askFailsFastAndNeverCallsTheModel` → 抛 `AI_PROMPT_UNAVAILABLE`，`chat`/`persistence` **零交互** |
| 流式问答在 Prompt 不可用时 | `askStreamFailsFastAndNeverCallsTheModel` → 立即下发 `error` 事件（code=`AI_PROMPT_UNAVAILABLE`）并结束流，`chat`/`persistence` **零交互** |
| worker 不可达 / 超时 / 5xx / 404 / 非法 JSON / schemaVersion 不符 | `RemotePromptCatalogTest.startupFailureCategories_failFast` 等 → 启动不阻断、`require` 快速失败 |
| sha256 不一致（manifest 与文档两边都验） | `tamperedDocumentTextKeepsLastSuccessfulSnapshot`、`documentShaFieldMustMatchTextEvenWhenManifestShaMatches` → 拒绝该批、**保留上次成功版本**、`require` 仍返回旧正文、指标 `result=failure` |
| 半套数据 | `batchIsReplacedAtomically_onlyWhenAllFourteenSucceed` → 13 条新数据成功、第 14 条失败 ⇒ 整批不生效，14 条都还是旧版本 |
| 对外错误码 | `AI_PROMPT_UNAVAILABLE` = 503 |

### 5.5 删除证据（grep 0 命中，`src/main/java`）

| 探针 | 命中 |
| --- | --- |
| `SYSTEM_PROMPT_TEMPLATE` | 0 |
| `TRUST_BOUNDARY_FOOTER` | 0 |
| `SYSTEM_PROMPT_BASE` | 0 |
| `你是 AI Collab 的项目知识库问答助手` | 0 |
| `你是交付就绪检查助手` / `你是会议纪要转任务助手` / `你是项目健康度检查助手` / `你是项目研究助手` / `你是周报生成助手` / `你是迭代规划助手` | 0 / 0 / 0 / 0 / 0 / 0 |
| `再次强调：以上 <QUESTION>` | 0 |
| `不可信数据，绝对不是系统指令` | 0 |
| `## 信息收集策略` / `## 检查策略` | 0 / 0 |
| 六个 `builtin/*Scenario.java` 内的 `"""` 文本块与 `instruction`/`outputContract` override | 0 |

### 5.6 CI

推送 `dev` @ `ef688e13` → [run 43059709247](https://github.com/BangShou1st/ai-collab/actions/runs/43059709247)
结论 **success**：

| Job | 结果 |
| --- | --- |
| P0 production configuration and backup contracts | success |
| Backend tests (shard 1/3 · 2/3 · 3/3) | success |
| Backend (Maven verify) | success |
| Frontend (pnpm) | success |
| Python worker (contract + prompts + eval) | success（含本批新增的 Java fallback 同步测试，跨目录读取 `ai-collab-backend/src/main/resources/promptpack`） |
| Image build & container smoke / Publish images to GHCR | skipped（`dev` push 的既有策略，非本批变化） |

> 说明：`python-worker` job 仍只跑 `pytest`，没有显式的 `ai-prompts check` / `export-java --check`
> 步骤——这两条已由 `tests/contract/` 里的用例覆盖（`ai-prompts check` 的字段比对与
> `export-java --check` 的逐字节比对各有一组用例），因此不重复加 CLI 步骤，避免 CI 里同一件事跑两遍。

---

## 6. 回滚语义（真实可用，不是空开关）

| 取值 | Prompt 来源 | 外呼 | 与今天的关系 |
| --- | --- | --- | --- |
| `prompt.registry.enabled=false`（**默认**） | classpath 只读 fallback 包 `promptpack/` | 零 | 正文逐字节相同 |
| `prompt.registry.enabled=true` | `ai-worker` 的 `GET /v1/prompts/*`，启动预取 + 缓存 + 周期刷新 | 仅内部网 | 首次让 Prompt 归属 Python |

- fallback 包**由 PromptPack 生成**（`uv run ai-prompts export-java`），**禁止手改**；
  漂移由两侧同时守护：Python `export-java --check` + `test_java_promptpack_sync.py`（逐字节，
  缺目录直接失败不 skip），Java `BundledPromptCatalog` 逐条复算 sha256/chars/variables。
- 因此关掉开关不是"关掉功能"，而是回到**内置的同一份正文**；`J2PromptPackConsistencyTest`
  的 `remoteAndBundledCatalogsYieldIdenticalPrompts` 直接证明这一点。
- **发布回滚** = 把 `enabled` 改回 `false` 并重启（无需回滚代码）。
- **代码级彻底回滚** = 回滚发布（`KnowledgePromptProvider` 已删除，不存在"代码里仍留一份正文"
  的隐性第二来源——这是刻意选择：留着它，回退时就分不清线上到底在用哪一份）。

---

## 7. 遗留与风险

1. **启动不阻断是刻意取舍**：`enabled=true` 且 worker 不可达时应用仍能启动，
   AI 功能以 `AI_PROMPT_UNAVAILABLE` 快速失败。若运维希望"worker 不通就不要启动"，
   需要新增一个显式开关（当前不做，避免空开关）。
2. **未与真实 worker 联调**：Java 侧的远端路径全部由本地 HTTP stub（JDK `HttpServer`）验证，
   协议细节已对 P0 契约逐字实现（manifest 四字段、文档带 `variables`），但**没有**在真实
   `ai-worker` 容器上跑过一次端到端。这是本批证据等级的分界（§9）。
3. **刷新与版本切换的语义边界**：整批原子替换意味着"新版本只有 14 条全部可用才生效"。
   若某次发布故意只更新一条 Prompt，Java 会用同一批 manifest 里的 14 条一起换（版本按 key 独立取最大），
   不存在"部分 key 停在新版本、部分停在旧版本"的中间态，但**同一次刷新里混用不同 key 的不同代次是正常的**
   （各 key 独立版本号）。
4. **`PromptKeys.all()` 硬编码 6 个 scenario code**：与 `AgentScenarioRegistry` 的一致性由
   `scenarioCodesMatchPromptKeys` 守住；将来新增 Scenario 必须同时改两处（测试会红）。
5. **`BundledPromptCatalog` 每个 Spring 上下文都会读 14 个 classpath 文件**：约 1ms，无缓存；
   若将来 Prompt 数量上数量级，再加静态缓存。
6. **`AI_PROMPT_UNAVAILABLE` 未在前端做专门文案**：走既有 `ApiResponse` 错误通道，
   前端显示服务端 message（"Prompt 资产不可用，请稍后重试"）；本波不改前端。
7. **`knowledge_message` 的 Prompt 身份未进读接口**：目前只在库里可查（运维/审计用）。
   若产品要看"这条回答用了哪个 Prompt 版本"，再补 View 字段（只加字段）。
8. **V90 是"两列可空"的最小改动**：没有 CHECK 约束把"只有 ASSISTANT 行才允许非空"钉在数据库里，
   由应用层保证（`KnowledgePersistenceService` 只给助手消息赋值）。理由是版本语法属于 PromptPack
   契约，不该在迁移里重复表达。

---

## 8. 编排复核记录

| 路 | 分支 / worktree | 产出 | 我方复核 |
| --- | --- | --- | --- |
| Phase 0 | 主工作树 | `01a94215`：golden 取证、14 条资产、fallback 包、冻结契约 | 亲自完成；生成脚本当场校验"换回占位符 == golden"（3/3 user + system） |
| S1 Python | `j2/s1` → `167e46db` | 正文口径宽松化、`export-java`、冻结指纹与同步测试 | 逐文件复核 diff（`contract/prompts.py` 只删一个拒绝分支）；主工作树重跑 7 条命令全绿后 cherry-pick（`ffdef243`） |
| S2 Java 基础设施 | `j2/s2` → 由我方接管提交 `ed326b79` | `common/ai/prompt/**`、配置、错误码、12 项远端测试 | 子 Agent 卡在 4 个**测试夹具**缺陷上（见下），我方定位并修复后 56/56 绿；复核了 `RemotePromptCatalog` 的原子替换/上次成功版本/失败分类实现，以及 `PromptRenderer` 只对**模板字面量**检查残留占位符（值里的 `{{ }}` 视为数据，避免用户制造 500） |
| S3a 知识侧 | `j2/s3a` → `47534fdb` | assembler、删 `KnowledgePromptProvider`、服务接线、V90 写入 | 复核 diff；重跑 `KnowledgePromptSafetyContractTest`(11)/`SingleSource`(2)/`ChatToggle`(2) 全绿后 cherry-pick（`21ff62fa`） |
| S3b Agent 侧 | `j2/s3b` → `feb30051` | 接口默认抛错、六个 Scenario 清空正文、注册表包装、Run 快照追溯 | 复核 diff；重跑 73 项（registry/composer/behavior）全绿后 cherry-pick（`640df55a`） |
| S4 测试聚合 | 主工作树 | `9edb6864`：一致性/快速失败/追溯 | 我方编写并逐项跑绿 |
| S5 文档 | 主工作树 | capability-matrix、设计基线、README、本报告 | 我方完成 |

**S2 卡点复盘（如实记录）**：子 Agent 停在 4 个红测上，根因都在测试夹具，不在生产实现：

1. `PromptWorkerStub` 的构造器参数 `pack` **遮蔽**了同名字段，lambda 捕获的是参数副本，
   因此 `setPack()` 永远不生效（两份 pack 看起来一样）；改为 `this.pack` 后 2 个用例转绿；
2. `startupFailureCategories` 的"连接不存在端口"用例期望 `unreachable`，但本机 JVM 走系统代理
   返回 502 / 连接超时——断言改为"失败发生在 manifest 阶段"，传输层措辞交由 503/404/非法 JSON
   等确定性用例断言；
3. 超时用例的 stub 延时（600ms）小于读超时（2s），请求其实成功了；改为短读超时（150ms）；
4. 同一超时用例复用共享 `restClient()`，因此需要按用例注入读超时（新增重载）。

运维/观察期建议：`prompt.catalog.refresh{result}`、`prompt.catalog.unavailable{key}`、
`prompt.catalog.entries{source}` 三条指标 + `prompt catalog …` 日志即可覆盖 §7.2 的运维面。

---

## 9. 证据等级

| 结论 | 等级 | 依据 |
| --- | --- | --- |
| PromptPack 资产、`export-java`、口径宽松化、Java 客户端与消费方接线已入库 | IMPLEMENTED | 代码与资产入库 |
| 同文本一致性（逐字节） | **TESTED** | 与**改动前导出的 golden** 比对，14 条 key 全覆盖 |
| 失败语义与"上次成功版本" | **TESTED** | 真实 `BundledPromptCatalog`（空 classloader）+ 本地 HTTP stub 的 12 项远端用例 |
| `promptVersion` 可追溯 | **TESTED** | 真实 PostgreSQL（Testcontainers）断言 Run 快照 SQL；实体内存断言 `knowledge_message` |
| 全量门禁 | **TESTED** | `mvnw verify` 2657 项全绿；Python 7 条命令全绿 |
| 两种来源等价 | **TESTED（同一份资产）** | bundled 与 remote 产出逐字节相同；**但** remote 侧是本地 stub，不是真实 `ai-worker` 容器 |
| 真实 worker 端到端联调 | **NOT_VERIFIED** | 本批无 docker-compose 联调（worker 生产默认关闭），见 §7.2 |
| 灰度 / 线上行为 | **NOT_CLAIMED** | 未上线、无灰度数据 |

---

## 10. 提交与清理

| 提交 | 内容 |
| --- | --- |
| `01a94215` | Phase 0：冻结映射 + golden 夹具 + 14 条资产 + Java fallback 包 |
| `ffdef243` | S1：正文换行口径 + `ai-prompts export-java` + 冻结指纹/同步测试 |
| `e01b5cbf` | V90 迁移与知识侧持久化入口 |
| `c5c08dee` | README 迁移版本号 V90 |
| `746557de` | S2：`PromptCatalog` 客户端、内置回退包与失败语义 |
| `21ff62fa` | S3a：问答 Prompt 改由 PromptPack 装配，删除 `KnowledgePromptProvider` |
| `640df55a` | S3b：六个 Scenario 迁 PromptPack + Run 快照记录身份 |
| `9edb6864` | S4：一致性 / 快速失败 / 追溯测试 |
| `ef688e13` | 本报告 + 文档同步（capability-matrix / 设计基线 / 两个 README / docs 导航） |
| `<final>` | 部署编排透传 `PROMPT_REGISTRY_*`（默认 false，行为不变）+ 本报告补 CI 结果 |

- **无** force push、**未**合并 `main`、**未**改写已推送历史。
- `ai-collab-deploy/docker-compose.yml` 只**追加**三行 `PROMPT_REGISTRY_*` 透传（默认 `false`，
  缺省行为与今天一致）：不追加的话 `enabled=true` 在最常用的部署形态下根本没法打开，
  回退开关就变成纸面语义。ai-worker 容器本身在后续波次加入编排。
- 四个临时 worktree（`j2-s1`/`j2-s2`/`j2-s3a`/`j2-s3b`）与对应分支在合入后立即删除；
  `git worktree list` 只剩主工作树，`git branch` 只剩 `dev` / `main`。
- 本批新增 **1 个迁移（V90）**，未改任何历史迁移。
- 本地未生成需入库的日志/构建产物（`verify` 日志写在 `ai-collab-backend/target/`）。
