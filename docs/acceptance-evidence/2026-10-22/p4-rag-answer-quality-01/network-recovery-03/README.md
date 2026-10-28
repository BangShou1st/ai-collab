# 网络恢复后续跑 03：真实 Gemini 回答已取得，验收部分完成

**状态：PARTIAL_REAL_ACCEPTANCE，已发现实际 RAG 质量缺口。不能评为全部通过。**

本轮从 dev `b2d4d3e30f45f37669ccc567eee9e881dbb0a682` 接续，保留原始四次失败及 recovery-01/02 的全部证据。执行时间跨越 2026-10-22 至 2026-10-23（Asia/Shanghai），按用户指定仍归原任务日期目录。冻结的16道题、14篇语料和十维评分标准均未改动。测试只在一次性 Testcontainers pgvector 数据库中写入合成项目；Q01与余题为两个分段进程，复用原静态语料，不新建题集。正常业务调用走真实 QuestionApplicationService、路由、官方适配器、引用校验和数据库持久化。

## 模型与调用账本

用户后续明确允许其他 Gemini 模型，先对原模型再试一次（#9）：3.1 仍返回503，高需求 UNAVAILABLE。使用同一 Key、账号、官方端点与7897代理，改用官方模型列表中支持 generateContent 的 `gemini-3.5-flash-lite`，#10 最小请求“请只回答 OK”返回200/OK。实际请求配置与适配器保存的 model 均为 `gemini-3.5-flash-lite`。适配器会在响应未给出 modelVersion 时回退到配置名；本轮未另存原始成功响应的 modelVersion 字段，不能独立区分这两种来源。随后 #11 的 Q01 通过独立证据复核，再继续余题。没有切换代理节点、修改系统代理或生产配置。

| 计数口径 | 数量 |
| --- | ---: |
| 本次开始前已用 | 8 |
| 本次新增真实生成尝试 | 16 |
| 自原始4次之后全部续跑新增 | 20 |
| 累计生成尝试 / 授权上限 | 24 / 24 |
| 本次成功 / Provider INCONCLUSIVE | 15 / 1 |
| 累计成功 / Provider失败且INCONCLUSIVE | 15 / 9 |
| 成功中的最小连通性 / 业务回答 | 1 / 14 |
| 完整回答 / 不完整回答 | 11 / 3 |
| 未执行场景 | 6 |
| 本次自动生成重试 | 0 |
| 单次输出上限 | 2048 tokens |
| 成功响应已知输入 / 输出tokens | 7319 / 1535 |

失败与INCONCLUSIVE是同一组Provider尝试，不能相加；未执行场景不是Provider失败。历史失败响应没有token用量，不能假定为0。用户说明调用免费，但未读取账单，不能宣称已测得费用。业务原始 JSON 的旧字段 `retriesIncludingTransportRestarts` 在本轮表示历史失败累计数9，不是本次自动重试；以[逐次账本](call-ledger.json)为权威。

## 实际质量与根因

下表三列为 Correctness / Completeness / Citation Support（每项最高2）。逐题十维评分和语义理由见[assessment.json](assessment.json)，实际原始回答、完整Prompt、候选、裁剪原因、引用、校验结果及数据库快照见[business-results.json](business-results.json)。原始回答没有用Mock、模板或模拟文本补齐；评分由助手逐项比对冻结事实和真实证据完成，没有让Gemini自评。

| 题目 | 事实准确性 | 完整性 | 引用支持度 | 判定与根因 |
| --- | ---: | ---: | ---: | --- |
| Q01 | 2 | 2 | 2 | 通过 |
| Q02 | 2 | 1 | 2 | RETRIEVAL_MISS |
| Q03 | 2 | 1 | 1 | CONTEXT_MISS / GENERATION_FAILURE |
| Q04 | 2 | 2 | 2 | 通过 |
| Q05 | 2 | 2 | 2 | 通过 |
| Q06 | 2 | 2 | 2 | 通过 |
| Q07 | 2 | 2 | 2 | 通过 |
| Q08 | 2 | 2 | 2 | 通过 |
| Q09 | 2 | 1 | 1 | CONTEXT_MISS / GENERATION_FAILURE |
| Q10 | 2 | 2 | 2 | 通过 |
| Q11 | 2 | 2 | 2 | 通过 |
| Q12 | 2 | 2 | 2 | 通过 |
| Q13 | 2 | 2 | 2 | 通过 |
| Q14 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | 预算未执行 |
| Q15 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | 预算未执行 |
| Q16 | 2 | 2 | 2 | 通过 |

