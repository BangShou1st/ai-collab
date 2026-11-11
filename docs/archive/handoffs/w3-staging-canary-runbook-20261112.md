# W3 rerank staging 灰度 runbook（dev-staging）

> 环境：instabox `box-aae54f`，compose 项目 `ai-collab-devstaging`，公网入口
> `https://9443-agqyc3kteqgazwefpmhwulu.instabox.tech`。
> 本文件只写操作与判据；**任何 token/密钥只经 `config/.env` 或环境变量传递，不写入本文件**。

## 1. 现有部署事实（2026-11-12 核实）

| 项 | 值 |
| --- | --- |
| compose | `/data/workspace/ai-collab-devstaging/dev-staging.yml`（项目名 `ai-collab-devstaging`） |
| env | `/data/workspace/ai-collab-devstaging/config/.env` |
| 源码 | `/data/workspace/ai-collab-devstaging/repo`（当前 J1 提交，非 W3） |
| app 镜像 | `ai-collab-backend:dev-j1`，容器 `ai-collab-devstaging-app-1`，管理端口 8081（仅内网） |
| 数据库 | 容器内 `psql -U ai_collab -d ai_collab`；Flyway 当前 V89（W3 不新增迁移） |
| 模型 | probe 用户（`049056aa-…`）的 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`，KNOWLEDGE_CHAT 已绑定 |
| 流量 | 过去 24h 仅 2 条请求（无真实用户）；所谓 canary 是**真实模型 + 真实容器 + 合成 query**，不是线上真实流量 |
| nginx | 只代理 `/api/v1/`；`/internal/**` 落到 SPA，不外泄 |

## 2. 部署 W3 版本

```bash
# 1) 更新源码到待验证提交（先由本地 push 到 origin/dev 或临时分支）
cd /data/workspace/ai-collab-devstaging/repo
git fetch --all && git checkout <commit> && git status -sb

# 2) Java 镜像（box 上 4C/7G，maven 依赖已预热）
cd ai-collab-backend && bash ./mvnw -B -ntp -DskipTests package
cp target/ai-collab-backend-0.0.1-SNAPSHOT.jar /data/workspace/ai-collab-devstaging/img/app.jar
cd /data/workspace/ai-collab-devstaging/img && docker build -t ai-collab-backend:w3 .

# 3) Python worker 镜像（构建上下文必须是 ai-collab-ai-worker/）
cd /data/workspace/ai-collab-devstaging/repo && docker build -t ai-collab-ai-worker:w3 ai-collab-ai-worker

# 4) env 追加（值由操作者填，不提交）
#    APP_IMAGE=ai-collab-backend:w3
#    AI_WORKER_IMAGE=ai-collab-ai-worker:w3
#    AI_WORKER_PROFILE=ai-worker          # 只在需要 worker 时启用 compose profile
#    RERANK_MODE=java|shadow|python
#    RERANK_WORKER_BASE_URL=http://ai-worker-api:9100
#    RERANK_SERVICE_TOKEN=<随机 32+ 位，Java 与 worker 同值>
#    RERANK_MODEL_USER_ID=049056aa-63b7-4932-b2d3-3546db122b0f
#    AI_WORKER_SERVICE_TOKEN=<同 RERANK_SERVICE_TOKEN>

# 5) 起栈（worker 用 profile 控制，不发布宿主端口）
cd /data/workspace/ai-collab-devstaging
docker compose -p ai-collab-devstaging --env-file config/.env -f dev-staging.yml \
  --profile ai-worker up -d --force-recreate app ai-worker-api
```

## 3. 冒烟与指标

```bash
# 契约冒烟（容器内，绕过 nginx）
docker exec ai-collab-devstaging-app-1 sh -c \
  'curl -s -X POST http://ai-worker-api:9100/v1/rag/rerank \
     -H "Authorization: Bearer $AI_WORKER_SERVICE_TOKEN" -H "Content-Type: application/json" \
     -d "{\"schemaVersion\":1,\"query\":\"回滚流程\",\"topK\":8,\"candidates\":[{\"id\":\"c1\",\"title\":\"发布\",\"text\":\"灰度失败后按预案回滚\"}],\"deadlineMs\":3000}"'

# 关键指标（需要 JWT；管理端口仅内网）
docker exec ai-collab-devstaging-app-1 curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8081/actuator/prometheus | grep -E '^rerank_'
```

报告口径：
- `worker-overhead`：`rerank.latency{mode="python"}` 减去模型耗时（或网关打桩）的端点开销；
- `worker-e2e`：含真实模型；实测 P50≈2.0s（1.85–2.7s），800ms 门禁 **NOT_MET**；
- `shadow`：`rerank.shadow.topk_overlap`、`rerank.shadow.score_delta`、`rerank.shadow.failure`，
  且**用户可见延迟不变**（影子非阻塞）。

## 4. 灰度顺序

| 步 | mode | 判据 | 回滚 |
| --- | --- | --- | --- |
| 1 | `java` | 基线：检索与问答正常，`rerank_*` 无调用 | — |
| 2 | `shadow` | 用户延迟不变；`shadow.failure` 低；overlap/分数差有记录 | `RERANK_MODE=java` + up -d app |
| 3 | `python`（小流量） | 无请求失败；fallback 可控；引用数不降 | 同上 |
| 4 | `python`（全量） | 仅在延迟预算被接受后；否则保持 shadow | 同上 |

## 5. 合规

- shadow / python 指标只记 `callId`、候选 id、分数、耗时；**不记录 query 原文与候选正文**。
- 服务令牌只在两端环境变量中，不入库、不进日志、不写文档。
- worker 无宿主端口、只挂 `ai` internal 网络；Python 进程无 DB/Provider Key。
