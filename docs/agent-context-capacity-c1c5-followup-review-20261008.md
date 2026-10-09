# Agent 上下文容量 C1-C5 修复后复核（2026-10-08）

## 1. 基线与范围

- 实际分支：`codex/context-foundation`；实际 HEAD：`2cf4c51`，前置代码/测试提交为 `492fddf`、`03df846`。
- 开始检查时工作区仅有既有未跟踪目录 `.dsh-acl-recovery/`、`.freebuff/`，未修改。
- 本轮为只读源码复核与隔离探针验证，不修改生产源码，不提交、不 push，不操作生产数据库或现有服务。
- 依据：[本轮交付报告](agent-context-capacity-c1c5-delivery-20261008.md)、[上一轮审查](agent-context-capacity-review-20261008.md)。历史文档保留原始时点，不用新结论覆盖历史证据。
- 探针源码与本轮实际运行日志见[证据目录](acceptance-evidence/2026-10-08/c1c5-followup-review/README.md)。

## 2. 总结

主链修复确实存在：Composer 消费有效 RUN_CONTEXT；协调器在压缩后刷新并重新组装；固定年龄裁剪退出正常路径；辅助调用解析自己的快照并接入输出封顶、续租。这里的确认来自源码检查，不意味着本轮重新执行了全部历史验收。

尚有两个实现缺口，涉及 C3 的消费端一致性和 C4 的发布 fencing。三个正确性探针均失败，其中一项使用受控模型与仓库边界，另外两项使用隔离 PostgreSQL 和真实事务。它们不是提供商差异或摘要文字质量问题。

| 发现 | 严重性 | 已实测边界 | 影响 |
| --- | --- | --- | --- |
| D1：partial 首条记录被主请求整条排除 | P1 | 生产 Summarizer -> Composer，受控摘要输出 | 摘要未见到的原文尾部不再进入主模型请求 |
| D2：摘要发布 fencing 不完整 | P1 | 租约过期未换 epoch；检查后、发布前取消已提交 | 无效的在途返回仍成为 COMMITTED 摘要 |

## 3. D1：partial 首条记录的尾部没有进入主请求

### 源码链路

1. `AgentContextSummarizer.java:817`：当 `covered` 为空、首条记录仅送入前缀时，仍将 `sourceThroughSequence` 写成 `partialStep.sequence()`。
2. `AgentContextSummarizer.java:894`：同时保存 `sourcePartialSequence` 和 `sourcePartialChars`。下一次摘要的候选选择确实认识 partial 记录。
3. `AgentModelMessageComposer.java:287`：只读取 `sourceThroughSequence`。
4. `AgentModelMessageComposer.java:332`：工具观察仅保留 `sequence > coveredThrough`，没有识别 partial 标记。于是首条 partial 被按“完全覆盖”整条排除。

这不是数据库删除：原始记录仍在。但当前主模型既没有该记录的未送入尾部，也不能从此前摘要知道那些内容。后续摘要有续读能力，不等于当前主请求已保留未覆盖来源；活跃视图变小后也未必再次触发压缩。

### 本轮探针

- 构造一条约 80k 字符的工具记录，在末尾放入 `UNSENT_TAIL_4907`，后一条为最新模型请求记录。
- 使用生产 Summarizer、Routing 执行器、Composer；只有 Repository/native 出站为受控边界，没有反射调用私有方法。
- 摘要模型只能看到送入的前缀，返回固定摘要；保存结果为 `through=1, partialSeq=1, partialChars=60000`。
- 在充足单次请求空间下，实际 Composer 消息仍不包含该尾部标记。
- 这是针对交付已声明支持的“超大首条 partial”路径的结构化验证，不声称某次真实用户文档读工具自然返回了 80k 字符。

实际输出：

```text
FAIL partialTailRemainsInActualMainRequest: tail missing after partial summary: through=1, partialSeq=1, partialChars=60000
```

### 现有测试缺口

`AgentRunContextCompactionRegressionTest.unsentTailStaysUncoveredWithPartialOffsetInsteadOfWholeStepCoverage` 检查了 partial 元数据和下一周期摘要候选，但没有检查该摘要提交后的实际 Composer 消息。当前两周期 PostgreSQL 用例使用完整覆盖的短记录，未覆盖此消费端边界。

### 有限修复方向

让覆盖声明与消费端使用同一语义：只有完整覆盖的前缀可整条跳过，partial 记录的未覆盖内容必须仍可进入主请求。可以先保留该条原始记录，允许有界重复；若做尾部投影，必须保留记录、工具调用与引用身份，不能制造孤立 ToolResult。兼容已经持久化的 partial 字段，不只修未来生成值。

