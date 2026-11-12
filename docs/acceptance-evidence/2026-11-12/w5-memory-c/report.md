# W5 Memory C + 来源治理：Lane D 验收报告（2026-11-12）

> 分支：`w5/memory-c`；基线：`d6fa5133`；合并 `dev @ 99c47d5e`；staging 部署代码：`4d0efa4b`
> 环境：staging `box-aae54f`，真实 Job + 真实 Gemini AGENT + 项目管理员显式 approve
> 结论：**C DREAMING `PASS`**（`LIVE_VERIFIED`）；sourceRef 快照治理与删除 500 回归通过
> 置信度：`LOW_CONFIDENCE`（每 arm 11 个任务，不得外推生产）

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| C 真实 Job | SUCCEEDED（job `e65fbaa4-938b-44cf-99e1-0574370018f8`，attempt 1） |
| C 候选 | 11 条，全部 `PENDING_REVIEW` → 项目 owner 逐条 approve |
| 自动发布 | **0**（approve 前 `AI_JOB_RESULT` 记忆数为 0） |
| 人工审批率 | **1.0（11/11）** |
| C 任务准确率 | **0.9091**（10/11） |
| A / B 任务准确率 | A **0.3636**、B **0.7273** |
| C ≥ B 且 C ≥ A | **PASS** |
| B/C `revocationLeaks` | **0 / 0** |
| B/C `crossProjectLeaks` | **0 / 0** |
| B/C `conflictMisuseRate` | **0 / 0** |
| B/C `citationValidityRate` | **1.0 / 1.0** |
| C 任务级门禁 | **PASS** |
| 默认开关恢复 | `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` |

**A/B 口径**：A/B 在 attempt 1 的真实模型任务窗口中完成；同一轮的 C Job 连续 3 次 attempt
均被 provider 400/503 打断，C 当时为 `NOT_EVALUATED`。随后用新的真实 Job 重跑 C，并复用
同一冻结 fixture、同一模型、同一任务 prompt 与判定器下的 A/B aggregate。attempt 1 的原始失败
证据完整保留在 `raw/staging/output-attempt1/`，不是 C 最终结果。

---

## 1. A/B/C 实测

### 1.1 冻结执行链

```text
真实 staging 项目（description 含 11 条 expectedAnswerFacts）
  → POST /agent/memory-jobs
  → AI Job dispatcher + Ed25519 job token
  → Python worker 真实模型提取
  → ai_job_result(PENDING_REVIEW)
  → 项目 owner 逐条 approve
  → agent_memory(source_type=AI_JOB_RESULT, verification_state=UNVERIFIED)
  → A/B/C 跨会话任务真实模型回答
  → w5-memory-c.answer-fact-match.v1 判定
```

模型为 staging 已发布的 AGENT 用途 `gemini-3.5-flash-lite`；preflight 返回
`usage.source=PROVIDER`。未用 stub、replay 或本地镜像冒充 C。

### 1.2 A/B/C 指标

| 指标 | A LEGACY | B GOVERNED_RECALL | C DREAMING |
| --- | ---: | ---: | ---: |
| `taskAccuracy`（门禁） | 0.3636（4/11） | 0.7273（8/11） | **0.9091（10/11）** |
| `recallAtK`（诊断） | 0.4545 | 1.0 | 1.0 |
| `precisionAtK`（诊断） | 0.0455 | 0.1833 | 0.3056 |
| `citationValidityRate`（门禁） | 0.70 | **1.0** | **1.0** |
| `conflictMisuseRate`（门禁） | 1.0 | **0.0** | **0.0** |
| `revocationLeaks`（门禁） | 33 | **0** | **0** |
| `crossProjectLeaks`（门禁） | 0 | **0** | **0** |
| 任务模型 P50 / P95 | 7624 / 25945 ms | 6971 / 23288 ms | 3754 / 19542 ms |
| tokens（in / out） | 3238 / 137 | 2369 / 214 | 3427 / 595 |

C 唯一失败任务为 `ma-002`：模型回答“迭代长度调整为两周。”，遗漏 expected fact 中的
“从三周”，冻结判定器 bigram 命中率 0.6364，按固定阈值判失败。没有事后放宽判定器。

### 1.3 C Job / 审批

