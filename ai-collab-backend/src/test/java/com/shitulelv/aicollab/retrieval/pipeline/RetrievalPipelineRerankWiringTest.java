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
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankModeRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalPipelineRerankWiringTest {

    private static final int CANDIDATE_COUNT = 20;
    private static final int INLINE_WINDOW = 8;

    @Test
    void defaultFlagsKeepLexicalHeuristicAndDoNotTouchConfiguredReranker() {
        UUID projectId = UUID.randomUUID();
        ConfiguredReranker configured = mock(ConfiguredReranker.class);
        RetrievalPipeline pipeline = pipeline(candidates(), configured, false, false,
                RerankMode.JAVA, null);

        RetrievalResult result = pipeline.execute(projectId, "query", List.of(UUID.randomUUID()),
                config(CANDIDATE_COUNT));

        assertThat(result.candidates()).hasSize(CANDIDATE_COUNT);
        assertThat(result.trace().rerankerName()).isEqualTo(SemanticHeuristicReranker.NAME);
        verifyNoInteractions(configured);
    }

    @Test
    void configuredSwitchAndTop8WindowRerankOnlyHeadAndKeepTailOrder() {
        UUID projectId = UUID.randomUUID();
        List<RetrievalCandidate> candidates = candidates();
        RerankCapabilityResolver resolver = mock(RerankCapabilityResolver.class);
        LlamaCppRerankAdapter adapter = mock(LlamaCppRerankAdapter.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        AtomicInteger adapterInputSize = new AtomicInteger();
        when(resolver.resolve(projectId)).thenReturn(
                new RerankCapabilityResolver.Resolution(configuration(projectId), null));
        when(adapter.rerank(any(), any(), anyString(), anyList(), any(), isNull()))
                .thenAnswer(invocation -> {
                    List<RerankDocument> documents = invocation.getArgument(3);
                    adapterInputSize.set(documents.size());
                    List<RerankScore> scores = IntStream.range(0, documents.size())
                            .mapToObj(index -> new RerankScore(index, documents.get(index).id(),
                                    index))
                            .toList();
                    return RerankCallResult.success(scores, 1, 2, documents.size(), documents.size());
                });
        ConfiguredReranker configured = new ConfiguredReranker(resolver, adapter,
                new SemanticHeuristicReranker(), secrets, new SimpleMeterRegistry());
        RetrievalPipeline pipeline = pipeline(candidates, configured, true, true,
                RerankMode.JAVA, null);

        RetrievalResult result = pipeline.execute(projectId, "query", List.of(UUID.randomUUID()),
                config(10));

        assertThat(adapterInputSize.get()).isEqualTo(INLINE_WINDOW);
        List<UUID> expected = new ArrayList<>();
        for (int index = INLINE_WINDOW - 1; index >= 0; index--) {
            expected.add(candidates.get(index).id());
        }
        expected.add(candidates.get(INLINE_WINDOW).id());
        expected.add(candidates.get(INLINE_WINDOW + 1).id());
        assertThat(result.candidates()).extracting(RetrievalCandidate::id)
                .containsExactlyElementsOf(expected);
        assertThat(result.trace().rerankerName()).isEqualTo(ConfiguredReranker.NAME);
        verify(resolver).resolve(projectId);
    }

    @Test
    void shadowModeStillSendsAllCandidatesToActiveReranker() {
        UUID projectId = UUID.randomUUID();
        List<RetrievalCandidate> candidates = candidates();
        RecordingReranker recording = new RecordingReranker();
        ConfiguredReranker configured = mock(ConfiguredReranker.class);
        when(configured.forProject(projectId)).thenReturn(recording);
        RetrievalPipeline pipeline = pipeline(candidates, configured, true, true,
                RerankMode.SHADOW, null);

        pipeline.execute(projectId, "query", List.of(UUID.randomUUID()), config(CANDIDATE_COUNT));

        assertThat(recording.candidateCount.get()).isEqualTo(CANDIDATE_COUNT);
        assertThat(recording.requestedTopN.get()).isEqualTo(CANDIDATE_COUNT);
    }

    @Test
    void pythonModeUsesTop8WindowWithoutConfiguredReranker() {
        UUID projectId = UUID.randomUUID();
        List<RetrievalCandidate> candidates = candidates();
        RecordingReranker recording = new RecordingReranker();
        RerankModeRouter router = mock(RerankModeRouter.class);
        when(router.wrap(any(Reranker.class), eq(projectId))).thenReturn(recording);
        RetrievalPipeline pipeline = pipeline(candidates, null, false, true,
                RerankMode.PYTHON, router);

        pipeline.execute(projectId, "query", List.of(UUID.randomUUID()), config(10));

        assertThat(recording.candidateCount.get()).isEqualTo(INLINE_WINDOW);
        assertThat(recording.requestedTopN.get()).isEqualTo(INLINE_WINDOW);
        verify(router).wrap(any(Reranker.class), eq(projectId));
        verify(router, never()).wrap(any(Reranker.class));
    }

    private static RetrievalPipeline pipeline(
            List<RetrievalCandidate> candidates,
            ConfiguredReranker configured,
            boolean configuredEnabled,
            boolean inlineTop8,
            RerankMode mode,
            RerankModeRouter router) {
        DenseRetriever dense = mock(DenseRetriever.class);
        LexicalRetriever lexical = mock(LexicalRetriever.class);
        when(dense.retrieve(any(), anyString(), anyList(), anyInt()))
                .thenReturn(new ArrayList<>(candidates));
        when(lexical.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(List.of());
        return new RetrievalPipeline(
                dense,
                lexical,
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new SemanticHeuristicReranker(),
                new PassThroughReranker(),
                new NeuralModelReranker(),
                router,
                configured,
                configuredEnabled,
                inlineTop8,
                mode);
    }

    private static RetrievalPipeline.PipelineConfig config(int finalTopN) {
        return new RetrievalPipeline.PipelineConfig(
                CANDIDATE_COUNT, CANDIDATE_COUNT, CANDIDATE_COUNT, finalTopN,
                false, 1, false);
    }

    private static List<RetrievalCandidate> candidates() {
        UUID documentId = UUID.randomUUID();
        return IntStream.range(0, CANDIDATE_COUNT)
                .mapToObj(index -> new RetrievalCandidate(
                        UUID.nameUUIDFromBytes(("chunk-" + index).getBytes(StandardCharsets.UTF_8)),
                        documentId,
                        "doc.md",
                        "heading-" + index,
                        "content-" + index,
                        "hash-" + index,
                        Map.of(),
                        index + 1,
                        1.0 - index * 0.01,
                        null,
                        null,
                        0.0,
                        null,
                        null))
                .toList();
    }

    private static ResolvedRerankConfiguration configuration(UUID projectId) {
        UUID profileId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        CapabilityAssignment assignment = new CapabilityAssignment(UUID.randomUUID(),
                CapabilityType.RERANK, CapabilityPurpose.KNOWLEDGE_RERANK,
                CapabilityAssignmentScope.PROJECT, projectId, profileId, true, UUID.randomUUID(),
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

    private static final class RecordingReranker implements Reranker {
        private final AtomicInteger candidateCount = new AtomicInteger();
        private final AtomicInteger requestedTopN = new AtomicInteger();

        @Override
        public String name() {
            return ConfiguredReranker.NAME;
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates,
                int topN) {
            candidateCount.set(candidates.size());
            requestedTopN.set(topN);
            return candidates.size() > topN ? new ArrayList<>(candidates.subList(0, topN))
                    : new ArrayList<>(candidates);
        }
    }
}
