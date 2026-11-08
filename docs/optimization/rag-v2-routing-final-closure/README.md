# RAG V2 智能路由正确性收尾与 Gemini A/B 续验专项

> 任务：RAG_V2_ROUTING_CLOSURE-01 ｜ 日期：2026-11-08 ｜ 分支：dev
> 基线 HEAD：`88e64734b936254a723fdfbd3150c0a8254586b8`（与远端 origin/dev 一致，工作区干净）
> 上一专项：RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01（裁决 OPTIMIZATION_PARTIAL，见 `../rag-v2-gemini-adaptive-retrieval/`）

## 范围

本轮为**有限范围缺陷修复与验收收尾**，非新一轮重构：

1. 任务一：Query Router 策略选择边界修复（含一处生产装配缺陷）
2. 任务二：Dense 证据不足时的回退判据升级（候选非空但装配后无有效证据）
3. 任务三：Query Router 日志与安全脱敏
4. 任务四：Lexical SQL 连接级阈值（pg_trgm GUC）副作用核查（只读审计）
5. 任务五：Gemini Embedding A/B 续验（受凭据阻断，见 05 报告）

## 报告导航

| 文件 | 内容 | 状态分类 |
| --- | --- | --- |
| [01-router-correctness.md](01-router-correctness.md) | 路由边界修复：Bean 装配缺失、标识符优先、默认 Dense 优先 | 真实修复 |
| [02-evidence-fallback.md](02-evidence-fallback.md) | 证据充分性回退：判据升级 + 有界回退 + QA/流式共享 | 真实修复 |
| [03-log-safety.md](03-log-safety.md) | 路由日志脱敏 | 真实修复 |
| [04-lexical-guc-audit.md](04-lexical-guc-audit.md) | pg_trgm.similarity_threshold 连接级副作用核查 | 仅完成审查（有据不改动） |
| [05-gemini-embedding-ab.md](05-gemini-embedding-ab.md) | Gemini Embedding A/B 续验 | 环境阻断 |
| [final-report.md](final-report.md) | 最终裁决与证据汇总 | — |

## 状态分类口径

每项工作严格区分：**真实修复**（代码变更+测试）/ **已有代码本来正确**（记录证据）/ **仅完成审查**（只读检查）/ **真实评测通过**（真实测量）/ **环境阻断** / **尚未验证**。

## 证据目录

`evidence/` 下存放原始审计输出（SQL 与对应 `.out`），均为只读检查或测试运行产物，不含任何密钥或用户敏感数据。
