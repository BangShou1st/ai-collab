# AC-P1 第一波:全局模型窗口档案

> 任务:AC-P1(Token 计数 / 窗口档案 / 摘要 A/B)第一波——**全局模型窗口档案**
> 日期:2026-11-11
> 代码基线:`dev` @ `73f13fac`(开工前工作区干净,CI success)
> 设计依据:[AI-Collab-2026-11-10](../../../design/AI-Collab-2026-11-10.md) §4.1(模型档案)、§4.2(统一计数,后续波次)
> 前置审计:[AC-P0 Trace 与评测基线审计](../../2026-11-10/AC-P0-trace-baseline/README.md) §8

---

## 1. 本波范围与判断

审计确认的两个事实决定了第一波的切法:

1. **窗口只有一处来源**:`agent.context.window-overrides`(默认空),未命中即 `ModelWindow.unknown()`,所有模型按 `H=50_000` 保守运行;仓库默认部署下 `256K` 触发线实际不可达(`T = min(256000, 0.85 × 50000) = 42500`)。
2. **档案不存在**:`user_ai_provider` / `shared_ai_provider` 只有 `max_output_tokens`(请求输出上限),没有 `context_window`、`supports_usage`、`tokenizer_kind`、`pricing_version`;运行时也不按 `base_url + api_path` 区分同名模型。

因此第一波只做**窗口来源**这一件事,不改 Token 计量口径、不改摘要算法、不改出站请求行为。

### 1.1 触发线改动的撤回(重要)

设计 §4.1 要求取消 `min(256_000, 0.85H)` 绝对触发顶。实施时先按设计改成了 `T = floor(0.85H)`,全量 verify 暴露出一个真实问题:

- Composer 的容量预算是 `availableInputTokens × 3` 字符,而压缩触发线用的是 CJK 感知 `TokenEstimator`(ASCII 约 1/4 字符);
- 两者口径不一致时,ASCII 长上下文只要"装得下"就必然满足 `H > 1.176 × active`,而装得下需要 `H > 1.333 × active`——触发条件 `active ≥ 0.85H` 永远不可达;
- 直接后果:`AgentContextReliabilityD1D8RegressionTest#necessaryLayerExceedingSmallWindowStillClosesExplicitly` 中 275k ASCII 活跃上下文不再触发 RUN_CONTEXT 压缩。

因此本波**撤回触发线改动**,保留 256K 封顶;取消封顶与统一 Token 计量放在同一波次实施,避免用一个口径算预算、另一个口径算触发。该结论已写入 `AgentContextBudget` 类注释,防止后续再次单独改动。

## 2. 变更清单

| 项 | 内容 |
| --- | --- |
| 迁移 `V89__ai_model_profile.sql` | 新增全局表 `ai_model_profile`;`profile_key = sha256(provider_type|归一化 base_url|归一化 api_path|小写 model_name)` 唯一;列含 `context_window_tokens`、`max_output_tokens`、`supports_usage`、`tokenizer_kind`、`pricing_version`、`source`、`verified_at/verified_by`;CHECK 约束保证非法窗口/来源不可写入,`ADMIN_VERIFIED` 必须带核实时间 |
| `ModelProfileRepository` / `ModelProfileService` | 档案增删改查;身份归一化(大小写、尾部斜杠、路径写法);重复身份拒绝;来源降级为 `UNVERIFIED` 时清空核实记录 |
| 管理 API | `GET/POST/PUT/DELETE /api/v1/admin/ai/model-profiles`,系统管理员校验沿用 `UserService.requireSystemAdmin`;本波仅 API,前端表单后续补 |
| `ModelWindowResolver` | 窗口解析唯一入口:**档案 → window-overrides → UNKNOWN**;`UNVERIFIED` 档案可用但标记 `estimated=true`,不冒充已核实 |
| `AgentRuntimeCoordinator` / `AgentContextSummarizer` | 三处窗口解析改走 resolver,并把 `base_url/api_path` 纳入身份;构造器保留历史 16 参/14 参兼容入口(测试装配不接档案,行为不变) |

### 2.1 行为变化边界

