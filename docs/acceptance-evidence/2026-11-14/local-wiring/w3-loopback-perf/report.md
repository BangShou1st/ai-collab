# W3：本机回环守卫、demo6 拆因与 ArchUnit

- Lane：`wiring/loopback-perf-archunit`
- Worktree：`E:\project\ai-collab-wt\wiring-loopback-perf`
- 基线：`1f95447e`
- 状态词：`IMPLEMENTED` / `TESTED(UNIT)` / `MEASURED_LOCAL` / `NOT_RUN` / `BLOCKED`

## 实现

- `IMPLEMENTED`：新增 `LocalOutboundTargetPolicy`，配置键
  `ai.outbound.local-allowed-targets`（默认空）。非 `local/test` profile 配置非空时
  在启动阶段抛出 `IllegalStateException`；启用后仅允许精确 `host:port` 且 DNS 全部
  解析为 loopback 的 `http/https` 目标。
- `IMPLEMENTED`：`OutboundEndpointPolicy.requireModelEndpoint` 接入
  `JsonHttpModelClient` 的 GET/POST/stream/proxyDiagnostic 路径，以及
  `AdminAiProviderService`、`UserAiProviderService` 的保存/校验路径；公网 HTTPS
  合约保持不变。
- `IMPLEMENTED`：`pom.xml` 引入 `archunit-junit5:1.4.2`（test scope），并新增 3 条规则：
  domain 不新增 infrastructure 依赖、`api.controller` 不依赖 `*Mapper*`、
  `*Repository` 接口只在 `infrastructure/repository`。既有 5 条 domain→infrastructure
  依赖边（含 `ParsedDocument$PageBoundary`）显式冻结。
- `IMPLEMENTED`：完成 demo6 同进程隔离测量与拆因，见 `demo6-breakdown.md`。

## 验证

| 项 | 状态 | 原始输出 |
| --- | --- | --- |
| 白名单、Provider、MCP loopback、模型 HTTP、ArchUnit 定向套件 | `TESTED(UNIT)`：93 tests，0 failures/errors/skips | `raw/affected-tests.txt` |
| 白名单/ArchUnit 首轮定向套件（含 1 次 DSL 修正前失败） | `TESTED(UNIT)`：最终 26 tests，0 failures/errors/skips | `raw/guard-unit-windows.txt` |
| demo6 10k 任务、87,495 bytes 响应、JIT/日志级别矩阵 | `MEASURED_LOCAL` | `demo6-breakdown.md`、`raw/demo6-*.json`、`raw/demo6-db-explain*.txt` |
| 全量 Java `mvnw verify` | `NOT_RUN`（按编排由中心在 cherry-pick 后统一执行） | — |
| 生产 profile 真进程启动 | `NOT_RUN`（单元测试覆盖启动守卫；Wave 2 由中心统一真进程） | — |

## demo6 事实摘要

- JIT on：DEBUG HTTP P95 `267.8ms`、INFO HTTP P95 `243.0ms`；同页 SQL median
  `217.138ms`。
- JIT off：DEBUG HTTP P95 `29.7ms`、INFO HTTP P95 `30.0ms`；同页 SQL median
  `24.662ms`。
- Jackson：87,495 bytes 响应，P95 `0.391ms`。
- DEBUG 日志：约 `15,458–19,329 bytes/请求`，但 JIT off 下未形成可区分延迟。
- curl：transfer P95 `298.5ms`，进程 wall P95 `314.8ms`，进程开销 P50 `10.2ms`；
  历史 `1356.5ms` 未复现。

## 阻塞与未执行

- `BLOCKED`：无。
- `NOT_RUN`：全量 Java 门禁、生产 profile 真进程启动；原因是本 lane 只负责实现与
  lane 级验证，中心将在集成后运行全量门禁与 Wave 2。

## 证据索引

- `raw/guard-unit-windows.txt`、`raw/affected-tests.txt`：测试原始输出。
- `raw/demo6-debug.json`、`raw/demo6-info.json`、`raw/demo6-debug-jit-off.json`、
  `raw/demo6-info-jit-off.json`：HTTP 测量。
- `raw/demo6-db-explain.txt`、`raw/demo6-db-explain-jit-off.txt`：DB 拆因。
- `raw/demo6-serialization.json`：Jackson 探针。
- `raw/demo6-curl-client.json`：curl 客户端。
- `raw/app-18093-*.txt`：隔离进程原始 stdout。
