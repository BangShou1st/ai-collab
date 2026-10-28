# 本轮测试矩阵

状态含义：`VERIFIED` 为本轮执行；`FAILED` 为实际失败；`BLOCKED_EXTERNAL` 为外部前提缺失；`NOT_EXECUTED` 为未执行；`HISTORICAL_ONLY` 为旧证据。隔离/模拟结果不能推导成生产结果。

| 场景 | 方法与结果 | 状态 |
| --- | --- | --- |
| 前端 | `pnpm lint`：0 错误、76 既有警告；`pnpm typecheck`、`pnpm build` 通过；`pnpm test`：52 文件、409 测试通过 | VERIFIED |
| 后端首次完整回归 | `mvnw -B -ntp verify`：1913 测试、44 跳过、1 错误；`AgentResearchQualityPostgresTest.identicalClauseKeepsTwoDistinctSourceIdentities` 连接 PostgreSQL 时 `BindException: Address already in use` | FAILED |
| 后端失败项定向复跑 | `-Dtest=AgentResearchQualityPostgresTest test`：7/7 通过。单项复跑不覆盖首次完整回归失败 | VERIFIED（定向） |
| Windows 后端完整复跑 | 1913 测试、44 跳过、1 错误；另一 `AgentPauseResumePostgresTest` 用例出现相同本地 `BindException`；该类在 Linux 容器中 21/21 定向通过 | FAILED（Windows）；VERIFIED（Linux 定向） |
| Linux 首次完整回归 | 1913 测试、45 跳过、2 错误；隔离测试容器未挂载 `.env.example` 与 `docs/api/openapi.yaml`，两项均为 `NoSuchFileException`。未把该运行记为通过 | FAILED（测试夹具不完整） |
| 后端 Linux 完整复跑 | 完整挂载版本化的 `docs`、`tests/fixtures` 和无秘密的 `.env.example` 后执行 `mvnw -B -ntp verify`：1913 测试、0 失败、0 错误、45 条条件跳过，耗时 13 分 11 秒；CI 指定的 21 个关键 Testcontainers 类逐一检查均实际执行，5 个真实模型 opt-in 类确认跳过 | VERIFIED（隔离 Linux） |
| Dockerfile 镜像 | 固定基础镜像后的 `ai-collab-backend/Dockerfile` 构建并在完整隔离拓扑中重测通过；本地镜像 ID `sha256:3f53dd3ce3e5a9e8ae4a8975aa3933f55f812209f7ae360ffa26e5bc20fc34b1`，运行用户 `appuser`，工作树标记为 `87817b5-dirty`，不是已发布镜像 | VERIFIED（隔离） |
| 生产 Compose | `docker compose ... config --format json` + `prod-compose-contract.mjs` 检查服务端口、信任 IP、地址池、持久卷及安全属性；实际隔离容器仅反代绑定 127.0.0.1 | VERIFIED（隔离）；公网 BLOCKED_EXTERNAL |
| 配置及 TLS 负例 | `node --test .../prod-config-check.test.mjs`：4 项通过，包括占位密钥、重复密钥、HTTP/通配 Origin、缺少 DNS SAN、证书域名及环境变量覆盖；自签证书只用于测试 | VERIFIED（合成）；真实 CA BLOCKED_EXTERNAL |
| HTTPS 身份与权限 | 真实应用和 Nginx：公开注册成功，账号没有系统管理员权限；匿名 401、错误 Origin 403、登录/刷新/登出、过期 Token、Cookie 属性、普通成员禁止管理、跨项目访问与移除后拒绝 | VERIFIED（隔离） |
| 转发头和限流 | `p0-proxy-ip-test.mjs` 使用两个实际容器 IP：来源 A 的 30 次失败登录、第 11 次注册后限流，来源 B 独立计数；变化的伪造头不能绕过，非受信来源直连伪造头不能绕过 | VERIFIED（隔离） |
| 文档与下载 | 应用账号上传合成文档；普通路径及包含空格、加号、百分号的对象经签名与同源代理下载，内容一致。真实 Provider/Embedding、解析、索引和引用链需另行验证 | 上传/下载 VERIFIED（隔离）；真实模型 BLOCKED_EXTERNAL |
| SSE | 合成已持久化事件通过真实应用和 Nginx 在 7 秒内送达；客户端主动中断后携 `Last-Event-ID` 只重放后续事件 | VERIFIED（受控事件） |
| 依赖故障 | 对本任务随机命名的隔离容器分别停止并重启 PostgreSQL、Redis、MinIO；整体 health 降级后恢复 | VERIFIED（隔离） |
| 备份恢复 | 全量 Flyway 后 54 张表、3 用户、2 项目、2 成员、3 文档与 3 对象恢复；关键行数及对象引用一致；备份+演练 20–21 秒；另有简化样本 17–18 秒 | VERIFIED（合成且同机）；异机 BLOCKED_EXTERNAL |
| 失败通知与日志 | 模拟查询失败、静默 `mc diff` 失败、恢复清单失败、缺失配置、互斥锁占用、应用不可用和日志读取失败，退出码/假接收器通过；隔离容器 stdout/stderr 未检出合成会话凭据、JWT、签名 URL 或文档标记 | VERIFIED（模拟/隔离）；真实接收 BLOCKED_EXTERNAL |
| 浏览器 | browser-skill 通过本机 HTTP 桥接观察登录页和开放注册页；自签 TLS 被浏览器拒绝，未修改浏览器信任。实际注册成功由独立 HTTPS API 测试证明 | VERIFIED（受控桥接）；浏览器 HTTPS 登录 NOT_EXECUTED |
| 外部 Provider、收费模型 | 未外呼或使用真实 API Key；RAG 真实索引、检索、来源引用和 Agent 模型回合未在本轮生产拓扑中实测 | BLOCKED_EXTERNAL |
| 目标服务器 / GitHub required checks | 无服务器、域名、受信证书、管理员设置授权；新增 Job 不能冒充已在本次 SHA 上运行 | BLOCKED_EXTERNAL |

隔离资源在脚本完成时按随机 Compose 项目标签核验并清理；未使用生产数据、业务卷或 `docker compose down -v`。日志扫描仅覆盖当次合成内容，不能证明所有场景永无泄漏。
