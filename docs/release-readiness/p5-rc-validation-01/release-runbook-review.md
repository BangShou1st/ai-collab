# 发布与回滚 Runbook 核对（release-runbook-review.md）

> 逐条核对 [release-runbook.md](../p5-release-readiness-02/release-runbook.md) 与
> [rollback-runbook.md](../p5-release-readiness-02/rollback-runbook.md) 的可执行性与内部一致性。

## 发布 Runbook（release-runbook.md）

| 阶段 | 条目 | 核对结论 |
| --- | --- | --- |
| 发布前 | §1 CI 全绿检查（三 Job）、发布 SHA 记录、迁移差集预检、磁盘余量 | ✅ 命令可直接执行；本轮后"三 Job 全绿"口径已被 run 40103355487 真实满足 |
| 发布前 | §2 生产 `.env` 逐项核对 + `docker compose config -q` 校验 | ✅ 与 .env.example 一致；`MODEL_CONFIG_MASTER_KEY` 不可更换警告明确 |
| 发布前 | §3 备份（`backup-postgres.sh` / `backup-minio.sh`），失败即中止 | ✅ 脚本已由 R-01 真实验证 |
| 发布时 | §4 本地构建镜像 + revision label 记录（与 CI 相同命令） | ✅ CI image job 已验证同一构建路径；`[部署环境补充]`：私有 registry 或生产主机构建 |
| 发布时 | §5 Flyway 启动自动迁移 + 手动预演建议 + 失败不重启原则 | ✅ 与 CI 冒烟行为一致（容器启动即迁移）；`FlywayException` 处理指向 rollback runbook |
| 发布时 | §6 `up -d --no-deps --no-recreate app` | ✅ |
| 发布时 | §7 健康检查三件套（health / liveness / 敏感端点拒绝） | ✅ 与 CI 冒烟断言完全同源 |
| 发布时 | §8 五项冒烟（登录/项目/知识库/Agent/权限） | ✅ 步骤可执行；需要真实浏览器与账号 `[部署环境补充]` |
| 判定 | §9 五项成功判定 + §10 T+30min/T+24h 观察 | ✅ 含日志密钥复核 grep |

## 回滚 Runbook（rollback-runbook.md）

| 条目 | 核对结论 |
| --- | --- |
| §1 停止条件（启动失败/health DOWN/核心冒烟失败 15 分钟）与"不回滚"例外（外部 Provider 异常） | ✅ 边界清晰，与本轮 C 项网络结论一致 |
| §2 应用回滚不动数据（前提：新迁移对旧代码兼容） | ✅ 前提声明明确；镜像按 SHA 保留的约定与 §4 发布流程一致 |
| §3 数据不一致：禁止手工回退 Flyway checksum；隔离恢复 → 前滚修复（首选）/ 恢复快照（末选） | ✅ 与 Flyway 只增不减纪律一致；`[部署环境补充]`：快照切换机制 |
| §4 一致性核对脚本 + 人工确认项（行数、object_key 对应、Redis 无需恢复） | ✅ 与 R-01 交付的 verify-restore-consistency.sh 对应 |
| §5 回滚后复跑冒烟、事件记录、禁止热改 | ✅ |

## 明确不混淆的边界

- **恢复演练 ≠ 生产发布回滚演练**：R-01 验证的是脚本与隔离恢复路径；真实生产回滚（含快照切换、增量丢失处置）首次执行仍属部署日动作，Runbook 已如实标注。
- 迁移后不能直接回退的场景已定义（前滚优先、快照兜底），无需本轮额外补充。

## 结论

两份 Runbook 流程完整、命令真实、停止条件明确、与 CI/脚本实现同源，**具备可执行性**；剩余全部为需要真实部署环境才能回填的 `[部署环境补充]` 项，不构成 RC 验证阻断。
