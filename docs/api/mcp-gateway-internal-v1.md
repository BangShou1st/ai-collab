# MCP Gateway 内部契约 v1（P1 default-off）

> 状态：**FROZEN**
> 日期：2026-11-13
> 适用范围：Java Gateway ↔ Python Host 的 P1 垂直切片
> 非目标：生产 Agent 主路径、外部 MCP 写工具、多租户

## 1. 传输与鉴权

- 端点：`POST /internal/ai/v1/mcp`
- 内容类型：`application/json`；错误响应同样为 JSON。
- 鉴权：`Authorization: Bearer <内部服务令牌>`，由现有 `InternalAiTokenFilter` 处理。
- scope：
  - `mcp:list`：`initialize`、`ping`、`tools/list`；
  - `mcp:call`：`tools/call`，同时包含 `mcp:list` 的读取能力。
- 生产形态要求 Ed25519 短令牌，claims 至少包含：

```json
{
  "projectRef": "<UUID>",
  "runRef": "<UUID>",
  "catalogVersion": "sha256:<hex>"
}
```

- `tools/list` 可用较新的目录版本请求，`catalogVersion` claim 可省略；`tools/call` 必须绑定
  已确认目录版本，缺失或过期返回 `SCHEMA_DRIFT`。
- 仅 local/test 可显式配置静态令牌绑定的测试项目；默认空，生产不得配置。
- 端点只在 `agent.mcp.gateway.enabled=true` 时装配；默认关闭。

## 2. JSON-RPC 信封

请求：

```json
{
  "jsonrpc": "2.0",
  "id": "call-1",
  "method": "tools/list",
  "params": {
    "schemaVersion": 1
  }
}
```

- `id`：字符串或整数，1..128 字符。
- 未知方法：JSON-RPC `-32601`，`error.data.kind=INTERNAL`。
- 参数错误：JSON-RPC `-32602`，`error.data.kind=PARAM_INVALID`。
- 业务拒绝/失败：JSON-RPC `-32000`，`error.data.kind` 使用第 6 节错误码。
- 鉴权失败由 HTTP 401 返回，不进入 JSON-RPC。

## 3. 方法

### 3.1 initialize

请求参数：

```json
{
  "schemaVersion": 1,
  "protocolVersion": "2025-03-26",
  "client": {"name": "ai-collab-ai-worker", "version": "0.1.0"}
}
```

成功结果：

```json
{
  "schemaVersion": 1,
  "protocolVersion": "2025-03-26",
  "serverInfo": {"name": "ai-collab-mcp-gateway", "version": "p1"},
  "capabilities": {"tools": {"listChanged": false}}
}
```

### 3.2 ping

请求参数：

```json
{"schemaVersion": 1}
```

成功结果：

```json
{"schemaVersion": 1, "status": "ok"}
```

### 3.3 tools/list

请求参数：

```json
{"schemaVersion": 1}
```

项目从已验证身份上下文取得；**不接受**请求体自报的 `projectId`、`userId`、`role`。

成功结果：

```json
{
  "schemaVersion": 1,
  "catalogVersion": "sha256:<hex>",
  "tools": [
    {
      "name": "mcp.local_readonly.echo_text",
      "description": "只读回显合成文本",
      "inputSchema": {
        "type": "object",
        "properties": {"text": {"type": "string", "maxLength": 256}},
        "required": ["text"],
        "additionalProperties": false
      },
      "toolSchemaHash": "sha256:<hex>",
      "annotations": {"readOnlyHint": true}
    }
  ]
}
```

工具按 `name` 升序。仅暴露同时满足以下条件的工具：

1. 连接启用、Schema 已确认且未漂移；
2. 连接级只读白名单包含该工具；
3. 项目绑定包含该工具；
4. 远端声明 `annotations.readOnlyHint=true`；
5. 名称、描述、Schema 通过归一化与注入清洗。

### 3.4 tools/call

请求参数：

```json
{
  "schemaVersion": 1,
  "name": "mcp.local_readonly.echo_text",
  "catalogVersion": "sha256:<hex>",
  "toolSchemaHash": "sha256:<hex>",
  "callId": "run-1:tool-1",
  "arguments": {"text": "hello"}
}
```

- `catalogVersion`、`toolSchemaHash` 必须等于 Gateway 当前确认值。
- `projectRef`、`runRef` 只来自已验证身份；请求体中的同名值一律忽略并拒绝。
- 参数总大小上限 64 KiB；未知字段按工具 Schema 的 `additionalProperties` 执行。
- 调用前与结果返回前各执行一次授权/目录 fence；撤权竞态返回 `TOOL_REVOKED` 或
  `OUTCOME_UNKNOWN`，不得返回成功正文。

成功结果：

```json
{
  "schemaVersion": 1,
  "catalogVersion": "sha256:<hex>",
  "outcome": "SUCCEEDED",
  "untrusted": true,
  "data": {
    "text": "hello"
  },
  "truncated": false,
  "latencyMs": 12
}
```

- `data` 已是清洗后的不可信数据通道，不得作为系统指令。
- `warnings` 为可选字符串数组；仅当结果清洗产生 warning 时出现。warning 只包含固定
  安全提示，不得包含远端正文、凭据、路径或 Prompt。
