# W3.5 修复轮：staging 重建、Gemini 3.5 重测与评测口径修复（2026-11-12）

> 日期：2026-11-12（Asia/Shanghai；staging 日志时间为 UTC）
> 范围：D1/D2/D3/D5/D6/D7/D10 修复后的 staging 重建与重测；W3 3s 预算；decide/planning/memory 口径
> 环境：instabox `box-aae54f` staging（compose `ai-collab-deploy`；app 8080/8081，worker 9100 仅 `ai` 网）
> 模型：**`gemini-3.5-flash-lite`**（免费档，15 RPM；`capabilities=["CHAT","STREAMING"]`；thinking 覆盖 `budget=1`）
> 证据等级：**LIVE_VERIFIED (staging, real provider)**；门禁样本 15 次，`LOW_CONFIDENCE`
> 结论：**C-1 FAIL（tier 8 失败率 13.33%，429=0；overhead 达标；1 次用户请求 = 1 次 provider 调用成立）**；D2 修复 **PASS**；D1/D3/D5 有单测与 staging 冒烟；D6/D7/D10 为归档真实输出 replay（非新模型调用）。

---

## 0. 结论先行

| 项目 | 结果 |
| --- | --- |
| staging 重建 | backend `b95399e17cd8` / worker `265b77c6490c`；app/worker 均 healthy |
| D2 健康检查 | app `Health.Status=healthy`、`FailingStreak=0`、8081 `/actuator/health=UP`（修复前 178 次 404） |
| D5 planning 默认 deadline | 契约默认 30000；staging 重建后 `/v1/planning/draft` 默认请求未再 503（见 §6.4 冒烟） |
| 1:1 provider 调用 | **PASS**：修复后 1 条 `Model API request` / 1 条 200；control（thinking 覆盖关闭）每次 2 条 / 400+200 |
| C-1 tier 8 门禁（3s） | **FAIL**：15 次尝试 / 13 成功 / 2 `DEADLINE_EXCEEDED`（3006.2 / 3007.5ms），失败率 **13.33%**（阈值 ≤1%），429=**0**，known **86.67%**（阈值 ≥99%） |
| overhead | **PASS**：P50/P95/P99 = 18.4 / 27.6 / 27.6ms（阈值 800/1500ms） |
| tier 20/50 与 60 次 | **NOT_RUN**（tier 8 失败率 >1%，按停止规则） |
| C-2 memory | 归档真实输出 replay：exact 0/33、0/20；semantic 5/33、5/20（precision 0.1515、recall 0.25）；仅诊断，不设门禁 |
| C-2 planning | 归档真实输出 mutation：105/105 = **1.0**；旧 request-level adversarial 0/5 单列 |
| C-2 decide | 归档 re-score：golden 19 例 agreement **0.7368**、JSON **1.0**；live 32 例：6 例 504 超时（429=0），剔除后 golden **13/18=0.7222**、JSON **1.0**；agreement FAIL、JSON PASS |
| 是否可灰度 | **否**；`RERANK_MODE=java` 保持；结论仅对 staging + 该 provider + 该额度档成立 |

---

## 1. 代码与镜像

