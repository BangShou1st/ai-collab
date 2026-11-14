# W4 Magpie Provider 重验冻结（2026-11-13）

> 状态：用户已明确要求“换个模型、多测几次综合考虑”
> 范围：仅 W4 decide 的 **provider revalidation**；不是第三轮 Prompt 修改，不覆盖 Gemini 历史 FAIL
> 模型：`marvis/deepseek-v4-1-flash-261017`（Magpie / Marvis，staging 微信登录）
> Prompt：`decide.system@2026-11-12.3`，本轮冻结，不再改 Prompt
> 证据目录：`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/`

---

## 1. 背景

- Gemini 3.5 Flash Lite 在 W4 v1 单轮 live 中出现 5 次 provider 503、3 次 504；
  即使剔除基础设施失败，golden agreement 仍为 12/15=0.80。
- 用户已确认 Gemini 不稳定，不应继续用它阻塞 W4 进度；改用 staging Magpie/Marvis
  `marvis/deepseek-v4-1-flash-261017` 做 provider 重验。
- Gemini 单轮 32 例结果仍是历史证据：`golden agreement=0.60`、`JSON=0.75`，
  不得删除、覆盖或改写成 PASS。

## 2. 冻结测量协议

| 项 | 冻结值 |
| --- | --- |
| 数据集 | `decide_baseline.v1.jsonl`，32 例（20 golden + 12 adversarial） |
| 轮数 | **3 次独立运行** |
| 每轮请求数 | 32；总请求 96 |
| 相邻请求间隔 | ≥10s |
| deadline | 30000ms |
| 成功样本重试 | 禁止 |
| Prompt | `2026-11-12.3`（sha256 以 C 的 raw 为准） |
| Provider | 临时 `OPENAI_COMPATIBLE` Magpie provider；结束后恢复 Gemini 并删除临时 provider |

## 3. 冻结判定

- **主判定（pooled）**：
  - pooled golden `decisionAgreement` ≥ **0.98**（正确数 / 60）；
  - pooled golden `jsonValidityRate` ≥ **0.995**；
  - adversarial `invalidToolCalls` 不增（基线 0）。
- **每轮必须单独报告**：agreement、JSON、5xx、504、429、逐 case。
- 5xx/504/429 仍计入分母，不得当成功；报告可另给“剔除基础设施失败”的诊断值，
  但不得用它替代主判定。
- **稳定性闸**：若任一轮 golden 基础设施失败 ≥2 或出现 429，则该轮标记
  `UNSTABLE`；加跑第 4 轮，或按 FAIL/NOT_VERIFIED 收口。不得把故障轮当模型通过。
- **事后纪律**：不得在看到结果后改 pooled 分母、阈值、轮数或 case 标签；
  任何口径变更必须由中心/用户重新冻结。

## 4. 停止条件

1. 3 轮 pooled golden agreement <0.98：W4 Magpie 记 **FAIL**，停止，不再加跑凑绿。
2. pooled JSON <0.995：W4 Magpie 记 **FAIL**。
3. provider 持续 5xx/429、无法完成 3 轮：记 **NOT_VERIFIED / UNSTABLE**，不得写 PASS。
4. 需要改 Prompt、改标签、删 case、放宽阈值：停止，交中心/用户决策。

## 5. 环境与清理

- 只使用 staging `box-aae54f`；`staging-box` + `staging-model` 必显式占用。
- 测量窗口可临时切 `AGENT` purpose 到 Magpie；结束后恢复
  Gemini `0f58581a-dbba-4e26-bafa-2e137f650563` 并删除临时 provider。
- Magpie gateway key 只从 `/data/home/magpie-gw.key`（0600）读取，不打印、不提交、不进报告。
- 六个生产默认开关不得改变：`RERANK_MODE=java`、`DECIDE_MODE=java`、
  `PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、
  `INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。

## 6. 交付

- 原始 3 轮 aggregate + 逐 case JSONL + run-meta；
- pooled 聚合与每轮对照表；
- 报告明确写：Gemini 历史 FAIL、Magpie provider revalidation 结果、
  是否建议把 Magpie 作为 staging/生产候选 provider；
- 最终是否把 W4 的 provider 从 Gemini 切到 Magpie，由中心/用户决策，不由子 agent 自行宣称。
