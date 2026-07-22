# 模块与包结构设计

## 1. 仓库结构

```text
ai-collab/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/shitulelv/aicollab/
│       ├── main/resources/
│       └── test/java/com/shitulelv/aicollab/
├── frontend/
│   ├── package.json
│   └── src/
├── deploy/
├── docs/
├── .env.example
├── .gitignore
└── README.md
```

不得创建 `后端`、`前端`、`项目模块` 等中文目录，也不得使用中文 Java 包名。

## 2. Java 根包

```text
com.shitulelv.aicollab
```

## 3. 后端包结构

```text
com.shitulelv.aicollab
├── AiCollabApplication.java
├── common
│   ├── api
│   │   ├── ApiResponse.java
│   │   └── PageResponse.java
│   ├── audit
│   │   ├── AuditAction.java
│   │   └── AuditPublisher.java
│   ├── config
│   ├── error
│   │   ├── ErrorCode.java
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   ├── idempotency
│   ├── security
│   │   ├── CurrentUser.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── ProjectAccessGuard.java
│   │   └── SecurityConfig.java
│   └── time
├── auth
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── user
│   ├── application
│   ├── domain
│   └── infrastructure
├── project
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── work
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── document
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── knowledge
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── planning
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── dashboard
│   ├── api
│   └── application
├── audit
│   ├── api
│   ├── application
│   └── infrastructure
└── infrastructure
    ├── ai
    │   ├── AiModelGateway.java
    │   ├── SpringAiModelGateway.java
    │   ├── AiProviderProperties.java
    │   └── AiCallRecorder.java
    ├── storage
    │   ├── ObjectStorageGateway.java
    │   └── MinioObjectStorageGateway.java
    └── vector
        ├── VectorSearchGateway.java
        └── PgVectorSearchGateway.java
```

## 4. 模块职责

| 模块 | 负责 | 不负责 |
|---|---|---|
| auth | 登录、刷新、退出、邀请接受 | 项目角色判断 |
| user | 用户资料和状态 | 项目成员关系 |
| project | 项目、成员、邀请、角色 | 任务细节 |
| work | 里程碑、任务、依赖、评论 | AI 生成 |
| document | 上传、解析、分块、索引、删除 | 问答生成 |
| knowledge | 会话、检索、回答、引用 | 修改任务 |
| planning | AI 草案、校验、确认写入 | 直接文件解析 |
| dashboard | 聚合统计 | 写业务数据 |
| audit | 查询审计记录 | 决定业务权限 |
| infrastructure.ai | 模型供应商适配 | 业务规则 |

## 5. 核心接口

```java
public interface AiModelGateway {
    ChatResult chat(ChatCommand command);
    <T> T structured(StructuredChatCommand<T> command);
    float[] embed(EmbeddingCommand command);
}
```

```java
public interface ObjectStorageGateway {
    void put(String objectKey, InputStream input, long size, String contentType);
    InputStream get(String objectKey);
    URI presignGet(String objectKey, Duration ttl);
    void delete(String objectKey);
}
```

```java
public interface ProjectAccessGuard {
    ProjectRole requireMember(UUID projectId, UUID userId);
    void requireAdmin(UUID projectId, UUID userId);
    void requireOwner(UUID projectId, UUID userId);
}
```

```java
public interface TaskDependencyPolicy {
    void validateNoCycle(UUID projectId, UUID taskId, Set<UUID> dependencyIds);
}
```

## 6. DTO 约束

- Controller 只接收 Request DTO，不直接接收 Entity。
- Application Service 返回 View DTO。
- UUID 使用字符串的标准连字符格式。
- 时间点使用 ISO-8601 UTC，例如 `2026-07-18T08:30:00Z`。
- 日期使用 `yyyy-MM-dd`。
- 枚举在 JSON 中使用大写英文值。

## 7. Mapper 与 SQL

- 简单单表操作使用 MyBatis-Plus `BaseMapper`。
- 复杂统计、向量检索和项目范围过滤使用显式 SQL。
- Mapper XML 路径：`backend/src/main/resources/mapper/<module>/`。
- 禁止在 Service 中拼接 SQL。
- 所有项目级 SQL 必须显式包含 `project_id = #{projectId}`。

## 8. 前端模块结构

```text
frontend/src/
├── api/
├── components/
├── layouts/
├── router/
├── stores/
├── styles/
└── modules/
    ├── auth/
    ├── project/
    ├── work/
    ├── document/
    ├── knowledge/
    ├── planning/
    ├── dashboard/
    └── audit/
```

每个前端模块内部可包含 `pages`、`components`、`api.ts`、`types.ts` 和 `store.ts`，避免按全局 `views/services/models` 横向堆叠。
