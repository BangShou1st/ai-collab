# AI Collab 下一阶段泳道冻结（A/B/C/D）

> 日期：2026-11-12（Asia/Shanghai）
> 基线：`dev @ d4247c34`，工作区干净，仅主 worktree，远端 `origin/dev` 同 SHA
> 性质：Phase 0 冻结。**先冻结、再派发**；任何泳道不得自行扩大范围。
> 设计依据：[Python AI 能力平面](../../design/python-ai-capability-plane-2026-11-11.md) §7 / §9 / §10；[AC-07 Memory V2](../../design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md) §4–§13；[W3.5 收口报告](../report/w3-5-blockers-20261112.md)
> 上游证据：[W3.5 修复轮](../../acceptance-evidence/2026-11-12/w3-5-remediation/report.md)、[S3 真实模型评测](../../acceptance-evidence/2026-11-12/w3-s3-real-model-evals/report.md)、[W3–W5 总审查包](../report/w3-w5-consolidated-20261112.md)

---

## 0. 不可变基线

本波所有分支、测试、staging 操作和报告都必须以 `d4247c34` 为共同基线；冻结文档提交后，以该文档提交为新的共同基线。以下六项是生产默认开关，任何泳道不得修改：

| 开关 | 必须保持 |
| --- | --- |
| `RERANK_MODE` | `java` |
| `DECIDE_MODE` | `java` |
| `PLANNING_DRAFT_MODE` | `java` |
| `MEMORY_JOB_ENABLED` | `false` |
| `INTERNAL_AI_TOKEN_MODE` | `static` |
| `AI_WORKER_TOKEN_MODE` | `static` |

规则：

1. 只允许在**仓库外**的 staging override 或测试 profile 中临时改变量；收尾必须恢复上表默认值。
2. `decide.mode` / `planning.draft.mode` 的 `python` 模式仍未实现；不得在本波实现或启用。
3. 不碰 `main`、不 force push、不改写已推送历史、不修改历史迁移、不改前端。
4. 确需数据库迁移只允许新建 `V92+`；先证明无法复用现有表/列。
5. 证据等级只允许 `IMPLEMENTED < TESTED(offline) < TESTED(STUB) < LIVE_VERIFIED`；`NOT_VERIFIED`、`INCONCLUSIVE`、`NOT_EVALUATED`、`BLOCKED` 不得写成 `PASS`。
6. 429、timeout、provider 5xx 必须单列；不得把它们从门禁分母中删除后宣称达标。
7. 密钥、令牌、cookie、二维码、登录态不得进入仓库、报告、日志或命令回显。

---

## 1. 泳道总表

| 泳道 | 目标 | 分支 | worktree | 证据目录 | 启动条件 |
| --- | --- | --- | --- | --- | --- |
| **A / W6** | MCP Gateway/Host 前置设计评估，不写实现 | `w6/preassessment` | `E:/project/ai-collab-worktrees/w6-preassessment` | `docs/design/` + `docs/archive/review/` | 本文冻结后立即 |
| **B / W3** | magpie/DS provider staging 诊断 | 无（不提交仓库） | 无；仅服务器 `box-aae54f` | `docs/acceptance-evidence/2026-11-12/w3-magpie-diagnostic/` | 本文冻结后立即，独占服务器 |
| **C / W4** | decide 一致率修复与冻结集复跑 | `w4/decide-fix` | `E:/project/ai-collab-worktrees/w4-decide-fix` | `docs/acceptance-evidence/2026-11-12/w4-decide-fix/` | 本文冻结后先写代码；B 释放服务器后才做 staging 复跑 |
| **D / W5** | Job/Memory 闭环 + 三组对照 | `w5/job-memory` | `E:/project/ai-collab-worktrees/w5-job-memory` | `docs/acceptance-evidence/2026-11-12/w5-job-memory/` | **B 已释放服务器且 C 代码冻结**后才启动 |

子 agent 最多两层；任何时刻活动子 agent 不超过 4 个。A/B/C 可并行；D 不得提前启动。

### 1.1 共同交付纪律

- A/C/D 各自只在自己的 worktree 提交；不得直接 push `dev`、不得直接改主工作树、不得互相 cherry-pick。
- B 不 commit、不改代码；只把脱敏证据写入指定证据目录，交由中心提交。
- C/D 代码冻结后通知中心；由中心审查原始证据并 cherry-pick 到 `dev`，逐笔提交。
- 任何发现跨泳道问题时，通知中心，不在本泳道顺手修复。
- 若范围超出一次会话，按停止条件交付可运行部分、失败证据和明确交接；不得用“基本完成”掩盖。

