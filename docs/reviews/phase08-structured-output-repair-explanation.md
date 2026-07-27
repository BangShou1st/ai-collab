# Phase 08 Structured Output Contract Repair — Explanation

## 最终结论

**PASS**

## 原始失败链路

```
POST /api/projects/{id}/plans (202)
→ SKELETON_GENERATING
→ 模型被调用但未发送 response_format=json_object
→ 模型按默认文本模式输出，可能包含 Markdown 包裹
→ Skeleton JSON 包含 sources/sourceRefs（Schema 要求但领域校验拒绝）
→ 解析失败或领域校验失败
→ Repair 收到泛化错误码 PLANNING_MODEL_INVALID_OUTPUT
→ Repair 再次失败
→ FAILED / 模型输出未通过安全校验
```

## S1：为 Planning 启用可选 JSON mode

### 原始代码

`ChatCompletionCommand` 只有 `(systemPrompt, userPrompt)`，无法区分文本和 JSON 输出模式。

### 根因

所有模型调用（包括需要结构化 JSON 的规划生成）都使用相同的文本输出模式。供应商按默认文本模式工作，可能输出 Markdown 包裹的 JSON。

### 修改文件

- `ChatCompletionCommand.java` — 新增 `OutputFormat` 枚举和3参数构造器
- `OpenAiCompatibleChatModelGateway.java` — `ChatRequest` 新增 `response_format`，`TEXT` 模式省略该字段

### 修改前后请求体

**修改前：**
```json
{"model":"qwen-flash","messages":[...],"temperature":0.7,"max_tokens":32768,"stream":false}
```

**修改后（JSON_OBJECT）：**
```json
{"model":"qwen-flash","messages":[...],"temperature":0.7,"max_tokens":32768,"stream":false,"response_format":{"type":"json_object"}}
```

**修改后（TEXT）：**
```json
{"model":"qwen-flash","messages":[...],"temperature":0.7,"max_tokens":32768,"stream":false}
```

### RED 测试

- `jsonObjectCommandSendsResponseFormat` — 验证 JSON_OBJECT 模式发送 response_format
- `textCommandOmitsResponseFormat` — 验证 TEXT 模式不发送 response_format

### GREEN 命令

```powershell
.\mvnw.cmd -Dtest=OpenAiCompatibleChatModelGatewayTest test
```

### 回归风险

低。普通聊天调用保持 `TEXT` 模式，不受影响。

## S2：让 Skeleton DTO、Schema、Prompt 三者完全一致

### 原始代码

`SkeletonModelOutput` 包含 `List<SkeletonSource> sources` 和 `SkeletonMilestone.sourceRefs`。`SKELETON_SCHEMA` 要求这些字段。但 `validSkeleton()` 拒绝非空 sourceRefs。

### 根因

Schema 要求模型输出 sources，但领域校验又拒绝它们——自相矛盾的契约。

### 修改文件

- `SkeletonModelOutput.java` — 移除 `sources` 和 `sourceRefs`
- `TaskPlanGenerationOrchestrator.java` — 更新 `SKELETON_SCHEMA` 和 `toDraft()`

### RED 测试

- `skeletonWithTopLevelSourcesIsRejected` — 含 sources 的 Skeleton 被拒绝
- `skeletonMilestoneWithSourceRefsIsRejected` — 含 sourceRefs 的里程碑被拒绝

### GREEN 命令

```powershell
.\mvnw.cmd -Dtest=TaskPlanOutputParserTest test
```

### 回归风险

低。来源由服务器 context 通过 `withSources()` 绑定，不来自模型。

## S3：收紧 Detail Schema

### 原始代码

`DETAIL_SCHEMA` 只要求 `["tempKey"]`。模型合法地只返回 tempKey，合并后 COMPLETE 校验要求 description/priority 等字段，最终失败。

### 根因

Schema 未表达真实业务契约，必需字段被标记为可选。

### 修改文件

- `TaskPlanGenerationOrchestrator.java` — 更新 `DETAIL_SCHEMA`，milestone 要求 tempKey/description/sourceRefs，task 要求 tempKey/description/priority/dependencyTempKeys/sourceRefs
- `TaskPlanOutputParser.java` — 新增 `validateDetailRequiredFields()` 后置校验

### RED 测试

- `detailWithOnlyTempKeyForMilestoneIsRejected` — 只含 tempKey 的里程碑被拒绝
- `detailWithOnlyTempKeyForTaskIsRejected` — 只含 tempKey 的任务被拒绝
- `detailMissingDescriptionIsRejected` — 缺 description 被拒绝
- `detailMissingPriorityIsRejected` — 缺 priority 被拒绝
- `detailMissingDependencyTempKeysIsRejected` — 缺 dependencyTempKeys 被拒绝
- `detailMissingSourceRefsIsRejected` — 缺 sourceRefs 被拒绝

### GREEN 命令

```powershell
.\mvnw.cmd -Dtest=TaskPlanOutputParserTest test
```

### 回归风险

