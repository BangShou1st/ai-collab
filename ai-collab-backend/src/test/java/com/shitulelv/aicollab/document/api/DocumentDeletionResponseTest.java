package com.shitulelv.aicollab.document.api;

import com.shitulelv.aicollab.document.api.controller.DocumentController;
import com.shitulelv.aicollab.document.application.service.BatchReindexService;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.view.DocumentDeletionAcceptedView;
import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentDeletionResponseTest {
    @Test
    void deleteReturnsAcceptedEnvelopeWithDeletionIdentity() {
        DocumentApplicationService documents = mock(DocumentApplicationService.class);
        DocumentController controller = new DocumentController(documents, mock(BatchReindexService.class));
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deletionId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .subject(userId.toString())
                .build();
        when(documents.delete(projectId, documentId, userId))
                .thenReturn(new DocumentDeletionAcceptedView(
                        deletionId, DocumentDeletionJobStatus.PENDING));

        var response = controller.delete(projectId, documentId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().deletionId()).isEqualTo(deletionId);
        assertThat(response.getBody().data().status()).isEqualTo(DocumentDeletionJobStatus.PENDING);
    }
}
