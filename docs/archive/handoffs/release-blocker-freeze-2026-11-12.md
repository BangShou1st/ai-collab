# Release Blocker Closure 冻结件（2026-11-12）

> 状态：**FROZEN / 待中心 review 后派发**
> 基线：`dev @ 7014aba0`（以实际 HEAD 为准）
> 范围：W3 / W4 / W5 release blocker 收口，以及 W6 实现 readiness；**不含 W6 实现**
> 证据等级：`IMPLEMENTED < TESTED(offline) < TESTED(STUB) < LIVE_VERIFIED`；`NOT_VERIFIED` 不得写成 `PASS`

---

## 0. 中心冻结结论

1. 生产默认继续冻结为：`RERANK_MODE=java`、`DECIDE_MODE=java`、
   `PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、
   `INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。
2. 五条泳道只允许做本文件列出的范围；任何扩大范围、改门禁、删场景、缩候选、
   把 5xx/超时当成功的动作，中心直接拒绝。
3. 所有子 agent 提交留在各自分支，**不得直接 push `dev`**；中心逐笔 review 原始证据后
   cherry-pick 到 `dev`。
4. 迁移版本预先分配，禁止抢占：
   - Lane A：`V92`–`V95`；
   - Lane D：`V96`–`V97`；
   - Lane B / C / E：默认不新增迁移；确需迁移必须先回报中心重新分配。
5. 最终只保留 `dev` / `main` 与主 worktree；临时分支、worktree、lock、日志、
   JTL、模型权重、密钥全部清理。

---

## 1. 资源锁与开工顺序

### 1.1 资源清单

| 资源 | 含义 | 占用方式 |
| --- | --- | --- |
| `local-cpu` | 本机重构建/全量测试/浏览器验收 | `target/release-locks/local-cpu/owner.txt` |
| `local-gpu` | RTX 4060 + llama.cpp `/rerank` | `target/release-locks/local-gpu/owner.txt` |
| `staging-box` | instabox `box-aae54f` | `target/release-locks/staging-box/owner.txt` |
| `staging-model` | staging 上已发布 provider/model/purpose | `target/release-locks/staging-model/owner.txt` |

锁目录在 `target/` 下，已被 `.gitignore` 忽略；每条泳道开工前写 owner/时间/用途，
收工删除目录。**同一资源同一时刻只允许一条泳道。**

### 1.2 冻结开工顺序

```text
Lane A（配置平台 + Adapter）
  → Lane B（本地 Reranker 实测；依赖 A 可部署）
  → Lane C（W4 最后一轮 live；独占 staging）
  → Lane D（W5 C DREAMING + 来源治理；独占 staging）
  → Lane E（W6 readiness；只读）
```

- A 先占 `local-cpu`；C/D 可先写代码与测试，但重构建/全量测试必须等中心释放
  `local-cpu` 后再按 A → C → D 顺序占用。
- B 占 `local-gpu`；staging 段必须同时占 `staging-box` + `staging-model`。
- B 的 staging 段结束后释放 staging，C 再占；C 结束释放，D 再占。
- E 不占资源锁，只读仓库与 A/B/C/D 的原始证据。
- 冲突时排队，不得并行抢占；等待期间可以 review 代码，不得启动验收。

### 1.3 分支 / worktree / 证据目录

| 泳道 | 分支 | worktree | 主证据目录 |
| --- | --- | --- | --- |
| A | `w3/reranker-config` | `E:\project\ai-collab-w3-reranker-config` | `docs/acceptance-evidence/2026-11-12/w3-reranker-config/` |
| B | `w3/reranker-local` | `E:\project\ai-collab-w3-reranker-local` | `docs/acceptance-evidence/2026-11-12/w3-reranker-local/` |
| C | `w4/decide-final` | `E:\project\ai-collab-w4-decide-final` | `docs/acceptance-evidence/2026-11-12/w4-decide-final/` |
| D | `w5/memory-c` | `E:\project\ai-collab-w5-memory-c` | `docs/acceptance-evidence/2026-11-12/w5-memory-c/` |
| E | `w6/readiness` | `E:\project\ai-collab-w6-readiness` | `docs/archive/review/w6-implementation-readiness-2026-11-12.md` |

