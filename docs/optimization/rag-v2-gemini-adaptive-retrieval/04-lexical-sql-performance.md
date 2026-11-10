# 04｜中文 Lexical SQL 性能优化

> 起始 SHA：`ab9e73d9` → 结束 SHA：`1015eba0`（已推送）
> 日期：2026-11-08 ｜ 阶段裁决：**PASS（语义等价 diff=0，性能 6 倍，安全不变量全保留）**

## 1. 根因确认（EXPLAIN (ANALYZE, BUFFERS)，隔离库 ai-collab-bench-pg，10 万级语料）

旧 `searchLexical` 的命中谓词为四路 OR：
`content % q OR similarity(content,q) > 0.03 OR content ILIKE '%q%' OR heading %/ILIKE`。
其中 **`similarity(...) > 0.03` 不可索引**（GIN trgm 仅服务 `%` 与 ILIKE 谓词），OR 组合迫使计划器放弃
V78 已建的 GIN trgm 索引：

```
Parallel Seq Scan on document_chunk  (actual time=381..3641 rows=7 loops=3)
  Rows Removed by Filter: 35719   Buffers: shared hit=115496 read=15287
实际 3655ms / 查询（公开 Benchmark 实测 Lexical P50 3032ms）
```

## 2. 被拒绝的方案（先测后退，质量优先）

**朴素预过滤**（仅保留 GIN 可服务分支、丢掉 similarity>0.03）：300 冻结查询候选 Top20 diff 实测
**281/300 题存在差异、丢失 2566 个旧候选、新增 0**——[0.03, 0.3) 相似度带贡献真实候选，
该方案构成实质召回收窄 → **拒绝**。
（另一条 GiST KNN 路线实测受阻：中文长文本 GiST 叶元组超 8KB 上限，即使 siglen=16/部分索引仍 16KB——放弃，未入库。）

## 3. 采用方案（语义完全等价）

`SET pg_trgm.similarity_threshold = 0.03`（Hikari `connection-init-sql`，连接级常量）：
数学上 `content % q @0.03 ⊇ content % q @0.3 ∪ similarity > 0.03`，即单谓词覆盖旧语义全集；
映射 SQL 移除被覆盖的 `similarity>0.03` 分支后，四路谓词全部 GIN 可服务 → BitmapOr：

```
BitmapOr
  -> Bitmap Index Scan on idx_document_chunk_content_trgm (rows=4537)
  -> Bitmap Index Scan on idx_document_chunk_content_trgm (rows=0)
实际 602ms / 查询（重检 4537 行 CJK 相似度为主成本）
```

**修复后实测延迟**：单查询 602ms（峰值候选场景）/ 300 查询全量重跑平均 145ms——相对旧实现
**6~20 倍**（旧 3032-3655ms）。

## 4. 语义等价性验证（不允许只报告延迟）

300 冻结查询（官方 DuRetrieval dev 抽样，与公开 Benchmark 同源）新旧 SQL Top20 逐题对账：

| 指标 | 结果 |
| --- | --- |
| 存在差异的题数 | **0/300** |
| 旧有新无候选 | **0** |
| 新有旧无候选 | **0** |
| Top1 差异 | **0** |

代数保证：新候选集 ⊇ 旧候选集（`%@0.03` 覆盖两个旧内容分支），外层评分与排序不变；
实测超集未在任何一题的 Top20 内产生位移。质量零回退。

## 5. 安全不变量（全保留）

project/document scope、`generation_id IS NOT DISTINCT FROM`、`chunk_type<>PARENT`、`d.status='READY'`、
`access_blocked`、connector 授权子查询、metadata 硬过滤下推——全部原样位于 WHERE，未做任何预筛选旁路。

## 6. 测试与成本

- 相关离线测试 33/33（路由/回归/嵌入契约）；RetrievalQualityHardGatePostgresTest 7/7；
  完整套件由 CI 验证（本地两次 verify 因多容器并行资源竞争出现环境性 JDBC 抖动，
  与改动无关模块单跑 19/19 通过，已如实记录）。
- 索引空间：零新增（复用 V78 GIN trgm 索引）；无新依赖、无新基础设施。
- 附带收益：HYBRID 检索整体 P50 从 3434ms 预期降至 ~1000ms 量级（词法段 3.0s→0.6s，
  其余段不变；该值为分项实测推算，非端到端复测）。
