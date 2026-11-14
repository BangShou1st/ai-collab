package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestDocument;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestResponse;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileSource;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityProfileServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID MODEL_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private final CapabilityProfileRepository profiles = mock(CapabilityProfileRepository.class);
    private final CapabilityProviderRepository providers = mock(CapabilityProviderRepository.class);
    private final CapabilityAssignmentRepository assignments = mock(CapabilityAssignmentRepository.class);
    private final ModelProfileRepository modelProfiles = mock(ModelProfileRepository.class);
    private final LlamaCppRerankAdapter adapter = mock(LlamaCppRerankAdapter.class);
    private final ModelSecretCipher secrets = mock(ModelSecretCipher.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CapabilityProfileService service = new CapabilityProfileService(profiles, providers,
            assignments, modelProfiles, new RerankSettingsSchema(objectMapper), adapter, secrets, audit,
            objectMapper);

    @Test
    void publishTransitionsOnlyTestedAndRepointsAssignments() {
        UUID profileId = UUID.randomUUID();
        CapabilityProfile tested = profile(profileId, 2, CapabilityProfileStatus.TESTED);
        CapabilityProfile previous = profile(UUID.randomUUID(), 1, CapabilityProfileStatus.PUBLISHED);
        CapabilityProfile published = profile(profileId, 2, CapabilityProfileStatus.PUBLISHED);
        when(profiles.findByIdForUpdate(profileId)).thenReturn(Optional.of(tested));
        when(profiles.findPublishedForUpdate(MODEL_ID, CapabilityType.RERANK))
                .thenReturn(Optional.of(previous));
        when(profiles.demotePublished(previous.id(), ADMIN)).thenReturn(true);
        when(profiles.markPublished(profileId, ADMIN)).thenReturn(true);
        when(profiles.findById(profileId)).thenReturn(Optional.of(published));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(modelProfiles.find(MODEL_ID)).thenReturn(Optional.of(modelProfile()));
        when(assignments.repointProfile(previous.id(), profileId)).thenReturn(2);

        service.publish(ADMIN, profileId, "canary");

        verify(profiles).demotePublished(previous.id(), ADMIN);
        verify(profiles).markPublished(profileId, ADMIN);
        verify(assignments).repointProfile(previous.id(), profileId);
        verify(audit).write(isNull(), eq(ADMIN), eq("AI_CAPABILITY_PROFILE_PUBLISHED"),
                eq("AI_MODEL_CAPABILITY_PROFILE"), eq(profileId), anyMap());
    }

    @Test
    void rejectsPublishingDraft() {
        UUID profileId = UUID.randomUUID();
        when(profiles.findByIdForUpdate(profileId))
                .thenReturn(Optional.of(profile(profileId, 1, CapabilityProfileStatus.DRAFT)));

        assertThatThrownBy(() -> service.publish(ADMIN, profileId, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.CAPABILITY_PROFILE_CONFLICT));
        verify(profiles, never()).markPublished(any(), any());
    }

    @Test
    void rollbackRestoresPreviousVerifiedVersionAndRepointsAssignments() {
        UUID currentId = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();
        CapabilityProfile current = profile(currentId, 2, CapabilityProfileStatus.PUBLISHED);
        CapabilityProfile previous = profile(previousId, 1, CapabilityProfileStatus.DISABLED);
        CapabilityProfile restored = profile(previousId, 1, CapabilityProfileStatus.PUBLISHED);
        when(profiles.findByIdForUpdate(currentId)).thenReturn(Optional.of(current));
        when(profiles.findPreviousVerifiedForUpdate(MODEL_ID, CapabilityType.RERANK, 2))
                .thenReturn(Optional.of(previous));
        when(profiles.demotePublished(currentId, ADMIN)).thenReturn(true);
        when(profiles.publishRollbackTarget(previousId, ADMIN)).thenReturn(true);
        when(profiles.findById(previousId)).thenReturn(Optional.of(restored));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(modelProfiles.find(MODEL_ID)).thenReturn(Optional.of(modelProfile()));
        when(assignments.repointProfile(currentId, previousId)).thenReturn(1);

        service.rollback(ADMIN, currentId, "rollback");

        verify(profiles).publishRollbackTarget(previousId, ADMIN);
        verify(assignments).repointProfile(currentId, previousId);
        verify(audit).write(isNull(), eq(ADMIN), eq("AI_CAPABILITY_PROFILE_ROLLED_BACK"),
                eq("AI_MODEL_CAPABILITY_PROFILE"), eq(previousId), anyMap());
    }

    @Test
    void failedTestDoesNotEnterTested() {
        UUID profileId = UUID.randomUUID();
        CapabilityProfile draft = profile(profileId, 1, CapabilityProfileStatus.DRAFT);
        when(profiles.findById(profileId)).thenReturn(Optional.of(draft));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(adapter.rerank(any(), any(), anyString(), anyList(), any(), isNull()))
                .thenReturn(RerankCallResult.failure("INVALID_RESPONSE", "index_duplicate",
                        null, 20, 2, 2));

        CapabilityTestResponse response = service.test(ADMIN, profileId, null);

        assertThat(response.outcome()).isEqualTo("INVALID_RESPONSE");
        assertThat(response.reason()).isEqualTo("index_duplicate");
        assertThat(response.profileStatus()).isEqualTo("DRAFT");
        verify(profiles, never()).markTested(any(), any());
    }

    @Test
    void successfulDefaultSmokeTestEntersTested() {
        UUID profileId = UUID.randomUUID();
        CapabilityProfile draft = profile(profileId, 1, CapabilityProfileStatus.DRAFT);
        CapabilityProfile tested = profile(profileId, 1, CapabilityProfileStatus.TESTED);
        when(profiles.findById(profileId)).thenReturn(Optional.of(draft), Optional.of(tested));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(modelProfiles.find(MODEL_ID)).thenReturn(Optional.of(modelProfile()));
        when(profiles.markTested(profileId, ADMIN)).thenReturn(true);
        when(adapter.rerank(any(), any(), anyString(), anyList(), any(), isNull()))
                .thenReturn(RerankCallResult.success(List.of(
                        new RerankScore(0, "relevant", 0.9),
                        new RerankScore(1, "irrelevant", 0.1)), 15, 20, 2, 2));

        CapabilityTestResponse response = service.test(ADMIN, profileId, null);

        assertThat(response.outcome()).isEqualTo("SUCCESS");
        assertThat(response.scores()).hasSize(2);
        assertThat(response.scores()).allSatisfy(score -> assertThat(Double.isFinite(score.score())).isTrue());
        assertThat(response.profileStatus()).isEqualTo("TESTED");
        verify(profiles).markTested(profileId, ADMIN);
    }

    @Test
    void customFiftyCandidateMeasurementCallsAdapterOnce() {
        UUID profileId = UUID.randomUUID();
        CapabilityProfile published = profile(profileId, 1, CapabilityProfileStatus.PUBLISHED);
        List<CapabilityTestDocument> documents = new ArrayList<>();
        List<RerankScore> scores = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            documents.add(new CapabilityTestDocument("doc-" + index, "title-" + index,
                    "text-" + index));
            scores.add(new RerankScore(index, "doc-" + index, 1.0 / (index + 1)));
        }
        when(profiles.findById(profileId)).thenReturn(Optional.of(published));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(adapter.rerank(any(), any(), eq("q"), anyList(), any(), isNull()))
                .thenReturn(RerankCallResult.success(scores, 30, 40, 50, 8));

        CapabilityTestResponse response = service.test(ADMIN, profileId,
                new CapabilityTestRequest("q", documents, 8, "lane-b"));

        assertThat(response.outcome()).isEqualTo("SUCCESS");
        assertThat(response.scores()).hasSize(50);
        assertThat(response.candidateCount()).isEqualTo(50);
        assertThat(response.profileStatus()).isEqualTo("PUBLISHED");
        verify(adapter, times(1)).rerank(any(), any(), eq("q"), anyList(), any(), isNull());
        verify(profiles, never()).markTested(any(), any());
    }

    private static CapabilityProfile profile(UUID id, int version, CapabilityProfileStatus status) {
        return new CapabilityProfile(id, MODEL_ID, PROVIDER_ID, CapabilityType.RERANK,
                "LLAMACPP_RERANK", settingsJson(), status, version, "ADMIN_VERIFIED",
                status == CapabilityProfileStatus.DRAFT ? null : OffsetDateTime.now(),
                status == CapabilityProfileStatus.DRAFT ? null : ADMIN,
                ADMIN, ADMIN, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static CapabilityProvider provider() {
        return new CapabilityProvider(PROVIDER_ID, "local-rerank", ModelProviderType.LOCAL_CUSTOM,
                "LLAMACPP_RERANK", "http://127.0.0.1", "/rerank", "NONE", "local", "/health",
                null, true, "PUBLISHED", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private static ModelProfile modelProfile() {
        return new ModelProfile(MODEL_ID, "LOCAL_CUSTOM", "http://127.0.0.1", "/rerank",
                "reranker-model", 8192, null, false, null, null, ModelProfileSource.LOCAL_FILE,
                OffsetDateTime.now(), ADMIN, OffsetDateTime.now(), "reranker-model",
                "Reranker", "1.0", "a".repeat(64), "Apache-2.0");
    }

    private static String settingsJson() {
        return """
                {"protocol":"LLAMACPP_RERANK","timeoutMs":3000,"maxCandidates":50,"batchSize":8,
                 "maxLength":2048,"scoreType":"sigmoid","topK":8,"healthPath":"/health",
                 "fallback":"java-heuristic"}
                """;
    }
}
