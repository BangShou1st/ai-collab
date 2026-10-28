# P10-SCORER-ROBUSTNESS-06 专项任务书（归档）

> **任务名：** P10-SCORER-ROBUSTNESS-06｜全链路评分器缺陷修复与回归验收
> **下达日期：** 2026-10-27
> **前置依赖：** `P10-SCORER-ROBUSTNESS-AUDIT-02`（审计基线 `953fe978e471a8ab1a36494cac8b94756aa929f2`）
> **交付证据：** `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-06/`

## 一、任务目标

基于 Audit-02 实际审计结果，对 P10 离线回答质量评分器开展测试驱动修复（TDD）专项。审计确证的 7 项缺陷为唯一默认修复范围：RISK-AUDIT2-001（数字来源支持判定裸字符包含误判）、002（分组引用未屏蔽）、003（嵌套 HTML 删除线归一化失效）、004（无关删除线抑制独立违规）、006（历史限定豁免现行主张）、007（范围排除污染独立命题）、008（未枚举主语认识模态放行）。

先逐项复现，再最小修复，最后以真实运行结果证明修复成立且历史契约未退化。不修改生产 Prompt、RAG 检索策略或业务执行链路。

## 二、Git 基线

- `dev`：`953fe978e471a8ab1a36494cac8b94756aa929f2`；`main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；PR #18：OPEN / Draft / 未合并。
- Audit-02 Push CI `40360772076` / PR CI `40360782534` 均 success。
- 开工必须重新核验本地、远端与 GitHub 实际状态；基线不符或工作区不干净立即停止并报告。

## 三、保护红线

1. 不修改 `main`，不合并 PR #18，不部署。
2. 不修改 `src/main/`、System Prompt、RAG 检索、上下文选择算法或 Token 预算。
3. 仅允许修改经确认的 P10 离线评分器（test scope）、对应自动化测试与本专项文档。
4. 不修改冻结题集与 P10-01～05、Audit-02 历史原始证据；不覆盖已有 manifest。
5. 不发起任何真实 Chat / Embedding / Agent / 模型裁判外部调用。
6. 不删除失败测试、不降低既有断言、不扩大人工复核范围代替修复。
7. 修复需超出范围时先停止说明理由。

## 四、TDD 顺序

每项缺陷：定位根因 → 最小确定性回归用例 → 基线运行保存失败输出 → 确认失败对应目标缺陷 → 最小修复 → 正例/反例/相邻交互测试 → 完整 P10 历史回归 → 全量验证 → 归档证据。无法在基线复现时不得伪造失败记录，按真实证据裁决。

## 五、静态风险边界

RISK-AUDIT2-005（转折连词两处定义不一致）与 009（汉字数字映射有限）不自动算作缺陷：与七项修复直接相关且可确定性复现时评估最小附带修复；未复现则保持 STATIC_RISK 并记录后续建议。不得为宣称风险清零而无边界扩展。

## 六、交付与裁决

证据最低集合：README、risk-remediation-matrix、before-after-regressions、numeric-support、grouped-citation、rich-text-and-strikethrough、legacy-and-exception-scope、epistemic-scope、cross-feature、false-positive、false-negative 回归记录与 manifest.sha256。归档更新 `docs/archive/prompts|research|reports/` 与 `docs/design/rag-answer-quality-evaluation.md`、`docs/README.md`。

裁决名 `P10_SCORER_ROBUSTNESS_06_ACCEPTED` 仅当七项缺陷均有证据支持、历史回归通过且门禁满足时使用；否则 PARTIAL_ACCEPTANCE 或 BLOCKED。提交落 `dev`，Push CI 与 PR CI 以新 SHA 实际完成为准。
