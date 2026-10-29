package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DownloadUrlView;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.domain.service.DocumentFilePolicy;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentDownloadAuthorizationTest {
    final DocumentRepository documents = mock(DocumentRepository.class);
    final DocumentStorageGateway storage = mock(DocumentStorageGateway.class);
    final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    final DocumentVisibilityRepository visibility = mock(DocumentVisibilityRepository.class);
    final DocumentApplicationService service = new DocumentApplicationService(documents,storage,mock(DocumentFilePolicy.class),
            access,mock(ProjectWriteGuard.class),mock(DocumentRegistrationService.class),mock(DocumentRetryService.class),
            mock(DocumentDeletionService.class),mock(AuditService.class),mock(ApplicationEventPublisher.class),visibility);
    final UUID project = UUID.randomUUID(), document = UUID.randomUUID(), user = UUID.randomUUID();

    @Test
    void memberGetsShortLivedAddressAndBlockedDocumentCannotBeSigned() {
        DocumentEntity row = new DocumentEntity();
        row.setStatus(DocumentStatus.READY);
        row.setOriginalFilename("synthetic.txt");
        row.setObjectKey("projects/synthetic/source.txt");
        when(documents.find(project,document)).thenReturn(Optional.of(row));
        when(visibility.readable(project,document)).thenReturn(true,false);
        var result = new DownloadUrlView("https://download.example.invalid/storage/synthetic", OffsetDateTime.now().plusMinutes(5));
        when(storage.presign(anyString(),anyString(),eq(Duration.ofMinutes(5)))).thenReturn(result);
        assertThat(service.downloadUrl(project,document,user)).isEqualTo(result);
        assertThatThrownBy(() -> service.downloadUrl(project,document,user)).isInstanceOf(BusinessException.class);
        verify(access,times(2)).requireMember(project,user);
        verify(storage,times(1)).presign(anyString(),anyString(),eq(Duration.ofMinutes(5)));
    }

    @Test
    void nonmemberCannotObtainAnAddressOrReachStorage() {
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN)).when(access).requireMember(project,user);
        assertThatThrownBy(() -> service.downloadUrl(project,document,user)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(documents,visibility,storage);
    }

    @Test
    void blockedDocumentIsNotListedOrAccessibleForMetadataRetryAndReindex() {
        DocumentEntity row = new DocumentEntity();
        row.setId(document);
        row.setStatus(DocumentStatus.READY);
        when(documents.list(project)).thenReturn(List.of(row));
        when(documents.find(project,document)).thenReturn(Optional.of(row));
        when(visibility.readable(project,document)).thenReturn(false);
        assertThat(service.list(project,user)).isEmpty();
        assertThatThrownBy(() -> service.get(project,document,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.retry(project,document,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reindex(project,document,user)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }
}
