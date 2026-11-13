# F2 Decide Python 主链

> 分支：`hardening/f2-decide-mainchain`  
> 基线：`e674dc0f`  
> 默认：`DECIDE_MODE=java`，未修改六开关、MQ、Gateway 或模型 Provider。

## 实现

| 能力 | 状态 |
| --- | --- |
| `DecideMode.PYTHON` 可绑定启动 | IMPLEMENTED |
| `DecideRouter` 在 Java 模型调用前消费 Python decision | IMPLEMENTED |
| 合法只读 `CALL_TOOL` 合成模型轮次并进入既有 `AgentToolCallExecutor` | IMPLEMENTED |
| 未知工具/写工具/Schema 不符不执行，回退 Java | IMPLEMENTED |
| FINAL/ASK_USER/NO_OP 回退 Java 文本轮 | IMPLEMENTED |
| worker 超时/不可用/非法 JSON/契约失败回退并标记 | IMPLEMENTED |
| `MODEL_STARTED.decideRoute` 稳定回退标记 | IMPLEMENTED |
| 路由指标 calls/fallback/handled/invalid_tool/latency | IMPLEMENTED |
| deadline 由运行剩余时间与配置上限取小；输入预算取运行可用值 | IMPLEMENTED |
| 取消前后复核、工具执行器既有复核 | IMPLEMENTED |
| 幂等键沿用 gateway `sha256(runId|attempt|turnNo|snapshotHash)` | IMPLEMENTED |

主链只覆盖根运行、只读 `PROJECT_RESEARCH`。Python 的 `CALL_TOOL` 不是授权：工具名/参数仍由
`AgentToolCallExecutor` 复核 exposed 集合、grantedTools、权限、Schema、预算、审批、租约与幂等。

## 测试

| 命令 | 结果 | 原始输出 |
| --- | --- | --- |
| 定向 decide/coordinator 5 类 | `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0`；exit=0 | `raw/targeted-final.txt` |
| coordinator Python CALL_TOOL 集成断言 | 6 tests exit=0；包含 Java `callModel` 零调用 | `raw/coordinator-targeted.txt` |
| 全量 `mvnw verify` | `Tests run: 3072, Failures: 0, Errors: 0, Skipped: 61`；exit=0，44:46 | `raw/verify-summary.txt` |

定向测试覆盖：

- `python` mode 可启动且默认仍 java；未知 mode 仍启动失败。
- 合法 CALL_TOOL：请求只含只读工具、预算/deadline 有界、合成轮次来源为 `PYTHON_DECIDE`。
- 未知工具/写工具/Schema 不符/FINAL/超时/预算不足：回退 Java，不执行工具。
- coordinator：Python 合法工具轮跳过 Java 模型并进入既有工具执行器。
- 既有 shadow 的只读、一致率、失败隔离、截断、饱和和零执行边界回归。

## 未执行/缺口

- `NOT_RUN`：真实 Python worker 的本地 shadow→python 双跑数据；由中心 E1 的 worker 与本地流量补测。
- `NOT_RUN`：前端门禁（本 lane 未改前端）。
- `NOT_RUN`：真实模型质量判定、冻结集复核；F1 候选仍为 `PENDING_REVIEW`。
- `NOT_RUN`：多实例缓存/租约压测；本轮假名单实例控制面。

## 关键边界

- `FINAL/ASK_USER/NO_OP` 不合成空文本轮：这些 decision 没有正文，若直接消费会把“没有答案”伪装成成功；
  因此回退 Java 文本轮并记录 `python_fallback:decision_*`。
- Python 主链被限制在只读、根运行与目标 Scenario；写操作/审批路径没有新增入口。
- 默认 `java` 路径不构造快照、不调用 worker、不记录 router 指标。

## 证据文件

- `raw/targeted-final.txt`：88 个定向/契约/协调器用例。
- `raw/coordinator-targeted.txt`：Python CALL_TOOL 进入既有执行器的成功回执。
- `raw/coordinator-targeted-attempt1.txt`：首次测试 mock 未匹配 `modelCallId=null` 的原始失败回执（未删）。
- `docs/design/decide-python-mainchain-2026-11-14.md`：路由语义与回退矩阵。
