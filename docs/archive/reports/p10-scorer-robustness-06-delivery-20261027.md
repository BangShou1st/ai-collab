# P10-SCORER-ROBUSTNESS-06 交付报告（归档）

> **交付日期：** 2026-10-27 · **裁决：** `P10_SCORER_ROBUSTNESS_06_ACCEPTED`

## 1. 交付范围

对 Audit-02 确证的 7 项缺陷实施 TDD 修复，附带最小修复 2 项可确定性复现的静态风险（005/009），评分器版本 `2.0-scoped-contextual-v5` → `2.1-scoped-contextual-v6`。

**代码改动（全部 test scope）：**

| 文件 | 改动 |
| --- | --- |
| `P10TextNormalizer.java` | stripMarkdownFormatting 多轮规整：先剥展示标签再转删除线、循环至不动点（P3） |
| `P10AnswerQualityScorer.java` | MASK_CITATION 分组引用契约对齐（P2）；isNumberSupported 数值+单位+词边界绑定比对、汉字/百分比等值变体绑定单位（P1）；isLegacyOccurrenceQualified 现行主张阻断（P5）；toSimpleChineseNumber/toArabicDigits 整十组合与单字兜底（009 附带）；SCORER_VERSION 升级 |
| `P10ContextualAssertionJudge.java` | A00 删除线靶向绑定（P4）；EXCEPTION_MARKER 移除 `除.*外`（P6）；E1 并列连词截断（P7）；ADVERSATIVE_CONJUNCTION 补 `而`（005 附带）；RULE_VERSION 升级 |
| `P10AbstentionDetector.java` | RULE_VERSION 升级（行为无变化，版本随评分器统一） |
| `P10ScorerRobustness06Test.java`（新增） | 32 项测试：7 项缺陷回归（含任务书全部必验场景）、静态风险验证、9 组跨规则组合、证据导出 |

`src/main/`、生产 Prompt、RAG 检索、冻结题集、历史证据：零改动。

## 2. 修复前后对比（真实运行，摘要）

| 风险 | 修复前（基线 v5 实测） | 修复后（v6 实测） |
| --- | --- | --- |
| 001 数字编造豁免 | `10 份` candidates=[]、HONEST_ABSTENTION、0 告警 | candidates=["10 份"]、CONFLICTING_FABRICATION |
| 002 分组引用泄漏 | candidates=["8","9"]、CONFLICTING_FABRICATION（假阳性） | candidates=[]、HONEST_ABSTENTION、身份校验保持 |
| 003 嵌套删除线 | `<del>` 残留、marker 匹配失败、0 告警 | `~~不可以~~`、AMBIGUOUS_NEEDS_REVIEW |
| 004 无关删除线 | 独立违规降级复核、0 告警 | AFFIRMATIVE_VIOLATION + highSeverity |
| 006 历史限定豁免 | LEGACY_QUALIFIED、0 告警 | VERSION_VIOLATION |
| 007 范围排除污染 | 独立违规降级复核、0 告警 | AFFIRMATIVE_VIOLATION + highSeverity |
| 008 模态跨域放行 | NEGATED 放行、0 告警 | AFFIRMATIVE_VIOLATION + highSeverity |

完整数据见 `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-06/`（baseline-probe.json / before-after-regressions.json / 各专项回归 / 组合回归 / 双向假阳性假阴性回归；基线红灯控制台输出受 `*.log` 忽略规则约束留存本地，关键实际输出已固化于上述 JSON）。

## 3. 验证结果

- 专项 32/32；历史健壮性 33/33；离线评测 4/4；前端 lint/typecheck/test/build 全过（409/409）。
- 历史 7/7 反例 100% 检出；AQ06/AQ12 假阳性 0 反弹；外部真实模型调用 0。
- 本地全量 verify 1896/1897（唯一 Error 为本地 Docker 约 1.86GB 内存上限下 Postgres Testcontainers 连接抖动，三次失败点互不相同、三个失败类单独重跑全绿，与本轮改动无关）；push 后 CI 完整 verify 全绿为最终门禁。
- 新证据 manifest.sha256 全部实测计算并校验通过。

## 4. 与审计记录的差异（如实声明）

P3 场景基线探针实测下游表现为 marker 匹配失败 0 告警（漏报方向），与审计记载的误报肯定违规不同；缺陷本体（删除线语义丢失）确凿复现，修复方向不受影响。P1 的真实触发路径为汉字等值链（“十个工作日”的“十”），非审计所述阿拉伯日期子串；缺陷成立性不受影响。详见研究记录归档。
