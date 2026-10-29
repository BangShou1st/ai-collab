package com.shitulelv.aicollab.connector.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuEventCrypto;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConnectorEventServiceTest {
    private final ConnectorConnectionMapper connections = mock(ConnectorConnectionMapper.class);
    private final ConnectorEventCredentialRepository credentials = mock(ConnectorEventCredentialRepository.class);
    private final FeishuEventCrypto crypto = mock(FeishuEventCrypto.class);
    private final ConnectorEventProcessor processor = mock(ConnectorEventProcessor.class);
    private final ModelSecretCipher cipher = new ModelSecretCipher("synthetic-test-master-key-only");
    private final ConnectorEventService service = new ConnectorEventService(connections,credentials,cipher,crypto,processor);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void verifiedChallengeMarksTheSameCredentialsAndEventsUseScopedHashedIdentity() throws Exception {
        UUID connectionId = UUID.randomUUID();
        var connection = new ConnectorRows.ConnectionRow();
        connection.appId="synthetic-app"; connection.status="ACTIVE";
        var secret = new ConnectorEventCredentialRepository.Credentials(cipher.encrypt("synthetic-key-123456"),cipher.encrypt("synthetic-token-123456"),false);
        when(connections.find(connectionId)).thenReturn(connection);
        when(credentials.find(connectionId)).thenReturn(Optional.of(secret));
        when(credentials.markVerified(connectionId,secret)).thenReturn(true);
        when(crypto.decode(any(),anyString(),anyString(),any(),any(),any())).thenReturn(
                json.readTree("{\"type\":\"url_verification\",\"challenge\":\"synthetic-challenge\"}"));
        assertThat(service.receive(connectionId,new byte[]{1},null,null,null)).isEqualTo(Map.of("challenge","synthetic-challenge"));
        verify(credentials).markVerified(connectionId,secret);

        Instant time=Instant.now().minusSeconds(1);
        when(crypto.decode(any(),anyString(),anyString(),any(),any(),any())).thenReturn(json.readTree("""
                {"header":{"event_type":"auth.user_access_token.revoked_v4","app_id":"synthetic-app",
                  "tenant_key":"synthetic-tenant","event_id":"event-1","create_time":"%d"},
                  "event":{"open_id":"synthetic-open-id"}}
                """.formatted(time.toEpochMilli())));
        assertThat(service.receive(connectionId,new byte[]{1},"timestamp","nonce","signature")).isEqualTo(Map.of("code",0));
        verify(processor).revokeTokens(connectionId,cipher.subjectDigest("synthetic-app","synthetic-tenant","synthetic-open-id"),
                "event-1",Instant.ofEpochMilli(time.toEpochMilli()));
        connection.appId="other-app";
        assertThatThrownBy(() -> service.receive(connectionId,new byte[]{1},"timestamp","nonce","signature"))
                .isInstanceOf(BusinessException.class);
        verifyNoMoreInteractions(processor);
    }
}
