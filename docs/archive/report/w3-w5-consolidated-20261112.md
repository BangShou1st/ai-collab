# W3+W4+W5 合并波次总审查包

> 日期：2026-11-12（跨零点；批次名沿用开工日）
> 基线：`dev @ 09205c6d`（本地领先 origin/dev 1 个提交）
> 交付：W3 收尾（W3.1 / W3.2 / W3.3）+ W4（J4 + P2）+ W5（J5 + P3）
> 单波报告：[W3 收尾](w3-final-20261112.md) · [W4 decide](w4-decide-20261112.md) · [W5 planning/memory](w5-planning-memory-20261112.md)
> 冻结契约：[W3 收尾](../handoffs/w3-final-freeze-20261112.md) · [W4](../handoffs/w4-decide-freeze-20261112.md) · [W5](../handoffs/w5-planning-memory-freeze-20261112.md) · [Ed25519 轮换](../handoffs/w3-ed25519-rotation-runbook-20261112.md)

---

## 0. 一句话结论

> CI：`dev` push（commit `942a6487`）GitHub Actions run **43103701256** **success**（3 个后端分片、
> Maven verify、Python worker、前端、P0 契约全绿；镜像/发布按 dev 策略 skipped）。

**可以进入 W6「前置评估」（只做设计与风险评审），但不可以开始 W6 实现**。三波代码、契约、
测试与默认关闭全部落地；但 **W3 的 overhead 只在 stub 下达标（真实模型 NOT_VERIFIED）**、
**Ed25519 未做 staging 双端联调**、**W5 的 Job dispatcher/候选发布未实现**。这三项清零前，
W3–W5 都不具备"生产灰度"资格。

---

## 1. 波次矩阵

| 波次 | 实现了什么 | 默认行为 | 门禁/测试 | 证据等级 | 风险/未完成 | 建议 human decision |
| --- | --- | --- | --- | --- | --- | --- |
| **W3.1** | Java/Python 延迟仪表、`modelLatencyMs` 透传、`ai-eval rerank` 三口径、stub 网关与基准脚本 | `rerank.mode=java` 不变；只多指标 | Java 全量 2869 项；Python 1104 项；测量 A 三档 510 样本 PASS（P95 11.93/24.41/37.91ms） | **TESTED（STUB）** | 真实模型 overhead **NOT_VERIFIED**；无 staging 无法做测量 B | 决定是否投入 staging 预算做真实模型测量与 canary |
| **W3.2** | Ed25519 JWS（300s、±60s、多 kid、static/ed25519/dual）、Java 签发/验签、Python 只验签并透传、跨语言 fixture | `static`（默认）；生产无需改动 | Java 148 项定向；Python 验签矩阵 + fixture | **TESTED（离线）** | staging 联调/轮换演练 **NOT_VERIFIED**；派生公钥为手写 RFC 8032 | 决定是否在下一窗口做 staging 双端联调 |
| **W3.3** | `rerank.shadow-sample-rate` + `skipped{reason=sample|saturated}` | `1.0`（保现状） | 单测 rate=0/1 | **TESTED**；真实流量 NOT_VERIFIED | 无真实流量验证抽样 | 观察期建议 staging 0.1–0.2 |
| **W4** | Python `/v1/rag/plan` + `/v1/agent/decide`（四决策/幂等/deadline）、Java `decide.mode=java|shadow` 只读影子、QueryPlan 契约客户端、`ai-eval decide` | `decide.mode=java`；QueryPlan 无调用方 | Java 228 项定向；Python 1104 项；decide 冻结集 golden 20/20、对抗 12/12 | **TESTED（STUB）** | 真实模型一致率 **NOT_VERIFIED**；`python` 模式未实现 | 决定是否在 staging 开 shadow 观察 ≥1 周 |
| **W5** | Python `/v1/planning/draft` + `/v1/jobs`、Java `planning.draft.mode=java|shadow`（重跑 Validator）、V91 Job 三表 + 状态机/租约/重试/内部端点、`ai-eval planning/memory` | `planning.draft.mode=java`；`memory.job.enabled=false` | Java 全量 2869 项（含 V91 真库）；Python 1104 项；planning golden 16/16、memory precision/recall 0.9 | **TESTED（STUB）** | dispatcher 真实调用、recovery 定时、**候选审核/发布未实现**；真实模型 NOT_VERIFIED | 决定 W6 前是否补 Job 触发与发布闭环 |

---

## 2. 新增开关总表（保证一键回退）

