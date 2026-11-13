# Lane A 原始证据说明

- `real-process.json`：最终真实进程探针，`17/17 PASS`，`TESTED(REAL_PROCESS)`；
  含成功、过期、篡改签名、错误 scope 四类真实 HTTP 结果。
- `real-process-16-before-expiry.json`：补测过期用例前的 `16/16 PASS` 记录。
  使用本地 Spring Boot Gateway、两个合成 TLS MCP 进程、Python 测试 Host 和
  临时数据库；结束后数据库已删除。
- `attempt1-relative-workdir-failure.json`：首轮 harness 使用相对
  `--work-dir`，Java 从 `ai-collab-backend` 工作目录解析相对 truststore 路径失败，
  两个真实调用返回 `OUTCOME_UNKNOWN`。该失败不是业务调用链回归，保留用于说明
  运行条件；改用绝对 work-dir 后复跑通过。
- `java-token-gateway-tests.txt`：Java 定向测试 62/62 PASS。
- `java-token-claims-tests.txt`：Java Issuer/Catalog/Static Binding 定向测试
  22/22 PASS。

本目录不包含令牌原文、私钥、生产证书或生产凭据。测试 token 使用
`ai-collab-ai-worker/tests/fixtures/auth/ed25519-test-fixture.json` 的测试专用密钥。
