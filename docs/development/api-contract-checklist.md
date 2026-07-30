# API 契约统一检查清单

新增或修改 HTTP 接口时逐项执行。任何一项缺失都表示功能尚未闭环。

## 1. 路径与权限

- 路径使用 `/api/v1`，项目资源使用 `/projects/{projectId}/...`。
- Controller 解析 `@AuthenticationPrincipal Jwt`，Application Service 执行授权。
- 子资源查询同时绑定 `projectId` 和资源 ID。
- 非成员使用 `PROJECT_NOT_FOUND`，角色不足使用稳定的权限错误。

## 2. 后端请求与响应

请求 DTO 使用专用 `record` 和 Jakarta Validation：

```java
public record UpdateExampleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(0) Integer version) {
}
```

响应使用不可变 View，不返回 Entity：

```java
public record ExampleView(
        UUID id,
        UUID projectId,
        String name,
        int version) {
}
```

HTTP 语义：

- 同步创建：201，并在适用时返回 `Location`。
- 异步提交：202。
- 查询/更新：200。
- 成功删除：204，无 JSON body。

## 3. 错误

- 在 `ErrorCode` 定义稳定 code、HTTP 状态和中文默认消息。
- Application Service 抛 `BusinessException`。
- 禁止在 Controller 手写不统一的错误 JSON。
- 前端 `api-result.ts` 增加中文映射。
- 不用 `PROJECT_NOT_FOUND` 表达状态冲突、只读或权限不足。

## 4. OpenAPI

同步 `docs/api/openapi.yaml`：

- path、method、summary、description。
- path/query/header 参数。
- requestBody 的 required、长度、范围和 enum。
- 成功 HTTP 状态及 Envelope/View schema。
- 400/401/403/404/409/429/5xx 中实际可能发生的错误。
- nullable 字段与 Java/TypeScript 一致。

执行：

```powershell
.\scripts\validate-openapi.ps1
```

## 5. TypeScript

类型逐字段匹配 Java View：

```ts
export interface Example {
  id: string
  projectId: string
  name: string
  version: number
}
```

API 只通过统一客户端：

```ts
async update(
  projectId: string,
  exampleId: string,
  payload: UpdateExamplePayload,
): Promise<ApiResult<Example>> {
  return apiResultFromResponse(
    await httpClient.patch<ApiResponse<Example>>(
      `/projects/${projectId}/examples/${exampleId}`,
      payload,
    ),
  )
}
```

禁止：

- `any` 或大范围类型断言。
- 页面直接解析 Axios 响应壳。
- 项目接口遗漏 `projectId`。
- 204 响应强行按 JSON 解析。

## 6. 页面

- OWNER/ADMIN/MEMBER 入口与后端权限一致。
- 写请求期间防止重复提交。
- 空状态、成功、业务错误、网络错误和版本冲突均可见。
- 取消确认不显示为网络失败。
- 原始枚举、action 或 error code 不直接展示。

## 7. 测试

后端至少覆盖：

- 正常请求和 View 字段。
- 非成员、角色不足、跨项目资源。
- 参数边界、状态冲突和版本冲突。
- PostgreSQL 特有 SQL、锁、约束或 JSON/向量映射。

前端至少覆盖：

- URL、HTTP 方法和 payload。
- 成功/错误/空状态。
- 角色入口和中文映射。
- TypeScript 编译与生产构建。

## 8. 完成证据

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
```

最后核对 Java DTO/View、OpenAPI schema 和 TypeScript type 的字段名、可空性和枚举逐项相同。
