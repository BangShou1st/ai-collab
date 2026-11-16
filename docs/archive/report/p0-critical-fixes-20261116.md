# P0/P1 关键缺陷修复记录（2026-11-16）

## 基线

- 分支：`dev`
- 基线提交：`a8c1aab7`
- 范围：看板缓存 single-flight、RocketMQ 文档事务消息、MQ 消费幂等占位

## 根因与修复

### 1. DashboardCacheService 异步重建遗留未完成 future

- 根因：逻辑过期异步重建占用 `inFlightLoads` 槽位并设置 `leaderClaimed`，但结束时只移除槽位，未完成 `future`。写失效后的冷 miss 进入 `singleFlight` 时会 `join` 该 future，永久阻塞。
- 修复：异步重建成功时 `complete(value)`，失败时 `completeExceptionally`；执行器拒绝任务时同样完成异常结果并清理槽位。`singleFlight` leader 对 `RuntimeException` 与 `Error` 均先完成异常结果再抛出。
- 行为：冷 miss 与异步重建继续共用同一槽位；等待者一定能退出，重建失败不会污染后续回源。

### 2. RocketMQ 文档事务消息使用普通生产者

- 根因：`RocketMqProducerLifecycle` 构造 `DefaultMQProducer`，其 `sendMessageInTransaction` 直接抛 `RuntimeException("sendMessageInTransaction not implement, please use TransactionMQProducer class")`，异常被门面捕获后永远回落 `AFTER_COMMIT` 普通消息。
- 修复：生命周期增加事务监听器构造函数，文档链路创建 `TransactionMQProducer` 并挂载 `DocumentRegistrationTransactionListener`；同一实例同时发送普通处理通知，避免同组多生产者。`DocumentRegistrationExecution` 记录本地事务已提交，broker 确认状态未知或在提交后抛异常时返回 true，禁止调用方重复注册；仅本地事务未执行时返回 false 允许安全回落。
- 行为：上传受理真正经过半消息 → 本地注册事务 → 提交/回滚 → 消费；未知状态由 broker 回查以文档状态行裁决。

### 3. idempotency_record 处理完成不释放

- 根因：消费处理器只在系统异常路径释放幂等占位，`DocumentProcessingService` 将业务失败标记为 `FAILED` 后正常返回，占位保留 24 小时；手动重试和 `READY` 后重建索引的新消息会被当作重复投递丢弃。
- 修复：`DocumentProcessingMqHandler` 在 `finally` 中释放占位，覆盖成功、业务失败和系统异常；释放失败仅记录日志，不改变 ACK 或原始异常传播。
- 行为：`idempotency_record` 变为处理中租约，完成后由状态前置校验与 claim CAS 兜底；24 小时保留期仅作为进程崩溃残留兜底。新增回归覆盖 READY 后立即重建索引。

## 验证

- 定向单测：`DashboardCacheServiceTest`、`DocumentProcessingMqHandlerTest`、`RocketMqProducerLifecycleTest`、`DocumentRegistrationTransactionListenerTest` 共 26 项通过。
- RocketMQ 集成：`DocumentMqPipelineIntegrationTest` 6 项通过（真实 Postgres + RocketMQ Testcontainers），覆盖事务发送、上传到 READY、重复投递、消费重试和 READY 后重建索引。
- 后端完整门禁：`.\mvnw.cmd -B -ntp verify`，2954 tests，0 failures，0 errors，62 skipped。
- 前端门禁：`pnpm lint && pnpm typecheck && pnpm test && pnpm build`，71 个测试文件 / 525 项测试通过，生产构建成功。

## 边界

- 未新增数据库迁移，未改变 HTTP API、MQ topic/consumer group 或默认开关。
- 未处理 ANN 索引、流式问答权限查询、V101 分页索引、staging 网络隔离和生产默认开关等其他审计项。
