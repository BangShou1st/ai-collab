# AI Collab Staging Bring-up（instabox `box-aae54f`）验收报告

- 执行日期：2026-11-12（Asia/Shanghai）
- 仓库基线：`dev` @ `a0515372`（本机 `E:\project\ai-collab`，未 commit / 未 push / 未改代码与默认值）
- 沙箱：`box-aae54f`（4 vCPU / 8 GiB；`/` 16G、`/data` 20G）
- 范围：M1 环境就绪、M2 就绪自证、M3 魔搭模型接通；**未执行任何评测**
- 敏感信息处理：魔搭 Key、密码、JWT、服务令牌均未写入本报告，也未出现在任何命令输出中

---

## 0. 结论速览

| 阶段 | 结论 |
| --- | --- |
| M1 环境就绪 | **PASS**（data-root + containerd root 双迁移、仓库推送、.env、构建起服务） |
| M2 就绪自证 | **PASS**（actuator UP、Flyway V91、worker 网络隔离、日志无 checksum/启动失败） |
| M3 模型接通 | **PASS**（魔搭 /models 200、最小 chat 200、provider create/test/publish、purpose 分配、`/internal/ai/v1/model-calls` 真实响应） |

需要注意的两处**环境事实/预置缺陷**（不影响上述结论，但会影响后续波次）：

1. Docker 29 的镜像存储由 containerd 负责，**只迁 `data-root` 不够**；`/var/lib/containerd`（本次 9.6G）仍在根盘，已额外迁移到 `/data/containerd`。
2. 镜像内 `HEALTHCHECK` 打的是 `8080/actuator/health`，但 `MANAGEMENT_SERVER_PORT=8081`，因此容器 Docker 健康态为 `unhealthy`；`8081/actuator/health` 实际返回 `UP`。

---

## 1. 访问方式（从本机）

沙箱端口未对公网发布，统一用 instabox 的 SSH 端口转发：

```bash
# 业务口 8080（本机 18080 -> 沙箱 8080）
npx -y instabox@latest port-forward box-aae54f 18080:8080

# 管理口 8081（本机 18081 -> 沙箱 8081）
npx -y instabox@latest port-forward box-aae54f 18081:8081
```

实测（本机执行）：

```text
$ npx -y instabox@latest port-forward box-aae54f 18080:8080
Forwarding 127.0.0.1:18080 -> box-aae54f:8080

$ curl -sS http://127.0.0.1:18080/api/v1/auth/registration-policy
{"code":"SUCCESS","message":"操作成功","data":{"enabled":true}}
```

> 备选（未采用）：`npx -y instabox@latest port publish box-aae54f 8080 --private` 会给一个 HTTPS 链接。

沙箱内的 staging 目录与受控凭据（均 600，内容不在此展示）：

- 代码：`/data/home/ai-collab`
- staging 配置：`/data/home/ai-collab/.env`
- 管理员凭据：`/data/.secrets/admin.env`
- 魔搭 Key：`/data/.secrets/modelscope.env`（目录 700 / 文件 600）

---

## 2. M1 环境就绪

### 2.1 Docker data-root 迁移（含 containerd）

实际命令（沙箱内）：

```bash
sudo mkdir -p /data/docker
sudo systemctl stop docker docker.socket
sudo cp -a /var/lib/docker/. /data/docker/
printf '%s\n' '{"data-root": "/data/docker", "log-opts": {"max-size": "50m", "max-file": "3"}}' \
  | sudo tee /etc/docker/daemon.json
sudo systemctl start docker
```

第一次迁移后的实际观测：`Docker Root Dir: /data/docker` 已生效，但根盘仍只剩 0.6G —— 定位到 Docker 29 使用 containerd 镜像存储：

