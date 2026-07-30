# 项目文档写入状态修复设计

## 目标

修复刚创建项目无法上传文档的问题，并统一项目状态对文档写操作的约束：

- `PREPARING`、`ACTIVE` 项目允许上传、删除、重试和重新索引文档。
- `COMPLETED`、`ARCHIVED` 项目保留文档读取能力，但拒绝所有文档写操作。
- 项目确实不存在或当前用户不是项目成员时，继续返回 `PROJECT_NOT_FOUND`。
- 项目存在但状态只读时，返回独立、稳定且中文可理解的业务错误，不能伪装成“项目不存在”。

## 已确认根因

项目 `8d741a93-7fde-449e-af3e-efd5f272a9ac` 在 PostgreSQL 中存在，状态为
`PREPARING`，当前用户角色为 `OWNER`。上传请求已经通过
`DocumentApplicationService.upload` 中的管理员权限检查，但
`DocumentRegistrationService.registerUploadedDocument` 随后调用
`ProjectRepository.lockActive`。该查询只锁定 `status = 'ACTIVE'` 的项目，
导致新建的 `PREPARING` 项目被错误映射为 `PROJECT_NOT_FOUND`。

新项目由 `ProjectApplicationService.create` 固定创建为 `PREPARING`，因此该缺陷会稳定影响所有新建项目。

## 设计

### 统一项目可写状态

在项目领域层增加单一状态规则，明确区分：

- 可写状态：`PREPARING`、`ACTIVE`
- 只读状态：`COMPLETED`、`ARCHIVED`

业务服务不得各自硬编码状态集合。状态规则应可由纯单元测试覆盖，并由文档注册事务在锁定项目行后使用。

### 文档上传事务

保持现有上传链路：

1. 校验 OWNER/ADMIN 权限。
2. 校验文件类型、大小和显示名称。
3. 将原文件写入 MinIO。
4. 在短数据库事务中锁定项目、校验项目可写状态、校验文档数量并创建记录。
5. 注册失败时补偿删除已写入的 MinIO 对象。

数据库锁查询需要返回实际 `ProjectStatus`，不能用“是否为 ACTIVE”的布尔结果混合表达“不存在”和“不可写”。

### 其他文档写操作

上传、删除、失败重试、单文档重新索引和批量重新索引必须使用同一可写状态规则。列表、详情、下载和索引进度查询仍允许项目成员在所有项目状态下访问。

第一轮实现至少修复上传入口，并为其他写入口增加同一服务端保护；不能只在前端隐藏按钮。

### 错误契约

新增 `PROJECT_READ_ONLY`：

- HTTP 状态：`409 Conflict`
- 中文消息：`当前项目状态为只读，不能修改项目文档`
- 前端统一中文映射与后端消息保持一致。

`PROJECT_NOT_FOUND` 只用于项目不存在或成员不可见语义。

## 测试

### 状态规则单元测试

逐项断言：

- `PREPARING` 可写。
- `ACTIVE` 可写。
- `COMPLETED` 只读。
- `ARCHIVED` 只读。

### PostgreSQL 集成测试

使用真实 PostgreSQL/MyBatis，覆盖：

1. 创建 `PREPARING` 项目及 OWNER 成员关系。
2. 调用文档注册事务。
3. 断言项目文档记录成功创建。
4. 将项目改为 `COMPLETED`，再次注册时断言返回 `PROJECT_READ_ONLY`。
5. 使用不存在项目 ID 时断言仍返回 `PROJECT_NOT_FOUND`。

### 回归验证

- 相关测试必须先在旧实现上失败，再在修复后通过。
- 后端全量测试和打包通过。
- 前端测试、类型检查和生产构建通过。
- OpenAPI、错误码映射、功能矩阵与实现一致。
- 使用新建 `PREPARING` 项目执行真实上传，确认返回 `202`，文档进入处理状态。

## 非目标

- 上传行为不会隐式把项目从 `PREPARING` 改为 `ACTIVE`。
- 不改变项目状态转换图。
- 不新增批量上传后端接口。
- 不借此重构文档处理、MinIO 或项目权限架构。