每个证据目录必须包含：

- `report.md`：结论、门禁表、原始证据索引、限定与未验证项；
- `raw/`：原始 JSON / JSONL / 日志 / 命令输出（不得只放截图或总结）；
- `run-meta.json`：代码 commit、镜像 digest、模型/provider、时间、资源锁持有记录；
- 如有失败：`FAIL` 与根因归属必须写在 `report.md` 顶部，不得藏在附注。

---

## 2. Lane A：Reranker 配置平台 + 本地 Adapter

### 2.1 目标

按 `docs/design/model-provider-configuration-2026-11-12.md` 实现 **RERANK capability**：

- 管理员可配置 Provider / Model Profile / Capability Profile / Assignment；
- 测试、发布、不可变版本、回滚、审计；
- 本地 llama.cpp `/rerank` 的 `RerankAdapter`；
- 默认 `enabled=false`；`RERANK_MODE=java` 不变；
- Java 代码不出现模型名、模型文件路径、llama.cpp 端口或 endpoint 常量；
- 如需迁移只用 `V92`–`V95`。

### 2.2 冻结 capability schema

#### Provider（管理端共享 Provider）

在现有管理端共享 Provider 基础上增加/映射以下逻辑字段，不改用户个人 Provider 行为：

| 字段 | 冻结要求 |
| --- | --- |
| `name` | 管理员可见名称，唯一 |
| `provider_type` | Rerank 本地服务允许 `LOCAL_CUSTOM`；其它能力保持现有类型 |
| `protocol` | Rerank 固定 `LLAMACPP_RERANK`；一个 Provider 一个主协议 |
| `base_url` | 服务根地址；只允许内网/回环地址用于本地 Provider |
| `api_path` | Rerank 默认 `/rerank`，可管理员配置 |
| `auth_type` | Rerank 本地服务允许 `NONE`；不得把密钥返回前端 |
| `network_zone` | `local` / `internal` / `public`；本地 Provider 只能是 `local` |
| `health_path` | 默认 `/health` |
| `enabled` | Provider 级开关 |

#### Model Profile

复用 `ai_model_profile` 作为模型身份档案：

- `model_key` / `display_name` / `version` / `source` / `sha256` / `license`；
- Rerank 的 `context_window_tokens` 表示模型最大输入长度（Qwen3-Reranker-0.6B 为 8192）；
- `max_output_tokens` 对 Rerank 为 `NULL`；`supports_usage` 按真实 provider 能力填写；
- **不得**把模型文件路径写进 Model Profile 的 Java 对象或前端响应。

#### Capability Profile（新表）

`ai_model_capability_profile` 至少包含：

```text
id UUID PK
model_profile_id UUID NOT NULL REFERENCES ai_model_profile(id)
provider_id UUID NOT NULL            -- 管理端共享 Provider
capability VARCHAR(16) NOT NULL      -- CHAT / EMBEDDING / RERANK
protocol VARCHAR(32) NOT NULL
settings_json JSONB NOT NULL
status VARCHAR(16) NOT NULL          -- DRAFT / TESTED / PUBLISHED / DISABLED
version INTEGER NOT NULL
source VARCHAR(24) NOT NULL
verified_at TIMESTAMPTZ NULL
verified_by UUID NULL REFERENCES app_user(id)
created_by / updated_by / created_at / updated_at
```

约束：

- `unique(model_profile_id, capability, version)`；
- 同一 `(model_profile_id, capability)` 最多一个 `PUBLISHED`；
- `status=TESTED/PUBLISHED` 必须有 `verified_at`；
- `PUBLISHED` 行不可直接改 `settings_json`；修改必须新建 DRAFT 版本；
- `settings_json` 必须由 Java 侧 JSON Schema 严格校验，`additionalProperties=false`。