| 开关 | 默认值 | 作用 | 回滚方式 |
| --- | --- | --- | --- |
| `RERANK_MODE` | `java`（**未改**） | rerank 模式 java/shadow/python | 已默认 java，无需动作 |
| `RERANK_SHADOW_SAMPLE_RATE` | `1.0` | shadow 抽样比例 | 设 1.0 或回滚版本 |
| `INTERNAL_AI_TOKEN_MODE` | `static` | Java 签发/验签：static/ed25519/dual | 切回 `static`，复用 `RERANK_SERVICE_TOKEN` |
| `INTERNAL_AI_ED25519_PRIVATE_KEY` | 空 | PKCS#8 DER base64 私钥（仅 ed25519/dual 必填） | 清空 + mode=static |
| `INTERNAL_AI_ED25519_KID` | `k1` | 签发 header 的 kid | 换回旧 kid + 旧私钥 |
| `INTERNAL_AI_ED25519_PUBLIC_KEYS` | 空 | 额外受信公钥（轮换重叠） | 清空 |
| `INTERNAL_AI_TOKEN_TTL_SECONDS` / `_CLOCK_SKEW_SECONDS` | `300` / `60` | 短令牌 TTL / 允许偏移 | 默认即可 |
| `AI_WORKER_TOKEN_MODE` | `static` | Python 验签模式 static/ed25519/dual | 切回 `static` |
| `AI_WORKER_ED25519_PUBLIC_KEYS` | 空 | `kid=SPKI DER base64` 多 kid | 清空 + mode=static |
| `AI_WORKER_ALLOW_INSECURE_INTERNAL` | `false` | 仅测试放行（生产禁用） | 保持 false |
| `DECIDE_MODE` | `java` | java/shadow（python 配置即启动失败） | 设 `java` |
| `DECIDE_WORKER_BASE_URL` / `_DEADLINE_MS` / `_MAX_CONCURRENCY` / `_MAX_TOOLS` / `_SNAPSHOT_MAX_CHARS` | `http://ai-worker-api:9100` / `30000` / `8` / `16` / `32000` | shadow 边界 | mode=java 即不访问 |
| `QUERY_PLAN_ENABLED` | `false` | 未来接线开关（本波无调用方） | 保持 false |
| `QUERY_PLAN_WORKER_BASE_URL` / `_DEADLINE_MS` | `http://ai-worker-api:9100` / `8000` | 契约客户端 | 无调用方 |
| `PLANNING_DRAFT_MODE` | `java` | java/shadow（python 配置即启动失败） | 设 `java` |
| `PLANNING_DRAFT_WORKER_BASE_URL` / `_DEADLINE_MS` / `_MAX_CONCURRENCY` / `_SNAPSHOT_MAX_CHARS` | `http://ai-worker-api:9100` / `60000` / `4` / `48000` | shadow 边界 | mode=java 即不访问 |
| `MEMORY_JOB_ENABLED` | `false` | Job dispatcher 装配开关 | 设 false + 重启 |
| `MEMORY_JOB_MAX_ATTEMPTS` / `_LEASE_SECONDS` | `3` / `120` | 重试上限 / 租约时长 | 设 1 / 调大 |
| `MEMORY_JOB_RECOVERY_ENABLED` | `false` | 租约过期恢复任务 | 设 false |
| `AI_WORKER_QUERY_PLAN_MODEL_ALIAS` / `_DECIDE_MODEL_ALIAS` / `_PLANNING_MODEL_ALIAS` | `query-plan` / `agent-main` / `planning-main` | 响应 modelAlias | 无需回滚 |
| `AI_WORKER_QUERY_PLAN_DEADLINE_MS` / `_DECIDE_DEADLINE_MS` / `_PLANNING_DEADLINE_MS` / `_MEMORY_JOB_DEADLINE_MS` | `8000` / `30000` / `60000` / `60000` | 服务端 deadline 上限 | 调小 |
| `AI_WORKER_MEMORY_MAX_CANDIDATES` | `50` | 单次候选上限 | 调小 |

**回滚总原则**：所有新能力都默认关闭/保现状；回滚 = 开关回默认 + 重启对应进程；无数据库破坏性
变更（V91 只新增表，可保留）。

---

## 3. 提交清单（`09205c6d..942a6487`，50 个提交）

