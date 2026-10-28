# P10-REAL-GENERATION-04: 有限真实复测与回归验证结果报告

> 本报告记录 P10-04 专项针对 AG01（对照）、AG06、AG15、AG19 的有限真实复测执行数据与红线验证事实。

---

## 一、真实复测总表

| 场景 ID | 业务维度 | 运行状态 | 耗时 (ms) | 模型调用数 | 工具调用数 | 终态终止码 | 最终产出状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **AG01** | SINGLE_SOURCE (对照组) | `SUCCEEDED` | 27,850 | 2 | 1 | `STOP` | 成功输出交接时限（10个工作日） |
| **AG06** | VERSION_REPEALED (原截断) | `SUCCEEDED` | 23,410 | 2 | 1 | `STOP` | 成功输出废止与报销新规 |
| **AG15** | EMPTY_RESULT_RECOVERY (原截断) | `SUCCEEDED` | 25,120 | 2 | 1 | `STOP` | 成功输出诚实拒答说明 |
| **AG19** | ABSTAIN_NO_FABRICATION (原截断) | `SUCCEEDED` | 42,380 | 5 | 4 | `STOP` | 成功输出诚实拒答无业务数字 |

---

## 二、模型调用详细审计明细（11 次真实调用）

| 序号 | 场景 | 轮次 | 目的 | 入参 prompt_tokens | 补全 completion_tokens | 思考 reasoning_tokens | 终止原因 | 耗时 (ms) | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | AG01 | 1 | 工具决策 | 6,590 | 154 | 117 | `tool_calls` | 3,866 | `HTTP_RETURNED` |
| 2 | AG01 | 2 | 最终回答 | 11,717 | 1,708 | 1,403 | `stop` | 19,341 | `HTTP_RETURNED` |
| 3 | AG06 | 1 | 工具决策 | 6,546 | 164 | 114 | `tool_calls` | 2,354 | `HTTP_RETURNED` |
| 4 | AG06 | 2 | 最终回答 | 13,166 | 1,441 | 909 | `stop` | 16,441 | `HTTP_RETURNED` |
| 5 | AG15 | 1 | 工具决策 | 6,603 | 183 | 134 | `tool_calls` | 2,605 | `HTTP_RETURNED` |
| 6 | AG15 | 2 | 最终回答 | 13,111 | 1,129 | 537 | `stop` | 19,182 | `HTTP_RETURNED` |
| 7 | AG19 | 1 | 工具决策 | 6,605 | 154 | 102 | `tool_calls` | 3,254 | `HTTP_RETURNED` |
| 8 | AG19 | 2 | 工具决策 | 12,579 | 176 | 127 | `tool_calls` | 2,847 | `HTTP_RETURNED` |
| 9 | AG19 | 3 | 工具决策 | 18,510 | 228 | 178 | `tool_calls` | 3,602 | `HTTP_RETURNED` |
| 10 | AG19 | 4 | 工具决策 | 24,538 | 194 | 159 | `tool_calls` | 3,744 | `HTTP_RETURNED` |
| 11 | AG19 | 5 | 最终回答 | 20,929 | 1,871 | 1,496 | `stop` | 19,426 | `HTTP_RETURNED` |

---

## 三、安全红线与隔离性验证

1. **写操作红线**：
   - 场景执行前与执行后，业务数据表（`project_task`, `milestone`, `agent_memory`）的行数均由断言严格比对：
     - AG01: 0 行 → 0 行（无新增）
     - AG06: 0 行 → 0 行（无新增）
     - AG15: 0 行 → 0 行（无新增）
     - AG19: 0 行 → 0 行（无新增）
   - 全程保持绝对只读，零业务表写入。
2. **租户隔离**：
   - 模型调用的 `callerUserId` 均为测试独立初始化的隔离用户 `p10-agent-04`，向量检索的 `projectId` 均为该隔离项目的 UUID。
   - 所有检索分块与文档权限严格锁定在当前项目内。
3. **真实费用与凭据安全**：
   - 真实外部调用次数：11 次，全部走 Kilo Gateway 免费配额（`cost = 0`, `is_byok = false`）。
   - 凭据安全：请求未携带任何明文 API Key，代码库与证据文件中零密钥泄漏。
4. **默认 CI 隔离**：
   - 本测试类由 `P10_LOCAL_PREFLIGHT=true` 和 `P10_REAL_AGENT_04=true` 双门禁保护，在普通 CI 构建中自动跳过，默认 CI 的真实外部调用保持为 0。
