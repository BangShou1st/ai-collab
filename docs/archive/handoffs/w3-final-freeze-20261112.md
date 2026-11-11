# W3 收尾施工冻结（W3.1 延迟仪表 / W3.2 Ed25519 短令牌 / W3.3 shadow 采样）

> 日期：2026-11-12
> 基线：`dev @ 09205c6d`（W3 已交付；本波为 W3 收尾，**不改生产默认行为**）
> 设计依据：[Rerank 延迟预算与可观测设计](../../design/rerank-latency-budget-2026-11-12.md)、
> [Python AI 能力平面](../../design/python-ai-capability-plane-2026-11-11.md) §1.4 / §6.3 / §7.1 / §7.8 / §10
> 上游证据：[J3 rerank 报告](../report/j3-rerank-20261112.md)、[W3 冻结](w3-rerank-freeze-20261112.md)、
> [staging runbook](w3-staging-canary-runbook-20261112.md)
> 性质：Phase 0 冻结文档。**先冻结、再实现**；实现期若必须偏离，回写本文件并注明原因与证据。

---

## 0. 范围与红线

**做**

| 编号 | 内容 | 生产默认 |
| --- | --- | --- |
| W3.1 | Java/Python 延迟仪表、`ai-eval rerank` 三口径输出、本地 stub Java 网关 + 基准脚本、测量 A | 只加仪表，不改行为 |
| W3.2 | Ed25519 短令牌（Java 签发/验签、Python 只验签并透传） | `static` 模式默认，Ed25519 opt-in |
| W3.3 | shadow 采样率与 `skipped{reason}` | `sample-rate=1.0`（保现状） |

**不做**：不改 `rerank.mode` 默认值（仍 `java`）、不切 `python`、不改 Prompt 正文语义、
不删 `NeuralModelReranker`、不改前端、零迁移（本波无 DB 变更）、不引入新运行时。

**红线**：Python 零 DB、零业务写、零 Provider 密钥；日志/指标不含 query、候选正文、
Prompt、令牌；指标异常吞掉，不得改变业务结果。

---

## 1. W3.1 延迟测量（唯一口径）

### 1.1 四个时间量（引用设计 §2，不重新定义）

| 符号 | 定义 | 测量点 | 指标/字段 |
| --- | --- | --- | --- |
| `T_total_java` | `RerankModeRouter` 进入 → 返回排序结果 | `RerankModeRouter.ModeReranker.rerank` 单调时钟 | `rerank.total.duration{mode,outcome}` |
| `T_model` | Java 内部网关的 `latencyMs`（Provider 调用耗时） | `InternalModelCallController` → `ChatCompletionResult.latencyMs()`；经 worker 响应的 `modelLatencyMs` 回到 Java | `rerank.model.duration{provider,model,outcome}`；响应字段 `latencyMs`（Java 内部）与 `modelLatencyMs`（worker → Java） |
| `T_overhead` | `max(0, T_total_java − T_model)`，仅 `T_model` 已知时计算 | `RerankModeRouter`（python 模式成功样本） | `rerank.overhead{mode,model_latency_known}` |
| `T_worker_internal` | worker 端点内部：Prompt 构建 + 解析 + 打分 + 序列化，**不含** gateway HTTP | `api/rerank.py` | `ai_worker_rerank_duration_seconds{outcome}` 减去 gateway/parse 两段之和（同进程相对口径） |
| `T_worker_total` | worker 端点总耗时（含 gateway HTTP） | `api/rerank.py` finally | `ai_worker_rerank_duration_seconds{outcome}` |
| `T_shadow` | shadow 后台对比耗时 | `RerankModeRouter.runShadowComparison` | `rerank.shadow.latency`（已有） |

### 1.2 新增/变更指标（低基数）

**Java**（Micrometer，`publishPercentileHistogram`）

