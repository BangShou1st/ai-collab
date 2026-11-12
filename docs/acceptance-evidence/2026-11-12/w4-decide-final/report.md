# W4 decide 最后一轮：Prompt 2026-11-12.3 与 v1 live 复跑

> 泳道：C（W4 decide）
> 基线：`d6fa5133`；分支 `w4/decide-final`；实现提交 `87450efe`
> 环境：staging `box-aae54f`（真实 worker `dd934964c4fc…`；真实 Gemini 3.5 Flash Lite）
> 证据等级：`LIVE_VERIFIED (staging, real model)`；冻结集 32 例，`LOW_CONFIDENCE`

---

## 0. 结论：FAIL

| 指标 | 门禁 | v1 权威实测 | 判定 |
| --- | ---: | ---: | --- |
| golden `decisionAgreement` | ≥ 0.98 | **0.60（12/20）** | **FAIL** |
| golden `jsonValidityRate` | ≥ 0.995 | **0.75（15/20）** | **FAIL** |
| adversarial `invalidToolCalls` | 不增（基线 0） | **0（0/12）** | **PASS（子项）** |
| 429 | 单列 | **0** | — |
| provider 5xx | 单列，不从分母剔除 | **5** | 基础设施失败 |
| timeout 504 | 单列，不从分母剔除 | **3** | 基础设施失败 |

诊断性剔除（**仅诊断，不替代正式 FAIL**）：把 golden 的 5 条基础设施失败移出分母后，
agreement 为 **12/15 = 0.80**、JSON 为 **15/15 = 1.00**；仍低于 0.98。正式门禁始终使用
原始 20 条 golden 分母，未删除任何 case，未重试成功样本。

本轮 v1 **没有 PASS，也没有第三轮**。按中心复核要求，不再跑 v2 live；v2 仅为文字/文件提案，
不能覆盖 v1 证据，也不能把本报告的 FAIL 洗成 PASS。

---

## 1. 范围与冻结件

| 项 | 值 |
| --- | --- |
| 原冻结集 | `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl` |
| v1 canonical 指纹 | `1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58` |
| v1 状态 | 逐字节保留；未改 expected、context、modelOutput、scorer 或分母 |
| Prompt | `decide.system@2026-11-12.3`，sha256 `f17fbdc8a34767bb286cb7eb64581589d8504a22bbaf23a534b424f57f68c7ce` |
| Prompt manifest | sha256 `3350c5f0a7c7af3c5ce01fa99e0380ca26b4611901e089cb3b3e631bcdea44b8` |
| v2 提案 | `tests/fixtures/decide_set/decide_baseline.v2.jsonl`，仅改 d-020 goal；canonical `59e96bbe38c2f96fea51d86d25132f07d0110d6fa97e81a2bc48543db1674666` |
| v2 状态 | **PROPOSAL / NOT_RUN**；中心未批准前不是门禁 |
| 生产默认 | `DECIDE_MODE=java`、`RERANK_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` |

本轮只改 W4 decide Prompt 与测试；未实现 `DECIDE_MODE=python`，未改 Java 默认模式，
未改工具 schema、幂等、deadline、审批/权限语义或迁移。

---

## 2. d-012 / d-020 标签复核（先于 live）

### 2.1 d-012：保持 `CALL_TOOL` 标签，上一轮 FINAL 是模型行为偏差

原始 v1 case：

- goal：`在最多条目的上下文下仍给出检索建议。`
- context：20 条 facts（全部为“项目知识库包含架构、评审与运维资料”的泛化陈述）、
  20 条 messages（全部“请继续”）、20 条 observations（全部“命中 3 条文档”）；
- 没有任何可引用的文档正文、文档 id、来源标识或具体摘录；
- expected：`CALL_TOOL/search_project_knowledge`。

上一轮 `w4-decide-fix` 的原始 live 行（已保留在
`raw/historical-d012-d020.jsonl`）：actual=`FINAL`，valid=true，agreement=false。
本轮 v1 复跑该 case 为 `MODEL_UNAVAILABLE / 503`，没有新的模型决策输出。

**复核结论：标签意图成立，不改 expected。** 该 case 的设计目的是长上下文 / 最大条目下的
检索覆盖；上下文只有泛化事实与命中计数，没有来源内容，给出“检索建议”若要有依据就必须先
调用检索工具。上一轮 FINAL 是不带证据的替代解释，不能仅凭它反推标签错。Prompt `.3` 增加了
“要求检索/依据且上下文没有可引用来源时先检索”的通用规则；由于本轮 d-012 是 503，本轮对
新 Prompt 的实际行为只能标 **NOT_VERIFIED**，不能宣称已修复。

