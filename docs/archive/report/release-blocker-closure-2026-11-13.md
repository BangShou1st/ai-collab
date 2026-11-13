# Release Blocker Closure 收口报告（2026-11-13）

> 基线：`dev @ c166292d`（本报告提交前的集成 HEAD）；W4 v3 live 补记基线 `dev @ 37692380`
> 范围：W3 / W4 / W5 release blocker 收口；W6 实现 readiness
> 证据等级：`IMPLEMENTED < TESTED(offline) < TESTED(STUB) < LIVE_VERIFIED`；`NOT_VERIFIED` 不写成 PASS
> 结论：W5 PASS；W3 `3S_FAIL / FUNCTIONAL_PASS`；W4 v3 live 两轮 FAIL（0.85 / 0.95，项目侧 prompt/policy）；W6 生产 `NOT_READY / BLOCKED`

---

## 1. 泳道结果表

| 泳道 | 目标 | 最终结果 | 证据等级 | 关键证据 |
| --- | --- | --- | --- | --- |
| A / Reranker 配置平台 | RERANK 四层配置、测试/发布/回滚/审计、本地 Adapter | **IMPLEMENTED / TESTED (offline)**；管理端 REST API 完成，前端 UI `NOT_IMPLEMENTED` | TESTED (offline) + Testcontainers | `docs/acceptance-evidence/2026-11-12/w3-reranker-config/`；`docs/archive/report/w3-reranker-config-2026-11-12.md` |
| B / W3 Reranker 实测 | 8/20/50 3s 门禁 + 30s functional | **`FUNCTIONAL_PASS / 3S_FAIL`** | LIVE_VERIFIED (staging, admin capability test) / FAIL | `docs/acceptance-evidence/2026-11-12/w3-reranker-local/report.md` |
| C / W4 decide | 标签复核 + Prompt/Policy + 32 例 live | **FAIL（项目侧 prompt/policy）**；Gemini、Magpie v1 与 v3 live 三套 FAIL 均保留 | LIVE_VERIFIED (staging, real model) | `docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md`；`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/report.md`；`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/report-v3-live-20261113.md` |
| D / W5 Memory C | C DREAMING、A/B/C 对照、sourceRef、删除 500 | **PASS** | LIVE_VERIFIED (staging) + TESTED (offline) | `docs/acceptance-evidence/2026-11-12/w5-memory-c/report.md` |
| E / W6 readiness | MCP Gateway/Host 实现 readiness | **NOT_READY / BLOCKED**（生产）；仅可 default-off 实现 | 设计/评审 | `docs/archive/review/w6-implementation-readiness-2026-11-12.md` |

---

## 2. 门禁结果表

### 2.1 W3

| 档位 | 3s 门禁（run 2） | 30s functional | 说明 |
| --- | --- | --- | --- |
| 8 | **PASS**（15/15，model P95 1714ms，overhead P95 19.98ms） | **PASS**（15/15，overhead P95 45.56ms） | 8/20/50 的 functional 各 15/15；8 档 60 次观测 60/60 |
| 20 | **FAIL**（15/15 adapter_TIMEOUT 3003ms；proxy 实际 4.0–5.8s） | **PASS**（15/15，overhead P95 109.32ms） | 3s 预算在正式连续负载下不够；不是 adapter 计数 bug |
| 50 | **NOT_RUN**（20 档失败停止） | **PASS**（15/15，overhead P95 71.99ms；补测 60/60） | 50 档 30s model P50/P95/P99 17.8/19.6/21.4s |
| 1:1 调用 | PASS（30/30） | PASS（30/30；50 档补测 60/60） | 1 次请求 = 1 次 `/rerank` |
| 生产用户路径 | **NOT_VERIFIED** | **NOT_VERIFIED** | `RERANK_MODE=java` 未变；admin capability test ≠ 生产灰度 |

**结论：3s 门禁 FAIL 保留，不缩候选、不改阈值；30s functional 通过，仅证明功能可用。**

### 2.2 W4

| 运行 | golden agreement | JSON | adversarial invalidToolCalls | 基础设施失败 | 判定 |
| --- | --- | --- | --- | --- | --- |
| Gemini 3.5 Flash Lite v1 | 12/20 = 0.60 | 15/20 = 0.75 | 0 | 5×503 + 3×504 | **FAIL** |
| Gemini 诊断（剔除基础设施） | 12/15 = 0.80 | 15/15 = 1.00 | 0 | — | 仅诊断，不替代 FAIL |
| Magpie r1 | 15/20 = 0.75 | 20/20 = 1.00 | 0 | 0 | FAIL |
| Magpie r2 | 14/20 = 0.70 | 20/20 = 1.00 | 0 | 0 | FAIL |
| Magpie r3 | 14/20 = 0.70 | 20/20 = 1.00 | 0 | 0 | FAIL |
| **Magpie pooled** | **43/60 = 0.7167** | **60/60 = 1.00** | **0** | **0 / 0 / 0** | **FAIL（项目侧）** |
| Magpie v3 r1 | 17/20 = 0.85 | 20/20 = 1.00 | 0 | 0 | FAIL |
| Magpie v3 r2（有效） | 19/20 = 0.95 | 20/20 = 1.00 | 0 | 0 | FAIL |
| **Magpie v3 两轮合并（诊断）** | **36/40 = 0.90** | **40/40 = 1.00** | **0** | **0 / 0 / 0** | **FAIL** |

