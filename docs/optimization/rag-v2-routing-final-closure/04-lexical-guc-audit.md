# 04｜Lexical SQL 连接级阈值副作用核查

> 分类：**仅完成审查（有据不改动）**——未发现需要修复的缺陷，不制造无意义生产代码提交。
> 全部检查为只读；原始输出见 `evidence/lexical-guc-audit/`。

## 1. 被核查配置

`src/main/resources/application.yml:12`：

```yaml
spring.datasource.hikari.connection-init-sql: "SET pg_trgm.similarity_threshold = 0.03"
```

上一专项阶段 4 的结论（不推翻）：阈值 0.03 使 `content % q` 单谓词覆盖旧 `similarity>0.03` 语义，BitmapOr 走 GIN trgm 索引；300 冻结查询候选集 diff=0；特定查询 3.6s → 602ms。

## 2. 核查项与证据

### 2.1 GUC 是会话级，未全局化，其他连接不受影响

`evidence/lexical-guc-audit/01-default-threshold.out`（隔离 bench 库，10 万级语料，全新 psql 会话）：

```
SHOW pg_trgm.similarity_threshold → 0.3
```

未执行 `ALTER SYSTEM/ DATABASE`，PostgreSQL 默认 0.3 保持不变；**只有携带 connection-init-sql 的 Hikari 池连接被设为 0.03**。`SET` 作用域为会话，连接归还池后随连接存续（Hikari 连接生命周期内阈值恒为 0.03，池内一致）。

### 2.2 全代码库 `%` 运算符 / similarity 阈值谓词审计

grep 主源码（含 XML 注解 SQL）结论：

- `content % query` / `heading % query` **仅存在于** `DocumentMapper.searchLexical` 一处（该 SQL 即优化目标本身）；
- `similarity(...)` 其余出现均为 SELECT 打分表达式（`GREATEST(COALESCE(similarity(...),0), ...)`），无任何 WHERE 阈值谓词依赖默认 0.3；
- Flyway 迁移中无 similarity 阈值谓词；`word_similarity` 等其他 trgm 阈值敏感操作符零使用。

**结论：不存在依赖默认 0.3 阈值的其他 SQL，无跨查询副作用。**

### 2.3 EXPLAIN (ANALYZE, BUFFERS) 对照（searchLexical 等价 SQL，bench 库）

`02-explain-thr003.out`（SET 0.03，即 Hikari 语义）：

```
Bitmap Heap Scan on document_chunk c (actual rows=6 loops=1)
  Rows Removed by Index Recheck: 7266
  → BitmapOr
    → Bitmap Index Scan on idx_document_chunk_content_trgm (actual rows=7272)
```

走 GIN trgm 索引（`idx_document_chunk_content_trgm`），无顺序扫描。

`03-explain-thr03.out`（SET 0.3，即默认阈值对照）：同样走 GIN 索引，但 trgm 分支 `actual rows=0`（阈值 0.3 下该查询词法候选近乎为零）——**两种阈值计划形状相同、候选规模不同**，即 GUC 只影响本查询的候选过滤语义，这正是阶段 4 设计意图（0.03 恢复 `similarity>0.03` 覆盖面）。

同查询三重复测（0.03 阈值）：Execution Time 1555ms / 1484ms / 1574ms。

### 2.4 语义差异的直接验证

`04-identifier-semantics.out`（标识符型查询 `content % 'JWT token'`，全库）：

| 阈值 | 命中候选 |
| --- | --- |
| 0.03 | 34 |
| 0.3 | 0 |

阈值 0.03 下词法通道对精确标识符查询才有候选——这一证据同时支撑 01 报告中"标识符查询路由 HYBRID"的决策。

### 2.5 测试与运行环境配置一致性

- 测试（Testcontainers + `@DynamicPropertySource`）仅覆盖 `url/username/password/driver-class-name`，`spring.datasource.hikari.connection-init-sql` 自 `application.yml` 继承生效（test 源码 grep 无 hikari 覆盖、无 application-test.yml）；
- 间接验证：阶段 4 的 300 冻结查询 diff=0 对账若在无 GUC 的连接上运行必然失败（% @0.3 会丢失 `similarity>0.03` 候选，见 2.4 的 34 vs 0），该对账通过即证明测试连接确实携带 0.03。

## 3. 延迟指标口径澄清（任务 6.4）

本轮核查的 1.5s 为**单条特定查询**（trigram 选择性差、7272 候选 heap recheck 主导）在 bench 库 psql 直连的 Execution Time，**不等于**冻结查询集 P50。阶段 4 的口径区分仍然有效：

- 单条查询耗时：随 trigram 选择性波动大（0.03 为宽容阈值）；
- 全部冻结查询 P50：602ms（阶段 4 实测，300 条）；
- 端到端检索耗时：另含 Dense 与装配阶段。

本轮未重测冻结集 P50/P95（无必要——配置与代码零改动，重测数字与阶段 4 不会有差异来源）。

## 4. 结论

- Hikari 初始化语句确实生效且仅影响池内会话；
- `%` / similarity 阈值谓词全库仅 searchLexical 使用，无跨查询副作用；
- 计划走 GIN 索引，性能收益与语义等价性证据链完整；
- **有据不改动**，不恢复旧 SQL，不做代码提交。

## 5. 环境差异备注（如实记录）

本轮审计 SQL 通过 `docker exec psql` 直连 bench 容器执行（非经应用 Hikari 池），与阶段 4 的应用侧实测环境存在连接路径差异；但 2.1/2.5 已独立证明 GUC 的作用域与生效机制，结论不受该差异影响。