---

## 2. 泳道 A：W6 前置评估（纯设计）

### 2.1 允许产出

- `docs/design/w6-mcp-gateway-host-2026-11-12.md`
- `docs/archive/review/w6-preassessment-2026-11-12.md`

### 2.2 必须覆盖

1. Java MCP Gateway 与 Python MCP Host 的职责、信任边界和调用拓扑：
   - Java 唯一持有凭据、出网策略、工具白名单、`schemaHash`、审计与审批边界；
   - Python 只做 `initialize/tools/list/tools/call` 协议适配与 schema 归一化，不直接持有 provider 密钥，不成为权限中心。
2. 只读工具白名单、工具 schema/`schemaHash` 的生成、冻结、漂移检测与拒绝策略。
3. 参数校验、授权再校验、跨项目隔离、写工具/审批工具禁止进入 MCP 白名单的机制。
4. 凭据托管、出网 allowlist、SSRF/重定向/DNS 重绑定防护、提示注入清洗与工具结果二次校验。
5. 撤权、禁用工具、端点下线、凭据轮换与缓存失效；撤销后不得靠旧索引/缓存继续返回。
6. 失败分类（连接失败、超时、schema 漂移、未授权、部分结果、工具未执行、结果未知）、降级、回滚和默认关闭策略。
7. 可观测指标：调用量、延迟、错误分类、schema 漂移、被拒调用、撤权延迟、审计命中；给出低基数标签建议。
8. W6 实现前置条件、风险表、待人工决策项；明确本波**不写实现**。

### 2.3 明确不做

- 不写 Java/Python 实现，不改配置、依赖、迁移、前端或服务器。
- 不安装 MCP SDK，不连接真实第三方 MCP，不提交任何凭据。
- 不将 W6 设计结论写成“实现可开始”；实现仍为 `BLOCKED`，等待三项前置条件清零。

### 2.4 停止条件

- 若设计需要改变生产默认开关或现有审批/权限语义，停止并列为人工决策。
- 若无法在不接触真实凭据的前提下完成设计，停止在评审文档，不进行服务器验证。

---

## 3. 泳道 B：W3 provider 诊断（独占 staging）

### 3.1 环境与边界

- 服务器：`box-aae54f`（个人服务器，不是生产）；Debian 13 x86_64，Node 22.23.3；Bun/Chromium/Xvfb/scrot 已装；`/data` 余量约 4GB。
- 安装位置：`/data/home`；版本以实际安装记录为准；插件 `magpie-marvis@0.2.22`。
- 只做 provider 诊断：不改仓库、不 commit、不改六个默认开关、不碰其他 compose 项目。
- 不得读取、复制、记录用户 cookie/session/密码；二维码只临时展示给用户扫码，**不得写入仓库或长期证据**。

### 3.2 任务

1. 在 `/data/home` 安装 magpie Linux 终端版，添加 `magpie-marvis@0.2.22`。
2. 启动登录，截取二维码交给用户扫码；登录态只留在服务器临时运行目录，权限 `0600`，诊断结束删除。
3. 验证网关 `127.0.0.1:3425/v1`：
   - `GET /v1/models` 可用；
   - 一次最小 chat 成功；
   - 一次用户请求恰好一次 provider 调用（日志计数）。
4. 通过管理 API 注册 staging provider；只记录脱敏 id/name/model/baseUrl，不记录 API Key。
5. 用冻结集跑 tier 8 共 15 次，相邻调用间隔 ≥10s；记录 429、timeout、model P50/P95、known ratio，并按停止条件决定是否继续 tier 20/50。
6. 证据写入 `docs/acceptance-evidence/2026-11-12/w3-magpie-diagnostic/`，至少包含 report、原始 aggregate/log、脱敏 provider 元数据、登录/清理记录；原始日志不得含凭据。

### 3.3 结论口径

- 只回答：“DS 经 magpie 在当前 staging 额度下能否稳定跑 3s 预算？”
- 标签最高为 `LIVE_VERIFIED (staging, third-party diagnostic)`，样本不足标 `LOW_CONFIDENCE`。
- 不宣称生产可灰度；不得把 magpie 结果外推到其他 provider/额度。
- 429 单列，不计入模型失败；3s 门禁失败率 ≤1%、known ratio ≥99%、overhead P95 ≤800ms、P99 ≤1500ms。

