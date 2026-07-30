# 单一 Main 与 AI 开发文档体系设计

## 目标

将本地仓库收敛为只在 `main` 上开发，并建立一套可直接交给 Claude 与 MiMo V2.5 使用的高约束开发文档。文档必须以当前代码和数据库迁移为事实来源，帮助后续开发者完成小而完整的功能闭环，而不是继续积累分支、过期计划和互相矛盾的说明。

## Git 工作方式

- 本地日常开发只使用 `main`。
- 不为普通功能、修复或 AI 任务创建分支和 worktree。
- 当前 `codex/qa-agent-ux-repair` 上领先 `main` 的提交快进合入 `main`。
- 当前工作树的未提交修改原样保留，不用重置、清理或覆盖来完成分支切换。
- 完成切换后删除已合并的本地开发分支；`origin/main` 是否推送由用户另行决定。
- 每个提交只包含一个可验证的结果，禁止把无关未提交修改顺手混入提交。

## 根目录指令文件

新增 `AGENTS.md`，作为 Codex、Claude 和其他代理共同遵守的仓库规则，至少包含：

- 单一 `main` 开发要求。
- 工作树保护和禁止破坏性 Git 操作。
- 代码、迁移、OpenAPI、测试和事实文档之间的优先级。
- 缺陷必须先写回归测试，功能必须先明确验收。
- 项目权限、项目隔离、事务、外部服务和敏感信息约束。
- Claude 与 MiMo 的职责边界。

同步精简 `CLAUDE.md`，删除已经与代码冲突的状态、迁移版本和文档索引，改为指向权威文档。

## 文档信息架构

保留并修正以下事实文档：

- `docs/feature-matrix.md`：当前已实现能力和缺口。
- `docs/architecture.md`：模块边界、主要数据流、安全和一致性原则。
- `docs/database.md`：当前表、约束、迁移顺序。
- `docs/api/openapi.yaml`：HTTP 契约。
- `docs/development/backend-conventions.md`。
- `docs/development/frontend-conventions.md`。
- `docs/development/smoke-testing.md`。

新增以下执行文档：

- `docs/README.md`：唯一文档入口和阅读路由。
- `docs/development/claude-mimo-protocol.md`：模型职责、任务流和 MiMo 禁区。
- `docs/development/task-template.md`：可复制给 Claude 的单任务模板。
- `docs/development/guides/project-and-work.md`：项目、成员、里程碑、任务功能实现指南。
- `docs/development/guides/document-and-knowledge.md`：上传、处理、检索、问答实现指南。
- `docs/development/guides/planning-and-agent.md`：任务规划、模型路由、Agent 审批写入指南。
- `docs/development/api-contract-checklist.md`：后端、OpenAPI、前端类型和错误码统一清单。

删除或不恢复以下无效内容：

- 已结束阶段的 learning 文档。
- 已被当前事实文档取代的旧路线图。
- 已执行完毕、与当前代码不一致的历史规格和实施计划。
- 内容已由新协议和模板替代的旧 Claude/MiMo 文档。

当前仍用于解释未提交实现的 2026-07-30 规格和计划暂时保留；稳定后再按事实文档吸收并删除。

## Claude 与 MiMo 分工

Claude 可以：

- 阅读跨模块上下文并提出设计。
- 设计数据库迁移、权限、事务、API 和测试。
- 实现跨文件完整闭环。
- 审查 MiMo 的局部输出并运行完整验证。

MiMo V2.5 只能在 Claude 给出完整文件范围、接口、代码骨架和验证命令后执行：

- 单个纯函数或 DTO/View/Type 的机械实现。
- 单个已有模式的 API 封装。
- 明确枚举映射、文案或样式的小改动。
- 明确测试用例的补写。

MiMo 禁止：

- 自行新增或修改数据库迁移。
- 自行设计权限、事务、并发、状态机或错误契约。
- 自行修改 OpenAPI、Controller 和前端类型中的任意一侧。
- 跨模块重构、批量重命名、删除文件或 Git 操作。
- 绕过失败测试、用 `any`、空 catch、硬编码假数据或降级分支掩盖问题。
- 未经 Claude 审查直接提交。

## 功能指南格式

每份指南必须包含：

1. 功能边界和不做什么。
2. 当前代码入口和依赖方向。
3. HTTP、Java View/DTO、TypeScript 类型的统一示例。
4. 权限、项目隔离、状态、事务和并发规则。
5. 正确实现步骤和参考代码。
6. 必写测试、真实验证命令和验收标准。
7. Claude 任务提示词。
8. 可委派给 MiMo 的最小任务示例及禁止事项。

参考代码必须使用仓库现有类名和路径，不创造不存在的框架或抽象。

## 验收

- 当前工作树最终位于本地 `main`，已合并开发分支不再存在。
- `AGENTS.md` 和 `CLAUDE.md` 对单一 main、验证与模型边界没有冲突。
- 文档入口中的每个链接存在。
- 文档中不再声称项目只有两种状态、迁移停在 V21/V25 或 Agent 未实现。
- OpenAPI 的项目状态定义一致。
- 指南中的文件路径、类名、接口路径和命令能在仓库中找到或执行。
- `git diff --check` 通过，文档无 `TBD`、`TODO` 或模糊占位符。
