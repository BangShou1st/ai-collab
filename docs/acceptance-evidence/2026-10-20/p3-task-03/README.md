# P3-TASK-03：AI 配置状态准确性与用途可用性说明

日期：2026-10-20（Asia/Shanghai）。批准范围：P3-SCOPE-01 **仅 U-03**。分支：`dev`；不合并 `main`。

## 基线、范围与权限边界

开工 HEAD 与 `origin/dev` 均为 `d6f01c539d4667186811bc9037db2c11dafad438`，工作区干净；`git merge-base --is-ancestor d6f01c539d4667186811bc9037db2c11dafad438 HEAD` 返回 0。无 force push、无硬重置、无分支删除、无 Docker 卷或数据库清理。仓库内未找到额外 `AGENTS.md`，遵循用户全局指令。已读 README、架构、capability-matrix、p3-scope-01，以及 TASK-01、TASK-02 验收记录，并核对 HomeView.vue、HomeApplicationService.java、UserAiSettingsView.vue、user-ai-api.ts、ai-preset-api.ts、用户默认/用途覆盖解析服务、连接与能力探测、权限控制。

未实施 U-04、U-05 或任何其他候选；不重复验收 RAG、AI 规划、Agent SSE；未触碰六份历史文档。

## 一、AI 配置状态契约（先定契约，再改 UI）

状态依据全部来自既有只读接口，未新增端点：

| 依据 | 内容 |
| --- | --- |
| `GET /api/v1/home` | `aiConfigSummary`：`configured` / `defaultModel` / `providerCount` / `anyPurposeUsable` |
| `GET /api/v1/user/ai-providers` | 本人全部 Provider 行（含 Zen/Kilo 预设行）：`enabled`、`isDefault`、`presetCode`、`modelName`、`hasApiKey`、`capabilities` |
| `GET /api/v1/user/ai-providers/purposes` | `Map<ModelPurpose, UUID>`，**只包含已保存的覆盖**；缺失即“使用默认” |
| `GET /api/v1/user/ai-provider-presets` | Zen/Kilo `PresetStatus`：`connected`（本人存在该预设行）、`enabled`、`modelName` |
| 后端权威解析 `UserAiProviderService.resolve` | `findAssigned`（JOIN `user_ai_provider`）→ `findDefaultByUserId` → 抛 `AI_PROVIDER_UNAVAILABLE` |

**六种状态可确定性结论**

| 状态 | 判定依据 | 可确定 |
| --- | --- | --- |
| 1 未保存任何配置 | `providerCount == 0` | 是 |
| 2 已保存 Provider 但未必能调用 | `providerCount > 0` 且 `anyPurposeUsable == false`（默认停用 / 无默认 / 覆盖停用） | 是 |
| 3 指定用途已解析到有效配置 | 按解析规则逐用途推导（覆盖 → 已启用默认），可得“可解析到已启用配置” | 是（仅配置层面） |
| 4 能力测试刚通过/失败 | 仅当前会话内存记录，随页面刷新丢失 | 会话内是 |
| 5 尚未测试 / 结果已过时 | 无持久化契约，默认“未测试”；刷新/离页后结果作废 | 是 |
| 6 配置加载失败、状态未知 | 前端 `loadFailed`，绝不改写为“未配置” | 是 |

**边界与例外（关键判断）**

- **网络 / 权限 / 模型配置错误可区分**：由前端 `normalizeApiError` 归类（`NETWORK_ERROR`、`HTTP_401/403`）与后端业务码（`AI_PROVIDER_UNAVAILABLE`、`AI_MODEL_CREDENTIAL_INVALID`、`AI_PROVIDER_ERROR` 等）映射为既定文案；页面不回显内部错误正文。
- **用途覆盖指向已删除 Provider**：V40 外键 `ON DELETE CASCADE` 会删除对应 assignment 行；即便存在脏数据，`findAssigned` 的 JOIN 也会回退到默认模型。前端按同一规则回退并标为“覆盖已失效”。→ 确定。
- **用途覆盖指向已停用 Provider**：assignment 行保留，`resolve()` 不校验 `enabled` 仍返回该行，但执行期 `adapter()`/Kilo 分支会拒绝。因此该用途**不可用**，UI 显示“用途覆盖指向已停用的模型”。→ 确定。
- **无默认模型时是否存在合法后备解析路径**：**没有**。`resolve()` 只有 assignment → default → 抛错；删除默认 Provider 后不会自动提升其他 Provider（预设断开同样不回填）。这是“已保存但不可用”的真实来源。→ 确定。
- **凭据存在性（`hasApiKey`）不参与可用性判断**：Kilo 匿名调用与 Zen 免费通道无需用户密钥，凭据只以“已配置/未配置”展示，不构成可用性证据。
- **`capabilities` 不参与状态展示**：能力策略在调用期由 `ModelCapabilityPolicy` 判定，能力探测按钮可分别验证，状态文案不替用户断言能力。
- **已连接的免费预设不足以证明某个用途可用**：`connected` 仅表示存在该用户行；是否仍被上游服务、凭据是否有效都需真实探测。UI 一律标“可用性未验证”。

