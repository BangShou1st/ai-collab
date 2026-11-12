# W3 provider 诊断：DeepSeek 经 magpie/marvis（staging）

> 日期：2026-11-12（Asia/Shanghai；服务器时间为 UTC）
> 环境：个人服务器 `box-aae54f`，不是生产；staging Java/Python 容器未改代码、未改默认开关
> 结论：**FAIL（LIVE_VERIFIED, staging, third-party diagnostic；LOW_CONFIDENCE）**
> 唯一问题：**DS 经 magpie 在当前 staging 额度下能否稳定跑 3s 预算？不能。**

---

## 0. 一句话结论

DeepSeek V4.1 Flash 经 magpie + marvis 插件在 tier 8、deadline 3000ms 下 **15/15 全部失败**，失败类型全部是 `DEADLINE_EXCEEDED`（100%），**429=0**，model latency known ratio 为 **0%**。因此 3s 预算 **FAIL**；不能宣称生产可灰度，也不把该结论外推到其他 provider/模型/额度档。

---

## 1. 安装与登录

| 项 | 结果 |
| --- | --- |
| magpie | `0.1.3875`，Linux 终端版，安装在 `/data/home/.local/bin/magpie` |
| 插件 | `magpie-marvis@0.2.22`，已加载 8 个模型 |
| 登录 | 微信登录成功；插件报告 `signedIn=true` |
| 凭据 | 二维码只临时展示给用户；登录截图、登录态临时目录和登录日志在扫码后删除；未读取/回显任何 token、登录态或二维码内容 |
| 清理 | 诊断结束执行 `magpie plugin logout marvis`，插件 `signedIn=false` |

登录后的 gateway 使用 0600 文件保存客户端 key；该 key 只用于 staging provider 注册，未进入仓库或报告。诊断结束已删除 key 文件、管理 API token 文件和临时目录。

---

## 2. 网关、模型与 1:1 调用

magpie 网关通过 instabox HTTPS 端口发布临时暴露为 `https://3425-agqyc3kteqehqgazwefpmhwulu.instabox.tech`，并临时绑定到 staging 的 Docker 网络网关；诊断后已停止网关并 unpublish。

| 检查 | 结果 |
| --- | --- |
| `GET /v1/models`（带 key） | HTTP **200**，8 个模型，含 `marvis/deepseek-v4-1-flash-261017` |
| `GET /v1/models`（无 key） | HTTP **401**，说明公网发布不是匿名开放 |
| Java 容器访问网关 | HTTP **200**，容器经发布 HTTPS 看到同样 8 个模型 |
| 一次最小 chat | HTTP **200**，`content=OK`，usage 33 input / 2 output tokens |
| 1 次用户请求 = 1 次 provider 调用 | **PASS**：最小 chat 前后 `magpie usage` 仅新增 1 行；tier 8 的 15 次请求对应 15 条 499 行，无重试/转交 |

最小 chat 的 Magpie usage 行显示 `duration_ms=3234`（不是 3s 门禁值，只能作为诊断背景）。provider `/test` 返回 HTTP 200、`content=连接成功`、`latencyMs=6091`；Java 内部 `model-calls` 冒烟 HTTP 200、`latencyMs=1766`。这些都不是 tier 8 冻结集门禁成绩。

---

## 3. staging provider 注册

| 项 | 值 |
| --- | --- |
| name | `Magpie-Marvis-DeepSeek-V4.1-Flash` |
| id | `0b5f5aba-6909-49f2-877d-da2197967783` |
| providerType | `OPENAI_COMPATIBLE` |
| modelName | `marvis/deepseek-v4-1-flash-261017` |
| baseUrl / apiPath | 发布 HTTPS / `/v1/chat/completions` |
| test / publish | HTTP **200** / HTTP **200**，`sharedStatus=PUBLISHED` |
| purpose | 临时将 `KNOWLEDGE_CHAT` 指向该 provider；诊断后恢复原 Gemini 3.5 |
| 收尾 | provider 已删除；providers 列表回到基线 3 项；`KNOWLEDGE_CHAT` / `AGENT` / `PLANNING` 均恢复 `0f58581a-dbba-4e26-bafa-2e137f650563` |

管理 API 第一次用内网 `http://172.23.0.1:3425` 被出站安全策略拒绝（仅允许公网 HTTPS），因此改用临时 HTTPS 发布地址；这是 staging 诊断路径，不是生产建议。

---

## 4. tier 8 门禁

