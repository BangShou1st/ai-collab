# 多周期上下文压缩真实模型质量验收 — 结果（2026-10-09，收口修订版）

> 修订说明：本版依据归档的原始调用捕获（model-calls-private.jsonl）、运行记录
> （agent_step / agent_run / agent_message）与资料 fixture 逐项复核后修正了初版的
> 四处不准确结论：对照范围收窄、D1 调用归因按 toolCallId 重新核对、第四次辅助降级
> 改为"输出超长资格保护"（非 LENGTH 截断）、事实核对表按真实资料补全。
> 修正不涉及任何实验输入或原始证据的改动；初版归档文件除本文件与
> fact-checklist.md 外未重写（final-answer-uncompressed-run1.md 因提取脚本取错
> 会话末条消息而以数据库原文重新提取，替换前内容为 run2 回答，属提取错误修正）。

## 结论（收窄）

**受控样本的三周期发布、消费及部分事实延续通过**；严格同问题质量对照、
更正跨周期保真**尚未验证**。定性为受控规模的真实模型质量验收（约 4.3–4.7 万
tokens 裁前估算，未达 256k 自然触发线，不冒充 256k 实测）。

## 运行身份

| 项 | 值 |
| --- | --- |
| 分支 / 起点 HEAD | codex/context-foundation / e092f77（验收期间零代码改动） |
| 验收入口 | RealAcceptanceHostTest（AI_REAL_ACCEPTANCE=true, AI_UPGRADE_REHEARSAL=true, KEEP_CONFIG=true） |
| 隔离 | PG ai-collab-rootfix-pg(55432, 卷 ai-collab-acceptance-20261003)、Redis(16379)、MinIO(18090)；业务容器 5432/6379/9000 未动 |
| 模型 | space-bunny-free（用户默认 Zen provider 配置只读沿用，未修改） |
| 实验组 runG1 | c4437bed-a3e3-432f-8aef-cdbca9c18341（SUCCEEDED, tools=11, 累计 in=252915 tok），8 项问题集 |
| 对照 runC1 | f32bb97e-1518-456a-bf4f-f88f0849df7d（SUCCEEDED，同 8 项问题，普通版资料），**发生 1 个压缩周期，不是未压缩对照** |
| run1 | c2c3a7fd（SUCCEEDED，0 压缩周期），**问题集不同（3 项），不构成同问题未压缩对照** |
| run2 | 5350dc72（SUCCEEDED，0 压缩周期），**用户更正验证运行（独立运行，更正未跨压缩周期传递）** |

对照范围修正（复核后）：本轮**不存在**"同问题、未压缩"的严格对照——runC1 同问题
但发生了 1 次压缩；run1 未压缩但问题不同。三组回答的对照只能用于事实正确性交叉
检查，不能用于"压缩 vs 未压缩"的质量差结论。

## 压缩周期演进（runG1，见 compaction-cycles.txt）

| 周期 | 提交 | 覆盖 | partial | prev 摘要并入 | 摘要 chars |
| --- | --- | --- | --- | --- | --- |
| 1 | COMMITTED | seq 1–7（7 条） | 无 | — | 6219 |
| 2 | COMMITTED | seq 8 超大记录 | partialSeq=8, partialChars=53739 | true | 11750 |
| 3 | COMMITTED | seq 8 完整闭合 | 无 | true | 12365 |
| 4 | UNQUALIFIED（输出超长资格保护，见下） | 未推进 | — | — | — |

## 闭环逐项证据（按 toolCallId 重新归因）

三次 24000-char 正文读取的 toolCallId 与文档归属（capture 口径，含 JSON 包装）：
`call_01a11e9bd010768c883737c9`＝数据报表口径手册、`…c8`＝客户成功服务规范、
`…c7`＝运维手册。周期号对应的 step 序号（seq 8/9/10）分别就是这三次读取。

1. **真实辅助模型调用**：callModelWithoutTools × 4（model-calls-summary.txt
   call#21/23/25/27，请求 12050/62776/12522/62640 chars；另有 runC1 的 call#34）。
2. **真实持久化发布**：agent_step reason=RUN_CONTEXT_SUMMARY，4 行
   （3 COMMITTED + 1 UNQUALIFIED），覆盖字段齐全。
3. **实际 Composer 消费**：周期 1 提交后的主请求（call#22）必选层 2c 含
   `<RUN_CONTEXT_SUMMARY sourceFromSequence="1" sourceThroughSequence="7" cycle="1">`；
   周期 2/3 提交后（call#24/26）为 through=8 的更新摘要。
4. **D1 partial 保留（修正归因）**：周期 2 的 partial 记录是 step seq=8（toolCallId
   `…c9`，数据报表读取）。周期 1 提交后的主请求 **call#22 [7] 中该记录为 55045 chars
   完整原文（无 projection 标记）**——partial 后的第一次主请求确实整条保留。
   周期 2 提交后的主请求 **call#24 [11] 起**同 toolCallId 消息变为 3542 chars 的
   `projection=DETERMINISTIC` 投影（元数据 originalChars=54940、modelVisibleChars=3437，
   口径为 SERIALIZED_TOOL_RESULT_JSON_CHARS）。初版把 call#22 说成"partial 周期整条
   保留"在时序上不成立——call#22 是周期 1（此时 seq8 尚未进入任何摘要），其整条保留
   属"未覆盖记录正常入选"；真正体现 D1 partial 语义的是：partial 记账的 seq8 在
   周期 2 摘要中只送入前缀 53739 chars、周期 3 从未送入偏移继续并完整闭合。
