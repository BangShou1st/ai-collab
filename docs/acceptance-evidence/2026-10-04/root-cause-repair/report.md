# Agent 稳定性根因修复与真实复验

日期：2026-10-04。基线 `c170891`（origin/codex/context-foundation），本轮代码 HEAD 见 `database-final-state.json.codeVersion`。隔离副本库沿用卷 `ai-collab-acceptance-20261003`（容器 ai-collab-rootfix-pg，localhost:55432）；原业务 PostgreSQL/Redis/MinIO 全程停止。模型 space-bunny-free，温度 0.2，规划输出 6000；Agent 输入上限 50000/输出 20000/步骤 24/工具 16，与本轮前完全一致。**本轮未整体宣称质量通过；规划内容一项未重新验证（见 quality-review.json 第 6 项）。**

## 五组根因与修复（确定性部分）

| 组 | 根因 | 修复（提交） | 真实复验 |
| --- | --- | --- | --- |
| 1 用量遮蔽 | `AgentRunEventRecorder` 多处 `LEAST(max,…)` 把真实消耗截到上限；Coordinator 只查输出超额；callModel 后取消丢弃用量 | `403ff00`：V56 迁移增加 `input_tokens_actual/output_tokens_actual`（used 保持预算语义，V22 CHECK 不动）+ `usage_basis`（PROVIDER/ESTIMATED/UNKNOWN）；全部记账路径双记账；返回后按实际输入超额结算并 BUDGET_EXCEEDED（不执行该响应工具）；取消前先结算 | 三批 40 个运行全部 recorded==actual（audit `usage_shadowed=0`）；本轮无运行实际超额（历史 52289 场景由 Testcontainers 真实 PG 回归覆盖：AgentActualTokenUsageIntegrationTest，7 项含工具 SKIPPED/幂等/子运行汇总/取消结算） |
| 2 新目标漏提取 | `appendUser` 新目标分支只 replaceGoal | `d105f03`：replaceGoal 后继续 classifyAndMerge，新条目绑定真实 messageId 与新 goalRevision | fixed24 第18轮"新目标…最多6项，取代8项，日期不改，负责人不改"→ 三条新 active 约束 sourceMessageId=该消息 |
| 3 约束串值 | 每个作用域 value 存整段请求 | `d105f03`：约束拆分为规范化事实（value/detail/quote/needsClarification）；疑问/否定/多值保守不猜；"沿用"仅唯一可定位时复用；存量 v2 条目写入时渐进重规范化 | fixed24 终态：active 仅 最多6项+日期区间+Local Owner；10/8 只存在于 superseded 历史；最终摘要 0 次提及旧数量、0 处"待确认"数量（quality-review 第3项） |
| 4 摘要截尾 | `bounded(1200)` 截尾后覆盖照常推进 | `97d43a7`：取消截尾；确定性容量检查；超长一次有界重压缩；仍超长 DOWNSGRADED_UNQUALIFIED 保留上一份、覆盖不推进；usage 按完整响应 | 真实命中：第22/23轮摘要输出超长 → 两条 DOWNSGRADED_UNQUALIFIED（PROVIDER 记账），第21/24轮正常 COMMITTED；终态摘要 523 字干净（summary-source-audit.json + DB attempt 终态） |
| 5 投影范围矛盾 | 正文截 200 字但范围字段留原值；originalChars 是序列化 JSON 长度 | `67a7420`：可见终点=起点+可见长度，originalThroughOffset/omittedChars/bodyProjection 显式区分；originalCharsSemantics/modelVisibleChars（定点自洽）；续读提示 | minimal/fixed24 读取轮回答逐字给出可见范围与续读参数（如 4.4：char4956–5513，可见 0–200/557，后半357未读，fromOffset=200 续读），无一处把投影当全文 |
| 规划旧10项 | 模型把约束历史写成当前验收要求；忽略本次检索来源 | `eecf100`：PLAN_INPUT 约束文本标注"可能含已取代旧值"；CURRENT_VS_HISTORY 与 SOURCE_AUTHORITY 规则（不硬编码数量）；maxTaskCount 结构化校验不变 | **未重新验证**：fixed24 第23轮运行预算收尾，模型如实声明未调用 start_task_plan，未产生任何草稿/操作（plans=0、operations=0）；不做补跑、不改题 |

## 批次与逐项核对

- `rootfix-minimal`（新项目/新会话，第1–10轮）：全部 SUCCEEDED、retry=0。第10轮"调整为最多8项取代10项"→ 回答复述"8项取代10项，以此为准"，日期/负责人不变。
- `rootfix-fixed24`（新项目/新会话，同字节蓝图，第1–24轮严格顺序，中途零改动）：24/24 SUCCEEDED、retry=0、无用量遮蔽。逐项：日期 2026-10-05..10-25、Local Owner、10→8→6 全链路正确；第22轮复述仅当前6项；无重复规划、无越权、无正式任务确认（confirmations 本轮新增=0）。**失败项：第23轮规划生成未执行**（预算收尾，模型诚实报告并给出定稿参数，未伪造成功）。
- `rootfix-correction`（原失败会话 + 全部历史保留，第14/16/18/21/22/24轮）：全部 SUCCEEDED。第14/16轮费用范围结论保持"资料未取得"；第21轮以实时工具结果区分历史描述与当前定义；第24轮读出原 READY 规划且未重复启动、未确认。

## 测试统计（不重复相加）

- 后端全量：1006 项，通过 995，显式 opt-in 跳过 11，失败 0（含真实 PostgreSQL Testcontainers 集成）。
- 新增回归：AgentActualTokenUsageIntegrationTest 7 项、AgentWorkingStateConstraintIntegrationTest 7 项、Coordinator/Summarizer/Composer/PlanningPolicy 新增确定性用例（已含于全量）。
- 前端：vitest、vue-tsc、Vite 生产构建结果见交付回复（运行记录 /tmp/fe.log 摘录）。
- 真实模型三批 40 轮不是单测，不计入上述统计。

## 环境恢复

见 `config-restoration.json`（沿用本轮宿主 finally 恢复标记）与本目录 report；本轮容器 ai-collab-rootfix-{pg,redis,minio} 停止但保留；卷未删除；未部署、未合并 main、未迁移正式库。私有登录缓存与状态文件保留在忽略的 target 下。