| 指标 | 类型 | 标签 | 记录点 | 说明 |
| --- | --- | --- | --- | --- |
| `rerank.total.duration` | Timer | `mode`(java/shadow/python), `outcome`(success/partial/fallback/skipped) | Router 三条路径 finally | java 模式也记（= 用户可见延迟基线） |
| `rerank.overhead` | Timer | `mode`, `model_latency_known`(true/false) | python 模式 finally | `known=false` 时记录 `T_total_java` 本身，**不计入门禁** |
| `rerank.model.duration` | Timer | `provider`, `model`, `outcome`(success/error) | `InternalModelCallController` | `model` 取模型档案里的模型名（受 `ai_model_profile` 约束的低基数），不取用户输入 |
| `rerank.worker.duration` | Timer | `outcome`(success/retry_success/failure) | `RerankGatewayClient.rerank` | 一次逻辑调用一条（含重试总耗时） |
| `rerank.worker.retries` | Counter | `attempt`(2/3) | 重试发生时 | 不放大基数 |
| `rerank.model_latency_known` | Counter | `known`(true/false) | Router python 模式 | 门禁前置：`known=true / (true+false) ≥ 99%` |
| `rerank.shadow.skipped` | Counter | `reason`(sample/saturated) | Router shadow 路径 | **W3.3**：`sample` 新增，`saturated` 沿用 |

> 既有 `rerank.calls` / `rerank.latency` / `rerank.fallback` / `rerank.partial_score` /
> `rerank.shadow.*` 全部保留，语义不变。

**Python**（独立 `REGISTRY`，低基数）

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| `ai_worker_rerank_duration_seconds` | Histogram | `outcome`(ok/partial/deadline/model_error/invalid) | **加 outcome 标签**（原无标签），语义不变 |
| `ai_worker_rerank_gateway_seconds` | Histogram | `outcome`(ok/error) | worker → Java 网关 HTTP 耗时（含超时/失败） |
| `ai_worker_rerank_parse_seconds` | Histogram | — | 模型输出解析 + 打分映射耗时 |
| `ai_worker_rerank_missing_scores_total` | Counter | — | 保留 |

### 1.3 日志

Java `RerankModeRouter` 每次 python/shadow 调用完成时一行 INFO/DEBUG，字段固定：

```
call_id=<projectId 或 unknown>:<idempotencyKey 前 8>:<attempt> mode=<java|shadow|python>
total_ms=<n> model_ms=<n|UNKNOWN> overhead_ms=<n|UNKNOWN> candidates=<n> outcome=<success|partial|fallback|skipped>
```

- `candidates` 是**条数**，不是正文；`call_id` 不含 query；
- 禁止：query 原文、候选 title/text、Prompt、令牌、Provider 原始异常文本；
- java 模式不打印该行（避免每请求一行日志），只记指标。

Python 日志同样只到 `call_id / outcome / elapsed_ms / missing_count / error_type` 粒度。

### 1.4 worker 响应新增字段（只加字段，`schemaVersion=1` 不变）

`POST /v1/rag/rerank` 响应增加**可选**字段：

```json
{"schemaVersion":1,"scores":[…],"modelAlias":"rerank-default",
 "usage":{…},"modelLatencyMs":1882}
```

- `modelLatencyMs` = Java 内部网关返回的 `latencyMs`（整数，ms，`>=0`）；未拿到时为 `null`/缺省；
- 语义：这是 `T_model` 的唯一来源。**不得**由 worker 用本地墙钟推算 Provider 延迟；
- Java 解析为 `RerankGatewayResponse.modelLatencyMs`（`Integer`，可空）；缺失 → 记
  `model_latency_known=false`，`T_overhead` 标 UNKNOWN，不冒充 0；
- 契约快照（`rerank-response.schema.json`）随模型重生成；Java 侧解析是宽松的（未知字段忽略、缺失字段容忍），保证旧 worker 仍可被新 Java 使用。

### 1.5 `ai-eval rerank` 输出

`--json` 报告的 `aggregate` 增加：

| 字段 | 类型 | 定义 |
| --- | --- | --- |
| `overheadP95Ms` | float \| null | 逐样本 `max(0, total_ms − model_latency_ms)` 的 P95（最近秩法）；样本全为 `model_latency_known=false` 时为 `null` |
| `modelP95Ms` | float \| null | 逐样本 `model_latency_ms` 的 P95；全未知时 `null` |
| `totalP95Ms` | float \| null | 逐样本客户端实测总耗时 P95（沿用现有 `p95Ms`，新字段是它的别名，二者相等） |
| `samples` | int | 参与延迟统计的样本数（= 现有 `latency.samples`） |
| `modelKnownSamples` | int | 其中 `modelLatencyMs` 可用的样本数 |
| `confidence` | `HIGH` \| `MEDIUM` \| `LOW_CONFIDENCE` | `samples>=500` → HIGH；`200<=samples<500` → MEDIUM；`<200` → LOW_CONFIDENCE |
| `candidateTarget` | int \| null | 使用 `--candidate-target` 时为该值，否则 `null`（用于 stub 基准的候选数覆盖） |

