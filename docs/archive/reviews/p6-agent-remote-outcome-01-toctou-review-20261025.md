# P6-B 并发安全补充复核

日期：2026-10-25。基线：`d694c1adbe80a1c29003d9db45942556e7fdea96`。
裁决保持 `P6_AGENT_REMOTE_OUTCOME_01_CI_PENDING`，不提前签署 ACCEPTED。

## 复现与根因

真实 PostgreSQL `READ COMMITTED` 下，出站线程已经 UPDATE dispatch 标记、尚未提交，
另一线程的 dispatchedUnsafe 普通 SELECT 仍返回 false。旧超时/异常处理据此生成 FAILED，
进入 recordToolResult 时虽等待 Run/Invocation 行锁，但拿到锁后不重新读取标记分类，导致已出站操作误记为失败。
原测试只覆盖已提交标记，没有覆盖未提交事务的 MVCC 可见性窗口。

另一个缺陷是结果校验仅在查询到 Invocation 时执行；结果为零行时仍记账、生成 Step/Event，最终 UPDATE 影响零行也未检查。
真实不存在的 toolCallId 和仅存在于旧轮次的 ID 都会造成孤立结果。

修复前新增 6 项：5 项失败、1 项通过、0 错误、0 跳过，见 [before.json](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/toctou-review/before.json)。
三个异常交错均把预期 UNKNOWN 记成 FAILED，其中超时和 Provider 错误的旧分支构造 retryable=true；
两个无当前 Invocation 的用例同时观察到额度 +1、版本 +1、孤立 Step/Event 各 1。
反向交错“先结算再出站”的 CAS 已有正确防护，原代码通过，未人为制造缺陷。

## 最小修复与事务边界

- 新增 `recordToolExecutionFailure` 事务入口，仅用于本地超时/异常；可信工具最终回执仍走原 recordToolResult。
- 统一 Run → Invocation 加锁顺序，Worker Scope 的 Lease 校验锁 Run；无 Scope 的内部调用也先锁 Run。
- 持锁读取最新已提交的执行阶段与安全类别，在同一事务中确定 UNKNOWN 或原失败，再提交额度、Step、Invocation 和 Event。
- Runtime 不再使用事务外 dispatchedUnsafe 为异常定性。规划超时的可选核对仍可读取提示；核对失败/未命中后的最终分类走原子入口。
- toolCallId 必须命中当前轮次唯一 PENDING Invocation 并匹配工具名/参数；最终 UPDATE 必须影响恰好一行。
  缺失行或任何冲突在写入前拒绝，后续失败也整体回滚。
- 非原子事件适配读取持久化处置结果，避免把规范化后的 UNKNOWN 再发布成 retryable FAILED。

未增加迁移/依赖；未修改 Scheduler、配额、审批、权限、模型 Provider 或前端。
原不携带 toolCallId 的 legacy 仓储接口保持既有兼容语义；原生执行器始终传持久化调用身份，异常结算入口强制要求该身份。

## 确定性验证

测试通过真实事务代理、两个独立连接和 CountDownLatch 控制提交；释放事务前必须通过
`pg_blocking_pids` 观察到指定 backend PID 正在等待数据库锁。没有用 Thread.sleep 猜测时序。

| 交错/边界 | 操作次数与必须满足的结果 |
| --- | --- |
| 未提交潜在副作用出站 + 超时 | 提交后副作用 1；UNKNOWN，retryable=false；1 次工具记账，1 个 UNKNOWN Step/Event |
| 同上 + Provider 错误 | 同上，禁止 FAILED + retryable=true |
| 同上 + 普通 RuntimeException | 同上，即使候选失败原本不可重试也必须表达 UNKNOWN |
| 同上 + 无 Worker Scope | 同样串行化、UNKNOWN；不反转 Run/Invocation 锁顺序 |
| 结算事务先写入、未提交，dispatch 等锁 | 结算提交后 dispatch CAS 失败；副作用 0、dispatch NULL；合法 FAILED/retryable=true |
| 出站事务回滚 | 副作用 0；合法 FAILED/retryable=true，不伪造出站事实 |
| SAFE_READ 出站事务先提交 | 业务副作用 0；合法 FAILED/retryable=true，保留安全重试 |
| 不存在及旧轮次 toolCallId | 拒绝；工具额度/版本不变；孤立 Step/Event 均 0 |
| fallback 事件 | 使用持久化 UNKNOWN 状态与不可重试标记，不复用候选 FAILED |

并发用例止于工具结算，因此 Run 在该断言点保持 RUNNING；后续 UNKNOWN 终结、暂停、租约与晚到结果由既有 P6-B 集成用例回归。
最终定向验收 11 类、167 项全部通过，0 失败/错误/跳过，耗时 2 分 27 秒；见
[after.json](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/toctou-review/after.json)。
其中 PostgreSQL 远端结果 30 项（新增并发/身份场景 9 项）、结果单测 2 项、Scheduler 13 项、暂停 21 项、
模型轮次恢复 24 项、仓储集成 40 项、Runtime 行为 25 项、原生 Tool Calling 5 项、审批升级 1 项、
审批 Spring 集成 2 项、规划恢复 PostgreSQL 4 项。
首轮修复验证 29 项已通过，追加回滚/只读/无 Scope 三个边界后再次运行上述必要回归；未削弱断言或增加 skip。
此前完整 verify 1730 项是修复前基线记录，不冒充本补丁的完整回归。
本次按用户要求仅运行相关单测、真实 PostgreSQL 与必要恢复/审批回归，不重复跑完整 Maven/前端。

## 交付与边界

最终补丁 SHA 与新 CI 链接在最终回复给出；源码哈希与执行回执保存在
`docs/acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/toctou-review/`。
只正常提交推送 dev，不产生空提交、不手动重跑 CI，不修改 main，不改变 PR #18 Draft/未合并状态。
提交前工作区原本干净，dev 与 origin/dev 同步；main 本地/远端均为 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`，
PR #18 复核为 OPEN / Draft / 未合并。本轮未生成 *.log，测试输出仅写入受控 target。
真实外部 MCP/Provider 取消确认仍为 `LIVE_NOT_RUN`，不声称跨服务 Exactly-Once。
