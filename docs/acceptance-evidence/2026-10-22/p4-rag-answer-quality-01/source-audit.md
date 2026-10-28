# 接入及业务链路审计（执行前）

基线：dev / origin/dev / 远端 dev = `e36c14ff49213c7459901c6262a40a512df0e0fe`；
main / origin/main / 远端 main = `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；工作区起始干净。

历史结论采用 10-19 README 最终复审、01B 勘误以及 02/03 的最终结论。
800/120 和候选选择 D/E 未获生产准入，保留 1200/150 与 Agent 策略 A。
本轮不改变 DocumentChunker、来源上限、阈值、排序、生产 Prompt 或任何迁移。

## 配置与凭据

GeminiModelAdapter 已实现 complete、completeStream 和原生 turn。通过官方
`GET /v1beta/models` 已核实 `models/gemini-3.1-flash-lite` 存在并支持 generateContent；
输入上限 1048576、输出上限 65536。本轮主动收紧单次输出至 2048。
列表成功不证明生成成功，不证明当前账号可用额度，不计入生成请求数。

实际本地默认开发库 user_ai_provider 中没有 GEMINI 行；用户并未记错此前接入：
P3-GEMINI-COMPAT-03 留有使用相同模型与指定桌面文件成功调用的历史记录。
P3-TEST-SAFETY-01 随后移除了自动桌面读取和全局代理属性修改，改为环境变量与显式目标双门控。
因此不能把“进程没有环境变量”或“开发库无模型行”解释为“从未接通过 Gemini”。

用户本轮明确授权读取指定凭据文件，并确认模型、24 次请求总预算和 2048 输出上限。
启动脚本仅在子进程环境中设置 GEMINI_API_KEY，测试源码不包含个人文件路径或自动文件读取。
凭据只加密保存到一次性测试库，报告不保存明文或密文。未搜索用户其他密钥文件。
不能仅按凭据前缀判定官方凭据是否有效；本轮官方模型列表已成功返回。

## 生产选择与路由

- ModelPurpose 分为 KNOWLEDGE_CHAT / AGENT / PLANNING。
- RoutingChatModelGateway 使用 callerUserId + purpose；用途绑定优先，否则个人默认配置。
  不按项目配置选 Chat，不从 application-local.yml 的旧 CHAT_MODEL 字段推定实际模型。
- 普通问答和流式问答都显式传入 KNOWLEDGE_CHAT 与 userId；调用同一生产路由。
- 原生 Agent 使用 AGENT，配置可与 KNOWLEDGE_CHAT 不同；检索工具本身不生成答案。
  若 Agent 调用 answer_project_question_with_sources，内部又经过知识问答用途。
  因此 UI 中一个模型的显示名本身不能证明两个业务用途最终选用了同一配置。
- 本轮隔离库为两个用途显式绑定同一 GEMINI 配置，以实际 completion.model 或 turn.model
  作为最终模型记录，保留请求用途和生产请求内容。

## 上下文、引用和保存

- 普通和流式服务先检查 ProjectAccessGuard、会话归属及限流，再检索 Top8，调用 build()
  保留最多 5 来源、8000 code points。build() 保持既有按 contentHash 全局去重语义。
- Agent search_project_knowledge 使用 Top20 与 buildDiverse()，默认 8、上限 12 来源，
  使用生产 CrossDocumentSourceSelector 策略 A；工具返回 quote 另有 600 字截断。
  不得用 Agent 诊断上下文替代实际进入模型的工具结果。
- build() 与 buildDiverse() 不是同一入口；跨文档同正文在普通问答中可能被去重。
  Q09 将区分缺少第二份证据与模型擅自确认两份文档一致。
- KnowledgeCitationValidator 在生成完成后解析 [S数字]，删除无效编号，无有效编号时降级
  为固定资料不足回答；它没有语义蕴含检查。合法编号不保证对应结论得到支持。
- 普通问答返回并保存 validated.answer；KnowledgePersistenceService 在事务内锁定归属会话，
  保存用户/助手消息、模型、用量和引用身份。持久 quote 最多 1000 code points。
- 流式服务先发送 token，完成时才校验、保存并发送 citations / done；错误发送 error。
  原始 token 文本和最终保存文本可能不同，必须逐题比较；本轮服务级 emitter 捕获不冒充
  浏览器验收、完整 HTTP 序列化或断线续传验收。
- 多轮只用最近历史解释代词，本轮 SOURCES 才是证据；Q11/Q12 同会话验证此路径。

## Provider 协议与门控

原生 endpoint 为 /v1beta/models/{model}:generateContent；streamGenerateContent?alt=sse 用于流式。
认证使用 x-goog-api-key 请求头。Gemini turn 保留 thoughtSignature，合并连续同角色消息，
将 tool result 映射为 user/functionResponse；运行级工具 ID 由 Coordinator 重定界。
MAX_TOKENS 映射截断；安全 finish reason 映射拒绝；流式必须有 STOP 与非空内容，
空流、缺失 finish、未知 finish 不算成功。HTTP 429/凭据问题/拒绝/超时/上游 5xx 分类不同。

既有 GeminiLiveTestSupport 严格要求 AI_GEMINI_LIVE_TESTS=true、显式 GeminiLiveAcceptanceTest
和环境 Key；503 最多 3 次，超时记 inconclusive。本轮不修改它，也不运行其整套 live 测试。
新增测试同样要求精确 opt-in、精确类名目标、环境 Key 及已批准模型；默认 verify 不访问 Gemini。
生产 JsonHttpModelClient 默认 post 仅 1 次，不在适配器外无限重试。

首次业务请求发生连接超时，保留独立证据；系统代理和历史验收均为本机 7897 端口。
续跑只通过 Maven 子进程 -Dhttps.proxyHost/-Dhttps.proxyPort 使用现有代理，不改全局设置，
不重跑任何已完成的模型答案，且将首次失败计入 24 次预算。
