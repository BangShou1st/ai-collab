package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.agent.job.AiJobStatus;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfile;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfileStatus;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProvider;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityType;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;
import com.shitulelv.aicollab.infrastructure.ai.capability.ResolvedRerankConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 租借：系统级 EMBEDDING（契约 §12.3 R1-3/R1-4）与 RERANK / LLAMACPP_RERANK（§12.2 P1-R4/P1-R5）。
 * 纯单元测试，不依赖 Docker。
 */
class P2NonChatLeaseTest {

    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_PROJECT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID TASK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_TASK = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID EMBEDDING_CONFIG = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID RERANK_PROVIDER = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID MODEL_PROFILE = UUID.fromString("99999999-9999-9999-9999-999999999999");
    /** 明文哨兵：拼接构造。 */
    private static final String SENTINEL = String.join("-", "sk", "p2", "lease", "c0ffee5eed");
    private static final String CIPHERTEXT = "enc:opaque-p2";

    private AiJobRepository jobs;
    private CredentialLeaseRepository leases;
    private ProjectAccessGuard access;
    private SystemEmbeddingConfigRepository embeddingConfigs;
    private EmbeddingEndpointPolicy endpoints;
    private RerankCapabilityResolver rerankResolver;
    private ModelProfileRepository modelProfiles;
    private ModelSecretCipher secrets;
    private CredentialLeaseService service;

