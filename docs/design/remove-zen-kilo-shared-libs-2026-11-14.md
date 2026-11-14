# 移除 OpenCode Zen & Kilo Gateway 共享库设计（2026-11-14）

> 性质：**设计，不含业务代码实现**
> 基线：`dev @ 8f4854ac`（Wave 1.5–Wave 3 已复核 PASS；CI `43572302573` / `43573112183` 全绿）
> 目标：彻底移除 `opencode-zen` / `kilo-gateway` 两个 JitPack 共享库及其预设通道、
>       中继、Bun 运行时、前后入口与配置，零第三方 JitPack 依赖
> 边界：保留 `SharedAiProvider` 全站共享大模型、直连 `OPENAI_COMPATIBLE` / `ANTHROPIC` /
>       `GEMINI`、本地 Reranker、Ollama Embedding 与全部现有核心质量测试；绝不修改 V1–V101 历史迁移

## 0. 为什么要清理 & 对现有系统的影响

1. 上游两个共享库仓库即将删除，继续保留 `jitpack.io` 坐标会导致后续 Docker / Maven 构建不可重现。
2. OpenCode Zen v1.6.1 强制要求镜像安装 Linux musl Bun（`BUN_VERSION=1.4.4`）做 TLS 指纹中继，
   移除后后端运行镜像大幅精简且去掉外部二进制下载源。
3. 删除后**零业务能力退化**：个人和管理员模型配置全部走直连三协议
   （`OPENAI_COMPATIBLE` / `ANTHROPIC` / `GEMINI`）与 `SharedAiProvider` 全站共享模型，
   本机 Magpie `:3425`、`ProviderProfileCatalog`（ModelScope 等）、`AiCapabilityProbeService`
   均不依赖两个共享库。

## 1. 保留清单（严禁误删）

| 资产 | 说明与处理 |
| --- | --- |
| `SharedAiProvider`（V77 全站共享模型） | **必须保留**。历史上借用 `UserAiProvider.presetCode="SHARED"` 区分内存共享模型快照；本轮改为类型安全的 **`boolean shared`**，彻底脱钩 `presetCode` 字符串 |
| `ProviderProfileCatalog`（ModelScope 等网关预设目录） | **保留**。它属于自定义模型的协议目录与端点预览辅助，与 Zen/Kilo 共享库无关 |
| `OpenAiCompatibleModelAdapter` / `ProviderExtensionParamsProperties` / `GeminiThinkingProperties` | **保留核心直连能力**。仅剥离 `OpenAiCompatibleModelAdapter` 内的 `zenRequestFactory`、`zenWire`、`completeWithSession`、`turnWithSession` 与 `reservedToolNames` 逻辑 |
| `RoutingChatModelGateway` / `RoutingModelTurnGateway` / `ModelTransportConfiguration` | **保留主路由和配置类**。只删除其中的 `ZenModelExecution`、`KiloGatewayModelExecution` 分支和 `KiloGatewayClient` Bean |
| `AiCapabilityProbeService` / `ProviderModelDiscoveryService` | **保留**。移除其中的 `isPreset()` / `presetCode` 跳过分支即可 |
| `P10RealAgentGeneration03PostgresTest` / `P10RealAgentGeneration04ReproductionPostgresTest` / `RealAgentLoopPostgresTest` | **保留质量回归覆盖，不许删除**。改为普通 `OPENAI_COMPATIBLE`（`shared=false`）+ Spy `OpenAiCompatibleModelAdapter` / `ModelTurnGateway` |
| `AgentModelTransportMetricsTest` | 将 `KiloGatewayClientTransport` 的指标行为测试改为用测试 stub 或通用 `ModelTurnProviderAdapter` 保留观测断言（或明确只清理 Kilo 专属用例而保留 `AgentRuntimeMetrics` 通用用例） |
| 历史迁移 `V1`–`V101`（含 `V45`、`V65`） | **一字不动**（Flyway checksum 红线） |
| 历史归档与证据目录 `docs/archive/**`、`docs/acceptance-evidence/**` | **保留历史真实记录，不改写** |

