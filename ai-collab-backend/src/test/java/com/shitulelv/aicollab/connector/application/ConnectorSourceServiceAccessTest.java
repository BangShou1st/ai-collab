package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows.ConnectionRow;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorSourceServiceAccessTest {
    private final ConnectorSourceMapper sources = mock(ConnectorSourceMapper.class);
    private final ConnectorConnectionMapper connections = mock(ConnectorConnectionMapper.class);
    private final FeishuClient feishu = mock(FeishuClient.class);
    private final ConnectorConnectionService connectionService = mock(ConnectorConnectionService.class);
    private final ConnectorSyncService syncService = mock(ConnectorSyncService.class);
    private final AuditService audit = mock(AuditService.class);
    private ConnectorSourceService service;
    private final UUID projectId = UUID.randomUUID();
    private final UUID otherProjectId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConnectorSourceService(sources, connections, mock(ConnectorSyncJobMapper.class),
                mock(ExternalDocumentMapper.class), feishu, new ModelSecretCipher("synthetic-test-master-key-only"),
                mock(ProjectAccessGuard.class), mock(ProjectWriteGuard.class), audit, syncService,
                mock(ConnectorOAuthRepository.class), mock(ConnectorOAuthTokenService.class),
                mock(DocumentVisibilityRepository.class), connectionService);
        ConnectionRow connection = new ConnectionRow();
        connection.id = connectionId;
        connection.appId = "cli_scope";
        connection.appSecretCipher = new ModelSecretCipher("synthetic-test-master-key-only").encrypt("secret");
        connection.status = "ACTIVE";
        connection.visibility = "SYSTEM";
        when(connectionService.requireAvailableForProject(projectId, connectionId)).thenReturn(connection);
        when(feishu.tenantAccessToken(anyString(), anyString())).thenReturn("tenant-token");
    }

    @Test
    void createRejectsConnectionsOutsideProjectScopeBeforeTouchingFeishu() {
        when(connectionService.requireAvailableForProject(projectId, connectionId))
                .thenThrow(new BusinessException(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> service.create(projectId, userId, connectionId, "fldcnToken01", "name", true, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND));
        verify(feishu, never()).listFolderDocx(anyString(), anyString());
        verify(sources, never()).insert(any(), any(), any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void createRejectsMalformedFolderTokenWithoutTouchingFeishu() {
        assertThatThrownBy(() -> service.create(projectId, userId, connectionId, "../../etc", "name", true, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(feishu, never()).listFolderDocx(anyString(), anyString());
    }

    @Test
    void createWithSystemConnectionAndValidTokenInsertsSourceAndAudits() {
        var sourceId = UUID.randomUUID();
        var stored = new com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows.SourceRow();
        stored.id = sourceId;
        stored.projectId = projectId;
        stored.connectionId = connectionId;
        stored.folderToken = "fldcnToken01";
        stored.name = "赛事资料";
        stored.status = "ACTIVE";
        when(sources.find(any())).thenReturn(stored);
        var created = service.create(projectId, userId, connectionId, "fldcnToken01", " 赛事资料 ", true, null);
        verify(feishu).listFolderDocx(eq("tenant-token"), eq("fldcnToken01"));
        verify(feishu).folderPermissionMembers(eq("tenant-token"), eq("fldcnToken01"));
        verify(audit).write(eq(projectId), eq(userId), eq("CONNECTOR_SOURCE_ADDED"), eq("CONNECTOR_SOURCE"),
                any(), any());
        assertThat(created.name()).isEqualTo("赛事资料");
    }
}