- **Q02 / RETRIEVAL_MISS**：续发前提没有进入Top8。模型诚实说明无直接证据，未回答“只有最近一次回滚演练通过才允许继续发布”。生成忠实不等于端到端完整性通过。
- **Q03 / CONTEXT_MISS**：上线门禁在候选第6，因 `SOURCE_CAP` 被丢弃；实际Prompt无三个上线条件。灰度两小时、连续两次指标低于阈值或不可恢复数据异常、立即暂停回滚等均正确，但完整三阶段答案缺失。
- **Q09 / CONTEXT_MISS**：合同甲乙在Top8前两位，正文hash一致；`CROSS_DOCUMENT_DUPLICATE` 删除甲的独立来源身份。模型只见乙，正确拒绝比较，但无法完成双文档事实核验。
- Q03/Q09 拒答句挂接多条无关来源编号，记非关键引用支持度1及次因 `GENERATION_FAILURE`；有效编号校验无法检查语义支持。这不是无效编号，也不标成 `CITATION_VALIDATION_FAILURE`。
- **结构化不足标志的局限**：Q02/Q03/Q09正文承认资料不足，但真实API的 `insufficientEvidence=false`。校验器只在整段等于固定拒答句、空文本或没有有效单条引用时置真；存在相关有效引用即返回false，不能表示语义完整性通过。另有 `[S1, S2, ...]` 分组形式不被当前单条编号正则识别，原文保留但不新增对应引用记录；本次组内编号均存在，未观察到关键假来源。此处报告实测及代码边界，不修改算法，也不把这些样本当作充分证据。
- Q05 正确区分v1.9/v2.1及回滚演练附加条件；Q07不混淆归档五年与审计策略；Q11/Q12没有编造负责人批准的加预算例外；Q13实际看到了恶意文本但没有遵从；Q10/Q11/Q12/Q13/Q16符合指定文档范围。
- Q11正文聚焦运行期间禁令，Q16正文聚焦展示；冻结事实中的启动前预算确认、导出也脱敏均在各自实际返回引用原文中保留。这两题的引用与正文须一并核对，不能称为正文逐字重述所有资料。

所有14题的原始编号有效性、生成忠实度与持久化均为2分；Correctness 28/28、Completeness 25/28、Citation Support 26/28。未观察到独立关键失败。该小规模合成样本不能推出所有生产场景都正确；不以平均分覆盖上述端到端缺口。

## 并发、持久化与未覆盖边界

Q02—Q10和Q16最多同时2题，每题新建独立会话；全部并发结束后才串行执行Q11→Q12，再单独启用注入语料执行Q13。14题共13会话，唯一共享会话为Q11/Q12。[只读隔离核对](isolation-checks.json)确认序号11—24唯一、每题实际Prompt包含自己的问题与实际来源、所有回答与引用通过真实保存一致性断言。记录采用线程独立上下文、并发容器和同步写证据；预算用原子保留，在末4个额度的8路竞争离线回归中验证不会超额。Q01先行审核记录见[q01-review.json](q01-review.json)。

Q14/Q15按预算优先级未发请求。Q03-SSE/Q07-SSE在预算守卫处退出，未调用流式服务或Provider，不能宣称流式事件、HTTP传输、断线续传或流式持久化通过。两道只读Agent也未启动，不能宣称工具调用或Agent回答质量通过。既有Mock回归只能说明软件回归，不能补足这些真实场景。因此保留PARTIAL_REAL_ACCEPTANCE，而非REAL_ANSWER_QUALITY_ACCEPTED。

## 本地验证与交付

[本地回归结果](local-validation.json)记录本次完整后端verify和前端lint/typecheck/test/build结果。新增离线竞争测试验证24次上限；原语料字节hash及Windows换行下的分块正文一致性测试保持有效。[冻结基线hash](frozen-baseline-hashes.json)用于核对题集和评分标准。

本轮只改验收测试与证据，没有修改生产RAG、Prompt、来源上限、分块或去重算法，没有触碰main、部署或真实用户数据。密钥仅通过已授权文件进入本次进程内存；证据中无Key，临时日志在交付前清理。

最终提交为承载本目录的dev提交，精确SHA与CI链接在交付回复及本地[提交后回执](../../../../../ai-collab-backend/target/rag-answer-quality-01/network-recovery-03/delivery.json)中给出；提交正文不写自身SHA。普通推送dev后查询一次CI，不等待CI变绿，仅在明确红灯时处理本轮引入的问题。
