# Lane A backend remove Zen/Kilo shared libs

- Worktree: `E:\project\ai-collab-wt\cleanup-backend`
- Branch: `cleanup/backend-remove-zen-kilo`
- Base HEAD: `c18b9f7b`
- Target gate: design §3.1–§3.3 (build/image/deploy, V102, main code, tests)

## Verification

- `.\mvnw.cmd -B -ntp verify` (attempt 2): BUILD SUCCESS
  - Tests run: 2990
  - Failures: 0
  - Errors: 0
  - Skipped: 60
  - Exit code: 0
- Attempt 1 was a Testcontainers runtime flake: one JDBC connection failure in
  `PersistedModelTurnRecoveryPostgresTest`; the same test passed standalone 24/24.
  Raw attempt 1: `verify-attempt1-runtime-flake.log`.
  Standalone retry: `persisted-turn-recovery-single-test.log`.
- `.\mvnw.cmd -B -ntp dependency:tree`: BUILD SUCCESS; no `com.sharedlibs`,
  OpenCode Zen, Kilo Gateway or JitPack coordinates (`dependency-tree-scan.log`).
- `py -3 scripts\container-test-guard.py`: passed all required classes.
- Active-code zero-residue scan: no hits for the prohibited identifiers outside
  the migration directory (`zero-residue-scan.log`). V45/V65 remain unchanged;
  V102 is the only new migration carrying cleanup SQL.

## Test cleanup

- Deleted test files: 21 total.
  - 19 pure Zen/Kilo/Preset or removed session/Preview API tests deleted directly.
  - 2 quality-bearing tests replaced under new names:
    `KiloPlanningJsonContractTest` -> `PlanningPromptJsonContractTest`;
    `PresetAwareRoutingTest` -> `AgentModelRoutingTest`.
- Added test files: 4
  `AgentModelRoutingTest`, `PlanningPromptJsonContractTest`,
  `JsonHttpModelClientSseTest`, `V102LegacyProviderMigrationPostgresTest`.
- Net test source files: -17.
- Test annotation count: 3174 -> 3055, net -119.
- Retained/refactored coverage includes direct OpenAI-compatible routing,
  SharedAiProvider, Agent runtime/recovery, Planning, RAG quality, V102 cleanup,
  multi-line SSE/[DONE]/missing-DONE contracts.

## Deleted main code (11)

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

## Modified main areas

- Build/deploy: `pom.xml`, `Dockerfile`, `application.yml`, compose prod/staging,
  `prod.env.example`, `install.ps1`, CI comment, container test guard.
- User provider identity: `UserAiProvider`, `UserAiProviderView`, `SharedAiProvider`,
  `UserAiProviderRepository`, `UserAiProviderService`, admin/provider discovery/probe.
- Model routing: `RoutingChatModelGateway`, `RoutingModelTurnGateway`,
  `RoutingAgentModelExecutor`, `OpenAiCompatibleModelAdapter`, `JsonHttpModelClient`,
  `ModelTransportConfiguration`.
- Agent/Planning snapshots: `AgentModelConfigurationStore`,
  `PlanningModelConfigurationStore`, `TaskPlanModelClient`.
- Migration: new `V102__remove_zen_kilo_presets.sql`; no historical migration edited.
- Tests: all affected constructors/behavior updated; full changed-file list in
  `changed-files.txt`.

## NOT_RUN

- Docker image build and container smoke (`ci.yml` image job) was not run in this
  local Lane A pass.
- Environment-gated Browser/Real acceptance and live-provider tests remained
  skipped by their existing opt-in guards; no assertions were removed or bypassed.
