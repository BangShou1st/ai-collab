# P1 Unified Model and Local Administration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route knowledge chat, Agent, planning, and embeddings through administrator-configured OpenAI-compatible, Anthropic, and Gemini adapters with capability checks and non-readable stored secrets.

**Architecture:** Preserve `ChatModelGateway` as the application-facing port, extend commands with purpose and tool definitions, and implement a routing gateway over provider-specific clients. Persist local model profiles and purpose assignments, encrypt API keys with an environment-provided local master key, and expose system-admin-only user and model management APIs and UI.

**Tech Stack:** Java 21, Spring Boot RestClient/Java HttpClient, Jackson, PostgreSQL/Flyway, AES-GCM, Vue 3, TypeScript, Vitest.

## Global Constraints

- Provider families are `OPENAI_COMPATIBLE`, `ANTHROPIC`, and `GEMINI`.
- Purposes are `KNOWLEDGE_CHAT`, `AGENT`, `PLANNING`, and `EMBEDDING`.
- API keys are writable and replaceable but never readable through API, logs, audit, or errors.
- `admin/admin` is created only under the `local` Spring Profile.
- Every admin endpoint enforces persisted `system_admin`; frontend visibility is not authorization.
- Legacy environment configuration remains a fallback until an administrator assigns a persisted profile.
- Automated adapter tests use official-format local fixtures and never call external networks.

---

### Task 1: Define the unified model contract and capability rules

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelProviderType.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelPurpose.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelCapability.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelToolDefinition.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelCapabilityPolicy.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/ChatCompletionCommand.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/ModelCapabilityPolicyTest.java`

**Interfaces:**
- Produces: `ChatCompletionCommand(ModelPurpose purpose, String systemPrompt, String userPrompt, OutputFormat outputFormat, List<ModelToolDefinition> tools)`
- Produces: `ModelCapabilityPolicy.requireAssignable(ModelPurpose, Set<ModelCapability>)`

- [ ] **Step 1: Write the failing capability matrix test**

```java
@ParameterizedTest
@MethodSource("assignments")
void acceptsOnlyModelsWithRequiredCapabilities(
        ModelPurpose purpose, Set<ModelCapability> capabilities, boolean allowed) {
    ThrowingCallable action = () -> policy.requireAssignable(purpose, capabilities);
    if (allowed) assertThatCode(action).doesNotThrowAnyException();
    else assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class);
}

static Stream<Arguments> assignments() {
    return Stream.of(
        arguments(KNOWLEDGE_CHAT, Set.of(CHAT, STREAMING), true),
        arguments(AGENT, Set.of(CHAT, STRUCTURED_OUTPUT), true),
        arguments(AGENT, Set.of(CHAT), false),
        arguments(PLANNING, Set.of(CHAT, STRUCTURED_OUTPUT), true),
        arguments(EMBEDDING, Set.of(EMBEDDING), true));
}
```

- [ ] **Step 2: Run and observe the missing model contract**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=ModelCapabilityPolicyTest test
```

Expected: FAIL at compile time.

- [ ] **Step 3: Implement enums, immutable tool definitions, and capability policy**

Use exact enum constants from Global Constraints. Make model tool definitions defensive-copy their JSON Schema. Require streaming for knowledge chat, structured output or native tool calling for Agent/planning, and embedding for embedding purpose.

- [ ] **Step 4: Extend commands without breaking text-only callers**

Keep compatibility constructors defaulting to `KNOWLEDGE_CHAT` and an empty tool list. Update Agent to pass `AGENT`, planning to pass `PLANNING`, and knowledge services to pass `KNOWLEDGE_CHAT`.

- [ ] **Step 5: Run all AI, Agent, knowledge, and planning unit tests**

```powershell
.\mvnw.cmd -Dtest=*Model*,AgentWorkerTest,TaskPlanModelClientTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the unified contract**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai
git commit -m "feat(ai): define unified model capabilities"
```

