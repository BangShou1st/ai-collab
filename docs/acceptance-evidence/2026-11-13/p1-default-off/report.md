# P1 W6 default-off 验收证据索引

> 日期：2026-11-13（Asia/Shanghai）
> 冻结契约：`540a029d`
> 最终报告：[`docs/archive/report/p1-default-off-2026-11-13.md`](../../../archive/report/p1-default-off-2026-11-13.md)
> 结论：**P1 default-off 最小闭环完成；生产 W6 NOT_READY / BLOCKED**

## 结果摘要

| 范围 | 结果 | 证据等级 |
| --- | --- | --- |
| Java Gateway 契约/目录/Hash | PASS（123 项定向回归） | TESTED(offline) |
| Python Host 契约/归一化/Hash | PASS（全量 1259 项） | TESTED(offline) |
| 本地只读测试 MCP Server + stub E2E | PASS（9/9 场景） | TESTED(STUB) |
| 只读调用经 Gateway 服务级 | PASS | TESTED(offline) |
| 私网/元数据、注入、在途撤权 | PASS | TESTED(offline) |
| D4 Provider test/publish 准入 | PASS（含真实 Postgres/V98） | TESTED(UNIT + POSTGRES) |
| 默认关闭/旧 Provider 回退 | PASS | TESTED(offline) |
| 真实 Gateway 进程 E2E | **NOT_RUN** | 未升级 |

## 泳道证据

| 泳道 | 路径 | 关键结果 |
| --- | --- | --- |
| A Java Gateway | [`lane-a/`](lane-a/)（如未单独落证，见主报告） | 123/123；审计 fail-closed；冻结 Hash 对齐 |
| B Python Host | [`lane-b/README.md`](lane-b/README.md) | 全量 1259；契约 fixture 逐字节一致 |
| C E2E + D4 | [`lane-c/README.md`](lane-c/README.md) | stub 9/9；D4 定向 27/27 + V98 |

## 限制

- 本地只读 Server 是合成 HTTP 回环夹具，不能替代真实 Java Gateway 进程 E2E。
- D4 只有 3 个默认样本，不是容量、SLO、长期稳定性结论。
- 未做 W6-B、多 MCP Server、多租户、生产 canary/TLS。