| 项 | 值 |
| --- | --- |
| 分支 / 提交 | `dev`；`6d96741f` D1+Gemini、`ded49902` D2、`a8dceee7` D3、`42fee251` D5；`dfe81893`/`b9a0b621` D6/D7/D10 |
| backend 镜像 | `ai-collab-backend:local` = `sha256:b95399e17cd8…`（08:10:00Z，build exit 0，24s） |
| worker 镜像 | `ai-collab-ai-worker:dev` = `sha256:265b77c6490c…`（07:50:40Z） |
| app | `healthy`、`FailingStreak=0`、restart 0；8081 UP |
| worker | `healthy`；`/healthz`/`/readyz` 200 |
| Flyway | 最新 **V91**（`ai job`），success=t；与代码库 91 个迁移一致 |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`——全部未变 |
| 测量开关 | `AI_GEMINI_THINKING_ENABLED=true`、`AI_GEMINI_THINKING_BUDGET=1`；`JAVA_TOOL_OPTIONS` 含 `JsonHttpModelClient=DEBUG`（仓库外 compose override） |

> 构建过程记录：第一版镜像 `17a54d9` 因 `ai.gemini-thinking.models` 的 `models: {}` 空 inline map 被扁平化成空串而启动失败；第二版 `71ebb87` 仍保留该行，仍失败；第三版 `b95399e` 删掉整个 `models: {}` 后启动成功。失败根因保留在 `raw/phase1/startup-bind-failure*.excerpt.txt` 与 `blocked-prefix-mismatch.md`，这是修复轮发现的真实启动级缺陷。

---

## 2. D2 验收（staging，LIVE_VERIFIED）

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| `docker inspect` Health.Status | `unhealthy`（FailingStreak 178） | **`healthy`（FailingStreak 0）** |
| 探针输出 | `HTTP/1.1 404`（8080/actuator/health） | 8081/actuator/health `{"status":"UP"}` |
| 镜像 HEALTHCHECK | `127.0.0.1:8080/actuator/health` | `127.0.0.1:8081/actuator/health` |

证据：`raw/phase1/` 的容器健康检查日志；D2 单测 `ContainerHealthcheckContractTest`（2/2）。

---

## 3. 1 次用户请求 = 1 次 provider 调用（LIVE_VERIFIED）

DEBUG `JsonHttpModelClient` 逐条计数（原始日志已拉回仓库）：

| 运行 | 生效配置 | `Model API request` | provider 状态 | 端到端 | 说明 |
| --- | --- | ---: | --- | ---: | --- |
| 修复后 model-call | `AI_GEMINI_THINKING_*` + `budget=1` | **1** | **200×1** | 899ms（网关 799ms） | 一次请求完成；usage in 10 / out 1 |
| control-01 | 无 thinking env；capabilities 无 THINKING | **2** | 400×1 + 200×1 | 1063.5ms | 发 `budget=0` → 400 → fallback 去字段重发 |
| control-02 | 同上 | **2** | 400×1 + 200×1 | 8294.7ms | 尾部尖峰 |
| control-03 | 同上 | **2** | 400×1 + 200×1 | 562.2ms | |
| tier 8 门禁（15 次） | `budget=1` | **15** | 200×13（2 次 3s 截断无响应） | — | **每次用户请求恰好 1 次 provider 调用** |

证据：`raw/phase1/log-fixed-01.txt`、`model-call-fixed-01.json`、`log-control-0{1,2,3}.txt`、`log-gate-8-t8b.txt`。

> 说明：control 的 2 次调用不是“模型失败”，而是 3.5 对 `thinkingBudget=0` 返回 400 后适配器的有界 fallback；修复后用 `budget=1` 避免 400，因此恢复 1:1。该计数只对非流式 `post()` 成立；本轮门禁走非流式端点。

---

## 4. C-1 tier 8 门禁（staging，3s，LOW_CONFIDENCE）

参数：`deadlineMs=3000`、`min-interval-ms=10000`（≤6 RPM）、15 次、tier 8。

| 指标 | 实测 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| 尝试 / 成功样本 | 15 / 13 | — | — |
| 失败（全部 `DEADLINE_EXCEEDED`） | **2（13.33%）** | ≤1% | **FAIL** |
| 429 | **0** | 单列、不计模型失败 | — |
| known ratio | **86.67%** | ≥99% | **FAIL** |
| overhead P50 / P95 / P99 | 18.4 / **27.6** / **27.6** ms | P95 ≤800 / P99 ≤1500 | **PASS** |
| model P50 / P95 / P99 | 778 / 2748 / 2748 ms | 记录 | — |
| total P50 / P95 / P99 | 793 / 2764 / 2764 ms | 记录 | — |
| provider 调用数 | 15（13×200，2 无响应） | 1:1 | **PASS** |
| confidence | LOW_CONFIDENCE | — | — |

失败样本：`r03` 3006.2ms、`r09` 3007.5ms，均为 3s 截断的 504。**429=0**，因此失败不是限流触发；按任务规则无需因 429 重跑该档。

**判定：FAIL（仅对 staging + `gemini-3.5-flash-lite` + 当前免费档额度成立）。** 与 S1.5 的 3.1 形态一致：中位 778ms、尾部 2748ms 并出现 3s 截断；3.5 没有改变“中位快、尾部尖峰”的形态。tier 20/50 与 60 次观测按停止规则 **NOT_RUN**，不得外推。

---

## 5. 30s 诊断（仅归因，不计门禁）

参数：tier 8、`deadlineMs=30000`、15 次、10s 间隔。

| 指标 | 实测 |
| --- | ---: |
| 尝试 / 成功 | 15 / **15** |
| 失败率 | **0%** |
| 429 | **0** |
| overhead P50 / P95 | 18.4 / 39.2 ms |
| model P50 / P95 / P99 | 730 / **2978** / 2978 ms |
| total P95 | 2994.9 ms |

解读：门禁里两次 3s 截断的样本在 30s 下都能完成，尾部约 **2.98s**，紧贴 3s 门限。因此 tier 8 的 13.33% 失败是 **provider 尾部延迟贴边**，不是硬失败、不是限流（429=0），也不是服务实现开销（overhead <40ms）。这与 S1.5 的“中位快、尾部尖峰”形态一致；3.5 没有消除尾延迟，只是把中位保持在 ~730ms。

---

## 6. C-2 评测重跑

> 统一口径：memory/planning 的 real fixture 是 **S3 归档真实模型输出 replay**，本次没有重新调用模型；报告显式标 `evidence=LIVE_VERIFIED (archived real model output)`、`model=gemini-3.1-flash-lite`。decide 的 live 复跑见 §6.3。

### 6.1 memory（D7 修复后）

| 口径 / 输入 | precision | recall | F1 | duplicateRate | schemaPassRate |
| --- | ---: | ---: | ---: | ---: | ---: |
| 归档真实输出，`--matcher exact`（旧口径） | 0.00（0/33） | 0.00（0/20） | 0.00 | 0.00 | 1.00 |
| 归档真实输出，`--matcher semantic`（新口径） | **0.1515（5/33）** | **0.25（5/20）** | **0.1887** | 0.00 | 1.00 |
| v1/v2 stub，`exact`（回归） | 0.90 | 0.90 | 0.90 | 0.05 | 1.00 |

semantic 审计命中 5 条（m-001 / m-003 / m-014 的“走/至”改写，m-011 / m-013 的“必须/必须经过”改写），报告含 `matchAudit` 与 `diagnosticOnly=true`。**precision/recall 只作诊断、不设门禁**；0.25 recall 仍不能解释为模型记忆质量结论。v2 判定集 14 case / 20 judgement 全部带 `factRefs`；v1 指纹与旧报告 sha 未变（`74177ebe…`）。

### 6.2 planning（D6 定向变异）

| 输入 | variants | rejected | rejectedRate |
| --- | ---: | ---: | ---: |
| `planning_baseline.v1`（stub，仅 golden 合法输出） | 80 | 80 | **1.0** |
| `planning_real_gemini31.v1`（归档真实输出，21 条全部合法） | 105 | 105 | **1.0** |

旧 request-level adversarial 子集仍为 `rejectedRate=0.00（0/5）`，与 S3 一致；报告明确它不再是校验器证据，校验器证据看 `bySubset.adversarialMutations`。`duplicateTitles=7` 只记录、不评估质量。

### 6.3 decide（同口径复跑）

**归档 re-score（已核实）**：`decide_real_gemini31.v1` 31/32 例（d-018 为 provider 503，无输出），golden 19 例：`decisionAgreement=0.7368（14/19）`、`jsonValidityRate=1.0（19/19）`；冻结口径含 503 时为 14/20=0.70、19/20=0.95。**JSON 项剔除基础设施失败后 1.0，不构成 FAIL；agreement 仍 FAIL。**

**live 复跑（2026-11-12 08:27–08:36Z，`gemini-3.1-flash-lite`，10s 间隔，32 例）**：

| 指标 | 冻结口径（含超时） | 剔除基础设施失败后 |
| --- | ---: | ---: |
| golden `decisionAgreement` | 13/20 = 0.65 | **13/18 = 0.7222** |
| golden `jsonValidityRate` | 18/20 = 0.90 | **18/18 = 1.0** |
| adversarial `rejectedRate` | 4/12 = 0.333 | **0/8 = 0.0** |
| adversarial `invalidToolCalls` | 0 | 0 |
| 429 | **0** | — |

6 例失败全部为 `DEADLINE_EXCEEDED`（HTTP 504，`rateLimited=0`）：golden d-018 / d-019，adversarial d-024 / d-027 / d-029 / d-030。它们是基础设施/超时，不是 JSON 解析失败，也不是模型输出非法。剔除后 golden agreement **0.7222** 仍 <0.98 → **FAIL**；JSON **1.0** → **不构成 FAIL**；adversarial 4 例 rejected 全是超时，剔除后为 0/8。与归档 re-score 的 14/19=0.7368 / JSON 1.0 同向，差异属于模型抖动与超时样本不同。

> 标签说明：该 live aggregate 由 S3 的 decide harness 生成，`evidence` 仍是冻结核 `TESTED (worker)`；本次输入是 live worker 路径。D10 的 `--evidence` 已在 memory/planning replay 中实际使用；decide live harness 未改，标签以本报告为准（下一轮应显式传 `--evidence "LIVE_VERIFIED (staging, real model)"`）。

### 6.4 D5 / D3 staging 冒烟

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| D5：`/v1/planning/draft` 默认（不带 `deadlineMs`） | **HTTP 200**（11.1s，真实 plan 输出），不再 503 | `raw/phase2/d5-planning-default-smoke.json` |
| D5：显式 `deadlineMs=60000` | **422**，`less_than_equal 30000`（契约层拒绝，未到网关） | `raw/phase2/d5-planning-60000.json` |
| D3：`/api/v1/auth/registration-policy` | `enabled=false`，与 app env `AUTH_PUBLIC_REGISTRATION_ENABLED=false` 一致；未发 register | `raw/phase2/d3-registration-policy.json` |

---

## 7. 缺陷修复对应

| 缺陷 | 修复 | 证据 |
| --- | --- | --- |
| D1 | OpenAI 兼容适配器白名单扩展参数（默认关闭，列表绑定；complete/completeStream/turn 生效） | `6d96741f`、`ProviderExtensionParamsTest`（6/6）、`GeminiThinkingOverrideTest`（13/13） |
| D2 | HEALTHCHECK 改 8081；EXPOSE 8080/8081 | `ded49902`、staging `healthy` |
| D3 | 公开注册开关统一 env 单一事实源 | `a8dceee7`、`RegistrationPolicyTest`（5/5） |
| D5 | Python 契约 + Java shadow 默认/上限统一 30000；>30000 契约层 422 | `42fee251`、契约边界测试、staging 冒烟 |
| D6 | 定向变异（planning 5 类 / decide 4 类）；真实输出 replay 集 | `dfe81893`、`test_eval_mutation`（mutationRejectedRate 1.0） |
| D7 | semantic 匹配 + v2 判定集 factRefs + 三组对照骨架 | `dfe81893` + `b9a0b621`；C 未实现，门禁 NOT_EVALUATED |
| D10 | 显式 evidence/quality_claim/subject/model；默认不变 | `dfe81893`、`test_eval_labels` |

---

## 8. 限定

1. **样本量不足**：C-1 门禁 15 次，D6/D7 为归档 replay（14/21/31 例），全部 <200，一律 `LOW_CONFIDENCE`。
2. **只对 staging + Gemini 3.5 免费档成立**：不得外推到生产、其他 provider、付费档或其他模型。
3. **429 单列**：本轮 tier 8 429=0；若后续档位出现 429，必须记录并排除出模型失败，不得混算。
4. **1:1 证明的限制**：只覆盖非流式 `post()`；SSE/流式端点不适用该日志计数。
5. **D7 三组对照不是质量结论**：A/B 是本地镜像语义，C 未实现；门禁 `NOT_EVALUATED`，不得写成 PASS/FAIL。
6. **回归证据**：旧 `ai-eval planning/decide/memory` 默认输出 sha 未变（`00e040be…` / `9e579350…` / `74177ebe…`）；memory v2 `--matcher exact` 与 v1 逐字节一致。

---

## 9. 证据索引

| 证据 | 路径 |
| --- | --- |
| C-1 门禁 aggregate | `raw/phase1/gate-8-3000-t8b.json` |
| C-1 门禁日志（15 request / 13×200） | `raw/phase1/log-gate-8-t8b.txt` |
| 1:1 修复后日志 | `raw/phase1/log-fixed-01.txt`、`raw/phase1/model-call-fixed-01.json` |
| control 三次（400+200） | `raw/phase1/log-control-0{1,2,3}.txt` |
| 启动绑定失败（两次） | `raw/phase1/startup-bind-failure.excerpt.txt`、`startup-bind-failure-2.excerpt.txt`、`blocked-prefix-mismatch.md` |
| 前缀缺陷说明 | `raw/phase1/blocked-prefix-mismatch.md` |
| provider 脱敏详情 / purposes | `raw/phase1/provider-*.json`、`purposes-*.json` |
| C-2 memory exact / semantic | `raw/phase2/memory-exact.json`、`memory-semantic.json` |
| C-2 planning mutation | `raw/phase2/planning-mutations.json` |
| C-2 decide live raw | `raw/phase2/aggregate-decide.json`、`decide-metrics.json`、`decide-cases.jsonl`、`decide-run.log`、`decide-app-log.txt` |
| D5 / D3 冒烟 | `raw/phase2/d5-planning-default-smoke.json`、`d5-planning-60000.json`、`d3-registration-policy.json` |
| 30s 诊断 | `raw/phase1/diag-8-30000.json` |
