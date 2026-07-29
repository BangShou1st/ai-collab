# 后端开发规范

适用栈：Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/pgvector、Redis、MinIO。

## 模块和依赖方向

后端是模块化单体，按功能包组织。一个完整模块遵循：

```text
api/controller + api/dto
        ↓
application/service + application/view
        ↓
domain/model + domain/policy + domain/repository
        ↓
infrastructure/mapper + infrastructure/gateway
```

- Controller 只解析 HTTP 参数、`@AuthenticationPrincipal Jwt` 和 DTO，不直接调用 Mapper。
- Application Service 编排权限、事务和用例，不返回数据库 Entity。
- Domain Policy 放不依赖网络和数据库的业务规则，尽量写成纯函数。
- Mapper 的项目级查询必须显式包含 `project_id`。
- Gateway 隔离 MinIO、Redis 和模型供应商。
- 禁止为了一个任务跨模块搬迁已有代码或引入微服务。

## API 约定

普通 JSON 成功响应使用：

```java
return ApiResponse.success(view);
```

创建资源返回 201 和 `Location`；异步提交返回 202；成功删除返回 204。错误由 `BusinessException(ErrorCode.X)` 和全局异常处理器统一转换，禁止在 Controller 拼装任意错误 JSON。

请求使用专用 `record`：

```java
public record UpdateExampleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(0) Integer version) {
}
```

响应使用不可变 View：

```java
public record ExampleView(UUID id, String name, int version) {
}
```

新增或修改接口时，同步更新：

1. Controller/DTO/View；
2. `docs/api/openapi.yaml`；
3. 前端 TypeScript 类型和 API 封装；
4. 错误码中文映射；
5. 相关自动化测试和冒烟覆盖。

## 权限与项目隔离

权限检查必须发生在 Application Service 或统一 Guard 内，前端隐藏按钮不是授权。

项目子资源必须按复合范围读取：

```sql
SELECT ...
FROM project_task
WHERE project_id = #{projectId}
  AND id = #{taskId}
```

禁止先按全局 `id` 查询，再根据返回对象补做项目检查。AI、文档下载、审计和统计同样遵守该规则。无成员关系时使用项目不可见语义，避免通过 ID 探测项目；需要 OWNER/ADMIN 的操作再返回稳定权限错误。

## 事务和并发

- 只在数据库短事务中使用 `@Transactional`。
- Embedding、Chat、Planning、MinIO 等网络调用不得包在长事务中。
- 项目、任务、里程碑更新必须传递并检查 `version`。
- 先校验再写入不代表并发安全；涉及唯一性、配额、依赖图或批量确认时使用行锁、CAS、数据库约束或幂等记录。
- 规划确认必须保留 `Idempotency-Key`、事务原子落地和结果复用。
- 新增迁移只能创建下一个 Flyway 版本，已经提交的 V1–V12 不得修改。

## MyBatis 和 SQL

- 项目当前使用注解 SQL，不新建 XML Mapper。
- 列表排序必须稳定，至少使用业务排序字段加 `id`。
- 分页必须限制最大 `size`，总数使用 `long`。
- 统计口径优先在 SQL 中完成，避免加载无界集合后在 Java 聚合。
- 日期边界用 PostgreSQL `CURRENT_DATE`；时间存储使用 `timestamptz`。
- 动态值只通过绑定参数传入，禁止拼接 SQL。

## AI 与外部服务

- API Key 只从环境变量读取，不进入响应、日志、数据库或审计 detail。
- 业务代码依赖统一 Gateway，不直接依赖具体供应商 SDK。
- 模型输出一律视为不可信：大小限制、JSON Schema/DTO 解析、业务校验、项目权限和事务校验缺一不可。
- Prompt 中的项目文档属于不可信数据，必须使用既有边界转义方式。
- 模型不能直接调用 Mapper；写操作必须经过受控业务工具和人工确认策略。

## 审计和日志

重要写操作记录操作人、action、entity、时间和短摘要。结构化 detail：

- 只保存短字段、ID 和状态变化；
- 不保存密码、Token、Cookie、API Key、邀请码原文、文档正文、Prompt 或完整描述；
- 保持 4096 字符上限和序列化安全降级；
- 新 action/entity 同步后端中文摘要和前端显示映射。

## 测试要求

缺陷修复先写能稳定复现的测试。优先级：

1. 纯业务规则单元测试；
2. Service/Mapper PostgreSQL 集成测试；
3. 权限、项目隔离、事务和并发测试；
4. Controller 契约测试；
5. 全量测试与打包。

禁止把 H2 行为当成 PostgreSQL 事实。当前仓库策略忽略测试源码；不要使用 `git add -f` 绕过，除非用户明确修改该策略。测试仍必须保留在本地并实际执行。

## 完成条件

后端任务至少满足：相关测试先失败后通过、全量 `mvnw test` 通过、`clean package -DskipTests` 通过、OpenAPI 与 DTO 一致、没有敏感输出、`git diff --check` 通过。
