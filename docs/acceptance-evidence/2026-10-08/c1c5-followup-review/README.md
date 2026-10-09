# C1-C5 修复后复核证据

## 基线

- 日期：2026-10-08。
- 分支：`codex/context-foundation`。
- HEAD：`2cf4c51`；生产修复 `492fddf`，测试 `03df846`。
- 结论见 [复核文档](../../../agent-context-capacity-c1c5-followup-review-20261008.md)。

## 归档内容

- `C1C5FollowupProbe.java.txt`：临时探针源码，未加入生产/test 源码，不调用私有方法。
- `probe-output.txt`：本轮重新编译当前生产源码后的完整执行输出，退出码 1 表示正确性断言失败。
- 探针 1：生产 Summarizer/Composer/Routing 执行器，Repository/native 出站受控。
- 探针 2/3：独立 pgvector PostgreSQL 17 + 真实 Flyway v1-v64 + 生产 Recorder + 真实事务。并发门控只控制 SELECT 之后的执行顺序，不替代 SQL。
- 本轮没有真实模型请求、浏览器验收或全量重跑。不要把这些探针统计并入之前的 Surefire 总数。

## 实际关键输出

```text
FAIL partialTailRemainsInActualMainRequest: tail missing after partial summary: through=1, partialSeq=1, partialChars=60000
FAIL expiredClaimCannotPublishRunSummary: expired claim epoch=1 published COMMITTED; actual input=1000
FAIL cancelBetweenCheckAndPublishCannotPublish: cancel committed after fence read, before publication; summary still COMMITTED
Expected-correctness failures=3/3
```

## 复现方式

需要当前 checkout 的既有测试构建产物、JDK 21、已就绪的 Docker Desktop。命令从 `ai-collab-backend` 执行，所有编译输出写入 ignored 的 target；不写生产数据库，不启动应用或改用户配置。

首次复核把源码保存在 `target/review-c1c5-20261008/C1C5FollowupProbe.java`。归档 `.java.txt` 可作为后续正式回归来源；复现时应使用相同内容的 `.java` 源码路径。

```powershell
$x = [xml](Get-Content -Raw -LiteralPath 'target/surefire-reports/TEST-com.shitulelv.aicollab.agent.application.runtime.AgentRunContextCommitPostgresTest.xml')
$cp = $x.SelectSingleNode('//property[@name="java.class.path"]').GetAttribute('value')
& javac -encoding UTF-8 -cp $cp -d 'target/review-c1c5-20261008/classes' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java' `
  'src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java' `
  'src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRunEventRecorder.java' `
  'target/review-c1c5-20261008/C1C5FollowupProbe.java'
if ($LASTEXITCODE -eq 0) {
  & java -cp ('target/review-c1c5-20261008/classes;' + $cp) `
    com.shitulelv.aicollab.agent.application.runtime.C1C5FollowupProbe 2>&1 |
    Tee-Object -FilePath 'target/review-c1c5-20261008/probe-output.txt'
}
```

首次夹具尝试的 username 超出 varchar(40) 已缩短；那是探针夹具错误，不是第四个产品缺陷。此处只归档修正后且重编译当前生产源码的最终运行。

所有探针执行完毕，线程池关闭，独立容器在 finally 中停止。未停止现有应用服务。
