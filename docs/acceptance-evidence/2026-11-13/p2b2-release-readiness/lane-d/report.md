# P2-B2 Lane D：仓库只读盘点

> 日期：2026-11-13（Asia/Shanghai）
> 扫描快照：`dev @ 5df9c596`；扫描时工作区有 P2-B2 Lane B 未提交改动
> 性质：**只读盘点**。未删除、未移动、未重写历史、未执行 force push 或 BFG/filter-repo。
> 结论：**仓库无需在本轮瘦身**；主要体积来自当前 `docs/` 验收证据与大图，
> 历史改写不是必要动作。

## 1. 结论先行

| 指标 | 数值 | 说明 |
| --- | ---: | --- |
| `.git` 总大小 | **117,950,796 B / 112.49 MiB** | Git 对象、索引、日志与引用 |
| pack 大小 | **106.33 MiB / 4 个 pack** | 主要压缩对象 |
| loose objects | **2,975 / 4.60 MiB** | 另有 `prune-packable=1` |
| pack 内对象 | **22,279** | `in-pack` |
| 当前 HEAD tree | **5,546 blobs / 186.61 MB 未压缩** | 当前提交实际需要的内容 |
| 所有 refs 唯一 blob | **9,439 / 240.26 MB 未压缩** | 含历史、tag、远端与 Codex refs |
| 工作区（不含 `.git`） | **1.44 GB / 1.34 GiB，36,821 文件** | 主要是依赖、构建与虚拟环境 |
| `docs/` 在对象中的未压缩字节 | **174.83 MB / 72.77%** | 验收证据是最大来源 |
| PNG 未压缩字节 | **96.39 MB / 570 blobs** | 截图类证据占单扩展名第一 |
| HEAD 历史剔除的旧 blob | **7 个 / 134,479 B** | 绝大多数大文件仍在当前 HEAD |
| unreachable（不含 reflog） | **2,754 对象；blob 525 / 12.35 MB** | 另有 tree/commit；可回收空间有限 |
| 全量 fsck | **无输出（无损坏）** | `git fsck --full --no-dangling` |

判断：

- “111 MB”主要对应 `size-pack 106.33 MiB + loose 4.60 MiB`，与用户口径一致。
- 体积不是由某个孤立的历史大文件造成；**当前仍在 HEAD 的 `docs/acceptance-evidence/`
  日志、JSON 和 PNG 截图**才是主体。
- 只做非历史改写操作（删当前文件、重传、普通 `git gc`）不能显著缩小 clone；
  要真正降到几十 MB，必须改写历史或迁移大文件到外部 artifact store，属于另一个维护会话。
- 本轮不执行瘦身；只保留本报告和建议。

## 2. 盘点命令

```powershell
git status --short --branch
git rev-parse HEAD
git count-objects -vH
git worktree list
git branch -a -vv
git show-ref --head
git tag --list
git ls-tree -r -l HEAD
git rev-list --objects --all
git rev-list --objects HEAD
git cat-file --batch-check='%(objectname) %(objecttype) %(objectsize) %(rest)'
git fsck --full --no-dangling
git fsck --unreachable --no-reflogs
git fsck --unreachable
git for-each-ref ...
git reflog --date=iso
```

对象大小口径：

- “未压缩”= `git cat-file` 的 blob 原始字节，不等于磁盘 pack 占用。
- “HEAD 历史剔除”= 存在于所有 refs，但不在 `git rev-list --objects HEAD` 中的 blob。
- “当前 HEAD tree”= `git ls-tree -r -l HEAD` 的实际提交文件。

## 3. 体积分布

### 3.1 扩展名（按对象未压缩字节）

| 扩展名 | 未压缩字节 | 占比 | blob 数 |
| --- | ---: | ---: | ---: |
| `.png` | 96,393,936 | 40.1% | 570 |
| `.java` | 48,004,328 | 20.0% | 3,620 |
| `.json` | 34,213,943 | 14.2% | 1,199 |
| `.md` | 15,955,086 | 6.6% | 1,316 |
| `.txt` | 11,417,615 | 4.8% | 595 |
| `.log` | 8,915,509 | 3.7% | 60 |
| `.jsonl` | 4,652,483 | 1.9% | 212 |
| `.vue` | 4,161,025 | 1.7% | 337 |
| `.py` | 3,627,684 | 1.5% | 347 |
| `.yaml` | 3,611,985 | 1.5% | 39 |
| `.ts` | 2,702,474 | 1.1% | 444 |