| 组 | 提交 |
| --- | --- |
| Phase 0 | `134809f2` 冻结 W3/W4/W5 契约 |
| W3.1 Java | `363202e5`、`2f4762ec`、`d7afb0de`、`3ff7f7b1` |
| W3.1 Python | `5de52d3a`、`b78f0d5c`、`77308d35`、`594efed0` |
| W3 证据/runbook | `78cd55a0`、`0a0aaa0c` |
| W3.2 Ed25519 | `958f1240`、`d95a2e45`、`600de8d4`、`674a18e6`、`563170b9`、`36a096f4` |
| W4 Python | `351d085d`、`a12ddfca`、`44fb17fb`、`5dcc7409`、`726465c5`、`d0e5e0df` |
| W4 Java | `23b1f86d`、`a138a4fe`、`7355b9d3`、`cb402226`、`90e89259`、`4c1602fc` |
| W5 Python | `91e54741`、`30e396a7`、`9cb1bae8`、`ede6bda7`、`43381b1e`、`3236c552` |
| W5 Java | `5e2ba837`、`4c9f562b`、`6e3721aa`、`bbebfbea`、`76e4edc2`、`cebf5ed6`、`fbc21c89` |
| 集成修复 | `843ae7b8`（RerankProperties 签名）、`e61a83fa`（prompt 指纹）、`e7b82fa3`→`4c1602fc` |
| 合并提交 | `16609071`、`ca3e9898`、`8231ecf5`、`fe8485bf`、`a942e8b8` |
| 报告/证据 | `942a6487` 三份单波报告 + 本审查包 + 验收证据更新 |

> 说明：`w3f/s1`、`w3f/s2`、`w4/s2` 经 cherry-pick 合入（提交哈希重写），原分支已打
> `archive/20261112/*` 本地 tag 归档后删除；其余分支用 merge 合入（保留原始提交）。

---

## 4. `git diff --stat`（推送基线 `4dc16c49`..`HEAD`）

```text
228 files changed, 34266 insertions(+), 343 deletions(-)
```

> 推送前 `origin/dev` 为 `4dc16c49`；`09205c6d..HEAD` 是本波自身的 50 个提交，
> `4dc16c49..HEAD` 额外包含开工前已有的 `09205c6d`（rerank 延迟预算设计）。

主要分布（按目录）：

| 目录 | 内容 |
| --- | --- |
| `ai-collab-backend/src/main/java/**` | rerank 仪表/采样、内部令牌、decide shadow、QueryPlan 契约、planning shadow、Job 状态机/端点 |
| `ai-collab-backend/src/main/resources/**` | `application.yml` 新配置段、`V91__ai_job.sql`、PromptPack fallback 同步 |
| `ai-collab-backend/src/test/**` | 令牌/decide/planning/job/仪表/prompt 指纹测试 |
| `ai-collab-ai-worker/src/**` | 三口径指标、plan/decide/planning/jobs 契约与端点、auth、eval 三件套、工具 |
| `ai-collab-ai-worker/tests/**` | 契约/边界/集成/评测测试与三套冻结集 |
| `docs/**` | 冻结契约 3 份、Ed25519 runbook、验收证据、单波报告与本审查包 |

---

## 5. 门禁与证据

| 门禁 | 结果 |
| --- | --- |
| Java `mvnw -B -ntp -o verify` | **2869 项，0 失败/0 错误/61 跳过，BUILD SUCCESS**（本地 Docker + Testcontainers） |
| Java 定向（W3/W4/W5 集成） | 228 → 213 → 76 项，全绿 |
| Python 全量 | **1104 passed**；ruff/format/mypy 全绿；契约快照 14；PromptPack 22 条；Java fallback 23 文件 |
| 测量 A（stub，门禁） | 8/20/50 各 510 样本，overhead P95 **11.93/24.41/37.91 ms**、P99 ≤45.96 ms，PASS，confidence=HIGH |
| 测量 B（staging 真实模型） | **NOT_VERIFIED**（本会话无 staging 访问） |
| Ed25519 真实联调 | **NOT_VERIFIED**（离线测试 + 跨语言 fixture 通过） |
| `ai-eval decide` | golden 20/20 一致且合法；adversarial 12/12 被拒；`qualityClaim=NOT_VERIFIED` |
| `ai-eval planning` | golden 16/16 schema pass；adversarial 5/5 被拒；`qualityClaim=NOT_VERIFIED` |
| `ai-eval memory` | precision/recall 0.9、duplicateRate 0.05；`qualityClaim=NOT_VERIFIED` |
| CI | 推送 `dev` 后的 Actions 结果见 §9（提交后补） |

**证据等级口径**：`IMPLEMENTED` ≤ `TESTED`（离线）≤ `TESTED (STUB)` ≤ `LIVE_VERIFIED`；
`NOT_VERIFIED` 表示本会话环境不可达，不能写成 PASS。

---

## 6. 未完成与风险（按严重度）

