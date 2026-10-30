package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
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
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorConnectionServiceTest {

    private ConnectorConnectionMapper connections;
    private UserService users;
    private ProjectAccessGuard access;
    private FeishuClient feishu;
    private ConnectorConnectionService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        connections = mock(ConnectorConnectionMapper.class);
        users = mock(UserService.class);
        access = mock(ProjectAccessGuard.class);
        feishu = mock(FeishuClient.class);
        service = new ConnectorConnectionService(connections, mock(ConnectorSourceMapper.class),
                feishu, new ModelSecretCipher("synthetic-test-master-key-only"),
                users, mock(ConnectorEventCredentialRepository.class),
                mock(ConnectorVisibilityService.class), access);
    }

    @Test
    void project_admin_creates_project_scoped_connection_with_encrypted_secret() {
        ConnectionRow row = new ConnectionRow();
        row.id = UUID.randomUUID();
        row.name = "我的社团应用";
        row.appId = "cli_x";
        row.status = "ACTIVE";
        row.visibility = "PROJECT";
        row.ownerProjectId = projectId;
        when(connections.find(any())).thenReturn(row);

        service.create(userId, projectId, " 我的社团应用 ", "cli_x", "secret-value");

        verify(access).requireAdmin(projectId, userId);
        ArgumentCaptor<String> cipher = ArgumentCaptor.forClass(String.class);
        verify(connections).insert(any(), eq("feishu"), eq("我的社团应用"), eq("cli_x"),
                cipher.capture(), eq("ACTIVE"), any(), eq(userId), eq("PROJECT"), eq(projectId));
        assertThat(cipher.getValue()).doesNotContain("secret-value");
    }

    @Test
    void system_admin_creates_system_connection_without_project() {
        ConnectionRow row = new ConnectionRow();
        row.id = UUID.randomUUID();
        row.visibility = "SYSTEM";
        when(connections.find(any())).thenReturn(row);

        service.create(userId, null, "全局连接", "cli_y", "secret-value");

        verify(users).requireSystemAdmin(userId);
        verify(connections).insert(any(), eq("feishu"), eq("全局连接"), eq("cli_y"),
                anyString(), eq("ACTIVE"), any(), eq(userId), eq("SYSTEM"), isNull());
    }

    @Test
    void non_admin_project_member_cannot_create_project_connection() {
        doThrow(new BusinessException(ErrorCode.PROJECT_ADMIN_REQUIRED))
                .when(access).requireAdmin(projectId, userId);

        assertThatThrownBy(() -> service.create(userId, projectId, "name", "cli_x", "secret"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.PROJECT_ADMIN_REQUIRED));
    }

    @Test
    void invalid_credentials_rejected_before_persisting() {
        doThrow(new FeishuApiException(20002, "app credentials rejected"))
                .when(feishu).tenantAccessToken("cli_bad", "bad-secret");

        assertThatThrownBy(() -> service.create(userId, projectId, "name", "cli_bad", "bad-secret"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONNECTOR_CREDENTIAL_INVALID));
        verify(connections, org.mockito.Mockito.never()).insert(any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void project_admin_manages_project_connection_but_not_system_connection() {
        ConnectionRow projectRow = new ConnectionRow();
        projectRow.id = UUID.randomUUID();
        projectRow.appId = "cli_x";
        projectRow.appSecretCipher = new ModelSecretCipher("synthetic-test-master-key-only").encrypt("secret");
        projectRow.visibility = "PROJECT";
        projectRow.ownerProjectId = projectId;
        when(connections.find(projectRow.id)).thenReturn(projectRow);
        when(feishu.tenantAccessToken(anyString(), anyString())).thenReturn("token");
        service.verify(userId, projectRow.id);
        verify(access).requireAdmin(projectId, userId);

        ConnectionRow systemRow = new ConnectionRow();
        systemRow.id = UUID.randomUUID();
        systemRow.visibility = "SYSTEM";
        when(connections.find(systemRow.id)).thenReturn(systemRow);
        doThrow(new BusinessException(ErrorCode.ADMIN_REQUIRED))
                .when(users).requireSystemAdmin(userId);
        assertThatThrownBy(() -> service.verify(userId, systemRow.id))
                .isInstanceOf(BusinessException.class);
    }
}