**根因：provider 稳定；v3 两轮均未达到 0.98。r1 为 d-006/d-012/d-018，r2 仅 d-018
复现；d-014/d-020 的 v3 goal 修正两轮通过。主要剩余问题是“无来源工具摘要被当作已核实
事实”（d-018）。另有 1 次 r2 被 worker 10 分钟幂等缓存整轮重放，已标 `CACHE_REPLAY /
INVALID` 并排除，不计入两轮；有效第 2 轮在 TTL 过期后重跑。**

### 2.3 W5

| Arm | taskAccuracy | revocationLeaks | crossProjectLeaks | conflictMisuse | citationValidity |
| --- | ---: | ---: | ---: | ---: | ---: |
| A LEGACY | 0.3636（4/11） | 33 | 0 | — | 0.70 |
| B GOVERNED_RECALL | 0.7273（8/11） | 0 | 0 | 0 | 1.0 |
| C DREAMING | **0.9091（10/11）** | **0** | **0** | **0** | **1.0** |

- Job `e65fbaa4-938b-44cf-99e1-0574370018f8`：SUCCEEDED，11/11 候选人工 approve，`autoPublished=0`，审批率 1.0。
- C ≥ B、C ≥ A；C 门禁 **PASS**。
- sourceRef：11/11 result 的 snapshot_hash/refs 与 attempt 一致，11/11 命中 `fact:project`；拒绝矩阵覆盖非法前缀、不存在 ref、跨 Job、跨项目、快照篡改、审批前 hash 变更。
- 测试项目删除 500：V97 `ON DELETE CASCADE`，staging 删除返回 **204**，项目/Job/result/memory 残留为 0；删除审计保留且 `project_id=NULL`。
- 限定：每 arm 11 tasks，`LOW_CONFIDENCE`；live sourceRef 仅覆盖 `fact:project`，`memory:` 路径由 V96/V97 Testcontainers 覆盖。

### 2.4 W6

| 前置 | 状态 | 依据 |
| --- | --- | --- |
| W3 3s | FAIL；functional PASS | B report |
| Ed25519 | PASS | 既有 14/14 staging 证据，未重做 |
| W5 | PASS | D report |
| W4 | 相邻阻塞 FAIL | C report；不作为 W6 前置 |
| W6 生产 readiness | **NOT_READY / BLOCKED** | 仅可 default-off 实现，需用户明确批准；不得宣称生产可灰度 |

---

## 3. 缺陷状态表

| 编号 | 状态 | 说明 |
| --- | --- | --- |
| D1 OpenAI 扩展参数 | 已修 `6d96741f` | thinking 参数透传 |
| D2 镜像 HEALTHCHECK | 已修 `ded49902` | 8081 健康检查 |
| D3 注册开关双源 | 已修 `a8dceee7` | env 单一事实源 |
| D4 模型延迟准入 | **未修** | W3 evidence 证明 provider/reranker 延迟是真实风险；仍缺供应商准入门禁 |
| D5 planning 默认 deadline | 已修 `42fee251` | 30000 上限对齐 |
| D6 负样本设计 | 已修 `dfe81893` | 定向变异 |
| D7 memory 评测/来源 | **已关闭（有限定）** | W5 C PASS、sourceRef 快照治理 `4fd32479`；live 仅 fact:project |
| D8 Job claims / dispatcher | **已关闭** | W5 Job/审批 E2E PASS；static fail-closed 保留 |
| D9 worker 鉴权探针 | 文档化，不改代码 | 使用合法 body + 非法 Idempotency-Key 探针 |
| D10 证据标签 | 已修 `dfe81893` | 显式 evidence/quality_claim |
| N1 Reranker `top_n` 截断 | 已修 `35bf52b3` | 9/10/20/50 文档回归 |
| N2 `ModelCapability.RERANK` / admin 500 | 已修 `d691e4d6` / `b8a58b85` | 管理端 providers 列表 200 |
| N3 W3 20 档 3s 超时 | **未修/未改门禁** | functional 30s PASS；3s FAIL 保留 |
| N4 W4 prompt/policy 一致率 | **后续已调优，历史 FAIL 保留** | Magpie v1 pooled 0.7167；v3 两个有效轮 0.85 / 0.95；后续 5 轮中 R5 `2026-11-13.5` 达到 golden 20/20（`docs/acceptance-evidence/2026-11-13/w4-continuous-tuning/report.md`），但未改默认门禁 |
| N5 Reranker 前端 UI | **NOT_IMPLEMENTED** | 仅管理端 REST API |
| N6 Java 全量 Testcontainers 抖动 | **环境未闭环** | 全量 4 errors，定向 116/116 PASS；CI 需复验 |

---

## 4. 提交 / CI / 分支 / 清理表

