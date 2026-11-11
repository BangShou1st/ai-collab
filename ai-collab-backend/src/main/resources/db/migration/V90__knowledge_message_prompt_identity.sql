-- V90: 知识问答链路记录 Prompt 身份（W2 / J2 PromptPack 接入）。
--
-- 目的：J2 的退出条件之一是「promptVersion 可在 Run/调用链中追溯」。Agent Run 侧复用既有
-- agent_run.model_configuration_snapshot（JSONB，V51）承载 Prompt 身份，无需迁移；知识问答链
-- 只有 knowledge_session → knowledge_message → knowledge_citation，没有任何 JSON/无类型列可以
-- 承载 Prompt 身份，而 knowledge_message 本来就已经逐条记录「这一轮换了哪个模型、多久、多少
-- token」——Prompt 身份属于同一组事实，因此在这里补两列，是最小且可查询的落点。
--
-- 口径：
--   * 两列均可空；只在「真的调用模型并产出助手回答」时写入，历史行不回填、不伪造；
--   * 未走模型的轮次（例如证据不足直接返回统一拒答文案）保持 NULL；
--   * 不加 CHECK 约束：Prompt 版本格式由 PromptPack 契约（YYYY-MM-DD.N）与应用层强制，
--     数据库不重复表达版本语法，避免 Prompt 版本规则演进时被迫改迁移。

ALTER TABLE knowledge_message ADD COLUMN prompt_key varchar(128);
ALTER TABLE knowledge_message ADD COLUMN prompt_version varchar(32);

COMMENT ON COLUMN knowledge_message.prompt_key IS
    '生成该条助手回答所用的 PromptPack key（如 knowledge.system）；NULL = 该轮未调用模型';
COMMENT ON COLUMN knowledge_message.prompt_version IS
    '生成该条助手回答所用的 PromptPack 版本（YYYY-MM-DD.N）；NULL = 该轮未调用模型';
