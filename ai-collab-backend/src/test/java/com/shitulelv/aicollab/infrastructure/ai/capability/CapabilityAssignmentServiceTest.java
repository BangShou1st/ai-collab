package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityAssignmentRequest;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityAssignmentServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private final CapabilityAssignmentRepository assignments = mock(CapabilityAssignmentRepository.class);
    private final CapabilityProfileRepository profiles = mock(CapabilityProfileRepository.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final AuditService audit = mock(AuditService.class);
    private final CapabilityAssignmentService service = new CapabilityAssignmentService(assignments,
            profiles, access, audit);

    @Test
    void bindsOnlyPublishedProfileWithFrozenPurposeAndDisabledByDefault() {
        UUID profileId = UUID.randomUUID();
        CapabilityProfile published = profile(profileId, CapabilityProfileStatus.PUBLISHED);
        CapabilityAssignment created = new CapabilityAssignment(UUID.randomUUID(),
                CapabilityType.RERANK, CapabilityPurpose.KNOWLEDGE_RERANK,
                CapabilityAssignmentScope.GLOBAL, null, profileId, false, ADMIN, ADMIN,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(profiles.findById(profileId)).thenReturn(Optional.of(published));
        when(assignments.findGlobal(CapabilityType.RERANK, CapabilityPurpose.KNOWLEDGE_RERANK))
                .thenReturn(Optional.empty());
        when(assignments.create(eq(ADMIN), eq(CapabilityType.RERANK),
                eq(CapabilityPurpose.KNOWLEDGE_RERANK), eq(CapabilityAssignmentScope.GLOBAL),
                isNull(), eq(profileId), eq(false))).thenReturn(created);

        service.upsertGlobal(ADMIN, new CapabilityAssignmentRequest(profileId, null, "bind"));

        verify(assignments).create(eq(ADMIN), eq(CapabilityType.RERANK),
                eq(CapabilityPurpose.KNOWLEDGE_RERANK), eq(CapabilityAssignmentScope.GLOBAL),
                isNull(), eq(profileId), eq(false));
    }

    @Test
    void rejectsUnpublishedProfile() {
        UUID profileId = UUID.randomUUID();
        when(profiles.findById(profileId))
                .thenReturn(Optional.of(profile(profileId, CapabilityProfileStatus.TESTED)));

        assertThatThrownBy(() -> service.upsertGlobal(ADMIN,
                new CapabilityAssignmentRequest(profileId, true, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.CAPABILITY_ASSIGNMENT_INVALID));
    }

    @Test
    void projectBindingRequiresProjectAdmin() {
        UUID profileId = UUID.randomUUID();
        UUID projectAdmin = UUID.randomUUID();
        when(profiles.findById(profileId))
                .thenReturn(Optional.of(profile(profileId, CapabilityProfileStatus.PUBLISHED)));
        when(assignments.findProject(PROJECT_ID, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK)).thenReturn(Optional.empty());
        when(assignments.create(eq(projectAdmin), eq(CapabilityType.RERANK),
                eq(CapabilityPurpose.KNOWLEDGE_RERANK), eq(CapabilityAssignmentScope.PROJECT),
                eq(PROJECT_ID), eq(profileId), eq(true))).thenReturn(
                new CapabilityAssignment(UUID.randomUUID(), CapabilityType.RERANK,
                        CapabilityPurpose.KNOWLEDGE_RERANK, CapabilityAssignmentScope.PROJECT,
                        PROJECT_ID, profileId, true, projectAdmin, projectAdmin,
                        OffsetDateTime.now(), OffsetDateTime.now()));

        service.upsertProject(PROJECT_ID, projectAdmin,
                new CapabilityAssignmentRequest(profileId, true, "project bind"));

        verify(access).requireAdmin(PROJECT_ID, projectAdmin);
        verify(assignments).create(eq(projectAdmin), eq(CapabilityType.RERANK),
                eq(CapabilityPurpose.KNOWLEDGE_RERANK), eq(CapabilityAssignmentScope.PROJECT),
                eq(PROJECT_ID), eq(profileId), eq(true));
    }

    private static CapabilityProfile profile(UUID id, CapabilityProfileStatus status) {
        return new CapabilityProfile(id, UUID.randomUUID(), UUID.randomUUID(), CapabilityType.RERANK,
                "LLAMACPP_RERANK", "{}", status, 1, "ADMIN_VERIFIED",
                status == CapabilityProfileStatus.DRAFT ? null : OffsetDateTime.now(),
                status == CapabilityProfileStatus.DRAFT ? null : ADMIN,
                ADMIN, ADMIN, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
