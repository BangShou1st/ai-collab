# 最终报告｜RAG V2 智能路由正确性收尾专项

> 任务：RAG_V2_ROUTING_CLOSURE-01 ｜ 日期：2026-11-08 ｜ 分支：dev
> 基线 HEAD：`88e64734b936254a723fdfbd3150c0a8254586b8`（= 远端 origin/dev，工作区干净，已重新核验）

---

## 最终裁决：`RAG_ROUTING_CLOSURE_ACCEPTED`

- 路由正确性、证据回退、日志安全修复完成并通过全部工程测试；Lexical GUC 副作用核实为零。
- **P10 生成侧质量：`NOT_VERIFIED`**（本轮生成门禁在基线与本分支均随机失败，归因生成模型与环境，非本轮改动；详见 §4）。
- **Gemini Embedding A/B：`BLOCKED`**（凭据缺失，单独裁决，见 05 报告）。

两项状态独立标注，不与主裁决混合。

---

## 1. 提交与推送

| Commit | 内容 | SHA | Push | CI |
| --- | --- | --- | --- | --- |
| Commit 1 | fix(rag) 路由正确性+证据回退+日志安全（任务一/二/三） | `ff36baa707a851fafe60c465e891407175995b7f` | 见推送记录 | 见 GitHub Actions |
| Commit 2 | docs(rag) Lexical GUC 审计证据（任务四，只读检查，无生产代码改动） | `ac19ffb254cbd755d6e5f44eef405f6a7e95738d` | 见推送记录 | 见 GitHub Actions |
| Commit 3 | docs(rag) 收尾专项报告（含 Gemini A/B 阻断记录） | 本报告所在提交 | 见推送记录 | 见 GitHub Actions |

## 2. 修复清单（真实修复 vs 审查结论）

| 项 | 分类 | 核心内容 |
| --- | --- | --- |
| QueryRouter Bean 装配缺失 | **真实修复**（收尾新发现） | 无 `@Component` ⇒ 生产装配注入 null ⇒ 阶段 3 策略路由在 QA/流式/Agent 生产链路实际从未生效。加 `@Component` 激活 |
| 短精确标识符被 FAST 截获 | **真实修复** | 路由优先级调整为 STRUCTURED→COMPLEX→**IDENTIFIER**→FAST→默认；`JWT` 类查询保留词法通道（HYBRID） |
| 普通语义问题默认 Hybrid | **真实修复** | 默认改 `DENSE_WITH_BOUNDED_FALLBACK`（DuRetrieval：Dense-only Recall@10 0.809 vs 0.729，P50 7 倍快） |
| 回退判据"候选为空" | **真实修复** | 升级为"装配后无有效证据"（`EvidenceSufficiencyProbe`）；回退有界（至多一次）；QA/流式共享同一算法 |
| 路由异常日志泄漏原始查询 | **真实修复** | 掩码 + 仅异常类名；兜底 reason 不含异常消息 |
| Lexical GUC 副作用 | **仅完成审查** | `%`/similarity 阈值谓词全库仅 searchLexical 使用；GUC 会话级、默认 0.3 未变；GIN 索引计划确认；有据不改动 |
| Gemini A/B | **环境阻断** | 无 `GEMINI_API_KEY`，零次 API 调用，状态维持 NOT_VERIFIED |

## 3. 工程测试与验证结果

| 验证项 | 结果 |
| --- | --- |
| 针对性单元测试（Router 21 + Executor 回退 8 + QA toggle 2） | **31/31 通过** |
| 全量 `mvnw verify`（CI 同命令） | **BUILD SUCCESS：2327 tests，0 Failures，0 Errors，59 skipped** |
| 路由开销 | 纯本地规则（长度/正则/计数），零模型调用 |
| 元数据硬过滤 | STRUCTURED 路由与 filters 传递零回归（单测覆盖 + verify 全绿） |
| 路由性能 | 单测毫秒级完成，无网络调用；不虚构路由 P50 数字 |
| 项目权限 / 来源身份 / 引用契约 | verify 全绿（RetrievalQualityHardGate 等套件含于全量 verify） |

## 4. P9 保护性回归（三轮对照实验，如实记录）

