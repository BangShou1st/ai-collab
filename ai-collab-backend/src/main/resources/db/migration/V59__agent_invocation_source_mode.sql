-- V59: 记录每次工具调用所属模型轮次的可信执行模式。
-- 恢复待处理调用时按来源轮次的模式做 Legacy 写工具校验，
-- 不因运行中途换模型追溯否定原生轮次的提案，也不放行 Legacy 轮次的越权写调用。
ALTER TABLE agent_tool_invocation ADD COLUMN source_mode VARCHAR(24);
