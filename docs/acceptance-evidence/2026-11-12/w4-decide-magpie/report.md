# W4 Magpie/Marvis provider revalidation

> 结论：**FAIL**
> 性质：provider revalidation，不是第三轮 Prompt 修改
> 证据：`LIVE_VERIFIED (staging, Magpie/Marvis, provider revalidation)` · `LOW_CONFIDENCE`
> 基线：`d6fa5133`；分支 `w4/decide-final`；Prompt 固定 `decide.system@2026-11-12.3`

## 0. 结论

Magpie/Marvis **provider 本身稳定**：3 轮共 96 次请求，0 个 5xx、0 个 504、0 个 429，
golden JSON 合法率 60/60，adversarial `invalidToolCalls=0`。失败点是**项目侧模型决策/prompt
一致率**，不是 provider 可用性；不改标签、不改分母、不把故障或决策差异洗成 PASS。

| 轮次 | golden agreement | golden JSON | 5xx | 504 | 429 | invalidToolCalls | 判定 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| r1 | **0.75（15/20）** | 1.00（20/20） | 0 | 0 | 0 | 0 | FAIL |
| r2 | **0.70（14/20）** | 1.00（20/20） | 0 | 0 | 0 | 0 | FAIL |
| r3 | **0.70（14/20）** | 1.00（20/20） | 0 | 0 | 0 | 0 | FAIL |
| **pooled（主判定）** | **43/60 = 0.7167** | **60/60 = 1.00** | **0** | **0** | **0** | **0** | **FAIL** |

pooled golden agreement 低于 0.98，故本轮 Magpie revalidation 为 **FAIL**。没有任何一轮触发
`UNSTABLE` 闸；`extraRound=null`，没有第 4 轮，没有改轮数或 pooled 分母。

Gemini 历史证据原样保留：`docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md`，
其单轮 golden agreement **0.60（12/20）**、JSON **0.75（15/20）** 仍为历史 **FAIL**，不被本报告
覆盖、合并或改写成 PASS。

---

## 1. 环境与 provider

