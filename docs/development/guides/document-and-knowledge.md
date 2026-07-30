# 文档与知识库实现指南

## 功能边界

本指南覆盖项目文档上传、MinIO 原文件、解析、分块、Embedding、pgvector 检索、知识问答、SSE、引用、反馈和评测。

文档正文和模型输出都是不可信数据。任何读取、检索和问答都必须限定当前项目。

## 当前入口

上传与处理：

- `document/api/controller/DocumentController.java`
- `document/application/service/DocumentApplicationService.java`
- `DocumentRegistrationService.java`
- `DocumentProcessingService.java`
- `document/domain/service/DocumentFilePolicy.java`
- `document/infrastructure/storage/MinioDocumentStorageGateway.java`
- `document/infrastructure/parser/TikaDocumentParser.java`
- `document/infrastructure/mapper/DocumentMapper.java`

知识问答：

- `knowledge/api/controller/KnowledgeController.java`
- `knowledge/application/service/KnowledgeQuestionApplicationService.java`
- `KnowledgeStreamQuestionService.java`
- `KnowledgePersistenceService.java`
- `knowledge/domain/service/KnowledgeContextBuilder.java`

前端：

- `modules/document/DocumentView.vue`
- `modules/document/document-api.ts`
- `modules/knowledge/KnowledgeView.vue`
- `modules/knowledge/knowledge-api.ts`

## 项目状态

文档读取在所有项目状态可用。文档写操作统一经过 `ProjectWriteGuard`：

| 状态 | 列表/详情/下载 | 上传/删除/重试/重建索引 |
|---|---:|---:|
| PREPARING | 是 | 是 |
| ACTIVE | 是 | 是 |
| COMPLETED | 是 | 否，`PROJECT_READ_ONLY` |
| ARCHIVED | 是 | 否，`PROJECT_READ_ONLY` |

不能把只读状态返回成 `PROJECT_NOT_FOUND`。

## 上传数据流

```text
Controller multipart
  → requireAdmin + requireWritable
  → DocumentFilePolicy
  → MinIO put
  → 短注册事务：
       FOR UPDATE 锁项目
       再校验可写状态
       检查 100 文档上限
       insert project_document + audit
  → after-commit 事件
  → 异步解析、分块、向量化
```

MinIO 写入在数据库事务外。注册失败必须补偿删除对象：

```java
try {
    registration.registerUploadedDocument(entity);
} catch (RuntimeException exception) {
    compensateObject(objectKey);
    throw exception;
}
```

不要把整个 `MultipartFile`、正文或对象 key 写入日志。日志只允许不可逆短摘要或资源 ID。

## 文件校验

当前支持 PDF、DOCX、Markdown、TXT，单文件最大 20MB。校验顺序：

1. 非空和大小。
2. 文件名、显示名称长度。
3. 扩展名白名单。
4. MIME/魔数与类型一致性。
5. 解析后的正文清洗和大小边界。

前端校验只用于即时反馈，后端必须重复执行。

## 异步处理

状态：

```text
UPLOADED → PARSING → INDEXING → READY
                      ↘ FAILED
```

处理 worker 使用 processing token/heartbeat 防止旧 worker 覆盖新重试。失败必须保存安全、简短、用户可理解的错误；不保存正文或上游密钥。

分块要求：

- 稳定 chunk_no。
- 标题和正文规范化。
- content_hash 用于去重或追踪。
- metadata 使用受控 JSON，PDF 页码等定位信息保留。
- Embedding provider/model/dimension 与向量同时记录。

## 项目隔离检索

检索 SQL 必须包含：

```sql
WHERE project_id = #{projectId}
  AND document_id = ANY(...)
```

文档 ID 还需验证属于当前项目且状态为 READY。不能接收前端 documentIds 后直接进入向量查询。

`KnowledgeContextBuilder` 只接收经过项目范围校验的候选；Prompt 中明确把文档内容视为引用材料而不是系统指令。

## 问答和引用

问答流程：

1. 校验项目成员和会话所有者。
2. 校验问题长度、频率和文档选择。
3. 生成 query embedding。
4. 只检索当前项目 READY 文档。
5. 达不到相似度阈值时明确证据不足，不让模型编造。
6. 模型回答与 citation 在服务端结构化校验。
7. 在短事务中保存用户消息、助手消息和引用。

引用至少包含 documentId、documentName、chunkId、rank、similarity、quote 和定位 metadata。前端不得把模型返回的 HTML 直接插入 DOM。

## SSE

流式端点使用 `text/event-stream`，事件至少区分：

- `token`
- `citation`
- `error`
- `done`

前端解析必须处理分片、CRLF 和连接结束前的末尾缓冲。使用 `AbortController` 取消时，不把用户主动取消显示为网络错误。

发生流内错误后必须停止继续发送 token，并保证客户端能结束 loading 状态。

## API 示例

上传：

```http
POST /api/v1/projects/{projectId}/documents
Content-Type: multipart/form-data

file=<binary>
displayName=需求说明
```

成功返回 202。`PREPARING/ACTIVE` 可写；只读项目返回 409 `PROJECT_READ_ONLY`。

前端：

```ts
async upload(projectId: string, file: File, displayName: string) {
  const body = new FormData()
  body.append('file', file)
  if (displayName.trim()) body.append('displayName', displayName.trim())
  return apiResultFromResponse(
    await httpClient.post<ApiResponse<ProjectDocument>>(
      `/projects/${projectId}/documents`,
      body,
    ),
  )
}
```

不要手动设置 multipart boundary。

## 测试

至少覆盖：

- PREPARING 上传成功；COMPLETED/ARCHIVED 返回只读；不存在仍返回 NOT_FOUND。
- 非管理员上传、跨项目文档访问。
- 空文件、超限、伪造扩展名。
- MinIO 写成功但注册失败时补偿删除。
- worker 重试和旧 token 失效。
- PostgreSQL JSON metadata 和 pgvector 项目隔离。
- 证据不足拒答、citation 持久化。
- SSE 分片、错误、done、取消。
- 前端批量队列中单文件失败不影响其他文件。

## 发给 Claude 的提示

```text
读取 AGENTS.md、docs/development/guides/document-and-knowledge.md 和
docs/development/api-contract-checklist.md。
先画出“外部存储、数据库短事务、after-commit、异步 worker”边界；
明确项目状态、权限、补偿、失败状态和真实端到端验收。
```

## 可交给 MiMo 的任务

可以：按已有模式增加一个文档状态中文映射，或为既定 SSE parser 增加一个明确 CRLF 测试。

禁止：让 MiMo 修改 MinIO、事务、处理状态机、向量 SQL、Prompt、引用结构或 SSE 协议。
