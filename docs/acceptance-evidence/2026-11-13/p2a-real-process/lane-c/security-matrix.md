# P2-A 安全矩阵（Lane C 视图）

> 当前状态：夹具已准备；真实三进程矩阵 **NOT_RUN**。
> 任一 FAIL 必须保留原始错误，不得改写成 PASS。

| 编号 | 场景 | 当前状态 | 证据/说明 |
| --- | --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外拒绝 | NOT_RUN（真实） | `offline` 不含 Gateway；脚本真实模式含 metadata/outside 两条调用 |
| M-02 | DNS 校验后解析变化 | NOT_RUN | 由 Lane A `McpEndpointPolicyTest`/pinned DNS 覆盖，中心引用原始结果 |
| M-03 | 描述含 injection | FIXTURE_READY | `injected_description` 夹具可用；真实 Gateway 清洗由 Lane A 测试与中心集成验证 |
| M-04 | 结果含假 system/tool 指令 | NOT_RUN（真实） | 真实 `echo_text` 调用断言 warning、untrusted、redaction |
| M-05 | Schema 变化停用并审计 | NOT_RUN（真实） | 脚本切 `__control/schema?mode=changed`，真实管理员 discover + audit_log |
| M-06 | 未白名单/未绑定工具拒绝 | NOT_RUN（真实） | `write_probe` 不在目录，真实 `tools/call` 期望拒绝 |
| M-07 | 请求体伪造 project/user | NOT_RUN（真实） | 直连 Java Gateway 注入 `projectRef/userId`，期望 PARAM_INVALID |
| M-08 | 撤权后新调用/在途结果 | NOT_RUN（真实） | 慢调用中 DB 撤权，在途期望 TOOL_REVOKED/OUTCOME_UNKNOWN |
| M-09 | 429/timeout/5xx/partial | NOT_RUN（真实） | 429/500/206/慢调用夹具已备；partial 依赖 Lane A 206 映射 |
| M-10 | 超大/二进制/路径 | NOT_RUN（真实） | `oversized_echo` + `unsafe_payload`，真实调用断言截断/拒绝与脱敏 |
| M-11 | 双端 tools/list/hash | NOT_RUN（真实） | 真实 Host/Java 原始 JSON 逐项与 catalogVersion 比较 |
| M-12 | Gateway 关闭回退旧 Provider | NOT_RUN | Lane A 配置测试 + 中心 default-off 启动记录 |

## 离线夹具状态

| 夹具 | 结果 |
| --- | --- |
| HTTPS TLS handshake + tools/list | PASS |
| HTTP 429 | PASS |
| HTTP 500 | PASS |
| HTTP 206 | PASS |
| unsafe_payload 原始二进制/路径字段 | PASS（只证明夹具产生，不证明 Gateway 清洗） |
| schema variant 切换 | PASS（只证明控制面可切换） |
