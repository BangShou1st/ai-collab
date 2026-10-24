# P4-MODEL-QUICK-CONNECT-REAL-ACCEPTANCE-01：统一模型接入真实验收与证据收口

**交付状态：`MODEL_REAL_CONNECT_ACCEPTED`**

本轮是 P4-MODEL-QUICK-CONNECT-02 唯一遗留项（真实外部模型生成与流式验证）的受控补验收。**零生产代码修改**，全部结论来自 AI Collab 自己的统一能力测试套件与真实浏览器知识问答链路。

核心结论：

1. **Gemini**（`gemini-3.5-flash-lite`）：真实普通生成 PASS、真实流式 PASS、真实浏览器知识问答（SSE + 引用 + 持久化 + 刷新复核）PASS。
2. **Kilo Gateway Free**（`nvidia/nemotron-3.5-lightning:free`，本轮从当前免费目录实际选出）：真实普通生成 PASS、真实流式 PASS、真实原生工具探针 PASS（套件运行两次，全部一致 PASS）。
3. **OpenCode Zen Free**：已知上游限流，本轮按授权约束 **0 次生成请求**，标记 `UPSTREAM_RATE_LIMITED_NOT_RETESTED`。
4. **PLANNING_JSON**：NOT_EXECUTED（预算优先级约束，详见第 F 节）；规划用途就绪度如实展示「未验证」，未以 CHAT 成功外推。

---

## A. Git

