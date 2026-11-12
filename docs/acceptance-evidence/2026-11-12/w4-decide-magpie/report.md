# W4 Magpie provider revalidation（准备中）

> 状态：**READY / NOT_STARTED**（尚未占用或连接 staging）
> 性质：provider revalidation，不是第三轮 Prompt 修复
> 基线：`d6fa5133`；代码/证据分支 `w4/decide-final`；Prompt 固定 `decide.system@2026-11-12.3`
> 原 v1 Gemini 证据：`docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md`（**FAIL，保留**）

执行后的证据标签固定为 `LIVE_VERIFIED (staging, Magpie/Marvis, provider revalidation)`。

## 0. 结论口径

本报告尚未执行真实 Magpie live，不能写 PASS/FAIL。执行后必须使用同一 v1 32 例（20 golden
+ 12 adversarial）、原 scorer、原分母，**独立跑 3 轮**（pooled 60 golden / 96 total）；
Magpie 只是 provider revalidation，不得把 Gemini 的 FAIL 改写，也不得改标签、删样本或更换
Prompt。每轮均记录 5xx/504/429，故障计入分母。

门禁与 v1 相同：

| 指标 | 门禁 |
| --- | ---: |
| pooled golden `decisionAgreement` | ≥ 0.98（/60） |
| pooled golden `jsonValidityRate` | ≥ 0.995（/60） |
| adversarial `invalidToolCalls` | 不增（基线 0） |
| 429 / 5xx / timeout | 单列，不从分母剔除 |
| 稳定性闸 | 每轮 golden 基础设施失败 ≥2 或 429 → `UNSTABLE`；加跑第 4 轮诊断或 FAIL |

## 1. 已准备脚本

- `raw/harness/run_magpie_revalidation.py`：登录、provider snapshot、创建
  `OPENAI_COMPATIBLE` provider、`/test`、`publish`、临时切换 `AGENT`、调用现有
  `run_decide_live.py` 三次、写每轮与 pooled、恢复 Gemini `0f58581a…`、删除临时 provider。
- `raw/harness/run_decide_live.py`：复用现有 v1 live harness，不修改其 scorer 或冻结集。
- Key 只从 staging `/data/home/magpie-gw.key`（0600）读取；不打印、不传命令行、不写证据。
- 目标 provider：`marvis/deepseek-v4-1-flash-261017`，base URL 为本次指定的 Magpie HTTPS
  gateway，`apiPath=/v1/chat/completions`。

## 2. 执行顺序（等中心明确 D+B 已释放）

1. 在 `E:\project\ai-collab\target\release-locks\` 写入 `staging-box` / `staging-model`
   的 `owner=lane-c` 锁；
2. 将当前 `w4/decide-final` 的追踪文件同步到 staging，确认 worker 镜像含 `.3`；
3. 运行 `--check-local`（代码/资产检查）和 `--dry-run`，再运行 `--run`（3 轮）；
4. 拉回 raw、生成最终 report、验证 AGENT purpose 已恢复且临时 provider 已删除；
5. 立即释放两把 staging 锁；不跑 v2，不做第三轮 Prompt。

## 3. 待填结果

| 项 | 状态 |
| --- | --- |
| 3 轮独立 live（60 golden / 96 total） | NOT_RUN |
| 429 / 5xx / timeout | NOT_RUN |
| 六个默认开关 | 执行时复核 |
| provider / purpose before-after | 执行时保存 |
| raw aggregate / 逐 case / run-meta | 执行时保存 |