### 3.4 停止与释放

停止条件：登录失败、网关不兼容、429 >10%、Java 容器无法访问 magpie。

释放前必须：

1. 停止 magpie 登录/诊断进程，删除二维码截图、临时日志、cookie/session 和临时 env；
2. 记录 staging provider 是否保留、是否健康、对应 model；若保留，必须无密钥泄漏；
3. 明确通知中心“服务器已释放”，并给出可复用的 staging 端点/限制；
4. 不删除其他项目的容器、卷或数据。

---

## 4. 泳道 C：W4 decide 修复

### 4.1 范围

只改 W4 相关代码、prompt 和策略；保持工具 schema、幂等、deadline、shadow 只读语义。默认 `DECIDE_MODE=java` 不变；不实现 Python 生产模式；不改前端、不加迁移。

### 4.2 已冻结的 decide 契约

- 请求/响应遵循 W4 冻结契约：`schemaVersion=1`，四决策 `CALL_TOOL/FINAL/ASK_USER/NO_OP`，`CALL_TOOL` 必须有 `toolCall.name`（∈本次 tools）和 JSON object `arguments`；其它决策不得携带 `toolCall`。
- 幂等键 `(runId, attempt, turnNo, snapshotHash)`；同键同 body 重放，同键不同 body 拒绝；缓存 10 分钟、只缓存成功。
- `deadlineMs` 默认/上限 30000；超时 `DEADLINE_EXCEEDED`，不得返回半成品。
- Python 只返回建议；Java 继续校验工具名、Schema、项目权限、`grantedTools`、写审批、预算、幂等。
- `decide.mode=java` 零外呼；`shadow` 只异步观测，不执行 Python 的 toolCall；`python` 模式仍未实现。

### 4.3 已知问题和修复目标

已冻结的 20 条 golden 中，d-007、d-008、d-015（`ASK_USER`）和 d-016（`NO_OP`）被系统性改成 `CALL_TOOL`，集中在“信息不足/范围未确认/无新输入”场景。先定位原因，再改 prompt/策略；不得只对四条写死分支。

门禁（冻结集原始分母，不得删样本、不得改期望、不得改 scorer）：

| 指标 | 门槛 | 20 条 golden 的最低观察 |
| --- | --- | --- |
| `decisionAgreement` | ≥0.98 | 20/20 |
| `jsonValidityRate` | ≥0.995 | 20/20 |
| adversarial `invalidToolCalls` | 不增（基线 0） | 0 |

provider 5xx/timeout/429 单列；可另报“剔除基础设施失败”的诊断值，但**不得用诊断值替代门禁值**。门禁值不达标即 FAIL；基础设施失败导致无法完成时只能 `NOT_VERIFIED`，不能写 PASS。

### 4.4 交付

- 代码 + prompt/策略改动 + 定向测试（Python 单元/契约；如触及 Java shadow 则含 Java 定向测试）。
- 冻结集 replay 复跑和 staging live 复跑（B 释放服务器后）；报告须列 32 例原始 case 结果、429/timeout/provider error、完整分母和证据等级。
- 证据目录：`docs/acceptance-evidence/2026-11-12/w4-decide-fix/`。
- 分支 `w4/decide-fix` 提交；不 push `dev`，由中心 cherry-pick。

### 4.5 停止条件

- 若修复需要改变工具 schema、审批/权限语义、默认开关或引入生产 `python` 模式：停止并报告。
- 若只能靠改冻结集、删失败样本、放宽阈值达到指标：判定 FAIL，不得伪造 PASS。
- B 未释放服务器前不得连 staging；本地/stub 证据可先完成。

---

## 5. 泳道 D：W5 Job + Memory 闭环（条件启动）

启动门：**B 已释放服务器且 C 代码冻结**。D 使用独立 worktree `w5/job-memory`；不得在 C 未冻结时改共享文件。

### 5.1 范围

1. `AiJobDispatcher` / `AiJobRecoveryJob` 真实触发：`MEMORY_JOB_ENABLED=false` 保持默认；只在显式测试/受控 staging 中开启。
2. 状态机与并发：
   - `QUEUED → LEASED → RUNNING → SUCCEEDED / FAILED_RETRYABLE → QUEUED / FAILED / CANCELED / EXPIRED`；
   - `lease_token` + 单调 `lease_epoch` fence；迟到 heartbeat/results 返回 `STALE_LEASE`；
   - `(job_id, dedupe_key)` 幂等；重试指数退避有上限；`AiJobRecoveryJob` 真实回收过期租约。
