# 多周期上下文压缩真实模型质量验收 — 结果（2026-10-09，收口修订版）

> 修订说明：本版依据归档的原始调用捕获（model-calls-private.jsonl）、运行记录
> （agent_step / agent_run / agent_message）与资料 fixture 逐项复核后修正了初版的
> 四处不准确结论：对照范围收窄、D1 调用归因按 toolCallId 重新核对、第四次辅助降级
> 改为"输出超长资格保护"（非 LENGTH 截断）、事实核对表按真实资料补全。
> 本次再复核以原始辅助请求的 STEP/source 内容绑定 seq8 与文档身份，不以消息排列
> 顺序推算 step 序号；摘要保留与最终回答分别核对，并按实际八项问题撤销错误的
> "20/21、A8 必答遗漏"评分。本次仅改说明文档，未改调用捕获、摘要、回答或 fixture。
> 上一次收口曾修正 final-answer-uncompressed-run1.md 的归档错误：原文件误存 run2
> 回答，已按数据库原文替换，并将 run2 回答独立归档；本次不再改动这些回答文件。

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
| 对照 runC1 | f32bb97e-1518-456a-bf4f-f88f0849df7d（SUCCEEDED，同 8 个问题主题），**发生 1 个压缩周期，不是未压缩对照** |
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

三次 24000-char 正文读取的参数见 `model-calls-private.jsonl` 第 20 行（call#20）：

| toolCallId 后缀 | 文档 | documentId |
| --- | --- | --- |
| `…c7` | 团队协作平台运维手册-放大版 | `b5a90861-1a11-46b0-8ddf-9571167f3a24` |
| `…c8` | 客户成功服务规范-放大版 | `436876f7-ed23-41b3-8518-30f333a7138a` |
| `…c9` | 数据报表口径手册-放大版 | `246f8cf6-3ce4-4f57-88a9-c0309adbd053` |

**seq8 是运维读取 `…c7`，不是报表读取 `…c9`**。身份依据不是摘要模型的转述，
而是原始 AUX call#23 输入：`[STEP 74ac629a-3edd-43ae-9a09-facdea84afdd seq=8
type=TOOL_CALL_COMPLETED tool=read_document_section]` 后的 TOOL_FACT 载有运维
documentId、API 限流正文和 chunkId `54daaaa7-a6c4-4338-aad8-c5aaeb760ea6`；
与 call#20 的 `…c7` 参数一致。主请求消息按观察层顺序排列，不能据此推算持久 step 序号。

1. **真实辅助模型调用**：callModelWithoutTools × 4（model-calls-summary.txt
   call#21/23/25/27，请求 12050/62776/12522/62640 chars；另有 runC1 的 call#34）。
2. **真实持久化发布**：agent_step reason=RUN_CONTEXT_SUMMARY，4 行
   （3 COMMITTED + 1 UNQUALIFIED），覆盖字段齐全。
3. **实际 Composer 消费**：周期 1 提交后的主请求（call#22）必选层 2c 含
   `<RUN_CONTEXT_SUMMARY sourceFromSequence="1" sourceThroughSequence="7" cycle="1">`；
   周期 2/3 提交后（call#24/26）为 through=8 的更新摘要。
4. **D1 partial 记录仍入选**：周期 2 提交后的首次主请求是 **call#24**。其中 seq8
   的 `…c7` 仍在消息索引 15（索引从 0 开始），但因请求空间压力已是
   `projection=DETERMINISTIC` 投影（originalChars=53838、modelVisibleChars=3591）。
   这证明 partial 记录未被按整条覆盖排除，**不证明整条原文进入主请求**。
   call#22 只消费周期 1（through=7），当时 seq8 尚未进入摘要；该轮 c7 已是投影，
   不能作为 partial 后的证据。原文续读发生在摘要侧：call#23 送入 seq8 前缀，周期 2
   记 `sourcePartialChars=53739`；call#25 从该偏移续读尾部 `es":[]}`，周期 3 完整闭合。
5. **完整覆盖后的来源退出**：周期 3 提交后，**call#26 不再含 `…c7` 工具消息**；
   同一运维读取已由有效摘要替代。`…c9` 与 `…c8` 仍以投影存在，但它们不是此次
   seq8 覆盖对象，不能用于证明覆盖退出。call#26 中另有后续读取的未投影结果，
   也不能把这些新读取与 seq8 混为一条记录。
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

## 事实核对（实际问题与摘要保留分开）