| 项 | 值 |
| --- | --- |
| Job ID | `e65fbaa4-938b-44cf-99e1-0574370018f8` |
| Project ID | `8325433a-ae11-483e-a33b-059ededc0dc9` |
| attempt / leaseEpoch | 1 / 1 |
| snapshot hash | `sha256:9187ad36666e1c406facee34f0a605e0` |
| snapshot refs | `["fact:project"]` |
| 候选 | 11 |
| 通过 | 11 |
| 自动发布 | 0 |
| 审批后 `AI_JOB_RESULT` 记忆 | 11 |
| 审批映射 | `raw/staging/approval-map.json` |

候选 ID、发布记忆 ID 与 sourceRef 的逐条映射在 `approval-map.json`；原始 approve 响应在
`raw/staging/output-c2/raw/approvals.json`。

### 1.4 attempt 1 保留记录

| 项 | 值 |
| --- | --- |
| Job ID | `ed1b0739-a117-49cf-9978-c80e7453399c` |
| 结果 | `FAILED`（3 次 attempt） |
| 根因 | provider 连续返回 HTTP 400/503，worker 如实回报 `WORKER_FAILED_RETRYABLE` |
| 候选 | 0；C 在该轮为 `NOT_EVALUATED` |
| 原始证据 | `raw/staging/output-attempt1/` |

该轮失败不是 harness 判定，也没有被改写成 PASS；最终报告只采用第二轮真实 Job 的 C。
首轮启动时 `instabox exec` 的 60 秒包装器超时，但 docker harness 容器继续运行并产出完整
`output-attempt1`；`live-run.txt` 的包装器超时不是 C 判定。后续 C 重跑改用 background job，
真实退出码与测量文件完整保留。

---

## 2. sourceRef 快照治理

### 2.1 实现

- `ai_job_attempt` 首次读取时冻结 `snapshot_hash`、`snapshot_refs`、`snapshot_payload`；
  同一 attempt 的后续派发/worker 读取复用该快照。
- `ai_job_result` 持久化提交时的 `snapshot_hash` 和 `snapshot_refs`。
- 提交时每个候选 `sourceRef` 必须**逐字命中**该 attempt 快照的
  `facts[].ref` 或 `memory:<existingMemories[].id>`；仅前缀相同、格式相似不通过。
- 审批时复核 result/attempt 的 hash/refs、从 payload 重算 hash、项目边界与 sourceRef。
- 固定错误码：
  - `AGENT_MEMORY_CANDIDATE_SOURCE_REF_INVALID`
  - `AGENT_MEMORY_CANDIDATE_SNAPSHOT_CHANGED`
- 拒绝消息、审计和日志不含 sourceRef 原值、候选正文、token 或密钥。

### 2.2 现场绑定证据

`raw/staging/c-db-snapshot.txt` 记录：

- attempt 1 的 snapshot hash 为 `sha256:9187ad36666e1c406facee34f0a605e0`；
- 11 条 ACCEPTED result 的 `snapshot_hash` 与该 attempt 完全一致；
- 11 条 result 的 `snapshot_refs` 均为 `["fact:project"]`；
- 11/11 满足 `result.snapshot_refs ? candidate.sourceRef`；
- 11 条发布记忆全部绑定 ACCEPTED result；
- 11 条 approve 审计记录存在。

### 2.3 拒绝矩阵

固定矩阵：`raw/source-ref-rejection-matrix.json`。覆盖：

| 场景 | 期望 | 结果 |
| --- | --- | --- |
| 合法 `fact:` / `memory:` | 发布 | PASS |
| 非法前缀 | `AGENT_MEMORY_CANDIDATE_SOURCE_REF_INVALID` | PASS |
| 前缀匹配但 ref 不存在 | 同上 | PASS |
| 跨 Job ref | 提交拒绝，结果 0 行 | PASS |
| 跨项目 ref | 提交拒绝 | PASS |
| 提交后 attempt 快照被篡改 | 拒绝，结果 0 行 | PASS |
| 审批前 snapshot hash 被改 | `AGENT_MEMORY_CANDIDATE_SNAPSHOT_CHANGED` | PASS |

Live C 的项目快照没有既有记忆，因此真实候选全部使用 `fact:project`；`memory:` 路径由
V96/V97 Testcontainers 矩阵覆盖，不伪造 live 用例。

---

## 3. 测试项目删除 500

根因：V91 `ai_job.project_id REFERENCES project(id)` 没有 `ON DELETE CASCADE`，项目已有
Job 时 PostgreSQL 拒绝删除。

修复：V97 仅修改该外键为 `ON DELETE CASCADE`；权限仍由
`ProjectApplicationService` 的 owner 校验强制，删除审计以 `project_id=NULL` 保留。

证据：

