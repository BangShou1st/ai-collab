# 部署与恢复说明

2026-10-05：本轮授权仅开发分支备份推送，验收写入仅隔离副本；不包含部署或正式迁移。最新稳定性剩余缺陷修复复验见 [agent-stability-remainder 报告](acceptance-evidence/2026-10-04/agent-stability-remainder/report.md)，隔离验收入口与停止/恢复约定不变。新增说明：本轮沿用独立容器 `ai-collab-rootfix-pg/redis/minio`（端口 55432/16379/18090，复用隔离卷 `ai-collab-acceptance-20261003`），Flyway 已在隔离副本应用至 V57（`agent_usage_settlement` 孤儿用量结算账本，run_id+call_id 唯一幂等）；复验脚本为 `run-agent-stability-remainder.ps1`（批次 `stability-minimal/fixed24/correction`，证据在 `2026-10-04/agent-stability-remainder/<batch>`），旧 `run-root-cause-repair.ps1`、`run-long-quality.ps1` 与旧证据未改动。宿主分离启动脚本 `start-real-acceptance-host.ps1`（先拉起异常退出的隔离容器再启动宿主）。V57 仅应用隔离副本，正式库迁移另行授权；V56 将历史 actual 用 used 回填属历史下界，与 V57 新增结算行分开解读。

## 隔离验收入口

`AcceptanceDatabaseSupport` 必须显式设置 AI_UPGRADE_REHEARSAL=true，只允许 localhost:55432 的 .env 所指数据库；原业务容器保持停止。真实模型宿主另需 AI_REAL_ACCEPTANCE=true，执行 RealAcceptanceHostTest，使用现有合法配置的 space-bunny-free；停止标记为 ai-collab-backend/target/real-acceptance.stop。每次启动将修改隔离副本模型选择，正常停止 finally 恢复；进程硬杀不能依赖 finally，必须事先保存私有配置快照。

四类质量复验使用 `run-long-quality.ps1`，新批次记录在 `2026-10-04/long-quality-fix`，不覆盖旧 reliability。`fixed24-final` 重建原空项目和同字节蓝图，同一新会话顺序执行原措辞。FAILED_RETRYABLE 仍是后台待恢复状态，必须等待原 run 终态后再提交下一轮；Recover 只重读，不重复发送。宿主专用登录阈值 200 不改变生产限流。额外 `AI_LONG_QUALITY_CAPTURE=true` 才记录实际模型输入/输出（含摘要）到私有 target JSONL，不得提交或直接复制为公开证据；公开证据只保存脱敏范围、usage、判断依据与必要测试业务内容。

故障宿主另需 AI_RELIABILITY_ACCEPTANCE=true，执行 ReliabilityAcceptanceHostTest。它仅允许 127.0.0.1:18082 的测试模型 HTTP 端点，模型输出是受控脚本，不是提供商质量验收。控制文件 ai-collab-backend/target/reliability-fault-control.json 的 queueHold/mode 控制排队与详情响应；停止标记 ai-collab-backend/target/reliability-fault.stop。凭据、配置备份与原始日志仅放 target，不进 Git。

受控端点启动命令为 `node docs/acceptance-evidence/reliability-model-peer.mjs ai-collab-backend/target/reliability-fault-control.json docs/acceptance-evidence/2026-10-04/reliability/fault-peer.jsonl`。NORMAL 返回固定合法骨架/详情；HOLD_DETAIL 在控制文件改为 NORMAL 后释放；TIMEOUT_DETAIL 始终不响应，直到真实网关 3 分钟超时断开。初始 queueHold=true 时执行器真正排队，重启改 false 恢复。不能回写数据库时间冒充重启恢复；运行中断按真实 10 分钟 stale 边界结束。登录 token 由 get-acceptance-auth.ps1 缓存在私有 target；仅故障宿主登录阈值 200，生产限流不变。新宿主首次演练前必须存在可靠的私有 provider 备份文件，且不能和真实模型宿主同时运行。

## 功能关闭与兼容