- `p95Ms` 保留（向后兼容），与 `totalP95Ms` 同值；
- 文本摘要同步输出三行：`overhead(p95)`、`model(p95)`、`total(p95)`，并注明门禁只看 overhead；
- 既有 canonical 口径不变：不测延迟时三字段全 `null`。

### 1.6 本地 stub Java 网关（仅测试，不进镜像）

`ai-collab-ai-worker/tools/stub_java_gateway.py`（stdlib `http.server`，**不打包进 wheel/镜像**）：

- `POST /internal/ai/v1/model-calls` → 固定合法响应：
  `{"schemaVersion":1,"content":"<固定 JSON>","usage":{"inputTokens":0,"outputTokens":0,"source":"ESTIMATE"},"modelAlias":"stub-rerank","latencyMs":0}`；
- `--port`（默认 9199）、`--delay-ms`（默认 0，可注入人工延迟做对照）、`--scenario rerank|decide|plan|planning|memory`（对端点的固定 content 形状）；
- 不鉴权（只绑 `127.0.0.1`）、不记录 prompt 正文、不落盘；
- 生产镜像不包含 `tools/`（Dockerfile 只 COPY `src/`、`prompts/`），已由构建上下文保证。

`ai-collab-ai-worker/tools/rerank_stub_bench.py`：

- 依次以 `--candidate-target 8/20/50` 调 `ai-eval rerank --scorer worker --measure-latency --runs N`，
  N 取 34（15 case × 34 = 510 样本/候选数）；
- 输出 JSON 证据（每候选数一行 P50/P95/P99、samples、confidence），并断言
  `overheadP95Ms<=800`、`overheadP99Ms<=1500`（P99 需要 CLI 额外输出 `overheadP99Ms`，见下）；
- 任一断言失败 → 退出码 1，报告写 `NOT_MET`，不得改门禁。

> 因此 `aggregate` 再增加 `overheadP99Ms`、`totalP99Ms`、`modelP99Ms`（同口径，允许 null）。
> 这是本冻结对设计 §5 的最小补充（设计只要求 P95）。

### 1.7 测量协议与样本量

| 测量 | 环境 | 候选数 | 样本 | 门禁 |
| --- | --- | --- | --- | --- |
| **A（门禁）** | 本机 worker 进程 + 本地 stub 网关（`T_model=0`） | 8 / 20 / 50 | 每档 ≥500（15 case × 34 runs） | overhead P95 ≤800ms、P99 ≤1500ms；confidence=HIGH |
| **A-Java** | Java 单测内嵌 stub worker（HTTP） + 500 次调用 | 8 / 20 / 50 | ≥500 | 同上（验证 Java 侧 `rerank.overhead` 仪表与门槛） |
| **B（观测）** | staging 真实模型 + 真实容器 | 8 | 记录实际样本量 | 不判门禁；三行分开输出；<200 标 LOW_CONFIDENCE |

**本会话环境事实**：本机无 instabox/staging 访问（`~/.ssh/config` 无对应主机、无 instabox CLI），
**测量 B 无法执行 → `NOT_VERIFIED`**；测量 A / A-Java 在本机执行并留证。

### 1.8 判据

- 测量 A 达标 → W3.1 `MET`（stub 证据等级 `TESTED`，非 `LIVE_VERIFIED`）；
- 未达标 → `NOT_MET`，保持 `rerank.mode=java`，记录 P95 定位（worker 内部 / 传输 / Java）；
- 测量 B 未执行 → `NOT_VERIFIED`，不得写成 PASS；W3 整体不得宣称"可灰度"。

---

## 2. W3.2 Ed25519 短令牌

### 2.1 令牌格式

JWS Compact（三段 base64url 无填充），`alg=EdDSA`、`typ=JWT`，Ed25519（RFC 8032）。

Header：

