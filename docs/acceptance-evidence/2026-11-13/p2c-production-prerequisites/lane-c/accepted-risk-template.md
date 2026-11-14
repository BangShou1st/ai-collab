# Lane C ACCEPTED_RISK 模板（未签署）

> 状态：**TEMPLATE_ONLY / NOT_ACCEPTED**
> 适用：用户明确决定 P2-C 期间不接 Alertmanager，改为人工监控
> 注意：签署后 Lane C 只能标 `ACCEPTED_RISK`，不能标 `PASS`

## 1. 接受事项

```text
我接受 P2-C 生产 canary 期间不接 Alertmanager，改为人工监控。
```

## 2. 监控安排

| 项 | 填写内容 |
| --- | --- |
| 监控频率 | 每 ___ 分钟 |
| 检查对象 | Prometheus 指标、应用日志、MCP 审计、Gateway 错误码 |
| Primary 值班人 | ___ |
| Backup 值班人 | ___ |
| 升级路径 | 异常 ___ 分钟内通知 ___；___ 分钟内决定回滚 |
| 回滚授权人 | ___ |
| 观察窗口 | canary 全程 + 结束后 ___ 分钟 |

## 3. 已知盲区

- 无法保证 7x24 即时发现；
- 无法保证告警去重、抑制、升级与送达回执；
- 夜间/周末依赖人工值守；
- Schema drift、审计失败、egress 拦截可能延迟发现；
- 本地 webhook 模拟不等于生产通知渠道。

## 4. 用户确认

```text
我理解上述风险由我承担。
该接受不构成“告警已就绪”，也不构成“生产 canary 已批准”。

确认人：__________
时间：__________
```

没有上述确认前，Lane C 保持 `NOT_VERIFIED`。
