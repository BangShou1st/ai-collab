# W3.2 Ed25519 双端矩阵（staging `box-aae54f`）

- 执行时间：2026-11-12 04:26–05:43 UTC（含分段重跑与 05:41–05:42Z 的 item 1 补测；item 13 在 static 回滚后执行）
- 判定口径：
  - worker：`401` = 认证失败；`400`（合法请求体 + 非法 `Idempotency-Key`，鉴权先于幂等键读取）= 认证通过；`200` = 完整成功。
  - Java：`401` = 认证失败；`422`（空 body 在鉴权之后被控制器校验拒绝）= 认证通过；`200` = 完整成功。
  - 说明：worker 的鉴权在 handler 内执行，FastAPI 会先做请求体校验，因此 **`{}` 的 422 不能证明认证通过**；矩阵统一改用能区分鉴权的探针（见 report §3）。
- 原始逐条记录：`raw/matrix-A.jsonl`、`raw/item1-first-run.jsonl`、`raw/matrix-fixture.jsonl`、`raw/matrix-B.jsonl`、`raw/matrix-C.jsonl`、`raw/rotation.jsonl`、`raw/rotation-item11b.jsonl`。

## 配置阶段

| 阶段 | app（Java） | worker（Python） | 用途 |
| --- | --- | --- | --- |
| A | ed25519，k1 私钥，无额外公钥 | ed25519，仅 k1 公钥 | 1–7、10、11a、12 |
| fixture | ed25519，k1 私钥 | ed25519，**k1 = fixture 固定公钥** | 14 |
| B | ed25519，k1 私钥 + 信任 k2 公钥 | ed25519，k1 + k2 公钥 | 8 |
| C | ed25519，k2 私钥，仅信任自身派生的 k2 | ed25519，仅 k2 公钥 | 9 |
| supplement | ed25519，临时 k3 私钥 | ed25519，仅 k3 公钥 | **item 1 补测**（B 泳道发布 Gemini provider 后） |
| rot3 | dual，k2 私钥 + 保留 k1 公钥 | dual，k1 + k2 公钥 | 11b（双端均已知 k1/k2） |

## 14 项结果