## 2. 数据库迁移方案（V102）

新建迁移文件：`ai-collab-backend/src/main/resources/db/migration/V102__remove_zen_kilo_presets.sql`

```sql
-- V102: 移除 OpenCode Zen / Kilo Gateway 预设模型配置及 preset_code 列。
-- 依赖现有触发器 trg_cascade_user_ai_provider_delete (V77) 与 ON DELETE CASCADE (V66)
-- 自动级联清理个人用途绑定和能力探针记录，显式清理前置兜底防孤儿行。
DELETE FROM user_model_purpose_assignment
WHERE is_shared = FALSE
  AND provider_id IN (
      SELECT id FROM user_ai_provider
      WHERE preset_code IN ('OPENCODE_ZEN_FREE', 'KILO_GATEWAY_FREE')
  );

DELETE FROM user_ai_provider
WHERE preset_code IN ('OPENCODE_ZEN_FREE', 'KILO_GATEWAY_FREE');

ALTER TABLE user_ai_provider DROP CONSTRAINT IF EXISTS ck_user_ai_provider_preset;
DROP INDEX IF EXISTS uk_user_ai_provider_user_preset;
ALTER TABLE user_ai_provider DROP COLUMN IF EXISTS preset_code;
```

**注意**：执行顺序必须保证 `DELETE` 在 `DROP COLUMN` 之前；已核实 `user_ai_provider_probe_result`
在 V66 定义 `REFERENCES user_ai_provider(id) ON DELETE CASCADE`，`user_model_purpose_assignment`
在 V77 由 `trg_cascade_user_ai_provider_delete` 级联清理，且额外显式清理保证幂等。
历史测试 `LegacyApprovalCitationUpgradeTest`（V45 → 最新）和 `NextStageMigrationPostgresTest`（V53 → 最新）
只断言 `>= 19` 等相对增量，不硬编码总版本；仍需在迁移后实跑验证。

## 3. 删除与改造清单（按层拆解）

### 3.1 构建、镜像与部署配置（Lane A）

| 文件 | 改动 |
| --- | --- |
| `ai-collab-backend/pom.xml` | 删除 `<opencode-zen.version>`、`<kilo-gateway.version>`、`<repositories>` 里的 `jitpack.io` 仓库、以及 `opencode-zen-spring-boot-starter` 和 `kilo-gateway-spring-boot-starter` 两个 `<dependency>` |
| `ai-collab-backend/Dockerfile` | 删除运行阶段的 `BUN_VERSION`、`BUN_MUSL_SHA256`、`curl`/`unzip`/`libstdc++`/`libgcc`（先确认无其它需求：alpine healthcheck 用的是 busybox `wget`，Java 不需要它们）、`bun` 下载安装与 `ENV ZEN_BUN` |
| `ai-collab-backend/src/main/resources/application.yml` | 删除底部 `zen:` 配置段落及 Zen/Kilo 注释 |
| `ai-collab-deploy/compose.prod.yml`、`compose.staging.yml` | 删除 `OPENCODE_ZEN_RELAY_URL` 环境变量透传 |
| `ai-collab-deploy/prod.env.example` | 删除 `OPENCODE_ZEN_RELAY_URL` 说明及变量 |
| `ai-collab-deploy/install.ps1` | 删除或更新第 117 行“后端镜像内置 linux-x64-musl 的 Bun（Zen 中继依赖）”注释；确认安装脚本逻辑不依赖 Zen/Bun |
| `.github/workflows/ci.yml` | 更新第 22 行注释（不再提及 JitPack 共享库） |
| `ai-collab-backend/scripts/container-test-guard.py` | 移除已删除的 `KiloGatewayPresetIsolationIntegrationTest` 登记及注释 |

### 3.2 后端主代码（Lane B）

**直接删除的文件（11 个）**：