3. Ed25519 job claims：`jobId/attempt/leaseEpoch/projectRef/snapshotHash` 与路径/请求逐项绑定；scope 至少覆盖 `job:execute/job:snapshot/job:heartbeat/job:results`。
4. `/v1/jobs` 端到端：Java 创建/租赁 → 签发 claims → Python 拉快照 → 模型调用 → 心跳/结果回报 → Java 落结果。
   - 必须在显式测试态 Ed25519 下完成 E2E；
   - 默认 `static` 下 `/v1/jobs` 的 503 是预期 fail-closed 负例，**不得再以该 503 作为“未验证”结论**；报告要同时给出 static 负例与 Ed25519 正例。
5. 候选审核/发布闭环（仅 Java API，不改前端）：
   - job 结果只能 `PENDING_REVIEW`；不得自动发布；
   - 项目管理员显式 approve/reject；approve 才允许写正式 `agent_memory`，写操作仍走现有审批/权限校验；
   - 并发审批只生效一次；审计可查；非管理员拒绝；跨项目拒绝。
6. D7-C：A `LEGACY` / B `GOVERNED_RECALL` / C `DREAMING` 三组对照：
   - 冻结样本/项目快照/模型配置/Prompt 版本；
   - 报告 Recall@K、引用有效率、冲突误用率、撤权泄露数、任务结果正确率、Token、P50/P95、Dreaming 费用、人工审批率；
   - precision/recall 只作诊断，不作门禁；撤权泄露必须为 0；
   - C 未真实跑通就标 `NOT_EVALUATED`/`NOT_VERIFIED`，不得写 PASS。

### 5.2 默认与回滚

- `MEMORY_JOB_ENABLED=false`、`PLANNING_DRAFT_MODE=java`、`DECIDE_MODE=java`、`RERANK_MODE=java`、两种 token mode 均为 `static`。
- 回滚 = 开关恢复默认 + 重启；V91 只增表，回滚镜像必须兼容 V91；确需新表/列只允许 V92+。

### 5.3 交付与停止

- 代码 + Java/Python 定向测试 + staging E2E 原始日志 + 三组对照报告。
- 证据目录：`docs/acceptance-evidence/2026-11-12/w5-job-memory/`。
- 若一次会话无法完成：交付可运行的 Job 骨架 + 已通过的测试 + 明确未完成项，通知中心交接；不得把 `static` 503、stub 或 skeleton 写成闭环完成。

---

## 6. 中心验收与合并

1. 中心直接审阅原始 aggregate/raw/log，不只看子 agent 总结；抽查至少一条原始日志与结论一致。
2. 检查 429/timeout 是否单列、`NOT_VERIFIED`/`INCONCLUSIVE`/`NOT_EVALUATED` 是否被写成 PASS、门禁是否被放宽。
3. 每个代码修复必须有对应测试；集成 HEAD 上由独立验证 agent 跑全量 Java/Python 门禁，中心审查原始输出。
4. 按泳道 cherry-pick 到 `dev`，逐笔提交；临时分支合并后立即删除；若提交不是 `dev` 祖先，按 AGENTS.md 先打 `archive/20261112/*` tag 再删。
5. 最终确认：`git branch` 仅 `dev/main`、`git worktree list` 仅主工作树、无临时日志、六个默认开关未变。

### 6.1 最终判定词

| 词 | 含义 |
| --- | --- |
| `PASS` | 指定门禁、指定环境、指定证据等级全部满足 |
| `FAIL` | 已执行且未达到门禁 |
| `NOT_VERIFIED` | 环境/证据不足，不能推定 |
| `INCONCLUSIVE` | 已执行但评测口径无效 |
| `NOT_EVALUATED` | 对照/验收未执行 |
| `BLOCKED` | 明确前置条件未清零 |

---

## 7. 需要人工决策的事项

1. W3 3s 预算：若 magpie/DS 仍不达标，是否接受“只用 Java rerank + 30s 诊断口径”，或投入付费额度。
2. W4 原冻结集只有 20 条 golden，≥98% 等价于 20/20；是否在 W4 之后扩充冻结集，而不是在本波修改样本。
3. W5 候选发布是否作为产品能力启用；本波只做受控审核 API，不自动发布。
4. W6 实现是否可在 W3/W5 前置条件未清零时继续 BLOCKED；本波只做设计，不启动实现。