```json
{"alg":"EdDSA","typ":"JWT","kid":"<key-id>"}
```

Payload（同步服务令牌）：

```json
{"iss":"ai-collab-backend","sub":"backend","aud":["ai-worker","backend"],
 "scope":"rerank model-call","jti":"<uuid v4>","iat":1762900000,"exp":1762900300}
```

- TTL **300s**（`exp-iat=300`）；`iat`/`exp` 为秒级 Unix 时间；
- `aud` 必须含 `ai-worker`（worker 侧要求）与 `backend`（Java 内部端点要求）；
- `scope` 为空格分隔串；同步能力至少 `rerank`、`model-call`；W5 job 场景扩展
  `job:snapshot job:heartbeat job:results`（见 W5 冻结）；
- `kid` 指向当前签名密钥；Python 按 `kid` 选公钥，未知 `kid` → 401。

### 2.2 密钥与注入

| 侧 | 配置 | 默认 | 说明 |
| --- | --- | --- | --- |
| Java | `internal.ai.token.mode` (`INTERNAL_AI_TOKEN_MODE`) | `static` | `static` = 现状（`rerank.service-token`）；`ed25519` = 签发短令牌 |
| Java | `internal.ai.token.private-key` (`INTERNAL_AI_ED25519_PRIVATE_KEY`) | 空 | PKCS#8 DER 的 base64（单行）；仅 ed25519 模式必填，缺失启动失败 |
| Java | `internal.ai.token.kid` (`INTERNAL_AI_ED25519_KID`) | `k1` | 签发时写入 header |
| Python | `AI_WORKER_ED25519_PUBLIC_KEYS` | 空 | `kid1=<base64 SPKI DER>,kid2=<...>`，逗号分隔；支持多 kid 用于轮换 |
| Python | `AI_WORKER_TOKEN_MODE` | `static` | `static` = 校验 `AI_WORKER_SERVICE_TOKEN`；`ed25519` = 只验短令牌 |

- **私钥只在 Java**；Python 只持公钥，进程环境 allowlist 已拒绝 `*_API_KEY`/`JWT_SECRET`，
  公钥不是密钥材料，允许出现在 `AI_WORKER_*`；
- 私钥缺失/非法：Java **启动失败**（配置错误不静默降级）；公钥缺失/非法：Python 进程可启动，
  但所有需要鉴权的端点 **fail closed**（503 `MODEL_UNAVAILABLE`），`/readyz` 503；
- 轮换流程（runbook 落地）：① Python 配置先加入新 `kid` 公钥 → ② 滚动重启 worker →
  ③ Java 切 `kid` + 私钥 → ④ 旧公钥保留 ≥ 2×TTL（10 分钟）后移除。降级路径：两侧同时切回
  `static` + 同一 `RERANK_SERVICE_TOKEN`。

### 2.3 验签规则（两侧一致）

1. 三段结构、base64url 解码失败 → 401；
2. header `alg` 必须 `EdDSA`、`typ` 必须 `JWT`（缺失 `typ` 容忍，`alg=none` 直接拒绝）；
3. `kid` 必须在已知公钥集合内；
4. 签名对 `header.payload` 原始 ASCII 字节验签，失败 → 401；
5. `iss` 必须 `ai-collab-backend`、`sub` 必须 `backend`；
6. `aud` 必须包含本端要求的 audience（worker: `ai-worker`；Java 内部: `backend`）；
7. `scope` 必须包含本次操作要求的 scope（rerank: `rerank`；model-calls: `model-call`）；
8. 时间：`exp` 必须 > now−60s，`iat` 必须 ≤ now+60s；**允许 ±60s 时钟偏移**；
9. 所有失败返回 401 + 扁平错误信封，日志只记 `reason`（expired/bad_signature/unknown_kid/aud/scope/…），**不记令牌**。

### 2.4 透传

Java → worker 请求携带 `Authorization: Bearer <短令牌>`；worker 验签通过后，把**同一个令牌**
透传给 Java `/internal/ai/v1/model-calls`（不重新签发、不落盘、不写日志）。Java 侧用同一公钥
验 `aud=backend`、`scope=model-call`。