| 项 | 状态 |
| --- | --- |
| 集成分支 | `dev`；最终代码 HEAD `2275c1cf`（本报告随后为 docs-only 提交） |
| W3 冻结/补充 | `d6fa5133`、`61c65512`、`c6e7e9cd` |
| Lane A | `2412b8be`、`b93bec68`、`4da55cb3`、`35bf52b3`、`99c47d5e`、`b8a58b85`、`34c5eaff` |
| Lane B | `72a10621`、`643a48ff`、`83bc97dc`、`ca0ec067`、`ea45be4a`、`570be7a5` |
| Lane C | `8f36d17c`、`6b97bcc8`、`db6aa4f3`、`54806675`、`76387751`、`ee102d1d` |
| Lane D | `09eaa3af`、`7b3d2118`、`1dc32d56`、`12943611` |
| Lane E | `c166292d` |
| `main` | 未动；本地 `main` 落后 `origin/main` 71 |
| force push / 历史改写 | 无 |
| 分支/worktree | 收口后只留 `dev` / `main` 与主 worktree；临时分支先打 `archive/20261113/<lane>` tag 再删 |
| 临时日志/密钥/模型 | 收口后清理；Magpie 二维码、gateway key、临时登录态、QR 截图、JWT/Ed25519 临时密钥不得进仓库 |
| CI | **success**：run `43299787847` @ `2275c1cf`（代码 HEAD）；run `43301480075` @ `476c8679`（最终 HEAD，含 Image build & container smoke）全部 success |

---

## 5. 仍需人工决策表

| # | 决策 | 当前建议 |
| --- | --- | --- |
| 1 | 专用 Reranker 是否上线 | 当前不启用；生产保持 `RERANK_MODE=java`。30s functional 仅证明功能可用，不能替代 3s SLO |
| 2 | 是否接受 8 候选或更长延迟 | 8 档 3s PASS；20/50 需要 30s。若产品要用，明确候选上限与延迟预算，另立验收 |
| 3 | W4 prompt/policy 后续 | v3 live 历史 FAIL 保留；连续调优 R5 `2026-11-13.5` 已达 20/20，评测轮次已使用独立 runId/snapshotHash 防缓存；是否将 v3/v5 纳入默认门禁仍需产品决策 |
| 4 | W6 是否 default-off 开工 | 生产和 W6 readiness 仍 BLOCKED；只有用户明确批准才可 default-off 实现 |
| 5 | Magpie 作为后续共享 Provider | 仅 staging 验证；生产接入需单独准入、密钥、配额与审计评审 |
| 6 | Java 全量 CI | 本机全量受 Testcontainers 抖动；以 push 后 CI 为准，CI 未绿不得发布 |

---

## 6. 最终判定词

| 波次 | 判定 |
| --- | --- |
| W3 | **`3S_FAIL / FUNCTIONAL_PASS`**（3s 不豁免；functional 只证明可用） |
| W4 | **FAIL（v3 r1 0.85 / r2 0.95；项目侧 prompt/policy）** |
| W5 | **PASS** |
| W6 | **NOT_READY / BLOCKED（生产）**；仅可 default-off，需用户批准 |

---

## 7. 证据索引

- W3 配置平台：`docs/acceptance-evidence/2026-11-12/w3-reranker-config/`
- W3 实测：`docs/acceptance-evidence/2026-11-12/w3-reranker-local/`
- W4 Gemini：`docs/acceptance-evidence/2026-11-12/w4-decide-final/`
- W4 Magpie：`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/`
- W4 v3 live：`docs/acceptance-evidence/2026-11-12/w4-decide-magpie/report-v3-live-20261113.md`
- W5：`docs/acceptance-evidence/2026-11-12/w5-memory-c/`
- W6：`docs/archive/review/w6-implementation-readiness-2026-11-12.md`
- 最终门禁：`docs/acceptance-evidence/2026-11-13/final-gates/`

---

## 8. 后续状态更新（2026-11-14）

> 本节为追加，不改写 §1–§7 的 2026-11-13 快照结论。

| 项 | 2026-11-13 快照 | 2026-11-14 更新 | 依据 |
| --- | --- | --- | --- |
| W4 | FAIL（v3 r1 0.85 / r2 0.95） | **PASS（调优验收）**：Prompt `2026-11-13.5` golden 20/20、JSON 20/20、invalidToolCalls 0 | `docs/acceptance-evidence/2026-11-13/w4-continuous-tuning/report.md` |
| W6 生产 | NOT_READY / BLOCKED | **CLOSED_AS_DEFAULT_OFF**：交付完成，本轮不上生产；`agent.mcp.gateway.enabled=false` | `docs/archive/report/w6-production-decision-2026-11-14.md` |
| Canary | WAITING_FOR_CANARY_APPROVAL | **CANCELLED_BY_DECISION**：用户决定暂不进入生产 | 同上 |

W6 的 `CLOSED_AS_DEFAULT_OFF` 是范围决策，不是缺陷关闭；P2-C 的 A/B/C 卡点仍然成立，
未来重新立项时按新报告 §4 的 8 项前置验收。
