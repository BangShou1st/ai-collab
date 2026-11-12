# Lane C READY 消息

状态：**READY / STAGING_NOT_ACQUIRED**

Lane C 的 Magpie provider revalidation harness 已准备完成，未连接 staging、未写 staging 锁：

- harness：`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/raw/harness/run_magpie_revalidation.py`
- shell 入口：`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/raw/harness/run_magpie.sh`
- 复用 v1 live harness：`docs/acceptance-evidence/2026-11-12/w4-decide-final/raw/harness/run_decide_live.py`
- Prompt 固定 `.3`；**3 次独立 v1 32 例**（60 golden / 96 total）、10s 间隔、deadline 30000、
  成功样本不重试
- 每轮单独 aggregate + pooled 聚合；pooled 主判定 agreement ≥0.98、JSON ≥0.995
- 任一轮 golden 基础设施失败 ≥2 或 429 → `UNSTABLE`；pooled 通过时最多加跑第 4 轮诊断，
  不把第 4 轮塞回主分母
- 证据标签：`LIVE_VERIFIED (staging, Magpie/Marvis, provider revalidation)`
- 目标 provider：`marvis/deepseek-v4-1-flash-261017`
- 脚本会创建/测试/发布临时 `OPENAI_COMPATIBLE` provider，临时切 `AGENT`，结束后恢复
  Gemini `0f58581a…` 并删除临时 provider
- key 只从 `/data/home/magpie-gw.key`（0600）读取，不打印、不写证据

等待中心明确“D 和 B 已释放”后，Lane C 才会写入 `staging-box` + `staging-model`
（owner=lane-c）并执行。当前不占用 staging。