- 任务起始基线：`44dfc5eb9dafe953c42dcf55ac8d9f26918a6fa1`（dev = origin/dev，工作区干净）
- `main` / `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程未触碰）
- 本轮改动：仅上一轮证据文档修正 + 本轮证据文档（见第 H 节），无生产代码修改

## B. 上一轮证据文档修正（任务书第十节三项）

| 问题 | 修正 |
| --- | --- |
| `regression-results.json` 末尾缺字段名的字符串导致 JSON 不可解析 | 改为合法 `ci` 字段（run/attempt/head_sha/status/conclusion），原数据未改写；`JSON.parse` 验证通过 |
| `browser-e2e.json` 实际记录 E2E-01 至 E2E-10 共 10 项全 PASS，摘要写 9 PASS | summary 改为「10 PASS / 0 FAIL（实际记录 E2E-01 至 E2E-10 共 10 个场景）」；README 两处「9 项/9 PASS」同步改为 10 |
| Git 统计口径 | README 第 A 节补写整轮口径（`71939d0..44dfc5e`：70 files，+3196 / -2133，净增 1063 行）；「局部净删 1315 行」保留并明确标注为生产代码局部口径，不是整轮 Git 净变化 |

四个证据 JSON 均以 Node `JSON.parse` 验证通过。

## C. 环境与网络

- 前端 Vite dev 5173、后端 local profile 8080、postgres/redis/minio（Docker，healthy）、Flyway V66 已应用（`add user ai provider probe result`）。
- Kilo Gateway（ai-gateway.kilo.ai）本机直连可达（HTTP 200）。
- Gemini（generativelanguage.googleapis.com）本机直连超时，沿用历史验收已获用户允许的系统代理 `127.0.0.1:7897`，以后端 JVM 参数 `-Dhttps.proxyHost/-Dhttps.proxyPort`（+http 同参）启动后端执行真实探针；未修改全局网络设置。验收结束后后端已恢复常规启动（无代理），日志落在 `ai-collab-backend/target/local-dev/`。
- 浏览器：browser-skill（bsk 0.3.3）+ Edge，复用既有登录态，账户为历史验收专用合成账户 **U04 合成管理员（p3u04_admin）**，未读取、未输出任何凭据。

## D. Gemini 真实验收（详见 gemini-results.json）

| 项 | 结果 |
| --- | --- |
| 配置 | `Gemini 3.5 Flash Lite` · GEMINI · API Key 已配置（只写不读）· 能力声明 CHAT,STREAMING · 配置版本 `2026-10-23T18:47:42Z`（本轮未变更） |
| CONNECTIVITY | **PASS**（真实生成，latency 608ms） |
| STREAMING | **PASS**（真实流式，latency 477ms） |
| 模型发现 | INCONCLUSIVE（旧格式硬编码 api_path 下发现 GET 404，正确分类并提示手输降级；不影响生成探针） |
| 浏览器知识问答 | **PASS**（隔离验收项目真实提问，SSE 完整结束，回答非空，引用真实，落库并刷新复核） |

知识问答明细：问题「哪些缺陷必须在生产发布前关闭？」→ 回答「根据项目资料，所有**阻断级缺陷**必须在生产发布前关闭 [S1]。」；引用 [S1] = `上线验收规程.md`（上线门禁，相似度 70.2%），引用原文与回答语义一致；`knowledge_message` 落库 model=`gemini-3.5-flash-lite`、latency 913ms、prompt 755 / completion 21 tokens；页面刷新后问答与引用仍完整展示。

## E. Kilo Gateway Free 真实验收（详见 kilo-results.json / provider-selection.json）

- 通过内置「刷新模型」获取**当前**免费目录（只读 GET，14 个模型），原保存模型 `minimax/minimax-m3.1-flash:free` **已不在目录中**（上游下线）。
- 按「稳定、聊天+流式、低成本」选择 **`nvidia/nemotron-3.5-lightning:free`**（在准入列表中），保存至 U04 测试用户 Kilo 预设行（仅改 model_name，is_default 与能力声明未动，其他用户配置未触碰）。原模型已记录（第 G 节说明未回滚原因）。
- **本轮实际验证成功的 Kilo 免费模型 ID：`nvidia/nemotron-3.5-lightning:free`。**
- 套件运行两次：第一次 连接/流式/原生工具全 PASS；第二次（复查）全部再次 PASS，落库结果被第二次覆盖（provider+mode 唯一约束），两次结论一致。
- 最终落库：CONNECTIVITY PASS（17608ms）、STREAMING PASS（14091ms）、NATIVE_TOOLS PASS（1051ms），全部 fresh。
- 未依赖任何已删除的旧 Kilo `/test` 端点（该端点在上一轮已移除，本轮走统一 `test-capability`）。

## F. PLANNING_JSON 与 NATIVE_TOOLS

- **NATIVE_TOOLS（Kilo）**：已执行，PASS（无副作用合成工具探针，未创建任何真实业务数据）。
- **PLANNING_JSON**：**NOT_EXECUTED**。触发条件本已满足（AI 规划用途默认指向 Kilo），但统一套件无单步跳过机制——执行规划探针必须连带重跑 CONNECTIVITY+STREAMING（2 次）+ 规划契约（≤4 次），而当时剩余预算仅 5 次（中途一次误重跑消耗 3 次），无法保证不突破 14 次硬上限。按任务书优先级规则（先保证连接/流式/知识问答）如实放弃。**CHAT/工具 PASS 不外推为规划可用**，AI 规划用途就绪度展示「未验证」。
- Gemini 侧规划/工具探针：NOT_EXECUTED（该配置未启用规划/Agent 用途，能力声明仅 CHAT,STREAMING）。

## G. 用户配置变更记录与恢复说明

| 配置 | 变更 | 恢复说明 |
| --- | --- | --- |
| U04 Kilo 预设行（`6854fb22`） | model_name：`minimax/minimax-m3.1-flash:free` → `nvidia/nemotron-3.5-lightning:free`（经 UI 保存，免费准入校验通过） | **未回滚**：原模型已被上游从免费目录移除（保存旧值将得到一个必然失败的配置），且回滚会使全部 PASS 结论立即 STALE。is_default、capabilities、API Key 均未改动 |
| U04 Gemini 配置（`b89ec1b4`） | 未变更 | — |
| 用途分配 | 未变更（KNOWLEDGE_CHAT → Gemini；AI 规划/Agent 使用默认 → Kilo） | — |
| STALE 验收临时配置 | 新建假端点配置 → 探针 INCONCLUSIVE → 修改模型字段 → 验证 STALE 展示 → 删除（探针行级联清理，0 残留） | 已完全清理 |

## H. 本轮证据文件

- [provider-selection.json](provider-selection.json) — Kilo 当前免费目录与选型依据（发现时间、准入依据、原配置记录）
- [gemini-results.json](gemini-results.json) — Gemini 探针 + 浏览器问答明细
- [kilo-results.json](kilo-results.json) — Kilo 两次套件运行明细
- [capability-probe-results.json](capability-probe-results.json) — 探针落库凭据、STALE 验证、用途就绪度
- [request-ledger.json](request-ledger.json) — 请求预算台账（9/14）
- [browser-e2e.json](browser-e2e.json) — 本轮浏览器场景（REAL-01 至 REAL-06）
- [regression-results.json](regression-results.json) — 文档修正回归验证与环境记录
- screenshots/ — 5 张原始截图

## I. 是否可进入下一阶段 Agent 真实业务验收

**具备条件。** Agent 用途就绪度已为「可用」（Kilo NATIVE_TOOLS 真实 PASS），知识问答链路（Gemini 流式）真实可用；Agent 真实任务验收只需在新的授权轮中针对 Agent 执行链（审批、租约、SSE 事件）补验收即可。AI 规划用途因 PLANNING_JSON 未执行仍为「未验证」，Agent 规划场景验收前建议先补规划探针。

## J. 遗留

1. PLANNING_JSON 真实探针 NOT_EXECUTED（预算约束；建议下一轮优先补跑）。
2. Zen Free 上游限流，待恢复后经用户确认单独复验。
3. 本机直连 Gemini 不可达为环境事实，未来涉及 Gemini 真实调用的会话需沿用 JVM 代理启动方式（见第 C 节）。
