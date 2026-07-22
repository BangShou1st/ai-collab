# AI Collab 认证 Postman 集合

本目录用于手工验证登录、JWT Access Token、HttpOnly Refresh Token Cookie、轮换、当前设备登出和多设备会话。Collection 不会、也不应通过脚本读取 Refresh Token；Cookie 由 Postman Cookie Jar 自动保存和随匹配请求发送。

## 前置条件与导入

1. 启动依赖服务：在 `E:\ai-collab\ai-collab-deploy` 执行 `docker compose up -d`。
2. 启动后端：在 `E:\ai-collab\ai-collab-backend` 执行 `./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local`。
3. 先发送 **01 Health**，预期响应为 `{"status":"UP"}`。
4. 在 Postman 选择 **Import**，导入 `AI-Collab-Auth.postman_collection.json` 与 `AI-Collab-Local.postman_environment.json`，然后在右上角选中环境 **AI Collab Local**。
5. 打开 Collection 中所有成功登录请求，把请求体中的密码替换成根目录 `.env` 的 `DEMO_OWNER_PASSWORD`；不要把该密码写入环境文件、Collection 或提交记录。

环境只定义三个变量：`baseUrl`、`frontendOrigin`、`accessToken`。默认本地后端为 `http://localhost:8080`，允许的前端来源为 `http://localhost:5173`。若改动其中一个，请确保后端 `AUTH_ALLOWED_ORIGINS` 也包含完全相同的 `frontendOrigin`。

## 执行顺序

`01` 可独立执行；首次完整验证按 `02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10` 执行：

- `02 Login Success` 成功后，响应 JSON 的 `data.accessToken` 被脚本保存到 `{{accessToken}}`，而 `Set-Cookie` 被 Cookie Jar 接收。
- `05 Me Without Bearer` 应为 401；`06 Me With Bearer` 使用 `Authorization: Bearer {{accessToken}}`，应为 200。
- `07 Refresh` 不在请求体或 Header 中填写 Refresh Token。Postman 会自动发送 Cookie，服务端轮换它，响应脚本更新新的 Access Token。
- `08 Refresh Without Cookie` 访问 `127.0.0.1`，而本环境主机是 `localhost`；两者在 Cookie Jar 中是不同域，因而应为 401。此请求要求 `baseUrl` 保持默认的 `http://localhost:8080`。
- `09 Logout Current Device` 对缺失、格式错误、未知、过期或已撤销 Refresh Cookie 等凭据状态错误幂等成功，并返回 `Max-Age=0` 的清除 Cookie；数据库等基础设施失败返回 500，但仍返回清除 Cookie。正常执行后，`10 Refresh After Logout` 应为 401。

所有 login、refresh、logout 请求都显式携带 `Origin: {{frontendOrigin}}`，因为服务端会对这些会签发、轮换或清除 Cookie 的端点做精确 Origin/Referer 校验。

## 查看 Cookie 与轮换

在 Postman 请求地址右侧点击 **Cookies**，选择 `localhost`，可看到名为 `ai_collab_refresh_token` 的条目及其 `HttpOnly`、`SameSite=Strict`、`Path=/api/v1/auth` 属性。本地配置为 `Secure=false`；生产环境会启用 Secure。

运行 `02` 后记录 Cookie 的过期时间（无需复制 Token 值），运行 `07` 后刷新 Cookie 列表：Cookie 仍为同名，但已由响应中的 `Set-Cookie` 替换。这就是严格轮换。不要在 Pre-request Script 或 Tests 中使用 `pm.cookies`、`pm.cookies.jar()` 或日志读取/输出该值；HttpOnly 的目的正是让浏览器脚本无法取得原文。

如需从干净状态开始，只能在 Postman 的 Cookies 管理界面删除对应域下的 Cookie，或执行 `09 Logout Current Device`。不要通过请求脚本伪造 Cookie。

## 双设备隔离演示

`localhost` 与 `127.0.0.1` 的做法只是**同一个 Postman Cookie Jar 中按 host 隔离**的近似本机演示：两者指向同一个后端、Cookie 不共享，因此能证明 Cookie 的 host 匹配规则，以及后端为每次登录创建独立 `session_id`。它不等同于真实的同域双设备，也不模拟两个独立浏览器配置文件。

1. 将 `baseUrl` 保持为 `http://localhost:8080`，先执行 **11 Device A Login (localhost)**。
2. 再执行 **12 Device B Login (127.0.0.1)**；在 Cookies 面板会看到两个不同主机的 Cookie。
3. 可手动复制 **07 Refresh** 的请求，把 URL 主机改成 `127.0.0.1` 后发送；两台设备都会各自轮换。不要在脚本中复制 Cookie 值。
4. 对 `localhost` 执行 `09 Logout Current Device`，再把 **07** 的 URL 临时切换到 `127.0.0.1` 发送：设备 B 仍应刷新成功。这证明登出按 `session_id` 仅撤销当前设备会话。

真实同域双设备必须使用两个独立的 Postman environment、profile、workspace 或 Cookie Jar，并让每个设备各自维护 `accessToken` 变量；不要共用同一环境中的 `accessToken`。本集合的单一 `accessToken` 只是便于演示 `/me`，最后一次成功登录或刷新会覆盖它，不影响 Cookie 主机隔离，但不能代表真实双设备的 Access Token 状态。

## 常见结果

业务响应结构固定为 `{"code":"...","message":"...","data":...}`。密码错误为 `401 AUTH_INVALID_CREDENTIALS`；缺失/无效/已登出的 Refresh Cookie 为 `401 AUTH_UNAUTHORIZED`；用户名或密码为空为 `400 VALIDATION_ERROR`；Cookie 端点的错误来源为 `403 AUTH_FORBIDDEN`。Refresh Token 从不出现在 JSON 的 `data`、环境变量或测试日志中。
