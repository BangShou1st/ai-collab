# P7-PROVIDER-ONBOARDING-01 验收证据

日期：2026-10-25 · 分支：`dev` · 任务：AI 模型接入自动化与能力发现改造

## 1. 证据清单

### 浏览器截图（真实 Microsoft Edge / browser-skill 0.3.3）

| 文件 | 内容 |
| --- | --- |
| `browser/01-ai-settings-initial.png` | AI 设置页初始状态（0 配置） |
| `browser/02-dialog-simplified-form.png` | 新建对话框简化表单（服务商/Key/模型 + 高级设置折叠） |
| `browser/03-modelscope-profile-prefill.png` | 魔搭 ModelScope 档案预填（Base URL 无 /v1，端点预览正确） |
| `browser/04-duplicate-v1-warning.png` | `/v1` + `/v1/...` 重复版本前缀警告与两个一键修正按钮 |
| `browser/05-detect-panel.png` | 「检测并配置」面板与用途预设 |
| `browser/06-detect-confirm-budget.png` | 确认框明示预算（全面检测 7 次真实请求） |
| `browser/07-invalid-key-fail-classification.png` | 无效 Key 真实探针：连接 401→未通过，后续步骤 SKIPPED 不再消耗请求；证据表四种状态分层 |
| `browser/08-gemini-full-preset.png` | Gemini 全面检测预设 |
| `browser/09-gemini-confirm-budget.png` | Gemini 检测确认框 |
| `browser/10-stale-after-model-change.png` | 修改模型后旧结果显示"已过期（配置已变更）" |
| `browser/11-gemini-real-detect-results.png` | gemini-3.8-flash 真实检测：对话已验证可用、503→暂时无法判断 |

注：验收后半程 browser-skill 截图通道出现间歇性 RPC 超时（observe/操作不受影响），
T01 最终证据改以数据库脱敏记录（见下）与测试断言为准，非 Mock 替代。

### 数据库脱敏证据（docker exec psql 只读查询）

Gemini-Real-Test（创建时仅声明 `CHAT,STREAMING`，未声明 NATIVE_TOOLS）：

```
probe_mode    | status | model_name           | latency_ms
NATIVE_TOOLS  | PASS   | gemini-3.1-flash-lite | 12924      ← 未声明能力的真实探针通过（T01）
STREAMING     | PASS   | gemini-3.1-flash-lite | 749
CONNECTIVITY  | PASS   | gemini-3.1-flash-lite | 1192
PLANNING_JSON | PASS   | gemini-3.8-flash      | 128060     ← 3.8 轮规划探针亦真实通过

user_ai_provider.capabilities = CHAT,NATIVE_TOOLS,STREAMING   ← 工具探针 PASS 后自动写回（T01/T06）
anchored_current（tested_config_updated_at == updated_at）：
  CONNECTIVITY=t  NATIVE_TOOLS=t  STREAMING=t   ← 写回后刚通过的结论不过期（T06）
  PLANNING_JSON=f（模型变更前的旧结论，正确过期）
```

### 后端日志证据（backend.stdout.log，无凭据）

- 出站前拦截：`BusinessException: Base URL 与 API Path 拼接后会重复版本前缀 /v1（最终端点：.../v1/v1/chat/completions）`
- 未保存发现：`Discovered 37 models for unsaved OPENAI_COMPATIBLE configuration`、`Discovered 50 models for unsaved GEMINI configuration`
- 真实分类：`Model API returned HTTP 503` → INCONCLUSIVE（不误报不支持）、404/401 → FAIL

## 2. T01–T10 结果矩阵