agent.enabled=false 关闭 Agent；agent.capabilities.planning=false 关闭规划工具；agent.capabilities.document-reading=false 关闭正文工具。agent.context.composer-v2=false 回退上下文组装，已有 working_state 可兼容读取（v2 渲染两条路径共用；约束 detail.object 为新增字段，旧条目从 value 前缀渐进解析，回退路径无需迁移）。预算收尾语义（2026-10-05 起）：收尾由收敛策略触发时，若当前目标确定性要求核心动作（如规划生成的 start_task_plan）且持久工具结果显示未发生，运行以 BUDGET_EXCEEDED（事件 scope=CORE_ACTION_NOT_PERFORMED）部分完成收场，不以模型文字记成功；事件 MODEL_STARTED 携带 inputBreakdown 供体积审计。此次可靠性修复没有新增迁移，不回退 V54/V55。规划的排队操作保留同一 operation/attempt；运行中进程丢失允许明确失败后由用户对原规划重试，不恢复半截模型输出。正式确认仍只通过原规划服务对指定版本人工执行。

纯文本规划与原生收尾请求不允许模型调用工具；Zen 兼容传输保留保留工具声明，但明确 `tool_choice=none`。解析和业务校验保持严格，失败 attempt 保存阶段/原因，不保存完整提供商正文。零版本骨架失败可在原规划页重新生成；原操作没有结果版本时显示尚未生成，不能借用后来重试的版本。预算预留同时覆盖主请求、摘要和最终回答；保底片段保留已取得信息，但不记为成功。

## 隔离副本停止与恢复

停止前保存运行/操作/版本与数据库不变量证据。正常停止宿主后核对监听消失，硬杀实验只终止已经核对为验收 JVM 的 18080 监听进程。恢复模型配置时使用验收前私有快照，恢复目的配置、默认标记和配置修改时间；测试提供商须禁用，历史引用保留。停止本轮转发器、模型端点、Ollama 及隔离容器，保留卷。不得启动原业务容器，也不得用隔离副本覆盖原库。

本次停止后执行 `docs/acceptance-evidence/restore-reliability-config.ps1`，它拒绝在 18080 仍监听时恢复，使用事务恢复固定副本并逐行断言配置/用途/修改时间。assert-reliability-database.ps1 读取固定隔离容器，核对原操作唯一、版本数量、历史草稿 hash、终态无 active attempt、零正式任务及确认；相关 API 重读用 run-reliability-fault-api.ps1。专项回归设置 AI_RELIABILITY_ASSERTIONS=true 与 AI_UPGRADE_REHEARSAL=true，执行 ReliabilityOperationDatabaseTest，复用本次私有 fault-state 文件。重跑新的实验应保留旧状态与证据，再创建新会话；脚本拒绝重发已经提交的轮次/案例，Recover 只读取原 run。此次没有新增迁移，操作错误从不可变 attempt 读取，旧误分类取消可兼容纠正。详情重试保留 generation_seq，原卡片固定原详情的状态与失败原因，最新规划状态在规划页查看。

正式部署另行决定：先核对正式 Flyway 版本和未执行编号，备份数据库与对象存储，在另一副本实际恢复并断言，再形成确定启动配置和迁移步骤。本轮隔离库 V55 不代表正式库版本。


### 2026-10-04 四类长对话修复的部署边界

本轮仅备份开发分支，不执行部署。真实固定场景仍存在阻断项，详见 [专项报告](acceptance-evidence/2026-10-04/long-quality-fix/report.md)。不要依据运行计数恰好等于预算上限认定实际 usage 没有超支，应核对步骤及摘要原始 usage 的合计。助手历史全文保留并标记未核验来源；摘要的现行目标、最新请求和有效约束作为有界输入计入预算，不删除历史或未决工具配对。完整固定批次使用 `fixed24-final2`，此前批次保留失败，不覆盖。

## MinIO 服务端镜像（2026-10-09 收口）

### 换镜像的原因（本轮实测，非转述历史结论）

