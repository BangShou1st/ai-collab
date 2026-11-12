# W3.2 Ed25519 双端联调 + 密钥轮换 + 回滚（staging 验收报告）

- 执行日期：2026-11-12（UTC；Asia/Shanghai 同日）
- 执行者：A-EXEC（S2）
- 仓库：`E:\project\ai-collab`（`dev`，未修改任何仓库代码、未 commit、未 push、未触碰 `main`/前端/迁移）
- 沙箱：`box-aae54f`；栈 `/data/home/ai-collab`，compose 项目 `ai-collab-deploy`
- 范围：Ed25519 双端矩阵、dual→k2 轮换演练、回滚 static；**只做 staging 验证与证据归档**
- 敏感信息：全程未回显/未落盘任何私钥、静态令牌或 JWS；报告与日志中令牌均为 0 命中

---

## 1. 结论速览

| 项 | 结论 | 证据等级 |
| --- | --- | --- |
| 14 项矩阵 | **14 项 PASS / 0 FAIL**（含 05:41–05:42Z item 1 补测；04:29Z 的 provider 429 历史保留） | LIVE_VERIFIED(staging) |
| 轮换演练（dual → k2） | 见 §7（观察窗口与计数） | LIVE_VERIFIED(staging) |
| Java 进程实际签发 k2 | **LIVE_VERIFIED(staging)**：仓库外 Java 探针调用 app.jar 内真实 `InternalTokenIssuer`，用容器 env 的 k2 私钥签发，header `kid=k2`，双端验签通过 | LIVE_VERIFIED(staging) |
| 回滚 static | 见 §8 | LIVE_VERIFIED(staging) |
| 最终两端模式 | 见 §9（双端 static） | LIVE_VERIFIED(staging) |

> 证据等级：`LIVE_VERIFIED(staging)` = 本次在 staging 双端真实进程/容器上直接观测；`NOT_VERIFIED` = 未达成或未执行。

---

## 2. 环境事实

| 项 | 值 |
| --- | --- |
| app 容器 | `ai-collab-app`（业务 8080，管理 8081；Docker health 因镜像探针打 8080 恒为 `unhealthy`，`8081/actuator/health` 为 `UP`，已知缺陷） |
| worker 容器 | `ai-collab-ai-worker-api`（9100，仅 `ai-collab-deploy_ai` 网络） |
| 共享 provider | `deepseek-ai/DeepSeek-V4.1-Flash-1017`（ModelScope，唯一已发布 provider；`KNOWLEDGE_CHAT` 唯一 purpose 绑定） |
| 网络 | 探针容器加入 `ai-collab-deploy_ai`，可同时访问 `app:8080` 与 `ai-worker-api:9100` |
| 令牌来源 | app 侧静态令牌 `RERANK_SERVICE_TOKEN`；worker 侧 `AI_WORKER_SERVICE_TOKEN`（同值，未回显） |

---

## 3. 关键实现事实与探针口径（重要）

1. **worker 的鉴权在 handler 内、请求体校验之后**：`rerank()` 先由 FastAPI 校验 `RerankRequest`，
   再调用 `require_token(...)`。因此最初用 `{}` 得到的 `422` **不能**证明认证通过（错误令牌同样
   返回 422）。
2. 修正后的 worker 廉价探针：**合法请求体 + 非法 `Idempotency-Key: bad`**。鉴权在读取幂等键之前，
   所以 `401` = 认证失败、`400` = 认证通过，且不触发任何模型调用。
3. Java 端 `{}` 在过滤器鉴权之后到达控制器，`422`（`SCHEMA_VIOLATION`）= 认证通过，口径成立。
4. 矩阵首轮（04:27–04:30Z）的 worker `422` 记录因上述事实被判为无效，已用修正探针全量重跑
   （04:39–04:41Z）；本报告只采用重跑结果。
5. 仓库 compose **不透传** `INTERNAL_AI_*` 与 `AI_WORKER_TOKEN_MODE/ED25519_PUBLIC_KEYS`；本次用
   仓库外 override `/data/home/w3s2/compose.s2.yml` + 600 权限 env 文件注入，未改仓库 compose。

---

## 4. 基线（改动前）

证据：`raw/baseline.txt`（04:25Z 实测）。