### Task 2: Implement three provider adapters

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/ProviderModelClient.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/ProviderModelRequest.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/ProviderModelResponse.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/OpenAiCompatibleModelClient.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/AnthropicModelClient.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/GeminiModelClient.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider/ProviderErrorMapper.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/provider/OpenAiCompatibleModelClientTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/provider/AnthropicModelClientTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/provider/GeminiModelClientTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/provider/ProviderErrorMapperTest.java`

**Interfaces:**
- Produces: `ProviderModelClient.supports(ModelProviderType)`
- Produces: `complete(ProviderModelRequest)` and `completeStream(ProviderModelRequest, Consumer<String>, Consumer<ProviderModelResponse>, Consumer<Exception>)`
- Produces normalized text, tool calls, finish reason, model name, usage, and latency.

- [ ] **Step 1: Write official-format request and response fixture tests**

For OpenAI-compatible, assert `messages`, `response_format`, `tools`, `tool_choice`, streaming delta content, `finish_reason`, and `usage`.

For Anthropic, assert `x-api-key`, `anthropic-version`, top-level `system`, `messages`, `tools[].input_schema`, `content_block_delta`, `tool_use`, `stop_reason`, and usage mapping.

For Gemini, assert query-key or header authentication is never logged, `systemInstruction`, `contents`, `tools.functionDeclarations`, `responseMimeType`, streamed `candidates[].content.parts`, `functionCall`, `finishReason`, and `usageMetadata`.

- [ ] **Step 2: Run all three tests and observe missing adapters**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=OpenAiCompatibleModelClientTest,AnthropicModelClientTest,GeminiModelClientTest test
```

Expected: FAIL at compile time.

- [ ] **Step 3: Implement the OpenAI-compatible adapter**

Move current OpenAI request/stream parsing behind `ProviderModelClient`. Preserve bounded timeouts, one retry for transient failures, JSON mode opt-out, and output-truncation handling. Parse both textual content and native tool calls.

- [ ] **Step 4: Implement the Anthropic adapter**

Use the Messages API shape and normalize text blocks plus `tool_use` blocks. Stream `content_block_delta` and capture `message_delta.usage`. Map `max_tokens` stop reason to output truncation.

- [ ] **Step 5: Implement the Gemini adapter**

Use generateContent and streamGenerateContent request shapes. Normalize text parts and function calls. Map `MAX_TOKENS` to output truncation and safety/blocked responses to a stable provider response error without exposing provider payloads.

- [ ] **Step 6: Implement the shared error mapper**

Map:

```text
401/403 -> AI_PROVIDER_AUTH_FAILED
408/504/timeout -> AI_MODEL_TIMEOUT
429 -> AI_PROVIDER_QUOTA_EXCEEDED
5xx/network -> AI_PROVIDER_ERROR (retryable where existing policy permits)
invalid or empty body -> AI_PROVIDER_INVALID_RESPONSE
length/max token finish -> AI_PROVIDER_OUTPUT_TRUNCATED
```

Add missing `ErrorCode` entries and Chinese frontend messages in the later admin/API task.

- [ ] **Step 7: Run adapter tests**

```powershell
.\mvnw.cmd -Dtest=OpenAiCompatibleModelClientTest,AnthropicModelClientTest,GeminiModelClientTest,ProviderErrorMapperTest test
```

Expected: PASS with no external network.

- [ ] **Step 8: Commit provider adapters**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/provider ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/provider ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java
git commit -m "feat(ai): add provider protocol adapters"
```

### Task 3: Persist encrypted model profiles and route by purpose

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V24__add_ai_model_configuration.sql`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelConfigCrypto.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelConfigProperties.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelConfigRepository.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelProfile.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/RoutingChatModelGateway.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/ChatModelConfiguration.java`
- Modify: `ai-collab-backend/src/main/resources/application.yml`
- Modify: `ai-collab-backend/src/main/resources/application-local.yml`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelConfigCryptoTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/config/ModelConfigRepositoryIntegrationTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/RoutingChatModelGatewayTest.java`

**Interfaces:**
- Produces: `ModelConfigRepository.findAssigned(ModelPurpose)`
- Produces: `ModelConfigCrypto.encrypt(String)` and `decrypt(byte[])`
- Produces: a primary `RoutingChatModelGateway`

- [ ] **Step 1: Write crypto round-trip and tamper tests**

Use a fixed 32-byte test key. Assert ciphertext does not contain plaintext, decrypt returns the original, and a changed byte throws a configuration exception.

