# W3 rerank staging 灰度 runbook（dev-staging）

> 环境：instabox `box-aae54f`，compose 项目 `ai-collab-devstaging`，公网入口
> `https://9443-agqyc3kteqgazwefpmhwulu.instabox.tech`（宿主侧 `127.0.0.1:9443`）。
> 本文件只写操作与判据；**任何 token/密钥只经 `config/.env` 或环境变量传递，不写入本文件**。
> 2026-11-12 已按本文件实际执行过一次（W3 canary），结果见
> [`j3-rerank-20261112.md`](../report/j3-rerank-20261112.md) §7.4–§7.5。

## 1. 环境事实

| 项 | 值 |
| --- | --- |
| compose | `/data/workspace/ai-collab-devstaging/dev-staging.yml`（项目名 `ai-collab-devstaging`） |
| env | `/data/workspace/ai-collab-devstaging/config/.env`（CRLF 文件，脚本里先 `tr -d '\r'`） |
| 源码 | `/data/workspace/ai-collab-devstaging/repo`（W3 提交 `ee6ea7af`） |
| app | `ai-collab-backend:w3`，容器 `ai-collab-devstaging-app-1`，管理端口 8081 仅内网 |
| worker | `ai-collab-ai-worker:w3`，容器 `ai-collab-devstaging-ai-worker-api`，只挂 `ai` internal 网、无宿主端口 |
| DB | 容器内 `psql -U ai_collab -d ai_collab`；W3 无新迁移，schema 停在 **V90**（J2） |
| 模型 | probe 用户（`049056aa-…`）的 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`，KNOWLEDGE_CHAT 已绑定 |
| 流量 | 过去 24h 仅 2 条请求；canary 用 Agent 会话合成流量，**不是真实用户** |

## 2. 部署 W3

```bash
cd /data/workspace/ai-collab-devstaging/repo
git fetch origin dev && git reset --hard origin/dev
cd ai-collab-backend && bash ./mvnw -B -ntp -DskipTests package
cp target/ai-collab-backend-0.0.1-SNAPSHOT.jar ../img/app.jar
cd ../img && docker build -t ai-collab-backend:w3 .
cd /data/workspace/ai-collab-devstaging/repo
docker build -t ai-collab-ai-worker:w3 ai-collab-ai-worker
```

`.env` 需要新增（值不提交、不打印）：

```dotenv
APP_IMAGE=ai-collab-backend:w3
AI_WORKER_IMAGE=ai-collab-ai-worker:w3
RERANK_MODE=java                 # java|shadow|python
RERANK_SERVICE_TOKEN=<随机 32+ 位，app 与 worker 同值>
RERANK_MODEL_USER_ID=049056aa-63b7-4932-b2d3-3546db122b0f
EMBEDDING_LOCAL_ALLOWED_TARGETS=ai-collab-devstaging-embedding:11434,127.0.0.1:11434,localhost:11434
```

```bash
cd /data/workspace/ai-collab-devstaging
docker compose -p ai-collab-devstaging --env-file config/.env -f dev-staging.yml \
  --profile ai-worker up -d --force-recreate app ai-worker-api
```

> 切模式只需改 `RERANK_MODE` 再 `up -d --force-recreate app`（约 15s）。

## 3. 冒烟

```bash
# 1) worker 就绪
docker exec ai-collab-devstaging-app-1 sh -c \
  'curl -s -o /dev/null -w "%{http_code}\n" http://ai-worker-api:9100/readyz'

# 2) 内部网关（worker → app，带服务令牌）——应 200 且 usage.source=PROVIDER
docker exec ai-collab-devstaging-ai-worker-api python -c "
import os,urllib.request,json
t=os.environ['AI_WORKER_SERVICE_TOKEN']
r=urllib.request.Request('http://app:8080/internal/ai/v1/model-calls',
 data=json.dumps({'schemaVersion':1,'purpose':'KNOWLEDGE_CHAT','systemPrompt':'test',
 'userPrompt':'ok','outputFormat':'PROMPT_JSON','deadlineMs':15000,'callId':'smoke-1'}).encode(),
 headers={'Content-Type':'application/json','Authorization':'Bearer '+t})
print(urllib.request.urlopen(r,timeout=30).status)"

# 3) 指标
docker exec ai-collab-devstaging-app-1 curl -s http://127.0.0.1:8081/actuator/prometheus | grep '^rerank_'
```

## 4. 嵌入模型（仅 staging：测试替身）

staging 无可用 Embedding Provider；为打通文档链路部署了**确定性哈希 bag-of-words 嵌入**
（768 维、OLLAMA 协议、`/v1/embeddings`、仅内网）。配置方式（probe 需 system_admin）：

```bash
curl -X PUT http://127.0.0.1:9443/api/v1/admin/embedding-config \
  -H "Authorization: Bearer <JWT>" -H 'Content-Type: application/json' \
  -d '{"provider":"OLLAMA","baseUrl":"http://ai-collab-devstaging-embedding:11434",
       "apiPath":"/v1/embeddings","apiKey":null,"modelName":"test-embed",
       "dimensions":768,"batchSize":8}'
```

> **这是测试替身，不是语义模型**：staging 的检索质量/引用率不代表生产。质量评测请用
> 冻结集 + 真实 rerank 模型（`ai-eval rerank --scorer worker`），不要用 staging 检索结果。

## 5. 触发 rerank 的真实路径

知识问答（`KnowledgeQuestionApplicationService`）走 `DENSE_LEGACY`，**不经过 rerank**。
只有 Agent 的 `search_project_knowledge`（RAG_V2）会进检索管线：

```bash
# 见 /tmp/agent-canary.sh（并行 4 个 Agent 会话，统计用户可见延迟）
ACCESS_TOKEN=<JWT> BASE_URL=http://127.0.0.1:9443 \
PROJECT_ID=<uuid> COUNT=4 OUT=/tmp/agent-java.jsonl bash /tmp/agent-canary.sh
```

单次 Agent 流程约 12–20s（模型轮次为主）；rerank 成本看 `rerank_latency{mode}`，不要看端到端。

## 6. 判据与实测（2026-11-12）

| 模式 | 判据 | 实测 | 结论 |
| --- | --- | --- | --- |
| `java` | 与迁移前逐字段一致、零外呼 | 4 次调用，<1ms/次 | PASS |
| `shadow` | 用户延迟不变；overlap/failure 有记录 | 可见 1.5ms、后台 2.17s、top-8 重合 1.0、2 成功 1 超时 | PASS（样本小） |
| `python` | 不失败；失败回退；延迟可接受 | 1 超时→3.0s 回退；成功一次约 5.8s（修复前 504 重试为 9s） | 回退 PASS；**延迟不达标** |
| P95 ≤800ms | 真实模型 e2e | **5054ms** | NOT_MET |

## 7. 回滚

```bash
sed -i 's/^RERANK_MODE=.*/RERANK_MODE=java/' config/.env
docker compose -p ai-collab-devstaging --env-file config/.env -f dev-staging.yml \
  up -d --force-recreate app
```

- **不能**回退到 J1 镜像（不认识 V90，Flyway 校验失败）；回滚镜像必须是含 V90 的 W3 版本。
- worker 故障：shadow 只记失败；python 单次 deadline 后回退；java 完全不受影响。

## 8. 合规

- 指标只记 callId/候选 id/分数/耗时；不记录 query 原文与候选正文。
- 服务令牌只在两端环境变量；worker 无宿主端口、只挂 `ai` internal 网；Python 无 DB/Provider Key。
