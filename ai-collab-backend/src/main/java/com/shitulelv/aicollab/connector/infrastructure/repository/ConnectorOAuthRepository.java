package com.shitulelv.aicollab.connector.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ConnectorOAuthRepository {
    record Grant(UUID id, UUID projectId, UUID connectionId, UUID userId, String status,
                 String accessCipher, String refreshCipher, Instant accessExpiresAt,
                 Instant refreshExpiresAt, String scope, long version, UUID claim) {
        @Override public String toString() { return "Grant[id=" + id + ", status=" + status + ", credentials=redacted]"; }
    }

    record Flow(UUID projectId, UUID connectionId, UUID userId, String verifierCipher, String redirectUri, Long grantVersion) {
        @Override public String toString() { return "Flow[credentials=redacted]"; }
    }
    record GrantKey(UUID projectId, UUID id) { }

    Optional<Grant> find(UUID projectId, UUID authorizationId);
    Optional<Grant> claimRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId);
    boolean completeRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId,
                            String accessCipher, String refreshCipher, Instant accessExpiresAt,
                            Instant refreshExpiresAt, String scope, String status);
    boolean failRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId, String status);
    void saveFlow(String stateHash, UUID projectId, UUID connectionId, UUID userId,
                  String verifierCipher, String redirectUri, Instant expiresAt);
    Optional<Flow> consumeFlow(String stateHash);
    Optional<UUID> saveAuthorization(UUID projectId, UUID connectionId, UUID userId, String accessCipher,
                           String refreshCipher, Instant accessExpiresAt, Instant refreshExpiresAt, String scope,
                           Long expectedVersion, String subjectHash, Instant tokenIssuedAt);
    List<Grant> listOwn(UUID projectId, UUID userId);
    boolean revoke(UUID projectId, UUID authorizationId);
    List<Grant> markAbandonedRefreshes();
    List<GrantKey> dueRefreshes();
}