**记录在案的最小只读契约缺口与调整**

- 缺口：原 `HomeView.AiConfigSummary` 只有 `configured`（= Provider 列表非空）、`defaultModel`、`providerCount`。当默认 Provider 被停用时，`configured` 仍为真且 `defaultModel` 仍给出名称，界面无法区分“已保存但不可用”。
- 调整（最小、只读）：新增 `anyPurposeUsable`；`defaultModel` 语义收窄为“**已启用**默认模型的名称，否则 `null`”。解析在 `HomeApplicationService` 内复用 `ai.list` + `ai.listAssignments`，**不新增端点、不调用模型、不读取凭据**。
- 同步：`docs/api/openapi.yaml`、前端 `home-api.ts` 类型。

## 二、实现与差异

1. **工作台（`HomeView.vue`）**：三态文案——未保存配置（引导，不出现“已就绪”）／已保存但不可解析（警示卡 + “管理 AI 设置”）／已保存且至少一项用途可解析（“AI 配置已保存 · 可用性需在 AI 设置中测试确认，此处不表示已就绪”）。新增加载失败态：无数据时“工作台暂时无法加载 / 状态未知 + 重试”；刷新失败时保留上次数据并明确“可能已过时”。请求令牌串行化，切换账号时清空并重新加载。
2. **个人 AI 设置页（`UserAiSettingsView.vue`）**：
   - 新增“按用途的解析状态”卡：逐用途显示默认解析 / 用途覆盖 / 不可用原因（无默认、默认停用、覆盖停用、覆盖失效），一律附“可用性未验证”。覆盖失效时选择器显示可读名称（“××（已停用）”）而不是原始 Provider ID。
   - 新增配置加载失败态：“AI 配置加载失败，当前状态未知 + 重试”，并且**不再**渲染“还没有自定义模型”的伪空态。
   - 测试结果改为**按 Provider × 能力分离**的本次会话记录，按钮旁显示“未测试 / 连接：本次会话测试通过 / 失败”；每次测试只声明被测能力，不把 CONNECTIVITY 结果外推到规划 JSON、流式或原生工具。
   - `probeMode` 由全局单一变量改为**按 Provider 独立**，避免一行切换影响所有行。
   - 新增“个人聊天模型与系统 Embedding 是两套配置”说明；系统 Embedding 入口仅在 `systemAdmin` 时渲染。
   - 保留原有自定义 Provider 增删改、连接/断开、测试、设为默认、用途覆盖保存与免费模型刷新。
3. **共享状态模块（`ai-purpose-status.ts`）**：把解析规则写成纯函数（同一份规则在后端 Java 中实现并单测），供设置页与单测使用。

## 三、自动化验证（独立于浏览器）

| 检查 | 结果 |
| --- | --- |
| `pnpm lint` | PASS：0 errors，77 warnings（开工基线 78，未新增警告） |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS：47 文件、348 项（基线 46 文件、321 项） |
| `pnpm build` | PASS；既有 PURE 注释与 chunk 体积提示保留 |
| `.\mvnw.cmd -B -ntp -Dtest=HomeAggregateTest test` | PASS：9 tests，0 failures/errors，0 skipped |
| `git diff --check` | PASS |

命令输出摘录见 [checks.txt](checks.txt)。

**新增/更新测试（本轮 27 项）**：`ai-purpose-status.test.ts` 10 项（解析矩阵、凭据不参与、能力结果不互相替代）、`UserAiSettingsView.test.ts` 14 项（加载失败未知、无自动探测、三种用途解析、覆盖停用可读、能力结果互不替代、测试失败后重测、结果不跨刷新、账号切换清空、管理员入口）、`HomeView.test.ts` 8 项（三态文案、无“已就绪”、加载失败未知、迟到响应、账号切换）、`HomeAggregateTest` 9 项（含“聚合过程不读凭据、不调用模型”的交互断言）。

