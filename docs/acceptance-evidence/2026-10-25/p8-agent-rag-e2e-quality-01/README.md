# P8-AGENT-RAG-E2E-QUALITY-01

最终裁决：**P8_PARTIAL_ACCEPTANCE**。研究与可复现证据已交付；**Q02=FAIL，Agent 多条件完整性=FAIL**。确定性跨层身份/回流/持久化验证通过；真实外部生成未执行，不能宣称完整端到端回答质量通过。

基线：dev@f11939e336e3bb72abdcdb6e2db2fd4cc0d0fd3b，开工时 HEAD=origin/dev、工作区干净。当前变更仅后端测试与本专项证据/导航，生产 Java、Prompt、分块、来源算法、配置及迁移未改。

| 层级 | 本轮结果 | 证据性质 |
| --- | --- | --- |
| 冻结检索 | Recall@8 / @20 均26/27；QA保留26/27，Agent8保留22/27 | 本地真实Ollama、真实pgvector、生产服务 |
| Q02 | 关键续发条件全量排名23，Top8/Top20/实际工具均缺失：FAIL | 本轮真实复测 |
| Q03 | QA三阶段与所有列明条件保留；Agent8缺上线门禁及回滚：FAIL | 实际QA上下文与工具quote逐短语核对 |
| Q09 | 双合同独立身份均保留并正确持久化 | 实际文档/块/数据库核对 |
| 新增跨层 | 16题、92条来源身份核验；工具成功16/16，未知身份0 | Chat脚本，其他核心检索与运行路径真实 |
| 权限/范围 | 非成员16次拒绝；指定范围零越界；Q10模型扩大范围被阻断 | 真实数据库guard/范围解析 |
| 父子与失败工具 | 既有Agent研究测试本轮复跑 | 脚本模型+注入检索候选，不是真实向量父子贯通 |
| 真实生成 | BLOCKED_EXTERNAL，外部Chat请求0 | 未获本轮新授权 |

[现有能力与设计](engineering-inventory.md) · [基线/环境/冻结哈希](baseline.json) · [执行矩阵](test-matrix.json) · [真实检索原始结果](retrieval-results.json) · [实际Agent跨层结果](integration-results.json) · [指标与口径](metrics.json) · [失败归因及下一轮建议](failure-analysis.md) · [回归结果](regression-results.md) · [限制](limitations.md) · [历史完整性核验](historical-integrity.json) · [哈希清单](manifest.sha256)。

## 可复现步骤

当前目录用于保存本轮冻结产物，runner 的 baseline 模式会拒绝覆盖 baseline.json。再次运行请先设置 $env:P8_EVIDENCE_DIR 为 docs/acceptance-evidence/日期/新的任务目录，再调用本目录原有脚本；不修改原脚本路径或固定测试target路径。baseline模式会先把旧P8 target输出复制到target/p8-replays/时间/prior-p8，且拒绝覆盖已存在的baseline.json。测试入口也支持用 -Drag.baseline.output 单独指定基线JSON。只复现某一测试时不必调用finalize。测试自身不写历史docs文件；已有target诊断文件先备份，结束后恢复。

在仓库根目录执行下列命令（实际完整Maven命令及 opt-in 设置记录在 test-matrix.json）：

~~~powershell
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs baseline
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs retrieval
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs integration
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs regression
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs verify
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/run-evaluation.cjs restore
node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/finalize-evidence.cjs
~~~

所有Maven执行为离线 -o；仅本机Ollama和隔离Testcontainers服务允许真实调用，外部模型/应用库验收opt-in均清除。P8在单个JUnit测试内顺序执行16道冻结问题，不能把Tests run:1当作只评估一道题，也不能把16题当作16个独立JUnit测试。

## 最终问题回答

1. RAG卡在两层：Q02语义排序未召回；Agent代表优先与来源名额竞争丢掉同文档互补条件。QA当前三阶段门禁恢复，不能据此替Agent判通过。
2. 真实向量→工具执行→清洗→模型可见→脚本回答→消息/引用数据库链路的机械一致性通过；真实Chat生成和真实向量父子完整链路未通过本轮验证（未执行）。
3. 检索、工具、SQL、来源/持久化是本轮真实运行；Chat、辅助摘要及既有父子模型是脚本/Mock；历史结果仅供对照。
4. 下一轮优先解决Q02最终证据召回，以及Agent同文档互补事实保留。无需再次修复当前已恢复的Q09；不凭引用数量扩展不足语义。
5. 具备进入独立修复研究的证据条件；生产改动准入仍需成对净收益、关键事实零退化与授权后的真实生成验证。

补充编号审计：[text-citation-audit.json](text-citation-audit.json) 检出 Q13 脚本复制资料中的 [S999]，最终文本编号 FAIL；实际结构化来源未伪造。该现象可复现最终文本校验边界，不构成真实模型抗注入结论，详见失败分析 F7。
