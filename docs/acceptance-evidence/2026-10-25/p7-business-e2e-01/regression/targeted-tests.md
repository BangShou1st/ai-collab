# P7 定向回归结果（2026-10-25）

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 后端 Actuator 安全 + 404 映射 | `mvnw -Dtest=AgentActuatorSecurityTest test` | 2/2 通过（含未暴露端点认证后 `isNotFound()` 断言） |
| 后端编译与启动 | dev.ps1 stop → start（重新编译 target/classes） | BUILD OK，服务 UP |
| 前端 lint | `pnpm lint` | 0 errors / 75 warnings（全部为既有 warning） |
| 前端类型 | `pnpm typecheck`（vue-tsc -b） | 通过 |
| 前端测试 | `pnpm test` | 51 文件 / 393 全部通过 |
| 后端全量 verify | 未执行（原因：本轮后端改动仅为异常映射 + 枚举新增，定向测试覆盖；最终 SHA CI 将执行完整 verify） | — |

## 修复涉及文件

- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/GlobalExceptionHandler.java`（I-1）
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java`（I-1）
- `ai-collab-frontend/src/styles.css`（I-2）

依据 AGENTS.md §五：修改前端 → lint/typecheck/test（已过）；后端逻辑 → 定向单测（已过）；完整 verify 由推送后 CI 承担，正式 ACCEPTED 以最终 SHA CI 为准。