| # | 用例 | 端点 | 期望 | 原始状态码 | 日志 reason | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Java issuer（k1）签发的 Ed25519 token，完整 rerank | worker | 200 | **200**（04:29:34，PROVIDER usage；后续轮次 429） | — | **PASS（worker 端）** |
| 1 | 同上，完整 model-call | Java | 200 | **200**（05:42Z 补测，第 1 次尝试；Gemini provider） | — | **PASS（补测）** |
| 2 | `exp=now-120` | worker | 401 | 401 | `expired` | PASS |
| 2 | `exp=now-120` | Java | 401 | 401 | `expired` | PASS |
| 3 | 签名篡改 1 字节 | worker | 401 | 401 | `bad_signature` | PASS |
| 3 | 签名篡改 1 字节 | Java | 401 | 401 | `bad_signature` | PASS |
| 4 | `aud` 不含目标 audience | worker（aud=backend） | 401 | 401 | `audience` | PASS |
| 4 | `aud` 不含目标 audience | Java（aud=ai-worker） | 401 | 401 | `audience` | PASS |
| 5 | `sub=service` | worker | 401 | 401 | `subject` | PASS |
| 5 | `sub=service` | Java | 401 | 401 | `subject` | PASS |
| 6 | 未知 `kid=k9`（用 k1 私钥签） | worker | 401 | 401 | `unknown_kid` | PASS |
| 6 | 未知 `kid=k9`（用 k1 私钥签） | Java | 401 | 401 | `unknown_kid` | PASS |
| 7a | `exp=now-59` | worker | 通过 | 400 | — | PASS |
| 7b | `exp=now-61` | worker | 401 | 401 | `expired` | PASS |
| 7c | `iat=now+59` | worker | 通过 | 400 | — | PASS |
| 7d | `iat=now+61` | worker | 401 | 401 | `expired` | PASS |
| 7a | `exp=now-59` | Java | 通过 | 422 | — | PASS |
| 7b | `exp=now-61` | Java | 401 | 401 | `expired` | PASS |
| 7c | `iat=now+59` | Java | 通过 | 422 | — | PASS |
| 7d | `iat=now+61` | Java | 401 | 401 | `expired` | PASS |
| 8 | 旧公钥保留：k1 token 与 k2 token 均通过 | worker | 通过 | 400 / 400 | — | PASS |
| 8 | 旧公钥保留：k1 token 与 k2 token 均通过 | Java | 通过 | 422 / 422 | — | PASS |
| 9 | 只配新公钥：k1 token | worker | 401 | 401 | `unknown_kid` | PASS |
| 9 | 只配新公钥：k1 token | Java | 401 | 401 | `unknown_kid` | PASS |
| 9 | 只配新公钥：k2 token 仍可用 | worker / Java | 通过 | 400 / 422 | — | PASS |
| 10 | `alg=none`、空签名 | worker | 401 | 401 | `alg` | PASS |
| 10 | `alg=none`、空签名 | Java | 401 | 401 | `alg` | PASS |
| 11a | 签名后篡改 header `typ` | worker | 401 | 401 | `alg` | PASS |
| 11a | 签名后篡改 header `typ` | Java | 401 | 401 | `alg` | PASS |
| 11b | 签名后篡改 header `kid`（k1→k2，双端均已知 k2） | worker | 401 | 401 | `bad_signature` | PASS |
| 11b | 签名后篡改 header `kid`（k1→k2，双端均已知 k2） | Java | 401 | 401 | `bad_signature` | PASS |
| 12 | 日志/错误体不含令牌：`grep -c eyJ` | app / worker / 错误体 | 0 | 0 | — | PASS |
| 13 | static 回归：静态令牌 | worker | 200 / 认证通过 | 400 | — | PASS（认证通过；完整 200 被 provider 429 阻塞） |
| 13 | static 回归：静态令牌 | Java | 200 / 认证通过 | 422 | — | PASS（认证通过；完整 200 被 provider 429 阻塞） |
| 13 | static 回归：Ed25519 token 必须被拒 | worker | 401 | 401 | `bad_signature` | PASS |
| 13 | static 回归：Ed25519 token 必须被拒 | Java | 401 | 401 | `invalid_token` | PASS |
| 14 | 跨语言 fixture：Python 复现 token | worker | byte-identical + 401 `expired` | 401 | `expired` | PASS |
| 14 | 同一 fixture 私钥 + 新鲜 claims | worker | 认证通过 | 400 | — | PASS |

## 计数

| 结论 | 数量 |
| --- | --- |
| PASS | **14 / 14 项**（含 05:41–05:42Z item 1 补测；item 7 含 4 个子断言、item 13 含双端 4 个子断言，均 PASS） |
| FAIL | 无（item 1 的 04:29Z 429 历史记录保留，见下） |

## item 1 说明（含补测；原始 429 证据保留）

- **worker 端完整 rerank 200**：`2026-11-12T04:29:34Z`，Java issuer（k1）签发的 token，
  响应含 `scores` 与 `usage.source=PROVIDER`（`raw/item1-first-run.jsonl`）。
- **历史 429（保留，不删除）**：`04:29:36Z` 起至 `04:46Z`，Java 端完整 model-call 多轮尝试均返回
  `429 MODEL_RATE_LIMITED`，Java 日志 `sourceError=AI_PROVIDER_QUOTA_EXCEEDED`
  （共享 DeepSeek provider 配额耗尽）。该段历史仍保留在 `raw/matrix-A.jsonl`、`raw/rotation.jsonl`
  与 `raw/item1-first-run.jsonl`。
- **补测 PASS**：B 泳道发布 Gemini provider（`5b1d9af4-52d7-4055-8ca4-e654caa622b4`，
  `gemini-3.1-flash-lite`）后，`2026-11-12T05:42Z` 用临时 kid `k3`、真实 `InternalTokenIssuer`
  签发 token，Java 端完整 model-call **第 1 次尝试即 200**（`raw/item1-supplement.jsonl`），
  worker 对照 rerank 亦 200。
- 原 FAIL 根因为 provider/环境；补测使用新 provider，未修改 token 实现、未放宽判据。