- 真库回归测试覆盖 Job/attempt/result/memory/audit 引用：owner 删除成功；非 owner
  `PROJECT_OWNER_REQUIRED` 且数据与审计不变；
  `raw/offline/java-deletion-500.txt`；
- staging 两轮测试项目 cleanup 均返回 HTTP 204：
  `raw/staging/attempt1-cleanup.txt`、`raw/staging/c-deletion-verify.txt`；
- staging DB 复核：project/job/result/memory 残留均为 0；2 条 `PROJECT_DELETED` 审计保留且
  `project_id=NULL`；
- 外键删除规则：`confdeltype=c`（`raw/staging/fk-delete-rule.txt`）。

---

## 4. 默认开关与清理

| 时点 | app 开关 | worker | Flyway |
| --- | --- | --- | --- |
| 测量前 | `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`java/java/java` | `AI_WORKER_TOKEN_MODE=static` | 95 |
| 测量窗口 | `MEMORY_JOB_ENABLED=true`、`MEMORY_JOB_RECOVERY_ENABLED=true`、`INTERNAL_AI_TOKEN_MODE=dual`；Job 令牌 Ed25519 | `AI_WORKER_TOKEN_MODE=ed25519` | 97 |
| 测量后 | **`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`**、`java/java/java` | **`AI_WORKER_TOKEN_MODE=static`** | 97 |

证据：`before-env-pre-d.txt`、`measurement-env.txt`、`after-env.txt`、`restore.txt`。

清理：

- 临时 app 用户已删除（`temp_user=0`）；
- 临时 Ed25519 私钥/公钥、JWT、compose override、measurement meta 已删除；
- `/data/home/w5d` 临时目录已删除；
- `ai-collab-backend:w5c-before` 备份镜像已删除；
- `staging-box` / `staging-model` 锁已释放给 B。

证据：`after-db-checks.txt`、`cleanup.txt`。

---

## 5. 离线验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 合并后定向 Java（5 类） | **43 passed** | `raw/offline/final-head-java-targeted.txt` |
| Job 快照 + 候选审批 | 11 passed | `raw/offline/java-candidate-final.txt` |
| 删除 500 真库回归 | 2 passed | `raw/offline/java-deletion-500.txt` |
| 编译 / package | PASS | `raw/offline/final-head-package.txt` |
| Flyway | 97 migrations，V92→V97 | `raw/staging/measurement-env.txt` |

最终合并 `dev @ 99c47d5e` 后重新运行定向测试：**43 passed, 0 failed**，原始日志已归档为
`raw/offline/final-head-java-targeted.txt`。

---

## 6. 证据索引

| 证据 | 路径 |
| --- | --- |
| 最终 C 测量 | `raw/staging/output-c2/raw/measurement.json` |
| 最终 C Job 状态 | `raw/staging/output-c2/raw/job-status.json`、`job-status.jsonl` |
| 候选与审批 | `raw/staging/output-c2/raw/candidates-pending.json`、`memories-before-approval.json`、`approvals.json`、`approval-map.json` |
| C 任务原始调用 | `raw/staging/output-c2/raw/task-calls.jsonl` |
| C 第一次失败 | `raw/staging/output-attempt1/` |
| DB 快照/绑定 | `raw/staging/c-db-snapshot.txt` |
| 删除 500 | `raw/staging/c-deletion-verify.txt`、`raw/staging/fk-delete-rule.txt`、`raw/offline/java-deletion-500.txt` |
| 来源拒绝矩阵 | `raw/source-ref-rejection-matrix.json` |
| 默认开关 | `raw/staging/before-env-pre-d.txt`、`measurement-env.txt`、`after-env.txt` |
| 运行元数据 | `run-meta.json` |

---

## 7. 限定与未覆盖项

- 每 arm 11 个任务，`LOW_CONFIDENCE`；不得外推到生产或其他 provider。
- A/B 使用冻结 fixture 的确定性选择器；真实模型执行是 live，但 A 不是当前 shipped
  `context(projectId)` 的线上行为。
- C 项目快照没有既有记忆，因此 live sourceRef 只覆盖 `fact:project`；`memory:` 回查由
  离线 Testcontainers 矩阵覆盖。
- attempt 1 的 provider 400/503 被保留；最终 C 使用 new Job，未把失败样本当成功。
- `precision` / `recall` 仅诊断，不设门禁；C 唯一失败任务 `ma-002` 是固定判定器对语义省略
  的保守判失败，未事后调整阈值。
- 未做真实用户 canary，未改变生产默认开关，`MEMORY_JOB_ENABLED=false` 仍是默认。
