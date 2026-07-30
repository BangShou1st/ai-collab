package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class BatchReindexAuthorizationTest {

    @Test
    void reindexAllRejectsCallersBeforeReadingProjectDocuments() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new BusinessException(ErrorCode.PROJECT_ADMIN_REQUIRED))
                .when(access).requireAdmin(projectId, userId);
        BatchReindexService service = new BatchReindexService(
                documents, mock(AuditService.class), mock(ApplicationEventPublisher.class), access,
                mock(ProjectWriteGuard.class));

        assertThatThrownBy(() -> service.reindexAll(projectId, userId))
                .isInstanceOf(BusinessException.class);
    }
}
