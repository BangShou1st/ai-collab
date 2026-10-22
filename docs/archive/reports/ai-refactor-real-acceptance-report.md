# AI 重构真实验收补充报告（2026-10-03）

后续真实提案链路和工具校验修复记录见 [太空兔验收补充报告](space-bunny-acceptance-report.md)。该补充完成同一原提案的修订、审批和幂等 API 验收，单独保留浏览器未验证与回答额外输出限制；以下为原轮次记录。

> 后续三项质量改进及真实提案尝试见 [质量收尾报告](ai-refactor-quality-report.md)。负责人建议字段核查已纠正此前结论；真实提案链路因 HTTP 429 停止，未宣称通过。

本轮实际调用修正后的生产 Zen 适配器，并使用真实 Ollama、项目架构文档和持久化 MinIO。**真实 JSON、原生工具调用、同需求跨模型完整规划、文档首问与追问、对象重启后下载均有成功证据。发现的失败也保留，不据此宣称所有模型或所有资料均可靠。正式环境未发布迁移。**

## 环境与边界

- 数据库仍为离线业务库副本 `ai-collab-acceptance-postgres-20261003`，本机 55432；原 `ai-collab-postgres` 和源卷未修改。V45→V52 演练见原报告，本轮未重做升级或迁移正式库。
- `RealAcceptanceHostTest` 使用真实 Spring 应用、生产网关、Runtime 和存储，没有模型或出站策略 mock；须显式设置 `AI_REAL_ACCEPTANCE=true` 与 `AI_UPGRADE_REHEARSAL=true`，连接受限副本，服务有停止标记和时限。
- 密钥来自先前授权的桌面输入；业务应用读取副本中加密的个人 Zen 配置。没有复制其他项目凭据，没有在报告或日志输出密钥。
- 独立 Edge 无头浏览器访问本机前端 15173、后端 18080。MinIO 使用专用容器和命名卷 `ai-collab-real-acceptance-minio-20261003`，本机 18090；Redis 为专用 16379。Ollama 使用本机已安装服务及模型，未下载模型。

## Zen 协议与真实业务

| 项目 | 实际结果 | 证据 |
| --- | --- | --- |
| JSON/SSE | `mimo-v2.5-free` 返回完整可解析 JSON，严格满足 `{"probe":true}`，约 3.45 秒 | `ai-collab-backend/ai-real-acceptance-zen-json.log` |
| 原生工具调用 | 调用 ID、`capability_probe` 名称及 `{"value":"probe"}` 参数契约通过；探测工具未执行 | `ai-collab-backend/ai-real-acceptance-zen-tools.log` |
| 模型目录 | 生产目录请求成功，包含 mimo 与 ling；目录可见不代表每个模型均验收通过 | `ai-collab-backend/ai-real-acceptance-zen-catalog.log` |
| 完整规划 | 同一规划先用 mimo 得到 v2，再切 ling 重新生成得到 v4；两次骨架、详情和业务校验均完成 | `real-planning-mimo.png`、`real-planning-ling.png` |
| ling 局部修复 | 返回内容未通过 `PLAN_VALIDATION_FAILED`，v4 保留；没有接受无效补丁。现有错误未指明具体字段，不能臆断原因 | `real-planning-repair-failure-preserved.png` |
| mimo 局部修复 | 生成 v5；随后人工核对摘要保存 v6，确认在副本创建 1 里程碑与 1 任务 | `real-planning-confirmed.png`、数据库版本记录 |

同需求为“在2026年10月完成Java项目验收，建立一项可审核任务并发布。”约束为保留 Java、不引入其他 Agent 框架、1 里程碑、1 任务、4 小时、Local Owner、验收报告；日期 10 月 3 日至 31 日。规划 ID 为 `a6e488e0-8696-4e34-9675-2750b80df68c`。**后续字段核查纠正：v2（mimo）、v4（ling）、v5 均将 `suggestedAssigneeId` 正确设为 Local Owner 的 `9c4cd312-0e99-497a-8e3b-e78f98692df0`，正式 `assigneeId` 按设计为空。此前仅据“未分配”认定模型未落实负责人不成立；实际是未人工采纳建议，直接确认生成了未分配任务。**

本轮修正封装后的成功请求不能证明首次 403 的确切原因。旧响应正文缺失，仍只能确认旧请求被拒绝。本轮没有遇到新的 403/429；没有通过等时点或重复拒绝请求取得成功。

## 实际 Agent 与回答质量

