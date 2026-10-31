package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConnectorOAuthServiceTest {
    final ConnectorOAuthRepository grants = mock(ConnectorOAuthRepository.class);
    final ConnectorConnectionMapper connections = mock(ConnectorConnectionMapper.class);
    final FeishuOAuthClient provider = mock(FeishuOAuthClient.class);
    final FeishuClient feishuApi = mock(FeishuClient.class);
    final ConnectorEventCredentialRepository eventCredentials = mock(ConnectorEventCredentialRepository.class);
    final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    final ProjectWriteGuard writable = mock(ProjectWriteGuard.class);
    final ConnectorVisibilityService visibility = mock(ConnectorVisibilityService.class);
    final ConnectorOAuthTokenService tokens = mock(ConnectorOAuthTokenService.class);
    final ModelSecretCipher cipher = new ModelSecretCipher("synthetic-test-master-key-only");
    final UUID project = UUID.randomUUID(), connectionId = UUID.randomUUID(), user = UUID.randomUUID(), grantId = UUID.randomUUID();
    final String scope = "offline_access drive:drive:readonly docx:document:readonly";
    ConnectorOAuthService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorOAuthService(grants, connections, cipher, provider, feishuApi, eventCredentials, access, writable, visibility, tokens,
                "https://example.invalid/api/v1/connector/oauth/callback");
        ConnectorRows.ConnectionRow connection = new ConnectorRows.ConnectionRow();
        connection.id = connectionId;
        connection.appId = "synthetic-app";
        connection.appSecretCipher = cipher.encrypt("synthetic-app-secret");
        connection.status = "ACTIVE";
        when(connections.find(connectionId)).thenReturn(connection);
        when(eventCredentials.find(connectionId)).thenReturn(Optional.of(new ConnectorEventCredentialRepository.Credentials("cipher-a","cipher-b",true)));
        when(feishuApi.currentUser(anyString())).thenReturn(new FeishuClient.UserIdentity("synthetic-open-id","synthetic-tenant"));
    }

    @Test
    void authorizationUsesPkceAndStoresOnlyHashedStateAndEncryptedVerifier() {
        var result = service.start(project,connectionId,user);
        assertThat(result.authorizationUrl()).startsWith("https://accounts.feishu.cn/open-apis/authen/v1/authorize?")
                .contains("code_challenge_method=S256", "offline_access");
        var state = org.mockito.ArgumentCaptor.forClass(String.class);
        var verifier = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(grants).saveFlow(state.capture(),eq(project),eq(connectionId),eq(user),verifier.capture(),anyString(),any());
        assertThat(state.getValue()).matches("[0-9a-f]{64}");
        assertThat(cipher.decrypt(verifier.getValue())).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.toString()).doesNotContain("authorizationUrl=https");
        verify(access).requireAdmin(project,user);
    }

    @Test
    void authorizationRequiresAValidatedEventEndpoint() {
        when(eventCredentials.find(connectionId)).thenReturn(Optional.of(new ConnectorEventCredentialRepository.Credentials("cipher-a","cipher-b",false)));
        assertThatThrownBy(() -> service.start(project,connectionId,user)).isInstanceOf(BusinessException.class);
        verify(grants,never()).saveFlow(anyString(),any(),any(),any(),anyString(),anyString(),any());
    }

    @Test
    void callbackEncryptsBothTokensAndRequiresVersionFence() {
        String state = "a".repeat(43);
        when(grants.consumeFlow(anyString())).thenReturn(Optional.of(new ConnectorOAuthRepository.Flow(
                project,connectionId,user,cipher.encrypt("b".repeat(43)),"https://example.invalid/api/v1/connector/oauth/callback",4L)));
        when(provider.exchange(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(
                new FeishuOAuthClient.Tokens("synthetic-access","synthetic-refresh",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),scope));
        when(grants.saveAuthorization(eq(project),eq(connectionId),eq(user),anyString(),anyString(),any(),any(),eq(scope),eq(4L),anyString(),any()))
                .thenReturn(Optional.of(grantId));
        service.callback(state,"synthetic-code");
        var a = org.mockito.ArgumentCaptor.forClass(String.class);
        var r = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(grants).saveAuthorization(eq(project),eq(connectionId),eq(user),a.capture(),r.capture(),any(),any(),eq(scope),eq(4L),anyString(),any());
        assertThat(a.getValue()).doesNotContain("synthetic-access");
        assertThat(r.getValue()).doesNotContain("synthetic-refresh");
        assertThat(cipher.decrypt(a.getValue())).isEqualTo("synthetic-access");
        assertThat(cipher.decrypt(r.getValue())).isEqualTo("synthetic-refresh");
        when(grants.saveAuthorization(any(),any(),any(),anyString(),anyString(),any(),any(),anyString(),anyLong(),anyString(),any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.callback(state,"synthetic-code-2")).isInstanceOf(BusinessException.class);
    }

    @Test
    void disconnectRequiresOwnerAndQueuesCleanupOnlyForConfirmedDisconnect() {
        var grant = new ConnectorOAuthRepository.Grant(grantId,project,connectionId,user,"ACTIVE","a","b",
                Instant.now().plusSeconds(300),Instant.now().plusSeconds(600),scope,1,null);
        when(grants.find(project,grantId)).thenReturn(Optional.of(grant));
        assertThatThrownBy(() -> service.disconnect(project,grantId,UUID.randomUUID())).isInstanceOf(BusinessException.class);
        verify(grants,never()).revoke(any(),any());
        service.disconnect(project,grantId,user);
        verify(access,times(2)).requireMember(eq(project),any());
        verify(writable,never()).requireWritable(project);
        verify(grants).revoke(project,grantId);
        verify(visibility).blockAuthorization(project,grantId,true);
    }
}
