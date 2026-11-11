# Handoff：J1 收口 → 下一批起点（2026-11-11）

> 给**新会话**用：读完这一页 + `docs/archive/report/j1-cleanup-20261111.md` 就能接上，
> 不需要读本次会话的历史。

## 1. 仓库状态

| 项 | 值 |
| --- | --- |
| 分支 | `dev`，与 `origin/dev` 一致（已推送），`main` 未动 |
| HEAD | `a3fc6224 docs(j1): 补记观察期环境、真实指标实证与管理端口抓取修复` |
| 本批提交 | 22 个（J0 的 11 + J1 的 11），无未提交改动 |
| 迁移 | 最新仍 **V89**，J0/J1 两批都没有新增迁移 |
| CI | 最近一次 `dev` push（`238e76a`，run 43031250674）全绿：3 个后端分片 + Maven verify + 前端 + P0 契约 |

## 2. J1 批次结论：2 交付 + 2 挂起

| 步骤 | 结论 |
| --- | --- |
| J1-1 Legacy 退出 | **挂起（不删）**：缺生产"CHAT-only Agent 调用量为 0"的证据；且 `AgentToolCallExecutor:613-614` 的 `source_mode IS NULL` 兜底是历史写调用的安全边界 |
| J1-2 文档范围结构化 | **挂起（不删正则）**：Agent 侧无文档范围选择器，删正则会让"只根据《X》"静默退回全项目检索 |
| J1-3 composerV2 回退 | **已交付** `0904a22`：所有 Run 固定 v2 组装 |
| J1-4 问答 Prompt 收敛 | **已交付** `1dc4c2a`：三份 Prompt → `KnowledgePromptProvider` 单一来源 |
| J1-1-P1 可观测性 | **已交付** `a7c3f08`：`agent.model.request{mode=NATIVE_TOOLS\|LEGACY_READ_ONLY}` |
| J1-2-P1 可观测性 | **已交付** `4f81400`：`agent.document.scope{status}` |
| 管理端口抓取 | **已交付** `b5d333e` + `238e76a`：`/actuator/prometheus` 在独立管理端口匿名可抓 |

详细取证、影响面清单与执行步骤：`docs/archive/report/j1-cleanup-20261111.md`
（J1-1 → §1；J1-2 → §2；J1-3 → §3；J1-4 → §4；verify → §6；观察环境 → §12）。

## 3. 两项挂起怎么捡起来

**J1-1**：任意环境里

```promql
sum by (mode) (increase(agent.model.request{purpose="turn"}[30d]))
```

`LEGACY_READ_ONLY` 为 0，且生产 SQL（报告 §1.4 的 (a)(b)）也为 0 → 直接进 R3 删代码；
非 0 → 先发配置迁移 + 用户通知，再删（设计 §5.3 要求两次发布）。

**J1-2**：看 `agent.document.scope` 一个周期里 `ENFORCED` 的占比。低 → 按"显式 documentIds +
fail-closed 澄清"切换并删正则；高 → 先给 Agent 页加文档多选（前端）。

两处的完整改动清单、必须保留的安全语义（`source_mode = 'LEGACY_READ_ONLY'` 的拒绝、
项目归属/READY/撤权过滤）都在报告 §1.4 / §2.3。

## 4. 观察环境（已就绪，别再重建）

instabox 沙箱 `box-aae54f`（4 vCPU / 8 GB，`/data` 剩余充足），上面跑了一套**与既有 staging 隔离**的
dev staging：

| 项 | 值 |
| --- | --- |
| 公网 | `https://9443-agqyc3kteqgazwefpmhwulu.instabox.tech` |
| compose | `-p ai-collab-devstaging --env-file /data/workspace/ai-collab-devstaging/config/.env -f dev-staging.yml` |
| 源码 | `/data/workspace/ai-collab-devstaging/repo`（dev 浅克隆） |
| 模型 | ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`，能力含 `NATIVE_TOOLS`（探针 PASS） |
| 抓取 | `docker exec ai-collab-devstaging-app-1 curl -s http://127.0.0.1:8081/actuator/prometheus`（**匿名**，无需 JWT） |
| 运维细节 | box 上 `/data/workspace/ai-collab-devstaging/README.md`（含重建、更新、踩坑） |

注意：
- 指标是**进程内计数**，重建/重启 app 容器会归零，需要重新产生流量。
- Agent/问答开关在 `system_configuration` 表、启动时读入内存，改库必须重启 app。
- 该环境模型是 NATIVE_TOOLS，`LEGACY_READ_ONLY` 恒 0 —— 只证明"本环境未用 Legacy"，
  不能替代生产证据。
- 凭据（DB/MinIO/JWT/模型 Key）都在 box 的 `config/.env` 与应用密文表里，**不入库**；
  不要把任何 key 写进仓库文档。

## 5. 下一批建议：P0（Python worker 骨架 + PromptPack）

设计见 `docs/design/python-ai-capability-plane-2026-11-11.md` §6.1（目录）、§6.2（技术栈与禁入）、
§6.3（P0 退出条件）、§7.2（PromptPack 契约）、§7.1（错误码与超时）。

**范围**（独立仓库 `ai-collab-ai-worker/`，不碰 Java）：

- FastAPI 装配 + `/healthz` `/readyz` `/metrics`；
- `contract/` 的 Pydantic v2 模型 + JSON Schema 快照；
- `prompts/` PromptPack 文件 + `manifest`（key/version/sha256/chars）+ `GET /v1/prompts/manifest`、`GET /v1/prompts/{key}/{version}`；
- `ai-eval` CLI（读冻结集 → canonical JSON + SHA256）；
- 禁入清单：LangChain/LangGraph/LlamaIndex/CrewAI/PydanticAI、psycopg/asyncpg/SQLAlchemy/redis/boto3/minio、Celery/RQ/Dramatiq/APScheduler、openai/anthropic/google-genai、torch/transformers。

**退出条件**：`uv sync --frozen` 可复现；`ruff` + `mypy --strict` + `pytest` 全绿；零 DB、零密钥；
与 Java 同输入产出可比。

**开工第一步**（建议）：先定 `contract/` 的 schemaVersion=1 快照与 PromptPack manifest 形状
（它同时是 J2 的输入），再写 `/healthz` 与 `ai-eval` 骨架——顺序反了会出现"先写实现再改契约"。

## 6. 红线（延续）

- 直接提交 `dev`；不 force push、不改写已推送历史、不合并 `main`（PR 由人触发）。
- 不改历史 Flyway；数据库变更只新建连续版本迁移。
- 不改前端行为；不调格式；不改依赖版本；不夹带其它批次。
- 密钥/Token 不入库、不进日志、不进文档。
- 每步先 `bash ./mvnw -B -ntp -o -DskipTests compile`，能跑的测试都跑；交付报告写清
  改动/为什么/验证/行为变化/遗留/证据等级。
