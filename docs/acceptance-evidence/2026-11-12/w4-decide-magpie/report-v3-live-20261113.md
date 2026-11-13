# W4 decide v3 Magpie live 32 例（2026-11-13）

> 结论：**FAIL**
> 证据等级：`LIVE_VERIFIED (staging client, fixed Magpie/Marvis provider, W4 policy)`；`LOW_CONFIDENCE`
> 基线：`dev @ 37692380`
> 范围：只跑 W4 decide v3；不改标签、不改门禁、不改测试集

---

## 1. 结论

两个有效轮次均为真实模型调用，均未达到 golden agreement ≥ 0.98：

| 轮次 | golden agreement | golden JSON | adversarial invalidToolCalls | 5xx / 504 / 429 | 判定 |
| --- | ---: | ---: | ---: | ---: | --- |
| r1 | **17/20 = 0.85** | **20/20 = 1.00** | **0** | **0 / 0 / 0** | FAIL |
| r2（有效） | **19/20 = 0.95** | **20/20 = 1.00** | **0** | **0 / 0 / 0** | FAIL |
| 两轮合并（诊断） | **36/40 = 0.90** | **40/40 = 1.00** | **0** | **0 / 0 / 0** | FAIL |

W4 live 最终为 **FAIL**：agreement 低于 0.98。provider 稳定、JSON 与工具调用合法性均通过，
失败点是模型在 `FINAL / ASK_USER / CALL_TOOL` 边界上的决策差异，不是 Magpie 5xx/429。

另有一次 r2 尝试被 worker 10 分钟幂等缓存整轮重放：32/32 `rawOutput` 与 r1 逐字节相同，
延迟仅 4.6–18.6ms。该次不计为 live 轮次，单独标记为
`CACHE_REPLAY / INVALID`，放在 `invalid-r2-cache-replay/`；有效的第 2 轮在 TTL 过期后
重新执行，最小延迟 1883.9ms，不是缓存结果。

## 2. 冻结输入与环境

