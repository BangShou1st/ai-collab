# Git 与 PR 审计（git-and-pr-audit.md）

## 一、Git 基线核实（会话开始时，2026-10-24）

| 项 | 值 |
| --- | --- |
| 工作分支 | `dev`，工作区干净（clean） |
| 本地 dev = origin/dev | `9ef75503c3cf953e4048a1e807e2451fa0794410` |
| 本地 main = origin/main | `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7` |
| 与任务书记录 | 完全一致 |
| 最新 dev CI（push） | run [40100668515](https://github.com/BangShou1st/ai-collab/actions/runs/40100668515) success（Backend/Frontend 全绿，Image Job skipped——符合触发条件设计） |
| 已有 PR | 无开放 PR；#17（dev→main，docs 治理）已合并 |
| main 分支保护 | required checks：`Backend (Maven verify)`、`Frontend (pnpm)`；要求 PR、enforce_admins、禁止 force push/删除 |

注意：**`Image build & container smoke` 不在 main 的 required status checks 中**——记入风险清单（见 release-risk-register.json N-01），本轮不修改分支保护（需管理员操作，超出本轮授权）。

## 二、dev → main 差异审查（origin/main...origin/dev）

- **提交数**：22 个，全部为 Conventional Commits，可逐条对应已验收任务：
  - P4 系列：RAG 检索质量（01/01B）、RAG 跨文档来源身份修复 + 真实 Gemini 验收、知识库真实 E2E、模型快速接入统一重整 + 真实验收、Agent 工具参数 Schema uuid/date 校验修复、Agent 生命周期真实 E2E。
  - P5 系列：P5-RELEASE-READINESS-01 全系统审计（CONDITIONAL GO）、P5-RELEASE-READINESS-02 四项运维 P1 整改、两轮 CI 结果补记。
- **变更规模**：261 文件，+30293 / −2208 行。分布：docs 145、后端 63（含 32 个测试文件）、前端 23、tests 语料 16、deploy 9、其余为 README/AGENTS/.gitignore/.github/.env.example 各 1。
- **无重大异常**，逐项确认：
  1. 无日志/备份/数据库文件误提交（可疑文件名扫描仅命中合法的 RefreshToken 安全类、备份脚本、V66 迁移）。
  2. `.env.example` 变更即 R-04 安全基线：`REFRESH_COOKIE_SECURE` 默认 `true` + 新增 `REFRESH_COOKIE_ALLOW_INSECURE=false` + `AUTH_ALLOWED_ORIGINS`，无真实凭据。
  3. 验收证据 JSON（transport-attempt-*.json）内容为测试元数据与系统提示词文本，无密钥/Token。
  4. 迁移仅新增 `V66__add_user_ai_provider_probe_result.sql`，与既有 V1–V65 连续，无历史迁移改动。
  5. 后端主源码变更集中在模型接入（infrastructure/ai）、RefreshTokenProperties、MinioHealthIndicator、RequestIdMdcFilter、application*.yml——全部对应已验收任务，无实验性调试代码。
  6. 无合并冲突风险：`git merge-base --is-ancestor origin/main origin/dev` 通过，main 是 dev 的祖先，可快进合并。

## 三、Draft PR 属性核实（创建后）

| 项 | 值 |
| --- | --- |
| PR 编号 / URL | [#18](https://github.com/BangShou1st/ai-collab/pull/18) |
| 标题 | Release Candidate: AI Collab P5 readiness validation |
| base ← head | `main` (9b4f7c5) ← `dev` (9ef7550) |
| isDraft | `true`（创建时即为 Draft） |
| 正文约束 | 已写明 **Validation only — DO NOT MERGE without explicit user approval.** |
| mergeable_state | `clean` |
| 合成 merge commit | `281fdbee998edfc2d2a93d2bbae024e18c2bbc1f`（父提交 = main + dev） |

## 四、PR CI 实际检出 SHA 与源分支 SHA 的区分（关键）

| SHA | 含义 | 值 |
| --- | --- | --- |
| 源分支真实提交 | dev HEAD，PR head | `9ef75503c3cf953e4048a1e807e2451fa0794410` |
| PR CI 实际检出 | pull_request 事件的 GITHUB_SHA = 合成 merge ref | `281fdbee998edfc2d2a93d2bbae024e18c2bbc1f` |
| 镜像 revision label | CI 内 `${GITHUB_SHA}` | `281fdbee998edfc2d2a93d2bbae024e18c2bbc1f` |

三者关系已验证：merge commit 的父提交是 main+dev，且本地 `git diff 281fdbee 9ef7550` 为空——**merge commit 的树与 dev 提交树完全一致**（main 为 dev 祖先，无合并改动）。因此镜像 revision label 指向的 merge commit 与源分支代码内容等价，版本追溯链完整（详见 ci-image-build-results.md）。
