# 可观测性与告警（observability-and-alerting）

> 基线：`p5-release-readiness-02/observability-review.md`（R-02 整改）。本文补充部署视角缺口与最小可用方案。

## 1. 已具备（`VERIFIED`，代码/配置在库）

| 能力 | 实现 |
| --- | --- |
| 健康探针 | `/actuator/health`（聚合 DB/Redis/MinIO，`MinioHealthIndicator` 探测 bucket）与 `/actuator/health/liveness`、`/readiness` 分离；Dockerfile HEALTHCHECK + compose 依赖服务 healthcheck |
| 健康端点安全 | `show-details=never`；除 health 外端点（env/metrics/info 等）匿名一律 401（CI 冒烟断言） |
| 请求关联 | `RequestIdMdcFilter`：服务端生成 16 位 requestId（不采信客户端头，防日志注入），日志 pattern 含 `[requestId=...]`，响应头 `X-Request-Id` |
| 日志输出 | stdout 单行 pattern（容器友好）；敏感 Mapper 强制 INFO 防泄漏；日志无密钥已实测（grep 计数 0） |
| AI 调用诊断 | 不建 Provider HealthIndicator（刻意设计）；Provider 故障经错误码分类 + `ai_call_log` 表诊断 |

## 2. 缺口（部署前必须明确，不得宣称告警就绪）

| 缺口 | 现状 | 最小可用方案（建议，非强制选型） |
| --- | --- | --- |
| **日志采集** | 仅 stdout，无采集器、无文件滚动 | 最小：`docker logs` + logrotate（json-file driver 限额）；进阶：单机 Promtail/Vector → Loki。`ENVIRONMENT_DEPENDENT` |
| **指标** | 无 micrometer-registry-prometheus 依赖；可用 `MANAGEMENT_ENDPOINTS_EXPOSURE=health,info,metrics` 显式打开 metrics（仍受 JWT 保护） | 首署可接受无指标平台；资源层面用 `docker stats` / 主机监控兜底 |
| **告警通道** | **完全未接通**（rc-configuration-review D 节原文："不能因本地 Actuator 可用而宣称告警就绪"） | 最小可用：cron 探测 `https://<域名>/actuator/health` 非 UP 即经既有 IM/邮件通道告警 `[部署环境补充：通道]`；备份脚本退出码非 0 告警（backup README 已列） |
| 资源监控 | 无 | 主机层（云监控 / node_exporter）`ENVIRONMENT_DEPENDENT` |

## 3. 发布日可观测检查单（并入 deployment-procedure 步骤 12）

1. `curl /actuator/health`、`/liveness` UP。
2. `docker logs app --since 15m` 无 ERROR 刷屏、无密钥。
3. 一次真实请求的日志可凭 `X-Request-Id` 检索关联。
4. 告警通道实测：人为停一个依赖（如 redis）→ health 503 → 告警送达（依赖已验证的自愈行为：恢复后自动 UP）。
5. 备份脚本首跑产物 + 退出码 0。

## 4. 明确边界

- 健康端点存在 ≠ 生产告警已接通；本文所有 `[部署环境补充]` 项未回填前，可观测性裁决为 **`ENVIRONMENT_DEPENDENT`（不阻断首署启动，但阻断"生产就绪"结论）**。
- 不为消除该缺口而放开敏感 Actuator 端点匿名访问。
