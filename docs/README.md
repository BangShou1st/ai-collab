# AI Collab 文档入口

本文档是仓库唯一的文档导航。不要从文件名猜测实现状态，也不要把历史规格当成当前事实。

## 开始开发

按顺序阅读：

1. 根目录 `AGENTS.md`：所有代理必须遵守的开发规则。
2. `feature-matrix.md`：当前已经实现什么、仍缺什么。
3. 与任务直接相关的事实文档：
   - `architecture.md`
   - `database.md`
   - `api/openapi.yaml`
4. 与改动类型相关的规范和功能指南。
5. 使用 `development/task-template.md` 把需求收敛成一个可验收任务。

## 权威事实文档

| 文档 | 职责 |
|---|---|
| `feature-matrix.md` | 当前功能完成度和明确缺口 |
| `architecture.md` | 模块边界、依赖、主要数据流、安全和一致性原则 |
| `database.md` | V1–V30 迁移形成的当前数据库结构 |
| `api/openapi.yaml` | HTTP 路径、方法、请求和响应契约 |

冲突时以当前源码、迁移和实际测试证据为准，并在同一个任务中修正文档。

## 实现规范

| 文档 | 使用场景 |
|---|---|
| `development/backend-conventions.md` | Java、Spring、MyBatis、事务、权限、日志 |
| `development/frontend-conventions.md` | Vue、TypeScript、中文界面、错误和页面闭环 |
| `development/api-contract-checklist.md` | 新增或修改任何 HTTP 接口 |
| `development/smoke-testing.md` | 本地关键业务链路回归 |

## 具体功能指南

| 指南 | 覆盖范围 |
|---|---|
| `development/guides/project-and-work.md` | 项目、成员、里程碑、任务、评论、可视化 |
| `development/guides/document-and-knowledge.md` | 文档上传、MinIO、解析、向量、问答、SSE、引用 |
| `development/guides/planning-and-agent.md` | 任务规划、模型路由、人工确认、Agent 工具与审批 |
| `agent/README.md` | Agent 2.0 当前入口、阶段历史、部署与验收文档导航 |

功能指南用于提供当前代码入口和正确实现模式，不代替本次任务规格。

## Claude 与 MiMo

- `development/claude-mimo-protocol.md`：职责、禁区、审查和交付流程。
- `development/task-template.md`：可直接复制给 Claude 的单任务模板。

MiMo V2.5 不得直接接收模糊产品需求。必须先由 Claude 填完整任务模板，再只委派其中一个局部步骤。

## 规格和实施记录

`superpowers/specs/` 与 `superpowers/plans/` 只保留当前仍在实施或仍解释未提交代码的记录。任务稳定后，应把长期事实吸收到上述权威文档，再删除已经完成且不再有解释价值的记录。

## 基础验证

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build

Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
```