    @BeforeEach
    void setUp() {
        jobs = mock(AiJobRepository.class);
        leases = mock(CredentialLeaseRepository.class);
        access = mock(ProjectAccessGuard.class);
        embeddingConfigs = mock(SystemEmbeddingConfigRepository.class);
        endpoints = mock(EmbeddingEndpointPolicy.class);
        rerankResolver = mock(RerankCapabilityResolver.class);
        modelProfiles = mock(ModelProfileRepository.class);
        secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt(CIPHERTEXT)).thenReturn(SENTINEL);
        when(endpoints.require(anyString(), anyString(), anyString())).thenReturn(URI.create("https://e.test/v1"));
        NonChatLeaseRouter router = new NonChatLeaseRouter(embeddingConfigs, endpoints, rerankResolver,
                modelProfiles, secrets);
        service = new CredentialLeaseService(mock(UserAiProviderService.class), secrets, access, jobs, leases,
                null, router, CLOCK);
    }

    // ---------- 枚举 ----------

    @Test
    void rerankAndEmbeddingAreNonChatPurposes() {
        assertThat(LeasePurpose.RERANK.routingPurpose()).isEmpty();
        assertThat(LeasePurpose.EMBEDDING.routingPurpose()).isEmpty();
        assertThat(LeasePurpose.parse("RERANK")).contains(LeasePurpose.RERANK);
    }

    // ---------- 系统级 EMBEDDING：放行路径 ----------

    @Test
    void indexDocumentTaskLeasesEmbeddingWithoutUserIdAndSkipsMembership() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 4, PROJECT)));
        when(embeddingConfigs.findActive()).thenReturn(Optional.of(embedding("OPENAI_COMPATIBLE", 1024, 64)));

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 4L, PROJECT));

        assertThat(grant.protocol()).isEqualTo("OPENAI_EMBEDDINGS");
        assertThat(grant.apiKey()).isEqualTo(SENTINEL);
        assertThat(grant.params()).containsEntry("dimensions", 1024).containsEntry("batchSize", 64);
        assertThat(grant.capabilities()).containsExactly("EMBEDDING");
        assertThat(grant.toString()).doesNotContain(SENTINEL);
        verifyNoInteractions(access);
        ArgumentCaptor<CredentialLeaseRepository.LeaseRecord> row =
                ArgumentCaptor.forClass(CredentialLeaseRepository.LeaseRecord.class);
        verify(leases).insert(row.capture());
        assertThat(row.getValue().purpose()).isEqualTo(LeasePurpose.EMBEDDING);
        assertThat(row.getValue().userId()).isNull();
        assertThat(row.getValue().taskId()).isEqualTo(TASK);
        assertThat(row.getValue().providerId()).isEqualTo(EMBEDDING_CONFIG);
        assertThat(row.getValue().toString()).doesNotContain(SENTINEL).doesNotContain(CIPHERTEXT);
    }

    @Test
    void reindexGenerationTaskLeasesWithoutProjectAndOllamaDimensionsAreNull() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("REINDEX_GENERATION", AiJobStatus.LEASED, 2, PROJECT)));
        SystemEmbeddingConfig ollama = new SystemEmbeddingConfig(EMBEDDING_CONFIG, "OLLAMA",
                "http://127.0.0.1:11434", "/v1/embeddings", null, "qwen3-embedding:0.6b", 1024, 16, "fp", true,
                OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK));
        when(embeddingConfigs.findActive()).thenReturn(Optional.of(ollama));

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 2L, null));

        assertThat(grant.protocol()).isEqualTo("OPENAI_EMBEDDINGS");
        assertThat(grant.apiKey()).isNull();
        assertThat(grant.params()).containsEntry("dimensions", null).containsEntry("batchSize", 16);
    }

    @Test
    void geminiUsesNativeProtocolAndCapsBatchAt100() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 1, PROJECT)));
        when(embeddingConfigs.findActive()).thenReturn(Optional.of(embedding("GEMINI", 768, 256)));

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, PROJECT, TASK, null),
                indexClaims(TASK, 1L, PROJECT));

        assertThat(grant.protocol()).isEqualTo("GEMINI_EMBEDDINGS");
        assertThat(grant.params()).containsEntry("dimensions", 768).containsEntry("batchSize", 100);
        assertThat(NonChatLeaseRouter.effectiveBatchSize("GEMINI", 0)).isEqualTo(32);
    }

    // ---------- 系统级 EMBEDDING：越权（全部 403，不签发、不落审计） ----------

    @Test
    void tidMismatchIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 1, PROJECT)));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(OTHER_TASK, 1L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    @Test
    void staleEpochIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 5, PROJECT)));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 4L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    @Test
    void jobNotLeasedOrMissingIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.SUCCEEDED, 1, PROJECT)));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        when(jobs.find(TASK)).thenReturn(Optional.empty());
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    @Test
    void wrongTaskTypeIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("MEMORY_EXTRACTION", AiJobStatus.RUNNING, 1, PROJECT)));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        when(jobs.find(TASK)).thenReturn(Optional.of(job("DELETE_DOCUMENT_INDEX", AiJobStatus.RUNNING, 1, PROJECT)));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    @Test
    void indexDocumentPidMismatchIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 1, PROJECT)));
        // 令牌 pid 不是该 job 的项目
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, OTHER_PROJECT)));
        // 令牌没有 pid
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, null)));
        // 请求体 projectId 与令牌不一致
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, OTHER_PROJECT, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    @Test
    void purpNotContainingEmbeddingIsForbidden() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 1, PROJECT)));
        InternalAiClaims noEmbedding = new InternalAiClaims(null, PROJECT, Set.of("RERANK"), TASK, 1L,
                Set.of("task:" + TASK, "credentials:lease", "usage:report"));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null), noEmbedding));
    }

    @Test
    void withoutTaskIdUserIdIsStillRequired() {
        InternalAiClaims searchClaims = new InternalAiClaims(USER, PROJECT, Set.of("EMBEDDING", "RERANK"), null,
                null, Set.of("knowledge:search", "credentials:lease", "usage:report"));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, PROJECT, null, null), searchClaims));
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, USER, null, null, null), searchClaims));
        verify(leases, never()).insert(any());
    }

    @Test
    void searchTokenEmbeddingLeaseRequiresMembership() {
        InternalAiClaims searchClaims = new InternalAiClaims(USER, PROJECT, Set.of("EMBEDDING", "RERANK"), null,
                null, Set.of("knowledge:search", "credentials:lease", "usage:report"));
        when(embeddingConfigs.findActive()).thenReturn(Optional.of(embedding("OPENAI_COMPATIBLE", 1024, 32)));

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, USER, PROJECT, null, null), searchClaims);

        assertThat(grant.protocol()).isEqualTo("OPENAI_EMBEDDINGS");
        verify(access).requireMember(PROJECT, USER);
    }

    @Test
    void missingOrDisabledEmbeddingConfigIsUnavailable() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job("INDEX_DOCUMENT", AiJobStatus.RUNNING, 1, PROJECT)));
        when(embeddingConfigs.findActive()).thenReturn(Optional.empty());
        assertUnavailable(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        SystemEmbeddingConfig disabled = new SystemEmbeddingConfig(EMBEDDING_CONFIG, "OPENAI_COMPATIBLE",
                "https://e.test", "/v1/embeddings", CIPHERTEXT, "m", 1024, 32, "fp", false,
                OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK));
        when(embeddingConfigs.findActive()).thenReturn(Optional.of(disabled));
        assertUnavailable(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.EMBEDDING, null, null, TASK, null),
                indexClaims(TASK, 1L, PROJECT)));
        verify(leases, never()).insert(any());
    }

    // ---------- RERANK / LLAMACPP_RERANK ----------

    @Test
    void rerankLeaseUsesInlineParamsAndNullKeyWhenNoAuth() {
        stubRerank("NONE", null, 5000 * 3, 16);
        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, USER, PROJECT, null, null), searchClaims());

        assertThat(grant.protocol()).isEqualTo("LLAMACPP_RERANK");
        assertThat(grant.apiKey()).isNull();
        assertThat(grant.model()).isEqualTo("bge-reranker-v2-m3");
        assertThat(grant.capabilities()).containsExactly("RERANK");
        // timeoutMs 被夹到 inline 上限 5000；maxCandidates 原值
        assertThat(grant.params()).containsEntry("maxCandidates", 16).containsEntry("timeoutMs", 5000);
        verify(rerankResolver).resolve(PROJECT);
        verify(access).requireMember(PROJECT, USER);
    }

    @Test
    void rerankLeaseWithBearerAuthCarriesKey() {
        stubRerank("BEARER", CIPHERTEXT, 1200, 8);
        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, USER, PROJECT, null, null), searchClaims());

        assertThat(grant.apiKey()).isEqualTo(SENTINEL);
        assertThat(grant.params()).containsEntry("timeoutMs", 1200).containsEntry("maxCandidates", 8);
        assertThat(grant.toString()).doesNotContain(SENTINEL);
    }

    @Test
    void rerankNonBearerAuthOrMissingKeyIsUnavailable() {
        stubRerank("API_KEY", CIPHERTEXT, 1200, 8);
        assertUnavailable(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, USER, PROJECT, null, null), searchClaims()));
        stubRerank("BEARER", null, 1200, 8);
        assertUnavailable(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, USER, PROJECT, null, null), searchClaims()));
        verify(leases, never()).insert(any());
    }

    @Test
    void rerankUnresolvedIsUnavailable() {
        when(rerankResolver.resolve(PROJECT)).thenReturn(RerankCapabilityResolver.Resolution.unavailable("no_assignment"));
        assertUnavailable(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, USER, PROJECT, null, null), searchClaims()));
    }

    @Test
    void rerankPurposeNotGrantedIsForbidden() {
        InternalAiClaims indexOnly = indexClaims(TASK, 1L, PROJECT);
        assertForbidden(() -> service.lease(
                new CredentialLeaseService.Request(LeasePurpose.RERANK, null, PROJECT, TASK, null), indexOnly));
        verifyNoInteractions(rerankResolver);
    }

    // ---------- helpers ----------

    private void stubRerank(String authType, String encryptedKey, int timeoutMs, int maxCandidates) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        CapabilityProvider provider = new CapabilityProvider(RERANK_PROVIDER, "reranker",
                ModelProviderType.LOCAL_CUSTOM, "LLAMACPP_RERANK", "http://reranker.internal:8081", "/v1/rerank",
                authType, "INTERNAL", "/health", encryptedKey, true, "PUBLISHED", now, now, now);
        CapabilityProfile profile = new CapabilityProfile(UUID.randomUUID(), MODEL_PROFILE, RERANK_PROVIDER,
                CapabilityType.RERANK, "LLAMACPP_RERANK", "{}", CapabilityProfileStatus.PUBLISHED, 1, "ADMIN",
                now, USER, USER, USER, now, now);
        RerankSettings settings = new RerankSettings("LLAMACPP_RERANK", timeoutMs, maxCandidates, 8, 1024,
                "RELEVANCE", 8, "/health", "NONE");
        when(rerankResolver.resolve(PROJECT)).thenReturn(new RerankCapabilityResolver.Resolution(
                new ResolvedRerankConfiguration(null, profile, provider, settings), null));
        ModelProfile model = mock(ModelProfile.class);
        when(model.modelName()).thenReturn("bge-reranker-v2-m3");
        when(modelProfiles.find(MODEL_PROFILE)).thenReturn(Optional.of(model));
    }

    private static SystemEmbeddingConfig embedding(String provider, int dimensions, int batchSize) {
        return new SystemEmbeddingConfig(EMBEDDING_CONFIG, provider, "https://e.test", "/v1/embeddings", CIPHERTEXT,
                "embed-model", dimensions, batchSize, "fp", true, OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK));
    }

    private static InternalAiClaims indexClaims(UUID taskId, Long epoch, UUID projectId) {
        return new InternalAiClaims(null, projectId, Set.of("EMBEDDING"), taskId, epoch,
                Set.of("task:" + taskId, "credentials:lease", "usage:report"));
    }

    private static InternalAiClaims searchClaims() {
        return new InternalAiClaims(USER, PROJECT, Set.of("EMBEDDING", "RERANK"), null, null,
                Set.of("knowledge:search", "credentials:lease", "usage:report"));
    }

    private static AiJobRecord job(String type, AiJobStatus status, long epoch, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new AiJobRecord(TASK, projectId, type, status, 1, "{}", 3, 1, epoch,
                UUID.randomUUID(), now.plusSeconds(60), null, null, null, null, now, now, 1);
    }

    private static void assertForbidden(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(CredentialLeaseException.class,
                failure -> assertThat(failure.kind()).isEqualTo(CredentialLeaseException.Kind.FORBIDDEN));
    }

    private static void assertUnavailable(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(CredentialLeaseException.class,
                failure -> assertThat(failure.kind()).isEqualTo(CredentialLeaseException.Kind.UNAVAILABLE));
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new HashMap<>(source);
    }
}
