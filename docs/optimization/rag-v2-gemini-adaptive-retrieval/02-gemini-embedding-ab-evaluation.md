# 02｜Gemini Embedding 接入与 A/B 评测

> 起始 SHA：`a1a3ec3f`（阶段 1 后）→ 代码入库于 `589b7fb8`（与阶段 1 CI 修复同批提交，见下方说明）
> 日期：2026-11-07 ~ 11-08 ｜ 阶段裁决：**接入实现 PASS；A/B 检索质量 NOT_VERIFIED（免费层配额耗尽，外部条件阻断）**

## 1. 提交说明（如实）

阶段 2 的嵌入协议代码与阶段 1 的 CI 门控修复因会话时序合并进了同一提交 `589b7fb8`（commit message 只写了 CI 修复——
文件清单包含本阶段全部产物：EmbeddingTaskType、ProjectEmbeddingGateway 原生分支、SystemEmbeddingService 白名单与指纹、
DenseRetriever/DocumentSearchService 查询链路、GeminiEmbeddingNativeContractTest、GeminiEmbeddingABBenchmarkPostgresTest）。
特此补充说明以保持提交-内容对应关系可审查。

## 2. 接入实现（最小修改，全部真实验证）

| 组件 | 内容 |
| --- | --- |
| `EmbeddingTaskType`（新增） | DOCUMENT / QUERY 任务类型枚举 |
| `ProjectEmbeddingGateway` | 4 参 embed 重载（旧 3 参默认 DOCUMENT，文档索引语义不变）；GEMINI 原生 `batchEmbedContents` 分支：每条请求携带 taskType（RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY）+ outputDimensionality、`x-goog-api-key` 认证、响应按序对齐、数量/维度/有限性严格校验（禁静默截断）、单次 ≤100 条请求上限 |
| `SystemEmbeddingService` | provider 白名单加入 GEMINI；指纹扩展 `EmbeddingFingerprints.geminiFingerprint`（绑定任务类型配对 + 协议版本 gemini-native-v1），旧 3 元指纹与既有代次零影响 |
| `DenseRetriever` / `DocumentSearchService` | 查询链路显式传 QUERY（RETRIEVAL_QUERY）——文档/查询任务配对语义正确落进生产检索路径 |

**协议实测（免费 Key，经系统代理）**：
- 默认输出 **3072 维且单位归一化**；`outputDimensionality` 支持 768/1536 降维，**降维后不再归一化**（norm≈0.59-0.70）——pgvector `<=>` 余弦自带归一化，检索语义不受影响，已在报告中如实记录。
- 批量 `batchEmbedContents` 按请求顺序返回、无 index 字段；单批上限 100 条。
- A/B 采用 1536 维（RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY 配对）。

**测试**：GeminiEmbeddingNativeContractTest 7/7（任务类型/前缀/维度/顺序/数量校验/429→QUOTA/400 API_KEY_INVALID→CREDENTIAL_INVALID）+ 既有兼容测试 3/3；随后全量回归中补齐 5 个检索测试类的 4 参镜像打桩（12 处）。

## 3. A/B 评测执行（部分完成，剩余 NOT_VERIFIED）

**协议**（harness：`GeminiEmbeddingABBenchmarkPostgresTest`，门控 `AI_GEMINI_EMBED_AB=true`）：
- 语料 `CUSTOM_CORPUS_SUBSET`：官方 DuRetrieval corpus 的 5,000 篇固定子集（seed=20261107；300 冻结查询的相关文档并集 + 随机负样本）——**完整 10 万语料不外发免费 API（出站边界）**，成绩不得标记为完整官方 Benchmark。
- 对照：同一子集双索引（Qwen 1024 本地 vs Gemini 1536 免费 API），物理隔离（独立 project/generation/fingerprint/维度），同一 300 查询、同一 Dense-only topK=10 预算。

**实测进度与阻断**：
- 隔离库双索引构建机制跑通（checkpoint 续跑、代次/FK 正确）；Qwen 侧 320 篇完成。
- **Gemini 免费层配额实测远低于预期**：首次 100 篇批量（≈27k tokens）即触发 429；降至 50 篇/27s 节流后仍持续 429（Embedding API 免费层 TPM/RPD 实测约 <15k TPM 当量）；至 2026-11-08 00:16 连续 47 分钟 429，判定 **RPD 耗尽**（太平洋时间午夜重置 ≈ +08:00 16:00，次日恢复）。
- 按任务红线（不得无限重试付费边界），运行已终止，A/B 检索质量指标 **NOT_VERIFIED**——不伪造数据，harness 支持配额恢复后一键续跑（checkpoint 保留）。
- 成本记录：本轮 Gemini 索引实际消耗 ≈300 篇 × 平均 270 tokens ≈ 81k tokens、3 次成功批量请求，全部为免费层；零付费调用。

## 4. 结论

- 接入实现（协议/隔离/指纹/查询配对/错误语义）：**PASS**（契约测试 + 真实请求双验证）。
- 检索质量 A/B：**NOT_VERIFIED**（免费层 RPD 耗尽；恢复后执行 `AI_GEMINI_EMBED_AB=true ... -Dtest=GeminiEmbeddingABBenchmarkPostgresTest` 即续跑）。
- 生产索引未激活：Qwen 默认代次原样保留；Gemini 配置仅存在于隔离评测库（enabled=false），回退路径 = 现状本身。
