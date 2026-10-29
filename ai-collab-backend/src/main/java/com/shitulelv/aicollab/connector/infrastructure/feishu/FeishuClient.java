package com.shitulelv.aicollab.connector.infrastructure.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feishu OpenAPI client. The base URL is a fixed, trusted official endpoint;
 * redirects are rejected to rule out open-proxy hops. Tokens and secrets are
 * never logged; error messages surface only Feishu's own business message.
 */
@Component
public class FeishuClient {
    public static final String BASE_URL = "https://open.feishu.cn";
    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long TOKEN_EARLY_EXPIRE_MS = 5 * 60 * 1000;

    private final HttpClient http;
    private final URI baseUri;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public FeishuClient() {
        this(OutboundHttpClients.builder().connectTimeout(Duration.ofSeconds(5)).build(), URI.create(BASE_URL));
    }

    FeishuClient(HttpClient http, URI baseUri) {
        this.http = http;
        this.baseUri = baseUri;
    }

    public String tenantAccessToken(String appId, String appSecret) {
        CachedToken cached = tokenCache.get(appId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now) {
            return cached.token;
        }
        synchronized (tokenCache) {
            cached = tokenCache.get(appId);
            if (cached != null && cached.expiresAtMs > now) {
                return cached.token;
            }
            JsonNode body = postFormJson("/open-apis/auth/v3/tenant_access_token/internal",
                    Map.of("app_id", appId, "app_secret", appSecret));
            if (body.path("code").asInt(-1) != 0) {
                throw new FeishuApiException(body.path("code").asInt(-1),
                        "飞书应用凭证接口返回错误，错误码=" + body.path("code").asInt(-1));
            }
            String token = body.path("tenant_access_token").asText();
            long expireMs = body.path("expire").asLong(7200) * 1000;
            cached = new CachedToken(token, now + expireMs - TOKEN_EARLY_EXPIRE_MS);
            tokenCache.put(appId, cached);
            return token;
        }
    }

    public void invalidateToken(String appId) {
        tokenCache.remove(appId);
    }

