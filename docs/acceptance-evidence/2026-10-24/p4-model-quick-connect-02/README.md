# P4-MODEL-QUICK-CONNECT-02：AI 模型快速接入、统一自动验收与模块重整

**交付状态：`MODEL_QUICK_CONNECT_PARTIAL_ACCEPTANCE`**

主要改造全部完成并经离线全量回归与真实浏览器验收；真实外部生成验收因未获得本轮用户授权（授权请求已发出、用户未响应，按任务书约束历史授权不自动延续）标记 `NOT_EXECUTED`，不冒充通过。

上一轮验收发现的三个产品缺陷（Gemini 端点预设错误、`{model}` 占位符被保存校验拒绝、STREAMING 无声明入口且流式失败无可见反馈）**全部修复并经真实浏览器逐项复验通过**。

---

## A. Git

- 任务起始基线：`71939d015bb23d9c78693d5f9b8173af5d05f42c`（dev = origin/dev，工作区干净）
- `main` / `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程未触碰）
- 本轮交付 SHA：见提交历史（本文档随最后一笔提交固定）
- 改动规模（整轮 Git 口径，`71939d0..44dfc5e` 含证据文档）：70 files，+3196 / -2133，净增 1063 行
- 生产代码局部口径（不含本轮新增证据文档）：46 文件，+818 / -2133，局部净删 1315 行（该数字仅为重构部分统计，不是整轮 Git 净变化）

## B. 生产代码修改清单

### 后端（新增 5 / 修改 12 / 删除 6）

| 类别 | 内容 |
| --- | --- |
| 新增 | `ProviderProfileCatalog`（三种协议接入档案的唯一事实源）；`ProviderModelDiscoveryService`（自定义 Provider 只读模型发现）；`UserAiProviderProbeResultRepository`（探针结果落库）；V66 迁移（`user_ai_provider_probe_result` 表）；`JsonHttpModelClient.get()`（只读 GET 通道，同一出站安全链路） |
| 修改 | `UserAiProviderService.validateEndpoint`（放行至多一个 `{model}` 占位符，替换安全 token 后仍走完整 URI + 公网 HTTPS 校验；错误信息按 baseUrl/apiPath/占位符/出站策略四类细化）；`AiCapabilityProbeService`（结果落库、失败分类 FAIL/NOT_SUPPORTED/INCONCLUSIVE、读取时动态判 STALE、`results()` 查询）；`UserAiProviderController`（新增 `GET /profiles`、`GET /{id}/models`、`GET /{id}/probe-results`，修复字段注入）；`ErrorCode` 新增 `AI_PROVIDER_NOT_FOUND`（404 不再泛化）；`JsonHttpModelClient.checkStatus` 404 分类 |
| 删除 | Zen/Kilo 旧 `/test` 端点及其独占的 `validateCredential`/`validateModel` 传输方法（语义分别被真实探针与 models 端点覆盖）；ProjectModel 遗留体系（Controller/Service/Repository + turn 网关 configurationId/projectId 死分支）；OpenAiCompatibleModelAdapter 三个死方法；详见 [code-cleanup.md](code-cleanup.md) |

### 前端（新增 4 / 修改 11 / 删除 3）

| 类别 | 内容 |
| --- | --- |
| 新增 | `ProviderFormDialog.vue`（表单：档案默认端点切换且不覆盖用户编辑、能力勾选默认 CHAT+STREAMING、模型发现按钮降级手输）；`CapabilitySuitePanel.vue` + `use-capability-suite.ts`（统一“测试连接与能力”编排：静态→发现→连接→流式→规划/工具按需，逐步展示、单步预算、失败停断规则）；`use-preset-connection.ts`（Zen/Kilo 镜像逻辑合并）；`provider-profiles.ts`（档案降级快照） |
| 修改 | `UserAiSettingsView.vue`（三入口统一接入套件面板；用途就绪聚合 可用/不可用/未验证/未声明，CONNECTIVITY 不外推）；`ai-purpose-status.ts`（探针结果状态机 + `PURPOSE_REQUIREMENTS` 就绪规则）；`knowledge-api.ts`/`use-knowledge-view.ts`/`KnowledgeView.vue`（SSE 错误一律 throw，消息区渲染错误气泡：错误消息 + 错误码 + 重试建议）；`user-ai-api.ts`（新端点封装 + ProbeMode 类型化） |
| 删除 | 不可达页面 ProjectModelSettings 及其独占 API/路由；`knowledgeApi.ask`；`zenEnabled/kiloEnabled` 假状态；`PresetStatus` 未读字段等（详见 code-cleanup.md） |

## C. 三缺陷修复验证（真实浏览器）

1. **Gemini 端点预设**：切到 Gemini 后 Base URL 自动为 `https://generativelanguage.googleapis.com`、API Path 预填 `/v1beta/models/{model}:generateContent`（含占位符说明）；用户已编辑的地址不被切换覆盖（browser-e2e.json E2E-01/02）。
2. **`{model}` 占位符**：占位符路径保存成功（上一轮被拒）；非法花括号组合与重复占位符仍被拒；出站 SSRF 策略零放松（不可解析域名被拒并给出分类文案）（E2E-04/05 + 后端 8 项契约测试）。
3. **STREAMING 声明与流式错误可见性**：表单默认勾选 CHAT+STREAMING（UI 新建配置即可流式问答）；知识问答 SSE 失败时消息区渲染错误气泡（错误码 + 建议 + 关闭），不再空白（E2E-03/07）。

