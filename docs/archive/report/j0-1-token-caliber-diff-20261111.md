# J0-1 统一 Token 计量口径差异报告

- 日期：2026-11-11
- 任务：J0-1 后端统一文本 → token 实现
- 结论：全后端文本计量只保留 `common/ai/token/TokenEstimator` 一个实现；Composer 预算、压缩触发、摘要结算与 fallback 结算全部改用该实现。来源显式区分 `PROVIDER / TOKENIZER / ESTIMATE / UNKNOWN`，估算值不再冒充精确值。

## 1. 旧口径 vs 新口径

旧口径有两层含义，实际等价于同一个系数：

- 预算/结算 fallback 常用 `chars/3`（0.333 token/字符，部分位置写作 `(chars + 2) / 3`）。
- Composer v2 用 `charBudget = availableInputTokens * 3` 分配字符预算，即每 1 token 预算可买 3 个字符。

新口径只有一个公式（`common/ai/token/TokenEstimator`）：

```text
tokens = round( ASCII可打印/空白 × 0.25 + CJK统一表意文字(含扩展A/B) × 0.75 + 其余codepoint × 0.5 )
```

同一 10,000 token 预算下，旧口径固定可容纳 30,000 字符；新口径按文本构成浮动：

| 文本构成 | 旧 token 估算 | 新 token 估算 | 新旧倍率 | 10,000 token 预算可容纳字符（旧 → 新） |
| --- | ---: | ---: | ---: | --- |
| 纯中文 100 字 | 33.33 | 75 | 2.25× | 30,000 → 13,333 |
| 纯英文 100 字符 | 33.33 | 25 | 0.75× | 30,000 → 40,000 |
| 中英各半 100 字符 | 33.33 | 50 | 1.50× | 30,000 → 20,000 |
| 纯 ASCII JSON 100 字符 | 33.33 | 25 | 0.75× | 30,000 → 40,000 |

实际回归样本：

| 样本 | 字符数 | 旧 chars/3 | 新 CJK 估算 | 新旧倍率 |
| --- | ---: | ---: | ---: | ---: |
| `人工智能协作平台` | 8 | 2.67 | 6 | 2.25× |
| `The quick brown fox jumps over the lazy dog` | 43 | 14.33 | 11 | 0.77× |
| `在 Spring Boot 中使用 pgvector 做向量检索` | 32 | 10.67 | 13 | 1.22× |
| 工具 Schema JSON 样本 | 107 | 35.67 | 27 | 0.76× |
| 12,000 字纯中文长文本 | 12,000 | 4,000 | 9,000 | 2.25× |

> 说明：旧代码多数位置对 `(chars + 2) / 3` 做整数除法，短样本上会额外向下取整；上表按连续系数展示，避免取整噪声掩盖口径差异。

## 2. 行为变化

1. **中文上下文获得约 2.25 倍 token 权重**：同一 token 预算下可容纳的中文历史更少，压缩触发更早、历史入选更少；必选层（系统提示、工作状态、摘要）也按同一口径更早触及预算。
2. **英文上下文获得约 25% 更低的权重**：同一 token 预算下可容纳的英文文本约为旧口径的 1.33 倍，英文历史会更晚被裁。
3. **中英混合、JSON 落在两者之间**：按各自 ASCII/CJK/其他 codepoint 占比加权，不再使用统一字符系数。
4. **工具观察下界改为 2,000 token**（`TOOL_OBSERVATION_MIN_TOKENS`，旧为 6,000 字符）：
   - 中文约 2,667 字符，低于旧 6,000 字符；
   - 英文约 8,000 字符，高于旧 6,000 字符；
   - 这是有意的单位统一，不再用固定 6,000 字符近似 2,000 token。
5. **工具结果投影按 token 容量计算**：先用该输出的实测密度把 token 上限折算成字符上限（`capChars = text.length() * capTokens / estimate(text)`），投影后重新计量；仍超限时按投影结果密度再收紧一次（最多两次投影）。不再使用固定 `*3` 字符预算。
6. **摘要输出预留改为固定策略常量**：`OUTPUT_RESERVE_TOKENS = 400`、`RUN_CONTEXT_OUTPUT_RESERVE_TOKENS = 5334`（保留旧 chars/3 口径下的数值），不在运行时按字符上限重算。
7. **摘要/压缩结算 fallback 使用实际文本 token**：例如 1,200 字 `"摘要。"` 重复输出，旧 `chars/3` 计 400 token，新口径为 800 token（2 个 CJK × 0.75 + 1 个全角标点 × 0.5，共 400 组）。
8. **提供商上报与未知来源语义不变**：Provider 响应中的真实 usage 仍是权威来源，缺失侧才 fallback 估算；无任何证据仍是 `UNKNOWN`。

## 3. 来源与数据库

- 来源枚举统一为 `PROVIDER / TOKENIZER / ESTIMATE / UNKNOWN`；`ESTIMATE` 与 `UNKNOWN` 恒标记 `estimated=true`。
- **本波不新增 Flyway 迁移**，未修改任何历史迁移。
- 数据库 `usage_basis` 的 CHECK 仍只允许 `PROVIDER / ESTIMATED / UNKNOWN`：
  - `PROVIDER → PROVIDER`
  - `ESTIMATE → ESTIMATED`
  - `UNKNOWN → UNKNOWN`
  - `TOKENIZER → ESTIMATED`（保守降级；在后续确有必要前不新增迁移）
- 提供商上报的 usage 保持 `PROVIDER` 且仍然优先，不被本地估算覆盖。

## 4. 删除的重复实现

- 删除 `agent/domain/model/TokenEstimator.java`。
- 删除 `planning/domain/PlanningTokenEstimator.java`。
- 新增唯一实现 `common/ai/token/TokenEstimator.java`；Agent、Planning、Knowledge 与结算 fallback 统一引用。
- `ModelAwareTokenCounter` 删除全部正则/校准启发式与伪造的 `NATIVE` 精确路径，只保留协议开销包装；provider/model 参数保留用于 API 兼容，但不改变数值。