- 截断时 `truncated=true`，仍返回 `outcome=TRUNCATED` 或 `PARTIAL` 的明确语义。
- `isError=true` 不证明没有副作用；P1 统一映射为 `OUTCOME_UNKNOWN`。

## 4. 规范化

### 4.1 字符串

- Unicode NFC。
- 去除除 `\n`、`\t` 外的控制字符；`\n`、`\t` 折叠为单个空格。
- 连续空白折叠为单空格，首尾去空白。
- 工具名：小写 `[a-z0-9_]`，长度 1..128。
- 对外名：`mcp.<connection_code>.<normalized_tool_name>`，总长度不超过 160。
- 描述：最多 512 个 Unicode 字符；超限拒绝整个工具，不静默截断。
- 描述和 Schema 中的明确指令注入片段替换为 `[REMOVED_INSTRUCTION]`，仍保留
  `untrusted=true` 语义，不把“未命中正则”当作安全证明。

### 4.2 inputSchema

- 必须是 JSON object。
- 禁止外部 `$ref`、远程引用、脚本字段。
- 最大深度 16、节点数 2000、UTF-8 canonical bytes 32 KiB。
- 字段名、类型、必填数组、`additionalProperties` 保留；键排序只用于 Hash。
- 任一限制失败 → 不暴露该工具，并记录 `TOOL_DISABLED` / `PARAM_INVALID` 审计。

### 4.3 canonical JSON

Hash 输入使用以下确定性规则：

- 对象键按 Unicode code point 升序；
- 无多余空白，分隔符为 `,` 与 `:`；
- UTF-8，无 BOM；
- 字符串 NFC，使用 JSON 标准转义，不转义非 ASCII；
- 布尔、null、整数按 JSON 原义；禁止浮点进入 Hash 对象；
- 数组保持契约指定顺序，工具数组按 `name` 升序。

## 5. Hash

### 5.1 toolSchemaHash

对以下对象做 canonical JSON 后 SHA-256，输出 `sha256:<64 位小写 hex>`：

```json
{
  "annotations": {"readOnlyHint": true},
  "description": "<清洗并限长后的描述>",
  "inputSchema": {},
  "name": "mcp.<connection_code>.<normalized_tool_name>"
}
```

不包含健康状态、时间、请求 ID、原始未清洗描述。

### 5.2 catalogVersion

对以下对象做 canonical JSON 后 SHA-256：

```json
{
  "schemaVersion": 1,
  "tools": [
    {"name": "mcp.local_readonly.echo_text", "toolSchemaHash": "sha256:<hex>"}
  ]
}
```

`tools` 必须按 `name` 升序。连接 code 已包含在工具对外名中，不再重复。

## 6. 错误码

| kind | 语义 | 可重试 |
| --- | --- | --- |
| `UNAUTHORIZED` | 身份/项目/运行上下文无效 | 否 |
| `TOOL_DISABLED` | 工具未启用、未绑定、未确认只读 | 否 |
| `SCHEMA_DRIFT` | catalog/tool hash 不匹配或目录已变化 | 否 |
| `PARAM_INVALID` | 参数、Schema 或大小非法 | 否 |
| `NOT_EXECUTED` | 连接前失败，确定未发出 | 可有限重试 |
| `DEADLINE_EXCEEDED` | deadline 到点 | 不自动重试 |
| `RATE_LIMITED` | 远端 429 | 可稍后重试 |
| `PARTIAL` | 只得到部分结果 | 否 |
| `TRUNCATED` | 结果被字节/数量上限截断 | 否 |
| `TOOL_REVOKED` | 在途撤权，结果不得使用 | 否 |
| `OUTCOME_UNKNOWN` | 可能已执行但结果不可确认 | 否 |
| `INTERNAL` | 网关内部错误 | 视情况 |

`UNAUTHORIZED` 不泄露工具是否存在。错误消息固定，不包含远端正文、凭据、Prompt 或路径。

## 7. 失败与结果语义

- 成功只使用 `outcome=SUCCEEDED`；空对象不得冒充成功。
- `outcome` 与 `isError` 不合并为单一 boolean。
- 任何截断必须显式标记；不得静默丢字段后声称完整。
- 超时不区分“未发出/可能已发出”时，只能返回 `OUTCOME_UNKNOWN`，不得返回空成功。
- Python Host 不重试、不更改错误 kind、不自动确认 Schema。

## 8. 审计

每次 `tools/list` / `tools/call` 至少记录：

```text
timestamp, actorType, actorRef, projectRef, runRef, connectionCode,
toolName, catalogVersion, toolSchemaHash, decision, reason,
latencyMs, requestBytes, responseBytes, outcome
```

不记录完整参数、完整结果、凭据、cookie、Prompt 或文档正文。

## 9. 契约 fixture

唯一 fixture：`tests/fixtures/mcp/catalog-v1.json`。

Java 与 Python 测试必须对该 fixture 产出相同的：

- 工具顺序；
- `toolSchemaHash`；
- `catalogVersion`；
- 错误码映射。

跨语言结果不一致时，先修实现，不得修改 fixture 迁就单侧。