正式回归应断言：partial 提交后尾部在实际模型消息中；完全覆盖后对应原文才退出；后续压缩不重送已摘要前缀、不丢尾部。

## 4. D2：发布 fencing 未覆盖有效租约与并发变化

### 源码链路

- `AgentRunEventRecorder.java:160` 的检查 SELECT 读取 `claim_version`、状态、取消、`goalRevision`，没有读取/核对 `lease_expires_at`，没有锁住运行或目标修订事实。
- `AgentRunEventRecorder.java:213` 的发布 UPDATE 只以 step 的 `ATTEMPTED` 为条件，没有重新约束当前 epoch、租约、取消或目标修订。
- `@Transactional` 保证事务提交/回滚，却不能让默认 READ COMMITTED 下的普通 SELECT 自动阻止另一事务修改这些行。
- `AgentRepository.java:589` 按 `COMMITTED` 选择有效摘要。因此错误发布并不是仅留下一个无效诊断记录。

发布/结算的 attempt 幂等修复有效，但与运行事实的并发 fencing 是另一项保证，不能用 step 的一次转换替代。

### 本轮两个隔离 PostgreSQL 探针

1. **租约过期且 epoch 未变**：运行仍为 RUNNING、epoch=1、目标修订匹配；将租约过期时间设为过去，在 `AgentLeaseScope(1)` 和真实 `TransactionTemplate` 中调用生产 Recorder。摘要仍为 COMMITTED，已发生输入用量 1000 也被结算。
2. **检查后、发布前取消**：生产检查 SELECT 返回后暂停当前线程；另一真实连接提交 `cancel_requested_at`；再放行生产发布 UPDATE。发布和检查位于同一个真实事务中，结果仍为 COMMITTED。

并发探针的 JDBC 门控仅控制时间顺序，SELECT/UPDATE 和事务都真实执行；不是 mock SQL 返回，不是无事务调用。若未来正确的短事务锁使取消不能先提交，探针允许合法的先发布后取消顺序，不强制其超时失败。

实际输出：

```text
FAIL expiredClaimCannotPublishRunSummary: expired claim epoch=1 published COMMITTED; actual input=1000
FAIL cancelBetweenCheckAndPublishCannotPublish: cancel committed after fence read, before publication; summary still COMMITTED
```

现有 PostgreSQL 用例测试的是调用完成方法前取消/换 epoch/目标修订已改变。没有证明“检查与写入之间”事实不再变化。目标修订、claim 接管存在同类检查窗口的源码风险，但本轮没有将这两种并发变化分别实测，不宣称已复现。

### 有限修复方向

在短发布事务中对有效租约、epoch、取消和真实目标修订做并发安全的校验与发布：采用一致锁序的短行锁或等价的原子条件方案。不能只加第二次无锁 SELECT。不要在模型 HTTP 请求期间持数据库锁。

保留已落实的行为：同 attempt 发布/结算只一次；fenced 返回用量仍幂等结算、不推进有效覆盖；已受理暂停不被自动解除。补充“租约过期未换 epoch”和受控并发取消回归；目标修订/接管的并发回归按锁序与实际实现补足。

## 5. 证据口径与后续范围

- 当前 HEAD 的三份直接相关生产源码重新编译到临时输出目录，连同探针执行；不是只依赖上一轮遗留 class 文件。
- 本轮结果是 **3 个正确性探针，3 个断言失败**，不是项目 JUnit 全量新增 3 个失败，也不是已完成红绿修复。
- PostgreSQL 使用独立 Testcontainers `pgvector/pgvector:pg17`、真实 Flyway v1-v64、真实事务；结束后容器关闭。
- 初次尝试遇到探针 username 超过 varchar(40) 的夹具错误；只修正临时夹具后重跑。该错误不计入产品缺陷；归档日志为修正夹具后、重编译当前源码的完整运行。
- 本轮没有重跑全量测试、前端测试或浏览器，没有向真实模型发送请求。交付报告的历史 1299 项与浏览器证据不冒充本轮结果。
- 不变更累计 token 仅统计、父子独立执行额度、256k 软触发、时长/单请求超时等已批准设计，不重开 SSE/UI 或增加第二套编排。
- 下一轮只收口 D1/D2。修复后再有界观察真实资料至少两个压缩周期的质量；未触发压缩的问答/委派成功不能替代该质量验收，也不为触发而修改用户生产配置或反复消耗模型额度。
