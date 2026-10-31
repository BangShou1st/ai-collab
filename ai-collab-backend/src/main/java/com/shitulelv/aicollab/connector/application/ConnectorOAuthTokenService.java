package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthException;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The durable claim and version fence protect a single-use refresh token across instances. */
@Service
public class ConnectorOAuthTokenService {
    private static final Duration EARLY_REFRESH = Duration.ofMinutes(5);
    private static final Set<String> REQUIRED_SCOPES = Set.of("offline_access", "drive:drive:readonly", "docx:document:readonly");
    private final ConnectorOAuthRepository grants;
    private final ConnectorConnectionMapper connections;
    private final ModelSecretCipher cipher;
    private final FeishuOAuthClient feishu;
    private final ConnectorVisibilityService visibility;

    public ConnectorOAuthTokenService(ConnectorOAuthRepository grants, ConnectorConnectionMapper connections,
                                      ModelSecretCipher cipher, FeishuOAuthClient feishu,
                                      ConnectorVisibilityService visibility) {
        this.grants = grants;
        this.connections = connections;
        this.cipher = cipher;
        this.feishu = feishu;
        this.visibility = visibility;
    }

    public String accessToken(UUID projectId, UUID authorizationId) {
        return accessToken(projectId, authorizationId, false);
    }

    public String forceRefresh(UUID projectId, UUID authorizationId) {
        return accessToken(projectId, authorizationId, true);
    }

    private String accessToken(UUID projectId, UUID authorizationId, boolean force) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var grant = grants.find(projectId, authorizationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED));
            if (!"ACTIVE".equals(grant.status())) {
                throw new BusinessException("REFRESHING".equals(grant.status())
                        ? ErrorCode.CONNECTOR_AUTHORIZATION_BUSY : ErrorCode.CONNECTOR_REAUTH_REQUIRED);
            }
            Instant now = Instant.now();
            if (!force && grant.accessExpiresAt() != null && grant.accessExpiresAt().isAfter(now.plus(EARLY_REFRESH))) {
                return cipher.decrypt(grant.accessCipher());
            }
            UUID claimId = UUID.randomUUID();
            var claimed = grants.claimRefresh(projectId, authorizationId, grant.version(), claimId).orElse(null);
            if (claimed == null) continue;
            if (claimed.refreshExpiresAt() == null || !claimed.refreshExpiresAt().isAfter(now)) {
                failAndBlock(claimed, claimId, "REAUTH_REQUIRED");
                throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
            }
            var connection = connections.find(claimed.connectionId());
            if (connection == null) {
                failAndBlock(claimed, claimId, "REAUTH_REQUIRED");
                throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
            }
            try {
                var tokens = feishu.refresh(connection.appId, cipher.decrypt(connection.appSecretCipher),
                        cipher.decrypt(claimed.refreshCipher()));
                String scope = tokens.scope().isBlank() ? claimed.scope() : tokens.scope();
                String status = hasRequiredScopes(scope) ? "ACTIVE" : "REAUTH_REQUIRED";
                boolean saved = grants.completeRefresh(projectId, authorizationId, claimed.version(), claimId,
                        cipher.encrypt(tokens.accessToken()), cipher.encrypt(tokens.refreshToken()),
                        tokens.accessExpiresAt(), tokens.refreshExpiresAt(), scope, status);
                if (!saved) throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN);
                if (!"ACTIVE".equals(status)) {
                    visibility.blockAuthorization(projectId, authorizationId, false);
                    throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
                }
                return tokens.accessToken();
            } catch (FeishuOAuthException exception) {
                if (exception.kind() == FeishuOAuthException.Kind.REVOKED) {
                    // Feishu confirms revocation of this refresh token, not necessarily consent itself.
                    // Block access, but only a confirmed authorization disconnect schedules deletion.
                    failAndBlock(claimed, claimId, "REAUTH_REQUIRED");
                    throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
                }
                if (exception.kind() == FeishuOAuthException.Kind.TEMPORARY) {
                    if (!grants.failRefresh(projectId, authorizationId, claimed.version(), claimId, "ACTIVE")) {
                        throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN);
                    }
                    if (attempt < 2) {
                        backoff(attempt);
                        continue;
                    }
                    throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_BUSY);
                }
                String next = exception.kind() == FeishuOAuthException.Kind.REAUTH_REQUIRED
                        ? "REAUTH_REQUIRED" : "REFRESH_UNCERTAIN";
                failAndBlock(claimed, claimId, next);
                throw new BusinessException("REAUTH_REQUIRED".equals(next)
                        ? ErrorCode.CONNECTOR_REAUTH_REQUIRED : ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN);
            }
        }
        throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_BUSY);
    }

    private void failAndBlock(ConnectorOAuthRepository.Grant grant, UUID claimId, String status) {
        if (grants.failRefresh(grant.projectId(), grant.id(), grant.version(), claimId, status)) {
            visibility.blockAuthorization(grant.projectId(), grant.id(), false);
        }
    }

    public static boolean hasRequiredScopes(String scope) {
        if (scope == null) return false;
        return new HashSet<>(Arrays.asList(scope.strip().split("\\s+"))).containsAll(REQUIRED_SCOPES);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(200L << attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_BUSY);
        }
    }
}
