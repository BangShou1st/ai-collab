# P2-SWITCH 金标准端到端回放验收证据（2026-11-28）

## 结论

- `tests/golden -m golden`：**4 passed / 0 failed**（pytest exit 0）。
- 端到端层 132 例 = **54 equal（全部精确相等，denseMaxAbsDiff=0.0）+ 78 not_compared_multi_query**（夹具未记录每次 fuse 的输入查询串，无法重建输入；报告单独计数、不计失败）。
- 夹具层 RRF：132/132 equal（129 严格相等 + 3 例经 R2-5a 截断边界平局组规则解决）。
- 夹具层启发式重排：80/80 严格相等，maxScoreAbsDiff=0.0。

## 回放修复记录（相对中断会话遗留状态）

中断会话留下的端到端层曾报 `equal=2 / embedding_diff=52 / not_compared_multi_query=78`，本轮定位并修复三处回放口径问题（均为测试工具代码，生产语义不变）：

1. **全局问题空 scope**：契约 §3-2 规定 P2 接口空 `visibleDocumentIds` 返回空结果；Java 门面对全局问题下发全部可见文档。回放此前把 `scopeDocumentIds=[]` 原样下传导致全局问题 dense/lexical 直接返回空。已按全语料重建下发集合。
2. **临时索引口径**：导出器对注入诱饵文档 F4（`temporary=true`）只在 `TEMP_INDEX_QUESTIONS`（H-G07-NONE/SIMILAR/INJECT 三题）执行时入索引；回放此前全语料建索引导致 F4 分块在其他题目冒出（缺失候选与 F4 完全互斥）。已对齐导出器与 `e2_rag.py` 的口径。
3. **RRF 输入顺序**：Java 基线 dense 列表在并列分数处保持小表物理扫描序（≈语料数组序），RRF 按位置计分，输入顺序直接影响融合分数；Python 生产口径是 `(documentId, chunkNo)` 升序。回放在融合前按「分数降序 + 语料数组序」稳定排序对齐两侧输入顺序。另将 dense 层改为「Python 全量排序 × Java 截断输出」的 R2-5/R2-5a 截断比对（HNSW 在 LIMIT 截断处是近似召回，属契约 Q10 接受的生产行为，不属于本门禁的算法等价范畴）。

## 复现步骤

```bash
# 1. 导出含分块正文的夹具（基线 f9fa9436 + content 补丁，本机一次约 3 分钟）
#    产物：p9-39q-golden.json / rrf-golden.json / reranker-golden.json
# 2. 临时 PostgreSQL（pgvector/pg17，ai_worker 非超级用户 + ai_index schema）
# 3. 本机 Ollama（qwen3-embedding:0.6b）
cd ai-collab-ai-worker
export AI_OLLAMA_BASE_URL=http://127.0.0.1:11434
export P2_GOLDEN_FIXTURE_DIR=<夹具目录>
export P2_GOLDEN_REPORT_DIR=<报告输出目录>
export P2_GOLDEN_DATABASE_URL=postgresql://ai_worker:...@127.0.0.1:<port>/p2_golden
export P2_GOLDEN_ADMIN_DATABASE_URL=postgresql://postgres:...@127.0.0.1:<port>/p2_golden
uv run pytest tests/golden -m golden -q -rs
```

## 产物

- `r2-5-compare-summary.json`：比对报告摘要（分层状态计数，不含逐用例明细）。
- 完整报告与夹具为临时产物，未入库；可用上述步骤重新生成，CI 侧金标准导出与回放比对由 `.github/workflows/p2-golden-fixture.yml` 承接（CI 因基线导出器不含 chunk content 而自动跳过端到端层，仅运行夹具层）。

## 关联验证

- 后端 `mvnw -B -ntp verify`：除 3 个类外全部通过；3 个类的失败修复后定点复跑全绿（合计 39 个测试）。
  - `RagV2MetadataAndParentChildPostgresTest`（6/6）：autowire 已迁移至 test 目录的 `ParentChildExpander`，缺 `legacy-rag-fixtures` profile → 已补 `@ActiveProfiles({"test","legacy-rag-fixtures"})`。
  - `TaskPlanSpringBeanPostgresIntegrationTest`（20/20）：切换后 `search_project_knowledge` 工具需 Python worker，测试环境无 worker → 在 `KnowledgeSearchClient` HTTP 接缝加 `@MockitoBean` 桩，按请求下发的可见文档集合查测试库返回带完整引用身份的命中，`KnowledgeHitVerifier` 与引用链在桩之后仍真实执行。
  - `DashboardCachePostgresIntegrationTest`（13/13）：Redis 测试容器固定主机端口 16379 与宿主机上无关容器（geocoupon-redis）冲突 → 改为类初始化时选取空闲端口并在容器生命周期内保持稳定（保留 C-T15 停机恢复场景的端口一致性）。
  - 另：本机 shell 的 `HTTP_PROXY/HTTPS_PROXY=127.0.0.1:7897` 曾使首轮 verify 的 TaskPlan 报代理配置错误，清除代理环境变量后复测；CI 无代理环境不受影响。
- worker：`uv run pytest tests/unit -q` 620 passed；`ruff check` / `ruff format --check` 全部通过。
- 前端：本分支零文件改动（中断会话内 lint/typecheck/test/build 已全绿，541 项）。