| 检查 | 结果 |
| --- | --- |
| app env | `RERANK_MODE=java`；`INTERNAL_AI_TOKEN_MODE` 未显式设置（默认 `static`）；`DECIDE_MODE`/`PLANNING_DRAFT_MODE`/`MEMORY_JOB_ENABLED` 未显式设置（默认 `java`/`java`/`false`） |
| worker env | `AI_WORKER_TOKEN_MODE` 未显式设置（默认 `static`）；`AI_WORKER_SERVICE_TOKEN` 已配置 |
| 静态令牌 E2E | worker `/v1/rag/rerank` **200**（PROVIDER usage）；Java `/internal/ai/v1/model-calls` **200**（真实 provider 文本） |
| 健康 | app `8081/actuator/health` = `UP`；worker `/readyz` = ready |

---

## 5. 密钥

| kid | 公钥 SPKI DER SHA-256 | 配对自检 |
| --- | --- | --- |
| k1 | `f145784b2552741173fdc0328b14ae23a3547d7e86cfe14626a5cf2ff391a9bb` | OK |
| k2 | `329cf83bb7354c3f1e42facb135de6b8eee6bfd043e25ceee383592f96762ef8` | OK |

- 生成：`openssl genpkey -algorithm ed25519` → PKCS#8 DER base64（私钥）/ SPKI DER base64（公钥），
  目录 `/data/.secrets/ed25519`（700，文件 600）。
- 私钥只在 app 侧注入；worker 只持公钥。
- **收尾已删除私钥文件**；`/data/.secrets/ed25519/` 仅残留空目录（无任何密钥材料），仅保留上表指纹（`raw/key-fingerprints.txt`）。

---

## 6. 14 项矩阵

完整逐项表（双端原始状态码 + 日志 reason）见 [matrix.md](matrix.md)。
**14 PASS / 0 FAIL（含补测）**：item 1 的 worker 端完整 rerank 已于 `04:29:34Z` 取得 **200**
（Java issuer k1 token，`usage.source=PROVIDER`，`raw/item1-first-run.jsonl`）；Java 端在
`04:29:36Z–04:46Z` 期间因共享 DeepSeek provider 配额耗尽返回 `429`
（`sourceError=AI_PROVIDER_QUOTA_EXCEEDED`，历史证据保留）。B 泳道发布 Gemini provider 后，
`05:42Z` 的补测（临时 kid `k3`）Java 端完整 model-call **第 1 次尝试即 200**，见 §6.1。
`429` 发生在鉴权之后，说明 token 已被接受；补测未修改 token 实现、未放宽判据。

## 6.1 item 1 补测（Gemini provider）

- 背景：B 泳道创建并发布了 Gemini provider（id `5b1d9af4-52d7-4055-8ca4-e654caa622b4`，
  model `gemini-3.1-flash-lite`，`KNOWLEDGE_CHAT/AGENT/PLANNING` 三个 purpose 均已指向它）。
  本次补测**未修改 provider 与 purpose 绑定**。
- 配置：生成临时 kid `k3`（Ed25519），用仓库外 override 把 app 配成 `ed25519`/k3 私钥、
  worker 配成 `ed25519`/k3 公钥，重建并等就绪（05:41:51Z）。
- 签发：仓库外 Java 探针调用 app.jar 内真实 `InternalTokenIssuer`，使用容器 env 的 k3 私钥；
  token 不打印，仅记录 sha256 前 12 位 `288d5b46f8a5`，header `alg=EdDSA, typ=JWT, kid=k3`。
- 请求：`POST /internal/ai/v1/model-calls`，`purpose=KNOWLEDGE_CHAT`、`deadlineMs=30000`、
  `outputFormat=TEXT`。
- 状态码序列：**200**（第 1 次尝试，未重试）。原始记录 `raw/item1-supplement.jsonl`。
- 响应（05:42Z；token `iat=1794462154`）：`modelAlias=gemini-3.1-flash-lite`，
  `usage={inputTokens:18, outputTokens:12, source:PROVIDER}`，`latencyMs=1249`，请求耗时 1343ms。
- worker 对照：同一 token 打 worker 完整 rerank → **200**，
  `usage={inputTokens:151, outputTokens:9, source:PROVIDER}`，`latencyMs=1984`。
