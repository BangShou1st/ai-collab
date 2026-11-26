# 厂商 HTTP/SSE 录制夹具（P1 契约 §6）

按 OpenAI Chat Completions、Anthropic Messages、Gemini generateContent 与 OpenAI 兼容端点
（Ollama / ModelBridge）的公开线格式录制，供 `tests/contract/test_provider_contract.py` 经
httpx2 `MockTransport` 注入，**不访问任何真实网络**。

每类 7 个文件：`text.json`、`text_stream.sse`、`tool.json`、`tool_stream.sse`、
`error_401.json`、`error_429.json`、`error_500.json`。`{{API_KEY}}` 在回放时替换成测试哨兵，
用来证明厂商在错误体里回显密钥时，异常与日志里也看不到它。
