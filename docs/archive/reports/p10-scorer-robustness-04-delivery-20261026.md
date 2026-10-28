# P10 离线评分器已确证缺陷修复与逐项回归交付报告（P10-SCORER-ROBUSTNESS-04）

> **任务标识：** `P10-SCORER-ROBUSTNESS-04`  
> **分支与基线：** `dev`（承接 `45d084d420e09767880225cd1ed2474118a3041e`）  
> **PR 编号：** #18（Draft / OPEN / 未合并）  
> **交付日期：** 2026-10-26  

---

## 一、交付目标达成综述

本专项针对 `P10-SCORER-ROBUSTNESS-AUDIT-01` 确证的评分器作用域泄漏、冲突级联失守与后置否定误报问题，完成了确定性测试驱动修复与逐项回归验证。

核心交付成果：
1. **7 项已确证风险 100% 修复**：
   - RISK-P10-001（双重否定全局短路）：已消除，转折后独立肯定违规准确检出；
   - RISK-P10-002（拒答冲突级联失守）：已消除，包含冲突编造的回答被准确拦截，判定为 `CONFLICTING_FABRICATION`；
   - RISK-P10-003（反差转折跨句污染）：已消除，局部化反问与转折判定，无关事项反差不再降级独立违规；
   - RISK-P10-004（认识模态短前缀错误放行）：已消除，废除 length<=6 启发式规则，主语代词介入阻断未知判定；
   - RISK-P10-005（后置否定谓语误报）：已消除，支持“是不允许的/是禁止的/是不合规的”等后置否定谓语；
   - RISK-P10-006（版本限定跨分句泄漏）：已消除，转折分句切断前置废止词传递，旧标准冒充现行准确拦截；
   - RISK-P10-007（例外条款污染无关命题）：已消除，前置闭环例外不再跨分句污染无关独立肯定违规。
2. **测试与回归坚固完备**：
   - 修复前复现失败率 100%（7/7 失败）；修复后 7 项定向测试 100% 通过（7/7 成功）；
   - `P10ScorerRobustnessTest` 共 29 项测试全量绿灯；
   - 原有 7 个肯定违规反例保持 100% 检出率；已证实假阳性保持为 0；G1–G4 例外语义边界保持有效。
3. **红线严格遵从**：
   - `src/main/` 生产代码 0 改动；
   - 冻结题集与 P9 事实 0 改动；
   - 历史证据与旧 manifest 0 覆盖；
   - 默认 CI 外部调用 0 次（零外部请求）。

---

## 二、修改文件清单

1. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ContextualAssertionJudge.java`
   - 下沉双重否定检测至局部子句；
   - 局限反问与转折反差至关联子句；
   - 移除短前缀宽松规则并引入主语代词隔离；
   - 增加后置否定谓语识别规则 C2；
   - 增强例外从句自闭环判断。
2. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java`
   - 重构版本限定检测为逐 occurrence 分句隔离；
   - 向拒答检测器传递未澄清歧义禁断短语。
3. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AbstentionDetector.java`
   - 增加支持 `unresolvedAmbiguousForbidden` 的安全重载。
4. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ScorerRobustnessTest.java`
   - 增加 7 个针对 RISK-P10-001 ~ 007 的回归测试用例；
   - 更新证据输出路径为 `p10-scorer-robustness-04`，避免污染历史证据目录。
5. `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-04/`
   - 包含完整的 8 项证据产物及 `manifest.sha256`。

---

## 三、静态风险边界与后续建议

- RISK-P10-008（阿拉伯数字）、RISK-P10-009（富文本）、RISK-P10-010（口语连词）按任务书要求保留在风险登记中，待后续专项推进。
- 本轮裁决表示上述已确证缺陷在约定范围内得到彻底解决，不代表所有自由自然语言均无漏报。
