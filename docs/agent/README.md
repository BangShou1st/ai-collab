# AI Collab Agent 2.0 文档入口

本目录同时包含当前事实、部署验收指南和 Agent 2.0 的历史阶段规格。判断当前实现时先读权威事实，不要把阶段文档中的“目标”“建议”或 2026-07-30 基线当成现状。

## 当前入口

1. `../feature-matrix.md`：仓库级功能完成度。
2. `00_CURRENT_STATUS.md`：Agent 当前实现、安全边界和仍需外部环境验证的事项。
3. `../development/guides/planning-and-agent.md`：当前源码入口与实现约束。
4. `../api/openapi.yaml`：Agent、事件、记忆和 MCP HTTP 契约。
5. `../database.md`：V1–V30 形成的数据库事实。
6. `MCP_DEPLOYMENT_AND_BROWSER_TESTING.md`：本地/生产配置、MCP 管理和浏览器验收。

## 当前源码基线

- Agent 已具备 session、message、run、plan、step、durable event、approval、schedule、skill、project memory 和 MCP binding。
- OpenAI-compatible、Anthropic、Gemini 通过统一模型轮次协议返回原生 tool calls；只读 legacy executor 仅作为受控兼容路径。
- Runtime 使用可信页面上下文、6 个服务端固定 Skill、严格工具 Schema、预算/循环保护和项目隔离。
- 前端以 SSE 时间线展示并续传运行事件，支持审批、取消、定时 Skill 和项目 MCP 白名单。
- 数据库当前最新迁移是 V30；已提交的 V1–V30 不得修改。

## 历史阶段规格

`01_BASELINE_GUARDRAILS_AND_TARGET.md` 至 `07_TESTING_ACCEPTANCE_AND_CLAUDE_PROTOCOL.md`，以及 `AI_Collab_Agent_2_0_Final_Development_Spec.md`，记录从 2026-07-30 旧基线演进到 Agent 2.0 的设计与验收要求。它们保留用于解释决策和回归边界，其中出现的 V26、空工具列表、轮询页面等描述均属于历史起点。

| 阶段 | 历史文档 | 当前落地 |
|---|---|---|
| Phase 1 | `02_PHASE_1_NATIVE_TOOL_CALLING.md` | 三类供应商原生 Tool Calling 与统一 turn contract |
| Phase 2 | `03_PHASE_2_RUNTIME_CONTEXT_SKILLS_TOOLS.md` | Runtime、可信上下文、计划、固定 Skill 和严格工具 |
| Phase 3 | `04_PHASE_3_EVENTS_SSE_FRONTEND.md` | V28 持久事件、SSE 重放/续传和前端时间线 |
| Phase 4 | `05_PHASE_4_MCP_SECURITY_MEMORY.md` | V29/V30 MCP、安全边界、项目记忆和定时 Skill |
| Phase 5 | `06_API_DATABASE_FRONTEND_CONTRACTS.md`、`07_TESTING_ACCEPTANCE_AND_CLAUDE_PROTOCOL.md` | OpenAPI、数据库、前端契约和自动验证 |

阶段完成与否只由当前源码、迁移和新鲜验证证明。真实模型、GitHub MCP、DNS/网络、生产域名与 Secret 仍需对应外部环境验收，详见 `00_CURRENT_STATUS.md`。
