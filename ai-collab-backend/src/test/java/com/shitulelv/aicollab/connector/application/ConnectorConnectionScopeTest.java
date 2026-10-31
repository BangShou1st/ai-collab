package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows.ConnectionRow;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorConnectionScopeTest {
    private final ConnectorConnectionMapper connections = mock(ConnectorConnectionMapper.class);
    private final ConnectorEventCredentialRepository eventCredentials = mock(ConnectorEventCredentialRepository.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final UserService users = mock(UserService.class);
    private ConnectorConnectionService service;
    private final UUID projectId = UUID.randomUUID();
    private final UUID otherProjectId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConnectorConnectionService(connections, mock(ConnectorSourceMapper.class),
                mock(FeishuClient.class), new ModelSecretCipher("synthetic-test-master-key-only"),
                users, eventCredentials, mock(ConnectorVisibilityService.class), access);
    }

    private ConnectionRow row(String visibility, UUID ownerProjectId, String status) {
        ConnectionRow row = new ConnectionRow();
        row.id = connectionId;
        row.visibility = visibility;
        row.ownerProjectId = ownerProjectId;
        row.status = status;
        return row;
    }

    @Test
    void systemConnectionIsUsableByAnyProject() {
        when(connections.find(connectionId)).thenReturn(row("SYSTEM", null, "ACTIVE"));
        assertThat(service.requireAvailableForProject(projectId, connectionId).id).isEqualTo(connectionId);
    }

    @Test
    void projectConnectionIsUsableOnlyByItsOwnerProject() {
        when(connections.find(connectionId)).thenReturn(row("PROJECT", projectId, "ACTIVE"));
        assertThat(service.requireAvailableForProject(projectId, connectionId).id).isEqualTo(connectionId);
        assertThatThrownBy(() -> service.requireAvailableForProject(otherProjectId, connectionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND));
    }

    @Test
    void inactiveConnectionIsRejectedForEveryProject() {
        when(connections.find(connectionId)).thenReturn(row("PROJECT", projectId, "INVALID"));
        assertThatThrownBy(() -> service.requireAvailableForProject(projectId, connectionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND));
        when(connections.find(connectionId)).thenReturn(row("SYSTEM", null, "INVALID"));
        assertThatThrownBy(() -> service.requireAvailableForProject(projectId, connectionId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void eventStatusRequiresProjectAdminAndConnectionScope() {
        when(connections.find(connectionId)).thenReturn(row("PROJECT", projectId, "ACTIVE"));
        when(eventCredentials.find(connectionId)).thenReturn(Optional.of(
                new ConnectorEventCredentialRepository.Credentials("cipher-a", "cipher-b", true)));
        var status = service.eventStatus(projectId, userId, connectionId);
        assertThat(status.configured()).isTrue();
        assertThat(status.verified()).isTrue();
        verify(access).requireAdmin(projectId, userId);

        when(connections.find(connectionId)).thenReturn(row("PROJECT", otherProjectId, "ACTIVE"));
        assertThatThrownBy(() -> service.eventStatus(projectId, userId, connectionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND));
    }
}
