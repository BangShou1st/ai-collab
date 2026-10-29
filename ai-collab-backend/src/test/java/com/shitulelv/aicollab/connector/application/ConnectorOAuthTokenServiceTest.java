package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthException;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorOAuthTokenServiceTest {
    @Mock ConnectorOAuthRepository grants;
    @Mock ConnectorConnectionMapper connections;
    @Mock ModelSecretCipher cipher;
    @Mock FeishuOAuthClient feishu;
    @Mock ConnectorVisibilityService visibility;
    final UUID project = UUID.randomUUID(), id = UUID.randomUUID(), connectionId = UUID.randomUUID();
    final UUID user = UUID.randomUUID();
    final String scope = "offline_access drive:drive:readonly docx:document:readonly";
    ConnectorOAuthTokenService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorOAuthTokenService(grants, connections, cipher, feishu, visibility);
        ConnectorRows.ConnectionRow connection = new ConnectorRows.ConnectionRow();
        connection.id = connectionId;
        connection.appId = "synthetic-client";
        connection.appSecretCipher = "client-cipher";
        when(connections.find(connectionId)).thenReturn(connection);
        when(cipher.decrypt("client-cipher")).thenReturn("synthetic-client-secret");
        when(cipher.decrypt("old-refresh-cipher")).thenReturn("synthetic-old-refresh");
        when(cipher.encrypt("synthetic-new-access")).thenReturn("new-access-cipher");
        when(cipher.encrypt("synthetic-new-refresh")).thenReturn("new-refresh-cipher");
    }

    private ConnectorOAuthRepository.Grant grant(String status, long version, UUID claim) {
        return new ConnectorOAuthRepository.Grant(id, project, connectionId, user, status,
                "old-access-cipher", "old-refresh-cipher", Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(3600), scope, version, claim);
    }

    @Test
    void rotatedPairIsSavedAtomicallyWithTheClaimFence() {
        when(grants.find(project, id)).thenReturn(Optional.of(grant("ACTIVE", 8, null)));
        when(grants.claimRefresh(eq(project), eq(id), eq(8L), any())).thenAnswer(call ->
                Optional.of(grant("REFRESHING", 9, call.getArgument(3))));
        var tokens = new FeishuOAuthClient.Tokens("synthetic-new-access", "synthetic-new-refresh",
                Instant.now().plusSeconds(1800), Instant.now().plusSeconds(3600), scope);
        when(feishu.refresh(anyString(), anyString(), anyString())).thenReturn(tokens);
        when(grants.completeRefresh(eq(project), eq(id), eq(9L), any(), eq("new-access-cipher"),
                eq("new-refresh-cipher"), any(), any(), eq(scope), eq("ACTIVE"))).thenReturn(true);
        assertEquals("synthetic-new-access", service.accessToken(project, id));
        verify(grants).completeRefresh(eq(project), eq(id), eq(9L), any(), eq("new-access-cipher"),
                eq("new-refresh-cipher"), any(), any(), eq(scope), eq("ACTIVE"));
    }

    @Test
    void concurrentRefreshUsesTheOldTokenOnlyOnce() throws Exception {
        when(grants.find(project, id)).thenReturn(Optional.of(grant("ACTIVE", 8, null)));
        AtomicBoolean claimed = new AtomicBoolean();
        when(grants.claimRefresh(eq(project), eq(id), eq(8L), any())).thenAnswer(call ->
                claimed.compareAndSet(false, true)
                        ? Optional.of(grant("REFRESHING", 9, call.getArgument(3))) : Optional.empty());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(feishu.refresh(anyString(), anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new FeishuOAuthClient.Tokens("synthetic-new-access", "synthetic-new-refresh",
                    Instant.now().plusSeconds(1800), Instant.now().plusSeconds(3600), scope);
        });
        when(grants.completeRefresh(any(), any(), anyLong(), any(), anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.accessToken(project, id));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> assertThrows(BusinessException.class, () -> service.accessToken(project, id)));
            second.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertEquals("synthetic-new-access", first.get(5, TimeUnit.SECONDS));
        }
        verify(feishu, times(1)).refresh(anyString(), anyString(), anyString());
    }

    @Test
    void uncertainRefreshBlocksWithoutSchedulingDeletion() {
        when(grants.find(project, id)).thenReturn(Optional.of(grant("ACTIVE", 8, null)));
        when(grants.claimRefresh(eq(project), eq(id), eq(8L), any())).thenAnswer(call ->
                Optional.of(grant("REFRESHING", 9, call.getArgument(3))));
        when(feishu.refresh(anyString(), anyString(), anyString()))
                .thenThrow(new FeishuOAuthException(FeishuOAuthException.Kind.OUTCOME_UNKNOWN));
        when(grants.failRefresh(eq(project), eq(id), eq(9L), any(), eq("REFRESH_UNCERTAIN"))).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.accessToken(project, id));
        verify(visibility).blockAuthorization(project, id, false);
        verify(grants, never()).completeRefresh(any(), any(), anyLong(), any(), anyString(), anyString(), any(), any(), anyString(), anyString());
    }
}