```text
$ sudo du -sh /var/lib/*
9.6G    /var/lib/containerd
209M    /var/lib/docker
$ docker info | grep -iE "Docker Root Dir|Storage Driver|driver-type"
 Storage Driver: overlayfs
  driver-type: io.containerd.snapshotter.v1
 Docker Root Dir: /data/docker
```

二次迁移（containerd root）：

```bash
sudo systemctl stop docker docker.socket
sudo systemctl stop containerd
sudo mkdir -p /data/containerd
sudo cp -a /var/lib/containerd/. /data/containerd/
sudo sed -i 's|^#root = "/var/lib/containerd"|root = "/data/containerd"|' /etc/containerd/config.toml
sudo systemctl start containerd && sudo systemctl start docker
sudo rm -rf /var/lib/containerd /var/lib/docker   # 确认新路径可用、27 个镜像与 13 个容器均正常后执行
```

最终证据：

```text
$ grep -n '^root' /etc/containerd/config.toml
17:root = "/data/containerd"

$ docker info | grep "Docker Root Dir"
 Docker Root Dir: /data/docker

$ df -h / /data
/dev/root        16G  4.7G   11G  32% /
/dev/vdb         20G   14G  6.0G  70% /data
```

结论：**PASS**。构建与运行期间根盘稳定在 32%，未再增长；`/data` 仍有 6.0G 余量。

### 2.2 推送仓库

```bash
# 在 E:\project\ai-collab 执行
MSYS_NO_PATHCONV=1 npx -y instabox@latest push box-aae54f . /data/home/ai-collab
```

```text
Pushed 4488 files (106.7 MiB compressed) to box-aae54f:/data/home/ai-collab
```

> 说明：Git Bash 会把 `/data/...` 转成 Windows 路径，必须加 `MSYS_NO_PATHCONV=1`。

### 2.3 staging `.env`

由仓库根 `.env.example` 生成 `/data/home/ai-collab/.env`（188 行，权限 600），全部密码/密钥用 `openssl rand`：

| 变量 | 取值方式 |
| --- | --- |
| `POSTGRES_PASSWORD` / `REDIS_PASSWORD` / `MINIO_ROOT_PASSWORD` | `openssl rand -hex 18` |
| `JWT_SECRET` | `openssl rand -hex 48`（96 字符，≥32 字节） |
| `MODEL_CONFIG_MASTER_KEY` | `openssl rand -hex 32`（64 字符） |
| `RERANK_SERVICE_TOKEN` | `openssl rand -hex 32` |
| `AUTH_PUBLIC_REGISTRATION_ENABLED` | `true`（bootstrap 后改回 `false`） |
| `REFRESH_COOKIE_SECURE` / `REFRESH_COOKIE_ALLOW_INSECURE` | `false` / `true`（成对） |
| `AUTH_ALLOWED_ORIGINS` / `APP_PUBLIC_URL` | `http://127.0.0.1:8080` |
| `MAIL_ENABLED` | `false` |
| `RERANK_MODE` / `DECIDE_MODE` / `PLANNING_DRAFT_MODE` | `java` |
| `MEMORY_JOB_ENABLED` / `INTERNAL_AI_TOKEN_MODE` | `false` / `static` |
| `RERANK_MODEL_USER_ID` | 首位管理员 UUID（bootstrap 后回填） |
| `AI_WORKER_IMAGE` | `ai-collab-ai-worker:dev` |

### 2.4 构建与启动

```bash
cd /data/home/ai-collab
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --build postgres redis minio app
docker build -t ai-collab-ai-worker:dev ai-collab-ai-worker      # compose 未给 worker 定义 build
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml --profile ai-worker up -d ai-worker-api
```

构建任务 exit code 0；镜像与容器：

```text
ai-collab-backend:local   4d9e97e50050
ai-collab-ai-worker:dev   79673cbd3de0

NAME                      SERVICE         STATUS
ai-collab-ai-worker-api   ai-worker-api   Up (healthy)
ai-collab-app             app             Up (unhealthy — 见 §5 缺陷 2)
ai-collab-minio           minio           Up (healthy)
ai-collab-postgres        postgres        Up (healthy)
ai-collab-redis           redis           Up (healthy)
```