**验收矩阵映射**（提示词第五节逐项）

| 要求 | 覆盖 |
| --- | --- |
| 无配置 | `reports every purpose as unresolvable without saved config`；工作台/设置页两态自动化 |
| 已保存但停用 | `treats a disabled default as saved-but-unresolvable`；后端 `disabled_default_provider_is_saved_but_not_resolvable` |
| 已保存但未测试 | `resolves to the enabled default without claiming it was tested`；视图“未测试”徽标 |
| 默认与覆盖不同解析结果 | `prefers an enabled override even when the default is disabled`；后端 `purpose_override_resolves_when_default_is_disabled` |
| 用途覆盖失效 | `falls back to the default when the override target no longer exists`、`marks a disabled override as blocking…` |
| 连接测试成功、失败及重新测试 | `marks a probe failure and allows re-testing`、`does not let one capability result stand in for another` |
| 不同能力结果不互相替代 | 同上 + `keeps probe outcomes keyed per provider and per capability` |
| API 加载错误不显示“未配置” | `keeps the AI config unknown, not empty, when loading fails`；工作台同类测试 |
| 迟到响应 / 切换用户不显示旧状态 | `ignores a late response from a superseded request`、`drops the previous user state when the account changes`、`drops the previous user's configuration and probe results on account switch` |
| 无自动模型探测 | `never probes a model without an explicit click, including a refresh` |
| 普通成员无法访问管理员功能 | 设置页 `hides the system Embedding admin entry from members`（真实浏览器另行确认跳转拒绝） |
| 原有保存/测试/断开/删除不回归 | 保留的断开与用途失败重试测试，以及既有套件全部通过 |

后端仅改 `HomeView` 聚合摘要与 `HomeApplicationService`，未新增端点、未改写入语义，故未新增 PostgreSQL 集成测试；后端全量回归由 GitHub Actions 执行，Testcontainers 守卫未被削弱。

## 四、真实 Edge / BSK

环境：真实 Edge 155、BSK CLI/扩展 0.3.3，后台 session `htsa`；全部验收结束后 `session stop`。桌面 CSS viewport 1540×846；390px 为同一真实 Edge 的 CDP 移动仿真（390×844@3x，移动 UA），不是物理手机。

夹具全部通过既有公开业务 API 产生，无 SQL 直改：合成账号 `p3task3_stateA`（非管理员）、`p3task3_member`（非管理员），以及两个合成自定义 Provider（`P3T3 主模型` / `P3T3 规划专用`，`baseUrl=https://api.openai.com`、占位 Key）。系统管理员入口对照使用既有本地 `owner` 账号，**仅做只读 DOM 断言、未截图、未修改其任何配置**。合成账号与 Provider 作为可复验夹具保留。

