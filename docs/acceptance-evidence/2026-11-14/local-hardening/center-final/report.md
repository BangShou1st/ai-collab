# 最终本地门禁汇总

## 门禁结果

| 门禁 | 事实 | 证据 |
| --- | --- | --- |
| Java `mvnw verify` | exit=0；`Tests run: 3090, Failures: 0, Errors: 0, Skipped: 61`；22:54 | `raw/java-verify-summary.txt` |
| 10k 任务服务层测量 | first 26ms，P95 29ms，每页 TaskMapper SQL=2，50 samples | `raw/java-verify-summary.txt` |
| 前端 lint/typecheck/test/build | 全 exit=0；504 tests / 66 files | `raw/frontend-*` |
| Python | exit=0；1284 passed，1 个既有 StarletteDeprecationWarning | `raw/python-pytest.txt` |
| 默认开关 | java/java/java/false/static/static；Gateway=false；MQ=false | 见 Wave 1/Wave 2/Wave 3 各 lane 默认快照 |
| 迁移 | V1–V101；V102/V103 未占用 | 各 lane 证据 |

## Lane 收口

| Lane | 状态 | 集成/证据 |
| --- | --- | --- |
| A H-1 | TESTED(UNIT\|INTEGRATION\|REAL_PROCESS) | `..\lane-a-doc-delete/` |
| B H-2 | TESTED(UNIT\|INTEGRATION)；OS 重启 NOT_RUN | `..\lane-b-token-revoke/` |
| C H-3 | TESTED(UNIT\|INTEGRATION\|REAL_PROCESS)；后台标签 REAL_PROCESS NOT_RUN | `..\lane-c-approval-inbox/` |
| D H-4 | TESTED(UNIT\|INTEGRATION)；MEASURED | `..\lane-d-task-list/` |
| F1 | TESTED(UNIT\|INTEGRATION)；候选 PENDING_REVIEW | `..\lane-f1-decide-dataset/` |
| E1 | MEASURED；shadow 一致率 NOT_RUN | `..\e1-rerank-shadow/` |
| F2 | TESTED(UNIT\|INTEGRATION)；真实 worker 双跑 NOT_RUN | `..\f2-decide-mainchain/` |
| G | TESTED(UNIT\|INTEGRATION)；真实 worker 双跑 NOT_RUN | `..\g-planning-mainchain/` |

## 事实边界

- G 分支第三轮全量因 3 个既有 Agent Postgres 时序用例 exit=1；4 类单独复跑 116/116。主分支最终全量复跑 exit=0，原始失败仍保留在 G lane。
- 真实 Python worker 双跑未执行；F2/G 的结论只覆盖 stub/契约/集成。
- E1 知识问答主链固定 `DENSE_LEGACY`，没有触发 `rerank.shadow.*`；只记录真实 worker rerank 数字。
- CI 结果在 push `dev` 后补记，不在本文件内伪写。
