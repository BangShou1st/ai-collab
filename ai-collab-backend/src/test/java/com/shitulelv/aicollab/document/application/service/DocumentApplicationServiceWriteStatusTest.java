package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.service.DocumentFilePolicy;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DocumentApplicationServiceWriteStatusTest {

    @Mock DocumentRepository documents;
    @Mock DocumentStorageGateway storage;
    @Mock DocumentFilePolicy filePolicy;
    @Mock ProjectAccessGuard access;
    @Mock ProjectWriteGuard writeGuard;
    @Mock DocumentRegistrationService registration;
    @Mock DocumentRetryService retry;
    @Mock DocumentDeletionService deletion;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock MultipartFile file;

    private DocumentApplicationService service;
    private UUID projectId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new DocumentApplicationService(
                documents, storage, filePolicy, access, writeGuard,
                registration, retry, deletion, audit, events);
        projectId = UUID.randomUUID();
        userId = UUID.randomUUID();
        doThrow(new BusinessException(ErrorCode.PROJECT_READ_ONLY))
                .when(writeGuard).requireWritable(projectId);
    }

    @Test
    void uploadRejectsReadOnlyProjectBeforeFileValidationOrStorage() {
        assertReadOnly(() -> service.upload(projectId, file, "需求说明", userId));

        verifyNoInteractions(filePolicy, storage, registration);
    }

    @Test
    void retryRejectsReadOnlyProjectBeforeReadingDocumentOrStorage() {
        assertReadOnly(() -> service.retry(projectId, UUID.randomUUID(), userId));

        verifyNoInteractions(documents, storage, retry);
    }

    @Test
    void deleteRejectsReadOnlyProjectBeforeReadingDocumentOrStorage() {
        assertReadOnly(() -> service.delete(projectId, UUID.randomUUID(), userId));

        verifyNoInteractions(documents, storage, deletion);
    }

    @Test
    void reindexRejectsReadOnlyProjectBeforeReadingDocument() {
        assertReadOnly(() -> service.reindex(projectId, UUID.randomUUID(), userId));

        verifyNoInteractions(documents, audit, events);
    }

    private static void assertReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_READ_ONLY));
    }
}