| 场景 | 真实结果 | 证据与边界 |
| --- | --- | --- |
| 无配置 | PASS：工作台“让 AI 加入你的工作流 / 还没有保存任何 AI 模型配置”，**无“AI 已就绪”**；设置页“已保存 0 个模型配置”、三种用途均“不可用”并标注“配置存在不代表可调用” | [工作台](01-home-none-desktop.png)、[设置](02-settings-none-desktop.png) |
| 已保存但停用 | PASS：新建 `enabled=false` 的默认 Provider 后，工作台改为“AI 配置已保存，但暂不可用 · 已保存 1 个模型配置…”；设置页 summary 变为“已启用 0 个”，三用途均“默认模型『P3T3 主模型』已停用” | [工作台](03-home-saved-unusable-desktop.png)、[设置](04-settings-default-disabled-desktop.png) |
| 已启用默认（未验证） | PASS：启用默认 Provider 后工作台为“AI 配置已保存 · 默认模型 gpt-4o-mini · 可用性需在 AI 设置中测试确认，此处不表示已就绪”；设置页每行为“默认模型：… · 已启用 · 可用性未验证”，Provider 行显示“未测试” | [工作台](05-home-saved-unverified-desktop.png) |
| 默认与用途覆盖不同解析 | PASS：为 AI 规划分配 `P3T3 规划专用` 后，知识问答/Agent 仍为“默认模型”，AI 规划为“用途覆盖：P3T3 规划专用 · gpt-4o · 已启用 · 可用性未验证” | [设置全页](06-settings-purpose-status-desktop.png) |
| 用途覆盖失效（已停用） | PASS：停用被覆盖的 Provider 后该用途变红“用途覆盖指向已停用的模型『P3T3 规划专用』，该用途当前不可用”，选择器显示“P3T3 规划专用（已停用）”而非原始 ID | [设置](07-settings-override-disabled-desktop.png) |
| 加载失败不显示“未配置”（工作台） | PASS：浏览器层注入 `/api/v1/home` 请求失败后点击“刷新”，出现“本次刷新失败 / 下面是上次成功加载的数据，AI 状态可能已过时 + 重试”，且**仍显示“AI 配置已保存”而非未配置** | [工作台失败态](08-home-load-failure-desktop.png)；注入为真实 XHR 失败，不是 Mock 应用逻辑 |
| 用途覆盖加载失败 | PASS：注入 `/api/v1/user/ai-providers` 失败后，设置页显示“用途覆盖加载失败，暂无法确认各用途解析结果”并保留“重试” | [设置用途失败](09-settings-purposes-failure-desktop.png) |
| 配置加载失败、状态未知 | PASS：从工作台返回设置页触发重新加载失败，显示“AI 配置加载失败，当前状态未知 + 重试”，且不出现“还没有自定义模型”、不出现“未配置” | [设置失败态](10-settings-load-failure-desktop.png) |
| 390px 窄屏 | PASS：用途行改为单列、状态文案换行、失效行红色可读；`scrollWidth=390 = clientWidth=390`，无横向溢出 | [设置 390](11-settings-narrow-390.png)、[工作台 390](12-home-narrow-390.png) |
| 键盘 | PASS：Tab 焦点依序经过导航链接、`添加 AI 配置`、免费模型选择、`刷新模型`、`测试连接`、`保存`、`+ 自定义 Provider`、Provider 的“测试能力”选择与“测试/编辑/删除”等控件；在用途覆盖选择器上 Enter 打开下拉、Escape 关闭。**未**在“测试”按钮上按 Enter，以避免无授权的真实模型调用 | 真实 `bsk press Tab/Enter/Escape` + `document.activeElement` 轨迹；无截图 |
| 权限入口（Member） | PASS：`p3task3_member` 的设置页只有 Embedding 说明、**无**“前往系统 Embedding 管理”；直接访问 `/admin/embedding` 被守卫重定向到 `/projects`，页面无 Embedding 管理内容、侧栏无“管理中心” | 真实 `location.pathname=/projects` 与 DOM 断言；无截图 |
| 权限入口（管理员） | PASS：`owner`（`system_admin=true`）的设置页出现“前往系统 Embedding 管理”，`/admin/embedding` 正常渲染“AI Infrastructure” | 仅 DOM 断言（`adminLinks=1`、`embeddingTitle=true`），**未截图以避免展示真实账号配置** |

所有截图均由 BSK 截图生成并逐张查看，只含合成账号、合成 Provider 与公开模型目录名；不含密码、Token、Cookie、请求头、地址栏或真实用户数据，无需像素遮挡。

## 五、NOT_TESTED、安全边界与回退

- **真实模型调用 NOT_TESTED**：本轮**未执行任何真实模型补全或能力探测**，因为用户未给出“真实模型调用”授权。因此“连接测试通过 / 失败 / 重新测试”的真实模型分支标为 **NOT_TESTED**，仅由确定性自动化覆盖；未用 Mock 冒充真实服务通过。真实浏览器中未点击任何“测试 / 测试连接”按钮。
- **真实 Provider 429** NOT_TESTED，未为制造限流消耗额度（R-4 维持原状态）。
- 页面初始化仍会按设计拉取 **免费模型目录**（Zen/Kilo 上游 `GET /models`，5 分钟缓存，为既有行为），这不是模型补全探测、不消耗生成额度；真实补全探测只在用户点击测试按钮时发生（自动化断言 `test`/`testCapability` 在挂载与刷新时均未被调用）。
- 加载失败注入是**浏览器层 XHR 失败**（真实网络失败），用于验证前端失败态；不代表后端返回了特定错误码。
- R-1～R-5 维持既有专项状态，不因本轮 AI 状态验收升级为全部通过。
- 权限：个人 AI 状态只读取当前用户自己的 Provider 与覆盖；未新增任何端点或放大读取范围；系统 Embedding 与个人聊天模型在 UI 与入口上明确区分，Member 无管理员入口。
- 回退方式：普通 `git revert 18f2287`（功能与文档）与 `git revert <本轮测试/补记提交>`，再正常推送 `dev`；不重置分支、不删除数据、不回滚数据库迁移（本轮无迁移）。

