# 多周期压缩验收 — 执行剧本（Runbook）

日期：2026-10-09 ｜ 分支：`codex/context-foundation` ｜ HEAD：`e092f77`

## 0. 定位与边界

- 类型：**受控规模的真实模型质量验收**（如未能在 256k 级自然触发，则明确标注，
  不冒充 256k 输入实测）。
- 隔离：所有业务数据写入与运行状态持久化在隔离副本 PG（127.0.0.1:55432，
  卷 `ai-collab-acceptance-20261003`）、隔离 Redis（16379）、隔离 MinIO（18090）；
  业务容器（5432/6379/9000）与用户模型配置**只读使用、不做任何修改**。
- 验收入口：`RealAcceptanceHostTest`（`AI_REAL_ACCEPTANCE=true`、
  `AI_UPGRADE_REHEARSAL=true`、`AI_REAL_ACCEPTANCE_KEEP_CONFIG=true`）。
- 软触发线方案：验收宿主进程环境变量注入 `AGENT_CONTEXT_WINDOW_OVERRIDES`，
  仅在验收宿主 JVM 内生效；模型真实硬窗口 H、输出封顶与生产默认策略不变；
  隔离副本中的模型配置行零修改。
- 若因环境或配置解析问题无法注入触发线，则退回自然长任务；无论何种路径，
  结果如实标注。

## 1. 资料（fixtures/）

三份 Markdown 手册：`团队协作平台运维手册.md`（8 项事实）、`客户成功服务规范.md`
（9 项）、`数据报表口径手册.md`（10 项）。事实清单见 `fact-checklist.md`
（仅供验收核对，不注入模型）。

## 2. 场景与执行

### 场景 G（实验组：多周期压缩）

1. 隔离项目「上下文压缩验收 20261009」，上传三份手册，等待 READY（embedding 走
   OpenAI 兼容 provider 的 `/v1/embeddings`，由用户默认 Zen provider 承担）。
2. 提交研究问题：「对照这三份手册，说明 API 限流规则、证据保留期限与活跃用户口径，
   并给出来源与未读范围。」
3. 运行 1–3 轮；第 2 轮在会话中给出用户更正（U1–U3，见清单）；要求续研
   （如「再补充批量导入与归因窗口的规则，同样给出来源」）。
4. 观察点：
   - 后端日志出现 RUN_CONTEXT 压缩记录（`运行轨迹窗口压缩已提交` / ATTEMPTED）；
   - `agent_step`（reason=RUN_CONTEXT_SUMMARY）周期 1 与周期 2 的摘要 JSON
     （`sourceFromSequence`、`sourceThroughSequence`、`sourcePartialSequence`、
     `sourcePartialChars`、`sourceStepCount`、`previousSummaryIncorporated`）；
   - 周期 2 提交后主请求中 `<RUN_CONTEXT_SUMMARY>` 是否渲染、周期 1 覆盖的
     已闭合步骤是否退出工具观察层（SQL 核对 `agent_step` + Composer 过滤条件）；
   - 最终回答逐条核对清单 27 项事实、更正 U1–U3 优先、冲突对 X1–X4 并存、
     来源定位与诚实缺口。
5. 持久化证据归档到本目录（SQL 导出 JSON、模型调用捕获、最终回答全文）。

### 场景 C（对照组：同一资料、同一问题、未压缩）

1. 新建隔离项目「压缩对照 20261009」，上传同一组资料。
2. 提交同一问题，单轮完成（不注入触发线覆盖、不给更正、不要求续研）。
3. 记录步数、工具次数、回答事实正确性与未读声明，与实验组逐条对照。

## 3. 判定标准

- **通过**：≥ 2 个有效压缩周期；周期 1 发现延续到周期 2（`previousSummaryIncorporated`
  = true 且抽核事实在周期 2 摘要中保留）；更正优先于被更正陈述；关键条件、
  来源定位与缺口保留；无虚报全文覆盖；最终回答逐条事实正确。
- **失败分类**：代码缺陷（摘要/覆盖/消费链路 bug）、摘要提示问题、模型行为差异、
  环境问题、设计限制。失败先保留可复现证据，再评估最小修复。

## 4. 清理与恢复

- 验收后：`target/real-acceptance.stop` 停宿主；隔离容器按需保留或 `docker stop`；
  私有捕获（`long-quality-model-calls-private.jsonl`）不入 Git。
- 不 reset、不 clean、不 push；`.freebuff/`、`.dsh-acl-recovery/` 原样保留、不混入提交。

## 5. 结果记录

见 `results.md`（执行后填写）。
