# AI Collab 代理开发规则

本文件适用于 Codex、Claude、MiMo 和其他自动化开发代理。开始任务前必须先读本文件，再按 `docs/README.md` 选择任务相关文档。

## 单一 Main

- 本地开发只使用 `main`。普通功能、修复和文档任务禁止创建分支或 worktree。
- 未经用户明确要求，不执行 `git push`、创建 PR、改写远端历史或操作远端分支。
- 工作树可能包含其他未提交成果。禁止 `git reset --hard`、`git checkout --`、批量清理、覆盖或删除无关修改。
- 每次只暂存当前任务明确涉及的文件。提交前检查 `git diff --cached`。
- 如果一次任务无法在一个可验证提交中表达，先拆成多个顺序闭环，不用分支并行。

## 事实与文档优先级

冲突时按以下顺序判断：

1. 当前用户指令。
2. 已确认的任务规格和验收标准。
3. 当前源码、V1–V30 Flyway 迁移、自动化测试和实际运行证据。
4. `docs/feature-matrix.md`、`docs/api/openapi.yaml`、`docs/database.md`。
5. 架构与开发规范。
6. 历史规格和计划。

发现文档与代码不一致时必须核实并同步权威文档，不能只改一侧或继续复制旧结论。

## 工作流程

### 缺陷

1. 稳定复现并追踪错误数据来源。
2. 先写能捕获该缺陷的测试并确认失败原因正确。
3. 只实现修复根因所需的最小改动。
4. 运行相关测试、全量测试、类型检查和构建。
5. 同步 OpenAPI、前端错误映射和功能矩阵。

### 功能

1. 明确目标、非目标、权限、状态、接口、数据结构和验收。
2. 先写设计与可执行任务计划。
3. 按“后端契约 → 后端实现 → 前端类型/API → 页面闭环 → 验证”推进。
4. 一次只实现一个可独立验收的功能闭环，不提前实现路线图能力。

## 后端硬约束

- Controller 只处理 HTTP、认证主体和 DTO；不直接访问 Mapper，不返回 Entity。
- 项目级接口先校验成员身份；OWNER/ADMIN/MEMBER 权限只由服务端决定。
- 项目子资源按 `projectId + entityId` 查询，SQL 显式包含 `project_id`。
- `PROJECT_NOT_FOUND` 只用于不存在或成员不可见；状态、权限和冲突使用独立错误码。
- 项目文档写操作只允许 `PREPARING`、`ACTIVE`；`COMPLETED`、`ARCHIVED` 返回 `PROJECT_READ_ONLY`。
- 外部模型、MinIO、Redis 网络调用不放进数据库长事务。
- 并发写使用版本、CAS、行锁、数据库约束或幂等键，不依赖“先查再写”。
- 已提交迁移不可修改；新结构使用下一个 Flyway 版本。
- 密码、Token、Cookie、API Key、邀请码原文、文档正文、Prompt 和完整模型输出不得进入日志或审计。

## API 与前端同步

修改接口时必须作为一个闭环同步：

1. Java Request DTO、View 和 Controller。
2. `ErrorCode` 与统一异常行为。
3. `docs/api/openapi.yaml`。
4. TypeScript type 与 API 封装。
5. 中文错误映射、状态映射和页面交互。
6. 后端、前端与契约测试。

禁止用 `any`、非空断言、空 catch、硬编码假数据或仅隐藏按钮来掩盖契约和权限问题。

## Claude 与 MiMo V2.5

Claude 负责需求澄清、设计、拆分、跨文件实现、数据库/权限/事务/API 决策、代码审查和最终验证。

MiMo 只能执行 Claude 已完全限定的小任务：

- 单个纯函数、DTO、View 或 TypeScript type。
- 沿用现有模式的单个 API 方法。
- 明确枚举映射、中文文案、局部样式。
- 已给出断言和目标接口的单个测试。

MiMo 禁止：

- 创建或修改迁移。
- 设计权限、状态机、事务、并发、幂等或错误契约。
- 单独修改 Controller、OpenAPI、前端类型中的任意一侧。
- 跨模块重构、批量重命名、删除文件和任何 Git 操作。
- 自行扩大允许文件列表。
- 测试失败后修改断言迎合实现，或用降级逻辑掩盖失败。

MiMo 的输出必须由 Claude 逐行审查并运行任务要求的验证，未经审查不得提交。完整协议见 `docs/development/claude-mimo-protocol.md`。

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
git status --short
```

必须报告实际执行命令、退出码和失败原因。没有新鲜验证证据时不能声称任务完成。