#### RERANK settings JSON Schema

```json
{
  "protocol": "LLAMACPP_RERANK",
  "timeoutMs": 3000,
  "maxCandidates": 50,
  "batchSize": 8,
  "maxLength": 2048,
  "scoreType": "sigmoid",
  "topK": 8,
  "healthPath": "/health",
  "fallback": "java-heuristic"
}
```

冻结规则：

- `timeoutMs`：100–30000；默认 3000；
- `maxCandidates`：1–50；默认 50；
- `batchSize`：1–`maxCandidates`；
- `maxLength`：64–8192；
- `scoreType`：`sigmoid` / `logit` / `normalized`；llama.cpp native `relevance_score` 使用
  `sigmoid` 语义，adapter 不得二次改变排序；
- `topK`：1–50；
- `fallback`：只允许 `java-heuristic`；
- 出现 `maxOutputTokens`、`streaming`、`toolCalling`、`dimension` 等其它 capability 字段
  必须被 Schema 拒绝。

#### Assignment

- `scope`：`GLOBAL` / `PROJECT`；本波不新增 `TENANT/USER`；
- `capability`：`RERANK`；
- `purpose`：`KNOWLEDGE_RERANK`（不得复用 `KNOWLEDGE_CHAT`）；
- 只允许绑定 `PUBLISHED` profile；
- `enabled` 默认 `false`；没有 assignment 时运行时使用现有 Java 启发式；
- 解析顺序：项目绑定 → 全局绑定 → 默认 Java 启发式；
- 解析失败、profile 停用、provider 不可达、超时、非法响应 → **回退 Java 启发式**并记录
  `rerank.fallback{reason=...}`，不得让主请求失败。

### 2.3 冻结协议 `LLAMACPP_RERANK`

请求（Java → 本地服务）：

```http
POST {base_url}{api_path}
Content-Type: application/json

{"query":"<query>","documents":["<title>\n<text>","..."],"top_n":50}
```

- `documents` 按候选输入顺序；`top_n` 可省略，adapter 不依赖服务端截断；
- 不在请求体发送模型文件路径、密钥、项目 ID、用户 ID。

响应（本地服务 → Java）：

```json
{
  "results": [
    {"index": 0, "relevance_score": 0.98},
    {"index": 1, "relevance_score": 0.12}
  ],
  "model": "optional",
  "usage": {"prompt_tokens": 211}
}
```

adapter 冻结规则：

- 必须为每个输入候选返回**恰好一个**有限分数；`index` 范围 0..n-1，不得重复；
- `results` 缺失、数量不符、索引越界/重复、分数非有限或非数值 → 整个响应判非法；
- `relevance_score` 按 `scoreType` 映射：`sigmoid` 直接使用，`logit` 做 sigmoid，
  `normalized` 只用于排序；不得在 adapter 内跨候选重排分数分布；
- 排序沿用现有 Java 稳定比较器：rerank score 降序 → rrf score 降序 → id 升序；
- 记录 `modelLatencyMs`（HTTP 往返）、`totalMs`、`overheadMs=max(0,total-model)`、
  `candidateCount`、`profileId`、`providerId`、`outcome`、`fallbackReason`；
- 日志不得包含候选正文、query 全文、密钥或模型路径。

### 2.4 管理端生命周期

必须提供：

1. **测试**：一条 query + 两条 document（一条明显相关、一条明显无关），经过真实
   `RerankAdapter`；断言两个有限分数、相关分数严格大于无关分数、记录延迟；
   测试失败不得进入 `TESTED`，更不得发布。
2. **发布**：仅 `TESTED` 可发布；发布会生成不可变版本并写审计。
3. **回滚**：切回上一 `PUBLISHED` 版本；写审计；已发布版本不原地修改。
4. **审计**：操作者、时间、profile、version、动作（create/test/publish/rollback/disable）、
   原因；不记录密钥、正文、endpoint 私网地址给普通用户。
5. **权限**：只有系统管理员可管理全局 Provider/Profile；项目绑定必须校验项目管理员权限。

