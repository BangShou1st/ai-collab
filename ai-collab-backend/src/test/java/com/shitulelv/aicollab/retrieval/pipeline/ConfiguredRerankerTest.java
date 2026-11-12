package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityAssignment;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityAssignmentScope;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfile;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfileStatus;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProvider;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityPurpose;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityType;
import com.shitulelv.aicollab.infrastructure.ai.capability.LlamaCppRerankAdapter;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCallResult;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankDocument;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankScore;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;
import com.shitulelv.aicollab.infrastructure.ai.capability.ResolvedRerankConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfiguredRerankerTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private final RerankCapabilityResolver resolver = mock(RerankCapabilityResolver.class);
    private final LlamaCppRerankAdapter adapter = mock(LlamaCppRerankAdapter.class);
    private final ModelSecretCipher secrets = mock(ModelSecretCipher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConfiguredReranker reranker = new ConfiguredReranker(resolver, adapter,
            new SemanticHeuristicReranker(), secrets, registry);

    @Test
    void usesConfiguredScoresAndStableTieBreakers() {
        ResolvedRerankConfiguration configuration = configuration();
        when(resolver.resolve(PROJECT_ID)).thenReturn(
                new RerankCapabilityResolver.Resolution(configuration, null));
        when(adapter.rerank(any(), any(), anyString(), anyList(), any(), isNull()))
                .thenReturn(RerankCallResult.success(List.of(
                        new RerankScore(0, "a", 0.2),
                        new RerankScore(1, "b", 0.9),
                        new RerankScore(2, "c", 0.2)), 12, 15, 3, 3));

        RetrievalCandidate first = candidate("a", 0.1);
        RetrievalCandidate second = candidate("b", 0.1);
        RetrievalCandidate third = candidate("c", 0.1);
        List<RetrievalCandidate> result = reranker.forProject(PROJECT_ID)
                .rerank("q", List.of(first, second, third), 3);

        assertThat(result).extracting(RetrievalCandidate::id)
                .containsExactly(second.id(), first.id(), third.id());
        assertThat(result).extracting(RetrievalCandidate::rerankScore)
                .containsExactly(0.9, 0.2, 0.2);
        assertThat(result.get(1).metadata()).containsEntry("rerankSource", ConfiguredReranker.NAME);
        verify(resolver).resolve(PROJECT_ID);
        verify(adapter).rerank(any(), any(), anyString(), anyList(), any(), isNull());
    }

    @Test
    void fallsBackToHeuristicWhenNoAssignment() {
        when(resolver.resolve(PROJECT_ID)).thenReturn(
                RerankCapabilityResolver.Resolution.unavailable("no_assignment"));
        RetrievalCandidate relevant = new RetrievalCandidate(UUID.randomUUID(), UUID.randomUUID(),
                "f.md", "France capital", "Paris is the capital of France.", "h1", java.util.Map.of(),
                1, 0.8, 1, 0.7, 0.5, null, null);
        RetrievalCandidate irrelevant = new RetrievalCandidate(UUID.randomUUID(), UUID.randomUUID(),
                "f.md", "Fruit", "Bananas are yellow.", "h2", java.util.Map.of(),
                2, 0.7, 2, 0.6, 0.4, null, null);

        List<RetrievalCandidate> result = reranker.forProject(PROJECT_ID)
                .rerank("capital France", List.of(irrelevant, relevant), 2);

        assertThat(result).extracting(RetrievalCandidate::id)
                .containsExactly(relevant.id(), irrelevant.id());
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.metadata()).containsEntry("rerankerDegraded", true);
            assertThat(candidate.metadata()).containsEntry("rerankFallbackReason", "no_assignment");
        });
        assertThat(registry.find("rerank.fallback").tag("reason", "no_assignment")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void fallsBackWhenAdapterReturnsInvalidResponse() {
        when(resolver.resolve(PROJECT_ID)).thenReturn(
                new RerankCapabilityResolver.Resolution(configuration(), null));
        when(adapter.rerank(any(), any(), anyString(), anyList(), any(), isNull()))
                .thenReturn(RerankCallResult.failure("INVALID_RESPONSE", "index_duplicate",
                        5, 8, 1, 1));

        List<RetrievalCandidate> result = reranker.forProject(PROJECT_ID)
                .rerank("q", List.of(candidate("a", 0.2)), 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).metadata()).containsEntry("rerankerDegraded", true)
                .containsEntry("rerankFallbackReason", "index_duplicate");
        assertThat(registry.find("rerank.fallback").tag("reason", "index_duplicate")
                .counter().count()).isEqualTo(1.0);
    }

    private ResolvedRerankConfiguration configuration() {
        UUID profileId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        CapabilityAssignment assignment = new CapabilityAssignment(UUID.randomUUID(),
                CapabilityType.RERANK, CapabilityPurpose.KNOWLEDGE_RERANK,
                CapabilityAssignmentScope.PROJECT, PROJECT_ID, profileId, true, UUID.randomUUID(),
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now());
        CapabilityProfile profile = new CapabilityProfile(profileId, UUID.randomUUID(), providerId,
                CapabilityType.RERANK, "LLAMACPP_RERANK", settingsJson(),
                CapabilityProfileStatus.PUBLISHED, 1, "ADMIN_VERIFIED", OffsetDateTime.now(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(),
                OffsetDateTime.now());
        CapabilityProvider provider = new CapabilityProvider(providerId, "local",
                ModelProviderType.LOCAL_CUSTOM, "LLAMACPP_RERANK", "http://127.0.0.1", "/rerank",
                "NONE", "local", "/health", null, true, "PUBLISHED", OffsetDateTime.now(),
                OffsetDateTime.now(), OffsetDateTime.now());
        RerankSettings settings = new RerankSettings("LLAMACPP_RERANK", 3000, 50, 8, 2048,
                "sigmoid", 8, "/health", "java-heuristic");
        return new ResolvedRerankConfiguration(assignment, profile, provider, settings);
    }

    private static String settingsJson() {
        return """
                {"protocol":"LLAMACPP_RERANK","timeoutMs":3000,"maxCandidates":50,"batchSize":8,
                 "maxLength":2048,"scoreType":"sigmoid","topK":8,"healthPath":"/health",
                 "fallback":"java-heuristic"}
                """;
    }

    private static RetrievalCandidate candidate(String id, double rrf) {
        return new RetrievalCandidate(UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.randomUUID(), "doc.md", "title", "content " + id, "hash-" + id, java.util.Map.of(),
                1, 0.5, 1, 0.5, rrf, null, null);
    }
}
