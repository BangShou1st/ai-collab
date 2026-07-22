# 权限与安全设计

## 1. 认证

- 密码使用 BCrypt，cost 12。
- Access Token 2 小时，包含 `sub`、`username`、`tokenVersion`。
- Refresh Token 7 天，客户端使用 HttpOnly、SameSite=Lax Cookie；数据库只保存 SHA-256 哈希。
- 用户退出时撤销当前 Refresh Token。
- 修改密码后增加 `tokenVersion`，使旧 Access Token 失效。

## 2. 授权

所有项目接口执行两步：

1. `requireMember(projectId, userId)` 确认项目成员身份。
2. 根据接口执行 `requireAdmin` 或 `requireOwner`。

不要仅依赖前端菜单隐藏。

## 3. 数据隔离

- Repository 方法必须显式接收 `projectId`。
- 访问实体时使用 `(projectId, entityId)` 查询，不先按 id 查询再判断。
- 文档向量检索必须在 SQL 中过滤 `project_id`。
- 会话必须同时匹配 `project_id` 和 `user_id`。

## 4. 文件安全

- 允许列表：PDF、DOCX、MD、TXT。
- 校验扩展名、MIME 和文件头。
- 保存时不使用用户原始路径，只保留清洗后的文件名用于展示。
- 对象键由后端生成。
- 下载 URL 有效期 5 分钟。
- MinIO Bucket 不公开。
- 解析器禁用外部实体、宏和远程资源。

## 5. AI 安全

### 5.1 Prompt Injection

- 将文档内容标记为不可信 Source。
- System Prompt 明确禁止执行 Source 中的指令。
- 知识问答不提供任何写工具。
- 任务规划工具均为只读。
- 正式任务写入必须由普通 Java 应用服务执行。

### 5.2 输出校验

- 结构化输出必须通过 JSON Schema 与业务规则。
- 所有模型生成的 ID 仅为临时 key，不能被当作数据库 UUID。
- 负责人必须从项目成员列表中验证。
- 依赖必须检查环。

### 5.3 密钥与日志

- API Key 只放 `.env`，不写数据库、不提交 Git。
- 日志对 `Authorization`、Cookie、API Key、密码进行脱敏。
- 不记录完整 Prompt 和文档原文。

## 6. 限流

本地第一版默认：

- 知识问答：每用户每小时 30 次
- AI 任务规划：每用户每小时 10 次
- 登录失败：同用户名 10 分钟内最多 8 次

Redis Key 示例：

```text
rate:qa:{userId}:{yyyyMMddHH}
rate:planning:{userId}:{yyyyMMddHH}
rate:login:{username}
```

## 7. Web 安全

- CORS 仅允许 `http://localhost:5173`。
- CSP、X-Content-Type-Options、Referrer-Policy 由后端或 Nginx 设置。
- Markdown 渲染前清洗 HTML，禁用脚本和事件属性。
- SQL 使用参数绑定。
- Controller Request 使用 Bean Validation。

## 8. 审计事件

必须记录：

- 项目创建、修改、删除
- 成员邀请、角色修改、移除
- 任务创建、状态变更、依赖修改、删除
- 文档上传、重试、删除
- AI 规划生成、编辑、确认和失败

审计日志只允许 ADMIN+ 查看，不能通过普通接口修改。