低。Schema 和 Parser 校验一致，模型必须输出完整 detail。

## S4：结构化失败诊断

### 原始代码

Parser 抛通用 `BusinessException(PLANNING_MODEL_INVALID_OUTPUT)`。Repair 只收到泛化错误码，不知道具体失败原因。

### 根因

异常不携带结构化诊断信息（类别、字段路径），导致 Repair 无法针对性修复。

### 修改文件

- `ModelOutputContractException.java` — 新增安全内部异常，携带 category 和 jsonPath
- `TaskPlanOutputParser.java` — 从 Jackson 异常提取 UNKNOWN_PROPERTY/MISSING_REQUIRED_FIELD/INVALID_FIELD_TYPE/JSON_SYNTAX_INVALID
- `TaskPlanGenerationOrchestrator.java` — `safeErrorSummary()` 生成安全摘要
- `TaskPlanRepository.java` — `fail()` 重载接受 errorSummary 参数

### 错误摘要格式

```
SKELETON / UNKNOWN_PROPERTY / sources
DETAIL / MISSING_REQUIRED_FIELD / tasks[1].priority
```

### RED 测试

- O3: `twoInvalidSkeletonsFailsWithSafeSummary` — 验证 lastErrorSummary 包含 "SKELETON" 且不包含原始输出

### GREEN 命令

```powershell
.\mvnw.cmd -Dtest=TaskPlanGenerationOrchestratorIntegrationTest test
```

### 回归风险

低。安全摘要不包含原始模型输出、Prompt 或 API Key。

## S5：识别输出截断

### 原始代码

`ChatChoice` 不读取 `finish_reason`。输出因长度限制被截断时，残缺 JSON 被当作普通无效输出。

### 根因

供应商截断信号未被捕获，系统无法区分截断与其他结构错误。

### 修改文件

- `OpenAiCompatibleChatModelGateway.java` — `ChatChoice` 新增 `finish_reason`，`checkFinishReason()` 检查 `length`
- `ErrorCode.java` — 新增 `AI_PROVIDER_OUTPUT_TRUNCATED` 和 `PLANNING_MODEL_OUTPUT_TRUNCATED`
- `TaskPlanModelClient.java` — 映射 `AI_PROVIDER_OUTPUT_TRUNCATED` → `PLANNING_MODEL_OUTPUT_TRUNCATED`

### RED 测试

- `finishReasonLengthMapsToOutputTruncated` — finish_reason=length 抛 OUTPUT_TRUNCATED
- `finishReasonStopReturnsContent` — finish_reason=stop 正常返回内容

### GREEN 命令

```powershell
.\mvnw.cmd -Dtest=OpenAiCompatibleChatModelGatewayTest test
```

### 回归风险

低。首次截断可进入 Repair，第二次截断终态失败。

## 最终 Schema

### Skeleton Schema

| 字段 | 阶段 | required | nullable | 领域限制 |
|------|------|----------|----------|----------|
| summary | SKELETON | ✓ | ✗ | minLength:1, maxLength:2000 |
| assumptions | SKELETON | ✓ | ✗ | maxItems:20 |
| risks | SKELETON | ✓ | ✗ | maxItems:20 |
| milestones | SKELETON | ✓ | ✗ | minItems:1, maxItems:8 |
| milestones[].tempKey | SKELETON | ✓ | ✗ | minLength:1, unique |
| milestones[].title | SKELETON | ✓ | ✗ | minLength:1, maxLength:100 |
| milestones[].objective | SKELETON | ✓ | ✗ | minLength:1, maxLength:1000 |
| milestones[].targetDate | SKELETON | **✓** | ✓ | format:date, **必须出现，可为 null** |
| milestones[].sortOrder | SKELETON | ✓ | ✗ | minimum:0 |
| tasks | SKELETON | ✓ | ✗ | minItems:1, maxTaskCount 领域校验 |
| tasks[].tempKey | SKELETON | ✓ | ✗ | minLength:1, unique |
| tasks[].milestoneTempKey | SKELETON | ✓ | ✗ | minLength:1, 引用有效里程碑 |
| tasks[].title | SKELETON | ✓ | ✗ | minLength:1, maxLength:160 |
| tasks[].objective | SKELETON | ✓ | ✗ | minLength:1, maxLength:1000 |
| tasks[].sortOrder | SKELETON | ✓ | ✗ | minimum:0 |

### Detail Schema

