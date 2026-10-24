# test-and-ci-coverage.md — 测试与 CI 有效覆盖审计

## 1. CI 对目标提交的覆盖 — 已覆盖

| 项 | 值 |
| --- | --- |
| 当前 dev HEAD | `15f4905`（docs-only 提交：P4 生命周期 CI 结果补记） |
| **HEAD 自身的 CI** | run [40062189574](https://github.com/BangShou1st/ai-collab/actions/runs/40062189574) · attempt 1 · head `15f4905` · branch dev · **completed / success**（gh run list 实测） |
| 代码提交 CI | run [40061629863](https://github.com/BangShou1st/ai-collab/actions/runs/40061629863) · head `eef4d12` · success（最后一个生产代码提交 536ca3a 之后链路上的全部 run 均绿） |
| 近 8 次 dev run | 全部 completed/success（40062189574 / 40061629863 / 40057902680 / 40057436278 / 及 10-23~10-24 的 536ca3a、3b87d76、44dfc5e、65e046f、71939d0） |
| 被跳过的测试 | 3 跳过为历史即跳过的 RealAgentLoopPostgresTest（真实模型联调类，不属 CI 门禁）；CI 有 Python 断言防 Testcontainers 整类静默跳过 |
| CI 未包含的外部真实验收 | 真实模型生成/流式/工具调用（外部付费/限流服务）、真实浏览器 E2E、真实 MCP 连接——均以 docs/acceptance-evidence 独立归档 |

**结论：CI 覆盖目标提交，无需为本轮重跑完整 verify**（本轮零生产代码改动）。

## 2. CI workflow 质量 — 高于平均

- .github/workflows/ci.yml：push(main,dev) + PR→main + 手动；permissions: contents: read；PR 并发取消旧跑。
- backend job（timeout 60min）：JDK21 temurin + maven 缓存 → `mvnw -B -ntp verify`（含 Testcontainers，CI 同本地命令）→ **Python 脚本断言 10 个关键 Testcontainers 类真实执行**（防 Docker 缺失静默跳过）→ Surefire/JaCoCo 归档 14 天。
- frontend job（timeout 30min）：pnpm 11.9.0 与 packageManager 精确对齐 + 缓存 → `--frozen-lockfile` install → lint/typecheck/test/build。
- 缺口：无镜像构建/发布 job（R-03）。

## 3. 测试资产盘点

| 套件 | 规模 | 证据 |
| --- | --- | --- |
| 后端 verify（含 Testcontainers） | 1670+ 用例 | p4-model-quick-connect-02 §G（BUILD SUCCESS 11m57s）+ CI 连续全绿 |
| Agent 模块定向 | 762 用例全绿（3 历史跳过） | p4-agent-real-e2e-01 §I |
| 生命周期契约定向 | 123 用例全绿（9 测试类） | p4-agent-lifecycle-real-e2e-02 §J |
| 前端 Vitest | 393 用例全绿（51 文件） | p4-agent-real-e2e-01 §I |
| 前端 lint/typecheck/build | 全绿 | 同上 + CI |
| MCP 专项 | 7 个测试类 | feature-inventory §G |

已知的本机环境存量竞态：Agent 租约/时序 2 个测试在本机高负载下偶发失败，经基线对照实验（worktree 检出 71939d0 复现）+ CI 独立环境全绿证明为环境竞态非代码回归（p4-model-quick-connect-02 §G 有完整记录）。未发生静默跳过、删测试、改断言迎合错误行为。

## 4. 历史验收证据有效性复核

| 证据 | 基线 | 与当前 HEAD 的差异 | 有效性判定 |
| --- | --- | --- | --- |
| p4-agent-lifecycle-real-e2e-02 | e1d2765 | 之后仅 2 笔 docs-only（eef4d12、15f4905） | **有效** |
| p4-agent-real-e2e-01 | 536ca3a（含修复 3b87d76 之后） | 之后仅 docs + 1 个测试修复提交 | **有效** |
| p4-knowledge-real-e2e-01 | 2f0a4b27 | 之后 Provider 模块重构（quick-connect-02）触碰其发现的缺陷并修复复验 | **有效**（其 S3 缺陷已由后轮修复关闭） |
| p4-model-quick-connect-real-acceptance-01 | 44dfc5e | 之后 docs-only + Agent 修复 | **有效** |
| p4-model-quick-connect-02 | 65e046f | 之后 docs-only | **有效** |
| p2-business-e2e-02（2026-10-19） | 更早 | 协作域核心代码此后无破坏性变更；协作域在后续每轮 CI verify 中持续回归 | **有效**（真实验收 + 持续自动化回归双重保障） |
| Zen Free | — | 上游限流，未复验 | **过期/未执行**（R-10） |

## 5. 覆盖缺口（详见 risk-register）

- work 可视化域 + 通知中心：无专项验收（R-06）。
- 规划页面编辑/版本链路：仅离线测试（business-e2e-matrix 闭环 3）。
- 真实 MCP 连接（R-05）、Zen 复验（R-10）、负载测试（R-11）。

## 6. 本轮测试策略说明

本轮无生产代码改动 → 依赖 HEAD 自身 CI（40062189574 success）+ 上述有效性复核，未重复执行完整 verify；补充的低成本验证为：本地服务健康实测（docker ps healthy 13h、/actuator/health UP、前端 200）、数据库只读实测（flyway 66/66）、gh CI 记录核验。**未修改任何测试标准，未静默跳过任何失败。**
