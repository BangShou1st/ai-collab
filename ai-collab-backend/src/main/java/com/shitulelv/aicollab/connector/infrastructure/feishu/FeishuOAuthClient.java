package com.shitulelv.aicollab.connector.infrastructure.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feishu user_access_token OAuth client. The authorize endpoint
 * {@code accounts.feishu.cn/open-apis/authen/v1/authorize} pairs with the v2 token
 * endpoint {@code open.feishu.cn/open-apis/authen/v2/oauth/token}, which is the only
 * endpoint that validates PKCE (the v3 {@code oauth/v3/token} endpoint is for
 * app/tenant tokens and rejects PKCE codes with error 20049). A lost response to a
 * single-use token is never blindly replayed.
 */
@Component
public class FeishuOAuthClient {
    private static final Logger log = LoggerFactory.getLogger(FeishuOAuthClient.class);
    private static final URI TOKEN_ENDPOINT = URI.create("https://open.feishu.cn/open-apis/authen/v2/oauth/token");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient http;
    private final URI endpoint;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public FeishuOAuthClient() {
        this(OutboundHttpClients.builder().connectTimeout(Duration.ofSeconds(5)).build(), TOKEN_ENDPOINT,
                Clock.systemUTC());
    }

    FeishuOAuthClient(HttpClient http, URI endpoint, Clock clock) {
        this.http = http;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    public Tokens exchange(String clientId, String clientSecret, String code, String redirectUri, String verifier) {
        Map<String, String> form = base(clientId, clientSecret, "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        return request(form, false);
    }

    public Tokens refresh(String clientId, String clientSecret, String refreshToken) {
        Map<String, String> form = base(clientId, clientSecret, "refresh_token");
        form.put("refresh_token", refreshToken);
        return request(form, true);
    }

    private Map<String, String> base(String clientId, String clientSecret, String grantType) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", grantType);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        return form;
    }

    private Tokens request(Map<String, String> form, boolean refresh) {
        var body = json.createObjectNode();
        for (var entry : form.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new FeishuOAuthException(FeishuOAuthException.Kind.CONFIGURATION);
            }
            body.put(entry.getKey(), entry.getValue());
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) throw new FeishuOAuthException(FeishuOAuthException.Kind.TEMPORARY);
            if (status >= 500) throw new FeishuOAuthException(FeishuOAuthException.Kind.OUTCOME_UNKNOWN);
            JsonNode data = json.readTree(response.body());
            int code = data.path("code").asInt(-1);
            if (code != 0 || status < 200 || status >= 300) {
                // 可观测性：记录飞书返回的错误分类与描述（不含任何令牌或密钥）。
                log.warn("飞书 OAuth 端点拒绝: status={}, feishuCode={}, msg={}", status, code,
                        data.path("msg").asText(data.path("error_description").asText("")));
                if (code == 20064) throw new FeishuOAuthException(FeishuOAuthException.Kind.REVOKED);
                if (code == 20026 || code == 20037 || code == 20073 || code == 99991669) {
                    throw new FeishuOAuthException(FeishuOAuthException.Kind.REAUTH_REQUIRED);
                }
                if (code == 20072) throw new FeishuOAuthException(FeishuOAuthException.Kind.TEMPORARY);
                if (!refresh && (code == 20003 || code == 20004 || code == 20065)) {
                    // 单次授权码无效、过期或已被使用：用户必须重新发起授权，同一个 Code 不得重放。
                    throw new FeishuOAuthException(FeishuOAuthException.Kind.REAUTH_REQUIRED);
                }
                if (code == 20071 || code == 20074) {
                    throw new FeishuOAuthException(FeishuOAuthException.Kind.CONFIGURATION);
                }
                throw new FeishuOAuthException(FeishuOAuthException.Kind.CONFIGURATION);
            }
            String access = data.path("access_token").asText("");
            String rotatedRefresh = data.path("refresh_token").asText("");
            long accessSeconds = data.path("expires_in").asLong(0);
            long refreshSeconds = data.path("refresh_token_expires_in").asLong(0);
            String scope = data.path("scope").asText("");
            if (access.isBlank() || rotatedRefresh.isBlank() || accessSeconds <= 0 || refreshSeconds <= 0
                    || (!refresh && scope.isBlank())) {
                throw new FeishuOAuthException(refresh ? FeishuOAuthException.Kind.OUTCOME_UNKNOWN
                        : FeishuOAuthException.Kind.CONFIGURATION);
            }
            Instant now = clock.instant();
            return new Tokens(access, rotatedRefresh, now.plusSeconds(accessSeconds),
                    now.plusSeconds(refreshSeconds), scope);
        } catch (IOException exception) {
            throw new FeishuOAuthException(FeishuOAuthException.Kind.OUTCOME_UNKNOWN);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FeishuOAuthException(FeishuOAuthException.Kind.OUTCOME_UNKNOWN);
        }
    }

    public record Tokens(String accessToken, String refreshToken, Instant accessExpiresAt,
                         Instant refreshExpiresAt, String scope) {
        @Override public String toString() {
            return "Tokens[redacted]";
        }
    }
}
