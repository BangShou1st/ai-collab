# P8-RAG-QUALITY-FIX-01：CI 本地模型门控补充验收

本目录追加记录最终CI暴露的问题；[25日研究与引用修复证据](../../2026-10-25/p8-rag-quality-fix-01/README.md)及更早语料/证据保持原字节，不改写历史失败或通过记录。

`d380b21` 的Push与PR后端CI均失败：继承P6测试方法的原P8跨层子类没有显式本地Ollama opt-in。JUnit类条件没有按注释所述继承，导致没有Ollama的CI实际执行向量化。本机此前因存在Ollama而未暴露这个问题。详见[失败回执](ci-failure.json)。

修复仅为该测试补充显式 `EnabledIfEnvironmentVariable`，并新增普通CI的三类本地模型测试全跳过断言。引用PostgreSQL测试仍必须实际运行；不删冻结题集、不放宽质量阈值、不增加CI模型依赖。生产Java相对d380b21完全不变，检索和来源选择仍NO_CHANGE。

- [默认verify与显式启用结果](gate-results.json)：默认环境门控必须实际跳过；显式启用跨层测试必须跑完16题。
- [逐类回归记录](regression-results.json)、[实际跨层结果](integration-results.json)、[源码版本与最终规范化哈希](source-versions.json)。
- [受保护历史字节](source-manifest.json)、[历史完整性](historical-integrity.json)、[本轮哈希清单](manifest.sha256)。
- [补充交付报告](../../../archive/reports/p8-rag-quality-fix-01-ci-gate-20261026.md)。

原P8 manifest的Java项绑定原始归档提交3a32937；其语料和证据项仍核对当前只读文件。本轮测试源码合法演进为显式门控，不能用旧源码hash要求未来源码永远不变，也不能改写旧hash来伪造原始版本。本目录给出源版本对应关系。若审计25日引用修复版本，可使用精确SHA：

```powershell
$env:P8_FIX_SOURCE_REF='d380b21e24aeffbfe85bb61bd9cd7c1f931c9e9f'
node ai-collab-backend/src/test/p8-quality-fix-audit.cjs
```

本补充目录同样冻结后拒绝覆盖。重跑请为runner设置新的 `P8_FIX_EVIDENCE_DIR`、`P8_FIX_TARGET_DIR`，先protect，再integration与verify；原始输出仍仅放target。最终Push/PR同SHA回执在最终交付消息与本地target/final-ci.json提供，不伪造自引用提交SHA或未来CI状态。
