# 待确认问题清单（open-questions）

> 以下问题**无法从仓库或现有证据确定**，必须由用户提供环境信息后才能回填方案。回答方式：直接在本文件每项下追加"用户答复：…"，或在 go-no-go.md 签署。**不需要也不得要求用户发送明文密码或 API Key。**

## A. 部署目标与网络

- **Q1 域名**：是否已有生产域名？DNS 由谁管理？答：`[部署环境补充]`
- **Q2 TLS**：证书来源（Let's Encrypt 自动续期 / 商业证书 / 云 LB 终止）？终止组件是 Nginx、Caddy 还是云负载均衡？
- **Q3 镜像分发**：生产主机可直连公网构建镜像（Maven Central + JitPack），还是需离线导入 / 自建私有 registry？
- **Q3b 出网代理**：生产主机是否需要经代理才能访问 `opencode.ai` / `ai-gateway.kilo.ai` / `generativelanguage.googleapis.com`？（若需要：当前代码不支持代理出网，R-24，必须提前决策——加代理支持 or 提供直连线路 or 弃用对应 Provider）
- **Q4 服务器**：目标主机规格、OS、位置（云区域/机房）；`[部署环境补充]`

## B. 数据安全

- **Q5 备份与异地**：备份产物保存位置（本机 `/backups` 之外的挂载/NAS/对象存储）？是否需要异地副本？保留期是否沿用 14 天？备份责任人是谁？
- **Q6 跨机恢复演练**：能否在部署前后提供一台非业务主机做异机恢复演练（R-01 未覆盖项）？
- **Q7 密钥备份**：`MODEL_CONFIG_MASTER_KEY` / `JWT_SECRET` 的离线加密归档由谁保管？（master key 不可更换，丢失即加密数据不可解）

## C. 应用配置

- **Q8 `AUTH_ALLOWED_ORIGINS` 生产值**：即生产域名（同源拓扑下 = `https://<域名>`）。
- **Q9 注册策略**：`AUTH_PUBLIC_REGISTRATION_ENABLED` 生产 true 还是 false？
- **Q10 模型 Provider 清单**：生产启用哪些 Provider（Zen/Kilo/Gemini/OpenAI 兼容/本地 Ollama embedding）？对应 API Key 经何种密管下发？embedding 维度需与库约束一致。
- **Q11 N-02 决策输入**：反代产品选型与 X-Forwarded 头设置能力（tls-and-networking.md §4.3 的三个前提是否满足）。

## D. 运维运营

- **Q12 日志与告警**：日志采集到哪（自建 Loki / 云日志服务 / 仅 docker logs）？告警通道（邮件/IM webhook）？接收人？
- **Q13 发布窗口与责任人**：首次发布时间窗口、发布批准人、风险接受负责人（P5-01 遗留：须由人明确指定）、事故联系人。
- **Q14 分支保护授权**：是否授权管理员将 `Image build & container smoke` 加入 main 的 required status checks（N-01，见 go-no-go.md §N-01）？本轮未获授权未改动。

## 已确认事项（无需重复回答）

- 部署形态：Docker Compose（既有 runbook 基础）。
- 前端托管：外部反向代理同源托管 `dist/`（若用户提出分离域名部署则重新评估 CORS/Cookie，见 production-architecture.md §6）。
- 全部基础设施（PostgreSQL/Redis/MinIO）自托管于同一 compose。
