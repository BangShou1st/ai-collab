# 前端设计

## 1. 技术栈

- Vue 3
- TypeScript
- Vite
- Element Plus
- Pinia
- Vue Router
- Axios
- Markdown 渲染与代码高亮

## 2. 页面清单

| 路由 | 页面 |
|---|---|
| `/login` | 登录 |
| `/invite/:code` | 邀请预览与设置账号 |
| `/projects` | 我的项目 |
| `/projects/:projectId/dashboard` | 项目概览 |
| `/projects/:projectId/board` | 任务看板 |
| `/projects/:projectId/milestones` | 里程碑 |
| `/projects/:projectId/documents` | 文档知识库 |
| `/projects/:projectId/knowledge` | 知识问答 |
| `/projects/:projectId/planning` | AI 任务规划 |
| `/projects/:projectId/members` | 成员管理 |
| `/projects/:projectId/audit` | 操作日志 |

## 3. 项目布局

左侧项目导航：概览、任务、里程碑、文档、知识问答、AI 规划、成员、日志。顶部展示项目名、当前用户和退出入口。

根据角色隐藏入口，但后端权限仍是唯一可信边界。

## 4. 任务看板

- 五列对应五种任务状态。
- 第一版不做拖拽库；状态变更通过任务卡菜单完成，降低实现复杂度。
- 支持负责人、优先级、里程碑筛选。
- 卡片显示标题、负责人、截止日期、优先级和依赖阻塞标记。
- BLOCKED 任务必须展示未完成前置任务数量。

## 5. 文档页面

表格字段：文件名、类型、大小、状态、分块数、Embedding 模型、上传者、更新时间、操作。

- 上传成功后轮询状态，每 3 秒一次，最多 10 分钟。
- FAILED 显示错误摘要与重试按钮。
- READY 可下载和用于问答。
- 删除操作二次确认。

## 6. 知识问答页面

- 左侧会话列表，右侧消息区。
- 回答正文支持 Markdown。
- 每条回答下展示引用卡片：文件名、标题、相似度、引用片段。
- 点击引用打开文档信息抽屉，不在第一版实现 PDF 页码跳转。
- `insufficientEvidence=true` 时显示明显提示，不伪装成正常答案。

## 7. AI 规划页面

分三步：

1. 输入目标、时间、最大任务数、选择文档和约束。
2. 展示生成状态，失败可重试。
3. 编辑草案并确认。

草案编辑器包含：

- 里程碑表格
- 任务表格
- 依赖选择器
- 风险与假设区域
- 来源文档标签

前端在提交前进行基础校验，但不替代后端校验。

## 8. 状态管理

- `authStore`：用户、Token、刷新状态
- `projectStore`：当前项目、角色、成员
- 其他页面优先使用组件局部状态和 API Query，不把所有数据放入 Pinia

Access Token 仅保存在内存；Refresh Token 使用 HttpOnly Cookie。若本地开发阶段暂时放在内存与安全 Cookie组合之外，不得使用长期 localStorage 保存明文 Refresh Token。

## 9. API 封装

```ts
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string
  timestamp: string
}
```

Axios 拦截器负责：

- 添加 Access Token
- 401 时串行刷新，避免并发刷新风暴
- 展示业务错误信息
- 将 `requestId` 输出到开发控制台

## 10. 可访问性与展示

- 所有输入框有 label
- 状态不只依赖颜色，同时显示文字
- 表格和按钮支持键盘访问
- 主要页面兼容 1366×768 和常见笔记本分辨率
- 演示数据不含真实个人隐私
