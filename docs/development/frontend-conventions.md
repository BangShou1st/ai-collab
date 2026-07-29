# 前端开发规范

适用栈：Vue 3、TypeScript、Vite、Element Plus、Pinia、Axios。

## 模块结构

每个业务模块就近保存页面、API、类型和组件：

```text
modules/example/
├── ExampleView.vue
├── example-api.ts
├── types.ts
└── components/
```

共享内容仅放入 `api/`、`stores/`、`shared/` 和 `styles/`。不要为了复用一个短函数建立通用框架。

## API 访问

- 登录态接口统一使用 `httpClient`，公开接口使用 `anonymousHttpClient`。
- API 模块返回 `ApiResult<T>`，页面不要直接解析 Axios 响应壳。
- 错误统一经过 `normalizeApiError`，新增后端错误码同步中文映射。
- 前端类型必须逐字段匹配 Java View/OpenAPI；禁止用大范围 `any` 掩盖契约差异。
- 项目 API 必须显式接收 `projectId`，子资源 URL 不能遗漏项目作用域。

示例：

```ts
async get(projectId: string): Promise<ApiResult<Example>> {
  return apiResultFromResponse(
    await httpClient.get<ApiResponse<Example>>(`/projects/${projectId}/example`),
  )
}
```

## 中文界面

- 用户可见的标题、按钮、表单、状态、错误、空状态和确认文本使用简体中文。
- `AI`、`PDF`、模型名称等公认技术缩写可以保留；内部枚举、action/entity code 和英文回退不得直接展示。
- 枚举统一通过 `shared/display-labels.ts` 转换；未知值显示“未知状态/未知操作/未知对象”，不能泄漏原始内部值。
- 日期统一通过共享格式化函数显示中文格式；输入和接口仍使用 ISO 日期。
- Element Plus 使用 `zh-cn`。

## 权限与状态

- 页面按角色隐藏无权限入口，减少误操作；后端仍是唯一授权边界。
- 项目路由角色使用 `project-context-store`，切换项目和退出时必须清理旧上下文。
- MEMBER 只能看到后端允许的操作。不要因为按钮隐藏就省略 403 错误处理。
- 写请求期间禁用重复提交；删除必须二次确认；取消确认不显示成网络错误。
- 乐观锁资源必须提交 `version`，冲突后刷新服务端真相。

## 页面闭环

每个现有页面至少处理：

- 首次加载与手动刷新；
- 空数据；
- 业务错误和网络错误；
- 重复点击；
- 成功反馈；
- 窄屏不横向溢出；
- 路由切换后的陈旧状态清理。

筛选逻辑必须说明是在服务端还是当前结果集前端筛选。无界数据不得只在前端分页。

## 安全

- 禁止渲染未经清洗的模型或 Markdown HTML；继续使用 DOMPurify。
- Token 只在认证 Store 的既有机制中处理，不写日志、URL、错误详情或持久化调试数据。
- 邀请码只用于当前路由和请求，不进入日志。
- 展示后端 detail 前先确认其已脱敏；默认不提供原始 JSON 调试弹窗。

## 测试

新功能或缺陷至少覆盖：

1. API 方法、URL、HTTP 方法和关键 payload；
2. 角色入口可见性；
3. 成功、错误、空状态；
4. 状态转换、版本号和取消确认；
5. 中文显示映射，未知内部值不能泄漏。

执行：

```powershell
pnpm test
pnpm typecheck
pnpm build
```

构建的 chunk-size 警告是已知优化项，不等于构建失败；真正的类型或构建错误必须清零。

## 完成条件

用户能从现有导航进入功能并完成业务闭环；界面无原始枚举和主要英文文案；权限入口与后端一致；测试、类型检查和生产构建全部通过。