    /** Lists every Docx document inside the folder, following all pages. */
    public List<FeishuFile> listFolderDocx(String token, String folderToken) {
        List<FeishuFile> result = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < 200; page++) {
            String path = "/open-apis/drive/v1/files?folder_token=" + urlEncode(folderToken)
                    + "&page_size=50" + (pageToken == null ? "" : "&page_token=" + urlEncode(pageToken));
            JsonNode data = getJson(token, path);
            if (!data.path("files").isArray() || !data.path("has_more").isBoolean()) {
                throw new FeishuApiException(-1, "文件夹分页响应不完整，请稍后重试");
            }
            for (JsonNode item : data.path("files")) {
                if (!item.isObject() || !item.path("token").isTextual() || item.path("token").asText().isBlank()
                        || !item.path("type").isTextual() || item.path("type").asText().isBlank()) {
                    throw new FeishuApiException(-1, "文件夹分页响应不完整，请稍后重试");
                }
                FeishuFile file = new FeishuFile(
                        item.path("token").asText(),
                        item.path("name").asText(),
                        item.path("type").asText(),
                        item.path("url").asText(null),
                        item.hasNonNull("modified_time") ? item.path("modified_time").asLong() : null);
                if (file.isDocx()) result.add(file);
            }
            pageToken = data.path("next_page_token").asText(null);
            if (!data.path("has_more").asBoolean() && pageToken != null && !pageToken.isBlank()) {
                throw new FeishuApiException(-1, "文件夹分页响应不一致，请稍后重试");
            }
            if (pageToken == null || pageToken.isBlank()) {
                if (data.path("has_more").asBoolean(false)) {
                    throw new FeishuApiException(-1, "文件夹分页响应不完整，请稍后重试");
                }
                return result;
            }
        }
        // A capped scan is not a complete snapshot and must never drive removals.
        throw new FeishuApiException(-1, "文件夹分页未完整读取，请稍后重试");
    }

    public String docxRawContent(String token, String documentId) {
        JsonNode data = getJson(token, "/open-apis/docx/v1/documents/" + urlEncode(documentId) + "/raw_content");
        return data.path("content").asText("");
    }

    public UserIdentity currentUser(String token) {
        JsonNode data = getJson(token, "/open-apis/authen/v1/user_info");
        String openId = data.path("open_id").asText("");
        String tenantKey = data.path("tenant_key").asText("");
        if (openId.isBlank() || tenantKey.isBlank()) throw new FeishuApiException(-1, "飞书用户身份信息不完整");
        return new UserIdentity(openId, tenantKey);
    }

    public record UserIdentity(String openId, String tenantKey) {
        @Override public String toString() { return "UserIdentity[redacted]"; }
    }

    /** Best-effort collaborator evidence for the folder; callers must tolerate failure. */
    public List<FeishuPermissionMember> folderPermissionMembers(String token, String folderToken) {
        try {
            JsonNode data = getJson(token,
                    "/open-apis/drive/v1/permissions/" + urlEncode(folderToken) + "/members?type=folder");
            List<FeishuPermissionMember> members = new ArrayList<>();
            for (JsonNode item : data.path("items")) {
                members.add(new FeishuPermissionMember(
                        item.path("member_id").asText(null),
                        item.path("member_type").asText(null),
                        item.path("perm").asText(null)));
            }
            return members;
        } catch (RuntimeException exception) {
            log.info("读取飞书文件夹协作者列表失败（不影响主流程）：{}", exception.getMessage());
            return List.of();
        }
    }

    private JsonNode getJson(String token, String pathWithQuery) {
        return execute(token, pathWithQuery, null, 0);
    }

    private JsonNode postFormJson(String path, Map<String, String> fields) {
        try {
            String json = mapper.writeValueAsString(fields);
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429 || response.statusCode() >= 500) {
                        if (attempt + 1 == MAX_ATTEMPTS) throw new FeishuApiException(response.statusCode(), "飞书服务暂时不可用");
                        sleepBackoff(attempt);
                        continue;
                    }
                    return mapper.readTree(response.body());
                } catch (IOException failure) {
                    if (attempt + 1 == MAX_ATTEMPTS) throw failure;
                    sleepBackoff(attempt);
                }
            }
            throw new FeishuApiException(-1, "连接飞书服务失败，请稍后重试");
        } catch (IOException exception) {
            throw new FeishuApiException(-1, "连接飞书服务失败，请稍后重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FeishuApiException(-1, "请求飞书服务被中断");
        }
    }

    private JsonNode execute(String token, String pathWithQuery, JsonNode retryBody, int attempt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(pathWithQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET();
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                if (attempt + 1 < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                    return execute(token, pathWithQuery, retryBody, attempt + 1);
                }
                throw new FeishuApiException(status, "飞书服务暂时不可用（HTTP " + status + "），请稍后重试");
            }
            if (status < 200 || status >= 300) {
                int code = status;
                try {
                    JsonNode error = mapper.readTree(response.body());
                    if (error != null && error.path("code").isIntegralNumber() && error.path("code").asInt() != 0) {
                        code = error.path("code").asInt();
                    }
                } catch (IOException ignored) {
                    // HTTP status remains authoritative even for non-JSON error bodies.
                }
                throw new FeishuApiException(code, "飞书接口调用失败（HTTP " + status + "）");
            }
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.path("code").isIntegralNumber()) {
                throw new FeishuApiException(-1, "飞书接口响应不完整，请稍后重试");
            }
            int code = body.path("code").asInt();
            if (code != 0) {
                if (code == 99991400 && attempt + 1 < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                    return execute(token, pathWithQuery, retryBody, attempt + 1);
                }
                FeishuApiException exception = new FeishuApiException(code, "飞书接口调用失败，错误码=" + code);
                if (exception.invalidToken() && attempt + 1 < MAX_ATTEMPTS) {
                    throw exception;
                }
                throw exception;
            }
            JsonNode data = body.path("data");
            if (!data.isObject()) throw new FeishuApiException(-1, "飞书接口响应不完整，请稍后重试");
            return data;
        } catch (IOException exception) {
            if (attempt + 1 < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
                return execute(token, pathWithQuery, retryBody, attempt + 1);
            }
            throw new FeishuApiException(-1, "连接飞书服务失败，请检查网络后重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FeishuApiException(-1, "请求飞书服务被中断");
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * (1L << attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record CachedToken(String token, long expiresAtMs) {
    }
}