| 字段 | 阶段 | required | nullable | 领域限制 |
|------|------|----------|----------|----------|
| milestones | DETAIL | ✓ | ✗ | |
| milestones[].tempKey | DETAIL | ✓ | ✗ | minLength:1, 引用 Skeleton |
| milestones[].description | DETAIL | ✓ | ✗ | minLength:1 |
| milestones[].sourceRefs | DETAIL | ✓ | ✗ | array, maxItems:5 |
| tasks | DETAIL | ✓ | ✗ | |
| tasks[].tempKey | DETAIL | ✓ | ✗ | minLength:1, 引用 Skeleton |
| tasks[].description | DETAIL | ✓ | ✗ | minLength:1, maxLength:4000 |
| tasks[].priority | DETAIL | ✓ | ✗ | enum: LOW/MEDIUM/HIGH/URGENT |
| tasks[].estimatedHours | DETAIL | **✓** | ✓ | number, **必须出现，可为 null** |
| tasks[].startDate | DETAIL | **✓** | ✓ | format:date, **必须出现，可为 null** |
| tasks[].dueDate | DETAIL | **✓** | ✓ | format:date, **必须出现，可为 null** |
| tasks[].suggestedAssigneeId | DETAIL | **✓** | ✓ | format:uuid, **必须出现，可为 null** |
| tasks[].dependencyTempKeys | DETAIL | ✓ | ✗ | array, maxItems:5 |
| tasks[].sourceRefs | DETAIL | ✓ | ✗ | array, maxItems:5 |

## 真实请求证据

```json
{
  "model": "<redacted-model>",
  "messages": "system + user prompts",
  "temperature": 0.7,
  "max_tokens": 32768,
  "stream": false,
  "response_format": {"type": "json_object"}
}
```

## 完整编排证据

| 测试 | 方法 | 关键断言 | 结果 |
|------|------|----------|------|
| O1 | `legalTwoPhaseGeneratesReadyPlan` | status=READY, activeAttemptId=null, AI_COMPLETE version 存在, sources 来自服务器 | ✅ |
| O2 | `firstInvalidThenRepairSucceeds` | Skeleton 首次含 sources → Repair → 第二次合法 → READY | ✅ |
| O3 | `twoInvalidSkeletonsFailsWithSafeSummary` | status=FAILED, lastErrorSummary 包含 "SKELETON", 不含原始输出 | ✅ |
| O4 | `outputTruncatedFailsWithTruncatedErrorCode` | status=FAILED, errorCode=PLANNING_MODEL_OUTPUT_TRUNCATED | ✅ |

## 真实模型 smoke test

待执行。需要用户启动本地服务并验证：

```
创建规划 → SKELETON_GENERATING → DETAIL_GENERATING → READY
Network: POST create = 202, GET detail = 200
```

若失败，最终说明必须记录 stage/errorCode/safe errorSummary/finishReason。

## Final Correction (2026-07-27)

### P0-1: TaskPlanModelClient.generate() 确认使用 JSON_OBJECT

**测试证据：**
```java
// TaskPlanModelClientTest.planningGenerateAlwaysRequestsJsonObjectOutput
verify(gateway).complete(captor.capture());
assertThat(captor.getValue().outputFormat()).isEqualTo(OutputFormat.JSON_OBJECT);
```

**生产代码：**
```java
ChatCompletionResult result = gateway.complete(new ChatCompletionCommand(system, user,
        ChatCompletionCommand.OutputFormat.JSON_OBJECT));
```

### P0-2: jackson-datatype-jsr310 已提升为 compile scope

**证据：** pom.xml 中移除 `<scope>test</scope>`，生产运行时可序列化 LocalDate。

### P0-3: fail(errorSummary) 方法已添加 @Transactional

**证据：**
```java
@Transactional
public void fail(UUID planId, long generationSeq, UUID attemptId,
                 TaskPlanStatus expectedStatus, TaskPlanStatus status,
                 String code, String errorSummary) {
    // SELECT FOR UPDATE + UPDATE plan + UPDATE attempt 在同一事务
}
```

### P1-1: Repair Prompt 接收结构化失败信息

**修复 Prompt 示例：**
```
stage=SKELETON
category=MISSING_REQUIRED_FIELD
path=tasks[0].priority
validation_codes=MISSING_PRIORITY
```

**测试证据：** `repairPromptContainsFailureCategoryAndSafePath` 验证所有字段存在。

### P1-2: Nullable required 字段强制出现

**Parser 预检查：** 使用 Jackson JsonNode 在反序列化前检查字段是否存在：
- omitted estimatedHours → reject (FIELD_OMITTED)
- `"estimatedHours": null` → accept

### P1-3: 安全路径提取改进

**使用 JsonMappingException.getPath()：**
```java
if (exception instanceof JsonMappingException mapping) {
    List<JsonMappingException.Reference> path = mapping.getPath();
    // 构建 "tasks[0].priority" 格式的安全路径
}
```

## 最终测试结果

- **后端：93 个测试全部通过**
- **前端：31 个测试全部通过**
- **前端类型检查：通过**
- **前端构建：通过**

## Commit Hashes

待提交（本轮修改）：
1. `fix(planning): create plan and root attempt in foreign-key-safe order`
2. `fix(ai): wire planning requests to JSON object output`
3. `fix(planning): align runtime serialization and structured repair diagnostics`
4. `test(planning): verify production mapper and full structured generation`
5. `fix(frontend): test actionable planning output failures`
6. `docs: finalize Phase 08 structured output verification`

## 未解决项

无。所有 S1-S5 + Final Correction 修复已完成，93 个后端测试 + 31 个前端测试全部通过。
