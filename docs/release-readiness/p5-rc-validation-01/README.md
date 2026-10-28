# P5-RC-VALIDATION-01：Release Candidate 构建、容器冒烟与发布前最终验证

> 上游审计：[p5-release-readiness-01](../p5-release-readiness-01/)（`RELEASE_CONDITIONAL_GO`）
> 与 [p5-release-readiness-02](../p5-release-readiness-02/)（R-01/R-02/R-04 FIXED_AND_VERIFIED，R-03 IMPLEMENTED_NOT_FULLY_VERIFIED）。
> 本轮目标：在真实 PR CI 上闭环 R-03 的最后一项未验证内容——`Image build & container smoke` Job，
> 并完成 RC 发布前配置、Runbook 与风险清单的最终核查。

## 最终裁决：`RC_VALIDATION_ACCEPTED`

RC 构建链路已真实经过 GitHub Actions 验证，**具备进入正式部署准备的条件**。
本状态只代表 RC 构建与发布前技术验证通过，不代表生产部署已获批，更不构成本 PR 的合并授权。

## 核心结论

| 验证项 | 结果 |
| --- | --- |
| Draft PR（dev → main） | [#18](https://github.com/BangShou1st/ai-collab/pull/18)，保持 Draft，未合并 |
| PR CI run | [40103355487](https://github.com/BangShou1st/ai-collab/actions/runs/40103355487)（event=pull_request，attempt 1）三个 Job 全部实际执行且 success |
| Image build & container smoke | **首次真实执行，不再 skipped，PASS**：镜像真实构建、容器真实启动、健康/安全/版本断言全部通过 |
| 代码修改 | 无——本轮零代码修改，镜像与冒烟一次通过 |

## 文档目录

| 文件 | 内容 |
| --- | --- |
| [git-and-pr-audit.md](git-and-pr-audit.md) | Git 基线核实、dev→main 差异审查、PR 属性核实 |
| [ci-image-build-results.md](ci-image-build-results.md) | PR CI 三个 Job 结论与镜像构建/revision 追溯证据 |
| [container-smoke-results.md](container-smoke-results.md) | 容器级冒烟逐项断言结果（依赖容器、健康、安全端点、版本） |
| [rc-configuration-review.md](rc-configuration-review.md) | TLS/密钥/外部模型网络/监控/备份五项 RC 配置核查 |
| [release-runbook-review.md](release-runbook-review.md) | 发布与回滚 Runbook 逐条可执行性核对 |
| [release-risk-register.json](release-risk-register.json) | 风险状态更新（R-03 闭环）与新发现风险 |
| [rc-gates.json](rc-gates.json) | RC 验收门槛的机器可读判定 |
| [rc-go-no-go.md](rc-go-no-go.md) | 最终 Go/No-Go 裁决与任务书二十问回答 |

## 验收证据

CI 原始日志归档于 [docs/acceptance-evidence/2026-10-24/p5-rc-validation-01/](../../acceptance-evidence/2026-10-24/p5-rc-validation-01/)：

- `pr-ci-40103355487-backend-job.log`（1688 tests, 0 failures + container test guard passed）
- `pr-ci-40103355487-frontend-job.log`（51 test files passed）
- `pr-ci-40103355487-image-job.log`（镜像构建 BUILD SUCCESS + 冒烟断言输出）

## 边界声明

- 本 PR 保持 Draft，合并 main 必须由用户明确批准后人工触发（PR 正文已写明 DO NOT MERGE）。
- 本轮未部署生产、未创建 Release、未推送任何镜像仓库；CI 中镜像仅存在于 runner 本地。
- 部署环境相关参数（TLS 代理、生产密钥、异地备份、告警通道）仍标注 `[部署环境补充]`，见 rc-configuration-review.md。