- `infrastructure/ai/model/ProviderPresetCode.java`
- `infrastructure/ai/model/ProviderPresetRegistry.java`
- `infrastructure/ai/model/OpenCodeZenTransport.java`
- `infrastructure/ai/model/HttpOpenCodeZenTransport.java`
- `infrastructure/ai/model/ZenModelExecution.java`
- `infrastructure/ai/model/KiloGatewayTransport.java`
- `infrastructure/ai/model/KiloGatewayClientTransport.java`
- `infrastructure/ai/model/KiloGatewayModelExecution.java`
- `infrastructure/ai/user/UserAiProviderPresetService.java`
- `infrastructure/ai/user/KiloGatewayPresetService.java`
- `infrastructure/ai/user/api/UserAiProviderPresetController.java`

**改造的文件**：

1. **移除 `presetCode`，用 `boolean shared` 表达共享身份**：
   - `UserAiProvider`：将 `String presetCode` 替换为 `boolean shared`（保留兼容构造器过渡如仅在少数调用点使用，但建议全仓清理干净为唯一规范构造器）；删除 `isPreset()`，保留/提供 `isShared()`。
   - `SharedAiProvider`：删除 `PRESET_CODE_SHARED = "SHARED"`，`toRuntimeUserAiProvider()` 传入 `shared = true`。
   - `UserAiProviderView`：将 `String presetCode` 替换为 `boolean shared`。
   - `UserAiProviderRepository`：`COLUMNS`、`MAPPER`、`save`、`findById` 移除 `preset_code`；共享供应商查询把 `'SHARED' AS preset_code` 改为 `TRUE AS shared`（个人查询 `FALSE AS shared`）；删除 `findByUserAndPreset` 与 `deleteByUserAndPreset`。
   - `UserAiProviderService`：删除 `rejectPreset`；`requireSnapshotAuthorized` 改为 `if (snapshot.shared())`；创建/更新直接沿用普通自定义配置规则（`shared = false`）。
   - `AdminAiProviderService`：移除 `.filter(p -> p.presetCode() == null)` 过滤（`user_ai_provider` 表中已全是用户自定义行）。
   - `ProviderModelDiscoveryService`、`AiCapabilityProbeService`：删除 `presetCode` / `isPreset()` 特殊分支，统一按普通配置处理。
   - `AiRequestMetadata` & `ModelSecretCipher`：清理过时 javadoc 注释中的 Zen 提及；`decryptOptional` 若仍有 `UserAiProviderService` 使用则保留。
2. **模型网关与适配器**：
   - `ModelTransportConfiguration`：删除 `@EnableConfigurationProperties` 中的 `KiloGatewayProperties.class` 与 `kiloGatewayClient(...)` Bean。
   - `OpenAiCompatibleModelAdapter`：删除 `OpenCodeZenRequestFactory` 字段与构造注入参数、删除 `zenRequest` / `zenTurnRequest` / `zenWire` / `completeWithSession` / `turnWithSession` / `turnStreamingSyncWithSession` / `validateNoReservedToolConflict` / `isZenReservedTool`，保留纯粹的 OpenAI 兼容 `complete` / `completeStream` / `turn` 与 `ProviderExtensionParamsProperties`。
   - `RoutingChatModelGateway` & `RoutingModelTurnGateway`：删除 `ZenModelExecution`、`KiloGatewayModelExecution` 字段、构造器参数和分支；`isShared` 简化为 `provider != null && provider.shared()`。
   - `RoutingAgentModelExecutor`：删除 `ZenModelExecution` 依赖，直接使用 `provider.toModelConfiguration()`。
3. **Agent 与 Planning 快照**：
   - `AgentModelConfigurationStore`：`budgetEnforced` 固定 `true`（原 `!provider.isPreset()`），`mode` 直接按 `provider.capabilities().contains(ModelCapability.NATIVE_TOOLS)` 判定。
   - `PlanningModelConfigurationStore` & `TaskPlanModelClient`：`budgetEnforced` 固定 `true`（移除 `!"OPENCODE_ZEN_FREE".equals(...)` 判断）。

### 3.3 后端测试清理与重构（Lane C）

