# 01｜Query Router 策略选择边界修复

> 分类：**真实修复**（代码变更 + 单元测试 21 项）

## 1. 发现 0（收尾审查新发现）：路由器从未注册为 Spring Bean，策略路由在生产链路实际未生效

### 证据（修复前，HEAD 88e64734）

- `QueryRouter` 类无 `@Component`/`@Service` 注解；全主源码 grep 无任何 `@Bean` 定义或 `new QueryRouter()` 装配点。
- 三个调用方均为 `@Autowired(required = false)` 注入：
  - `KnowledgeQuestionApplicationService.java:83`
  - `KnowledgeStreamQuestionService.java:90`
  - `KnowledgeSearchAgentTool.java:41-42`
- Bean 缺失 ⇒ 注入为 null ⇒ `decision = null` ⇒ `StrategyAwareRetrievalExecutor.execute(projectId, null, ...)` 取默认策略 `HYBRID` ⇒ **所有问题恒走完整 Hybrid 管线**，阶段 3 的 FAST/Dense 快路径与多查询分解在生产装配下均为死代码。
- 影响范围：普通 QA、流式 QA、Agent 知识检索三条链路的路由分支全部失效（元数据过滤解析同样不生效——`filters` 取自 `decision.structuredQuery()`，decision 为 null 时 filters 恒为空）。
- 说明：上一专项阶段 3 的 P9 复测之所以"与封版一致"，是因为 P9 测试（`RagV2QualityEvaluationPostgresTest`）显式 `new QueryRouter()` 注入测试装配，未覆盖生产 Spring 装配路径。

### 修复

`QueryRouter` 增加 `@Component`。三个调用方代码零改动（原有 null 容错保留，作为防御性回退）。

## 2. 问题 A：短精确标识符被 FAST 提前截获

### 证据（修复前）

`QueryRouter.route()` 原顺序为：STRUCTURED → COMPLEX → **FAST** → identifier → 默认。
查询 `JWT` 命中 `isFastQuery`（≤8 code points、无推理标记）→ `DENSE_WITH_BOUNDED_FALLBACK`——精确标识符的词法精确匹配通道被跳过。
bench 库实测（10 万级语料，`evidence/lexical-guc-audit/04-identifier-semantics.out`）：`content % 'JWT token'` 在阈值 0.03 下命中 34 个候选，阈值 0.3 下命中 0 个——词法通道对该类查询有真实可验证价值，Dense-only 会放弃它。

### 修复

路由优先级调整为（顺序即契约，写入类 Javadoc）：

```
STRUCTURED → COMPLEX → EXACT_IDENTIFIER → FAST → 默认
```

- 命中标识符结构特征（错误码 `ERR-503` / 语义版本 `1.2.3` / 文件名 `application.yml` / 全大写代号 `JWT`）→ `HYBRID`，reason 说明词法精确匹配保留。
- **不是**"所有含标识符的问题都改 HYBRID"：COMPLEX/STRUCTURED 优先级更高（复杂问题含标识符仍走 MULTI_QUERY_HYBRID，元数据约束问题仍走 STRUCTURED+HYBRID）；仅"单一意图 + 结构化标识符"这一窄类落到本分支。
- 非标识符的短查询（`pgvector`、`MinIO默认端口`）保持 FAST，不受影响。

## 3. 问题 B：普通单一意图自然语言问答默认 Hybrid

### 证据（修复前）

路由默认分支对所有非 FAST/非结构化/非复杂问题返回 `HYBRID`（完整 Dense+Lexical+RRF+重排管线）。
DuRetrieval 全量语料实测（上一专项阶段 3 报告）：Dense-only Recall@10 0.809 vs Hybrid RRF 0.729，P50 465ms vs 3434ms——无条件 Hybrid 在陌生中文短文本语料上质量与成本均劣于 Dense。

### 修复

- 默认分支（普通单一意图语义问题）改为 `DENSE_WITH_BOUNDED_FALLBACK`：Dense 优先，装配后证据不足时由执行器回退一次完整 Hybrid（见 02 报告）。
- `QueryRouteDecision.defaultStrategy(HYBRID)` 同步改为 `DENSE_WITH_BOUNDED_FALLBACK`（单一事实来源）。
- 空查询/路由异常兜底仍显式 `HYBRID`（保守完整管线）。
- reason 可解释：`"Standard semantic question routed dense-first with bounded evidence fallback (lexical skipped unless assembled evidence is insufficient)"`。

## 4. 关于既有中文正则的处理（任务 3.4）

- 既有 `COMPARATIVE_PATTERN` / `COMPOSITE_CONJUNCTION_PATTERN` / `REASONING_DISQUALIFIERS` / `FAST_DEFINITION_PATTERN` 为中文语素特征，业务作用是**复杂度检测与 FAST 排除**（防漏判），非本轮新增。本轮**保留不动**（删除会破坏既有中文比较/并列问句的多查询分解能力），也未新增任何中文词驱动路由。
- 新增的路由决策全部基于语言无关结构特征：标识符模式、查询长度、问号计数、过滤条件解析结果。
- 全部语言相关规则仍集中在 `QueryRouter` 与 `MultiQueryDecomposer` 两个类内，未扩散。

## 5. 无模型调用

路由仍为纯本地规则（正则+长度+计数），零 Gemini/Chat 调用，路由开销为微秒级（任务 3.5 验收达成）。

## 6. 测试证据

`src/test/java/com/shitulelv/aicollab/retrieval/query/`：

| 测试类 | 用例 | 结果 |
| --- | --- | --- |
| `QueryRouterTest` | FAST 排除标识符查询；`JWT`/`什么是RRF`/`JWT的全称是什么` → HYBRID；普通语义问题 → DENSE_WITH_BOUNDED_FALLBACK；异常兜底 → HYBRID | 8/8 通过 |
| `QueryRouterStrategyMappingTest` | 标识符优先于 FAST、非标识符短查询保持 FAST、中英文语义问题默认 Dense 优先、异常 reason 不含异常消息、defaultStrategy 总映射 | 13/13 通过 |

路由前后对照（`JWT` 与普通语义问题）：

| 查询 | 修复前 | 修复后 |
| --- | --- | --- |
| `JWT` | FAST → Dense-only+回退 | HYBRID（词法保留） |
| `什么是RRF` | FAST | HYBRID（词法保留） |
| `MinIO默认端口` / `pgvector` | FAST | FAST（不变） |
| `解释一下混合检索中倒数排名融合的数学原理与工程取舍` | HYBRID 完整管线 | DENSE_WITH_BOUNDED_FALLBACK（跳过词法，证据不足回退） |
| `部署时出现 ERR-503 应该怎么排查` | HYBRID | HYBRID（不变） |
| `查一下 type:任务 状态为 OPEN 的条目` | STRUCTURED+HYBRID | STRUCTURED+HYBRID（不变，元数据硬过滤零回归） |
| `Redis和Memcached的区别是什么？各自适用什么场景？` | COMPLEX+MULTI_QUERY_HYBRID | 不变 |
| 空查询 / 路由异常 | HYBRID | HYBRID（不变） |
