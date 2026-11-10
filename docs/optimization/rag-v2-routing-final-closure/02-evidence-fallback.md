# 02｜Dense 证据不足时的回退修复

> 分类：**真实修复**（代码变更 + 单元测试 8 项）

## 1. 问题复现与确认（修复前，HEAD 88e64734）

`StrategyAwareRetrievalExecutor` 的回退判据只有 `denseOnly.isEmpty()`：

```java
case DENSE_WITH_BOUNDED_FALLBACK -> {
    List<DocumentSearchHit> denseOnly = executeDenseOnly(...);
    if (denseOnly.isEmpty()) { fallbackUsed = true; yield executeFullHybrid(...); }
    yield denseOnly;
}
```

真实缺陷场景（上一专项最终报告"已知问题 3"也明确记录此留待事项）：

| # | 场景 | 修复前行为 |
| --- | --- | --- |
| 1 | Dense 返回零候选 | 回退 ✓（既有行为） |
| 2 | Dense 非空，但全部候选相似度低于 `KnowledgeContextBuilder.MIN_SIMILARITY=0.15` 被准入过滤 | **不回退**，最终上下文为空 → 直接拒答 |
| 3 | Dense 非空，但候选在 Token Budget 装配阶段全部被 `TOKEN_BUDGET_EXCEEDED`/`EMPTY_BODY`/重复淘汰 | **不回退**，同上 |
| 4 | Dense 有合法证据 | 不回退 ✓ |
| 5 | 权限/文档范围导致候选为空 | 回退一次 Hybrid（同范围，结果同样为空，有界无害） |

场景 2/3 即"候选非空，但有效知识上下文为空"——回退机制对此完全失效。尤其场景 2 在 Dense 优先策略成为默认后（01 报告问题 B）出现概率上升：Dense 对词法可精确命中的查询可能返回低相似度候选。

## 2. 修复设计

### 2.1 判据升级（`EvidenceSufficiencyProbe`，新接口）

新增 `retrieval/query/EvidenceSufficiencyProbe.java`（函数式接口）：

```java
boolean hasUsableEvidence(List<DocumentSearchHit> hits);
```

语义：候选集是否已形成"可使用、符合权限与引用约束"的有效证据（`KnowledgeContext` 装配后存在合法可引用的 `KnowledgeSource`）。契约写入 Javadoc：

- 判定必须**确定性且幂等**（同一候选列表重复装配结果一致）；
- 不得降低阈值、不得放行不合格来源凑证据；
- "有有效来源但不足以回答复杂问题"（数量充分性）**不属于**判定范围——保持保守行为，不凭数量自动认定证据充分（对应任务 4.3 第 4 类的保守要求）。

### 2.2 执行器（算法单点持有）

`StrategyAwareRetrievalExecutor`：

- 保留 6 参 `execute(...)`（无探针 → 既有"候选为空"兼容判据，Agent 与既有调用零改动）；
- 新增 7 参 `execute(..., EvidenceSufficiencyProbe)`：DENSE_WITH_BOUNDED_FALLBACK 策略下 Dense 结果经探针判定无有效证据（覆盖零候选与"候选被准入/预算装配全部过滤"两类）→ 最多执行**一次**完整 HYBRID 补充检索；
- 补充结果同样经探针（同一权限与证据校验，装配入口不变），仍不足时返回实际候选，由调用方走既有拒答路径——**不循环重试、不降低阈值**；
- 探针对齐契约：执行器保证**返回前最后一次探针调用对应最终返回的候选列表**（含异常降级路径），调用方可直接复用最后一次装配结果，避免二次装配；
- 回退原因结构化输出：`fallbackCause = DENSE_EMPTY | EVIDENCE_INSUFFICIENT_AFTER_ASSEMBLY | STRATEGY_EXECUTION_FAILURE | NONE`，进入既有 `RAG_STRATEGY_ROUTE` 诊断日志。

### 2.3 QA 与流式 QA 共享（不复制两套回退算法）

`KnowledgeQuestionApplicationService` 与 `KnowledgeStreamQuestionService` 注入同一探针实现：

```java
candidates = strategyExecutor.execute(projectId, decision, effectiveQuery, documentIds, filters, TOP_K,
        hits -> { KnowledgeContext built = assembleEvidenceContext(hits);
                 assembled[0] = built; return !built.sources().isEmpty(); });
context = assembled[0] != null ? assembled[0] : assembleEvidenceContext(candidates);
```

- 回退**算法**只存在于执行器一处；服务仅提供装配判定函数（与最终证据装配共享同一 `assembleEvidenceContext` 实现）；
- 回退判定看到的证据与进入提示词的证据**完全一致**（同一次装配结果复用）；
- "失败路径没有重复发起 Chat 生成"：`context.sources().isEmpty()` 时走既有 `INSUFFICIENT_ANSWER` 保存路径并返回，不进入 `chat.complete(...)`/`completeStream(...)`（QA 既有分支逻辑未动）。

## 3. 明确的语义限制（任务 4.5 要求如实说明）

- 探针判定为"装配后至少一个有效来源"这一**保守充分性**判据。未引入"来源数量/相似度质量阈值"类的拍脑袋标准——数量充分性（第 4 类）保持保守不回退，与任务要求一致；
- 第 5 类（权限或范围不允许返回来源）：Dense 与回退 Hybrid 使用完全相同的 `projectId/documentIds/filters` 下推（测试 `fallback_passesSameScopeAndFilters` 逐字验证），回退不会放宽范围，结果仍为空时走拒答。

## 4. 测试证据

`StrategyAwareRetrievalExecutorEvidenceFallbackTest`（8/8 通过，mock RetrievalPipeline）：

| 用例 | 覆盖任务 4.5 要求 |
| --- | --- |
| `denseEmpty_triggersSingleHybridFallback` | Dense 返回零候选 → 回退一次 |
| `denseCandidatesAllFiltered_triggersSingleHybridFallback` | Dense 非空但全部被过滤 → 回退一次 |
| `denseEvidenceUsable_noFallback` | 有合法证据 → 不回退（词法管线零调用） |
| `hybridStillInsufficient_noSecondRetry` | 补充后仍不足 → 不发起第二次补充，探针对齐最终候选（引用身份不变） |
| `legacyCallSite_keepsEmptyCheckSemantics` | 无探针调用点行为逐字兼容 |
| `fallback_passesSameScopeAndFilters` | 回退路径 projectId/documentIds/filters 逐字传递（权限/过滤始终生效） |
| `strategyFailure_fallbackAlignsProbeWithReturnedHits` | 异常降级路径探针对齐 |
| `nonDenseFirstStrategies_probeNotInvoked` | STRUCTURED/HYBRID 等策略不受探针影响 |

普通 vs 流式一致性：两服务注入同一执行器 + 结构相同的探针（装配函数逐一相同），回退算法单点持有于执行器，不存在两套实现；服务层装配一致性行为由 `KnowledgeQuestionChatToggleTest`（2/2 通过）与既有 P9 协议回归覆盖。

## 5. 回退次数上限的实现位置

有界性由 `executeDenseFirstWithBoundedFallback` 的直线控制流保证（Dense 一次 → 至多 Hybrid 一次，无循环结构）；`mvnw verify` 全量测试无重复检索放大（见 final-report 的 verify 结果）。
