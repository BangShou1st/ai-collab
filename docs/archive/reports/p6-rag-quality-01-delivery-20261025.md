# P6-RAG-QUALITY-01 交付报告

> 交付日期：2026-10-25 · 分支：`dev` · 设计基线：`d9554c301a8a82c986630ba085e97ea6e1e7fcdf`
> 设计：[docs/design/rag-quality-and-evidence.md](../../design/rag-quality-and-evidence.md)
> 证据：[docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/README.md](../../acceptance-evidence/2026-10-25/p6-rag-quality-01/README.md)
> 研究：[docs/archive/research/p6-rag-quality-01-research-20261025.md](../research/p6-rag-quality-01-research-20261025.md)

## 最终裁决

**`P6_RAG_QUALITY_01_ACCEPTED`（限检索/证据/引用契约层）。**
D0–D4 全部按设计执行；两项生产变更（QA 每文档名额均衡 S2、分组引用解析）通过全部准入门槛；
端到端生成质量因无用户新授权未执行（`END_TO_END_GENERATION_RESULT=NOT_EXECUTED`），未声称任何生成层收益。

## 任务要求的问题逐项回答

### 1. 哪些问题真正改善了

| 问题 | 改善 | 证据 |
| --- | --- | --- |
| Q03 上线门禁被 SOURCE_CAP 挤掉（历史+当前 SHA 均复现） | **已修复（QA 路径）**：上线门禁进入实际 Prompt 上下文，三阶段证据（上线门禁/两小时灰度/连续两次阈值）齐全 | 真实 Ollama+pgvector 终态 26/27（legacy 25/27）；`d0-d2-final.json` Q03 事实 qaStage 全 RETAINED |
| 分组引用 `[S1, S2]` 不被解析、合法引用丢失持久化（历史 Q03/Q09 真实输出格式） | **已修复**：受限分组逐成员校验，合法成员计入持久化引用；部分非法仅移除非法成员 | 17 例确定性测试（`KnowledgeCitationValidatorTest`） |
| Q09 双合同身份（历史缺陷） | 当前 SHA 已由按文档去重修复，本轮**仅验证不重复修**：QA/Agent 双路径均保留两份独立来源 | `d0-d2-final.json` Q09 + 断言 |

### 2. 哪些仍未解决（如实声明）

1. **Q02 续发前提召回**：候选层缺失（全量排名 23）。R1b 词法补充可入池但无法赢得名额（SELECTION_MISS）；
   改分块（800/120）已被 P4 真实对照否决；换模型/重排超出本轮约束与授权。
2. **Agent 路径 limit=8 的挤压**：Q03 上线门禁（stage-1 代表拥挤）、Q03 回滚触发条件、Q04 导出审计、Q05 v1.9
   共 4 项事实被挤掉；limit=12 实测救回 3 项，但需 token 成本与端到端验证授权，维持现状并留档。
3. **端到端回答质量**：未调用外部 Chat（Gemini 24/24 预算已用完、无新增授权），生成层无任何新结论。

### 3. 是否产生关键事实丢失

**零丢失。** 生产准入的 S2 在三重口径下零回归：冻结历史候选回放（23 项事实）、真实当前 SHA（27 项）、
确定性反例矩阵。被否决的 S1（跨文档代表优先）正是因为会丢 Q05 v1.9 关键事实而被拒绝进入生产。
S2 的已知结构性权衡（同文档第 3 条互补事实让位，CE3）在冻结题集中不触发（同文档必需事实 ≤2 条），已固化为测试断言。

### 4. 是否增加查询/Embedding/Token 消耗

**零增加。** S2 是检索后纯内存重排：候选池、TopK、来源名额（QA 5）、8000 code points 预算、
查询 embedding 调用数（每路径 1 次）全部不变；无新增 SQL、无新增向量请求、无模型调用。
实测 QA 检索延迟 37–125ms（单次口径）。R1 实验变体（每题 1 条有界 SQL）**未入生产**。

### 5. 真实模型 / 离线回放 / 数据库回归的验证范围

| 范围 | 内容 | 结论 |
| --- | --- | --- |
| 真实模型（本地 Ollama `qwen3-embedding:0.6b`，1024 维，fingerprint 与 P4 冻结一致） | 冻结 14 篇语料（manifest sha256 逐字节校验）生产分块索引；16 题双路径逐事实归因；R1a/R1b/S1/S2/agent12 对照 | 通过（AI_REAL_OLLAMA_RETRIEVAL=true 显式 opt-in） |
| 真实数据库（Testcontainers pgvector/pgvector:pg17，生产 Service→Repository→Mapper SQL） | 指纹/代际/READY/project/scope 过滤、范围零越界断言、预算断言 | 通过 |
| 离线回放（确定性，CI 常跑） | network-recovery-03 冻结候选 A/S1/S2/S3 gained/lost；E1–E11；CE1–CE9 反例矩阵；引用契约 17 例；冻结质量回归 8 例（已迁移至生产入口） | 通过 |
| 外部 Chat（Gemini） | 未执行（无授权、不读凭据、不绕过限额） | NOT_EXECUTED |

### 6. 验收矩阵对照（D01–D16）

D01 冻结语料/题集/SHA 实核 ✓（manifest 哈希断言）· D02 Q02 归因到候选 TopK 阶段（rank 23）✓ ·
D03 Q03 SOURCE_CAP 与事实损失可记录 ✓（dropReasons 逐题落 JSON）· D04 Q09 双保留且未重复修 ✓ ·
D05 真实 pgvector+Ollama 对照 ✓ · D06 D/E 与 E7/E11 反例及策略 A 一致性持续通过 ✓ ·
D07 越界=0（逐题断言）✓ · D08 QA/Agent 分母不混算 + 600 字 quote 逐项验证 ✓ ·
D09 source 身份与引用一致 ✓ · D10 引用机械契约 17 例 ✓ · D11 不足标记边界文档化（未扩展语义）✓ ·
D12 逐项 gained/lost 零回归 ✓ · D13 成本有界、真实/诊断分开报告 ✓ · D14 无索引/分块变更（不适用，已声明）✓ ·
D15 零外部调用/零凭据 ✓ · D16 定向+完整 `mvnw verify` 见下 ✓

## 变更与提交

| 提交 | 内容 |
| --- | --- |
| `5454832` | docs: 设计入库 + 导航 |
| （本轮后续） | feat: QA 每文档名额均衡选择（S2）+ 分组引用解析；test: P6 基线/实验/冻结回归迁移；docs: 证据归档与报告 |

main 未动；PR #18 保持 Draft 未合并；无 force push；历史冻结 JSON 与语料只读未改。

## 复现命令

见证据 README §7（`AI_REAL_OLLAMA_RETRIEVAL=true` 门控的真实套件 + 离线确定性套件）。
