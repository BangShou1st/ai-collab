# memory-arms 三组跨会话记忆对照（D7-B 骨架）

`arms_cases.v1.json` 是 `ai-eval memory-arms` 的冻结 fixture。它用于在**真实任务执行之前**
把 Java 的记忆选择语义在本地做确定性镜像，先暴露口径差异；它**不是**真实任务执行、不是
模型质量结论，也不是生产延迟/成本测量。

## 1. 诚实声明（必须与数字一起读）

- **A/B 是本地镜像**：不调用模型、不写数据库、不执行任务；只复现两种记忆选择语义。
- **A_LEGACY 是治理前语义的本地重建**：它镜像 3 参兼容重载
  `AgentMemoryRepository.list(projectId, true, 10)`（`excludeRecallUnsafe=false`）。
  当前 shipped `AgentMemoryService.context(projectId)` 实际调用 4 参
  `list(projectId, true, 10, true)`（带 recall-safe），**不是** A_LEGACY 的行为；
  仓库没有生产开关会选择 A_LEGACY，读者不得把 A 的撤权泄露当作线上 LEGACY 行为。
- **B_GOVERNED_RECALL 是当前语义的镜像**：active + recall-safe
  （`VERIFIED`/`UNVERIFIED` 且 `supersededBy` 为空）+ 请求关键词相关性，取前 10。
- **C_DREAMING 未实现**：仓库没有 Job dispatcher / 候选发布实现；整组固定
  `NOT_VERIFIED`，不得用 stub 冒充。
- **三组对照门禁 `NOT_EVALUATED`**：C 没有实现，不具备判定条件；A/B 只给诊断数字，
  **不得写成 PASS/FAIL，也不得外推生产**。
- `taskAccuracy` / `dreamingCost` / `humanApprovalRate` 没有真实任务执行 / Dreaming 实现 /
  审批流，固定 `NOT_VERIFIED`。
- `tokens` 是 `ceil((title+content)字符数/4)` 的粗略估算（`source=ESTIMATE`），
  不是 provider usage。
- `latency` 只有显式 `--measure-latency` 才测，且只测本地检索函数
  （`scope=local-retrieval-only-non-production`）；默认不测以保证报告逐字节稳定。

## 2. fixture schema

顶层只允许 5 个键：

| 键 | 含义 |
| --- | --- |
| `schemaVersion` | 固定 `1` |
| `sessions` | 跨会话任务请求：`id/projectId/userId/request/priorSessionIds` |
| `projectSnapshots` | 项目/成员/记忆快照：`projectId/members[]/memoryIds[]`；`memoryIds` 必须与该项目全部记忆一致 |
| `memories` | 共享记忆池：`id/projectId/type/title/content/status/verificationState/supersededBy/updatedAt` |
| `cases` | 8–12 条：`id/sessionId/projectId/expectedRecallIds/expectedAnswerFacts/conflicts/revocations/maxK` |

`memories` 覆盖：active、superseded（`supersededBy` 非空）、disabled（`status=DISABLED`）、
other-project（`p-beta`）、revoked（`verificationState=SOURCE_REVOKED`），以及
`STALE` 与无关记忆。`revocations` 的 `reason` 必须与记忆实际状态一致：
`DISABLED` / `SUPERSEDED` / `STALE` / `CONFLICTED` / `SOURCE_REVOKED`。

## 3. 覆盖矩阵（11 条 case）

| 类别 | 覆盖 |
| --- | --- |
| 跨会话召回 | `ma-001` 的 session `sess-002` 引用 `sess-001` 的周报决定 |
| 同义冲突 | `ma-002`：`mem-a02`（新口径）vs `mem-a13`（STALE 旧口径），preferred=`mem-a02` |
| 撤权 | `mem-a13` STALE、`mem-a14` SUPERSEDED、`mem-a15` DISABLED、`mem-a16` SOURCE_REVOKED |
| 跨项目泄露诱饵 | `mem-b01` / `mem-b02` 属于 `p-beta`，不得进入 `p-alpha` 召回 |
| 无关记忆干扰 | `mem-a10` / `mem-a18` 等偏好与目标请求无关，A 按时间/偏好基础分仍可能返回 |
| 前 10 截断 | `p-alpha` 18 条记忆，A 的 updatedAt 前 10 覆盖不到较老的 `mem-a01`–`mem-a06` |

## 4. 实测（本 fixture，默认无延迟采样）

| 指标 | A_LEGACY | B_GOVERNED_RECALL |
| --- | ---: | ---: |
| `recallAtK` | 0.454545（5/11 宏平均） | **1.0** |
| `citationValidityRate` | 0.7（70/100） | **1.0** |
| `conflictMisuseRate` | 1.0（1/1） | **0.0** |
| `revocationLeaks` | **33**（每条 case 返回 a13/a14/a16） | **0** |
| `crossProjectLeaks` | 0 | 0 |
| `tokens`（ESTIMATE） | total 814，mean 74.0 | total 422，mean 38.364 |
| `taskAccuracy` / `dreamingCost` / `humanApprovalRate` | NOT_VERIFIED | NOT_VERIFIED |
| `latency`（默认） | NOT_VERIFIED | NOT_VERIFIED |
| C_DREAMING | NOT_VERIFIED（未实现） | — |

门禁：`{"status":"NOT_EVALUATED","reason":"... C 未实现 ..."}`。

## 5. 指纹与用法

- fixture canonical 指纹：`9e19ff1ef5be87c58ae934f0102452935fc35b155d33e633318204d22435ddac`
  （11410 字节）。
- 默认报告（同一次提交内固定）：`sha256=9fa04d544bb5c23e07a8a8cd17c65cea2bd76762a4d0014090ddbde453015bf0`；
  同一输入两次运行必须逐字节一致。

```bash
cd ai-collab-ai-worker
uv run ai-eval memory-arms tests/fixtures/memory_arms/arms_cases.v1.json --json
uv run ai-eval memory-arms tests/fixtures/memory_arms/arms_cases.v1.json \
  --measure-latency --runs 20 --json   # 延迟仅本地、非生产，且会破坏字节稳定
```

## 6. 变更纪律

- 冻结文件只加不删；新增 fixture 使用 `arms_cases.v2.json` 并在本 README 登记指纹与期望值；
- 修改 Java 的 `AgentMemoryService` / `AgentMemoryRepository` 选择语义时，必须同步更新本
  fixture 的期望值与口径说明；
- 真实任务执行、Dreaming 实现、审批流接入后，另行新增证据文件，不得把本地镜像数字
  改写为真实任务数字。
