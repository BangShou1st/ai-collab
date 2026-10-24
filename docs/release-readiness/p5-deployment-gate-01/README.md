# P5-DEPLOYMENT-GATE-01 — Docker Compose 配置透传修复（G-1 关闭）

> 日期：2026-10-24。工作分支 `dev`，基线 `b0c5dcac97fd5162733f2b2b5de3a1bb49d7ec4a`。

## 任务

关闭上一轮 `P5-DEPLOYMENT-READINESS-01` 确定的部署阻断项 **G-1**：Docker Compose 未向
app 容器透传 Embedding 与 Planning 相关配置。

## 结论

**`DEPLOYMENT_GATE_G1_ACCEPTED`** —— G-1 已关闭，但从源码审计看，G-1 的原始定性
需要修正：

1. **"缺 EMBEDDING_* 透传导致 RAG 知识库生产不可用"是误判**：生产 Embedding 配置
   走管理端「AI Infrastructure」入库（数据库，密钥加密），不依赖环境变量；
   消费 `EMBEDDING_*` 的 `OpenAiCompatibleEmbeddingGateway` 是无注入点的死代码。
2. **真实缺陷**：7 个生效的 Planning 调优变量未透传 + `.env.example` 把 19 个
   历史遗留变量展示为有效配置。本轮按"最小修改、不机械透传无效配置"原则处理。

详细证据链：[config-mapping.md](config-mapping.md)。

## 文档导航

| 文件 | 内容 |
| --- | --- |
| [config-mapping.md](config-mapping.md) | 配置调用链审计与完整映射表（含遗留变量判定与源码证据） |
| [compose-validation.md](compose-validation.md) | Compose 语法 / 正负向 / local profile / 真实容器验证 |
| [regression-results.md](regression-results.md) | 回归测试执行与豁免清单、业务回归核对 |
| [risk-closure.json](risk-closure.json) | 结构化收口记录（验收裁决、验证矩阵、遗留变量清单） |

验收证据（合成数据）：`docs/acceptance-evidence/2026-10-24/p5-deployment-gate-01/`

## 修改清单

| 文件 | 变更 |
| --- | --- |
| `ai-collab-deploy/docker-compose.yml` | app environment 新增 7 个生效的 Planning 调优变量（带默认值），附地址原则与遗留变量说明 |
| `.env.example` | 第 6/7 段重写：移除遗留变量、注明原因，列出真实生效项 |
| `ai-collab-deploy/scripts/compose-config-contract.sh` | 新增 Compose 配置契约回归（仅渲染、全合成值，本地与 CI 可跑） |
| `.github/workflows/ci.yml` | image job 的 Validate compose config 步骤追加契约回归；未动其他 job |
| `docs/release-readiness/p5-deployment-readiness-01/deployment-gates.json` | G-1 → VERIFIED，移出 blocking_gate_ids |

## CI 记录

- 待 push 后补记（若在运行记 `CI_PENDING`）。

## 后续不在本任务范围

N-01 分支保护、N-02 Forwarded Header、生产域名/TLS、出网代理、告警接入、异地备份、
MCP、Work 页面、Agent UX。
