# 多周期上下文压缩真实模型质量验收 — 结果（2026-10-09）

## 结论：通过（受控规模的真实模型质量验收）

3 个有效压缩周期 + 1 个 D3 截断降级周期（未提交，按设计保留旧摘要），全链路真实组件、真实模型、真实持久化。

## 运行身份

| 项 | 值 |
| --- | --- |
| 分支 / HEAD | codex/context-foundation / e092f77（验收期间零代码改动） |
| 验收入口 | RealAcceptanceHostTest（AI_REAL_ACCEPTANCE=true, AI_UPGRADE_REHEARSAL=true, KEEP_CONFIG=true） |
| 隔离 | PG ai-collab-rootfix-pg(55432, 卷 ai-collab-acceptance-20261003)、Redis(16379)、MinIO(18090)；业务容器 5432/6379/9000 未动 |
| 模型 | space-bunny-free（用户默认 Zen provider 配置只读沿用，未修改） |
| 实验组 runG1 | c4437bed-a3e3-432f-8aef-cdbca9c18341（SUCCEEDED, tools=11, 累计 in=252915 tok） |
| 对照 runC1 | f32bb97e-1518-456a-bf4f-f88f0849df7d（SUCCEEDED, 同问题, 1 个压缩周期） |
| 未压缩对照 | run1 c2c3a7fd（SUCCEEDED, 0 压缩周期） |

## 压缩周期演进（runG1，见 compaction-cycles.txt）

| 周期 | 提交 | 覆盖 | partial | prev 摘要并入 | 摘要 chars |
| --- | --- | --- | --- | --- | --- |
| 1 | COMMITTED | seq 1–7（7 条） | 无 | — | 6219 |
| 2 | COMMITTED | seq 8 超大记录 | partialSeq=8, partialChars=53739 | true | 11750 |
| 3 | COMMITTED | seq 8 完整闭合 | 无 | true | 12365 |
| 4 | UNQUALIFIED（completion 12747 tok 超长，D3 降级） | 未推进 | — | — | — |

## 闭环逐项证据

1. **真实辅助模型调用**：callModelWithoutTools × 4（model-calls-summary.txt call#21/23/25/27，输入 12050/62776/12522/62640 chars）。
2. **真实持久化发布**：agent_step reason=RUN_CONTEXT_SUMMARY，4 行（3 COMMITTED + 1 UNQUALIFIED），覆盖字段齐全。
3. **实际 Composer 消费**：周期1 提交后主请求含 \<RUN_CONTEXT_SUMMARY sourceFromSequence="1" sourceThroughSequence="7" cycle="1">\（call#22 [2]）；周期3 后 through=8（call#24/26）。
4. **覆盖原文退出活跃视图**：seq8 的 53984 chars 工具结果在 partial 周期（call#22）整条保留（D1 正确），周期3 完整覆盖后被确定性投影为 3384 chars（call#26 [13]）——已覆盖前缀由摘要承载，未覆盖尾部（seq13/14/17/20 的 16k 级结果）始终保留原文。
5. **未覆盖尾部保留**：call#26 [7][9][11] = 16860/16256/15808 chars 完整原文。
6. **真实最终回答**：final-answer-G1.md（8 项问题逐条正确，见下）。
7. **虚报全文覆盖检查**：无。摘要周期1 明确"正文内容尚未读取"；周期3 对 partial 尾部残片\s":[]}\如实标注"无法与任何已知调用结果对应，不能视为空结果"；最终回答明确列出"附录段 2 后半段至附录段 20 未读（chunk 11–108/16–109/17–110）"。

## 事实核对（问题集内 24 项，fact-checklist.md）

- G1（3 周期）：22/24 直接命中（C7 出数时间、A5 轮换不在本轮问题集，未问不答，合理）；问题集要求的 A1-A4/A6-A8/B1-B4b/C1/C3/C5/C6/C8/C9 全部正确且带来源。
- C1（1 周期对照）：同样全部正确（含 C7）。
- run1（未压缩）：仅回答了问题集 3 项（A1/A2/B4/B4b/C1/C8/C9），因为问题不同——它是"小问题未触发压缩"的对照。
- 周期间事实延续：cycle3 头部完整包含 cycle1 的 documentId/snapshotId/contentHash 与大纲结构事实（previousSummaryIncorporated=true 的真实体现）。
- 摘要质量：TOOL_FACT vs ASSISTANT_UNVERIFIED 边界严格执行（cycle1 §3"该陈述为模型自述，未附任何已完成的读取证据"）；数值与条件保留（2% 失败率、120 req/min 条件、365 天不追溯）；来源定位精确到 documentId/snapshotId/chunkId/charFrom。
- 更正优先：在 run2 会话（同会话第二轮，U1 更正）中验证——最终回答明确"提额条件（以你的更正为准）"并正确复述；"本轮未重新读取正文，仅按你的更正复述条件"的验证状态声明诚实。

## D3 截断降级实测（额外收获）

周期4 的辅助模型输出 12747 tokens（约 271 chars 可见文本记录）触发输出超长判定，按 D3 设计降级 DOWNGRADED_UNQUALIFIED：不发布、不推进覆盖、用量如实结算（inputTokens=33044/outputTokens=12747, basis=PROVIDER），周期3 摘要继续生效。这正是"截断产物不能冒充完整摘要"红线的真实行为证明。

## 环境与恢复

- Flyway：隔离副本 V61 → V64（宿主自动迁移，仅隔离副本）。
- 模型配置：KEEP_CONFIG=true，开始/结束时 user_ai_provider 与用途分配零修改（config-integrity.txt）。
- REAL_ACCEPTANCE_CONFIGURATION_RESTORED 出现于每次宿主停止。
- 浏览器（bsk ccez）：最终回答、来源、诚实缺口完整呈现；整页刷新后登录态、会话列表、实验组会话内容完整恢复（browser-final-state.txt）。

## 未验证项（如实记录）

1. **256k 输入未实测**：本轮裁前估算在 4.3–4.7 万 tokens 级（27 万 chars 材料 + droppedSourceChars），未达 256000 的自然触发线。属"受控规模的真实模型质量验收"，不冒充 256k 实测；H/T/L 公式与 256k 常量由 AgentContextBudgetTest 锁定（既有正式回归）。
2. **窗口覆盖注入**未用于本轮最终验收（前期试验中曾注入 W=12000 并成功观察到压缩触发与辅助窗口核对日志，但为保持"模型真实硬窗口 H、输出封顶和生产默认策略不变"，最终验收按 UNKNOWN 窗口回退路径自然触发）。
3. **会话摘要 scope**（主会话 summary，非 RUN_CONTEXT）未单独触发；两个 scope 的合并事实（E2）由正式回归覆盖。
4. **委派子运行的 RUN_CONTEXT**：4f7ac99b（run5 的子运行）在窗口覆盖试验中走到辅助窗口核对，但被 H=6000 拒绝（准入前拒绝，未出站），未产生子运行压缩周期；行为符合设计（C5 准入核对），但子运行完整闭环未在本轮实测。
5. **暂停/取消与压缩的交互**、**fenced 发布**：由真实 PostgreSQL 并发回归（AgentRunContextCommitPostgresTest 10 项）覆盖，本轮未重复实测。

## 是否需要开发

**不需要**。全链路行为与第 14–17 节设计一致，未发现生产缺陷；D3 降级、D1 partial 保留、C3 覆盖推进、E2/E3 事实与重组均在真实模型下按设计工作。
