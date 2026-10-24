# P5-DEPLOYMENT-GATE-01 Compose 配置透传验证

> 验证日期：2026-10-24。所有测试值均为合成数据，不含任何真实凭据。

## A. 配置语法校验

```bash
docker compose --env-file .env.example -f ai-collab-deploy/docker-compose.yml config -q
```

结果：**通过**（与 CI image job `Validate compose config` 同命令）。postgres / redis /
minio / app 四个服务均正常解析，无未定义变量异常。

## B. 正向配置验证（合成值）

方式：`ai-collab-deploy/scripts/compose-config-contract.sh` 场景 1——合成基础环境 +
合成覆盖值（`PLANNING_MAX_OUTPUT_TOKENS=7777` 等），渲染 `docker compose config --format json`
后逐键断言 app 环境值与输入一致。

结果：**通过**（8 个新透传变量全部原样到达 app 环境渲染结果）。

## C. 负向配置验证

- 场景 2（仅基础变量）：未设置 Planning/Embedding 变量时，7 个 Planning 变量解析为与
  `application.yml` 一致的默认值（6000/12/16000/0.55/2/4/20）；
  `EMBEDDING_LOCAL_ALLOWED_TARGETS` 默认 `host.docker.internal:11434`（容器内
  127.0.0.1 指容器自身，不能照搬本机测试地址）。**通过**。
- 场景 3（遗留变量）：14 个遗留 `EMBEDDING_*/PLANNING_*` 变量在三种环境
  （合成正向 / 仅基础 / `.env.example`）下均不出现在 app 环境中——
  按任务要求未机械透传无效配置。**通过**。
- 场景 3（安全基线）：`REFRESH_COOKIE_SECURE=true`、`REFRESH_COOKIE_ALLOW_INSECURE=false`
  默认值在三环境下不被覆盖。**通过**。
- 场景 4（特殊字符/URL）：`http://ollama.internal:11434,[::1]:11434` 形态值原样透传。**通过**。

完整输出：`docs/acceptance-evidence/2026-10-24/p5-deployment-gate-01/compose-config-contract-output.log`

## D. local Profile 影响

- 本轮未修改任何 Spring 代码、`application*.yml` 或 local profile 配置；
  Compose 仅新增 app 服务的环境变量条目，local 开发（`dev.ps1` 本机进程方式运行）
  不读取该 Compose 文件，行为不变。
- local HTTP 登录与 Refresh Cookie 安全基线不受影响（本机 local profile 固定非 Secure
  Cookie 逻辑未触碰；容器侧安全默认值由场景 3 断言保护）。

## E. 真实容器验证

| 项目 | 结果 |
| --- | --- |
| Compose 运行时环境变量注入（真实容器） | **通过**：隔离项目 `p5gate01` + 合成 env + override（端口 15432/16379/19000-19001/18080，容器名 gate01-*，项目级隔离卷），`up -d` 拉起后 `docker exec gate01-app printenv` 断言 11 个变量（7 个 Planning 新变量 + GENERATION_LIMIT + EMBEDDING_LOCAL_ALLOWED_TARGETS + REFRESH_COOKIE_*）与输入完全一致，遗留变量不存在。证据：`isolated-container-env.txt`。验证后 `down -v` 已清理全部容器/卷/网络 |
| Spring Boot 应用容器启动 | **NOT_EXECUTED**：本机访问 `auth.docker.io` 失败（Dockerfile 首行 `# syntax=docker/dockerfile:1` 需拉取 frontend 镜像），应用镜像无法本地构建。应用容器启动冒烟由 CI image job 既有步骤在 GitHub runner 覆盖（其中已接入本契约回归）；Spring 配置绑定本身未改动，无新增绑定风险 |
| Embedding/Planning 模型真实调用 | 按任务定义不在本轮范围（上轮已完成真实 Embedding 验收；本轮只验证容器配置链路） |

## F. 与既有 CI 的关系

`.github/workflows/ci.yml` image job 原有 `Validate compose config` 步骤扩展为：
`config -q` + 契约回归脚本。未删除 Backend/Frontend/Image 任一 job，未放宽必需检查，
未改动 Image Job 触发条件，未引入真实凭据。