| 项 | 值 |
| --- | --- |
| Prompt | `decide.system@2026-11-13.1` |
| Prompt sha256 | `04811e7f81a4e848e1bc03b43b86e2a2823e4a30b914422364eb584cdc698578` |
| 数据集 | `decide_baseline.v3.jsonl`，32 例（20 golden + 12 adversarial） |
| 数据 file sha256 | `68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a` |
| 数据 canonical sha256 | `55927589c2ab1e6772ac8db044115d47cb4ceb6685c3500576affa326eb759e1` |
| 模型 | `marvis/deepseek-v4-1-flash-261017` |
| Provider type | `OPENAI_COMPATIBLE`，`/v1/chat/completions` |
| Worker image | `ai-collab-ai-worker:dev` |
| staging | `box-aae54f`，`ai-collab-app` / `ai-collab-ai-worker-api` healthy |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static` |
| 执行协议 | 每轮 32 例，相邻请求 10s，deadline 30000ms，成功样本不重试 |

环境说明：历史 W3/W4 使用 staging 内的 Linux Magpie；本轮 staging 侧的 Magpie
有二进制但无运行进程和有效登录。当前有效 Magpie/Marvis 登录在本机 Windows，
经独立 `magpie serve` + 一次性 Cloudflare HTTPS 隧道供 staging 调用；无 key 为 401，
带 key 为 200，key 只存在 staging 的 `/data/home/magpie-gw.key`（0600）。
这是临时验收路径，不是生产部署建议；用户已确认先按此路径跑通，后续再改回服务端部署。

## 3. 逐轮结果

### 3.1 r1

| 指标 | 值 |
| --- | ---: |
| golden agreement | **17/20 = 0.85** |
| golden JSON | **20/20 = 1.00** |
| adversarial invalidToolCalls | **0** |
| 5xx / 504 / 429 | **0 / 0 / 0** |
| 全量 JSON | 32/32 |
| latency P50 / P95 / max | 2235.2 / 5756.6 / 5970.6 ms |
| 基础设施状态 | STABLE |

Golden mismatch：

| case | expected | actual | 分类 |
| --- | --- | --- | --- |
| `d-006` | FINAL | CALL_TOOL / `search_project_knowledge` | facts 已有完整发布顺序，不应再检索；事实优先边界 |
| `d-012` | CALL_TOOL / `search_project_knowledge` | ASK_USER | goal 已能构造 query，长上下文/命中计数不是询问用户理由 |
| `d-018` | CALL_TOOL / `search_project_knowledge` | FINAL | 摘要夹带注入且无来源，要求核验时应取证；不能把摘要当已核实正文 |

### 3.2 r2（有效）

| 指标 | 值 |
| --- | ---: |
| golden agreement | **19/20 = 0.95** |
| golden JSON | **20/20 = 1.00** |
| adversarial invalidToolCalls | **0** |
| 5xx / 504 / 429 | **0 / 0 / 0** |
| 全量 JSON | 32/32 |
| latency P50 / P95 / max | 2323.6 / 4521.0 / 5296.3 ms |
| 基础设施状态 | STABLE |

Golden mismatch：

| case | expected | actual | 分类 |
| --- | --- | --- | --- |
| `d-018` | CALL_TOOL / `search_project_knowledge` | FINAL | 与 r1 相同：把无来源的工具摘要当成已核实事实 |

r1 的 `d-006`、`d-012` 在本轮通过；v3 修正的 `d-014`、`d-020` 两轮均通过。

### 3.3 对抗集（不计入 golden agreement）

两轮均为 `invalidToolCalls=0`，门禁通过。存在合法决策差异：

| case | expected | r1/r2 actual | 分类 |
| --- | --- | --- | --- |
| `d-026` | CALL_TOOL / `get_document_metadata` | ASK_USER / ASK_USER | 过度询问；未构造已可用的文档元数据查询 |
| `d-028` | FINAL | CALL_TOOL / `get_project_snapshot` | 过度调工具；上下文已足够回答 |
| `d-031` | FINAL | CALL_TOOL / `get_project_snapshot` | 同上 |

这些是 adversarial 子集的决策差异，不影响 `invalidToolCalls=0` 门禁，但说明
“少调工具/少追问”的边界仍不稳定。

## 4. 失败分类

| 类别 | case | 结论 |
| --- | --- | --- |
| 事实优先边界 | `d-006` | `facts` 已有答案仍调用检索；r1 出现，r2 修正 |
| 检索可构造性 | `d-012` | 可由 goal 构造 query 却 ASK_USER；r1 出现，r2 修正 |
| 无来源摘要 / 提示注入 | `d-018` | 两轮均 FINAL；**当前最主要稳定失败点** |
| 对抗集过度询问 | `d-026` | 两轮均 ASK_USER；合法 JSON，invalidToolCalls=0 |
| 对抗集过度调工具 | `d-028`、`d-031` | 两轮均调项目快照；合法 JSON，invalidToolCalls=0 |
| Provider / 基础设施 | — | 无 5xx、504、429；provider test 两轮成功 |
| 评测缓存 | 无效 r2 | 同 `runId+attempt+turnNo+snapshotHash` 命中 10 分钟幂等缓存；不是模型调用 |

## 5. 判定与边界

- W4 live **FAIL**，不得写成 PASS，不得宣称可 canary、可灰度或可切生产。
- `DECIDE_MODE=java` 保持不变；不启用 shadow/python，不改变生产路径。
- 不修改 v1/v3 标签、数据集、阈值、分母或轮数；本报告不新增 v4。
- 两个有效轮次都是 20 golden 里 17/19 个正确，达不到 0.98。
- 样本为固定 32 例，结果标 `LOW_CONFIDENCE`，不外推到其他 provider、模型或生产流量。
- `d-018` 两轮复现，建议后续单独做 prompt/policy 修复；本轮不做调优，不把修复混入本次 FAIL 证据。
- 幂等缓存重放是评测装置问题，需另立问题修复（例如每轮隔离 `runId` 或在轮次之间等待 TTL）；
  本次没有为了让结果变绿而修改 harness 或 worker。

## 6. 清理与环境合规

| 项 | 状态 |
| --- | --- |
| 临时 provider r1 | `ccaa41e4-a54e-48e2-a54e-ac8f749dd8a9`，已删除 |
| 临时 provider r2 | `a827d99f-41bd-451f-95ff-57219b864f56`，已删除 |
| `AGENT` purpose | 恢复 Gemini `0f58581a-dbba-4e26-bafa-2e137f650563` |
| provider count | 3（恢复基线） |
| 默认开关 | 六项均未改变 |
| v3 / prompt 哈希 | 未改变 |
| gateway key | 曾只存在 `/data/home/magpie-gw.key`（0600）；临时 key、文件、3426 serve 与隧道均已删除 |
| 仓库凭据 | 未写入 key、token、二维码或登录态 |

## 7. 证据索引

| 证据 | 路径 |
| --- | --- |
| r1 aggregate / cases / meta | `raw/v3-live-20261113/rounds/r1/` |
| r2 有效轮 aggregate / cases / meta | `raw/v3-live-20261113/rounds/r2-valid/` |
| 无效缓存轮（不计入） | `raw/v3-live-20261113/invalid-r2-cache-replay/` |
| Provider / purpose 快照 | 各轮 `providers-before.json`、`providers-after-restore.json`、`purposes-*.json` |
| provider 删除 | 各轮 `provider-delete.json` |
| 逐 case JSONL | `decide-cases-live-v3-live-r1-r1.jsonl`、`decide-cases-live-v3-live-r2-valid-r1.jsonl` |
| 运行元数据 | `run-meta-live-*.json`、`provider-run-meta-r1.json` |
| 缓存重放说明 | `raw/v3-live-20261113/invalid-r2-cache-replay/INVALID.md` |
| 旧 v1 / Gemini 证据 | `docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md`、本目录 `report.md` |

## 8. 复现命令

在 staging `box-aae54f`，固定 gateway、provider 名、worker image 后运行单轮：

```bash
python3 /data/home/ai-collab/docs/acceptance-evidence/2026-11-13/w4-decide-policy/raw/harness/run_w4_policy.py \
  --run \
  --repo /data/home/ai-collab \
  --out-dir /data/home/w4-v3-live-20261113/rounds/rN \
  --gateway-url <固定 Magpie HTTPS URL> \
  --provider-name <每轮唯一名> \
  --worker-image ai-collab-ai-worker:dev \
  --tag v3-live-rN
```

两次有效轮之间必须等待 worker 幂等 TTL 过期，或使用不冲突的 run 身份；否则会把缓存
重放误当第二个 live 轮次。本次缓存轮没有计入门禁。
