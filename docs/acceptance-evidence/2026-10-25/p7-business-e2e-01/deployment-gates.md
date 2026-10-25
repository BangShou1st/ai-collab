# E12 发布前门禁复核（2026-10-25，dev=5aa30ba）

只读复核 `docs/release-readiness/p5-deployment-readiness-01/deployment-gates.json`，结合本轮最新代码核对。

| 门禁 | JSON 记录状态 | 本轮复核 | 结论 |
| --- | --- | --- | --- |
| G-CI | VERIFIED | dev 5aa30ba 为 P6-RAG 交付后状态，CI 历史绿；本轮 dev 新提交需以其自身 CI 为准 | 不变 |
| G-BACKUP-SCRIPTS | VERIFIED | 脚本与演练记录在库 | 不变 |
| G-RUNBOOK | DOCUMENTED | 未复核生产实机 | 不变 |
| G-COMPOSE | VERIFIED | 无相关改动 | 不变 |
| G-1（Embedding/Planning 配置透传历史误判） | VERIFIED（已关闭） | 本轮实机验证：Embedding 走管理端入库配置（Ollama 生效），规划模型走用户级 Provider（魔搭 DeepSeek 真实生效）——与"历史遗留变量是死代码"的结论一致 | 维持关闭，不重复修复 |
| G-2 / G-N02（Forwarded Headers / 反代注册限流） | ENVIRONMENT_DEPENDENT | 本轮发现并修复"未知路径 500→404"，与限流无关；限流 key 仍只含 RemoteAddr，反代场景需部署拓扑确认 | 仍阻断（环境依赖） |
| G-TLS | ENVIRONMENT_DEPENDENT | 域名/证书/反代仍待用户提供 | 仍阻断 |
| G-SECRETS | ENVIRONMENT_DEPENDENT | 生产密钥下发未执行；本轮魔搭 Key 仅用于本地验收配置（用户明示授权），不入库、不入证据 | 仍阻断 |
| G-FRONTEND-HOSTING | ENVIRONMENT_DEPENDENT | 仓库仍无 nginx/前端容器；E05 曾以受控代理托管 dist（仅本地测试用） | 仍阻断 |
| G-OBSERVABILITY | ENVIRONMENT_DEPENDENT | Actuator 仅暴露 health/info（本轮实测：metrics 未认证 401、认证后 404）；告警通道未接通 | NOT_CONNECTED，不阻断首署、阻断就绪结论 |
| G-BACKUP-OPS | ENVIRONMENT_DEPENDENT | 无 cron 调度、异机恢复未演练 | 仍阻断 |
| G-N01（Image Job required checks） | VERIFIED_NOT_DONE | 分支保护未改动 | 不变 |
| G-REGRESSION-ITEMS | ENVIRONMENT_DEPENDENT | R-10 Zen 限流、R-13 MinIO 旧卷、R-11 压测等遗留未变 | 需用户知情接受 |

## 本轮新增事实

1. **未知路径 500 → 404 已修复**（GlobalExceptionHandler + RESOURCE_NOT_FOUND），消除了"非暴露 Actuator 端点与未知路径返回 INTERNAL_ERROR"的监控噪音；`AgentActuatorSecurityTest` 既有 404 断言通过。
2. 模型发现（/models）与能力探针实测可用（魔搭 ModelScope 37 模型、4/4 探针通过）；预设 Provider 与自定义 Provider 的准入路径正常。
3. AI 设置页 Provider 卡片在长模型名下的按钮重叠布局缺陷已修复（纯 CSS，见 issues.md）。

## 结论

**浏览器 E2E 通过 ≠ 部署门禁全部关闭。** 仍阻断生产的门禁：G-2/G-N02、G-TLS、G-SECRETS、G-FRONTEND-HOSTING、G-BACKUP-OPS、G-REGRESSION-ITEMS，全部依赖用户服务器/域名/密钥/部署授权或明确接受。未经授权不更新为生产 GO。
