# 开发文档入口

这组文档用于后续 Claude + MiMo V2.5 开发。每次只给模型与当前任务直接相关的文档，避免把历史阶段笔记当成现行需求。

## 阅读顺序

所有开发任务先读：

1. 根目录 `CLAUDE.md`
2. `docs/feature-matrix.md`
3. 与任务相关的事实文档：
   - 系统边界：`docs/architecture.md`
   - 数据结构：`docs/database.md`
   - 接口：`docs/api/openapi.yaml`
4. 与改动层次相关的规范：
   - 后端：`backend-conventions.md`
   - 前端：`frontend-conventions.md`
5. 使用 `claude-mimo-task-template.md` 创建本次任务单。

Agent 相关任务额外读取 `agent-design.md`。未来功能选择读取 `docs/FUTURE_ROADMAP.md`。

## 文档职责

| 文档 | 只负责什么 |
|---|---|
| `CLAUDE.md` | 仓库入口、不可违反的约束、验证命令 |
| `feature-matrix.md` | 当前到底实现了什么 |
| `architecture.md` | 模块边界、调用流程、安全与一致性原则 |
| `database.md` | 当前数据库结构、约束和迁移规则 |
| `openapi.yaml` | HTTP 契约 |
| `backend-conventions.md` | Java/Spring/MyBatis 实现方式 |
| `frontend-conventions.md` | Vue/TypeScript/中文界面实现方式 |
| `agent-design.md` | Agent 的目标架构和分阶段实现边界 |
| `claude-mimo-task-template.md` | 限制模型范围和验收输出的任务模板 |
| `learning/phase-*.md` | 历史学习记录，不作为当前事实 |
| `superpowers/specs`、`superpowers/plans` | 已批准设计和实施记录 |

冲突时按以下优先级处理：当前用户指令 → 任务规格 → 功能矩阵/代码和迁移事实 → 架构与开发规范 → 历史文档。代码与文档冲突时先报告证据，不要默默扩大改动范围。

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
.\scripts\smoke-existing.ps1
git diff --check
```

具体冒烟说明见 `smoke-testing.md`。