**删除纯 Zen / Kilo / Preset 测试（15 个）**：
- `infrastructure/ai/model/`: `ZenModelWiringTest`, `ZenUpstreamRoutingTest`, `ZenStarterWiringTest`, `ZenKnowledgeStreamContractTest`, `ZenPlanningContractTest`, `OpenCodeZenSmokeTest`, `ModelProtocolHttpTest`, `KiloGatewayModelTest`, `KiloGatewayStarterWiringTest`, `KiloGatewayModelExecutionStreamContractTest`, `KiloGatewayTransportLogSafetyTest`, `RoutingChatModelGatewayKiloDisabledTest`
- `infrastructure/ai/user/`: `UserAiProviderPresetTest`, `UserAiProviderPresetGuardTest`, `ZenDisconnectIntegrationTest`, `KiloGatewayPresetServiceTest`, `KiloGatewayPresetIsolationIntegrationTest`（实际共 17 个，按代码核实逐一清除）

**重构以保留非 Zen/Kilo 业务覆盖的测试（约 15 个）**：
- `P10RealAgentGeneration03PostgresTest` & `P10RealAgentGeneration04ReproductionPostgresTest`：改为普通 `OPENAI_COMPATIBLE`（`shared=false`、带测试 placeholder key）+ `@MockitoSpyBean OpenAiCompatibleModelAdapter`（或 `RoutingModelTurnGateway`）拦截原始出站，保持 20 场景质量回归完整跑通。
- `RealAgentLoopPostgresTest`：移除 `KiloGatewayClient` / `KiloGatewayModelExecution` / `ZenModelExecution`，改用 `OpenAiCompatibleModelAdapter` / `RoutingModelTurnGateway`。
- `PresetAwareRoutingTest`：移除，或将其测试 `isShared` / `UserAiProvider` 快照隔离部分保留并更名为 `SharedProviderRoutingTest`。
- `RoutingAgentModelExecutorRequestConsistencyTest`、`AgentAuxiliaryOutboundRegressionTest`、`AgentContextReliabilityD1D8RegressionTest`、`AgentRunContextCommitPostgresTest`、`AgentRunContextCompactionRegressionTest`、`AgentRuntimeRequestSnapshotTest`：构造 `RoutingAgentModelExecutor` 与 `UserAiProvider` 时去掉 `ZenModelExecution` 和 `presetCode` 参数（改为 `shared`）。
- `GeminiThinkingOverrideTest`、`ProviderModelDiscoveryServiceTest`、`UserAiProviderOwnershipTest`、`AiCapabilityProbePlanningContractTest`、`AiCapabilityWriteBackIntegrationTest`：更新构造器参数与 `boolean shared`。
- `AgentModelTransportMetricsTest`：去掉 Kilo 专属用例或改用普通 `ModelTurnProviderAdapter` 验证 `AgentRuntimeMetrics` 不回归。
- `BrowserAcceptanceHostTest` & `RealAcceptanceHostTest`：改为选取当前用户的任意 enabled `OPENAI_COMPATIBLE` 配置，不再硬编码 `preset_code='OPENCODE_ZEN_FREE'`。

### 3.4 前端移除（Lane D）

**删除文件（2 个）**：
- `ai-collab-frontend/src/modules/ai/ai-preset-api.ts`
- `ai-collab-frontend/src/modules/ai/use-preset-connection.ts`

**改造文件**：
- `UserAiSettingsView.vue`：移除“推荐连接”区块（OpenCode Zen / Kilo Gateway 卡片）、移除 `zen` / `kilo` 状态与加载/保存/断开逻辑、移除 `zenProvider` / `kiloProvider` 过滤；只保留“全站共享模型”与“你的模型”（自定义模型）；`readinessOf` 与目的下拉列表改用 `shared` 判断。
- `user-ai-api.ts`、`ai-purpose-status.ts`、`CapabilitySuitePanel.vue`、`use-capability-suite.ts`、`ProviderFormDialog.test.ts`：把 `presetCode: string | null` 改为 `shared?: boolean`（或 `shared: boolean`）；`CapabilitySuitePanel` 不再需要 `isPreset` 特例（所有个人模型均正常跑完整能力套件与写回；若未来需要传共享模型预览，则按 `shared` 区分）。
- `styles.css`、`styles/v211.css`：清理无用的 `.zen-brand`、`.zen-two-col`、`.provider-tile.zen`、`.provider-tile.kilo`、`.zen-form` 等孤立 CSS 类（先全局检索确认无其他组件引用）。
- `UserAiSettingsView.test.ts`、`use-capability-suite.test.ts`、`ai-purpose-status.test.ts`：删除 Zen/Kilo 预设用例，更新自定义与共享模型测试用例。