结论：**PASS**。

---

## 3. M2 就绪自证

### 3.1 健康检查

```text
$ docker exec ai-collab-app wget -qO- http://127.0.0.1:8081/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

### 3.2 Flyway V91（原始行）

```text
2026-11-12T02:43:48.355Z INFO ... o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "91 - ai job"
2026-11-12T02:43:48.366Z INFO ... o.f.core.internal.command.DbMigrate - Successfully applied 91 migrations to schema "public", now at version v91 (execution time 00:00.345s)
```

重启后：

```text
2026-11-12T02:49:26.780Z INFO ... o.f.core.internal.command.DbMigrate - Schema "public" is up to date. No migration necessary.
```

`grep -icE "checksum mismatch|APPLICATION FAILED TO START"` → `0`。

### 3.3 worker 网络隔离

```text
$ docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' ai-collab-ai-worker-api
ai-collab-deploy_ai

$ docker port ai-collab-ai-worker-api
（空：无宿主端口映射）

$ curl -sS -m 3 http://127.0.0.1:9100/healthz
curl: (7) Failed to connect ... （宿主不可直连，符合预期）

$ docker exec ai-collab-app wget -qO- http://ai-worker-api:9100/healthz
{"status":"ok","service":"ai-collab-ai-worker","version":"0.1.0","role":"api","schemaVersion":1}
```

结论：**PASS**。

---

## 4. M3 魔搭模型接通

### 4.1 模型可用性（`/v1/models` + 最小 chat）

```text
$ curl -H "Authorization: Bearer <redacted>" $MODELSCOPE_BASE_URL/models
HTTP=200   # 共 38 个模型，示例：Qwen/Qwen3.5-27B、ZhipuAI/GLM-4.7-Flash、deepseek-ai/DeepSeek-V4.1-Flash ...
```

候选模型实测：

| 模型 | 结果 |
| --- | --- |
| `deepseek-ai/DeepSeek-V4.1-Flash` | HTTP 400 `Model id ... has no provider supported` |
| `Qwen/Qwen3.5-27B` | HTTP 200，`content="可用"` |
| `ZhipuAI/GLM-4.7-Flash` | HTTP 200，但 32 token 全被 reasoning 占用（`finish_reason=length`） |

选定：**`Qwen/Qwen3.5-27B`**。

### 4.2 首位管理员（`install.sh bootstrap-admin`）

```bash
# 沙箱内：/data/home/boot/ai-collab-deploy 为 install.sh 的运行目录（脚本原样拷贝，
# compose.local.yml = 仓库 docker-compose.yml 的副本、local.env = staging .env 的副本）
bash install.sh bootstrap-admin --username admin
```

```text
[install] 首位管理员创建成功（本凭据仅显示这一次，不会写入任何日志或文件）：
admin.env: user=admin pass_len=30
```

登录校验（凭据从 `/data/.secrets/admin.env` 读取，未回显）：

```text
login HTTP=200
user_id=4c322e1c-0466-4fc3-a7ed-4d610a702e92 username=admin systemAdmin=True
```

随后把 `AUTH_PUBLIC_REGISTRATION_ENABLED` 改回 `false` 并重启 app，注册接口实测已关闭：

```text
POST /api/v1/auth/register -> HTTP 403
{"code":"REGISTRATION_DISABLED","message":"当前未开放公开注册","data":null}
```

> **与任务的唯一偏离**：`install.sh` 本地模式把入口硬编码为 `http://localhost:<port>`，而本环境
> `AUTH_ALLOWED_ORIGINS=http://127.0.0.1:8080`，会被 `AuthRequestOriginValidator` 精确匹配拒绝（403）。
> 处理方式：只在沙箱内的 `install.sh` 副本把该 host 由 `localhost` 改为 `127.0.0.1`（仓库文件未动），
> 其余逻辑原样执行。

