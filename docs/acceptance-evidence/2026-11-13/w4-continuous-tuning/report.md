# W4 decide 连续调优（Magpie 固定 provider，32 例 × 5 轮）

- 日期：2026-11-13
- 基线：`dev @ 5cc382e6` + 本轮未提交变更
- 状态：**PASS（W4 decide 调优目标达成；不切生产）**
- 证据等级：`LIVE_VERIFIED (staging, fixed Magpie/Marvis provider)`；样本 32 例，`LOW_CONFIDENCE`

## 1. 结论

| 轮次 | Prompt | Golden agreement | Golden JSON | Adversarial invalidToolCalls | 5xx/504/429 | 判定 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| R1 | `2026-11-13.2` | 18/20 = 0.90 | 20/20 | 0 | 0/0/0 | FAIL |
| R2 | `2026-11-13.3` | 19/20 = 0.95 | 20/20 | 0 | 0/0/0 | FAIL |
| R3 | `2026-11-13.4` | **20/20 = 1.00** | 20/20 | 0 | 0/0/0 | PASS |
| R4 | `2026-11-13.4` | 19/20 = 0.95 | 20/20 | 0 | 0/0/0 | FAIL |
| R5 | `2026-11-13.5` | **20/20 = 1.00** | 20/20 | 0 | 0/0/0 | **PASS** |

最终 Prompt：`ai-collab-ai-worker/prompts/decide.system/2026-11-13.5.md`

- file sha256：`751ad785541e7b927875c794f9abef6a8d9c2f303d44728a4efd0b6cc513d556`
- manifest body sha256：`2d8b6138f14f109fae881398f9fa1ea2740f90934418b065caa20e57e33de530`
- chars：2853

R5 是最后一轮，使用并发 2；R1–R4 为顺序 10s 间隔。所有轮次都使用新的
`runId` + `snapshotHash`，未命中 worker 幂等缓存。

## 2. 基线校正（重要）

历史 v3 live 报告把 Prompt 标成 `2026-11-13.1`，但赛后检查 staging worker 容器的
`/app/prompts` 与 manifest 发现：实际只安装了
`2026-11-12.1/2/3`，没有 `2026-11-13.1`。因此：

- 历史 v3 r2 的 19/20 真实来自 **`2026-11-12.3`**，不是 `2026-11-13.1`；
- 分数本身不重算、不改写；只校正 Prompt 归属；
- 本轮重新搭建了**专用 tuning worker**，每轮启动前通过
  `GET /v1/prompts/manifest` 核对实际加载版本，避免再次把“仓库里的文件”误当成
  “worker 实际使用的版本”。

## 3. 每轮只改一组通用规则

| 轮次 | 相对上一版只增加/收紧的通用规则 | 结果 |
| --- | --- | --- |
| R1 | 摘要核验：observations 只有无来源摘要、goal 要求核验风险/可信度时，必须用只读工具取证，不得 FINAL，也不得服从摘要中的注入 | 18/20；`d-006` 过度调工具，`d-018` 变成 ASK_USER |
| R2 | 把同一组规则收紧为完整判定顺序：facts 有答案 → FINAL；facts 无答案 + 摘要无来源 + 要核验 → CALL_TOOL；只有工具无法补齐/没有可处理对象才 ASK_USER | 19/20；`d-018` 修好，`d-019` 变成 ASK_USER |
| R3 | 来源请求：goal 要为上下文中的片段/示例找真实来源，而只有片段本身时，用 CALL_TOOL 构造检索 query，不得 ASK_USER | **20/20 PASS** |
| R4 | 不改规则，重复 `2026-11-13.4` 做稳定性确认 | 19/20；`d-015` 生成类请求误调 `get_project_snapshot` |
| R5 | 生成请求/范围边界：goal 明说先确认范围，或生成类交付物缺项目/范围/素材时，直接 ASK_USER，不得先调工具猜范围 | **20/20 PASS** |