`.png + .json + .txt + .log + .jsonl` 合计约 155.6 MB，占 64.8%，主要是验收图片、
原始 JSON/JSONL 和构建日志。

### 3.2 顶层目录（按对象未压缩字节）

| 目录 | 未压缩字节 | 占比 | blob 数 |
| --- | ---: | ---: | ---: |
| `docs/` | 174,829,341 | 72.8% | 3,915 |
| `ai-collab-backend/` | 49,181,148 | 20.5% | 3,833 |
| `ai-collab-frontend/` | 7,719,029 | 3.2% | 842 |
| `ai-collab-ai-worker/` | 4,770,872 | 2.0% | 428 |
| 根目录 | 1,487,952 | 0.6% | 114 |
| `ai-collab-deploy/` | 985,411 | 0.4% | 157 |
| `tests/` | 817,977 | 0.3% | 91 |
| `.github/` | 295,697 | 0.1% | 26 |

`docs/` 是唯一值得优先治理的目录；后端源码占 20.5% 属正常主源码体量，
不应为了体积做架构层拆分。

### 3.3 体积桶

| 阈值 | 唯一 blob 数 |
| --- | ---: |
| ≥ 1 MB | 10 |
| ≥ 500 KB | 35 |
| ≥ 250 KB | 116 |
| ≥ 100 KB | 536 |

## 4. 当前 HEAD 最大 30 个 blob

| # | 字节 | 路径 |
| ---: | ---: | --- |
| 1 | 4,225,531 | `docs/acceptance-evidence/2026-11-12/final-gates/raw/maven-verify.txt` |
| 2 | 3,113,176 | `docs/acceptance-evidence/2026-11-12/w3-reranker-local/raw/functional/llama-server-formal.log` |
| 3 | 2,690,017 | `docs/acceptance-evidence/2026-11-07/rag-v2-p10-final-security-quality-01/run-logs/rag-v2-p10-final-verify.txt` |
| 4 | 2,336,014 | `docs/acceptance-evidence/2026-10-09/context-compaction/model-calls-private.jsonl` |
| 5 | 1,700,873 | `docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/raw/verify-output.txt` |
| 6 | 1,441,818 | `docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01/paired-experiments.json` |
| 7 | 1,301,824 | `docs/benchmark/2026-11-07-rag-v2-public-benchmark/retrieval-results-pool20-reference.json` |
| 8 | 1,297,698 | `docs/benchmark/2026-11-07-rag-v2-public-benchmark/retrieval-results.json` |
| 9 | 1,291,759 | `docs/acceptance-evidence/2026-10-27/p10-real-generation-03/judge/corrected-review-inputs-02.json` |
| 10 | 1,171,938 | `docs/acceptance-evidence/2026-10-27/p10-real-generation-03/judge/corrected-review-inputs.json` |
| 11 | 1,048,337 | `docs/acceptance-evidence/2026-10-16/d-rag-01a/maven-verify-scheduler-fix.log` |
| 12 | 1,024,418 | `docs/acceptance-evidence/2026-10-16/d-rag-01a/maven-verify-final.log` |
| 13 | 1,005,798 | `docs/acceptance-evidence/2026-10-16/d-rag-01a/maven-verify-pr12.log` |
| 14 | 950,957 | `docs/acceptance-evidence/2026-10-08/d1d8-delivery/green-full-backend-run.log` |
| 15 | 926,205 | `docs/acceptance-evidence/2026-11-03/onboarding-workbench-upgrade/baseline/login-1440.png` |
| 16 | 924,983 | `docs/acceptance-evidence/2026-11-03/workbench-fix/01-login-no-competition-tag.png` |
| 17 | 924,342 | `docs/acceptance-evidence/2026-11-03/onboarding-workbench-upgrade/final/login-1440-after-tag-removed.png` |
| 18 | 922,524 | `docs/acceptance-evidence/2026-11-05/account-email-cooldown/forgot-password.png` |
| 19 | 900,705 | `docs/acceptance-evidence/2026-11-03/frontend-experience-final/iteration/iteration-home-1440.png` |
| 20 | 900,195 | `docs/acceptance-evidence/2026-11-03/frontend-experience-final/iteration/iteration-board-1440.png` |
| 21 | 810,961 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-invalid-1440.png` |
| 22 | 798,098 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/forgot-password-1440.png` |
| 23 | 790,946 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-error-feedback.png` |
| 24 | 789,610 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-form-1440.png` |
| 25 | 783,465 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-1440.png` |
| 26 | 780,577 | `docs/acceptance-evidence/2026-11-03/onboarding-workbench-upgrade/final/login-1440-tag-removed.png` |
| 27 | 777,618 | `docs/acceptance-evidence/2026-11-03/workbench-fix/01-login-clean-no-tag.png` |
| 28 | 753,589 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-1440.png` |
| 29 | 748,387 | `docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-validation-feedback.png` |
| 30 | 719,453 | `docs/acceptance-evidence/2026-11-12/w3-reranker-local/raw/formal-run2/llama-server-formal.log` |