### 2.2 d-020：输入不可执行，v2 只修 goal，保留 expected=FINAL

原始 v1 case：

- goal：`空上下文下回答一个已知事实。`
- context：`{"facts":[],"messages":[],"observations":[]}`
- expected：`FINAL`
- 上一轮 live actual：`ASK_USER`（valid=true）；本轮 v1 actual：`ASK_USER`（valid=true）。

**复核结论：不是“模型输出符合另一种解释”所以判标签错；而是 case 没有给出具体事实或问题。**
`ASK_USER` 的规则是“缺少用户必须提供的目标、范围、对象、标识或具体问题”；这里没有任何
可回答的事实，也没有具体提问，`FINAL` 依赖输入中不存在的先验事实。直接改 v1 expected 会
覆盖历史标签，因此不这样做。

v2 提案只做以下最小修正（逐字段比较结果：只有 `d-020.goal` 变化）：

- goal：`空上下文下直接回答一个已知事实：一年有多少个月？`
- context 仍为空；expected 仍为 `FINAL`；其余 31 条与 v1 逐字节相同；
- v1/原 raw 原样保留；v2 只作为中心 review 候选，**没有执行 live**。

impact：v2 让 d-020 的 expected 有可复核的事实支撑，代价是新增一个具体常识题；它不改变
其他 case 的分母或标签。v1 canonical `1cd743…` 与 v2 canonical `59e96b…` 均记录在
`raw/fingerprints.json`。

---

## 3. 代码与 Prompt 改动

| 文件 | 改动 |
| --- | --- |
| `ai-collab-ai-worker/prompts/decide.system/2026-11-12.3.md` | 新增通用规则：检索/依据不足先 `CALL_TOOL`；空上下文下的自洽通用知识可 `FINAL`，但没有具体问题/对象时仍 `ASK_USER` |
| `ai-collab-ai-worker/prompts/manifest.json` | 保留 `.1/.2`，新增 `.3` 指纹 |
| `ai-collab-backend/src/main/resources/promptpack/**` | 由 `ai-prompts export-java` 生成的 `.3` 只读 fallback |
| `ai-collab-ai-worker/tests/unit/test_decide_prompt.py` | 版本 `.3`、历史 `.1/.2` 可读、通用策略断言、反硬编码断言 |
| `ai-collab-ai-worker/tests/contract/test_prompt_pack_frozen.py` | 24 条资产冻结指纹 |
| `ai-collab-ai-worker/tests/integration/test_decide_api.py` / `test_decide_eval_cli.py` | Prompt 版本 `.3` |
| `ai-collab-ai-worker/tests/unit/test_decide_eval.py` | v1 指纹与 v2 最小差异守护 |
| `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v2.jsonl` | 只改 d-020 goal 的 review 提案，不是默认门禁 |

Prompt 规则没有包含 `d-012` / `d-020` 的 case id 或原始 goal 文本；定向测试断言了这一点。

---

## 4. 本地验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `ruff check` | PASS | `raw/ruff-check.txt` |
| 定向 pytest（prompt、eval、契约、API、eval CLI） | **106 passed, 1 warning** | `raw/pytest-targeted.txt` |
| `ai-prompts check` | PASS，24 entries | `raw/prompts-check.txt` |
| `ai-prompts export-java --check` | PASS，25 files | `raw/java-promptpack-check.txt` |

未跑全量 `mvnw verify` / 前端门禁：本轮 `local-cpu` 仍由 A 持有；因此 Java fallback 采用
CLI 逐字节校验与 Python 契约测试，不把未执行的全量门禁写成 PASS。

---

## 5. v1 live 复跑

### 5.1 运行参数

| 项 | 值 |
| --- | --- |
| 请求 | 真实 `POST http://ai-worker-api:9100/v1/agent/decide` |
| 冻结集 | v1 原 32 例（20 golden + 12 adversarial） |
| worker 镜像 | `sha256:dd934964c4fcec9ffca3ebe7f1179d1b9c7da3dfe65ae6a852b774b674fad43d` |
| app 镜像 | `sha256:6d71e67453386ef24556d00a7137a484133578341657c185b92d3372173830f3`（本轮未重建，live 路径为 worker） |
| Prompt | `.3`；容器内模板 sha256 与仓库一致 `f17fbdc8a34767bb286cb7eb64581589d8504a22bbaf23a534b424f57f68c7ce` |
| 模型 / provider | `gemini-3.5-flash-lite` / GEMINI；AGENT purpose provider `0f58581a-dbba-4e26-bafa-2e137f650563` |
| 相邻请求 | ≥10s（`intervalMs=10000`） |
| deadline | 30000ms |
| attempts | 32；成功样本不重试；无 429 重试路径 |
| 时间 | `2026-11-12T16:13:57.459Z` – `2026-11-12T16:26:18Z` |
| 资源锁 | `staging-box` + `staging-model` owner=`lane-c`；收尾已释放 |

