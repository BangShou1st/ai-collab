package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProfileRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProfileView;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestDocument;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestResponse;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileService;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileSource;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileView;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileRequest;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** V92–V95 真实数据库约束、发布不可变、CAS 回滚与 Lane B 50 候选测试端点。 */
@Testcontainers(disabledWithoutDocker = true)
class RerankCapabilityMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger HTTP_CALLS = new AtomicInteger();

    static Flyway flyway;
    static JdbcTemplate jdbc;
    static ModelProfileRepository modelProfiles;
    static ModelProfileService modelProfileService;
    static CapabilityProviderRepository providerRepository;
    static CapabilityProfileRepository profileRepository;
    static CapabilityAssignmentRepository assignmentRepository;
    static SharedAiProviderRepository sharedProviderRepository;
    static CapabilityProfileService profileService;
    static AuditService audit;
    static UUID admin;
    static UUID modelProfileId;
    static UUID providerId;
    static HttpServer rerankServer;
    static String baseUrl;

    @BeforeAll
    static void setup() throws IOException {
        var dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        modelProfiles = new ModelProfileRepository(jdbc);
        modelProfileService = new ModelProfileService(modelProfiles);
        providerRepository = new CapabilityProviderRepository(jdbc);
        profileRepository = new CapabilityProfileRepository(jdbc);
        assignmentRepository = new CapabilityAssignmentRepository(jdbc);
        sharedProviderRepository = new SharedAiProviderRepository(jdbc);
        audit = mock(AuditService.class);

        startRerankServer();
        admin = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name, system_admin)
                VALUES (?, ?, 'unused', 'rerank-admin', true)
                """, admin, "rerank-admin-" + admin.toString().substring(0, 8));
        ModelProfileView model = modelProfileService.create(admin, new ModelProfileRequest(
                ModelProviderType.LOCAL_CUSTOM, baseUrl, "/rerank", "rerank-model", 8192, null,
                false, null, null, ModelProfileSource.LOCAL_FILE, "rerank-model", "Rerank test",
                "1.0", "a".repeat(64), "Apache-2.0"));
        modelProfileId = model.id();
        providerId = providerRepository.create(admin, UUID.randomUUID(), "local-rerank-test",
                ModelProviderType.LOCAL_CUSTOM, "LLAMACPP_RERANK", baseUrl, "/rerank", "NONE",
                "local", "/health", null, true).id();
        profileService = new CapabilityProfileService(profileRepository, providerRepository,
                assignmentRepository, modelProfiles,
                new RerankSettingsSchema(JSON), new LlamaCppRerankAdapter(JSON),
                new ModelSecretCipher("integration-test-master-key-123456"), audit, JSON);
    }

    @AfterAll
    static void teardown() {
        if (rerankServer != null) {
            rerankServer.stop(0);
        }
    }

    @BeforeEach
    void resetCalls() {
        HTTP_CALLS.set(0);
    }

    @Test
    void migrationsAndConstraintsAreInstalled() {
        UUID profileId = createDraft();
        // 不硬编码迁移数量：footprint 随其它 lane 的新迁移增长，只要求 classpath 上
        // 所有迁移都已应用、没有 pending，且至少包含 Lane A 的 V95。
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).hasSameSizeAs(flyway.info().all());
        assertThat(current.getVersion())
                .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("95"));

        Map<String, Object> provider = jdbc.queryForMap("""
                SELECT provider_type, protocol, auth_type, network_zone, health_path
                FROM shared_ai_provider WHERE id=?
                """, providerId);
        assertThat(provider.get("provider_type")).isEqualTo("LOCAL_CUSTOM");
        assertThat(provider.get("protocol")).isEqualTo("LLAMACPP_RERANK");
        assertThat(provider.get("auth_type")).isEqualTo("NONE");
        assertThat(provider.get("network_zone")).isEqualTo("local");
        assertThat(sharedProviderRepository.findAllPublished())
                .noneMatch(shared -> shared.id().equals(providerId));
        assertThat(sharedProviderRepository.findChatCapableById(providerId)).isEmpty();
        assertThat(sharedProviderRepository.findAll())
                .anySatisfy(shared -> {
                    if (shared.id().equals(providerId)) {
                        assertThat(shared.capabilities()).contains(
                                com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.RERANK);
                    }
                });

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_model_capability_profile(
                    model_profile_id, provider_id, capability, protocol, settings_json,
                    status, version, source, verified_at, created_by, updated_by)
                VALUES (?, ?, 'RERANK', 'LLAMACPP_RERANK', '{}'::jsonb,
                    'TESTED', 99, 'ADMIN_VERIFIED', NULL, ?, ?)
                """, modelProfileId, providerId, admin, admin))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_capability_assignment(
                    capability, purpose, scope, project_id, model_capability_profile_id,
                    enabled, created_by, updated_by)
                VALUES ('RERANK', 'KNOWLEDGE_CHAT', 'GLOBAL', NULL, ?, false, ?, ?)
                """, profileId, admin, admin))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_capability_assignment(
                    capability, purpose, scope, project_id, model_capability_profile_id,
                    enabled, created_by, updated_by)
                VALUES ('RERANK', 'KNOWLEDGE_RERANK', 'GLOBAL', ?, ?, false, ?, ?)
                """, UUID.randomUUID(), profileId, admin, admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lifecyclePublishRollbackImmutabilityAndAssignmentRepoint() {
        UUID v1 = createDraft();
        CapabilityTestResponse smoke = profileService.test(admin, v1, null);
        assertThat(smoke.outcome()).isEqualTo("SUCCESS");
        assertThat(smoke.scores()).allSatisfy(score -> assertThat(Double.isFinite(score.score())).isTrue());
        assertThat(profileRepository.findById(v1).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.TESTED);
        profileService.publish(admin, v1, "first");
        assertThat(profileRepository.findById(v1).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.PUBLISHED);
        assertThat(profileRepository.markPublished(v1, admin)).isFalse();

        CapabilityAssignment assignment = assignmentRepository.create(admin, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK, CapabilityAssignmentScope.GLOBAL, null, v1, true);

        UUID v2 = createDraft();
        assertThat(profileService.test(admin, v2, null).outcome()).isEqualTo("SUCCESS");
        profileService.publish(admin, v2, "second");
        assertThat(profileRepository.findById(v1).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.DISABLED);
        assertThat(profileRepository.findById(v2).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.PUBLISHED);
        assertThat(assignmentRepository.findById(assignment.id()).orElseThrow()
                .modelCapabilityProfileId()).isEqualTo(v2);

        profileService.rollback(admin, v2, "rollback");
        assertThat(profileRepository.findById(v2).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.DISABLED);
        assertThat(profileRepository.findById(v1).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.PUBLISHED);
        assertThat(assignmentRepository.findById(assignment.id()).orElseThrow()
                .modelCapabilityProfileId()).isEqualTo(v1);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ai_model_capability_profile
                SET settings_json = settings_json || '{"topK": 9}'::jsonb
                WHERE id=?
                """, v1))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_model_capability_profile(
                    model_profile_id, provider_id, capability, protocol, settings_json,
                    status, version, source, verified_at, created_by, updated_by)
                VALUES (?, ?, 'RERANK', 'LLAMACPP_RERANK', ?::jsonb,
                    'PUBLISHED', 50, 'ADMIN_VERIFIED', now(), ?, ?)
                """, modelProfileId, providerId, settingsJson(), admin, admin))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(audit, atLeastOnce()).write(isNull(), eq(admin),
                eq("AI_CAPABILITY_PROFILE_PUBLISHED"), eq("AI_MODEL_CAPABILITY_PROFILE"),
                any(), any());
        verify(audit, atLeastOnce()).write(isNull(), eq(admin),
                eq("AI_CAPABILITY_PROFILE_ROLLED_BACK"), eq("AI_MODEL_CAPABILITY_PROFILE"),
                any(), any());
    }

    @Test
    void fiftyCandidateEndpointUsesOneHttpCallAndReturnsAllFiniteScores() {
        UUID profileId = createDraft();
        profileService.test(admin, profileId, null);
        profileService.publish(admin, profileId, "promote");
        HTTP_CALLS.set(0);

        List<CapabilityTestDocument> documents = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            documents.add(new CapabilityTestDocument("candidate-" + index, "title-" + index,
                    "candidate text " + index));
        }
        CapabilityTestResponse response = profileService.test(admin, profileId,
                new CapabilityTestRequest("lane-b query", documents, 8, "50 candidates"));

        assertThat(response.outcome()).isEqualTo("SUCCESS");
        assertThat(response.candidateCount()).isEqualTo(50);
        assertThat(response.scores()).hasSize(50);
        assertThat(response.scores()).allSatisfy(score -> {
            assertThat(Double.isFinite(score.score())).isTrue();
            assertThat(score.id()).startsWith("candidate-");
        });
        assertThat(response.modelLatencyMs()).isNotNull();
        assertThat(response.totalMs()).isGreaterThanOrEqualTo(response.modelLatencyMs());
        assertThat(HTTP_CALLS).hasValue(1);
        assertThat(profileRepository.findById(profileId).orElseThrow().status())
                .isEqualTo(CapabilityProfileStatus.PUBLISHED);
    }

    private static UUID createDraft() {
        CapabilityProfileView view = profileService.create(admin, new CapabilityProfileRequest(
                modelProfileId, providerId, CapabilityType.RERANK,
                CapabilityProtocol.LLAMACPP_RERANK, readSettings(), "LOCAL_FILE", null));
        assertThat(view.status()).isEqualTo("DRAFT");
        return view.id();
    }

    private static JsonNode readSettings() {
        try {
            return JSON.readTree(settingsJson());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String settingsJson() {
        return """
                {"protocol":"LLAMACPP_RERANK","timeoutMs":3000,"maxCandidates":50,"batchSize":8,
                 "maxLength":2048,"scoreType":"sigmoid","topK":8,"healthPath":"/health",
                 "fallback":"java-heuristic"}
                """;
    }

    private static void startRerankServer() throws IOException {
        rerankServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rerankServer.setExecutor(Executors.newCachedThreadPool());
        rerankServer.createContext("/rerank", RerankCapabilityMigrationIntegrationTest::handleRerank);
        rerankServer.start();
        baseUrl = "http://127.0.0.1:" + rerankServer.getAddress().getPort();
    }

    private static void handleRerank(HttpExchange exchange) throws IOException {
        HTTP_CALLS.incrementAndGet();
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        JsonNode documents = request.path("documents");
        ArrayNode results = JSON.createArrayNode();
        for (int index = 0; index < documents.size(); index++) {
            String text = documents.get(index).asText();
            double score = text.contains("Paris") ? 0.95 : 0.05;
            results.addObject().put("index", index).put("relevance_score", score);
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("results", results));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