5. **已覆盖前缀退出与未覆盖尾部保留**：周期 3 完整覆盖后（call#26）c9 只剩投影
   （3542 chars），未覆盖的新读取（call#26 [7][9][11] = 16860/16256/15808 chars，
   无投影标记）始终完整原文。另 c7/c8（运维、客户）从 call#22 起就一直是
   DETERMINISTIC 投影（origChars 53838/54320）——它们不是周期 1–2 的覆盖对象，
   属空间不足的正常投影，**不构成"覆盖退出"证据**（初版误把另一调用的投影当作
   seq8 归因，已修正）。
6. **真实最终回答**：final-answer-G1.md。
7. **虚报全文覆盖检查**：无。摘要周期 1 明确"正文内容尚未读取"；周期 3 对 partial
   尾部残片 `es":[]}` 如实标注"无法与任何已知调用结果对应，不能视为空结果"；最终
   回答明确列出"附录段 2 后半段至附录段 20 未读（chunk 11–108/16–109/17–110）"。

## 第四次辅助降级（修正定性）

周期 4 的原始辅助响应 **finishReason=STOP，正文 18740 字符**（capture：AUX call#27，
contentChars=18740，outputTokens=12747 PROVIDER 口径）。触发的是
`runContextQualifies` 的**输出超长资格保护**（RUN_CONTEXT_MAX_OUTPUT_CHARS=16000），
**不是 LENGTH 截断**。降级行为符合 D3 设计：`DOWNSGRADED_UNQUALIFIED`、不发布、
不推进覆盖、用量如实结算（inputTokens=33044/outputTokens=12747, basis=PROVIDER），
周期 3 摘要继续生效。初版"12747 tokens 超长（约 271 chars）"把状态 JSON 的长度
（cycle4-unqualified.json，273 bytes）误当响应正文长度，已修正——271/273 chars 是
落库记账记录的序列化长度，与 18740 字符的响应正文是两个口径。

## 事实核对（27 项清单，按真实资料修正）

清单修正：初版 B2 写了"仅限 P1 故障"——**资料原文没有该限定**（B2 是付费客户
1 小时、7×24；"30 分钟内拉群"是 B3 且确实仅限付费客户），初版把 B3 的限定错安到
B2。回答 G1/C1 均未犯此错。按真实资料重新逐项核对（✓=正确且带来源，
△=已读未在回答中给出，－=问题集未问）：

| ID | 事实 | G1 | C1 | run1(3项问题) | run2(更正+补充) |
| --- | --- | --- | --- | --- | --- |
| A1 | 限流 100 req/min | ✓ | ✓ | ✓ | ✓ |
| A2 | 提额 120 req/min（条件） | ✓ | ✓ | ✓ | ✓（按 U1 更正复述） |
| A3 | 导入 5000 条/CSV/UTF-8 | ✓ | ✓ | － | ✓ |
| A4 | 失败率 2% 拒批 | ✓ | ✓ | － | ✓ |
| A5 | 密钥 180 天轮换 | △ | △ | － | － |
| A6 | 双 release 禁止 | ✓ | ✓ | － | － |
| A7 | 先回滚再修脚本 | ✓ | ✓ | － | － |
| A8 | 备份保留 90 天 | △ | △ | － | － |
| B1 | 工单 4 小时 | ✓ | ✓ | － | － |
| B2 | 付费 1 小时 7×24 | ✓ | ✓ | － | － |
| B3 | P1 30 分钟拉群（付费） | ✓ | ✓ | － | － |
| B4 | 证据保留 180 天 | ✓ | ✓ | ✓ | ✓ |
| B4b | 企业 365 天 | ✓ | ✓ | ✓ | ✓ |
| B5 | 交接 5 个工作日 | － | － | － | － |
| B6 | 回访 20% | － | － | － | － |
| B7 | 升级 48/24 小时 | － | － | － | － |
| B8 | 专属经理 12 个月 | － | － | － | － |
| C1 | 活跃口径·登录 | ✓ | ✓ | ✓ | ✓ |
| C2 | 活跃口径·事件 | ✓ | ✓ | ✓ | ✓ |
| C3 | 时区 UTC+8 | ✓ | ✓ | － | － |
| C4 | 海外节点当地时间 | ✓ | ✓ | － | － |
| C5 | GMV 毛口径 | ✓ | ✓ | － | － |
| C6 | GMV 净口径 | ✓ | ✓ | － | － |
| C7 | T+1 03:00 出数 | △ | ✓ | － | － |
| C8 | 归因 7 天 | ✓ | ✓ | － | ✓ |
| C9 | 归因 14 天（新客） | ✓ | ✓ | － | ✓ |
| C10 | 延迟 30 分钟值班 | ✓ | ✓ | － | － |

