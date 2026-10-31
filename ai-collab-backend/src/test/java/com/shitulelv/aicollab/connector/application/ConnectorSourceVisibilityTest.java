package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.*;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConnectorSourceVisibilityTest {
    @Test
    void inactiveUserGrantHidesFolderLocatorAndMarksSourceBlocked() {
        var sources=mock(ConnectorSourceMapper.class);
        var grants=mock(ConnectorOAuthRepository.class);
        UUID project=UUID.randomUUID(), user=UUID.randomUUID(), sourceId=UUID.randomUUID(), grantId=UUID.randomUUID();
        UUID connectionId=UUID.randomUUID();
        var source=new ConnectorRows.SourceRow(); source.id=sourceId; source.projectId=project;
        source.connectionId=connectionId; source.userAuthorizationId=grantId; source.status="ACTIVE";
        source.folderToken="synthetic-folder-id";
        when(sources.listByProject(project)).thenReturn(List.of(source));
        when(grants.find(project,grantId)).thenReturn(Optional.of(new ConnectorOAuthRepository.Grant(
                grantId,project,connectionId,user,"REAUTH_REQUIRED",null,null,Instant.now(),Instant.now(),"",1,null)));
        var service=new ConnectorSourceService(sources,mock(ConnectorConnectionMapper.class),mock(ConnectorSyncJobMapper.class),
                mock(ExternalDocumentMapper.class),mock(FeishuClient.class),new ModelSecretCipher("synthetic-test-master-key-only"),
                mock(ProjectAccessGuard.class),mock(ProjectWriteGuard.class),mock(AuditService.class),
                mock(ConnectorSyncService.class),grants,mock(ConnectorOAuthTokenService.class),mock(DocumentVisibilityRepository.class));
        var results=service.list(project,user);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().status()).isEqualTo("BLOCKED");
        assertThat(results.getFirst().folderToken()).isNull();
        assertThat(results.getFirst().documentCount()).isZero();
    }

    @Test
    void blockedSourceDocumentsDoNotExposeTitlesOrOriginalLinks() {
        var sources=mock(ConnectorSourceMapper.class);
        var external=mock(ExternalDocumentMapper.class);
        var visibility=mock(DocumentVisibilityRepository.class);
        var access=mock(ProjectAccessGuard.class);
        var service=new ConnectorSourceService(sources,mock(ConnectorConnectionMapper.class),mock(ConnectorSyncJobMapper.class),
                external,mock(FeishuClient.class),new ModelSecretCipher("synthetic-test-master-key-only"),access,
                mock(ProjectWriteGuard.class),mock(AuditService.class),mock(ConnectorSyncService.class),
                mock(ConnectorOAuthRepository.class),mock(ConnectorOAuthTokenService.class),visibility);
        UUID project=UUID.randomUUID(), sourceId=UUID.randomUUID(), user=UUID.randomUUID();
        var source=new ConnectorRows.SourceRow(); source.id=sourceId; source.projectId=project;
        var hidden=new ConnectorRows.ExternalDocRow(); hidden.id=UUID.randomUUID(); hidden.documentId=UUID.randomUUID();
        hidden.title="hidden title"; hidden.sourceUrl="https://example.invalid/hidden";
        var visible=new ConnectorRows.ExternalDocRow(); visible.id=UUID.randomUUID(); visible.documentId=UUID.randomUUID();
        visible.title="visible title"; visible.sourceUrl="https://example.invalid/visible";
        when(sources.find(sourceId)).thenReturn(source);
        when(external.listBySource(sourceId)).thenReturn(List.of(hidden,visible));
        when(visibility.readable(project,visible.documentId)).thenReturn(true);
        var results=service.documents(project,sourceId,user);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().title()).isEqualTo("visible title");
        assertThat(results.getFirst().sourceUrl()).isEqualTo("https://example.invalid/visible");
        verify(access).requireMember(project,user);
    }
}
