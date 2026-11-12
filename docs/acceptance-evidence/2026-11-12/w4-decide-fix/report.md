# W4 decide 修复：决策优先级与 Prompt v2026-11-12.2（2026-11-12）

> 泳道：C（W4 decide）
> 基线：`dev @ 79e4839b`（冻结提交），工作分支 `w4/decide-fix`
> 环境：staging `box-aae54f`（部署 `w4/decide-fix @ 9383894f`）；本地 C worktree
> 当前证据等级：`LIVE_VERIFIED (staging, real model)` + 本地 `TESTED (stub)`
> 结论：**live FAIL**；golden agreement **0.80（16/20）**、JSON **0.90（18/20）**、
> adversarial `invalidToolCalls=0`；不能宣称 W4 可 canary 或生产可灰度

---

## 0. 一句话结论

已定位 golden 4 条 `ASK_USER/NO_OP` 被系统性改成 `CALL_TOOL` 的 Prompt 结构性原因，并发布
`decide.system@2026-11-12.2`。live 复跑证明这 4 条原始误判已全部修复（d-007/d-008/d-015
为 ASK_USER，d-016 为 NO_OP），但出现 2 条新的真实模型误判：d-012 期望 CALL_TOOL 实际 FINAL，
d-020 期望 FINAL 实际 ASK_USER；另有 d-011 provider 503、d-018 timeout 两条基础设施失败。
因此 live 门禁仍 **FAIL**；契约、parser、幂等、deadline、shadow 只读语义和 `DECIDE_MODE=java`
均未改变，也不把 replay 的 `1.0` 或诊断性剔除值写成 PASS。

---

## 1. 根因（基于冻结输入与 S3 归档真实输出）

旧版 `decide.system@2026-11-12.1` 只展示 `CALL_TOOL` 和 `FINAL` 两个 JSON 形状，且把
`CALL_TOOL` 描述为“需要工具补充事实时使用”，没有规定：

1. 先判断 `NO_OP` 与 `ASK_USER`，再判断是否调用工具；
2. 工具可用不等于应该调用；
3. 缺少范围/对象/必填参数（例如会议纪要、周报范围未指定，或 `get_document_metadata`
   缺少 `documentId`）时应先 `ASK_USER`，不能用空对象或猜参数调用；
4. 用户要求引用/依据、而上下文没有可引用来源时，应优先 `CALL_TOOL`，不能直接把工具摘要
   当作最终证据。

S3 归档真实输出（`decide_real_gemini31.v1.jsonl`）与上述模式一致：

| case | expected | 归档实际 | 现象 |
| --- | --- | --- | --- |
| d-007 | `ASK_USER` | `CALL_TOOL/search_project_knowledge` | 范围未指定仍直接检索 |
| d-008 | `ASK_USER` | `CALL_TOOL/get_project_snapshot` | "Which project?" 未追问 |
| d-015 | `ASK_USER` | `CALL_TOOL/get_project_snapshot` | 周报范围未确认仍取快照 |
| d-016 | `NO_OP` | `CALL_TOOL/get_project_snapshot` | 无新输入仍制造工具动作 |
| d-011 | `CALL_TOOL/search_project_knowledge` | `FINAL` | 要求 citations 却直接回答 |

归档回放仍是旧模型输出，不能证明新 Prompt 已修复；它只用于证明评测打分路径未改变、上述
失败不是 parser/指标计算造成的。真正的修复效果必须由 staging live 复跑判定。

---

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `ai-collab-ai-worker/prompts/decide.system/2026-11-12.2.md` | 新 Prompt 版本：四形状、判定顺序、工具参数边界、引用证据规则 |
| `ai-collab-ai-worker/prompts/manifest.json` | `ai-prompts write` 生成的 fingerprint |
| `ai-collab-backend/src/main/resources/promptpack/**` | `ai-prompts export-java` 同步的只读 fallback |
| `ai-collab-ai-worker/tests/unit/test_decide_prompt.py` | 断言优先级、四形状、不得硬编码冻结 case 文本 |
| `ai-collab-ai-worker/tests/unit/test_decide_prompt.py` | 断言历史 `.1` 可按版本读取、当前仍取 `.2` |
| `ai-collab-ai-worker/tests/contract/test_prompt_pack_frozen.py` | 更新新版本指纹 |
| `ai-collab-ai-worker/tests/contract/test_java_promptpack_sync.py` | 断言历史 `.1` 的 Java fallback 与 Python 模板逐字节一致 |
| `ai-collab-ai-worker/tests/integration/test_decide_api.py` | Prompt 版本 `.2` |
| `ai-collab-ai-worker/tests/integration/test_decide_eval_cli.py` | Prompt 版本 `.2` |

PromptPack 历史版本纪律：`.1` 模板与 Java fallback 均保留（可由版本号读取），manifest 同时登记
`.1`/`.2`；`load_current` 仍选择最高版本 `.2`。历史版本不是当前生产 Prompt。

Prompt fingerprint：

