# P6-C 实施自审记录（2026-10-25）

本记录为实施者自审，不声称独立 Agent 或人工审核。

- C0：dev/origin/dev 为 0e489824358046443429ad32d53b7143c51979cc，开工工作区干净；main 保持 9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7；PR18 OPEN/Draft/未合并。
- 持久化一致性：所有 Invocation 更新入口以 UPDATE RETURNING 收集实际转换行，事务 afterCommit 计数；包括普通结果、委派成功、取消、批次SKIPPED与UNKNOWN。结果读取和覆盖拒绝均不发布指标。真实PG测试验证成功/UNKNOWN/取消回滚及重放。
- 隔离：状态快照由独立只读REPEATABLE_READ仓储读取；scrape仅内存；失败保留旧快照，首次NaN；无调度锁下SQL。
- 模型出站：Routing仅建立观测作用域，传输层在请求通过本地校验后且真正调用发送时标记。HTTP执行池显式传递无身份的观测对象，ThreadLocal在finally清理；配置、密钥、SSRF与未开始的执行器拒绝不计。
- 标签：Worker/Model/Invocation/Run状态均固定枚举，没有user/run/invocation/model/tool/URL/错误正文标签；没有额外Token Counter。
- 日志：Worker/工具显式MDC作用域，不继承HTTP上下文；支持嵌套恢复、异常/取消和线程复用。新增日志只有状态/耗时/类型；删除所触及执行边界的原始异常消息日志。requestId pattern保留，SecurityConfig不改。
- 兼容：Scheduler源码blob与基线相同；P6-B的状态转换条件、claim/fencing与重试策略保持。V68仅索引，历史迁移不变；前端、依赖和Actuator默认暴露均未改。
- 统计边界：Invocation Timer只为已知本次Scheduler提交时间的调用采样；审批/委派/未调度与跨重启未知提交时间仅Counter，不伪造耗时。告警平台未接通。
- 迁移运行风险：常规事务CREATE INDEX在大表升级时需要写锁；3万行样本只验证正确性与代表性查询计划，不承诺生产建索引耗时或任意规模刷新性能。部署窗口应按实际表规模安排。

证据：[基线/不变量/矩阵/测试](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/)。最终结果见 [交付报告](../reports/p6-agent-observability-01-delivery-20261025.md) 与最终同SHA CI回执。
