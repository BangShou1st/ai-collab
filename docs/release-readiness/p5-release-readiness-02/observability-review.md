# 可观测性整改说明（R-02）

## 1. 现状与改造内容

| 能力 | 整改前 | 整改后 |
| --- | --- | --- |
| management 配置 | 仅 `application-local.yml`（health,info），容器（默认 profile）回落 Boot 默认 | `application.yml` 增加生产基线：exposure=`${MANAGEMENT_ENDPOINTS_EXPOSURE:health,info}`、`show-details=never`、`show-components=never`、probes 开启 |
| 存活/依赖区分 | 无分组探针 | `/actuator/health/liveness`（仅进程存活）与 `/actuator/health`（聚合 DB/Redis/MinIO）分离 |
| MinIO 健康 | 无（对象存储故障不可见） | 新增 `MinioHealthIndicator`（bucketExists 探测；bucket 缺失或连接失败判 DOWN；明细只含 bucket 名与异常类名，不含密钥） |
| 请求关联 | 无 | 新增 `RequestIdMdcFilter`：服务端生成 16 位关联 ID（不采信客户端头），写入 MDC + 回传 `X-Request-Id` 响应头；日志 pattern 输出 `[requestId=...]` |
| 日志 | 无统一 pattern；敏感 Mapper 防泄漏规则只在 local | console 单行 pattern（stdout 收集，容器友好）；敏感 Mapper 防 DEBUG 基线（user/auth-refresh/document/knowledge mapper）**上移到默认 profile，全环境生效** |
| AI 依赖 | — | 刻意不建 HealthIndicator：Chat/Planning/Embedding 是按需外部服务，故障经错误码分类与 `ai_call_log` 表诊断，不与进程存活混淆 |

未新增任何依赖（health/metrics 能力来自既有 `spring-boot-starter-actuator`），未引入监控平台。需要 metrics 时运维可用 `MANAGEMENT_ENDPOINTS_EXPOSURE=health,info,metrics` 显式打开（仍受 JWT 认证保护）。

## 2. 安全边界

- 敏感端点（env/configprops/beans/heapdump/threaddump/mappings/metrics/info/logfile）匿名访问一律 401（SecurityConfig 仅 permitAll `/actuator/health` 与其子路径，其余落入 `anyRequest().authenticated()`）。
- `/actuator/health` 匿名可访问但 `show-details=never`，不向未授权方泄露组件明细。
- 日志不含密钥：Mapper 防泄漏基线全环境生效；健康明细不含凭据；RequestIdMdcFilter 拒绝客户端提供的 ID（防日志注入）。

## 3. 故障诊断方式

| 故障域 | 诊断入口 |
| --- | --- |
| PostgreSQL / Redis | `/actuator/health` 变 DOWN（503）；组件明细经授权通道或读日志 |
| MinIO | `/actuator/health` DOWN（`MinioHealthIndicator`）；具体异常类名在授权后的 details 或日志 |
| AI Provider（Zen/Kilo/Gemini） | 不影响健康；错误码分类（超时/限流/凭据无效）+ `ai_call_log` 表 |
| 单请求定位 | 响应头 `X-Request-Id` → 日志 `grep requestId=<值>` |