```text
historical: key=decide.system version=2026-11-12.1 chars=1076
            sha256=1b60e3f92b00570b0b30569149636db309abb81664eaf6cf2609a98ac72872ad
key=decide.system
version=2026-11-12.2
chars=1699
sha256=021aac9fe1e0df15daafe2c6d06cbee3a2d7b7648a6ae1a0856989b4f4e21ecb
```

Java fallback 与 Python 模板逐字节相同（`ai-prompts export-java --check` PASS）。

---

## 3. 本地验证

| 检查 | 结果 | 原始证据 |
| --- | --- | --- |
| `uv run --frozen ruff check .` | PASS，164 files | `raw/ruff-check.txt` |
| `uv run --frozen ruff format --check .` | PASS，164 files | `raw/ruff-format.txt` |
| `uv run --frozen mypy` | PASS，63 source files | `raw/mypy.txt` |
| `uv run --frozen pytest -q` | **1177 passed, 1 warning** | `raw/pytest-full.txt` |
| 定向 Python 测试（prompt/API/eval/contracts） | **92 passed, 1 warning** | `raw/pytest-targeted.txt` |
| Java PromptPack 定向（3 个测试类） | **22 passed, 0 failed** | `raw/java-promptpack-tests.txt` |
| `ai-prompts check` | PASS，23 entries（含历史 `.1`） | 命令输出 |
| `ai-prompts export-java --check` | PASS，24 files（含历史 `.1`） | 命令输出 |
| replay golden | **20/20，agreement 1.0，JSON 1.0** | `raw/decide-replay.json` |
| 归档真实输出 replay | golden 19 例 agreement **0.7368**，JSON **1.0** | `raw/decide-real-archive.json` |

**Adversarial 口径说明**：`decide-replay.json` 的 top-level `invalidToolCalls=2` 来自 d-021 /
d-022 两条**故意非法工具名**的 adversarial 输入；top-level JSON 0.625 也是 12 条刻意非法
adversarial 与 20 条 golden 混合后的 20/32，不是 golden 回归（golden 仍为 20/20）。
live 门禁的 `invalidToolCalls` 必须由原 scorer 对 live 输出重新计算，并对照冻结基线/门禁值 0；
**不得把 replay 的 2 当成 live 的 0，也不得把 deliberate adversarial 洗成 golden 失败**。
归档真实输出只有 19 条 golden（d-018 为 provider 503 无输出），其 0.7368 是旧 Prompt 的真实
模型结果，不是新 Prompt 的结果。

---

## 4. Live staging 结果

### 4.1 部署与环境