## 5. 历史与引用关系

### 5.1 引用

| 类型 | 数量 | 备注 |
| --- | ---: | --- |
| 本地分支 | 2 | `dev`、`main` |
| 远端分支 | 3 | `origin/dev`、`origin/main`、`origin/HEAD` |
| tag | 20 | 含 `v1.0.0` 与 19 个 `archive/*` |
| Codex refs | 11 | `refs/codex/turn-diffs/*`，属于工作区恢复/快照元数据 |
| reflog 条目 | 1,499 | 保留近期提交/切换轨迹 |

当前：

- `dev @ 5df9c596`，比 `origin/dev @ 94672900` 领先 3 个提交；
- 本地 `main @ 66a704dd` 落后 `origin/main @ 2aeee50d` 71 个提交；
- 主工作树只有一个：`E:/project/ai-collab`。

这不是体积问题，但本地 `main` 陈旧，后续任何发布判断必须以 `origin/main` 和实际 PR 为准，
不能把本地 `main` 当远端最新状态。

### 5.2 HEAD 历史剔除的旧 blob

仅 7 个，合计 **134,479 B**：

| 字节 | 路径 |
| ---: | --- |
| 32,081 | `ai-collab-backend/src/test/java/.../AgentDecideShadowServiceTest.java` |
| 24,721 | `ai-collab-backend/src/main/resources/application.yml` |
| 18,149 | `ai-collab-backend/src/main/resources/application.yml` |
| 17,759 | `ai-collab-backend/src/main/resources/application.yml` |
| 17,313 | `ai-collab-backend/src/test/java/.../DecideGatewayClientTest.java` |
| 15,872 | `ai-collab-backend/src/main/java/.../ErrorCode.java` |
| 8,584 | `docs/api/mcp-gateway-internal-v1.md` |

结论：**没有需要靠“删除旧历史大文件”解决的主要问题**；大头仍是 HEAD 当前内容。

### 5.3 unreachable 与 reflog

- `git fsck --unreachable --no-reflogs`：2,754 个对象
  （525 blob / 2,077 tree / 152 commit）。
- unreachable blob 未压缩合计 **12,352,068 B（约 12.35 MB）**；
  tree + commit 约 973 KB。
- 计入 reflog 后 unreachable 降为 2,467 个对象，说明其中一部分由 reflog 保护。
- `git count-objects -vH`：`prune-packable=1`，`garbage=0`。

少量可回收空间约 **10–13 MB**，而且普通 `git gc` 可能被 reflog/Codex refs 阻止；
这不足以支撑一次激进的历史清理，也不应在本轮执行。

## 6. 工作区体积

工作区（不含 `.git`）为 **1,443,187,150 B / 1.34 GiB / 36,821 文件**，
主要不是 Git 对象：

| 路径 | 字节 | 文件数 | 说明 |
| --- | ---: | ---: | --- |
| `target/` | 605,024,814 | 2,984 | 构建/性能/临时证据产物 |
| `ai-collab-backend/` | 330,902,268 | 7,095 | 含 `target/` 与本地构建缓存 |
| `ai-collab-frontend/` | 197,848,708 | 18,706 | 含 `node_modules/` |
| `docs/` | 182,483,628 | 3,420 | 跟踪证据 + 本地生成物 |
| `ai-collab-ai-worker/` | 125,803,441 | 4,447 | 含 `.venv/` |
| 根目录 `node_modules/` | 接近空目录 | 0 | 实际依赖在前端子目录；不计入主要体积 |

