package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuOAuthException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ConnectorOAuthService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConnectorOAuthService.class);
    private static final String AUTHORIZE = "https://accounts.feishu.cn/open-apis/authen/v1/authorize";
    private static final String SCOPE = "offline_access drive:drive:readonly docx:document:readonly";
    private final ConnectorOAuthRepository grants;
    private final ConnectorConnectionMapper connections;
    private final ModelSecretCipher cipher;
    private final FeishuOAuthClient feishu;
    private final FeishuClient feishuApi;
    private final ConnectorEventCredentialRepository eventCredentials;
    private final ProjectAccessGuard access;
    private final ProjectWriteGuard writable;
    private final ConnectorVisibilityService visibility;
    private final ConnectorOAuthTokenService tokens;
    private final String redirectUri;
    private final SecureRandom random = new SecureRandom();

    public ConnectorOAuthService(ConnectorOAuthRepository grants, ConnectorConnectionMapper connections,
                                 ModelSecretCipher cipher, FeishuOAuthClient feishu, FeishuClient feishuApi,
                                 ConnectorEventCredentialRepository eventCredentials, ProjectAccessGuard access,
                                 ProjectWriteGuard writable, ConnectorVisibilityService visibility, ConnectorOAuthTokenService tokens,
                                 @Value("${connector.oauth.redirect-uri:${FEISHU_OAUTH_REDIRECT_URI:}}") String redirectUri) {
        this.grants = grants;
        this.connections = connections;
        this.cipher = cipher;
        this.feishu = feishu;
        this.feishuApi = feishuApi;
        this.eventCredentials = eventCredentials;
        this.access = access;
        this.writable = writable;
        this.visibility = visibility;
        this.tokens = tokens;
        this.redirectUri = redirectUri;
    }

    public StartView start(UUID projectId, UUID connectionId, UUID userId) {
        access.requireAdmin(projectId, userId);
        writable.requireWritable(projectId);
        var connection = connections.find(connectionId);
        if (connection == null || !"ACTIVE".equals(connection.status)) {
            throw new BusinessException(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND);
        }
        if (eventCredentials.find(connectionId).filter(ConnectorEventCredentialRepository.Credentials::verified).isEmpty()) {
            throw new BusinessException(ErrorCode.CONNECTOR_EVENT_NOT_CONFIGURED);
        }
        validateRedirect();
        String state = randomValue();
        String verifier = randomValue();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        Instant expires = Instant.now().plusSeconds(300);
        grants.saveFlow(hash(state), projectId, connectionId, userId, cipher.encrypt(verifier), redirectUri, expires);
        log.info("PKCE start 诊断: stateHashPrefix={}, challengePrefix={}",
                hash(state).substring(0, 12), challenge.substring(0, 12));
        String url = AUTHORIZE + "?response_type=code&client_id=" + encode(connection.appId)
                + "&redirect_uri=" + encode(redirectUri) + "&scope=" + encode(SCOPE)
                + "&state=" + encode(state) + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
        return new StartView(url, expires);
    }

    public void callback(String state, String code) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || code == null || code.isBlank() || code.length() > 2048) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "授权回调无效或已过期");
        }
        var flow = grants.consumeFlow(hash(state))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "授权回调无效或已过期"));
        access.requireAdmin(flow.projectId(), flow.userId());
        writable.requireWritable(flow.projectId());
        var connection = connections.find(flow.connectionId());
        if (connection == null || !"ACTIVE".equals(connection.status)) {
            throw new BusinessException(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND);
        }
        try {
            // Provider event timestamps have millisecond precision and may differ slightly from this clock.
            Instant tokenIssuedAt = Instant.now().minusSeconds(60);
            var verifier = cipher.decrypt(flow.verifierCipher());
            var recomputedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
            log.info("PKCE complete 诊断: recomputedChallengePrefix={}, verifierLen={}",
                    recomputedChallenge.substring(0, 12), verifier.length());
            var token = feishu.exchange(connection.appId, cipher.decrypt(connection.appSecretCipher), code,
                    flow.redirectUri(), verifier);
            if (!ConnectorOAuthTokenService.hasRequiredScopes(token.scope())) {
                throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED, "飞书授权缺少所需的只读或离线访问权限");
            }
            var identity = feishuApi.currentUser(token.accessToken());
            String subjectHash = cipher.subjectDigest(connection.appId, identity.tenantKey(), identity.openId());
            grants.saveAuthorization(flow.projectId(), flow.connectionId(), flow.userId(),
                    cipher.encrypt(token.accessToken()), cipher.encrypt(token.refreshToken()),
                    token.accessExpiresAt(), token.refreshExpiresAt(), token.scope(), flow.grantVersion(), subjectHash, tokenIssuedAt)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN));
        } catch (FeishuApiException exception) {
            throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN, "无法确认飞书用户身份，请重新授权");
        } catch (FeishuOAuthException exception) {
            throw new BusinessException(exception.kind() == FeishuOAuthException.Kind.REAUTH_REQUIRED || exception.kind() == FeishuOAuthException.Kind.REVOKED
                    ? ErrorCode.CONNECTOR_REAUTH_REQUIRED : ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN);
        }
    }

    public List<GrantView> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return grants.listOwn(projectId, userId).stream()
                .map(g -> new GrantView(g.id(), g.connectionId(), g.status(), g.accessExpiresAt())).toList();
    }

    public List<ConnectionOption> availableConnections(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        return connections.listAvailableForProject(projectId).stream().filter(row -> "ACTIVE".equals(row.status))
                .map(row -> new ConnectionOption(row.id, row.name)).toList();
    }

    @Transactional
    public void disconnect(UUID projectId, UUID authorizationId, UUID userId) {
        // Grant owners retain the ability to disconnect after demotion or project archival.
        access.requireMember(projectId, userId);
        var grant = grants.find(projectId, authorizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED));
        if (!grant.userId().equals(userId)) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        grants.revoke(projectId, authorizationId);
        visibility.blockAuthorization(projectId, authorizationId, true);
    }

    @Scheduled(fixedDelayString = "${connector.oauth.recovery-delay-ms:60000}")
    public void recoverAbandonedRefreshes() {
        for (var grant : grants.markAbandonedRefreshes()) {
            visibility.blockAuthorization(grant.projectId(), grant.id(), false);
        }
    }

    @Scheduled(fixedDelayString = "${connector.oauth.refresh-delay-ms:60000}")
    public void refreshDue() {
        for (var grant : grants.dueRefreshes()) {
            try {
                tokens.accessToken(grant.projectId(), grant.id());
            } catch (BusinessException failure) {
                // Status and retry timing are handled by the token service; no credentials are logged.
            }
        }
    }

    private void validateRedirect() {
        try {
            URI uri = URI.create(redirectUri);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !"/api/v1/connector/oauth/callback".equals(uri.getPath())) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.CONNECTOR_AUTHORIZATION_UNCERTAIN, "飞书 OAuth 回调地址未安全配置");
        }
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String hash(String value) { return HexFormat.of().formatHex(sha256(value)); }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    public record StartView(String authorizationUrl, Instant expiresAt) {
        @Override public String toString() { return "StartView[authorizationUrl=redacted,expiresAt=" + expiresAt + "]"; }
    }
    public record GrantView(UUID id, UUID connectionId, String status, Instant accessExpiresAt) {}
    public record ConnectionOption(UUID id, String name) {}
}