### 2.5 交付与验收

- 后端单测 + 契约测试 + Testcontainers 集成测试（如涉及迁移）；
- 前端管理入口或管理端 API 证据；至少覆盖创建/测试/发布/回滚/禁用；
- 证据：`docs/acceptance-evidence/2026-11-12/w3-reranker-config/`；
- 报告：`docs/archive/report/w3-reranker-config-2026-11-12.md`；
- 证明 `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、
  `AI_WORKER_TOKEN_MODE=static` 未变；
- 证明 Java 源码无 `D:\models`、`Qwen3-Reranker`、`18081`、`llama-server` 等硬编码。

---

## 3. Lane B：W3 专用 Reranker 实测

### 3.1 目标

使用 Lane A 的管理员配置与 Adapter，把本地 Qwen3-Reranker-0.6B + llama.cpp `/rerank`
接入 staging 的 **capability 测试路径**，执行 8 / 20 / 50 候选门禁；不改变
`RERANK_MODE=java`，不把 research harness 接进生产用户路径。

### 3.2 冻结测量口径

- 冻结集：`ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`
  （15 cases）；20/50 用确定性零标签补齐候选只测延迟，NDCG@8 只用原 8 候选；
- 每个档位先 warm-up，再跑 **15 次门禁**；通过后才补 **60 次观测**；
- 1 次评测请求 = 1 次 Reranker 调用；不得重试成功样本、不得并发掩盖尾延迟；
- 门禁样本不足 200 一律 `LOW_CONFIDENCE`，不得外推生产。

### 3.3 冻结门禁

| 指标 | 门禁 |
| --- | --- |
| 3s 端到端失败率（含 timeout/5xx/非法响应） | ≤ 1% |
| `model_latency_known_ratio` | ≥ 99% |
| `overhead` P95（不含模型） | ≤ 800ms |
| `overhead` P99 | ≤ 1500ms（工程护栏） |
| 1 次请求 = 1 次 Reranker 调用 | 必须成立 |
| model / e2e 延迟 | 分列记录，不合并 |

### 3.4 停止规则

- **8 档失败**：停止 20/50，写 `FAIL`；不得缩门禁、改阈值、删场景；
- 8/20 通过、50 失败：记录 `FAIL`；是否把产品候选上限改为 20 必须由中心/用户决策；
- 出现 429/5xx：单独计基础设施失败，不从分母剔除；按门禁算失败；
- 模型/服务非法响应：计失败，不得把部分分数当成功。

### 3.5 交付

- `docs/acceptance-evidence/2026-11-12/w3-reranker-local/report.md`；
- `raw/*.json`（每档 aggregate、逐请求日志、配置 ID、模型 SHA、llama.cpp 版本）；
- 标签：`LIVE_VERIFIED (staging, local reranker, admin capability test)` 或 `FAIL`；
- 明确写：生产用户路径仍为 `NOT_VERIFIED`（因为 `RERANK_MODE=java` 未变），
  admin capability test 通过不等于生产可灰度。

---

## 4. Lane C：W4 decide

### 4.1 目标

在第一轮 Prompt `2026-11-12.2` + live `agreement=0.80` 的基础上，做**最后一轮**修复与
32 例 live 复跑。最多两轮；第一轮已用，剩余 **1 轮**。

### 4.2 冻结动作顺序

1. 先复核 d-012 / d-020：是标签错还是模型错；
2. **标签错**：提出新冻结集版本 `decide_baseline.v2.jsonl` + 影响分析，
   保留 v1 原标签与 v1 原始结果，不得静默覆盖；新版本必须经中心 review 才能作为门禁；
3. **标签对**：改 prompt/policy，最多一轮；不得针对具体 case 硬编码；
4. 32 例 live 复跑；503/504 单独计基础设施失败，但**不从分母剔除**；
5. 不实现 `DECIDE_MODE=python`，不改变 `DECIDE_MODE=java`。

### 4.3 冻结门禁

| 指标 | 门禁 |
| --- | --- |
| golden `decisionAgreement` | ≥ 0.98 |
| golden `jsonValidityRate` | ≥ 0.995 |
| adversarial `invalidToolCalls` | 不增（基线 0） |
| 429 / 503 / 504 | 单列记录，不从分母剔除 |

### 4.4 交付

- 代码 + 单测/契约测试；
- 32 例 live 原始 `aggregate`、逐 case JSONL、运行元数据；
- 报告：`docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md`；
- 若仍 <0.98：写 `FAIL` + 根因归属，不得改标签凑绿。

---

## 5. Lane D：W5 Memory C + 来源治理

### 5.1 前提

Job 创建/派发/租约/结果/审批 E2E 已 PASS，**不重做**。本轮只做：

- C DREAMING 真实对照；
- `sourceRef` 逐条回查 Job 快照；
- 测试项目删除 500 修复；
- A LEGACY / B GOVERNED_RECALL / C DREAMING 任务级对照。

### 5.2 C DREAMING 冻结定义

C 必须是真实链路，不得用 stub 或本地镜像冒充：

1. 在 staging 用真实项目快照创建 Memory Job；
2. 启用 Ed25519 job token + `MEMORY_JOB_ENABLED=true` 仅限测量窗口；
3. worker 真实调用模型并回报候选；
4. 项目管理员显式审批候选（人工动作，不得自动发布）；
5. 用审批后的记忆集重跑同一批跨会话任务；
6. 记录 Job/候选/审批/记忆 ID、模型、Prompt 版本、token、延迟；
7. 测量结束恢复 `MEMORY_JOB_ENABLED=false`、`static`/`static`。

若任一步无法完成，C 写 `NOT_EVALUATED` 或 `FAIL`，不得写 PASS。

### 5.3 冻结任务级门禁

`precision` / `recall` 只作诊断，不作门禁。三组对照按任务级判定：

| 项 | 门禁 |
| --- | --- |
| B / C `revocationLeaks` | **0** |
| B / C `crossProjectLeaks` | **0** |
| B / C `conflictMisuseRate` | **0** |
| B / C `citationValidityRate` | **1.0** |
| C 候选发布方式 | 100% 经人工审批；自动发布数 = 0 |
| C 任务级结果 | 不低于 B；且不低于 A 的同一任务基线 |
| C 证据 | 真实 Job + 真实模型 + 真实审批，不得 replay/stub |

任务级结果的定义：对每个冻结 session，用该 arm 返回的记忆完成既定任务/
回答既定事实，按冻结 `expectedAnswerFacts` 判定；判定器与 fixture 必须在报告中列明并冻结。

### 5.4 sourceRef 来源治理（硬要求）

- 候选的 `sourceRef` 必须逐条回查**同一 Job / attempt 的快照**；
- 只允许命中该快照的 `facts[].ref` 或 `existingMemories[]`（`memory:<id>`）；
- 前缀匹配、格式匹配、跨 Job/跨项目引用一律拒绝；
- 若当前 schema 未保存快照引用集合，允许新增 `V96`–`V97` 持久化
  `ai_job_result` 的 `snapshot_refs` / `snapshot_hash`，并补迁移与测试；
- 提交时和审批时都要校验；不一致的候选不得进入 `PENDING_REVIEW` 或不得被批准；
- 审计记录被拒绝的 ref 固定错误码，不记录正文/密钥。

### 5.5 测试项目删除 500

- 复现并修复项目删除 500；
- 补回归测试，覆盖有 Job/候选/记忆/审计引用的项目；
- 删除仍须遵守权限；不得用级联删除绕过审计/所有权。

### 5.6 交付

- 代码 + 单测/集成测试；
- A/B/C 原始 JSON + 逐 case JSONL + Job/审批证据；
- `docs/acceptance-evidence/2026-11-12/w5-memory-c/report.md`；
- 若 C 仍无法验证：`NOT_EVALUATED` / `FAIL` + 根因，不得写 PASS。

---

## 6. Lane E：W6 readiness

### 6.1 只做

- 核对 W3 / W5 前置是否真正清零（Ed25519 已 PASS，不重复验收）；
- 输出 MCP Gateway / Host 的实现任务拆分、风险、待决策；
- 明确 W6 仍不实现，除非所有前置 PASS；
- 产出 `docs/archive/review/w6-implementation-readiness-2026-11-12.md`。

### 6.2 W6 冻结前置

| 前置 | 清零条件 |
| --- | --- |
| W3 | Lane B 8/20/50 门禁 PASS，且报告明确生产用户路径仍待 canary |
| Ed25519 | 已 PASS，引用既有证据，不重做 |
| W5 | Lane D C DREAMING 任务级门禁 PASS + sourceRef 治理 PASS + 删除 500 修复 |
| W4 | 作为相邻阻塞单列；不作为 W6 前置，但不得写成 PASS |

**任一项未 PASS → W6 readiness 必须是 `NOT_READY / BLOCKED`，不得输出实现开工建议。**

---

## 7. 中心验收协议

1. 不看子 agent 总结，直接读 `raw/` 原始 JSON / JSONL / 日志 / aggregate；
2. 每个 PASS 必须有原始证据；每个 FAIL 必须有根因归属；
3. 逐泳道 cherry-pick 到 `dev`，每笔提交独立；合并后删除临时分支/worktree；
4. 在最终集成 HEAD 跑 Java 全量 `mvnw verify`；Python 全量
   `uv run --frozen pytest`；必要时跑前端 lint/typecheck/test/build；
5. 核对六个默认开关、`main` 未动、无 force push、无历史迁移修改；
6. 核对仓库根/源码目录无 `*.log`、JTL、模型、密钥、临时脚本；
7. 最终只留 `dev` / `main` 与主 worktree。

---

## 8. 冻结门禁总表

| 波次 | 门禁 | 判定 |
| --- | --- | --- |
| W3 8 候选 | 失败率 ≤1%、known ≥99%、overhead P95 ≤800ms、1:1 调用 | PASS / FAIL |
| W3 20 候选 | 同上 | PASS / FAIL / NOT_RUN |
| W3 50 候选 | 同上 | PASS / FAIL / NOT_RUN |
| W3 60 次观测 | 仅观测，不改变门禁 | 记录 |
| W4 | agreement ≥0.98、JSON ≥0.995、invalidToolCalls 不增 | PASS / FAIL |
| W5 A/B/C | 安全项全 0、C 人工审批 100%、C 任务级不低于 B/A | PASS / FAIL / NOT_EVALUATED |
| W5 来源治理 | sourceRef 逐条回查 Job 快照，拒绝率可审计 | PASS / FAIL |
| W6 readiness | W3 + Ed25519 + W5 全 PASS | READY / NOT_READY |

---

## 9. 停止条件（任一触发即停）

1. W3 8 候选失败：停止 50，写 FAIL；不得缩门禁；
2. W4 最后一轮仍 <0.98：停止，写 FAIL；不得改标签凑绿；
3. W5 C 无法验证：写 NOT_EVALUATED / FAIL；不得用 stub 冒充；
4. Reranker 配置需要改默认开关：停止，交用户决策；
5. 资源锁冲突：排队，不并行抢；
6. 需要修改历史迁移、force push、动 `main`：停止，交中心/用户。

---

## 10. 中心 review 记录

| 项 | 结果 |
| --- | --- |
| 基线 HEAD | `7014aba0` |
| 默认开关核对 | `application.yml` / compose 默认值均为冻结值 |
| 迁移现状 | V1–V91；Lane A 从 V92 起，Lane D 从 V96 起 |
| 既有证据 | W3/W4/W5 报告与 raw 已读；W3 3s FAIL、W4 0.80、W5 C NOT_EVALUATED |
| 资源 | `box-aae54f` running/healthy；RTX 4060 可用；Docker 29.8.0 |
| 结论 | **FROZEN，可派发** |
