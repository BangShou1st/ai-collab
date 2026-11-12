# W5 Memory C + 来源治理：Lane D 验收报告（2026-11-12）

> 分支：`w5/memory-c`；基线：`d6fa5133`；代码提交：`4fd32479`
> 当前结论：**C 真实对照 `NOT_EVALUATED`（等待 staging 串行锁）**；离线来源治理与删除 500 回归已通过。
> 证据等级：`TESTED (offline/Testcontainers)`；C 只有真实 Job + 真实模型 + 人工审批完成后才可写 `LIVE_VERIFIED`。

---

## 0. 结论先行

**C 尚未执行，本报告不得写 PASS。** B 的 W3 formal 重跑完成并释放 `staging-box`/`staging-model` 后，Lane D
才部署 `4fd32479`，执行真实 `MEMORY_EXTRACTION` Job、Gemini AGENT 任务和逐条人工 approve；结束后恢复
`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。

---

## 1. 已实现

### 1.1 sourceRef 快照治理

缺陷：`AgentMemoryCandidateService.validate` 只检查 `fact:` / `memory:` 前缀，不能证明引用属于同一
Job/attempt 快照。

修复：

- `ai_job_attempt` 首次读取时冻结 `snapshot_hash`、`snapshot_refs`、`snapshot_payload`；
  后续派发/worker 读取都复用同一快照。
- `ai_job_result` 持久化提交时的 `snapshot_hash`、`snapshot_refs`。
- 提交结果时要求每个候选 `sourceRef` **逐字命中**该 attempt 快照的
  `facts[].ref` 或 `memory:<existingMemories[].id>`；前缀相同但不存在也拒绝。
- 审批时重新校验 result 与 attempt 的 hash/refs、payload 重算 hash、项目边界和 sourceRef；
  跨 Job、跨项目、格式匹配但不存在、快照改动均 fail closed。
- 固定业务错误码：
  - `AGENT_MEMORY_CANDIDATE_SOURCE_REF_INVALID`
  - `AGENT_MEMORY_CANDIDATE_SNAPSHOT_CHANGED`
- 错误消息、审计和日志不记录 sourceRef 原值、候选正文、token 或密钥。

拒绝矩阵：`raw/source-ref-rejection-matrix.json`。

### 1.2 迁移

| 迁移 | 内容 |
| --- | --- |
| `V96__ai_job_snapshot_binding.sql` | attempt/result 快照绑定列与 CHECK |
| `V97__ai_job_project_delete_cascade.sql` | 修复 `ai_job.project_id` 缺失 `ON DELETE CASCADE` |

未改动历史迁移。V97 仍由 `ProjectApplicationService` 先做 owner 权限校验并写删除审计，级联只回收
项目所属 Job/attempt/result，不绕过权限或审计。

### 1.3 测试项目删除 500

根因：`ai_job.project_id REFERENCES project(id)` 没有级联策略，项目已有 Job 时数据库拒绝删除。

回归覆盖：项目 owner 删除含 Job、attempt、候选、已发布记忆、审计引用的项目成功；非 owner 被
`PROJECT_OWNER_REQUIRED` 拒绝，业务行与删除审计均保持不变。

证据：`raw/offline/java-deletion-500.txt`、
`raw/offline/TEST-com.shitulelv.aicollab.project.application.service.ProjectDeletionPostgresIntegrationTest.xml`。

---

## 2. 离线验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 编译 / test-compile | PASS | `raw/offline/java-package.txt` |
| `AiJobServiceTest` + `InternalAiJobControllerTest` | 26 passed | `raw/offline/java-unit.txt` |
| Job 快照冻结 + 候选来源 + 审批矩阵 | 14 passed | `raw/offline/java-snapshot-and-candidate.txt` |
| 候选审批最终单独复跑 | 11 passed | `raw/offline/java-candidate-final.txt` |
| 项目删除 500 回归单独复跑 | 2 passed | `raw/offline/java-deletion-500.txt` |

---

## 3. C 真实对照冻结口径

### 3.1 执行链

```text
真实 staging 项目（description 含 11 条 expectedAnswerFacts）
  → POST /agent/memory-jobs
  → AI Job dispatcher + Ed25519 job token
  → Python worker 真实模型提取
  → ai_job_result(PENDING_REVIEW)
  → 项目 owner 逐条 approve
  → approved agent_memory(source_type=AI_JOB_RESULT)
  → A/B/C 跨会话任务真实模型回答
  → 冻结判定器
```

禁止 stub/replay/本地镜像冒充 C。若 Job、模型、审批任一环节未完成，C 写 `NOT_EVALUATED` 或 `FAIL`。

### 3.2 A/B/C

| arm | 记忆来源 | 任务执行 |
| --- | --- | --- |
| A LEGACY | 冻结 fixture + pre-governance selector | 真实模型 |
| B GOVERNED_RECALL | 冻结 fixture + governed selector | 真实模型 |
| C DREAMING | 审批后的真实 Job 记忆 + 冻结 governed selector + revocation/conflict controls | 真实模型 |

### 3.3 任务级判定器

- 每个 fixture session 用对应 arm 返回的记忆回答 `expectedAnswerFacts`；
- 判定器固定为 `w5-memory-c.answer-fact-match.v1`：
  - NFKC + casefold + 去标点/空白后，expected fact 是 answer substring；或
  - expected fact 的汉字 bigram 在 answer 中命中率 ≥ 0.8；
- A/B/C 使用同一 prompt、同一个真实模型、同一个判定器；
- `precision` / `recall` 只作诊断，不设门禁。

### 3.4 门禁

| 检查 | 门禁 |
| --- | --- |
| B/C `revocationLeaks` | `0` |
| B/C `crossProjectLeaks` | `0` |
| B/C `conflictMisuseRate` | `0` |
| B/C `citationValidityRate` | `1.0` |
| C 候选人工审批率 | `1.0` |
| C 自动发布数 | `0` |
| C 任务准确率 | `>= B` 且 `>= A` |

### 3.5 可复跑资产

- `harness/live_memory_eval.py`：真实 Job/审批/任务测量；
- `harness/prepare_staging.sh`：部署镜像、生成临时 Ed25519 密钥、JWT、compose override；
- `harness/approve_pending.py`：显式逐条审批；
- `harness/run_live.sh`：在 worker 容器执行测量；
- `harness/cleanup_projects.py`：通过 public API 删除测试项目；
- `harness/restore_staging.sh`：恢复默认开关、删除临时用户/密钥。

---

## 4. staging 证据索引（待执行后补全）

- `raw/staging/before-env.txt`
- `raw/staging/after-env.txt`（待生成）
- `raw/staging/measurement/`（待生成）
- `raw/staging/deletion/`（待生成）

---

## 5. 限定

- 样本 11 session/arm，`LOW_CONFIDENCE`；
- A/B 选择器是冻结 fixture 的确定性镜像，真实模型执行是 live；
- C 的候选由真实模型提取，数量与质量由该次运行决定，不补造候选；
- 未改变 `MEMORY_JOB_ENABLED` 默认值；仅测量窗口临时开启，结束后恢复。