- 首次只读查询运行 `48ea061d-cd4b-4365-9e8b-94b6e26cecef` 成功执行一次真实原生 `list_tasks`，未创建或批准提案，但最终回答错误声称工具数据缺少状态。持久化工具输出实际包含 `status=TODO`，故**首次回答质量未通过**。截图 `real-agent-query.png`。
- 核查工具结果重建及生产请求序列化均保留完整 JSON。新会话明确要求读取 `data.items` 的 `title/status` 后，运行 `7d046e3e-e774-4457-92cb-c30106841a71` 正确列出两条任务的标题与 TODO。截图 `real-agent-query-retry.png`。这支持工具结果传递可用，但不能消除模型解释结果的不稳定性。
- 真实查询和原生工具协议通过，不等于真实“提案修订→审批”已通过。本轮未追加真实写提案验收；该链路沿用前轮模拟业务验收证据。

## 真实文档、RAG 与持久化

- 实际上传本项目 `docs/architecture.md`（10.7 KB），文档 ID `12d7e424-0573-4c6e-9641-797ab9a03773`。初次处理因本地 Ollama 服务不可连接而失败；恢复本地服务后通过页面重试，得到 32 个可检索分块、1024 维向量。没有用模拟向量替代。
- 模型 `qwen3-embedding:0.6b`；候选索引通过显式激活，活动代 ID `be9b4a5a-a7fe-4a32-bda4-95bb03b80140`。截图 `real-ollama-index-active.png`。
- 首问实际 Zen 回答模块化单体、Spring Boot、Vue SPA、REST 与 SSE，引用原文并标记使用边界推断，约 7.75 秒。
- 同会话追问事务/权限所在层及 Controller 是否直接访问 Mapper，第一次在 180 秒超时，未保存虚构回答；应用重启后一次受限重试成功，约 13.63 秒。回答 Application Service 与“不直接访问 Mapper”，引用与文档一致。截图 `real-knowledge-followup.png`。两轮匹配不能视为全面资料质量评测。
- 通过真实下载入口分别在重启前、MinIO 重启后、应用重启后下载。三份下载与源文件的 SHA-256 均为 `2BC88FB68DC226F8BC36E295933F174E61D2D6DE9EE86466131A26D913B2F307`。应用重启后文档仍可检索，首问和引用历史仍可恢复。截图 `real-document-persistent.png`。
- 以上证明本次专用命名卷的重启持久化；未验证主机灾难恢复、备份恢复或正式环境存储配置。

## 本轮修复与回归

真实局部修复先失败、后成功时，发现旧错误元数据未清除。`TaskPlanRepository.appendVersion` 现在在成功追加版本时清空旧错误；已有生产 Bean 集成测试补充失败后成功修复断言。浏览器人工保存 v6 后旧错误提示消失，随后确认创建任务成功。前端补齐业务校验失败提示，明确草稿保留，增加对应回归用例。

最终回归：后端 **890 项，885 通过、5 跳过、失败/错误 0**；前端 **126 项通过**；后端打包和前端生产构建成功。增加的默认跳过项为本轮显式启用的真实宿主，不是普通测试失败。原有四个受控验收/真实探测仍默认跳过。

证据：`ai-collab-backend/ai-real-acceptance-regression.log`、`ai-real-acceptance-package.log`，完整 Surefire XML 快照 `ai-collab-backend/target/real-acceptance-final-surefire/`；前端 `ai-real-acceptance-frontend-tests.log` 与 `ai-real-acceptance-frontend-build.log`。真实宿主重启后的单项测试通过，独立于默认全量回归。

已关闭验收浏览器、前后端验收进程，停止专用 MinIO、Redis 与数据库副本容器，保留副本、对象卷和日志供复核。原业务库保持停止。现有本地 Ollama 服务继续保留；未按进程名批量终止用户服务。`git diff --check` 通过；已有未提交修改保留，未创建提交或发布。

## 验收结论及剩余项

真实协议、两个指定模型的完整规划、真实文档问答样例及对象重启持久化可以关闭对应待验证项。仍保留：ling 局部修复失败、首次 Agent 事实解释错误、建议负责人未人工采纳、一次真实追问超时；需要扩大真实资料与模型样例、真实提案审批链路及独立代码审查。正式环境发布和迁移尚未执行。

截图目录：`docs/acceptance-evidence/2026-10-03/`。真实宿主日志：`ai-collab-backend/ai-real-acceptance-host.log` 与 `ai-real-acceptance-host-restarted.log`。这些记录与之前模拟日志严格区分。