### 4.3 provider 配置 / 测试 / 发布

```text
POST /api/v1/admin/ai/providers
create HTTP=200 code=SUCCESS
provider id=8839415b-226a-4126-8a4b-b37aff46493e
name=ModelScope-Qwen3.5-27B model=Qwen/Qwen3.5-27B type=OPENAI_COMPATIBLE
baseUrl=https://api-inference.modelscope.cn apiPath=/v1/chat/completions
hasApiKey=True enabled=True isShared=False status=UNPUBLISHED caps=['CHAT','STREAMING']

POST /api/v1/admin/ai/providers/{id}/test
test HTTP=200 code=SUCCESS
test content='连接成功'
test usage promptTokens=29 completionTokens=808 model=Qwen/Qwen3.5-27B provider=OPENAI_COMPATIBLE latencyMs=24297

POST /api/v1/admin/ai/providers/{id}/publish
publish HTTP=200 code=SUCCESS isShared=True status=PUBLISHED
publishedAt=2026-11-12T02:53:20.952488Z
```

### 4.4 purpose → 模型解析路径

实际运行时解析链（代码事实）：

```text
ChatCompletionCommand(purpose=KNOWLEDGE_CHAT)
  -> RoutingChatModelGateway.resolveProvider()
  -> UserAiProviderService.resolve(callerUserId, purpose)
       -> UserAiProviderRepository.findAssigned(userId, purpose)      // user_model_purpose_assignment
       -> 未命中则 findDefaultByUserId(userId)
  -> is_shared=true 时取 shared_ai_provider（发布态校验）-> toRuntimeUserAiProvider()
  -> OpenAiCompatibleModelAdapter（baseUrl + apiPath + modelName）
```

`/internal/ai/v1/model-calls` 的调用者身份来自 `rerank.model-user-id`（env `RERANK_MODEL_USER_ID`），
本次回填为首位管理员 UUID。

三张"档案类"表的实际角色（本次实测）：

| 表 | 本次取值 | 作用 |
| --- | --- | --- |
| `model_configuration` | 0 行 | 历史遗留，Java 运行时代码无引用 |
| `model_purpose_assignment` | 0 行 | 历史遗留，Java 运行时代码无引用 |
| `ai_model_profile` | 0 行 | 全局模型能力档案（上下文窗口等元数据，按 provider_type+endpoint+model 匹配），不参与 provider 选择 |
| `user_model_purpose_assignment` | 1 行（见下） | **真实生效**的 purpose 绑定 |

分配（走用户接口，使用发布后的共享 provider id）：

```text
PUT /api/v1/user/ai-providers/purposes/KNOWLEDGE_CHAT  {"providerId":"71386dc5-8e87-4890-ad3c-a770f67637f4"}
HTTP=200 {"code":"SUCCESS"}

GET /api/v1/user/ai-providers/purposes
{"KNOWLEDGE_CHAT":"71386dc5-8e87-4890-ad3c-a770f67637f4"}

user_model_purpose_assignment:
 user_id=4c322e1c-0466-4fc3-a7ed-4d610a702e92 | KNOWLEDGE_CHAT | 71386dc5-...-a770f67637f4 | is_shared=t
```

### 4.5 真实模型调用闭环（成功判据）

```bash
curl -X POST http://127.0.0.1:8080/internal/ai/v1/model-calls \
  -H "Authorization: Bearer <RERANK_SERVICE_TOKEN>" -H 'Content-Type: application/json' \
  -d '{"schemaVersion":1,"callId":"staging-m3-<ts>","purpose":"KNOWLEDGE_CHAT",
       "systemPrompt":"你是 staging 健康检查助手。",
       "userPrompt":"用一句话说明你正在通过哪个模型回答。",
       "outputFormat":"TEXT","deadlineMs":30000}'
```