- 回滚：双端回 `static`（05:42:49Z 重建），静态令牌 worker `400` / Java `422`（认证通过），
  同一 Ed25519 k3 token → worker `401 bad_signature` / Java `401 invalid_token`；两端健康正常。
  临时 k3 私钥、env 文件与 override 已删除（`raw/item1-supplement-rollback.txt`）。

补充事实（item 14）：Python 用 fixture 固定私钥 + 固定 claims 复现的 JWS 与 fixture `token`
字段**逐字节相同**（sha256 前 12 位 `880b83f8549e`）；该 token 打到 worker 返回 `401 expired`，
同一 fixture 私钥 + 新鲜 claims 返回 `400`（认证通过），证明解析/验签/过期判定路径一致。

---

## 7. 轮换演练（dual → k2 → converge）

时间线（UTC）：

| 时间 | 动作 | 结果 |
| --- | --- | --- |
| 04:43Z | rot1：app `dual`/k1 私钥；worker `dual`/k1 公钥；双端重建 | 就绪 |
| 04:46Z | rot2：worker 增加 k2 公钥（保留 k1），重建 worker | 就绪 |
| 04:46Z | rot3：app 切 `kid=k2` + k2 私钥 + 保留 k1 公钥，重建 app | 就绪；Java 探针签发 header `kid=k2` |
| 04:47Z | rot3 验签：k2 token 双端通过；k1 token 双端仍通过（旧公钥保留） | PASS |
| 04:47–04:59Z | 观察窗口（每 25s 一次 Java 签发 token；worker 廉价 / Java 廉价 / 每 3 次一次真实 rerank） | 见下 |
| 04:59Z | 收敛：worker `ed25519` 仅 k2 公钥 | 见下 |

观察窗口统计（`raw/observe-rot-k2-dual.log`）：

| 指标 | 值 |
| --- | --- |
| 时长 | **11m21s**（04:47:44–04:59:05，≥2×TTL=600s） |
| 探针迭代数 | **24** |
| 鉴权探针失败数 | **0**（判据：worker 400 / Java 422） |
| 真实 rerank 200 | 0 |
| 真实 rerank provider 429 | 8 |
| 真实 rerank 其他失败 | 0 |
| 观察期 Java 签发 token 的 kid | 全部 `k2` |

> **口径声明**：观察期的“鉴权失败数”为 0；真实 E2E 的 429 全部来自 provider 配额
> （`AI_PROVIDER_QUOTA_EXCEEDED`），不计入鉴权失败，但如实单列，未放宽判据。

收敛后观察（`conv-k2-ed25519`，`raw/observe-conv-k2-ed25519.log`）：

| 指标 | 值 |
| --- | --- |
| 时长 | **3m06s**（04:59:29–05:02:35） |
| 迭代数 | **8** |
| 鉴权失败数 | **0** |
| 真实 rerank 200 / provider 429 | 0 / 2 |

---

## 8. 回滚 static

| 检查 | 结果 |
| --- | --- |
| app `INTERNAL_AI_TOKEN_MODE=static`，无 `INTERNAL_AI_ED25519_*` | PASS（static 阶段 env dump） |
| worker `AI_WORKER_TOKEN_MODE=static`，公钥清空 | PASS（`AI_WORKER_ED25519_PUBLIC_KEYS` 为空） |
| 静态令牌：worker full rerank / Java model-call | 认证通过（worker `400` / Java `422`）；完整 200 被 provider `429` 阻塞 |
| Ed25519 token：worker / Java | `401`（worker `bad_signature`，Java `invalid_token`） |
| 回滚后 2 分钟探针 | **2m15s**（05:04:30–05:06:45），6 轮，鉴权失败 **0**，真实 E2E provider 429=2 |

---

## 9. 最终状态（必须）

最终两端环境（`2026-11-12T05:07Z` 采集，`raw/final-state.txt`）：

| 服务 | 键 | 值 |
| --- | --- | --- |
| app | `INTERNAL_AI_TOKEN_MODE` | `static` |
| app | `RERANK_MODE` | `java` |
| app | `DECIDE_MODE` | `java` |
| app | `PLANNING_DRAFT_MODE` | `java` |
| app | `MEMORY_JOB_ENABLED` | `false` |
| app | `INTERNAL_AI_ED25519_*` | 不存在 |
| worker | `AI_WORKER_TOKEN_MODE` | `static` |
| worker | `AI_WORKER_ED25519_PUBLIC_KEYS` | 不存在 |
| worker | `AI_WORKER_SERVICE_TOKEN` | 已配置（`<redacted>`） |