原 `ai-collab-deploy/docker-compose.yml` 固定 `quay.io/minio/minio:latest`。该引用**当前不可用**，
在无 registry 凭据的干净环境下实测：

| 引用 | 本轮实测结果 | 归因 |
| --- | --- | --- |
| `quay.io/minio/minio:latest` | `401 UNAUTHORIZED`（HEAD manifest） | 仓库访问策略：匿名不可拉取，不是本机网络故障 |
| `minio/minio:latest`（Docker Hub） | `pull access denied ... repository does not exist` | 仓库已不存在；同一时刻 `hello-world` 拉取成功，排除网络问题 |
| `cgr.dev/chainguard/minio:latest` | 拉取成功，`linux/amd64`、`linux/arm64` | 可用 |

`401` 出现在 quay 的 `/v2/` ping 上时是 OCI registry 的正常令牌协商，**不能据此判断镜像可拉取**；
判断依据必须是真实 `docker pull` 的结果。MinIO 官方 release 说明已改为
"for container environments, please clone the source and build the latest container"，
即官方不再发布可直接匿名拉取的服务端镜像。因此本项目不自建镜像平台，也不迁移存储产品。

### 采用的镜像与更新方式

- `ai-collab-deploy/docker-compose.yml` 的 minio 服务改为
  `cgr.dev/chainguard/minio:latest@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18`。
  该 digest 对应上游 `minio/minio` 的 `RELEASE.2026-09-22T19-25-18Z`
  （构建 commit `df34868a`，可在 github.com/minio/minio 查到），是**同一服务端**而非替代品。
- Chainguard 免费层只发布 `latest` 标签（`2026-09-22`、`RELEASE.*` 等 tag 均返回
  `MANIFEST_UNKNOWN`），因此版本只能靠 **digest** 固定。
- **digest 更新方式**：`docker buildx imagetools inspect cgr.dev/chainguard/minio:latest`
  或 `docker manifest inspect cgr.dev/chainguard/minio:latest` 取当前 index digest，
  连同其 `RELEASE.*` 版本串一并记录后替换 compose 中的 `@sha256:...`。digest 被回收导致拉取失败时，
  按同法取新 digest；**不要**改用不带 digest 的 `latest` 部署。
- 运行用户为非 root（uid/gid 65532），数据目录 `/data` 可写；compose 新增
  `/minio/health/ready` 语义的健康检查（`mc ready local`），与 postgres/redis 的健康检查风格一致。
- `ai-collab-backend` 的 `MinioTestImage` 仍**独立**固定同一镜像 digest，不反向依赖 compose；
  两者只共享"镜像来源"这一事实，测试不读部署配置。

### 隔离验证结论（2026-10-09）

独立容器 + 独立临时卷 + 非冲突端口（19000/19001），未启动整个业务 compose，
未挂载任何真实业务卷，未删除任何既有镜像、容器或卷。实测通过：镜像可拉取、
`/minio/health/ready` 返回 200、9001 控制台返回 200、匿名访问被拒、错误凭据被拒、
测试凭据可建桶/上传/下载（字节一致）、删除容器后用同一卷重建后桶与对象内容不变。

### 回退边界

- 改动只涉及镜像引用与一条健康检查，**未改**端口映射（9000/9001）、凭据变量名
  （`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`）、数据卷名（`ai_collab_minio_data`）、命令语义
  （`server /data --console-address ":9001"`）与服务依赖关系。
- 回退方式：把 minio 的 `image` 改回原值即可；但原值当前**拉不到**，回退后服务将无法启动，
  故回退只在 quay/Docker Hub 访问恢复的前提下有意义。
- **未验证项**：没有对"旧 MinIO 版本写入的数据卷"做升级兼容验证——旧官方镜像已不可拉取，
  无法取得旧版二进制来产出旧格式数据。上述空卷验证不能代表生产数据兼容。
  正式部署前须用隔离 fixture 单独验证旧数据升级。
- 本轮未执行正式部署、未操作业务数据卷、未改动业务库或用户 AI 配置。
