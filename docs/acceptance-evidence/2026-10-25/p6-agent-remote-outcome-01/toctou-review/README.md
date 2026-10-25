# 并发安全补充复核证据

- [修复前](before.json)：6 项、5 失败、1 通过；三个出站交错误判 FAILED，两个无当前 Invocation 的写入产生孤立结果；反向 CAS 原本正确。
- [修复后必要回归](after.json)：11 类、167 项，0 失败/错误/跳过；包含最终新增的 9 项数据库并发/身份用例。
- [最终源码 Manifest](source-manifest.json)：本补丁生产与测试文件 SHA-256，Scheduler blob 与基线一致；无迁移/前端变化。
- [后续 CrossTick CI 修复](cross-tick-ci-fix.json)：只同步新异常结算 API 的 mock/verify；先跑 7 项，再跑 P6-B 120 项，全部通过；生产源码未改。
- [复核说明](../../../../archive/reviews/p6-agent-remote-outcome-01-toctou-review-20261025.md)：事务交错、修复与保证边界。

CountDownLatch 控制提交时刻，`pg_blocking_pids` 确认目标连接已进入真实锁等待，再释放对方事务。
没有 Thread.sleep，也没有真实外部写入/付费模型调用。最后提交 SHA 和最新 CI 链接由最终回复提供。
当前裁决：`P6_AGENT_REMOTE_OUTCOME_01_CI_PENDING`，未签署 ACCEPTED。