```text
model-call HTTP=200
schemaVersion= 1
content= '我正在通过通义千问 Qwen3.5 模型回答您的问题。'
usage= {'inputTokens': 32, 'outputTokens': 899, 'source': 'PROVIDER'}
modelAlias= Qwen/Qwen3.5-27B latencyMs= 12890
error= None
```

结论：**PASS** —— 响应含 provider 返回的真实文本与 usage，且 `modelAlias` 与魔搭模型一致。

---

## 5. 发现的问题 / 卡点与已试方案

### 卡点 1：Git Bash 路径转换导致 push 失败

- 现象：`error missing_parent: directory /data/home/E:/Git/data/home does not exist`
- 方案：`MSYS_NO_PATHCONV=1`（1 种即解决）

### 卡点 2：data-root 迁移后根盘仍告急

- 现象：`Docker Root Dir` 已是 `/data/docker`，但根盘仅剩 0.6G
- 定位：Docker 29 使用 containerd 镜像存储，`/var/lib/containerd` 9.6G
- 方案：迁移 containerd `root` 到 `/data/containerd`，验证后再删旧目录（共 2 种方案）

### 卡点 3：`install.sh bootstrap-admin` 注册 403

- 现象：`AUTH_FORBIDDEN / 没有权限执行此操作`
- 定位：`AuthRequestOriginValidator` 对携带 Origin 的认证请求做**精确白名单**匹配；`install.sh`
  固定发 `Origin: http://localhost:8080`，而 `AUTH_ALLOWED_ORIGINS=http://127.0.0.1:8080`
- 已试：`localhost` 与 `127.0.0.1` 交叉对比（确认是 Origin 精确匹配，不是 CORS 预检）
- 方案：沙箱内 install.sh 副本改为 `127.0.0.1`（仓库未改）

### 卡点 4：DeepSeek 模型在该渠道不可用

- `deepseek-ai/DeepSeek-V4.1-Flash` → 400 `has no provider supported`
- 方案：改用 `Qwen/Qwen3.5-27B`（1 种）

### 预置缺陷 1：Docker HEALTHCHECK 端口与 actuator 端口不一致

- 镜像 `HEALTHCHECK` 打 `8080/actuator/health`，实际 404；actuator 在 `8081`
- 结果：`ai-collab-app` 的 Docker 健康态恒为 `unhealthy`（`FailingStreak=10`，最后输出 `HTTP/1.1 404`）
- 影响：`/actuator/health` 本身 UP；但任何依赖 Docker health 的编排会误判
- 建议（未实施，属仓库改动）：`Dockerfile` 健康检查改 8081，或 compose 设 `MANAGEMENT_SERVER_PORT=8080`

### 预置缺陷 2：注册开关读两套配置源

- `GET /api/v1/auth/registration-policy` 读 DB `system_configuration.public_registration_enabled`（当前 `t`），
  返回 `enabled=true`
- `POST /api/v1/auth/register` 读 env `auth.public-registration.enabled`（已改 `false`），返回
  `REGISTRATION_DISABLED`
- 即：**注册实际已关闭**，但策略端点仍显示开放（两处判定源不一致，属代码缺陷，未改动）

### 其他副作用

- 排障期间通过注册接口创建了普通用户 `probeuser`（`systemAdmin=false`），未删除，供后续排查参考。
- compose 对未在 `.env` 显式声明的 `CHAT_API_KEY` / `CHAT_BASE_URL` / `CHAT_MODEL` 给出 blank 警告，不影响启动。

---

## 6. 验收表（中心会话按此核对）

