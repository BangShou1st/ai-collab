# W4 decide 修复：决策优先级与 Prompt v2026-11-12.2（2026-11-12）

> 泳道：C（W4 decide）
> 基线：`dev @ 79e4839b`（冻结提交），工作分支 `w4/decide-fix`
> 环境：本地 C worktree；**尚未连接 staging**
> 当前证据等级：`TESTED (stub)`（本地契约/策略/测试）；staging live 为 `NOT_VERIFIED`
> 结论：**本地实现与回放证据已齐；live agreement / JSON 门禁尚未验证，不能宣称 PASS**

---

## 0. 一句话结论

已定位 golden 4 条 `ASK_USER/NO_OP` 被系统性改成 `CALL_TOOL` 的 Prompt 结构性原因，并发布
`decide.system@2026-11-12.2`：补齐四决策输出形状、`NO_OP/ASK_USER` 优先级、缺少必填参数时
先追问、以及“工具可用不等于应调用”的规则。契约、parser、幂等、deadline、shadow 只读语义和
`DECIDE_MODE=java` 均未改变。live 复跑要等 B 释放 staging 后才能执行；本报告不把 replay 的
`1.0` 写成真实模型达标。

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
| `ai-collab-ai-worker/tests/contract/test_prompt_pack_frozen.py` | 更新新版本指纹 |
| `ai-collab-ai-worker/tests/integration/test_decide_api.py` | Prompt 版本 `.2` |
| `ai-collab-ai-worker/tests/integration/test_decide_eval_cli.py` | Prompt 版本 `.2` |

Prompt fingerprint：

```text
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
| `uv run --frozen ruff check .` | PASS，163 files | `raw/ruff-check.txt` |
| `uv run --frozen ruff format --check .` | PASS，163 files | `raw/ruff-format.txt` |
| `uv run --frozen mypy` | PASS，63 source files | `raw/mypy.txt` |
| `uv run --frozen pytest -q` | **1174 passed, 1 warning** | 本轮终端输出；分项原始日志见下方 |
| 定向 Python 测试（prompt/API/eval/contracts） | **89 passed, 1 warning** | `raw/pytest-targeted.txt` |
| Java PromptPack 定向（3 个测试类） | **22 passed, 0 failed** | `raw/java-promptpack-tests.txt` |
| `ai-prompts check` | PASS，22 entries | 命令输出 |
| `ai-prompts export-java --check` | PASS，23 files | 命令输出 |
| replay golden | **20/20，agreement 1.0，JSON 1.0** | `raw/decide-replay.json` |
| 归档真实输出 replay | golden 19 例 agreement **0.7368**，JSON **1.0** | `raw/decide-real-archive.json` |

说明：`decide-replay.json` 的 top-level agreement 为 0.625、JSON 为 0.625，是因为冻结 replay
集包含 12 条**刻意非法** adversarial 输出；golden 子集为 20/20。归档真实输出只有 19 条 golden
（d-018 为 provider 503 无输出），其 0.7368 是旧 Prompt 的真实模型结果，不是新 Prompt 的结果。

---

## 4. 门禁状态

| 指标 | 门槛 | 当前状态 | 判定 |
| --- | --- | --- | --- |
| golden `decisionAgreement` | ≥0.98（20 条原分母） | staging live **NOT_VERIFIED** | **未判定** |
| golden `jsonValidityRate` | ≥0.995（20 条原分母） | staging live **NOT_VERIFIED** | **未判定** |
| adversarial `invalidToolCalls` | 不增（基线 0） | staging live **NOT_VERIFIED** | **未判定** |
| Prompt/契约/本地回放 | 不破坏 | 89 targeted + 1174 full + 22 Java PASS | TESTED (stub) |

本报告**不**把 replay 的 1.0 当作 live PASS，也不剔除 provider 5xx/timeout/429 来重算门禁。
staging 复跑必须使用原冻结集、原 20 条 golden 分母、原 scorer，并单列 429/timeout/provider error。

---

## 5. 待执行（等 B 释放 staging）

1. 将 `w4/decide-fix` 的提交部署到 staging worker（仅测试态，不改默认开关）。
2. 用原 `decide_baseline.v1.jsonl` 跑 `ai-eval decide --scorer worker`，逐 case 间隔 ≥5s，
   `deadline=30000`；记录 429、timeout、provider 5xx。
3. 至少跑一次 live 32 例；若出现基础设施失败，保留原始分母，另列诊断值，但不能替代门禁值。
4. 补 `raw/live-decide-*.jsonl`、aggregate、app/worker 日志筛选、环境开关复核。
5. 只有 live golden agreement ≥0.98 且 JSON ≥0.995、invalidToolCalls=0 时，才可把 W4 标为
   `LIVE_VERIFIED (staging, real model)`；否则如实 FAIL/NOT_VERIFIED。

---

## 6. 合规

- 未改 `DECIDE_MODE` 等六个默认开关；未实现 `python` 生产模式；未改前端、迁移、审批/权限语义。
- 未改冻结集、expected、scorer 或门禁；未把 replay 或归档输出冒充 live。
- Prompt 为通用规则，未写入 d-007/d-008/d-015/d-016 的 id 或 goal 文本；有反硬编码测试。
- 当前证据只覆盖本地/stub；staging 结论待补。
