# 本地应用配置只读补核

2026-10-25 北京时间约 22:43，在运行完整隔离测试时，只读查询本地 Docker PostgreSQL 的以下非敏感字段。通过容器内已有数据库环境变量选择连接身份，未输出、读取文件或持久化任何密码，未读取 `.env`，未查询文档正文或用户资料。两条 SQL 均为 SELECT，没有模型调用、配置修改或用户数据写入。

```sql
SELECT json_agg(t) FROM (
  SELECT provider, model_name, dimensions, fingerprint, generation_id, enabled
  FROM system_embedding_config
) t;

SELECT json_agg(t) FROM (
  SELECT embedding_provider, embedding_model, embedding_dimension,
         embedding_fingerprint, generation_id, count(*) AS chunk_count
  FROM document_chunk
  GROUP BY embedding_provider, embedding_model, embedding_dimension,
           embedding_fingerprint, generation_id
) t;
```

实际输出为 [local-embedding-configuration.json](local-embedding-configuration.json) 和 [local-index-summary.json](local-index-summary.json)。存量29块全部匹配当前应用配置指纹，generation 为 null；本轮隔离49块拥有独立 ACTIVE generation。配置服务 `SystemEmbeddingService.fingerprint()` 把 baseUrl/apiPath 纳入模型指纹字符串，P6 冻结测试只按 provider/model/dimension 计算，故二者指纹不同有明确源码解释，不能据此认定应用索引损坏。本轮没有重建或变更应用索引。

测试装配中的 AgentRepository / Recorder / Coordinator 由测试手动构造，实际执行 SQL 与运行逻辑，但没有完整复现 Spring 代理事务、HTTP 控制器、真实 worker、SSE 发送及租约后台续期。该验证证明限定样本内身份与结果一致性，不代表故障注入下事务原子性已验收。