| 项 | 值 |
| --- | --- |
| staging | `box-aae54f`，`staging-box` + `staging-model` 由 lane-c 持有并已释放 |
| app | dev `b8a58b85` 构建，镜像 `sha256:60599dff043ce6c34994bb537a7f422020219ac361f79493608fd035feca3620` |
| worker | 分支 `w4/decide-final`，镜像 `sha256:388688d232d56357bb7cd58553549bf0e4d7be08715b15bc029916da5bba4b1b` |
| Prompt | `decide.system@2026-11-12.3`，sha256 `f17fbdc8a34767bb286cb7eb64581589d8504a22bbaf23a534b424f57f68c7ce` |
| Provider | `Magpie-Marvis-DeepSeek-V4.1-Flash-W4R`，id `bc6e3ad7-6f72-4acb-a1de-f55d90fdffde` |
| 模型 | `marvis/deepseek-v4-1-flash-261017`（`OPENAI_COMPATIBLE`） |
| Gateway | `https://3425-agqyc3kteqehqgazwefpmhwulu.instabox.tech`（本次实测 `/v1/models` 200） |
| Provider test | `/test` 成功，content=`连接成功`，latencyMs=`2594`，model=`marvis/...` |
| Provider publish | `isShared=true`、`sharedStatus=PUBLISHED` |
| AGENT purpose | 测试窗口指向临时 provider；结束后恢复 Gemini `0f58581a-dbba-4e26-bafa-2e137f650563` |
| 临时 provider | 已 `DELETE` 成功；当前 DB 中 `AGENT` 指向 Gemini，临时 provider 不存在 |
| 六默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` |

Admin 探针：`GET /api/v1/admin/ai/providers` **200**（3 个 provider，AGENT=Gemini）；app 修复
`b8a58b85` 后不再出现 `No enum constant ... RERANK`。Provider key 只从
`/data/home/magpie-gw.key`（0600）读取，未打印、未提交、未写入证据。

---

## 2. 执行协议

- v1 原冻结集：`decide_baseline.v1.jsonl`，32 例（20 golden + 12 adversarial），
  canonical 指纹 `1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58`；
- 3 次独立运行，每轮 32 次请求；相邻请求 10s；轮间也等待 10s；
- `deadlineMs=30000`；successful samples 不重试；
- 5xx/504/429 计入原始分母，不剔除；本轮无 503/504/429；
- 运行时间 `2026-11-12T18:18:08Z` 至约 `2026-11-12T18:35Z`。

---

## 3. 逐轮与 pooled 聚合

### 3.1 每轮

| 轮次 | 原始 aggregate | agreement | JSON | 失败 |
| --- | --- | ---: | ---: | --- |
| r1 | `raw/rounds/r1/aggregate-decide-live-v1-magpie-r1.json` | 0.75 | 1.00 | 无 |
| r2 | `raw/rounds/r2/aggregate-decide-live-v1-magpie-r2.json` | 0.70 | 1.00 | 无 |
| r3 | `raw/rounds/r3/aggregate-decide-live-v1-magpie-r3.json` | 0.70 | 1.00 | 无 |

每轮均有独立逐 case JSONL、worker harness `run-meta-live`、provider-run-meta 与 stdout：
`raw/rounds/r1/`、`raw/rounds/r2/`、`raw/rounds/r3/`。

### 3.2 pooled（主判定，前 3 轮）

| 指标 | 值 |
| --- | ---: |
| golden cases | 60 |
| adversarial cases | 36 |
| total cases | 96 |
| golden agreement | **43/60 = 0.7167** |
| golden JSON validity | **60/60 = 1.00** |
| adversarial invalidToolCalls | **0** |
| provider 5xx / 504 / 429 | **0 / 0 / 0** |
| 稳定性 | **STABLE（无轮触发 ≥2 golden infra 或 429）** |
| 主判定 | **FAIL（agreement <0.98）** |

pooled canonical 摘要与 sha256：`raw/pooled/pooled-aggregate.json`，`sha256=aa15f29893b9bea2d74bc2953b7eaccb230fc8aa085540ed96f370331c1f41dc`。

---

## 4. Golden 不一致明细（17/60）

| round | case | expected | actual |
| ---: | --- | --- | --- |
| 1 | d-006 | FINAL | CALL_TOOL / search_project_knowledge |
| 1 | d-012 | CALL_TOOL / search_project_knowledge | ASK_USER |
| 1 | d-015 | ASK_USER | CALL_TOOL / get_project_snapshot |
| 1 | d-018 | CALL_TOOL / search_project_knowledge | FINAL |
| 1 | d-020 | FINAL | ASK_USER |
| 2 | d-006 | FINAL | CALL_TOOL / search_project_knowledge |
| 2 | d-012 | CALL_TOOL / search_project_knowledge | ASK_USER |
| 2 | d-014 | FINAL | ASK_USER |
| 2 | d-018 | CALL_TOOL / search_project_knowledge | FINAL |
| 2 | d-019 | CALL_TOOL / search_project_knowledge | ASK_USER |
| 2 | d-020 | FINAL | ASK_USER |
| 3 | d-006 | FINAL | CALL_TOOL / search_project_knowledge |
| 3 | d-012 | CALL_TOOL / search_project_knowledge | ASK_USER |
| 3 | d-014 | FINAL | ASK_USER |
| 3 | d-018 | CALL_TOOL / search_project_knowledge | FINAL |
| 3 | d-019 | CALL_TOOL / search_project_knowledge | ASK_USER |
| 3 | d-020 | FINAL | ASK_USER |

这些是有效 JSON 的模型决策不一致，不是 5xx/504/429，也不是 provider 失败。逐 case 的
expected/actual/error/HTTP/timing 全表在 `raw/pooled/case-table.md`；机器可读明细在
`raw/pooled/pooled-cases.jsonl` 与 `raw/pooled/golden-mismatches.json`。

---

## 5. 标签复核延续

- **d-012**：`expected=CALL_TOOL` 保持；Magpie 三轮均为 `ASK_USER`。此前已复核为标签意图
  成立，本轮不改标签；这是模型行为不一致，不是 provider 故障。
- **d-020**：原始 goal 是“空上下文下回答一个已知事实”，没有具体问题；Magpie 三轮均为
  `ASK_USER`。此前已判定输入不可执行，v2 仅作为文字/file 提案，**本次未使用 v2、未改 v1
  expected 或分母**。中心/用户仍可决定是否接受 v2。

本轮没有 Prompt 改动、没有额外轮次、没有因为结果调整阈值或删除 case。

---

## 6. 清理与环境合规

- 临时 `OPENAI_COMPATIBLE` provider `bc6e3ad7…` 已删除；`provider-delete.json` 为 SUCCESS。
- `AGENT` purpose 已恢复 `0f58581a-dbba-4e26-bafa-2e137f650563`；`KNOWLEDGE_CHAT` / `PLANNING`
  同样保持 Gemini。
- app/worker 均 `running/healthy`；六默认开关复核见 `raw/env/defaults-after-eval.txt`。
- `RERANK`/`DECIDE`/`PLANNING_DRAFT` 均为 `java`；memory job disabled；token mode 均 `static`。
- key 未进入报告、日志或 git；临时 provider 的 apiKey 仅出现在一次性 create 请求中，响应
  证据已脱敏。
- staging 双锁已释放；当前资源锁目录不再有 lane-c 的 staging 锁。

早先一次使用旧 app 的 preflight 在 `GET /admin/ai/providers` 返回 500（旧 app 不认识
`ModelCapability.RERANK`），没有跑任何 live 轮次，且已创建/清理临时 provider 并恢复 AGENT；
证据保留在 `raw/attempt-20261113-preflight/`，不属于本报告的三轮结果。

---

## 7. 限定

1. 结论只适用于 staging `box-aae54f` + Magpie/Marvis 当前 gateway + 本次 3 轮样本，不能外推
   到其它 provider、生产或其它模型；样本 96 < 200，`LOW_CONFIDENCE`。
2. Provider 稳定不等于产品可灰度；W4 pooled agreement 仍 FAIL，最终是否把 Magpie 替换为
   W4 provider 由中心/用户决定。
3. Gemini 历史 FAIL 保留；Magpie 是 provider revalidation，不是第三轮 Prompt，也没有把失败
   归因给 Magpie provider。

---

## 8. 证据索引

| 证据 | 路径 |
| --- | --- |
| 每轮 aggregate / 逐 case / meta | `raw/rounds/r1/`、`raw/rounds/r2/`、`raw/rounds/r3/` |
| pooled aggregate / cases / summary / run-meta | `raw/pooled/` |
| 逐 case 可读表 | `raw/pooled/case-table.md` |
| golden 不一致 | `raw/pooled/golden-mismatches.json` |
| provider before/assign/create/test/publish/after/delete | `raw/pooled/provider/` |
| admin 200 探针 | `raw/provider/probe-admin.json` |
| app/worker 运行日志 | `raw/logs/` |
| 六默认开关 after | `raw/env/defaults-after-eval.txt` |
| 旧 app preflight 失败（非本轮结果） | `raw/attempt-20261113-preflight/` |
| Gemini 历史 FAIL | `docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md` |