- 最终探针：静态令牌 worker `400`（认证通过）/ Java `422`（认证通过）；两容器日志 `grep eyJ` = 0。
- 补测回滚后再次确认（`05:43Z`）：双端仍为 `static`；静态令牌 `400`/`422`；临时 Ed25519 k3 token
  worker `401 bad_signature` / Java `401 invalid_token`；两端健康正常。
- 密钥清理：`/data/.secrets/ed25519/` 下私钥文件已删除（仅残留空目录，无密钥材料）；`app-token.env` / `worker-token.env` / `compose.s2.yml` 已删除；
  最终仅保留无密钥的 `compose.final.yml`。
- 其他 compose 项目（`ai-collab-devstaging-*`、`ai-collab-staging-*`、`spec-agent-private-*`）保持运行，未被操作。

---

## 10. 问题与限定条件

1. **provider 配额耗尽（已由补测闭环）**：04:29Z 起共享 DeepSeek provider 返回
   `AI_PROVIDER_QUOTA_EXCEEDED`，导致 item 1 Java 端完整 200 与观察期真实 E2E 无法取得 200；
   该历史证据保留。B 泳道发布 Gemini provider 后，05:42Z 的 item 1 补测取得 200（见 §6.1）。
   鉴权路径（401/400/422）在两次 provider 状态下均真实可用。
2. **worker 鉴权顺序**：见 §3；后续 runbook/验收若继续用 `{}` 的 422 作为“认证通过”会被误导。
3. **容器重建导致历史日志丢失**：每个阶段 `--force-recreate` 后旧容器日志不可再取；矩阵 reason
   在每次请求后即时采集并写入 `raw/*.jsonl`，最终容器日志只覆盖最后一个阶段。
4. **Docker health 缺陷**：镜像 HEALTHCHECK 打 8080，实际管理口 8081，`unhealthy` 属已知环境缺陷。
5. 未验证项：无（item 1 完整 200 记为 FAIL 而非未验证）。

---

## 11. 证据文件

| 文件 | 内容 |
| --- | --- |
| `report.md` | 本报告 |
| `matrix.md` | 14 项 × 双端逐项表 |
| `probe.log` | 轮换/收敛/回滚探针原始时间戳与状态码（令牌未出现） |
| `env-keys.md` | 各阶段 env 键值与最终状态（敏感值 `<redacted>`）、公钥指纹 |
| `raw/matrix-A.jsonl` | 矩阵阶段 A 原始记录 |
| `raw/item1-first-run.jsonl` | item 1 首轮：worker 200 + Java 429 原始记录 |
| `raw/item1-supplement.jsonl` | item 1 补测：Java 200（Gemini）+ worker 200 对照原始记录 |
| `raw/item1-supplement-rollback.txt` | 补测回滚：static 认证、Ed25519 拒绝、最终 env 与清理 |
| `raw/matrix-fixture.jsonl` | 跨语言 fixture 原始记录 |
| `raw/matrix-B.jsonl` | 旧/新公钥并存原始记录 |
| `raw/matrix-C.jsonl` | 仅新公钥原始记录 |
| `raw/rotation.jsonl` | rot1 下的 item 1 重试记录 |
| `raw/rotation-item11b.jsonl` | rot3 下 header kid 篡改记录 |
| `raw/baseline.txt` | 基线 env 与静态令牌 E2E |
| `raw/key-fingerprints.txt` | 公钥指纹 |
| `raw/auth-failures-app.log` / `raw/auth-failures-worker.log` | 最终容器生命周期的鉴权失败原始日志（无令牌） |
| `raw/observe-rot-k2-dual.log` / `raw/observe-conv-k2-ed25519.log` / `raw/observe-static-rollback.log` | 观察窗口原始探针日志 |
| `raw/final-state.txt` | 最终静态探针、日志卫生、其他 compose 项目状态 |
| `raw/final-env.txt` | 最终两端 env 键值（`<redacted>`）与健康状态 |
