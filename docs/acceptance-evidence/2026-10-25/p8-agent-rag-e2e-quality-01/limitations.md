# 结论边界

- Embedding 是本轮真实本地模型；Chat 是 Mockito 确定性脚本。实际 Runtime、SQL、工具注册/执行/清洗、消息 composer、数据库 guard 与引用持久化执行。上下文 assembler、事件发送、审批和记忆 collaborator 被替换，前两者避免接入真实 worker/SSE，后两者不属于只读搜索验收。没有浏览器、公开 HTTP/API 认证入口或真实 Worker 后台调度验收。
- 模型脚本逐字复制实际 quote，无法证明真实 Correctness、Completeness、Faithfulness、Citation Support、自主拒答、提示注入抵抗能力。Q13 工具内容包含恶意文本，脚本复制它不构成真实抗注入通过。
- Q11/Q12 使用同一 session、生产消息历史组装，Q12 的用户范围由测试显式继承。辅助摘要为明确脚本 EMPTY 降级；没有验证真实摘要语义与自主指代改写。
- 父子运行、工具失败由现有 AgentResearchQualityPostgresTest 复测覆盖；该测试仍注入检索候选及 Mock guard。P8 的真实向量新链路只覆盖直接搜索；真实向量父子完整贯通 NOT_EXECUTED。
- 冻结 P6 27 个 marker 不等于 questions.json 所有语义约束。Q03 另逐短语核对或关系、不可恢复异常、暂停与最近稳定版。完整的生成评分仍必须由未来授权的真实生成和人工逐项审查完成。
- R1 候选补充是既有诊断实验：QA 使用 legacy buildWithDiagnostics，而非当前 balanced 入口；不能据它断言 R1+S2 联合收益。R1b 测试侧 SQL 扫描项目所有 READY 同代际分块，至多保留4项补充；不能称为生产级固定 I/O 上界。未把它接入 Agent 实际链路。
- 只有逻辑 Embedding 调用数可核查。没有 HTTP 级请求/重试、缓存、Token 计量，均 UNKNOWN；脚本 Token 为 null，Runtime 的估算不作为真实用量。单次延迟不做 P95 或性能提升推断。
- origin/dev 是开工时本地跟踪引用，未访问远端核验最新提交。未执行生产部署、模型配置变更、生产算法修改或用户数据写入；测试仅写一次性 Testcontainers DB。
- 未执行任何外部 Chat 请求；历史 Gemini/Kilo/魔搭账本仅为历史授权边界，不继承本轮授权。

只读配置补核：local-embedding-configuration.json 与 local-index-summary.json 显示应用使用相同 Ollama 模型/1024维，存量29块均匹配应用指纹371934...，generation_id为null；P8临时索引是49块、独立ACTIVE generation、指纹20f29e...。生产配置服务的指纹把endpoint路径纳入模型参数字符串，冻结P6夹具保留旧provider/model/dimension计算方式；这是明确的配置命名空间差异，不据此宣称应用索引漂移或损坏。实际业务库检索未执行，本轮质量结论适用于冻结语料上的当前生产实现。只读核对未读取任何凭据或正文，没有改应用数据。
