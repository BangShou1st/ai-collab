# P6-AGENT-OBSERVABILITY-01 验收证据

日期：2026-10-25。仅保存脱敏元数据与断言结果，不保存Token、HTTP头、Prompt、工具参数或完整结果。

- `baseline.json`：C0 Git/指标/Actuator/日志基线。
- `p6b-final-ci-receipt.json`：P6-B最终0e48982同SHA push/PR成功回执；历史测试证据未改写。
- `metrics-catalog.json`：新增指标、类型、有限标签值、持久化/重启/耗时统计口径。
- `targeted-tests.json`：先行定向22项，实际类/方法、零失败/错误/跳过。
- `acceptance-matrix.md`：C01–C10、I01–I07对应实际测试入口。
- `invariants.json`：Scheduler基线/current git blob、历史迁移/前端/安全配置无改动。
- `initial-full-verify-failure.json`：首轮全量中PostgreSQL重启后9项连接错误的真实失败摘要。
- `final-targeted-recovery.json`：最终专项23项与恢复24项，共47项全通过。
- `backend-verify.json`：最终完整verify的实际类列表、总数及既有opt-in跳过；排除target陈旧XML。
- `postgres-query-plan.txt`：真实PostgreSQL17的EXPLAIN ANALYZE BUFFERS，3万历史行/10 UNKNOWN，不强制关闭seqscan。
- `source-manifest.json`：本轮生产/测试/迁移的SHA256清单。

最终提交无法把自身SHA写入自身而保持同一SHA；交付报告、证据与源码一起提交。最终完整SHA、push/PR Actions链接及同SHA最终裁决在聊天交付回执中给出；复核时检出该SHA，核对清单及测试守卫。未确认最终CI以前不得只凭本地测试签署ACCEPTED。

未执行：真实付费Provider/外部MCP网络取消确认（LIVE_NOT_RUN）、外部告警平台、多实例与生产大表性能。无前端改动，本地未重复前端套件或浏览器UI验收；正常CI继续前端与PR镜像验证。