- [ ] **Step 2: Run and observe the missing crypto class**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=ModelConfigCryptoTest test
```

Expected: FAIL at compile time.

- [ ] **Step 3: Create migration V24**

Create `ai_model_config` with UUID ID, unique display name, provider type, base URL, model name, encrypted API key bytes, API-key-configured flag, capabilities JSONB, enabled flag, JSONB options, version, and timestamps. Create `ai_model_assignment` with purpose as primary key, model ID foreign key, version, and updated timestamp. Add checks for provider, purpose, nonblank names, and nonnegative version.

- [ ] **Step 4: Implement AES-256-GCM**

Use a random 12-byte nonce per encryption and prepend a one-byte format version plus nonce to ciphertext. Read a base64 32-byte key from `MODEL_CONFIG_MASTER_KEY`. If no persisted key must be decrypted, legacy environment fallback still starts; model-config write endpoints return a stable unavailable error until the key is configured.

- [ ] **Step 5: Write repository and routing tests**

Persist two enabled model profiles, assign different purposes, and assert routing selects the correct provider client. Disable the assigned profile and assert the gateway uses the existing environment-backed OpenAI-compatible client. Assign a capability-incompatible model and assert the transaction is rejected.

- [ ] **Step 6: Implement repository and routing gateway**

The routing gateway loads the assignment for `command.purpose()`, decrypts only for the duration of the request, selects the matching `ProviderModelClient`, and discards secret-bearing objects after the call. Cache only non-secret profile metadata; correctness must not rely on cache invalidation.

- [ ] **Step 7: Run focused tests**

```powershell
.\mvnw.cmd -Dtest=ModelConfigCryptoTest,ModelConfigRepositoryIntegrationTest,RoutingChatModelGatewayTest test
```

Expected: PASS.

- [ ] **Step 8: Commit persistence and routing**

```powershell
git add ai-collab-backend/src/main/resources/db/migration/V24__add_ai_model_configuration.sql ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai ai-collab-backend/src/main/resources/application.yml ai-collab-backend/src/main/resources/application-local.yml
git commit -m "feat(ai): route encrypted model profiles"
```

### Task 4: Complete local system administration APIs

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/dto/CurrentUserResponse.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/config/LocalDemoUserInitializer.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/config/LocalAdminUserProperties.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/application/view/AdminUserView.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/controller/AdminController.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/service/AdminService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/service/UserService.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/api/AdminModelController.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/api/dto/SaveModelConfigRequest.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/api/dto/AssignModelPurposeRequest.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/application/AdminModelService.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/application/view/ModelConfigView.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/user/service/AdminServiceAuthorizationTest.java`
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/user/config/LocalAdminUserInitializerTest.java`
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/application/AdminModelServiceTest.java`

**Interfaces:**
- Adds: `CurrentUserResponse.systemAdmin`
- Adds: `GET /api/v1/admin/users?page=0&size=50`
- Adds CRUD, enable/disable, key replace, connection test, and purpose assignment under `/api/v1/admin/models`

- [ ] **Step 1: Write failing auth-view and local-admin tests**

Assert `CurrentUserResponse.from(admin).systemAdmin()` is true. Under local configuration, run the initializer twice and assert exactly one active `admin` user exists, the stored password matches `admin`, and `system_admin` is true.

- [ ] **Step 2: Run tests and observe missing behavior**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=LocalAdminUserInitializerTest,AdminServiceAuthorizationTest test
```

Expected: FAIL because current-user view omits the flag and the local `admin` account is absent.

- [ ] **Step 3: Add local-only admin bootstrap**

Keep the existing local owner initializer behavior. Add local admin properties defaulting to username `admin`, password `admin`, display name `本地管理员`. Create or upgrade the account idempotently only in `local`; never reset an existing password on restart.

- [ ] **Step 4: Add bounded user listing**

Implement `AdminService.listUsers(page, size, operatorId)` with `page >= 0`, `1 <= size <= 100`, persisted system-admin authorization, safe fields only, and stable ordering by creation time then ID.

- [ ] **Step 5: Write model-admin authorization and secret tests**

Assert a normal user is denied every model method. Assert views contain `apiKeyConfigured=true` but no key or ciphertext. Assert replacing a key changes ciphertext and connection tests call the selected provider with a minimal request.

- [ ] **Step 6: Implement model administration**

Use optimistic version checks for edits, assignments, enable, and disable. Validate base URL scheme/host, provider-specific path rules, model name length, capabilities, and assignment compatibility. Audit only profile ID, provider, model name, changed non-secret fields, and result.

- [ ] **Step 7: Run admin tests**

```powershell
.\mvnw.cmd -Dtest=LocalAdminUserInitializerTest,AdminServiceAuthorizationTest,AdminModelServiceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit administration APIs**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth ai-collab-backend/src/main/java/com/shitulelv/aicollab/user ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/api ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/application ai-collab-backend/src/test/java/com/shitulelv/aicollab/user ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/application
git commit -m "feat(admin): expose local user and model management"
```

### Task 5: Build the administrator UI

**Files:**
- Modify: `ai-collab-frontend/src/api/types.ts`
- Modify: `ai-collab-frontend/src/stores/auth-store.ts`
- Create: `ai-collab-frontend/src/modules/admin/types.ts`
- Create: `ai-collab-frontend/src/modules/admin/admin-api.ts`
- Create: `ai-collab-frontend/src/modules/admin/AdminView.vue`
- Create: `ai-collab-frontend/src/modules/admin/UserManagementPanel.vue`
- Create: `ai-collab-frontend/src/modules/admin/ModelManagementPanel.vue`
- Create: `ai-collab-frontend/src/modules/admin/admin-api.test.ts`
- Create: `ai-collab-frontend/src/modules/admin/AdminView.test.ts`
- Modify: `ai-collab-frontend/src/router.ts`
- Modify: `ai-collab-frontend/src/shared/AppShell.vue`
- Modify: `ai-collab-frontend/src/api/api-result.ts`

**Interfaces:**
- Produces frontend `CurrentUser.systemAdmin: boolean`
- Produces route `/admin`
- Produces admin API methods matching Task 4.

- [ ] **Step 1: Write the route-visibility test**

Mount `AppShell` with a normal current user and assert no `系统管理` link. Repeat with `systemAdmin:true` and assert the link targets `/admin`.

- [ ] **Step 2: Run and observe failure**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/modules/admin/AdminView.test.ts
```