| 项 | 值 |
| --- | --- |
| 代码 | `w4/decide-fix @ 9383894f`（Prompt `.2` + 历史 `.1`） |
| backend 镜像 | `ai-collab-backend:local`，id `sha256:e59441f48ca6839c40c308101d50cc78881098971456267aa155fcb98e26b2d2` |
| worker 镜像 | `ai-collab-ai-worker:dev`，id `sha256:23c6101c7149c08bf938e1868cce1a1bfa554be15af4a0823e76960dbb831260` |
| 容器 | `ai-collab-app` / `ai-collab-ai-worker-api` 均 `running/healthy` |
| 部署内 Prompt | worker `.2` 与 backend fallback `.2` 文件 sha256 均为 `6c70a54f...`；`.1` 仍为 `77b77c12...` |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` |
| AGENT provider | Gemini 3.5 `0f58581a-dbba-4e26-bafa-2e137f650563`（评测前后 `providers` 与 `purposes` 文件 sha256 完全一致） |
| 评测 | 原 `decide_baseline.v1.jsonl`、原 scorer、`deadline=30000`、32 例、相邻请求间隔 10s、一次运行、无成功样本重试 |

### 4.2 门禁

| 指标 | 门槛 | 实测 | 判定 |
| --- | --- | --- | --- |
| golden `decisionAgreement` | ≥0.98（20 条原分母） | **0.80（16/20）** | **FAIL** |
| golden `jsonValidityRate` | ≥0.995（20 条原分母） | **0.90（18/20）** | **FAIL** |
| adversarial `invalidToolCalls` | 不增（基线 0） | **0（12 条 adversarial）** | **PASS（子项）** |
| golden `schemaViolations` | 记录 | 0 | 记录 |
| 429 | 单列 | **0** | — |
| timeout | 单列 | **1（d-018，HTTP 504）** | — |
| provider 5xx | 单列 | **1（d-011，MODEL_UNAVAILABLE，HTTP 503）** | — |
| attempts / confidence | — | 32 / `LOW_CONFIDENCE` | 20 < 200 |

诊断性剔除（**不替代门禁**）：把 d-011/d-018 两条基础设施失败从分母移除后，golden agreement
为 **16/18=0.8889**、JSON 为 **18/18=1.0**；仍不足 0.98，且报告不以此改写 FAIL。

### 4.3 逐 case 结论

| case | expected | live actual | 分类 |
| --- | --- | --- | --- |
| d-007 / d-008 / d-015 | ASK_USER | ASK_USER | 修复成功 |
| d-016 | NO_OP | NO_OP | 修复成功 |
| d-012 | CALL_TOOL/search_project_knowledge | FINAL | 真实模型误判，valid |
| d-020 | FINAL | ASK_USER | 真实模型误判，valid |
| d-011 | CALL_TOOL/search_project_knowledge | — | provider 503（MODEL_UNAVAILABLE） |
| d-018 | CALL_TOOL/search_project_knowledge | — | timeout 504（DEADLINE_EXCEEDED） |
| d-021..d-032 | 12 条 adversarial | 契约/拒绝行为 | `invalidToolCalls=0`，`rejectedRate=0.0`（仅记录） |

原始 32 行逐 case：`raw/decide-cases-live.jsonl`；aggregate：`raw/aggregate-decide-live.json`
（`aggregate.sha256=6485f3eb883097bebca52c9d36ef63a77c101d3ea2ec2c0b8673ecf75911b7f7`）。

### 4.4 冻结集标签待人工复核

| case | 标签现状 | 可能不自洽之处 |
| --- | --- | --- |
| d-012 | goal=`在最多条目的上下文下仍给出检索建议`；expected=`CALL_TOOL/search_project_knowledge`；live=`FINAL` | goal 是元评测式指令“给出检索建议”，没有直接说“检索并给依据”；expected 要求实际调用检索工具，但也可被解读为只需给出检索建议。标签语义需要人工确认，不能据此硬编码 Prompt 分支。 |
| d-020 | goal=`空上下文下回答一个已知事实`；expected=`FINAL`；live=`ASK_USER` | goal 说“已知事实”，但快照的 `facts/messages/observations` 全为空，输入中没有可支撑该事实的内容；expected FINAL 依赖未提供的先验知识，live ASK_USER 反而符合当前通用规则。标签语义需要人工确认。 |

这两条都是**有效 JSON、有效决策类型**的真实模型不一致，已按原标签计入 golden 门禁（不是 429/timeout/5xx）；
在人工复核并重新冻结标签前，不得修改 expected、不得针对这两条做 Prompt 硬编码，也不改变当前
W4 门禁 **FAIL** 的判定。

## 5. 限定与后续

1. 结论只适用于本次 staging、Gemini 3.5 Flash Lite、当前免费额度和该 32 例冻结集；不得外推生产
   或其他 provider/模型。
2. 样本 20 条 golden <200，`LOW_CONFIDENCE`；d-011/d-018 是基础设施失败，不能计为模型能力证据，
   也不能从主门禁分母删除。
3. 这次只跑一轮，未重试成功样本；因此 d-012/d-020 是当前 Prompt 的实际误判，不能靠重复采样洗掉。
4. W4 仍不满足 canary 条件。下一轮若要继续，先针对 d-012（长上下文应检索）和 d-020（空上下文
   已知事实应 FINAL）做通用策略分析，再重新冻结集复跑；本轮不继续调参，也不宣称 canary。
5. 服务器已于 `2026-11-12T11:38:48Z` 释放给 D；C 无残留评测容器，临时 admin token/login 已删除，
   app/worker 仍为 `running/healthy`，AGENT purpose 仍为 Gemini 3.5 `0f58581a...`。

---

## 6. 合规

- 只重建/重启 `ai-collab-deploy` 的 `app` 与 `ai-worker-api`；未操作其他 compose 项目，未改默认开关。
- 未改冻结集、expected、scorer、门禁或前端/迁移；未使用 `DECIDE_MODE=shadow/python`。
- 32 次请求均保留原始逐 case 行；429/timeout/provider 5xx 单列；无成功样本重试。
- AGENT purpose 仍为 Gemini 3.5 `0f58581a...`；评测前后 provider/purpose 文件 sha256 一致。
- 评测容器 `w4-decide-live` 显示 `unhealthy` 是预期：它运行一次性 Python harness，不是 uvicorn 服务；
  app/worker 的 Docker health 均 healthy。

---

## 7. 证据索引

| 证据 | 文件 |
| --- | --- |
| Live aggregate（原始 scorer canonical） | `raw/aggregate-decide-live.json` |
| 32 行逐 case（含 expected/actual/error/HTTP/timing） | `raw/decide-cases-live.jsonl` |
| 运行元数据（间隔、失败分类、模型） | `raw/run-meta-live.json` |
| 运行原始输出 / 退出码 / 开始时间 | `raw/live-output.txt`、`raw/live.exit`、`raw/live-start.txt` |
| 评测 harness | `raw/harness/run_decide_live.py` |
| app / worker 关键日志 | `raw/app-decide-live.txt`、`raw/worker-decide-live.txt` |
| 环境六项与健康 | `raw/defaults-after-eval.txt` |
| provider/purpose before | `raw/providers-before-eval.json`、`raw/purposes-before-eval.json` |
| provider/purpose after | `raw/providers-after-eval.json`、`raw/purposes-after-eval.json` |
| 自动复核摘要 | `raw/live-verification.json` |
| staging 释放状态 | `raw/staging-release.txt` |
| 本地 Prompt/测试/replay | `raw/prompt-fingerprint.json`、`raw/pytest-*.txt`、`raw/decide-*.json` |