## 六、Git 与 CI

功能、测试、规划更新、12 张真实截图与 `checks.txt` 已普通推送 `dev`：

- 功能与契约：`18f2287`（`feat(ai): report AI configuration state honestly`）
- 账号切换守卫与补充测试：`5a9b526`（`test(ai): cover account switching and per-capability probe results`）
- 本报告：`37a368f`（`docs(p3): record P3 task 03 AI configuration status acceptance`）

`37a368f` 的 [CI run 39411180854](https://github.com/BangShou1st/ai-collab/actions/runs/39411180854)：**Frontend PASS、Backend PASS**（Backend 5m47s，`Assert container tests actually ran` 通过；本轮未复现 P3-TASK-02 的 PostgreSQL 清理死锁，非阻断风险维持观察）。

回退功能可使用 `git revert 18f2287` 与 `git revert 5a9b526`，再正常推送 `dev`。

**非阻断 CI 稳定性风险复现（与 P3-TASK-02 同类）**：`e6d4e5f` 的 Backend 作业在 `Build & verify (Testcontainers + Flyway)` 失败，唯一错误为
`RetrievalCoveragePostgresTest.reset:139 » PessimisticLockingFailure`，SQL 为
`TRUNCATE document_chunk, project_document, project_member, project, app_user, embedding_index_generation, system_embedding_config CASCADE`，
数据库返回 `ERROR: deadlock detected`，Detail 为
`Process 69 waits for AccessExclusiveLock on relation 17321 of database 16384; blocked by process 68.` 与
`Process 68 waits for RowExclusiveLock on relation 17236 of database 16384; blocked by process 69.`；
结果摘要 `Tests run: 1543, Failures: 0, Errors: 1, Skipped: 18`，并伴随 HikariPool 连接校验告警与
`Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`。

该失败与本轮改动无关：本轮未改任何 PostgreSQL 测试类，且同一条分支的上一次运行（`37a368f`）1543 项后端测试全部通过。按既有先例对同一提交重跑，**未禁用测试、未跳过容器守卫、未降低覆盖率、未放宽断言**。重跑结论记录于最终交付消息。

本报告补记提交只记录上述 SHA/CI，无运行代码变化。最终交付 SHA 是包含本次补记的提交，可用 `git log -1 --format=%H -- docs/acceptance-evidence/2026-10-20/p3-task-03/README.md` 定位；其完整 SHA、精确匹配 headSha 的 CI run 与 Frontend / Backend 结论在工作区状态核对后的最终交付消息中说明。仅当最终交付提交的两项 CI 均成功后交付。

## 七、最终必答问题

1. **工作台是否还会把“存在配置”误报为“AI 已就绪”？** 不会。文案已改为“AI 配置已保存 · …此处不表示已就绪”，且在“已保存但不可解析”时改为警示卡；自动化与真实浏览器均断言页面不含“AI 已就绪”。
2. **三种 AI 用途的配置与可用状态是否有可信依据？** 有。依据既有 `/user/ai-providers`、`/purposes`、`/ai-provider-presets` 与后端 `resolve()` 同一规则推导，只声明“可解析到已启用配置”，并标注“可用性未验证”。
3. **未测试、测试失败、测试成功、加载失败能否明确区分？** 能。未测试为默认徽标；测试成功/失败为“本次会话”记录且按 Provider×能力分离；加载失败显示“当前状态未知 + 重试”，绝不显示为未配置。
4. **是否产生未经授权的真实模型调用或费用？** 没有。本轮零真实模型调用；页面初始化/刷新/用途切换均不探测；自动化断言挂载与刷新不调用 `test`/`testCapability`。
5. **Member 权限与凭据隐私是否保持安全？** 是。仅读取本人配置；未暴露 API Key（只有“已配置/未配置”）、鉴权头或内部错误正文；Member 无系统 Embedding 入口且 `/admin/embedding` 被守卫拒绝。
6. **自动化、真实浏览器、最终 SHA 和 CI 结论？** 自动化：lint 0 error、typecheck、348 项前端测试、build、后端 9 项聚合测试全部 PASS。真实浏览器：Edge+BSK，12 张脱敏截图覆盖三态、默认/覆盖、两种加载失败、390px、键盘与权限入口，均为 PASS；真实模型探测分支为 NOT_TESTED。最终 SHA 与 CI 结论见第六节与最终交付消息。
