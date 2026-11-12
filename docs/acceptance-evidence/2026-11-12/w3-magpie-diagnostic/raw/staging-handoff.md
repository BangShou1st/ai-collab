# C 泳道 staging 复用口径

> 交接时间：2026-11-12T10:57Z
> 服务器：`box-aae54f`（个人服务器，不是生产）
> B 泳道状态：已释放；C 可以使用 staging。
> 本文件只写路径、变量名和命令，不写任何 secret 值。

---

## 1. 当前 staging 状态

| 项 | 值 |
| --- | --- |
| compose 项目 | `ai-collab-deploy` |
| base compose | `/data/home/ai-collab/ai-collab-deploy/docker-compose.yml` |
| 当前 override | `/data/home/w3s2/compose.final.yml`（容器 labels 实测） |
| app 容器 | `ai-collab-app`，镜像 `ai-collab-backend:local` |
| worker 容器 | `ai-collab-ai-worker-api`，镜像 `ai-collab-ai-worker:dev` |
| 容器网络 | `ai-collab-deploy_default`、`ai-collab-deploy_ai` |
| worker 网络 | 仅在 `ai-collab-deploy_ai`，无宿主端口 |
| 当前健康 | app `running/healthy`；worker `running/healthy`（见 `defaults-runtime.txt`） |
| magpie/B 临时服务 | 已停止，3425 无监听，3425 HTTPS 已 unpublish |
| 既有 8443 / 9443 | 未动，仍属于既有 staging，不要改 |

**重要**：`/data/home/ai-collab` 是部署文件树，**不是 git checkout**（服务器上 `git rev-parse` 返回 not a git repository）。C 的代码必须由中心从已冻结提交同步成新文件树/镜像；不要在该目录执行 `git pull`，也不要把凭据或证据文件放进部署目录。

---

## 2. 代码镜像重建与部署

只在 `ai-collab-deploy` 项目操作；不要操作 `ai-collab-staging`、`ai-collab-devstaging`、`spec-agent` 等其他 compose 项目，也不要删除既有卷。

同步代码后，在服务器执行：

```bash
cd /data/home/ai-collab

# backend：base compose 的 build context 是仓库根
docker build -f ai-collab-backend/Dockerfile -t ai-collab-backend:local .

# worker：compose 只声明 image，不在 compose 内 build
docker build -t ai-collab-ai-worker:dev ai-collab-ai-worker

# 只重建/重启目标服务；基础设施复用现状
docker compose \
  --env-file .env \
  -f ai-collab-deploy/docker-compose.yml \
  -f /data/home/w3s2/compose.final.yml \
  --profile ai-worker \
  up -d --no-deps app ai-worker-api
```

部署后检查：

```bash
docker inspect ai-collab-app ai-collab-ai-worker-api \
  --format '{{.Name}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
docker exec ai-collab-app wget -qO- http://127.0.0.1:8081/actuator/health
docker exec ai-collab-app wget -qO- http://ai-worker-api:9100/healthz
```

如果只改 Python worker，可只重建 worker 并重启 `ai-worker-api`；如果只改 Java W4 代码，可只重建 backend 并重启 `app`。不要用 `down -v`。

---

## 3. worker 访问与 token 注入

- 从服务器宿主机不能直连 `127.0.0.1:9100`（worker 没有宿主端口）。
- 从 app 容器或临时容器（必须加入 `ai-collab-deploy_ai`）访问：`http://ai-worker-api:9100`。
- token 只从现有 secret 文件读取：
  - 文件：`/data/.secrets/worker.env`（0600）
  - 变量名：`AI_WORKER_SERVICE_TOKEN`
  - 不要使用命令行 `--token` 直传，不要把值写入命令、日志或报告。
- 管理 API 需要临时登录时只从 `/data/.secrets/admin.env` 读取；可用变量名是 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 和（可能已过期的）`ADMIN_ACCESS_TOKEN`。不要把 token 值写入命令历史或证据。
- 推荐在临时 worker 容器中运行评测，保持代码与 worker 镜像一致：

```bash
docker run --rm \
  --network ai-collab-deploy_ai \
  --user 0 \
  -v /data/home/ai-collab:/repo:ro \
  -v /data/home/w4-eval:/out \
  --env-file /data/.secrets/worker.env \
  --entrypoint /app/.venv/bin/python \
  ai-collab-ai-worker:dev \
  -m ai_collab_worker.eval.cli decide \
  /repo/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl \
  --scorer worker \
  --worker-url http://ai-worker-api:9100 \
  --service-token-env AI_WORKER_SERVICE_TOKEN \
  --evidence "LIVE_VERIFIED (staging, real model)" \
  --json --output /out/aggregate-decide.json
```

若容器内提供 `ai-eval` 入口，也可等价使用：

```bash
ai-eval decide <冻结集> \
  --scorer worker \
  --worker-url http://ai-worker-api:9100 \
  --service-token-env AI_WORKER_SERVICE_TOKEN \
  --evidence "LIVE_VERIFIED (staging, real model)" \
  --json --output <受控输出路径>
```

`WorkerScorer` 默认读取 `AI_WORKER_SERVICE_TOKEN`；`--service-token-env` 只是显式写默认变量名。禁止把 token 值回显或用 `curl -H "Authorization: Bearer <literal>"` 写进日志。

---

## 4. 允许的测试 profile

- 保持生产默认：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。
- C 的真实评测只允许直接调用 worker 的 `/v1/agent/decide`（`ai-eval --scorer worker`）；**不把 `DECIDE_MODE` 切到 `shadow`/`python`**，不改 `python` 生产路径。
- 测试容器可以加入 `ai-collab-deploy_ai`；不能给 worker 发布宿主端口，不能把 worker 接入 `data` 网络。
- 不修改 `KNOWLEDGE_CHAT`、`PLANNING`、`MEMORY_JOB_ENABLED`。如果 C 确实需要替换模型，只允许临时切换 `AGENT` purpose，并在评测后恢复；切换前后都要保存 provider/purpose 快照。
- 不新增迁移；若确需迁移只允许 `V92+`，并在真实 staging 前单独说明。
- 每次真实模型运行必须显式标 `LIVE_VERIFIED (staging, real model)`；基础设施失败单列，不得删样本或放宽冻结门禁。

---

## 5. 当前 AGENT purpose

实测只读结果（见 `raw/purposes-c-handoff.json`、`raw/providers-c-handoff.json`）：

| purpose | provider id | provider | model |
| --- | --- | --- | --- |
| `AGENT` | `0f58581a-dbba-4e26-bafa-2e137f650563` | `Gemini-3.5-Flash-Lite` | `gemini-3.5-flash-lite` |
| `KNOWLEDGE_CHAT` | `0f58581a-dbba-4e26-bafa-2e137f650563` | `Gemini-3.5-Flash-Lite` | `gemini-3.5-flash-lite` |
| `PLANNING` | `0f58581a-dbba-4e26-bafa-2e137f650563` | `Gemini-3.5-Flash-Lite` | `gemini-3.5-flash-lite` |

provider `0f58581a-...` 为 `enabled=true`、`sharedStatus=PUBLISHED`。这是 C 开始测试时的现状，不是生产灰度声明。

---

## 6. 交接确认

B 已停止 magpie serve，3425 无监听，HTTPS 3425 已 unpublish；没有占用 staging 的数据库、Redis、MinIO 或 worker 资源。现有 app/worker 健康。**C 可以使用 staging；开始前仍应自行读取本文件与冻结契约，并按泳道停止条件执行。**
