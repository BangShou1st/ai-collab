# P10 离线评分器数字编造检测、富文本归一化与弱转折作用域交付报告（P10-SCORER-ROBUSTNESS-05）

> **任务标识：** `P10-SCORER-ROBUSTNESS-05`  
> **分支与基线：** `dev`（承接 `2c1ee3e68f56fac4a73785160b984a99daee6f00`）  
> **PR 编号：** #18（Draft / OPEN / 未合并）  
> **交付日期：** 2026-10-26  

---

## 一、交付目标达成综述

本专项针对 `P10-SCORER-ROBUSTNESS-AUDIT-01` 与 `P10-SCORER-ROBUSTNESS-04` 遗留的三项静态风险，遵循严格的测试驱动开发（TDD）规范：
1. **基线真实验证**：未修改代码前，执行基线探针测试，证实三项静态风险均真实存在运行时缺陷；
2. **定向最小修复**：分别对数字候选提取、HTML 展示标签与删除线归一化、弱转折连词作用域隔离完成代码修复；
3. **逐项回归验证**：测试从全红（4 失败）到全绿（33 项测试全量通过），三组风险交叉交互无冲突；
4. **历史能力保持**：P10-04 的 7 项修复无退化，7/7 肯定违规反例 100% 检出，AQ06/AQ12 历史假阳性保持为 0，G1–G4 语义边界保持有效；
5. **红线严格遵从**：`src/main/` 生产代码零修改，冻结题集零修改，历史证据与 manifest 零覆盖，默认 CI 零外部网络调用。

---

## 二、修改文件清单

1. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java`
   - 实现 `extractUnsupportedNumbers`，支持阿拉伯整数、小数、百分比及千分位数值；
   - 增加来源引用编号 `[S#]`、版本号 `V1.0` 与日期的预屏蔽过滤机制；
   - 建立阿拉伯与汉字核心数值双向来源比对，防止合法数字误报；
   - 扩充转折连词词表收录弱转折连词；
   - 升级架构版本为 `2.0-scoped-contextual-v5`。
2. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10TextNormalizer.java`
   - 实现展示型 HTML 标签（`<u>`、`<strong>`、`<em>`、`<span>` 等）的递归剥离与属性支持；
   - 将 HTML 删除线（`<del>`、`<s>`、`<strike>`）规范化为 Markdown 删除线 `~~text~~`；
   - 增强 `containsFact` 支持删除线包裹事实的语义感知。
3. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ContextualAssertionJudge.java`
   - 扩充 `ADVERSATIVE_CONJUNCTION` 连词正则，收录 `只是|反倒|反而|相反|仅是`；
   - 增加删除线复核分流机制（规则 A00 与 `evaluateOccurrences` 删除线感知），遇删除线修饰/打断转入 `AMBIGUOUS_NEEDS_REVIEW`，杜绝机械放行；
   - 升级架构版本为 `2.0-scoped-contextual-v5`。
4. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AbstentionDetector.java`
   - 同步升级架构版本为 `2.0-scoped-contextual-v5`。
5. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ScorerRobustnessTest.java`
   - 新增 4 个针对 RISK-P10-008、009、010 与交叉交互回归测试方法；
   - 导出 P10-05 专属证据文件至独立目录 `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-05/`。
6. `docs/design/rag-answer-quality-evaluation.md`
   - 补充第 15 节说明，记录评分器 2.0-scoped-contextual-v5 架构演进与验收指标。
7. `docs/README.md`
   - 增补 P10-05 验收证据导航条目。