套件：`RagV2QualityEvaluationPostgresTest`（`AI_RAG_V2_QUAL_EVAL=true`，冻结 P9 语料，Testcontainers + DB 路由真实生成模型）。

| 轮次 | HEAD | 门禁 | 违规项 | QA 检索侧 | Agent 检索侧 |
| --- | --- | --- | --- | --- | --- |
| 1 | 本分支 | REGRESSION | AQ12-ABSTAIN（生成编造"每年抽查"） | 52/54 ✓ | 53/54 ✓ |
| 2 | **基线 88e64734**（stash 对照） | REGRESSION | AQ02-REPEALED（生成版本口径违规） | 52/54 ✓ | 53/54 ✓ |
| 3 | 本分支 | REGRESSION | AQ07-AND-OR + AQ11-ABSTAIN（生成编造） | 52/54 ✓ | 53/54 ✓ |

**结论：**

- **检索侧保护指标（任务底线 P9 QA ≥52/54、Agent ≥53/54）三轮全部达成**，与封版基线一致；空上下文/注入类未出现可归因于本轮改动的违规（注入挑战 highSeverity=[] 全过）。
- 三轮门禁违规**全部来自真实生成侧判定，且每一轮违规项均不相同**；**基线 HEAD 同样失败**——证明该门禁的生成维度在当前环境不可复现 PASS，与本轮代码改动无关。
- 环境漂移证据：上一阶段封版 PASS 复测使用的生成模型为 `gemini-3.5-flash`（`evidence/03-p9-rerun-phase3-results.json`），当前 DB 路由快照为 `deepseek-ai/DeepSeek-V4.1-Flash-1017`。生成模型已变更 + 采样随机性 ⇒ 门禁波动。
- 依任务规则"P9 检索侧 PASS ≠ P10 生成侧 PASS"：**本轮生成质量标记 `NOT_VERIFIED`**。本轮未修改生成链路，此前封版生成结论仅作为既有基线引用（未重新完整执行）。

## 5. Gemini Embedding A/B：`BLOCKED`

- 本环境（进程/用户/机器环境变量、仓库 .env）均无 `GEMINI_API_KEY`；上阶段为运行时内联提供。
- 本轮**零次** Gemini API 调用：未探测配额、未触发 429、未消耗免费额度、未轮换 Key、未升级付费。
- 状态维持上一专项的 `NOT_VERIFIED`（被凭据阻断为 `BLOCKED`）；续跑指引见 05 报告。

## 6. 已知风险与遗留事项（不隐藏）

1. **P9 生成侧门禁不可复现**：`RagV2QualityEvaluationPostgresTest` 的生成判定维度在当前 DB 路由模型（DeepSeek-V4.1-Flash）下，基线与本分支均随机失败（每轮违规项不同）。属评测环境/模型问题，建议后续单独处理：固定评测模型或为门禁生成维度建立多次采样协议。**这不是本轮修复引入的回归**（基线对照实验证明）。
2. **生产路由行为变更**：`@Component` 激活后，生产 QA/流式/Agent 的路由与元数据过滤解析首次真正生效；普通语义问题由完整 Hybrid 改为 Dense 优先+有界回退。单测与 verify 全绿、P9 检索侧三轮与封版一致，但建议上线后观察 `RAG_STRATEGY_ROUTE` 日志（strategy/fallbackCause 分布）。
3. **Gemini A/B 未完成**：需要凭据后按 05 报告指引续跑，结果须标记 `CUSTOM_CORPUS_SUBSET`。
4. 本轮未测冻结集 Lexical P50/P95（配置与代码零改动，无差异来源；单条查询实测见 04 报告，不代表 P50）。

## 7. 改动范围核查

- 主源码 5 文件（QueryRouter / QueryRouteDecision / StrategyAwareRetrievalExecutor / 两个 QA 服务）+ 新增 `EvidenceSufficiencyProbe`；无 RRF 权重、Embedding 代次、Flyway 历史、生产配置改动；无新增依赖。
- 测试 2 文件更新 + 1 文件新增；报告与证据仅在 `docs/optimization/rag-v2-routing-final-closure/`。
- 无硬编码 Benchmark Query、无固定分支刷分；全部路由规则为语言无关结构特征或保留既有特征。