核对结论（如实，不凑"全部通过"）：

- **G1**：问题集明确要求的项中 20 项 ✓；**A8（备份保留 90 天）问题第 6 项明确要求
  但最终回答未给出（△）**——摘要周期 2/3 与工具事实层均保留了"90 天"
  （cycle2/cycle3 摘要可查），主请求也可见，属**模型收尾遗漏（模型行为差异）**，
  不是压缩链路丢失；A5/C7 已读但未被问及未主动给出，不算失败。
- **C1**：同问题集，A8 同样遗漏（△）；其余 21 项 ✓（含 C7）。
- **run1**（3 项问题集）：6 项 ✓；B2"1 小时"在 run1 的未读范围声明中提及但未作为
  结论展开（其问题集不含工单时限，匹配）。
- **run2**（更正+补充问题集）：7 项 ✓；U1 更正按"以你的更正为准"复述且声明
  "本轮未重新读取正文"——**更正验证在 run2 单运行内完成，run2 无压缩周期，
  更正跨压缩周期保真未验证**（G1 会话只有 1 个运行，无第二轮更正输入）。
- **周期间事实延续（部分通过）**：cycle3 头部完整包含 cycle1 的
  documentId/snapshotId/contentHash 与大纲结构事实（previousSummaryIncorporated=true
  的真实体现）；周期 2/3 摘要保留 90 天备份等工具事实。但"第一周期发现能否延续到
  第二周期"的完整核验只覆盖了文档身份/结构类事实与部分数值（2%、365 天、120
  条件、14 天窗口），未对全部 27 项做周期间逐项比对。
- 摘要质量：TOOL_FACT vs ASSISTANT_UNVERIFIED 边界严格执行；来源定位精确到
  documentId/snapshotId/chunkId/charFrom。

## 环境与恢复

- Flyway：隔离副本 V61 → V64（宿主自动迁移，仅隔离副本）。
- 模型配置：KEEP_CONFIG=true，开始/结束时 user_ai_provider 与用途分配零修改
  （config-integrity.txt）。
- REAL_ACCEPTANCE_CONFIGURATION_RESTORED 出现于每次宿主停止。
- 浏览器（bsk ccez）：最终回答、来源、诚实缺口完整呈现；整页刷新后登录态、
  会话列表、实验组会话内容完整恢复（browser-final-state.txt）。

## 未验证项（如实记录）

1. **严格同问题、未压缩的质量对照未完成**：runC1 同问题但发生了 1 个压缩周期；
   run1 未压缩但问题集不同。压缩前后的回答质量差无法从本轮数据下结论。
2. **更正跨压缩周期保真未验证**：U1–U3 更正验证在 run2（0 压缩周期）完成；
   G1 会话无第二轮更正输入。
3. **A8（备份保留 90 天）在 G1/C1 最终回答中遗漏**：摘要与主请求均含该事实，
   属模型收尾遗漏；未做修复轮（回答完整性属模型行为差异，不在压缩链路修复范围）。
4. **256k 输入未实测**：裁前估算约 4.3–4.7 万 tokens，未达 256000 自然触发线；
   H/T/L 公式与 256k 常量由 AgentContextBudgetTest 锁定（既有正式回归）。
5. **窗口覆盖注入**未用于最终验收（试验期间观察到压缩触发与辅助窗口核对日志；
   最终验收按 UNKNOWN 窗口回退路径自然触发，生产默认策略不变）。
6. **会话摘要 scope**（主会话 summary）未单独触发；E2 合并事实由正式回归覆盖。
7. **委派子运行的 RUN_CONTEXT**：4f7ac99b 在窗口覆盖试验中走到辅助窗口核对，
   被 H=6000 准入拒绝（未出站），未产生子运行压缩周期；子运行完整闭环未实测。
8. **暂停/取消与压缩的交互、fenced 发布**：由 AgentRunContextCommitPostgresTest
   （10 项）覆盖，本轮未重复实测。

## 是否需要开发

**不需要**。压缩链路行为与第 14–17 节设计一致，未发现生产缺陷。第 3 项（回答
收尾遗漏）属模型行为差异，不以改代码方式"修复"；第 1/2 项属对照实验缺口，
如需补齐属可选的补充实验，不是必做任务。

## 字符/长度口径对照（避免再次混用）

| 口径 | 含义 | 例（周期 2 partial 记录） |
| --- | --- | --- |
| DB out_chars | agent_step.output_json 序列化长度 | 53984（seq 8 工具结果） |
| capture len | 捕获 jsonl 中消息 JSON 长度（含 role/toolCallId 包装） | 55045 |
| originalChars | 投影元数据，SERIALIZED_TOOL_RESULT_JSON_CHARS | 54940 |
| modelVisibleChars | 投影后模型可见 JSON 字符数 | 3437 |
| 正文 chars | 响应 content 字符数（cycle4 为 18740） | — |
| 记账 JSON 长度 | cycle4-unqualified.json 的 273 bytes（状态记录） | 与正文无关 |