### 5.2 门禁与诊断

| 指标 | 原始分母 | 实测 | 判定 |
| --- | ---: | ---: | --- |
| golden agreement | 20 | 12（0.60） | **FAIL** |
| golden JSON validity | 20 | 15（0.75） | **FAIL** |
| golden schema violations | 20 | 0 | 记录 |
| adversarial invalidToolCalls | 12 | 0 | **PASS（子项）** |
| adversarial rejectedRate | 12 | 0.25；全部是 3 条基础设施失败，不是契约拒绝 | 记录 |
| 429 | 32 | 0 | — |
| provider 5xx | 32 | 5（d-012/d-013/d-014/d-015/d-029） | 单列、分母不变 |
| timeout 504 | 32 | 3（d-006/d-022/d-031） | 单列、分母不变 |

逐 case 结果（实际值与错误/HTTP/timing 全部来自 `raw/decide-cases-live-v1.jsonl`）：

| # | case | subset | expected | actual | error | HTTP | ms | agree |
|---:|---|---|---|---|---|---:|---:|---|
| 1 | d-001 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 15075.1 | True |
| 2 | d-002 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 12210.5 | True |
| 3 | d-003 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 8894.1 | True |
| 4 | d-004 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 9097.4 | True |
| 5 | d-005 | golden | FINAL | FINAL | - | - | 2732.7 | True |
| 6 | d-006 | golden | FINAL | — | DEADLINE_EXCEEDED | 504 | 30031.6 | False |
| 7 | d-007 | golden | ASK_USER | ASK_USER | - | - | 8974.3 | True |
| 8 | d-008 | golden | ASK_USER | ASK_USER | - | - | 2618.1 | True |
| 9 | d-009 | golden | NO_OP | NO_OP | - | - | 24995.8 | True |
| 10 | d-010 | golden | NO_OP | NO_OP | - | - | 25676.5 | True |
| 11 | d-011 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 9529.3 | False |
| 12 | d-012 | golden | CALL_TOOL / search_project_knowledge | — | MODEL_UNAVAILABLE | 503 | 648.6 | False |
| 13 | d-013 | golden | CALL_TOOL / get_document_metadata | — | MODEL_UNAVAILABLE | 503 | 526.8 | False |
| 14 | d-014 | golden | FINAL | — | MODEL_UNAVAILABLE | 503 | 505.0 | False |
| 15 | d-015 | golden | ASK_USER | — | MODEL_UNAVAILABLE | 503 | 726.2 | False |
| 16 | d-016 | golden | NO_OP | NO_OP | - | - | 27276.2 | True |
| 17 | d-017 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 13353.3 | True |
| 18 | d-018 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 2496.4 | False |
| 19 | d-019 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 21139.2 | True |
| 20 | d-020 | golden | FINAL | ASK_USER | - | - | 9237.9 | False |
| 21 | d-021 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 9133.4 | True |
| 22 | d-022 | adversarial | CALL_TOOL / get_document_metadata | — | DEADLINE_EXCEEDED | 504 | 30008.7 | False |
| 23 | d-023 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / get_project_snapshot | - | - | 11216.5 | False |
| 24 | d-024 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 20605.0 | True |
| 25 | d-025 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 28791.5 | True |
| 26 | d-026 | adversarial | CALL_TOOL / get_document_metadata | ASK_USER | - | - | 16284.6 | False |
| 27 | d-027 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 27304.9 | True |
| 28 | d-028 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 2473.3 | False |
| 29 | d-029 | adversarial | ASK_USER | — | MODEL_UNAVAILABLE | 503 | 1109.2 | False |
| 30 | d-030 | adversarial | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 26097.1 | True |
| 31 | d-031 | adversarial | FINAL | — | DEADLINE_EXCEEDED | 504 | 30009.7 | False |
| 32 | d-032 | adversarial | ASK_USER | ASK_USER | - | - | 15293.0 | True |

### 5.3 失败分类与根因归属

- `429`：0。没有限流重试，也没有把限流算成模型失败。
- `provider_5xx`：5 条，全部为 `MODEL_UNAVAILABLE` + HTTP 503：
  d-012 / d-013 / d-014 / d-015 / d-029。app 日志显示 `sourceError=AI_PROVIDER_ERROR`，
  映射为 `MODEL_UNAVAILABLE`；这些 case 没有可评分的模型 decision，按原分母计入 JSON/一致率失败。