| 场景 | 变化 |
| --- | --- |
| 未配置任何档案(默认) | **与改造前完全一致**:窗口未知 → `H=50_000`、`T=42_500` |
| 配置 `UNVERIFIED` 档案 | 窗口数值生效,但 `windowEstimated=true`,事件中可区分"未核实" |
| 配置 `PROVIDER_DOCUMENT` / `ADMIN_VERIFIED` 档案 | 窗口视为已确认;v2 运行以 `H=W−输出预留−安全余量` 为硬边界 |
| 同名模型不同网关 | 档案按 `base_url + api_path` 区分,不再共用一份覆盖 |

### 2.2 回退

- 行为层:删除档案或把来源改回 `UNVERIFIED` 即回到保守语义;`window-overrides` 仍可作运维兜底。
- 代码层:窗口解析是纯读取路径,无缓存与后台任务;回滚到上一提交即可恢复旧行为。
- 数据层:V89 只新增表,不修改任何历史迁移与既有表;历史数据不受影响。

## 3. 测试

| 测试 | 覆盖 |
| --- | --- |
| `ModelProfilePostgresIntegrationTest`(真实 PostgreSQL 17 + Flyway V1–V89) | 档案生命周期、归一化身份命中、跨端点/跨 Provider 不误命中、重复身份拒绝、核实记录随来源清空 |
| `ModelWindowResolverTest` | 档案优先于配置、`UNVERIFIED` 标记估算、无档案回退 overrides、全无则 UNKNOWN |
| `AgentContextBudgetTest` / D1D8 回归 | 触发线保持 256K 封顶的既有行为不变 |

## 4. 明确未做 / NOT_VERIFIED

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 取消 256K 绝对触发顶 | **有意撤回**(见 §1.1) | 必须与统一 Token 计量同批实施;单独改动会让 ASCII 长上下文压缩不可达 |
| 统一 Token 计量接口(设计 §4.2) | 未做(下一波) | Agent 主/子调用、摘要仍用 `TokenEstimator` + `chars/3`;知识库用 `ModelAwareTokenCounter`;`usage_basis` 仍是 PROVIDER/ESTIMATED/UNKNOWN |
| 真实压缩 A/B(NO_COMPRESSION / TRIM / SUMMARIZE) | NOT_VERIFIED | 需要 32K/64K/128K 冻结语料、arm 标识与关键事实判据;本波只提供窗口档案这一前置条件 |
| `max_output_tokens` / `supports_usage` / `tokenizer_kind` / `pricing_version` 驱动运行时 | 未做 | 字段已入库,本波不改变出站请求与计费;避免把"档案存在"夸大为"已生效" |
| 管理前端 | 未做 | 仅管理 API,前端表单与列表后续补 |
| 真实模型窗口核实 | NOT_VERIFIED | 档案值由管理员按服务商文档/实测填写,系统不自动探测 |

## 5. 执行结果

| 项 | 结果 |
| --- | --- |
| 定向测试(`ModelWindowResolverTest` + `ModelProfilePostgresIntegrationTest` + `AgentContextBudgetTest`) | 20/20 PASS |
| 后端全量 `mvnw verify` | 第一次:2557 项,1 失败(D1D8,触发线改动所致,已撤回);撤回后 **2557 项,0 失败/0 错误,61 skipped,BUILD SUCCESS**(22m55s)。中途一次 surefire fork 启动失败属本机可用内存不足,单独复跑 `AgentPauseResumePostgresTest` 21/21 通过,非代码回归 |
| 生产行为默认值 | 与改造前一致(无档案、无 overrides → 未知窗口) |

## 6. 复现

```bash
cd ai-collab-backend
bash ./mvnw -B -ntp -Dtest=ModelWindowResolverTest,ModelProfilePostgresIntegrationTest,AgentContextBudgetTest test
bash ./mvnw -B -ntp verify
```

管理接口示例(需系统管理员令牌):

```bash
curl -X POST http://localhost:8090/api/v1/admin/ai/model-profiles \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://gateway.example/v1",
       "apiPath":"/chat/completions","modelName":"gpt-4o","contextWindowTokens":128000,
       "maxOutputTokens":8192,"supportsUsage":true,"tokenizerKind":"cl100k",
       "pricingVersion":"2026-11-11","source":"ADMIN_VERIFIED"}'
```