参数：tier **8**、15 次（15 条冻结 rerank case × 1 run）、`deadlineMs=3000`、相邻调用间隔 **10000ms**、worker 端点 `http://ai-worker-api:9100/v1/rag/rerank`。冻结集：`rerank_baseline.v1.jsonl`，15 行，sha256 `69ca5d90d9b24847f8cf390c21e9eefc2d3901f87bc0a91661a3ba3d80c3871f`。

| 指标 | 实测 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| attempts | 15 | — | — |
| samples（返回模型结果） | **0** | — | — |
| failures | **15** | ≤1% | **FAIL** |
| failureRate | **1.0（100%）** | ≤1% | **FAIL** |
| 429 | **0** | 单列 | 无 429 |
| `DEADLINE_EXCEEDED` | **15** | — | 全部失败 |
| modelKnownSamples | **0** | — | — |
| modelLatencyKnownRatio | **0.0** | ≥99% | **FAIL** |
| model P50/P95/P99 | **null / null / null** | 记录 | 无法计算 |
| overhead P50/P95/P99 | **null / null / null** | P95≤800/P99≤1500 | 无法计算 |
| confidence | `LOW_CONFIDENCE` | — | — |

停止规则：tier 8 失败率 100% 远高于 1%，**tier 20/50 未运行**，不得外推。

原始证据：

- [`raw/gate-8-3000-magpie.json`](raw/gate-8-3000-magpie.json)：aggregate，15 attempts / 15 failures / 0 samples / 0 `429Count` / 15 `DEADLINE_EXCEEDED`
- [`raw/app-gate8-timeouts.log`](raw/app-gate8-timeouts.log)：Java 内部网关 15 条 `internal model call 超时`，deadline=3000
- [`raw/worker-gate8-deadline.log`](raw/worker-gate8-deadline.log)：worker 15 条 `outcome=deadline error_type=DEADLINE_EXCEEDED` 与 HTTP 504
- [`raw/usage-gate.csv`](raw/usage-gate.csv)：15 条 status 499、requested/served model 均为 `marvis/deepseek-v4-1-flash-261017`，无 429、无重试/转交；账号列已脱敏
- [`raw/provider-test.json`](raw/provider-test.json)、[`raw/java-model-call-smoke.json`](raw/java-model-call-smoke.json)、[`raw/chat-response.json`](raw/chat-response.json)：非门禁的诊断背景

---

## 5. 解释与限定

- **结论只对本次 staging、`gemini` 之外的第三方 magpie/marvis 通道、当前免费账号额度和 `deepseek-v4-1-flash-261017` 成立。**
- 15 次都没有在 3s 内返回，因此不能计算 model P50/P95；`known ratio=0` 本身就是门禁失败证据，不能用“未知”写成“通过”。
- 429 为 0，失败不是限流；Magpie usage 记录的是客户端在约 2.8–2.9s 取消的 499 行，不能把 499 当作模型完成时间。
- provider `/test` 的 6091ms 和最小 chat 的 3234ms 只用于诊断背景，不替代 tier 8 门禁；它们也提示当前通道的尾延迟风险。
- 样本 15 < 200，标记 `LOW_CONFIDENCE`；即使 100% 失败，也只代表当前 staging 额度与配置。
- **不可宣称生产可灰度**；生产默认开关未改变。

---

## 6. 收尾与合规

| 检查 | 结果 |
| --- | --- |
| 代码/迁移 | 未改仓库代码，未 commit，未新增迁移 |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` 均未变 |
| purpose | 已恢复 Gemini 3.5（`0f58581a-dbba-4e26-bafa-2e137f650563`） |
| provider | 诊断 provider 已删除；providers 列表回到基线 3 项 |
| 网关 | `magpie serve` 已停止，3425 无监听；HTTPS 3425 已 unpublish；8443/9443 既有发布未动 |
| 登录/凭据 | 微信二维码截图、临时登录日志、一次性 Chromium profile、登录态、gateway key 与 admin token 临时文件已删除；插件 `signedIn=false` |
| 服务器 | 诊断进程已释放；未操作其他 compose 项目 |

清除记录见 [`cleanup.log`](cleanup.log)。

---

## 7. 最终判定

| 问题 | 答案 |
| --- | --- |
| `/v1/models` 可用？ | **是（200，8 models）** |
| 一次最小 chat 可用？ | **是（200，content=OK）** |
| 1 次用户请求 = 1 次 provider 调用？ | **是（usage 新增 1 行；tier 15 请求对应 15 行）** |
| DS 经 magpie 能否稳定跑 3s？ | **否，FAIL；15/15 `DEADLINE_EXCEEDED`，429=0** |
| 是否可宣称生产可灰度？ | **否** |
| tier 20/50 | **NOT_RUN（tier 8 失败率 100%）** |