### 2.5 必测矩阵

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 合法令牌 | 通过 |
| 2 | `exp` 过期 | 401 / reason=expired |
| 3 | 签名被篡改 | 401 / reason=bad_signature |
| 4 | `aud` 不含本端 | 401 / reason=audience |
| 5 | `sub` 不符 | 401 / reason=subject |
| 6 | 未知 `kid` | 401 / reason=unknown_kid |
| 7 | 时钟偏移 −59s（未过期）/ −61s（过期） | 通过 / 401 |
| 8 | 时钟偏移 +59s（`iat` 未来）/ +61s | 通过 / 401 |
| 9 | 轮换：旧 `kid` 公钥仍在 | 通过 |
| 10 | 轮换：只配新 `kid` 的旧令牌 | 401 / unknown_kid |
| 11 | `alg=none` / 篡改 header | 401 |
| 12 | 日志与错误体不含令牌 | 断言无 `eyJ` 前缀 |
| 13 | `static` 模式回归 | 与 W3 行为逐字段一致 |
| 14 | 跨语言：Java 固定密钥签发的固定令牌 → Python 验签 | 通过（fixture 双向） |

### 2.6 证据等级

本地单测 + 跨语言 fixture = `TESTED`；未在真实 staging 双端联调 → `NOT_VERIFIED`（LIVE）。

---

## 3. W3.3 shadow 采样

- 配置：`rerank.shadow-sample-rate`（`RERANK_SHADOW_SAMPLE_RATE`），`double`，默认 **1.0**（保现状），
  合法区间 `[0,1]`，越界按边界收敛并在启动日志告警；
- 抽样：每次 shadow 机会独立均匀抽样（`ThreadLocalRandom`），`rate>=1` 全采、`<=0` 全不采；
  抽样判定在计算 Java 结果**之后**、提交后台之前，不影响用户可见结果与延迟；
- 指标：未采样 → `rerank.shadow.skipped{reason="sample"}`，并发饱和 → `{reason="saturated"}`；
  `rerank.calls{mode=shadow,outcome=skipped}` 保持（一次调用只记一条 skipped）；
- runbook：staging 建议 `0.1–0.2`；生产默认 1.0 不变（当前生产未启用 shadow）；
- 无 staging/无流量 → `NOT_VERIFIED`。

---

## 4. 默认开关与回滚（本波新增）

| 开关 | 默认 | 作用 | 回滚 |
| --- | --- | --- | --- |
| `RERANK_SHADOW_SAMPLE_RATE` | `1.0` | shadow 抽样比例 | 设 1.0（或回滚版本） |
| `INTERNAL_AI_TOKEN_MODE` / `AI_WORKER_TOKEN_MODE` | `static` | 启用 Ed25519 短令牌 | 两侧切回 `static`，复用 `RERANK_SERVICE_TOKEN` |
| `INTERNAL_AI_ED25519_PRIVATE_KEY` / `KID` | 空 / `k1` | 私钥与 kid | 清空 + mode=static |
| `AI_WORKER_ED25519_PUBLIC_KEYS` | 空 | 公钥集合（多 kid） | 清空 + mode=static |

`RERANK_MODE` 默认值 **不变**（`java`）。

---

## 5. 停止条件

1. 测量 A overhead >800ms → 记 `NOT_MET`，不切 `python`，报告写明定位；
2. 测量 B 无法执行（本会话无 staging）→ `NOT_VERIFIED`，不宣称可灰度；
3. Ed25519 若出现"必须引入不可接受的依赖/无法做跨语言验签"→ 交付设计 + runbook + 半成品开关
   **默认关闭**，报告列未完成项，不硬写；
4. 任一项改动导致 `java` 默认路径行为变化 → 立即回退该实现（默认路径必须逐字段等价）。

---

## 6. 证据等级登记（实现后回填）

| 项 | 等级（预期） |
| --- | --- |
| Java/Python 仪表、`ai-eval` 三口径 | TESTED |
| 测量 A（本机 stub，≥500/档） | TESTED（stub，非真实模型） |
| 测量 B（staging 真实模型） | NOT_VERIFIED（本会话不可达） |
| Ed25519 签发/验签/轮换/跨语言 | TESTED（离线） |
| Ed25519 双端联调 | NOT_VERIFIED |
| shadow 采样 | TESTED（单测）；真实流量 NOT_VERIFIED |