## D. 统一能力测试架构

复用 `AiCapabilityProbeService`（未新建并行体系）：三个入口（自定义/Zen/Kilo）共用 `POST /user/ai-providers/{id}/test-capability` 与同一步骤编排；探针结论落库（V66）带配置版本，配置变更后读取即 STALE；失败分类区分 FAIL / NOT_SUPPORTED / INCONCLUSIVE；Zen/Kilo 与自定义获得**完全相同**的测试体验。浏览器实测：套件对假端点配置完成 静态 PASS → 发现 INCONCLUSIVE（404 分类+手输降级提示）→ 连接/流式 INCONCLUSIVE（405）→ 按需跳过 → 落库时间戳展示（E2E-06，见 [capability-probes.json](capability-probes.json)）。

## E. 安全边界保持（未为统一而抹平）

Zen 白名单上游/免费模型准入/凭据分桶缓存；Kilo 匿名语义/共享库网关/免费目录准入；预设行不可经自定义通道改删；密钥加密落库、日志零明文；`ModelCapabilityPolicy` 生产门控原样保留（探针对未声明能力的拒绝被归类 NOT_SUPPORTED 而非绕过）；预设不允许通过自定义发现通道绕开准入。详见 provider-matrix.json 与 architecture-audit.md 第 2 节。

## F. 真实外部模型验收

授权请求已发出（Gemini + Zen + Kilo，总预算 14 次生成），用户未响应 → 按“未授权”路径执行：

| 项 | 结果 |
| --- | --- |
| Gemini 真实生成/流式/规划/工具 | NOT_EXECUTED（待授权） |
| Zen 真实生成/流式 | NOT_EXECUTED（待授权） |
| Kilo 真实生成/流式 | NOT_EXECUTED（待授权） |
| Zen/Kilo 真实模型发现（只读，无生成） | PASS（真实目录返回） |
| 真实浏览器 E2E（无生成场景 10 项，E2E-01 至 E2E-10） | 10 PASS / 0 FAIL（[browser-e2e.json](browser-e2e.json)） |

本轮实际发起的生成请求数：**0**。唯一外呼为 IANA 保留域 example.com（套件错误路径）与 Zen/Kilo 只读模型目录。

## G. 本地全量测试

| 套件 | 结果 |
| --- | --- |
| 后端 `mvnw -B -ntp verify`（含 Testcontainers） | 1670 tests，0 Failures；三轮各出现 1 个 Error，其中第 1 轮为本轮引入的 openapi.yaml 引号问题（已修复并复跑通过），第 2/3 轮的 Agent 租约/时序测试失败经**基线对照实验**（worktree 检出 71939d0 跑同款 Agent 全包测试，同样两处失败且失败更多）证明为本机环境存量竞态，与本轮改动无关；两个测试单独复跑分别 5/5、40/40 全绿；本轮改动依赖链上的全部模块三轮全绿 |
| 前端 lint / typecheck | 0 错误 / 通过 |
| 前端 vitest | 393 tests 全绿（51 文件） |
| 前端 build | 成功 |

明细与基线对照实验记录见 [regression-results.json](regression-results.json)。

**CI（最终裁决）**：run `40012687910`，attempt 1，head SHA `65e046f9479f4a64959a6a7beda6fc2021837070`，**completed / success**——独立环境下后端 1670 tests 与前端全套全部通过，证实本机两处 Agent 时序失败为环境负载竞态而非代码回归。

## H. 证据文件

- [architecture-audit.md](architecture-audit.md) — 架构审计与重整方案
- [code-cleanup.md](code-cleanup.md) — 删除清单与逐项依据
- [provider-matrix.json](provider-matrix.json) — 五通道验收矩阵（未执行项如实标注）
- [capability-probes.json](capability-probes.json) — 探针架构、分类规则与浏览器实测套件记录
- [browser-e2e.json](browser-e2e.json) — 10 项浏览器场景（E2E-01 至 E2E-10）
- [regression-results.json](regression-results.json) — 全量回归
- screenshots/ — 3 张原始截图（占位符保存、套件执行、SSE 错误气泡）

## I. 遗留与下一步

1. **真实外部生成验收待授权补跑**（Gemini + Zen + Kilo 各一套能力套件 + Gemini 浏览器问答链路；预算见 capability-probes.json）。授权后预计 30 分钟内完成。
2. 非阻断小项：E2E-06 中 example.com 对 POST 返回 405 而非 404，属于保留域行为，不影响分类正确性。

## J. 是否可进入下一阶段 Agent 验收

**可以。** Agent 模型路由依赖的 turn 网关、NATIVE_TOOLS 门控、预设分支与用户隔离在本轮全量回归中全部保持绿；Agent 真实能力验收与本轮待授权项可合并补跑。