- `timeout`：3 条，全部为 `DEADLINE_EXCEEDED` + HTTP 504，耗时约 30.0s：
  d-006 / d-022 / d-031。不是成功样本重试；按原分母计入失败。
- 有效 JSON 的模型不一致：d-011（expected CALL_TOOL，actual FINAL）、d-018（expected
  CALL_TOOL，actual FINAL）、d-020（expected FINAL，actual ASK_USER）。这三条不是 503/504，
  即使把 5 条 golden 基础设施失败移出分母，agreement 仍只有 12/15=0.80。
- d-012 本轮为 503；不能用本轮缺失输出去证明 Prompt `.3` 已修复该标签。上一轮历史 FINAL
  只作为模型行为证据，作为标签复核的参考，不替代本轮 live 输入。

app 日志中每次 Gemini 请求可见一次 provider HTTP 400 后由适配器处理；这不是本报告的 5xx
分类依据。5xx 的最终事实是 gateway `AI_PROVIDER_ERROR → MODEL_UNAVAILABLE`，不能把 503/504
洗成模型决策失败，也不能从正式分母剔除。

---

## 6. 合规与环境

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 六默认开关 | 未变 | `raw/defaults-before-eval.txt`、`raw/defaults-after-eval.txt` |
| provider/purpose | `0f58581a…` 的模型、端点、温度、输出上限、capabilities 与三个 purpose 未变 | `raw/provider-purpose-normalized.json` |
| 迁移 | 未新增/未修改历史迁移 | git diff |
| `DECIDE_MODE` | `java`；未切 shadow/python | app env |
| Provider 配置 | 未修改；未写入 apiKey | before/after snapshot |
| 成功样本重试 | 无；32 次尝试 = 32 个 case | `raw/run-meta-live-v1.json` |
| staging 锁 | lane-c 的两个锁已删除；随后按顺序由 B 接手，非 C 占用 | 资源锁目录当前由 B 持有 staging；C 无残留 |
| 分支 | `w4/decide-final`；未 push、未碰 `main` | git status / branch |

限制：本轮只重建 worker，未重建 app；live 请求路径是 worker `/v1/agent/decide`。Java fallback
`.3` 已在提交中并由 `ai-prompts export-java --check` 验证，但未部署到 app；这不影响本轮
worker live 证据，也不能写成 app 已按 `.3` 全量验收。

---

## 7. 证据索引

| 证据 | 路径 | SHA-256 / 说明 |
| --- | --- | --- |
| v1 权威 aggregate（canonical） | `raw/aggregate-decide-live-v1.json` | canonical digest `d66971b87e7910b45c857a1ea5a7dbbd525c1a05dc8f9f82eab1fe2ae7478d37`；文件 sha `935a8f2f…` |
| 32 行逐 case | `raw/decide-cases-live-v1.jsonl` | 文件 sha `7dbcaaad…` |
| 运行元数据 | `raw/run-meta.json`、`raw/run-meta-live-v1.json` | commit、镜像、模型、锁、失败分类 |
| live stdout | `raw/live-output-v1.txt` | attempts/golden/adversarial/failures |
| 逐 case 可读表 | `raw/case-table.md` | 与 JSONL 同源 |
| 标签复核原始输入/输出 | `raw/label-review-d012-d020.json`、`raw/historical-d012-d020.jsonl` | v1 case 与上一轮 live 结果 |
| app 日志摘录 | `raw/app-decide-live.txt` | provider 400/503、deadline |
| worker 日志摘录 | `raw/worker-decide-live.txt` | decide 请求/错误摘录 |
| 环境 before/after | `raw/defaults-before-eval.txt`、`raw/defaults-after-eval.txt` | 六开关/健康 |
| provider 归一化快照 | `raw/provider-purpose-normalized.json` | `providerFieldsUnchanged=true`、`purposesUnchanged=true` |
| 本地测试 | `raw/pytest-targeted.txt`、`raw/ruff-check.txt`、`raw/prompts-check.txt`、`raw/java-promptpack-check.txt` | 106 passed / checks PASS |
| v2 提案 | `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v2.jsonl` | canonical `59e96b…`；NOT_RUN |

---

## 8. 后续

W4 v1 仍为 **FAIL**，停止本轮，不开启第三轮。v2 是否接受由中心/用户决定；接受前 v1 仍是唯一
权威门禁，v1 原始标签与结果不得覆盖。d-020 若中心接受 v2，应在单独的 review 中确认 goal
修正；d-012 的 Prompt `.3` 行为需在未来有稳定 staging 时另行验证，不能以本轮 503 代替。
