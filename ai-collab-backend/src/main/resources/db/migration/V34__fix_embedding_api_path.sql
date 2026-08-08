-- 修复 V32 迁移错误：嵌入配置的 api_path 被错误复制为 /chat/completions
-- 嵌入 API 路径应为 /v1/embeddings
UPDATE project_embedding_config SET api_path = '/v1/embeddings' WHERE api_path = '/chat/completions';
