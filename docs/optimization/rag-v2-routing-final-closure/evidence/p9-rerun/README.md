# P9 三轮对照实验证据（RagV2QualityEvaluationPostgresTest, AI_RAG_V2_QUAL_EVAL=true）

- run1 = 本分支（基线 HEAD 88e64734 + 收尾修复）：门禁 REGRESSION，违规仅 AQ12-ABSTAIN（生成侧编造判定）
- run2 = 基线 HEAD 88e64734 原样（git stash 对照）：门禁 REGRESSION，违规仅 AQ02-REPEALED（生成侧版本口径判定）
- run3 = 本分支：门禁 REGRESSION，违规 AQ07 + AQ11（生成侧判定）

三轮 QA/Agent 检索侧均为 52/54、53/54（run2/run3 结果 JSON 实测；run1 门禁违规列表仅含生成侧项，
即检索侧门禁通过）。三轮违规项互不相同且基线同样失败 ⇒ 生成维度随机、与本轮改动无关。
上阶段封版 PASS 复测生成模型为 gemini-3.5-flash，当前 DB 路由快照为 DeepSeek-V4.1-Flash-1017（环境漂移）。
run3 全量逐题结果见 rag-v2-quality-eval-results-run3-closure2.json。
