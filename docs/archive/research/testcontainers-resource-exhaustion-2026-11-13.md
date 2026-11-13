# Testcontainers 本机资源耗尽定位（2026-11-13）

> 性质：测试基础设施诊断；不改变生产行为、不削弱 Testcontainers 断言。
> 相关证据：[P2-B1 Java verify attempt](../../acceptance-evidence/2026-11-13/p2b1-real-process-evidence/raw/tests/java-verify-local-attempt.txt)

## 1. 现象

P2-B1/P2-A 的本机 `mvnw verify` 在大量 Testcontainers 集成测试后出现：

- `CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`；
- 个别 `NoSuchElementException`（同一测试类单独重跑通过）；
- 失败类不固定，常见于 Agent/RAG/PostgreSQL 集成测试；
- CI 分片全绿，同 HEAD 的定向测试也全绿。

## 2. 环境事实

```text
Docker Desktop Server 29.8.0
Docker VM: 8 vCPU / 1,995,673,600 bytes ≈ 1.86 GiB
常驻：ai-collab-postgres / redis / minio
```

本机 Docker VM 内存远低于 CI runner，也低于常见 4C/8G staging。

## 3. 根因判断

证据指向**测试资源放大**，不是单一生产代码缺陷：

1. Surefire 默认单 fork、复用 fork，但 Spring TestContext 默认最多缓存 32 个上下文；
2. 每个 Boot 上下文默认 Hikari `maximum-pool-size=10`，多个缓存上下文会同时保留连接池；
3. 全量套件包含数十个独立 `PostgreSQLContainer`，容器启动/停止与 Hikari 建连叠加；
4. Docker VM 仅 1.86GiB，容器内存 + 连接数 + 缓存上下文超过可用资源后，表现为随机 JDBC 获取失败；
5. 失败类单独重跑通过、CI 全绿，不支持“固定代码路径必然失败”的判断。

## 4. 最小修复

只在 `ai-collab-backend/src/test/resources/application.properties` 收敛测试资源：

```properties
spring.datasource.hikari.maximum-pool-size=4
spring.datasource.hikari.minimum-idle=0
spring.test.context.cache.maxSize=12
```

理由：

- 不改生产连接池；
- 不跳过任何 Testcontainers 测试；
- 不改变测试并发/断言；
- 把缓存上下文的理论连接上限从 `32 × 10` 降到 `12 × 4`，并允许空闲连接归零。

## 5. 验证

本轮验证：

| 检查 | 结果 |
| --- | --- |
| `McpAdministrationPostgresIntegrationTest`（含 V99/Flyway/真实 PostgreSQL 容器） | PASS |
| 定向 MCP 测试（43 项） | PASS |
| 本机全量 `mvnw verify` | **未重跑**；受 1.86GiB Docker VM 限制，仍以 CI 分片为权威 |

## 6. 剩余边界与 Deferred

- 这是**资源收敛**，不是 Testcontainers 共享容器/Service Mode 的根治；
- 如果后续全量本机 verify 仍随机失败，应另开维护任务评估共享 PostgreSQL 容器或 CI 分片；
- 不得通过跳过集成测试、降低断言或关闭 Testcontainers 来换取本地绿；
- 本轮不改 CI workflow、不改生产资源、不新增镜像。
