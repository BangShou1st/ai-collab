# Provider 发布最小延迟准入（D4，2026-11-13）

> 状态：**P1 最小实现**
> 范围：管理员共享 Provider 的 test → publish 生命周期
> 非目标：容量承诺、长时间稳定性测试、`main` 发布、默认开关变更

## 1. 目标

管理员测试连接时不再只采样一次，而是执行多次合成请求并记录：

- P50 / P95 延迟；
- 超时样本数；
- 失败样本数；
- 测试绑定的 `capability_config_version`。

发布为全站共享前必须存在与当前配置版本一致、状态为 `PASS` 的准入摘要，
且 P95、超时、失败均不超过配置阈值。单次成功不能冒充稳定，部分成功也不能
被洗成整体通过。

## 2. 数据与版本

复用 `user_ai_provider_probe_result`，新增 `probe_mode='PUBLISH_ADMISSION'`：

- `status`：`PASS` / `FAIL` / `INCONCLUSIVE`；
- `latency_ms`：本次 P95；
- `tested_config_version`：测试开始时的 `capability_config_version`；
- `detail`：JSON 摘要（`samples`、`p50Ms`、`p95Ms`、`timeoutCount`、
  `failureCount`、`configVersion`、`p95LimitMs`）。

V98 只扩展既有 CHECK 约束，不新增表。配置请求参数变化会推进
`capability_config_version`，旧准入摘要随即过期。

## 3. 默认阈值

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `ai.provider-admission.samples` | 3 | 至少 2、最多 10；越界回落 3 |
| `ai.provider-admission.p95-max-ms` | 10000 | 只拦截明显超时，不代表容量预算 |
| `ai.provider-admission.max-timeouts` | 0 | 默认不接受超时样本 |
| `ai.provider-admission.max-failures` | 0 | 失败样本总数（含超时） |

环境变量：`AI_PROVIDER_ADMISSION_SAMPLES`、`AI_PROVIDER_ADMISSION_P95_MAX_MS`、
`AI_PROVIDER_ADMISSION_MAX_TIMEOUTS`、`AI_PROVIDER_ADMISSION_MAX_FAILURES`。

## 4. 发布与编辑规则

- 没有准入记录、记录版本不一致、状态非 `PASS`、P95 超阈值、超时或失败超限
  均拒绝发布，错误信息给出样本摘要，不泄露凭据。
- 准入摘要落库失败时测试直接失败（`INTERNAL_ERROR`），发布 fail closed。
- 已共享 Provider 仅修改显示名等非请求参数时保持 `PUBLISHED`；
  修改模型、端点、凭据、温度或输出上限推进版本时，共享状态自动转
  `REVOKED`，必须重新测试并发布，不能借编辑绕过准入。
- 测试响应保留原有 `content/provider/model/promptTokens/completionTokens/latencyMs`
  字段，新增 `admission` 对象；前端既有 `content` 读取不受影响。

## 5. 口径与残余风险

- 3 个样本只能拦截明显不稳定，不能证明容量、SLO 或生产可用性。
- 没有基于时间的准入过期；配置版本不变时摘要持续有效，需人工重新测试才能刷新。
- 摘要以服务端落库为准，多实例并发编辑仍由 `capability_config_version`
  与共享表更新顺序决定；本波不实现分布式准入锁。
- 回滚代码时保留 V98（仅扩展 CHECK 约束，旧写入不受影响）。
