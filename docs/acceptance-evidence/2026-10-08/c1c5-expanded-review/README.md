# C1-C5 扩展复核证据（D3-D8）

- 日期：2026-10-08；分支 `codex/context-foundation`；HEAD `2cf4c51`。
- 报告：[扩展复核](../../../agent-context-capacity-expanded-review-20261008.md)。
- 上一条答复的 D1/D2 三探针：[先前证据](../c1c5-followup-review/README.md)，本目录不冒充重跑那些探针。

## 本次新增探针

`C1C5ExpandedProbe.java.txt` 为 ignored target 临时源码的归档，不是正式生产/test 源码。相关生产 Java 从当前 HEAD 重新编译到独立临时目录，模型/Gateway/Repository/记忆存储等边界受控；没有反射调用私有方法或同名替身，无真实 HTTP/模型/业务库写入。

实际运行日志 `probe-output.txt`，退出码 1 表示下列六个正确性断言失败，不是执行器异常：

```text
FAIL lengthTerminatedRunSummaryDoesNotAdvanceCoverage: finishReason=LENGTH advanced sourceThrough=1
FAIL childV2DoesNotInheritProjectMemory: depth=1 v2 request includes parent project preference; memoryIncluded=true
FAIL fullToolLayerStillCountsUnvisitedOlderSources: tool layer filled exactly: candidates=2, included=1, droppedSourceChars=0, omittedOlderChars=180011
FAIL nearWindowV2ConversationSummaryHasIndependentInput: v2 main request fits 50k fallback, but old conversation summary never sent; status=SUCCEEDED
FAIL failedAuxiliaryStillRefreshesNextMainRequest: configuration changed A->B, actual outbound order=[model-B, model-A]; failed auxiliary left stale main snapshot
FAIL legacyOutboundKeepsCommittedRunSummaryAndGuidance: actual Legacy HTTP command dropped composed mandatory layers: summary=false, finalizingGuidance=false
Expanded expected-correctness failures=6/6
```

### 执行方式

从 `ai-collab-backend` 执行，需要既有当前测试构建产物与 JDK 21。归档源码内容存放为 `target/review-c1c5-expanded-20261008/C1C5ExpandedProbe.java` 后：

```powershell
$x = [xml](Get-Content -Raw -LiteralPath 'target/surefire-reports/TEST-com.shitulelv.aicollab.agent.application.runtime.AgentRunContextCommitPostgresTest.xml')
$cp = $x.SelectSingleNode('//property[@name="java.class.path"]').GetAttribute('value')
& javac -encoding UTF-8 -cp $cp -d 'target/review-c1c5-expanded-20261008/classes' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/RoutingAgentModelExecutor.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/LegacyReadOnlyAgentExecutor.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/AgentMemoryService.java' `
  'target/review-c1c5-expanded-20261008/C1C5ExpandedProbe.java'
if ($LASTEXITCODE -eq 0) {
  & java -cp ('target/review-c1c5-expanded-20261008/classes;' + $cp) `
    com.shitulelv.aicollab.agent.application.runtime.C1C5ExpandedProbe 2>&1 |
    Tee-Object -FilePath 'target/review-c1c5-expanded-20261008/probe-output.txt'
}
```

## 正式相关用例重跑

实际命令（离线 Maven，不获取依赖）：

```powershell
.\mvnw.cmd -o '-Dtest=AgentRunContextCompactionRegressionTest,AgentRunContextCommitPostgresTest,AgentAuxiliaryOutboundRegressionTest,LegacyReadOnlyAgentExecutorTest' test
```

完整 Maven 日志：`formal-regressions.log`。

| 类 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| AgentRunContextCompactionRegressionTest | 4 | 0 | 0 | 0 |
| AgentRunContextCommitPostgresTest | 6 | 0 | 0 | 0 |
| AgentAuxiliaryOutboundRegressionTest | 3 | 0 | 0 | 0 |
| LegacyReadOnlyAgentExecutorTest | 8 | 0 | 0 | 0 |
| 合计 | 21 | 0 | 0 | 0 |

BUILD SUCCESS，结束时间 `2026-10-08T19:43:26+08:00`。PostgreSQL 用例使用独立 Testcontainers pgvector 与真实 Flyway v1-v64；不是业务库。执行已结束。

没有生产修复，没有本次绿灯修复结果；没有全量、前端、浏览器或真实模型验收。21 项现有用例通过与 6 个额外探针失败属于两套不同用例，不能混加成一组 Surefire 统计。