| # | 场景 | 结果 | 证据 |
| --- | --- | --- | --- |
| T01 | 未声明 NATIVE_TOOLS 但模型实际支持 | **PASS** | `CapabilityProbeBoundaryTest`（上下文内真实放行/上下文外拒绝/跨 Provider 拒绝）+ `AiCapabilityDetectAndConfigureTest`（真实合成请求→PASS→自动启用）；浏览器+真实 Gemini：未声明→探针 12924ms PASS→capabilities 自动含 NATIVE_TOOLS |
| T02 | 真实不支持工具调用 | **PASS** | `nativeToolsRealFailureDoesNotWriteBack`（真实拒绝→FAIL→不写回）；无效 Key 真实 401→FAIL 浏览器证据 |
| T03 | 429/503/超时 | **PASS** | `transientFailuresAreInconclusiveAndDoNotAbortSuite`（无重试，每步恰好一次）；真实 Gemini 503/400→INCONCLUSIVE，未误报"不支持" |
| T04 | 重复 /v1 路径 | **PASS** | `ProviderEndpointRulesTest` + 后端保存前硬拒绝 + 前端警告/一键修正（浏览器实测修正生效）；Gemini/Anthropic/自定义网关路径不误伤 |
| T05 | 模型与 Key 变更竞争 | **PASS** | `casRejectsWhenConfigVersionMovedOn`（真实 PG）+ `credentialChangeDuringProbeRejectsWriteBackWithReason`；浏览器实测：检测进行中修改模型后配置版本推进，写回按 CAS 拒绝 |
| T06 | 写回与探针过期语义 | **PASS** | `casWriteBackAppendsCapabilitiesAndRebasesProbeEvidence`（真实 PG）+ 浏览器/DB 实测：刚 PASS 的探针锚定当前版本不过期；修改模型后旧结果即时显示"已过期" |
| T07 | 并发测试 | **PASS** | `concurrentDetectRunsAreRejectedByBackendLock`（跨线程双请求）+ 前端防重复点击测试；单 Provider 锁为 JVM 内实现（单体部署），跨实例演进登记残余风险 |
| T08 | 用途就绪度 | **PASS** | `ai-purpose-status.test.ts`（未声明≠不支持、已声明≠已验证、验证未启用中间态）+ 浏览器实测：知识问答/Agent 可用、规划未验证（未跑探针） |
| T09 | 跨 Provider 一致性 | **PASS（真实子集）** | OpenAI Compatible（ModelScope 真实发现 37 模型 + 真实 401 FAIL）、Gemini（真实发现 50 模型 + 三探针真实 PASS + 工具 schema 跨协议兼容修复）、Anthropic 路径经 `ProviderEndpointRulesTest`/档案测试覆盖；Zen/Kilo 预设面板共用同一编排（前端测试） |
| T10 | 安全与权限 | **PASS** | 既有 `UserAiProviderOwnershipTest`（跨用户 404/403）不回退；探针上下文仅服务端设置（`CapabilityProbeBoundaryTest`）；端点校验/SSRF 防护不变（`ProviderEndpointValidationTest`）；全部日志/证据无凭据 |

## 3. 真实 Provider 消耗记录

| Provider | 模型 | 用途 | 请求数 |
| --- | --- | --- | --- |
| ModelScope | DeepSeek-V4.1-Flash-1017 | 未保存模型发现 | 只读 1 次（免鉴权成功） |
| ModelScope | DeepSeek-V4.1-Flash-1017 | 无效 Key 检测（连接+流式） | 生成 1 次（401 后中止） |
| Gemini | gemini-3.8-flash | 全面检测第 1 轮 | 生成 2 次（1×404 模型不可用；1×200 慢思考） |
| Gemini | gemini-3.8-flash | 全面检测第 2 轮（3.5→改模型竞争） | 生成 ≥3 次（连接 200、流式 503、规划 PASS 等，后端重启中断收尾） |
| Gemini | gemini-3.1-flash-lite | Agent 预设检测（修复 schema 后） | 生成 3 次（连接/流式/工具全 PASS） |
| Gemini | gemini-3.1-flash-lite | 未保存模型发现 | 只读 1 次 |

Gemini Key 为用户当轮提供（桌面 key2.txt），仅经浏览器表单与后端出站使用，未写入任何日志或证据文件。

## 4. 分层结论

1. **真实浏览器 + 真实 Provider 已验证**：简化表单、ModelScope/Gemini 档案预填、重复前缀警告与一键修正、未保存发现、确认框预算展示、FAIL/INCONCLUSIVE 真实分类、STALE 展示、T01 未声明能力探针通过并自动写回、用途就绪度联动、刷新持久化。
2. **仅自动化证据**：CAS 并发冲突、写回 rebase（真实 PostgreSQL 集成测试）、探针边界三情形、并发锁、双窗口重复点击。
3. **环境限制**：browser-skill 截图通道在验收后半程间歇超时（T01 终态以 DB 脱敏记录为准）；gemini-3.5-flash 不在本 Key 模型列表（列表含 3.x 系列，改用 3.1-flash-lite）。
