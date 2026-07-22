# 开发规范

## 1. 环境

- JDK 21
- Maven 3.9+
- Docker Desktop / Docker Engine + Compose
- Node.js 22+
- pnpm 10+
- IntelliJ IDEA
- Git

## 2. 分支

```text
main                 可演示版本
develop              集成分支
feature/<scope-name> 功能分支
fix/<scope-name>     修复分支
```

独立开发也应使用短功能分支，避免所有修改直接堆在 main。

## 3. Commit 规范

```text
feat(project): add invitation acceptance
fix(work): reject cyclic task dependencies
test(knowledge): add refusal evaluation cases
docs(api): document task planning endpoints
refactor(document): extract parser strategy
chore(deploy): add pgvector compose service
```

每个可测试用例完成后提交，不把一周修改压成一个 commit。

## 4. 编码约定

- Java 类名、包名、变量名均使用英文。
- 包名全小写。
- Controller 不超过 200 行，Service 不承担多个用例。
- Entity 不直接作为 API Response。
- 禁止 `catch (Exception) {}` 吞异常。
- 外部调用必须配置连接超时、读取超时和有限重试。
- 所有时间通过注入 `Clock` 获取，便于测试。
- 不在业务代码中调用 `LocalDateTime.now()`。

## 5. API 开发顺序

1. 定义 Request/Response 和错误码。
2. 写 Controller 测试。
3. 写 Application Service 测试。
4. 实现最小业务逻辑。
5. 写 Repository 集成测试。
6. 补 OpenAPI 文档。
7. 提交。

## 6. 数据库变更

- 所有变更新增 Flyway 文件，不修改已执行迁移。
- 命名：`V2__add_document_status_index.sql`。
- 迁移必须在空数据库和已有数据数据库上测试。
- 禁止使用 Hibernate 自动更新 schema。

## 7. 配置

配置优先级：环境变量 > `application-local.yml` > 默认值。

敏感信息：

- 不写进 Git
- 不放在前端
- 不打印到日志
- `.env` 必须在 `.gitignore`

## 8. 模型供应商切换

- Chat Provider 可在配置中手动切换。
- Embedding Provider 切换后必须重建相关文档索引。
- Provider 错误映射为统一 `AiProviderException`。
- 业务模块不得依赖供应商专属 Request/Response。

## 9. Definition of Done

一个功能只有同时满足以下条件才完成：

- 单元/集成测试通过
- 权限场景已测试
- 错误码与接口文档已更新
- 日志不含敏感信息
- 数据库迁移可重复执行
- README 或开发文档反映真实行为
- 手动走通至少一条用户主流程