Expected: FAIL because the route and view do not exist.

- [ ] **Step 3: Add typed admin API tests**

Mock the HTTP client and assert exact methods/paths for user list/create/enable/disable and model list/save/enable/disable/key/test/assign. Assert no returned type contains an `apiKey` field.

- [ ] **Step 4: Implement user management panel**

Render user table with username, display name, status, system-admin mark, created/last-login times, and enable/disable actions. Include the existing create-test-user form. Disable actions against the current user and system administrators.

- [ ] **Step 5: Implement model management panel**

Render provider, display name, model, capabilities, enabled state, assigned purposes, key-configured indicator, and last connection result. Use a dialog for create/edit, a separate write-only key field, and explicit connection-test and purpose-assignment actions.

- [ ] **Step 6: Add route guard**

If a non-admin navigates directly to `/admin`, redirect to `/projects` after auth initialization. Treat backend 403 as authoritative and show the normalized Chinese message.

- [ ] **Step 7: Run frontend checks**

```powershell
pnpm test -- src/modules/admin/admin-api.test.ts src/modules/admin/AdminView.test.ts
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 8: Commit administrator UI**

```powershell
git add ai-collab-frontend/src/modules/admin ai-collab-frontend/src/api/types.ts ai-collab-frontend/src/stores/auth-store.ts ai-collab-frontend/src/router.ts ai-collab-frontend/src/shared/AppShell.vue ai-collab-frontend/src/api/api-result.ts
git commit -m "feat(frontend): add local administration"
```

### Task 6: Synchronize P1 contracts and verify

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database.md`
- Modify: `docs/feature-matrix.md`
- Modify: `docs/development/project-collaboration-agent.md`
- Create: `.env.example`
- Create: `scripts/verify-model-adapters.ps1`

- [ ] **Step 1: Add OpenAPI contract assertions for every admin endpoint**

Require `systemAdmin` in current-user responses, model views without an API key property, bounded user-list parameters, version fields on mutating requests, and all stable provider error codes.

- [ ] **Step 2: Run contract validation before doc changes**

```powershell
.\scripts\validate-openapi.ps1
```

Expected: FAIL until OpenAPI is synchronized.

- [ ] **Step 3: Update contracts and local configuration documentation**

Add `MODEL_CONFIG_MASTER_KEY` to `.env.example` as an empty value with a command for generating a base64 32-byte key. Document `admin/admin` as local-only. Document adapter capabilities and legacy fallback without claiming live provider success.

- [ ] **Step 4: Add the adapter verifier**

The script logs in as local admin, lists profiles, runs connection tests only for explicitly selected profile IDs, and prints provider/model/status without secrets. A missing profile is skipped rather than treated as adapter test success.

- [ ] **Step 5: Run P1 verification**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 6: Perform local admin browser acceptance**

Log in with `admin/admin`, open `/admin`, create or edit one profile for each available protocol family, confirm keys are never displayed after save, run configured connection tests, and assign purposes only to capability-compatible enabled profiles.

- [ ] **Step 7: Commit P1 documentation**

```powershell
git add docs/api/openapi.yaml docs/database.md docs/feature-matrix.md docs/development/project-collaboration-agent.md .env.example scripts/verify-model-adapters.ps1
git commit -m "docs: describe unified model administration"
```
