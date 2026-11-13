# P2-C Lane D 评审报告：Canary 与回滚方案

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 211e4e32`
> 性质：**PLAN_REVIEW / NOT_EXECUTED**
> 结论：**方案完整性 PASS；生产 canary NOT_STARTED**

## 1. 结论

Lane D 已完成 canary 范围、样本、指标、停止/回滚阈值、角色和证据模板的冻结。
方案可提交用户批准，但当前**不具备直接开始 canary 的条件**，因为：

- Lane A 的生产调用方/短令牌路径仍是外部前置；
- Lane B 的真实证书/私钥仍未验证；
- Lane C 的生产告警送达仍未验证；
- 用户尚未回复“批准 canary”。

## 2. 方案覆盖表

| 要求 | 覆盖位置 | 状态 |
| --- | --- | --- |
| 测试项目/单租户/只读工具/单连接 | `plan.md` §1.1 | 已冻结 |
| 持续时间与样本量 | §3 | 30 分钟起、300 次调用、低流量延长/INCONCLUSIVE |
| 成功率、timeout/429/5xx | §4.1、§5.2 | 指标与阈值已定义 |
| schema drift | §4.1、§5.1 | 任一 drift 立即停止 |
| 撤权/在途 | §8 | 语义与演练限制已定义 |
| 审计行 | §4.3、§5.2 | 对账规则与缺失即停 |
| 用户侧延迟 | §4.4 | 工具墙钟延迟代理，端到端另取 APM |
| 停止/回滚阈值 | §5 | 立即停止 + 质量阈值 |
| Gateway 关闭/旧 Provider 回退 | §6 | 步骤、验证、失败升级 |
| 批准/观察/回滚角色 | §10 | 角色已定义，具名待批准前补齐 |
| 证据模板 | §11 | CSV/时间线/决策记录 |

## 3. 关键评审意见

### 3.1 不能把协议探针当用户路径

当前 Java Agent 仍走旧 `McpAgentToolProvider`，Gateway 不在 Agent 工具执行主路径。
如果 Lane A 只提供探针客户端，canary 必须标为 `PROTOCOL_ONLY`，不能证明用户侧
readiness。该限制已写入 `plan.md` §0 与 §12。

### 3.2 全局开关的单项目约束

`agent.mcp.gateway.enabled` 是应用级开关。`plan.md` §1.3 要求开启前盘点所有
`enabled` 且 `APPROVED` 的项目绑定；若存在其他可暴露绑定，必须改用独立 canary
实例，不能在共享生产 app 上直接开启。

### 3.3 样本口径

300 次 `tools/call` 是最低门槛，不是“统计显著”。少于 300 次只能
`INCONCLUSIVE`；30 分钟内不足则延长，60 分钟仍不足 100 次即回滚。该口径避免
用低样本“100% 成功率”误导批准。

### 3.4 回滚验证的诚实边界

生产不开放 `/actuator/beans`，因此不能用 Bean 内省作为唯一证据。方案采用
“条件装配代码 + 404 + 指标停止 + 旧 Provider 可执行”组合判定，并在 §6.2 明确
不伪造 Bean 内省证据。

### 3.5 撤权演练

生产 canary 不应人为延迟外部调用制造在途场景。若 Provider 没有安全慢速只读方法，
只能记录 `NOT_EXERCISED`，不能写 PASS；已有 staging M-08/M-17 证据可作为前置，
但不能替代生产结论。

## 4. 方案完整性判定

```text
Lane D：PASS（方案完整，可提交批准）
生产 canary：NOT_STARTED
生产 readiness：取决于 Lane A/B/C
```

本报告不构成 canary 批准，也不构成“已 canary / 已灰度 / 已上线”。

## 5. 需要用户/负责人补齐

1. 批准前具名：`CANARY_APPROVER`、`CANARY_COMMANDER`、`CANARY_OBSERVER`、
   `ROLLBACK_OPERATOR`、`SECURITY_REVIEWER`、`PROVIDER_OWNER`；
2. 填写 `CANARY_PROJECT_ID`、`CANARY_CONNECTION_ID/CODE`、`CANARY_TOOL_NAME`、
   `CANARY_CALLER`、`CANARY_IMAGE_DIGEST`、`CANARY_COMMIT_SHA`；
3. 明确 Lane A 调用方是真实用户路径还是协议探针；
4. 明确 Lane C 是真实送达还是 `ACCEPTED_RISK`；
5. 用户明确回复“批准 canary”后才可进入执行阶段。

## 6. 本次执行边界

- 只读仓库并写 `lane-d/plan.md`、`lane-d/report.md`；
- 未启动容器、未启动应用、未连接生产数据库；
- 未修改代码、配置、默认开关、迁移或 `main`；
- 未启用 Gateway、未发送生产请求、未执行 canary；
- 未创建分支/worktree/提交。