这些生成目录不应进入 Git；当前 `git status` 没有把它们列为未跟踪文件，说明忽略规则生效。
本地工作区清理是另一类磁盘维护，不属于本轮仓库瘦身。

## 7. 风险与建议

### 7.1 风险

1. **PNG/日志入库**：截图约 96 MB，日志与原始 JSON 约 60 MB，持续新增会线性抬高 clone 时间。
2. **LFS 未启用**：`.gitattributes` 只把 PNG 标记为 binary，没有 LFS 指针；大图仍直接进入历史。
3. **reflog/Codex refs 不能盲删**：1499 条 reflog、11 个 `refs/codex/turn-diffs/*` 支撑恢复与审计；
   直接 prune 可能丢失工作区恢复点。
4. **历史改写代价高**：真正缩减 clone 需要移动当前证据路径（历史仍保留旧路径）并重写所有提交；
   与“禁止 force push/改写历史”的仓库规则冲突，必须另开维护会话并由人批准。
5. **main/dev 状态漂移**：本地 `main` 落后 origin 71 个提交；不要以本地 main 做发布判断。

### 7.2 本轮建议（不执行）

- 不在本轮删除任何证据或执行 `git gc --aggressive`。
- 后续证据优先保存压缩后的 PNG/WebP、摘要 JSON 和可复现命令；完整日志仅在受控 artifact
  store 保存，并在仓库保留 SHA-256/链接。
- 新增截图超过约 200 KB 时，优先压缩后再提交；大日志不要直接提交。
- 业务代码与 `docs/` 证据分开治理；后端源码 49 MB 属正常，不要为了瘦身拆模块。

## 8. 备份与维护方案

### 8.1 任何清理前的备份（建议）

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force E:\project\backups\ai-collab | Out-Null
git bundle create "E:\project\backups\ai-collab\ai-collab-$stamp.bundle" --all
git show-ref > "E:\project\backups\ai-collab\refs-$stamp.txt"
git count-objects -vH > "E:\project\backups\ai-collab\count-$stamp.txt"
git fsck --full --no-dangling > "E:\project\backups\ai-collab\fsck-$stamp.txt" 2>&1
```

若维护会话决定做不可逆操作，再加：

- 复制整个 `.git` 目录到独立磁盘；
- 导出一个 mirror clone；
- 记录所有 tag/远端 refs 与实际 SHA；
- 在隔离副本先演练，禁止直接在主工作树重写。

### 8.2 分级方案

| 方案 | 收益 | 风险 | 本轮 |
| --- | --- | --- | --- |
| 停止新增未压缩证据 | 防止继续膨胀 | 无 | 建议立即执行 |
| 普通 `git gc`（非 aggressive） | 可能回收少量 loose/pack | 低；reflog/Codex refs 会保留 | 可另择维护窗口 |
| 过期旧 reflog 后 prune | 约 10–13 MB | 中；丢失恢复轨迹 | 不做 |
| 将新证据外置到 artifact store | 未来增长受控 | 流程改造 | 后续 |
| `filter-repo` 移出历史 PNG/日志 | 最大，可能降到几十 MB | 高；必须重写历史、协调远端/PR | 不在本会话 |
| 新建干净仓库并保留旧仓归档 | 彻底小 clone | 高；需要产品/运维决策 | 另立维护会话 |

如果必须 rewrite，最低限度流程：

1. 冻结 `dev`，确认没有未推送/未合并工作；
2. 备份 bundle + `.git` + refs；
3. mirror clone 到隔离盘；
4. 只对证据大文件路径执行 filter，保留代码/SHA 引用并生成迁移映射；
5. 在隔离副本跑全量 CI/链接检查；
6. 由人决定是否替换远端；**不得在本规则下直接 force push `main`**。

## 9. Deferred / 未执行

| 项目 | 状态 |
| --- | --- |
| 删除大文件/证据 | **未执行** |
| `git gc --aggressive` | **未执行** |
| reflog 过期/prune | **未执行** |
| filter-repo / BFG | **未执行** |
| 分支/tag 删除 | **未执行** |
| force push | **未执行** |
| 远端 main 同步 | **未执行** |

## 10. 判定

```text
Lane D：PASS（只读盘点完成）
仓库完整性：PASS（fsck 无输出）
当前 clone 体积：约 111 MiB pack + loose，符合预期
本轮瘦身动作：NONE
后续若需显著瘦身：另开维护会话，先备份，再评估历史改写
```
