# P10-REAL-GENERATION-02 交付（阶段一）：预检通过，等待真实调用授权

**阶段一（只读核验与预检）已完成。真实 Chat 请求 0 次，外部 Embedding 0 次，Agent 0 次，模型裁判 0 次。**

本轮**没有**执行真实模型调用，也**没有**产出任何模型回答。交付物是预检证据、批次隔离的证据组装流程，
以及一份需要用户明确批准的调用确认单。执行裁决与模型质量裁决**均未作出**。

## 一、基线复核（不沿用上一会话结论）

| 项 | 实测值 |
| --- | --- |
| 分支 / HEAD | `dev@65139e2c53a08c3f7e860577d99f05af94bfb62e`（阶段一开工时） |
| `origin/dev` | 与本地一致（`0 0`） |
| `origin/main` | `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`，**未修改** |
| 工作区 | 干净，无未跟踪文件，无用户 WIP |
| PR #18 | OPEN / Draft / 未合并 |
| 开工时 CI | push `40281552739`、PR `40281560218` 同 SHA `65139e2` 均 success |

## 二、预检结论

### 路由与模型

- 实际路由：`p3u04_admin` → `KNOWLEDGE_CHAT` → **GEMINI / `gemini-3.5-flash-lite`**，`enabled=true`。
- 路由 SHA-256：`df17eb978e42ecb533a8f1f7a6ff756c6e1521abe6308f9671dca06e9123cbbc`
  ——用**生产代码路径**（同 SQL、同字段序、同 Jackson 序列化、同摘要算法）复算得出。
- 该值与 01 批次账本记录**完全一致**，说明路由配置未变：
  **01 批次的超时不能归因于路由或模型配错**，只能归因于当时出站选路为 DIRECT。
- 模型仍受支持（只读 GET，不消耗推理配额）：
  `GET /v1beta/models` → HTTP 200，目录 50 个模型，含 `gemini-3.5-flash-lite`；
  `GET /v1beta/models/gemini-3.5-flash-lite` → HTTP 200，`version=3.5-flash-lite-07-2026`，
  `outputTokenLimit=65536`，`supportedGenerationMethods` 含 `generateContent`。

### 网络前置

- 环境未变：Windows 系统代理仍为 `ProxyEnable=1` / `127.0.0.1:7897`，端口在监听。
- **实际 Surefire fork** 复测：选路 `PROXY`、`CONNECT` → `HTTP_200_TUNNEL_ESTABLISHED`、
  `TLSv1.3`、主机名校验通过、`chatRequests=0`。
- 额外加强：带 `x-goog-api-key` 的**只读** `GET /v1beta/models` 经同一代理返回 HTTP 200，
  证明"代理隧道 + TLS + 凭据鉴权 + Provider 应用层可达"整条链路成立。
  **这仍不是一次 Chat 请求，不证明 Provider 会接收推理请求。**

### 行为边界（代码事实）

- 自动重试：**无**（Gemini 非流式 `maxAttempts=1`）。
- 失败后模型切换：**无**（路由只解析一次，失败直接上抛）。
- 单次输出上限：`AiRequestOutputCap(1024)` 生效，出站 body `maxOutputTokens=1024`；持久配置 2048 不变。
- 外部 Embedding：0（本地 `qwen3-embedding:0.6b` / 1024 维可用）。
- Agent / 模型裁判：0。
- 应用侧频率限制：`knowledge.rate-limit-per-user-hour=60`，本轮 8 次远低于该上限。

### 零 Chat 预检

隔离 Testcontainers `pgvector/pgvector:pg17` 上 8 题全部 `PREFLIGHT_SYNTHETIC_TRANSPORT`，
`chatDispatchReservations=0`、`syntheticTransportResponses=8`、外部出站 0；
每题候选 8 / 入选 5，来源身份当次数据库核验通过。
合成响应不参与任何质量评分。

## 三、批次隔离与旧证据保护

- 门禁显式拒绝 `p10-real-generation-01`，要求 `P10_REAL_BATCH_ID` 与大写 `P10_APPROVAL` 匹配。
- 运行输出独立目录：`target/p10-real-generation-02/real/`。
- 新增 `build-real-evidence.cjs` 与 01 批次同名脚本**完全独立**：只读本批次 target 输出、
  只写本批次目录、不引用 01 批次任何文件；无授权、超预算、间隔 <15 s、输出上限偏离 1024、
  证据含凭据字段、冻结语料哈希漂移时**一律拒绝执行**。
- 旧批次复核：manifest **26/26 OK、0 失败**；`raw/real/` 与 `target/.../real/` 逐字节一致；
  旧 `real/` 账本仍在且 `task=P10-REAL-GENERATION-01`。
- **未删除旧 ledger、未清空旧输出、未绕过"已有真实 ledger 拒绝重跑"保护。**

## 四、如实标记为不可得 / 未核实的项

| 项 | 状态 |
| --- | --- |
| 账号计费状态 | `UNAVAILABLE`（本环境无 Web 访问、无计费接口；用户此前自述免费 API，未独立核实） |
| 免费额度剩余 | `UNAVAILABLE` |
| 速率限制 RPM/RPD | `UNAVAILABLE` |
| 实际费用 | `UNAVAILABLE`（不按推算值记账） |
| Provider 是否接收推理请求 | `UNKNOWN`（需执行后才能观察） |

间接证据（**不构成结论**）：本地 `ai_call_log` 中该模型有 5 次 `SUCCESS` + 1 次 `INVALID_OUTPUT`
（2026-10-23/24），说明该 API Key 此前可正常返回内容；不证明当前剩余额度，也不保证本轮成功。

## 五、Git 与 CI

| 项 | 值 |
| --- | --- |
| 最终 SHA | `ac1d3ba4ab51e5400bd35fabf6279cadba22f4bf` |
| 分支 | `dev`，`origin/dev` 已同步 |
| `main` | `9b4f7c5`，**未修改** |
| 工作区 | 干净 |
| push CI | [40291404865](https://github.com/BangShou1st/ai-collab/actions/runs/40291404865) — **success**（Backend success / Frontend success / Image skipped） |
| PR CI | [40291415733](https://github.com/BangShou1st/ai-collab/actions/runs/40291415733) — **success** |
| CI 内真实模型调用 | **0**（`P10RealGenerationPostgresTest` 记为 Skipped:1；`P10RealGenerationGateTest` 2 项通过） |
| PR #18 | OPEN / Draft / 未合并，headRefOid = `ac1d3ba` |
| 部署 / 付费 / 套餐升级 | 无 |

被 supersede 的 `6b5bf4b` PR CI（40287664480）为 **cancelled**，原因是 CI 自身
`concurrency: cancel-in-progress` 策略被新提交取代，**不是失败**；最终 SHA 的两个运行均独立成功。

## 六、下一步

等待用户对**本批次**的明确授权。授权后按既有 P10 门禁执行 8 题（每题最多 1 次 Chat、间隔 ≥15 s、
失败即停、不重试、不切换模型），随后运行冻结评分器，并分别作出执行裁决与模型质量裁决。

没有证据支持修改生产 Prompt、检索算法、来源选择、上下文预算、模型路由或输出上限。

完整证据：[README](../../acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)、
[预检快照](../../acceptance-evidence/2026-10-26/p10-real-generation-02/preflight-summary.json)、
[Git/CI 回执](../../acceptance-evidence/2026-10-26/p10-real-generation-02/checks/git-ci-receipt.json)、
[研究](../../archive/research/p10-real-generation-02-research-20261026.md)。