| # | 项 | 影响 | 建议 |
| --- | --- | --- | --- |
| 1 | **W3.1 真实模型 overhead 未测** | 无法证明 800ms 门禁在真实链路成立；不能切 `python` | 有 staging 时按三行口径补测；保留 `java` |
| 2 | **真实用户 canary 未做** | 无法证明真实分布质量/延迟 | 有流量环境 `shadow` ≥1 周 |
| 3 | **Ed25519 未 staging 联调/轮换演练** | 静态令牌仍是生产实际凭据 | 按 runbook 演练后再切 `ed25519` |
| 4 | W4 真实模型一致率未测 | 不能宣称 ≥98%/≥99.5% | staging `--scorer worker` 跑同一冻结集 |
| 5 | W5 Job dispatcher 真实调用/recovery 定时未实现 | Memory Dreaming 不可用 | W6 前补触发与恢复调度 |
| 6 | W5 候选审核/发布未实现 | 候选永远停在 `PENDING_REVIEW` | 需要产品决策与 Java 审核流程 |
| 7 | Python plan/decide/planning 的 Java 影子链路未真实联调 | 跨进程契约只在 stub 下验证 | staging 联调 |
| 8 | `rerank.model-user-id` 服务账号仍在 | 模型账本归属不精确 | 后续换系统共享模型档案 |

---

## 7. W6 前置评估：结论与最小阻塞项

**结论**：进入 **W6 前置评估（设计/风险评审）** 可以；进入 **W6 实现** 需要先清零：

1. **W3.1 真实模型测量 + 真实 canary**：staging 按 §3 三行口径跑 A/B，overhead P95 ≤800ms、
   失败率 ≤1%、model_latency_known_ratio ≥99%，再做 ≥1 周 shadow；
2. **Ed25519 staging 双端联调 + 密钥轮换演练**：`dual` 过渡 → `ed25519` 收敛，观察 ≥2×TTL；
3. **W5 Job 触发与候选发布闭环**：dispatcher 真实调用、recovery 调度、候选审核/发布（或明确
   产品决定"暂不发布"）。

W6（MCP Gateway + Host/Client）本身与上述三项解耦，但按设计 §9 的"每一波稳定 ≥5 个工作日"
纪律，W6 实现应在 W5 稳定后启动。

---

## 8. 合规检查

- [x] 未 force push、未改写已推送历史、未合并 `main`；
- [x] 默认行为不变：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
      `MEMORY_JOB_ENABLED=false`；
- [x] Python 零 DB、零业务写、零 Provider 密钥；所有模型调用经 Java 网关；
- [x] Python 输出只是建议：decide/planning 结果不进入执行/落库/事件；Java 每次重校验；
- [x] 迁移只新增 V91，未改历史；V91 只加表；
- [x] 无第二套运行时；无前端改动；
- [x] 日志/指标/错误体不含 token、Prompt、正文；
- [x] 临时分支/worktree 已清理（`git branch` 仅 dev/main，`git worktree list` 仅主工作树；
      3 个 cherry-pick 分支已打 `archive/20261112/*` tag 归档）；
- [x] 冻结文档/报告/证据按 `docs/` 规范存放。

---

## 9. 清理、推送与 CI

### 9.1 清理

```text
git branch            → dev, main（仅两个长期分支）
git worktree list     → 仅主工作树 E:/project/ai-collab
临时分支              → 已删除；w3f/s1、w3f/s2、w4/s2 为 cherry-pick 合入，
                         已打本地 tag archive/20261112/{w3f-s1,w3f-s2,w4-s2} 归档
```

### 9.2 推送

```text
git push origin dev   → 4dc16c49..942a6487  dev -> dev（未 force push）
```

### 9.3 CI（`dev` push，commit `942a6487`）

GitHub Actions run **43103701256**：**success**。

| job | 结论 |
| --- | --- |
| Backend tests (shard 1/3) | success |
| Backend tests (shard 2/3) | success |
| Backend tests (shard 3/3) | success |
| Backend (Maven verify) | success |
| Python worker (contract + prompts + eval) | success |
| Frontend (pnpm) | success |
| P0 production configuration and backup contracts | success |
| Image build & container smoke | skipped（dev push 策略） |
| Publish images to GHCR | skipped（dev push 策略） |

> 该 run 的 commit 已包含本审查包；后续仅对文档做 CI 结果补记（不改变代码），
> 其 CI 结论见最终 `dev` HEAD 的 run。

---

## 10. 最终判定表

| 问题 | 答案 |
| --- | --- |
| W3.1 stub 门禁是否达标？ | **是**（P95 11.93/24.41/37.91ms，余量 ≥20 倍） |
| W3.1 真实模型门禁是否达标？ | **未知（NOT_VERIFIED）** |
| W3 是否可宣称"可灰度"？ | **否** |
| W4 Python/Java 契约与 shadow 是否全绿？ | **是**（离线/stub） |
| W4 是否满足 §10.2 canary 条件？ | **否（真实模型未测）** |
| W5 planning/memory 是否全绿？ | **是**（契约/骨架/单测/真库迁移） |
| W5 是否可发布候选？ | **否（本波明确不实现）** |
| 是否可进入 W6 前置评估？ | **是** |
| 是否可开始 W6 实现？ | **否**，先清零 §7 三项 |