| 检查项 | 结果 | 证据（命令/日志片段） | 证据等级 |
| --- | --- | --- | --- |
| Docker data-root 迁移 | PASS | `docker info` → `Docker Root Dir: /data/docker`；`/etc/containerd/config.toml:17 root = "/data/containerd"`；`df -h /` 32%，`/data` 余 6.0G | A（直接命令输出） |
| 仓库推送 | PASS | `Pushed 4488 files (106.7 MiB compressed) to box-aae54f:/data/home/ai-collab` | A |
| compose 起服务 | PASS | `docker compose ... ps`：app / postgres / redis / minio / ai-worker-api 全部 Up；镜像 `ai-collab-backend:local`、`ai-collab-ai-worker:dev` | A |
| 健康检查 | PASS（附注） | `docker exec ai-collab-app wget -qO- http://127.0.0.1:8081/actuator/health` → `{"status":"UP"}`；**Docker 健康态为 unhealthy**（镜像 HEALTHCHECK 打 8080/actuator/health 返回 404，见 §5 缺陷 1） | A |
| Flyway V91 | PASS | `Successfully applied 91 migrations to schema "public", now at version v91 (execution time 00:00.345s)`；重启后 `Schema "public" is up to date. No migration necessary.` | A |
| worker 网络隔离 | PASS | `docker inspect` 网络仅 `ai-collab-deploy_ai`；`docker port` 为空；宿主 `curl 127.0.0.1:9100` 拒绝；app 内 `http://ai-worker-api:9100/healthz` → 200 `{"status":"ok",...}` | A |
| provider 配置 | PASS | `POST /api/v1/admin/ai/providers` → `SUCCESS`，id `8839415b-...`，`hasApiKey=true`；`/test` → `SUCCESS`，`content="连接成功"`，usage 29/808；`/publish` → `isShared=true, status=PUBLISHED` | A |
| purpose 解析 | PASS | `GET /api/v1/user/ai-providers/purposes` → `{"KNOWLEDGE_CHAT":"71386dc5-..."}`；DB `user_model_purpose_assignment` 行 `is_shared=t`；`model_configuration` / `model_purpose_assignment` / `ai_model_profile` 均 0 行（非本次链路） | A |
| 真实模型调用闭环 | PASS | `POST /internal/ai/v1/model-calls` → HTTP 200，`content='我正在通过通义千问 Qwen3.5 模型回答您的问题。'`，`usage={'inputTokens':32,'outputTokens':899,'source':'PROVIDER'}`，`modelAlias=Qwen/Qwen3.5-27B` | A |
| 魔搭 Key / 模型可用性 | PASS | `GET /v1/models` HTTP 200（38 个模型）；最小 chat `Qwen/Qwen3.5-27B` → HTTP 200 `content="可用"` | A |
| 首位管理员 | PASS | `install.sh bootstrap-admin` → `首位管理员创建成功`；登录 `systemAdmin=True`；注册关闭后 `/register` → `REGISTRATION_DISABLED` | A |
| 未跑评测 | PASS | 本次未执行任何 eval / 测量命令 | A（执行日志） |

> 证据等级定义：A = 本次会话可复现的直接命令/日志输出；B = 间接推断；C = 未验证。
> 本次所有结论均为 A 级；不含 stub 或伪造结果。

---

## 7. 环境清单（留档）

- 容器：`ai-collab-app`(8080)、`ai-collab-postgres`(5432)、`ai-collab-redis`(6379)、`ai-collab-minio`(9000/9001)、`ai-collab-ai-worker-api`(9100，仅 ai 网络)
- 镜像：`ai-collab-backend:local` / `4d9e97e50050`，`ai-collab-ai-worker:dev` / `79673cbd3de0`
- 网络：`ai-collab-deploy_default`、`ai-collab-deploy_ai`（internal）
- 数据卷：`ai_collab_postgres_data`、`ai_collab_redis_data`、`ai_collab_minio_data`
- 迁移版本：Flyway v91
- 模型：`Qwen/Qwen3.5-27B`（ModelScope，OpenAI 兼容，base `https://api-inference.modelscope.cn`，path `/v1/chat/completions`）
- 未变更项：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`