### 3.5 文档与契约（收口前同步）

- `docs/api/openapi.yaml`：删除 `/user/ai-provider-presets*` 6 个端点与 `PresetStatus` schema；`UserAiProvider` schema 中 `presetCode` 改为 `shared: boolean`。
- `AGENTS.md`：第 2 节技术栈移去“OpenCode Zen & Kilo Gateway 共享库（公开 JitPack）”，迁移范围更新为 **`Flyway V1–V102`**。
- `README.md`：更新核心特性、架构图、依赖表，去掉 Zen / Kilo 与 JitPack 条目，并同步迁移范围 `V1–V102`。
- `docs/capability-matrix.md` & `docs/design/agent-runtime-maintenance.md`：在 `capability-matrix.md` 顶层台账补充说明共享库与预设已移除（旧行保留历史标注或注明已退役）。

## 4. 编排与门禁

建议在干净基线上分 3 个串/并行可控的 Lane 完成：

| Lane | 范围 | 分支 | 冲突边界 |
| --- | --- | --- | --- |
| A | V102 + `pom.xml` + `Dockerfile` + deploy/CI + 后端模型/用户/Agent/Planning 代码删除与重构 | `cleanup/backend-remove-zen-kilo` | 所有后端代码、迁移与配置（含把后端测试编过跑绿） |
| B | 前端预设文件/UI 删除、`shared` 改造与样式清理、前端四门禁 | `cleanup/frontend-remove-zen-kilo` | 仅 `ai-collab-frontend/` |
| C | `openapi.yaml`、`AGENTS.md`、`README.md`、`docs/` 同步更新与全局零引用复检 | 集成后中心直接收口或独立分支 | 仅文档与契约 |

> 由于 Java 测试高度依赖后端源码重构，把构建文件、V102、主源码和测试重构放在同一 Lane A 内闭环编译跑通，与纯前端 Lane B 并行，可以彻底避免跨 worktree 依赖错位。

## 5. 完成定义（验收门禁）

1. **零残留扫描**：仓库活动代码与现行配置（排除 `docs/archive`、`docs/acceptance-evidence` 和 `V45`/`V65` 历史迁移）中，`jitpack.io`、`opencode-zen`、`kilo-gateway`、`com.sharedlibs`、`OPENCODE_ZEN_FREE`、`KILO_GATEWAY_FREE`、`ZEN_BUN`、`OPENCODE_ZEN_RELAY_URL`、`preset_code`、`presetCode` 全部 **0 命中**。
2. **Maven 脱敏离线验证**：清空本地 `~/.m2/repository/com/sharedlibs` 或用依赖树校验 `mvn dependency:tree` 确认完全不解析 JitPack 坐标。
3. **后端完整门禁**：`.\mvnw.cmd -B -ntp verify` `0 failures / 0 errors / exit=0`（含 V102 迁移、`P10RealAgentGeneration03/04`、`container-test-guard.py`）。
4. **前端四门禁**：`pnpm lint`（0 error）、`pnpm typecheck`、`pnpm test`、`pnpm build` 全 exit=0。
5. **Python 门禁**：`uv run pytest -q` + `ruff` + `mypy` 全 exit=0（确认未受影响）。
6. **Docker 镜像构建与容器冒烟**：`ci.yml` 的 `Image build & container smoke`（建议手动 `workflow_dispatch` 触发跑绿）验证去掉 Bun 和 JitPack 后镜像仍可构建且 `/actuator/health` UP。
7. **收口**：清理全部 lane worktree，打 `archive/20261114/*` tag 后删除临时分支，仅留 `dev`/`main` 与主 worktree；push `dev` 并拿全绿 CI。
