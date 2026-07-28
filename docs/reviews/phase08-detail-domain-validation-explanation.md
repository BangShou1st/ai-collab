# Phase 08 Detail Domain Validation Fix — 修复说明

## 1. 问题描述

当前真实错误链路：

```text
Create 成功
→ Skeleton 成功
→ Detail JSON 已解析
→ Detail 与 Skeleton 合并
→ COMPLETE 领域校验失败
→ Repair 后仍失败
→ DETAIL_GENERATION_FAILED / FAILED
```

浏览器显示 `DETAIL / DOMAIN_VALIDATION_FAILED`，但无法知道具体哪一项校验失败。

## 2. 根因分析

### D1：Orchestrator 把完整 ValidationResult 压缩成 boolean

在 `TaskPlanGenerationOrchestrator.generateDetailWithOneRepair` 中：

```java
Predicate<DetailModelOutput> valid  // 只返回 boolean
```

失败后只抛：

```java
throw new IllegalArgumentException("DOMAIN_VALIDATION_FAILED");
```

导致丢失所有具体错误码（`ESTIMATED_HOURS_INVALID`、`TASK_DATE_INVALID` 等）。

### D2：Repair Prompt 无法知道失败原因

Repair Prompt 只接收 `contractError`（解析错误），领域校验失败时 `contractError` 为 null，所以 Repair 完全不知道什么失败了。

### D3：错误摘要不含具体验证代码

`safeErrorSummary` 方法只返回 `DETAIL / DOMAIN_VALIDATION_FAILED`，不含具体错误码。

## 3. 修复方案

### 3.1 新增 DetailValidator 函数式接口

```java
@FunctionalInterface
interface DetailValidator {
    ValidationResult validate(DetailModelOutput candidate);
}
```

替代 `Predicate<DetailModelOutput>`，返回完整的 `ValidationResult`。

### 3.2 捕获 ValidationResult 的错误码

```java
ValidationResult validation = valid.validate(detailOut);
if (validation.valid()) return new GeneratedDetail(detailOut, initialAttempt, result);
// 领域校验失败 — 捕获错误码用于 Repair
contractError = new ModelOutputContractException("DOMAIN_VALIDATION_FAILED", null,
        validation.errorCodes());
```

### 3.3 Repair Prompt 接收具体验证代码

```java
String repairPrompt = repairPrompt(result.content(), DETAIL_SCHEMA, "DETAIL",
        contractError.category(),
        contractError.jsonPath(),
        contractError.validationCodes());  // 现在包含具体错误码
```

### 3.4 错误摘要包含具体验证代码

更新 `ModelOutputContractException.safeSummary`：

```java
String safeSummary(String stage) {
    String base = stage + " / " + category + (jsonPath != null ? " / " + jsonPath : "");
    if (!validationCodes.isEmpty()) {
        String codes = " / " + String.join(",", validationCodes);
        base = base + codes;
    }
    // ...
}
```

### 3.5 前端解析并显示友好错误消息

新增 `parseValidationErrorSummary` 函数，将错误码转换为中文描述：

- `ESTIMATED_HOURS_INVALID` → "预计工时无效（需为 0.5-80 的数字）"
- `TASK_DATE_INVALID` → "任务日期无效（需在规划范围内且开始≤截止）"
- `TASK_PRIORITY_INVALID` → "任务优先级无效（需为 LOW/MEDIUM/HIGH/URGENT）"
- 等等

## 4. Validator 规则确认（COMPLETE + aiGenerated）

从 `TaskPlanDraftValidator` 源码确认：

### Task 规则

| 字段 | 规则 | 是否必填 |
|------|------|----------|
| description | 非空且 ≤4000 字符 | 必填 |
| priority | LOW/MEDIUM/HIGH/URGENT | 必填 |
| estimatedHours | 0.5-80 或 null | 可空 |
| startDate | YYYY-MM-DD 或 null | 可空 |
| dueDate | YYYY-MM-DD 或 null | 可空 |
| suggestedAssigneeId | 项目成员 UUID 或 null | 可空 |
| dependencyTempKeys | 引用有效任务，无循环，无自依赖 | 必填（可为空数组） |
| sourceRefs | S1-S12，无重复 | 必填（可为空数组） |

### 跨任务规则

- startDate ≤ dueDate
- 前置任务 dueDate ≤ 后续任务 startDate
- 所有日期在规划范围内
- 无依赖循环
- tempKey 唯一
- Detail/Skeleton key 集合完全一致

## 5. Schema 确认

当前 Detail Schema 允许 `estimatedHours`、`startDate`、`dueDate` 为 null，这与 Validator 规则一致（Validator 只在非空时检查范围）。

## 6. 测试验证

### 单元测试

- `TaskPlanGenerationOrchestratorTest`：6 个测试全部通过
- `TaskPlanGenerationOrchestratorIntegrationTest`：4 个测试全部通过

### 前端测试

- `planning-api.test.ts`：5 个测试通过
- `planning-draft.test.ts`：9 个测试通过
- `planning-failure.test.ts`：10 个测试通过
- `planning-poller.test.ts`：7 个测试通过

### 构建验证

- 后端：`mvnw.cmd clean package` 成功（93 个测试通过）
- 前端：`npm run typecheck`、`npm test`、`npm run build` 全部成功

## 7. 修改文件列表

### 后端

1. `TaskPlanGenerationOrchestrator.java`
   - 新增 `DetailValidator` 函数式接口
   - 修改 `generateDetailWithOneRepair` 使用 `DetailValidator`
   - 捕获 `ValidationResult` 错误码并传给 Repair Prompt
   - 第二次失败时抛出 `ModelOutputContractException` 包含错误码

2. `ModelOutputContractException.java`
   - 更新 `safeSummary` 方法，在摘要中包含 validation codes

### 前端

3. `planning-api.ts`
   - 新增 `parseValidationErrorSummary` 函数

4. `PlanningView.vue`
   - 导入 `parseValidationErrorSummary`
   - 新增 `parsedErrorMessages` 计算属性
   - 更新模板显示结构化错误消息

## 8. 提交信息

```text
fix(planning): preserve domain validation issues during detail repair
fix(planning): align detail schema and prompt with complete draft rules
fix(frontend): display actionable detail validation failures
```

## 9. 最终状态

- ✅ 后端所有测试通过（93 个）
- ✅ 前端所有测试通过（31 个）
- ✅ 前端构建成功
- ✅ 后端打包成功
- ⏳ 真实页面验收（需要启动服务并手动测试）

## 10. 验收命令

```powershell
# 后端
.\mvnw.cmd test
.\mvnw.cmd clean package

# 前端
npm run typecheck
npm test
npm run build

# 启动和 health
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
curl.exe http://localhost:8080/actuator/health
```
