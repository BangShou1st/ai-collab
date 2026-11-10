# 03｜Query Router 日志与安全审查

> 分类：**真实修复**（代码变更，1 处日志出口 + 1 处 reason 泄漏面收敛）

## 1. 审查范围与结论

逐一日志出口审查（`retrieval/query/` 包 + 两个 QA 服务 + Agent 工具）：

| 位置 | 修复前 | 处置 |
| --- | --- | --- |
| `QueryRouter.route()` catch 分支 | `log.warn("Query routing failed for query '{}', ...", rawQuery, e.getMessage())` —— **原始用户查询全文 + 异常消息**进入日志 | **修复**：查询掩码（前 6 字符 + `***`）+ 仅记录 `e.getClass().getSimpleName()` |
| `QueryRouteDecision.reason()`（异常兜底） | `"Fallback to HYBRID due to routing error: " + e.getMessage()` —— 异常消息可能内嵌输入片段（实测：`MetadataFilterParser` 对非法过滤字段抛出 `IllegalArgumentException("Disallowed metadata filter field: '<用户输入key>'...")`，key 为用户原文） | **修复**：只拼异常类名；reason 随执行器日志输出时另有掩码 |
| `StrategyAwareRetrievalExecutor` `RAG_STRATEGY_ROUTE` | 已掩码（`queryMasked`/`reason` 掩码） | 已有代码本来正确，零改动（新增 `fallbackCause` 结构化代码，不含自由文本） |
| `MetadataFilterParser` / `MultiQueryDecomposer` | 均无原始查询输出（decomposer 仅 `log.debug` 计数、`log.warn` 异常消息） | 已有代码本来正确，零改动 |
| `KnowledgeSearchAgentTool` / QA 服务 | 检索路径无原始查询日志 | 已有代码本来正确，零改动 |

## 2. 修复内容

`QueryRouter`：

- 新增 `MASKED_LOG_CHARS=6` 与 `mask()`（口径与 `StrategyAwareRetrievalExecutor.MASKED_QUERY_CHARS` 一致）；
- 异常兜底日志改为：
  ```
  Query routing failed, safely falling back to HYBRID: queryMasked={} queryLen={} errorType={}
  ```
- 兜底 reason 不再携带异常消息，只带异常类型（`"Fallback to HYBRID due to routing error: RuntimeException"`）。

保留的可定位性：`queryLen` + 异常类型 + 掩码前缀足以关联与归因；完整查询仍随 `StructuredQuery` 进入检索链路（业务必需），仅不落日志。

## 3. 结构化诊断字段（现有，未引入新框架）

`RAG_STRATEGY_ROUTE` 日志已覆盖任务 5.2 建议项：策略名、掩码 reason、查询长度、是否回退（`fallbackUsed`）、候选数、阶段耗时（`latencyMs`）、本次新增的回退原因代码（`fallbackCause`）。请求关联 ID 由上层日志 MDC（requestId）承载，路由层不重复发明。

## 4. 测试证据

- `QueryRouterTest.shouldFallbackSafelyToHybridOnError`（更新）：兜底 reason 含 `Fallback to HYBRID`、不含异常消息文本；
- `QueryRouterStrategyMappingTest.routingError_reasonContainsNoExceptionMessage`（新增）：构造解析器抛出 `"leaked-input fragment"`，断言 reason `doesNotContain("leaked-input")` 且包含异常类名；
- 日志输出本身以 `RAG_STRATEGY_ROUTE` 实测输出佐证（测试运行日志）：`queryMasked=语义问题*** queryLen=4`，无原文全文。

## 5. 未验证/边界说明

- 日志正则审计仅覆盖 `retrieval/query` 包与知识检索链路；其他模块（planning/agent 对话等）的日志脱敏不在本任务范围；
- 异常消息本身（`e.getMessage()`）不再进入日志与 reason，但 `MetadataFilterParser` 抛出的异常消息仍含用户输入 key —— 该异常向上传播的路径已被 `QueryRouter.route()` 的 catch 全量拦截（parse 仅在 route 内调用），主源码无其他 `filterParser.parse` 调用点（grep 验证）。