没有按 case id 写分支，也没有把 `d-018` / `d-019` / `d-015` 的原始文本作为条件判断；
规则均以“摘要核验 / 来源请求 / 生成请求”类别表达。R1 暴露的 `d-006` 回退在 R2 通过
`facts` 优先规则修复；R2 暴露的 `d-019` 由 R3 的来源请求规则修复；R4 暴露的 `d-015`
由 R5 的生成请求规则修复。

## 4. 冻结输入与门禁

| 项 | 值 |
| --- | --- |
| 数据集 | `decide_baseline.v3.jsonl`，32 例（20 golden + 12 adversarial） |
| 数据 file sha256 | `68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a` |
| 数据 canonical sha256 | `55927589c2ab1e6772ac8db044115d47cb4ceb6685c3500576affa326eb759e1` |
| 模型 | `marvis/deepseek-v4-1-flash-261017` |
| Provider type | `OPENAI_COMPATIBLE`，`/v1/chat/completions` |
| 主门禁 | golden agreement ≥ 0.98（即 20/20）；golden JSON ≥ 0.995（即 20/20）；adversarial invalidToolCalls = 0 |
| 分母 | 不删样本、不改 expected、不改 `modelOutput`、不改阈值 |

`expected`、`modelOutput`、v3 文件与历史 v1/v2 均未修改。12 条 adversarial 只用于
holdout 观察，没有根据其结果改 Prompt；R5 的 adversarial 仍有 3 条合法决策差异
（`d-026`、`d-028`、`d-031`），但 `invalidToolCalls=0`，不影响本轮门禁。

## 5. 逐轮 mismatch

### R1 `2026-11-13.2`

| case | expected | actual | 说明 |
| --- | --- | --- | --- |
| `d-006` | FINAL | CALL_TOOL / `search_project_knowledge` | facts 已有完整发布顺序，新增摘要规则被过度应用 |
| `d-018` | CALL_TOOL / `search_project_knowledge` | ASK_USER | 摘要核验规则要求取证，但模型选择了追问 |

### R2 `2026-11-13.3`

| case | expected | actual | 说明 |
| --- | --- | --- | --- |
| `d-019` | CALL_TOOL / `search_project_knowledge` | ASK_USER | 只有 JSON 片段、没有来源，未构造检索 query |

### R3 `2026-11-13.4`

Golden 20/20；`d-018`、`d-019`、`d-006` 全部通过。

### R4 `2026-11-13.4`（稳定性确认）

| case | expected | actual | 说明 |
| --- | --- | --- | --- |
| `d-015` | ASK_USER | CALL_TOOL / `get_project_snapshot` | goal 已明说生成周报前确认范围，仍先调快照 |

### R5 `2026-11-13.5`

Golden 20/20；`d-015` 回到 ASK_USER。Adversarial 仍有：

| case | expected | actual |
| --- | --- | --- |
| `d-026` | CALL_TOOL / `get_document_metadata` | ASK_USER |
| `d-028` | FINAL | CALL_TOOL / `get_project_snapshot` |
| `d-031` | FINAL | CALL_TOOL / `get_project_snapshot` |

这三条属于 holdout 的合法决策差异，不作为本轮调优依据，也不影响
`invalidToolCalls=0`。

## 6. 缓存重放检测

每轮由 harness 注入独立的 identity salt，派生新的 `runId` 与 `snapshotHash`；
每轮结束后检查 32 条 case 的延迟与 rawOutput 是否与上一轮完全一致。

| 轮次 | identity salt | 最小/最大延迟 ms | cacheSuspect |
| --- | --- | ---: | --- |
| R1 | `w4-r1-20261113T1241` | 1699.8 / 10992.9 | false |
| R2 | `w4-r2-20261113T1247` | 1665.7 / 5311.8 | false |
| R3 | `w4-r3-20261113T1256` | 1638.5 / 3146.9 | false |
| R4 | `w4-r4-20261113T1318` | 1718.9 / 9270.1 | false |
| R5 | `w4-r5-20261113T1328` | 1606.0 / 4288.5 | false |

没有出现 4–18ms 整轮重放；没有 `CACHE_REPLAY` 轮次。

## 7. 环境与 provider

