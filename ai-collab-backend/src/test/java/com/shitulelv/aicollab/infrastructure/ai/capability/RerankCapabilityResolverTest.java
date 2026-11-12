package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankCapabilityResolverTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID GLOBAL_PROFILE = UUID.randomUUID();
    private final CapabilityAssignmentRepository assignments = mock(CapabilityAssignmentRepository.class);
    private final CapabilityProfileRepository profiles = mock(CapabilityProfileRepository.class);
    private final CapabilityProviderRepository providers = mock(CapabilityProviderRepository.class);
    private final RerankCapabilityResolver resolver = new RerankCapabilityResolver(assignments,
            profiles, providers, new RerankSettingsSchema(new ObjectMapper()));

    @Test
    void prefersProjectAssignmentOverGlobal() {
        UUID profileId = UUID.randomUUID();
        CapabilityAssignment project = assignment(CapabilityAssignmentScope.PROJECT, PROJECT_ID, profileId);
        CapabilityProfile profile = profile(profileId, CapabilityProfileStatus.PUBLISHED);
        CapabilityProvider provider = provider(profile.providerId());
        when(assignments.findEnabledProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.of(project));
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(providers.findById(profile.providerId())).thenReturn(Optional.of(provider));

        RerankCapabilityResolver.Resolution resolution = resolver.resolve(PROJECT_ID);

        assertThat(resolution.available()).isTrue();
        assertThat(resolution.configuration().assignment()).isEqualTo(project);
        verify(assignments, never()).findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK);
    }

    @Test
    void fallsBackToGlobalWhenProjectHasNoEnabledBinding() {
        CapabilityAssignment global = assignment(CapabilityAssignmentScope.GLOBAL, null, GLOBAL_PROFILE);
        CapabilityProfile profile = profile(GLOBAL_PROFILE, CapabilityProfileStatus.PUBLISHED);
        CapabilityProvider provider = provider(profile.providerId());
        when(assignments.findEnabledProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.empty());
        when(assignments.findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.of(global));
        when(profiles.findById(GLOBAL_PROFILE)).thenReturn(Optional.of(profile));
        when(providers.findById(profile.providerId())).thenReturn(Optional.of(provider));

        RerankCapabilityResolver.Resolution resolution = resolver.resolve(PROJECT_ID);

        assertThat(resolution.available()).isTrue();
        assertThat(resolution.configuration().profile().id()).isEqualTo(GLOBAL_PROFILE);
    }

    @Test
    void explicitProjectBindingFailureDoesNotBypassToGlobal() {
        UUID profileId = UUID.randomUUID();
        CapabilityAssignment project = assignment(CapabilityAssignmentScope.PROJECT, PROJECT_ID, profileId);
        CapabilityProfile disabled = profile(profileId, CapabilityProfileStatus.DISABLED);
        when(assignments.findEnabledProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.of(project));
        when(profiles.findById(profileId)).thenReturn(Optional.of(disabled));

        RerankCapabilityResolver.Resolution resolution = resolver.resolve(PROJECT_ID);

        assertThat(resolution.available()).isFalse();
        assertThat(resolution.fallbackReason()).isEqualTo("profile_not_published");
        verify(assignments, never()).findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK);
    }

    @Test
    void reportsProviderDisabled() {
        UUID profileId = UUID.randomUUID();
        CapabilityAssignment global = assignment(CapabilityAssignmentScope.GLOBAL, null, profileId);
        CapabilityProfile profile = profile(profileId, CapabilityProfileStatus.PUBLISHED);
        CapabilityProvider disabled = new CapabilityProvider(profile.providerId(), "local",
                ModelProviderType.LOCAL_CUSTOM, "LLAMACPP_RERANK", "http://127.0.0.1", "/rerank",
                "NONE", "local", "/health", null, false, "PUBLISHED", OffsetDateTime.now(),
                OffsetDateTime.now(), OffsetDateTime.now());
        when(assignments.findEnabledProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.empty());
        when(assignments.findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.of(global));
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(providers.findById(profile.providerId())).thenReturn(Optional.of(disabled));

        RerankCapabilityResolver.Resolution resolution = resolver.resolve(PROJECT_ID);

        assertThat(resolution.available()).isFalse();
        assertThat(resolution.fallbackReason()).isEqualTo("provider_disabled");
    }

    @Test
    void reportsNoAssignment() {
        when(assignments.findEnabledProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.empty());
        when(assignments.findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.empty());

        RerankCapabilityResolver.Resolution resolution = resolver.resolve(PROJECT_ID);

        assertThat(resolution.available()).isFalse();
        assertThat(resolution.fallbackReason()).isEqualTo("no_assignment");
    }

    private static CapabilityAssignment assignment(CapabilityAssignmentScope scope, UUID projectId,
            UUID profileId) {
        return new CapabilityAssignment(UUID.randomUUID(), CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK, scope, projectId, profileId, true,
                UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static CapabilityProfile profile(UUID id, CapabilityProfileStatus status) {
        return new CapabilityProfile(id, UUID.randomUUID(), UUID.randomUUID(), CapabilityType.RERANK,
                "LLAMACPP_RERANK", validSettings(), status, 1, "ADMIN_VERIFIED",
                status == CapabilityProfileStatus.DRAFT ? null : OffsetDateTime.now(),
                status == CapabilityProfileStatus.DRAFT ? null : UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static CapabilityProvider provider(UUID id) {
        return new CapabilityProvider(id, "local", ModelProviderType.LOCAL_CUSTOM,
                "LLAMACPP_RERANK", "http://127.0.0.1", "/rerank", "NONE", "local", "/health",
                null, true, "PUBLISHED", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    static String validSettings() {
        return """
                {"protocol":"LLAMACPP_RERANK","timeoutMs":3000,"maxCandidates":50,"batchSize":8,
                 "maxLength":2048,"scoreType":"sigmoid","topK":8,"healthPath":"/health",
                 "fallback":"java-heuristic"}
                """;
    }
}
