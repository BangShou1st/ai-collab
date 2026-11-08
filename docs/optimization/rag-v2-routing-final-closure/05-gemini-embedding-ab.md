# 05｜Gemini Embedding A/B 续验

> 分类：**环境阻断（BLOCKED）**——本环境未找到可用 `GEMINI_API_KEY`，未发起任何 Gemini API 调用。

## 1. 状态

- **Gemini Embedding A/B：`BLOCKED`**（凭据缺失，非配额耗尽）。
- 本轮**零次** Gemini API 调用（无 Key，未试错、未消耗任何免费额度、未触发 429）。

## 2. 环境核查记录（2026-11-08）

| 检查项 | 结果 |
| --- | --- |
| 进程环境变量 `GEMINI_API_KEY` | 不存在 |
| Windows 用户级环境变量 | 不存在 |
| 仓库 `.env` | 无 GEMINI 相关键（grep 为空） |
| 上一阶段 checkpoint（`target/gemini-embed-ab/`） | 目录不存在于当前 worktree（上阶段 5000 文档子集索引未在本环境留存） |

上一专项阶段 2 的 A/B 因免费层 RPD 耗尽标记 `NOT_VERIFIED`，运行时为内联提供 Key（未入文件、符合零明文凭据规范），本会话无法复用。

## 3. 未执行事项（诚实清单）

以下均**未执行、未测量、未声称**：

- Key 有效性 / 模型可用性 / 免费额度现状探测；
- Gemini Embedding 实际响应维度核验；
- 5000 文档子集索引续跑（既有 harness `GeminiEmbeddingABBenchmarkPostgresTest`，checkpoint 机制就绪但无进度可续）；
- Qwen vs Gemini 的 Recall@5/10、MRR@10、nDCG@10、延迟与 429 对照。

因此上一专项的结论维持原样：**Gemini Embedding A/B = `NOT_VERIFIED`（状态未变，本轮被凭据阻断）**。

## 4. 续跑指引（凭据就绪后）

```bash
AI_GEMINI_EMBED_AB=true GEMINI_API_KEY=<key> \
bash ./mvnw test -Dtest=GeminiEmbeddingABBenchmarkPostgresTest
```

- harness 已支持 checkpoint 续跑与 429 退避（30s×3）；免费层 RPD 重置时间见上一专项 02 报告（约 +08:00 16:00）；
- 报告口径约束（沿用任务规则）：子集为"已选题相关文档并集"，结果必须标记 `CUSTOM_CORPUS_SUBSET`，不得冒充 DuRetrieval 官方全量成绩；
- 不得轮换 Key、不得循环重试 429、不得自动升级付费。

## 5. 与本专项其他交付的关系

Gemini A/B 阻断**不阻塞** Commit 1（路由/回退/日志修复）与 Commit 2（Lexical GUC 审计）的验收——任务规则明确二者独立。
