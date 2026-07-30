# Main Consolidation and AI Development Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将仓库收敛到本地单一 main 开发，并提供 Claude/MiMo 可执行、可验证的权威开发文档。

**Architecture:** 根目录代理规则控制工作方式，docs 入口按“事实、规范、功能指南、任务模板”路由；功能指南引用现有代码和契约，不复制过时实现。Git 只移动已确认的本地分支引用，不清理工作树。

**Tech Stack:** Git、Markdown、OpenAPI、Java/Spring、Vue/TypeScript

## Global Constraints

- 只在本地 `main` 开发，不创建普通开发分支。
- 保留当前未提交修改，不 reset、不 checkout 覆盖、不批量清理。
- MiMo 不得设计迁移、权限、事务、API 或跨模块重构。
- 所有路径、类名、接口和命令必须与当前仓库一致。
- 删除文档前必须确认其内容已过期或已被权威文档替代。

---

### Task 1: 安全收敛本地分支

**Files:**
- Git refs only

**Interfaces:**
- Produces: 当前分支为 `main`，当前 HEAD 提交均可达

- [ ] **Step 1: 记录分支和工作树**

Run: `git status --short --branch`
Run: `git log --oneline --decorate --graph --all -n 30`

- [ ] **Step 2: 将 main 快进到当前 HEAD**

仅当 `git merge-base --is-ancestor main HEAD` 成功时，执行 `git branch -f main HEAD`，随后 `git switch main`。

- [ ] **Step 3: 验证工作树未丢失**

比较切换前后的 `git status --short` 路径集合和未跟踪文件数量；不要求内容干净。

- [ ] **Step 4: 删除已合并本地分支**

确认 `codex/qa-agent-ux-repair` 已被 `main` 包含后，执行非强制删除。

### Task 2: 建立根目录代理规则

**Files:**
- Create: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Produces: 所有代理统一的仓库规则和 Claude 启动入口

- [ ] **Step 1: 写 AGENTS.md**

覆盖单一 main、工作树保护、事实优先级、TDD、API 同步、项目隔离、事务、安全和 Claude/MiMo 分工。

- [ ] **Step 2: 精简 CLAUDE.md**

只保留项目定位、必读顺序、运行/验证入口和不可违反约束；具体规则链接到 AGENTS 与 docs。

- [ ] **Step 3: 一致性检查**

搜索 `创建分支`、`worktree`、`V21`、`V25`、`只有 ACTIVE` 等冲突文本并修正。

### Task 3: 重建 docs 入口与 AI 协议

**Files:**
- Create: `docs/README.md`
- Create: `docs/development/claude-mimo-protocol.md`
- Create: `docs/development/task-template.md`
- Create: `docs/development/api-contract-checklist.md`
- Modify: `docs/development/README.md`

**Interfaces:**
- Produces: 可复制任务模板和模型职责边界

- [ ] **Step 1: 建立唯一文档入口**

按事实文档、实现规范、功能指南、任务执行四类列出阅读顺序，并说明冲突处理优先级。

- [ ] **Step 2: 写 Claude/MiMo 协议**

给出允许/禁止矩阵、Claude 审查步骤、MiMo 单任务输入格式和输出验收。

- [ ] **Step 3: 写任务模板**

模板必须要求目标、非目标、允许文件、接口、参考实现、测试、验收、禁止事项和停止条件。

- [ ] **Step 4: 写 API 契约检查清单**

逐项绑定 Java DTO/View、Controller、ErrorCode、OpenAPI、TypeScript type、API 封装、中文错误和测试。

### Task 4: 编写具体功能指南

**Files:**
- Create: `docs/development/guides/project-and-work.md`
- Create: `docs/development/guides/document-and-knowledge.md`
- Create: `docs/development/guides/planning-and-agent.md`

**Interfaces:**
- Produces: 可直接附给 Claude 的模块级实现上下文

- [ ] **Step 1: 项目与任务指南**

记录项目/成员/里程碑/任务入口、复合项目查询、权限、乐观锁、状态转换和参考接口闭环。

- [ ] **Step 2: 文档与知识指南**

记录上传补偿、短事务、异步处理、项目隔离检索、SSE、引用、错误与测试闭环。

- [ ] **Step 3: 规划与 Agent 指南**

记录模型 Gateway、结构化输出校验、人工确认、幂等、Agent 只读工具和审批写工具边界。

### Task 5: 清理并验证文档

**Files:**
- Delete: 已被替代且仍存在的历史 learning、旧路线图、旧 AI 模板和过期计划
- Modify: `docs/feature-matrix.md`
- Modify: `docs/architecture.md`
- Modify: `docs/database.md`
- Modify: `docs/api/openapi.yaml`

**Interfaces:**
- Produces: 无冲突、无断链的当前文档集

- [ ] **Step 1: 核对事实**

以源码、V1-V26 迁移、前端路由和 OpenAPI 为证据修正文档中的状态、表数、迁移版本和能力说明。

- [ ] **Step 2: 删除已替代文档**

只删除内容已经由新入口、协议、指南或事实文档覆盖的文件。

- [ ] **Step 3: 检查路径和占位符**

验证所有 Markdown 相对链接存在；搜索 `TBD|TODO|待定|稍后实现`；验证指南中的代码路径存在。

- [ ] **Step 4: 验证并提交**

Run: `.\scripts\validate-openapi.ps1`
Run: `git diff --check`

只暂存代理规则和文档文件，提交信息：
`docs: establish main-only AI development workflow`