清单修正：初版 B2 写了"仅限 P1 故障"——**资料原文没有该限定**（B2 是付费客户
1 小时、7×24；"30 分钟内拉群"是 B3 且确实仅限付费客户），初版把 B3 的限定错安到
B2。回答 G1/C1 均未犯此错。27 项资料清单的**摘要内容保留**与**最终回答表述**
现分别列在 `fact-checklist.md`，不能用最终回答正确反推该事实经过压缩。

实际问题见捕获 call#18（G1）及 call#29（C1），明确要求的八个主题如下。G1 的
`final-answer-G1.md`「八项问答」与 C1 的 `final-answer-C1.md`「八项条款速查」
按同一顺序均给出了回答及文档/章节来源；以下映射用于核对问题范围，不将整份资料
清单冒充必答项评分。

| 问题序号 | 实际主题 | 对应资料清单项 |
| --- | --- | --- |
| 1 | API 限流规则及提额条件 | A1、A2 |
| 2 | 客户交互证据保留期限 | B4、B4b |
| 3 | 活跃用户统计口径 | C1、C2 |
| 4 | 批量导入上限与失败率 | A3、A4 |
| 5 | 归因窗口 | C8、C9 |
| 6 | 数据库迁移失败处置顺序 | A7；A6 是回答附带的相关约束 |
| 7 | 工单响应时限 | B1、B2；B3 是回答附带的故障分级说明 |
| 8 | 报表时区 | C3、C4 |

- **撤销错误评分**：原请求没有要求 A8（备份保留 90 天），第六项只问数据库迁移
  失败处置顺序。因此 A8 虽在周期 2/3 保留、未在 G1/C1 回答展开，也不构成必答项
  遗漏；不再使用此前错误的 "20/21" 评分或将它定性为模型收尾失败。
- **额外事实不是必答范围**：G1 未展开 C7 出数时间或 C10 延迟处置；C1 附带了 C7，
  对 C10 只提及超 30 分钟按运维章节处理。GMV 条款在 G1 中展开，在 C1 中仅给出
  毛/净口径的概括。分别记为完整、片段或未述，不以主题名称或另一条 30 分钟规则
  代替具体事实证据。
- **run1/run2**：各按自身问题核对。run2 对更正的复述与导入/归因补充有记录，且
  明确区分本轮新读取和沿用上一轮证据；这是无压缩运行内的行为，不证明更正跨周期保真。
- **周期间事实延续（部分通过）**：三个摘要保留文档身份与结构信息；周期 2/3
  完整保留运维正文事实 A1–A8（含 90 天、120 提额条件、2% 拒批）。客户/报表侧
  仍只有部分启发式标题片段（如 365 天与 14 天），其正文条件缺口未被消解。
  这不是全部 27 项事实都经过压缩的证明。
- **摘要边界**：周期 2/3 明确仅有运维 seq8 的正文事实，未读取客户/报表正文，
  partial 尾部不可识别。保留这种边界声明与部分来源定位，不外推为所有事实/条件完整保真。

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
3. **全部资料事实的压缩质量未被本样本覆盖**：周期 2/3 压缩正文只覆盖运维 seq8；
   客户/报表正文的最终回答不能用于证明这些事实经过摘要。A8 未在最终回答展开，
   但不在实际问题必答范围，不能据此计为失败。
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

**本次证据复核不需要生产开发**。在已观察到的压缩链路中未发现新的生产缺陷；
本次纠正的是文档归因与评分，不以此触发生产代码修改。严格对照、跨周期更正及
更广的事实覆盖仍属可选补充实验，不是下一轮必做任务。

## 字符/长度口径对照（避免再次混用）

| 口径 | 含义 | 例（周期 2 partial 记录） |
| --- | --- | --- |
| DB out_chars | agent_step.output_json 序列化长度，见 steps-G1.txt | 54742（seq8 运维结果） |
| capture len | 捕获中完整消息的 JSON 长度，包含工具消息包装且依赖序列化方式 | 不与 DB 长度或投影字段互换，也不以消息大小认定 step 身份 |
| originalChars | 投影元数据，SERIALIZED_TOOL_RESULT_JSON_CHARS | 53838（call#24 的 `…c7`） |
| modelVisibleChars | 投影后模型可见 JSON 字符数 | 3591（call#24 的 `…c7`） |
| 正文 chars | 响应 content 字符数（cycle4 为 18740） | — |
| 记账 JSON 长度 | cycle4-unqualified.json 的 273 bytes（状态记录） | 与正文无关 |