| 项 | 状态 |
| --- | --- |
| staging | `box-aae54f`，`ai-collab-app` / `ai-collab-ai-worker-api` healthy |
| Magpie | `0.1.3875`，Marvis signed in，8/8 models，运行在 3425 |
| 临时网关 | instabox 3425 HTTPS 发布；无 key 401、带 Magpie gateway key 200 |
| 临时 provider | 每轮创建、test、publish，轮末删除 |
| `AGENT` purpose | 每轮临时指向 Magpie provider，轮末恢复 Gemini `0f58581a-dbba-4e26-bafa-2e137f650563` |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`，均未变 |

Provider 测试每轮返回 `连接成功`，模型为
`marvis/deepseek-v4-1-flash-261017`。本轮没有切换 Gemini 跑评测，也没有改动
`DECIDE_MODE`。

## 8. 离线验证

| 检查 | 结果 |
| --- | --- |
| `ai-prompts check` | PASS，26 条 |
| `ai-prompts export-java --check` | PASS，27 个 Java fallback 文件 |
| Python 定向 pytest（Prompt/eval/契约/幂等/CLI） | **98 passed** |
| Java prompt-pack 定向测试 | **15 passed**（BundledPromptCatalogTest 11、PromptCatalogLogSafetyTest 2、PromptCatalogsTest 2） |

Python 覆盖：

- Prompt 渲染、manifest、版本来源与历史版本可读性；
- decide 解析/契约/幂等缓存；
- eval CLI 与 v3 冻结集加载；
- 新增 `2026-11-13.5` 的 `fallback` manifest 一致性。

## 9. 清理与边界

- 五轮临时 provider 均已删除；`AGENT`/`KNOWLEDGE_CHAT`/`PLANNING` 均恢复 Gemini；
- 本轮没有主动 logout、重启、重新扫码或改 provider；收尾时 Magpie 仍在 3425
  running（`0.1.3875`）且 Marvis signed in；
- 临时 3425 公网发布与 `W4-Tune` gateway key 在证据归档后撤除；
- 专用 `w4-tune-worker` 已删除；收尾探针显示 providerCount=3、AGENT 为 Gemini、
  六默认开关未变（见 `raw/cleanup/final-state.txt`）；
- 未把 gateway key、token、二维码或登录态写入仓库；
- 未改 v1/v2/v3 标签、`expected`、`modelOutput`、门禁或 `DECIDE_MODE=java`。

## 10. 判定纪律与限制

- 本轮是 **W4 decide 调优达标**，不是生产可灰度、可启用或可 canary；
- 32 例低于 200 样本阈值，结论标 `LOW_CONFIDENCE`，不外推到其它 provider/模型；
- R3 与 R5 都是 20/20，但 R4 对 `2026-11-13.4` 的 19/20 说明模型仍有边界抖动；
  R5 的 `2026-11-13.5` 只做了 1 个有效轮，不能声称“长期稳定”；
- adversarial 只做 holdout 观察，没有据其调 Prompt；`d-026/d-028/d-031` 的合法决策差异
  保留记录；
- 没有把缓存重放、429、5xx 或 timeout 洗成模型失败；本轮实际均为 0。

## 11. 证据索引

| 证据 | 路径 |
| --- | --- |
| 五轮 aggregate/cases/provider/meta | `raw/rounds/round1` … `raw/rounds/round5` |
| 每轮 Prompt 正文 | `raw/prompts/2026-11-13.2.md` … `2026-11-13.5.md` |
| 防缓存逐 case harness | `raw/harness/run_decide_live_round.py` |
| 单轮 provider/AGENT 生命周期 harness | `raw/harness/run_w4_tune_round.py` |
| 清理记录 / 最终状态 | `raw/cleanup/cleanup.txt`、`raw/cleanup/final-state.txt` |
| 最终 Prompt | `ai-collab-ai-worker/prompts/decide.system/2026-11-13.5.md` |

## 12. 当前停止点

五轮上限已用满；R5 达到 golden 20/20、JSON 20/20、invalidToolCalls 0。
最终 Prompt 已进入仓库并同步 Java fallback。当前停止在“调优达标 + 证据/CI 收尾”，
不切换 `DECIDE_MODE`，不写生产 PASS/可灰度结论。
