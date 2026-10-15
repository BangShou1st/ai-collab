package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** JDK HttpClient Zen transport. Policy (endpoint/identity) comes from {@link ProviderPresetRegistry}. Explicit DIRECT (NO_PROXY), Redirect.NEVER, runtime endpoint validation. */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {
    /** 可信公开模型目录：定价、退役状态与原生协议的事实来源（凭证无关）。 */
    static final String TRUSTED_CATALOG_URL = "https://models.opencode.ai/api.json";
    /** 目录缓存时长：命中时不再重复访问公共目录。 */
    static final Duration CATALOG_TTL = Duration.ofMinutes(30);
    /** 目录获取失败后的短退避：避免每次用户请求都重试公共目录。 */
    static final Duration CATALOG_FAILURE_TTL = Duration.ofMinutes(5);

    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final ProviderPresetRegistry registry;
    private final HttpClient production;
    private final HttpClient settings;
    private final Object catalogLock = new Object();
    private JsonNode catalogCache;
    private Instant catalogExpires = Instant.EPOCH;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.production = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        this.settings = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(45)).build();
    }
    HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry, HttpClient production, HttpClient settings) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.production = production;
        this.settings = settings;
    }
    ProviderPresetRegistry.PresetPolicy policy() {
        return registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
    }
    public static boolean isDirect(HttpClient client) { return client.proxy().map(s -> s == HttpClient.Builder.NO_PROXY).orElse(false); }
    public HttpClient productionClient() { return production; }
    @Override
    public JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) {
        try {
            ProviderPresetRegistry.PresetPolicy pol = policy();
            URI uri = URI.create(registry.modelsEndpoint(pol));
            endpoints.requirePublicHttps(uri);
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(45)).GET()
                    .header("Accept", "application/json");
            OpenAiCompatibleModelAdapter.headersWithSession(apiKey, metadata, pol.userAgent()).forEach(b::header);
            HttpResponse<String> resp = settings.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonHttpModelClient.checkStatus(resp.statusCode(), resp.body(), mapper);
            return mapper.readTree(resp.body());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR); }
    }
    @Override
    public ZenFreeModelSelection freeModelSelection(String apiKey, AiRequestMetadata metadata) {
        // 可用性来自当前上游列表；免费与协议由可信目录元数据判定；元数据不可用时保守回退。
        return ZenFreeModelSelection.select(listModelsRaw(apiKey, metadata), trustedModelCatalog());
    }

    /**
     * 可信公开模型目录（凭证无关，只做只读 GET，不带任何用户凭证）。
     * 获取失败时返回 {@code null}，由选择规则保守回退；失败与命中都受有界 TTL 保护，
     * 不会每次用户请求都访问公共目录，也不会在日志中输出凭证或完整响应体。
     */
    JsonNode trustedModelCatalog() {
        synchronized (catalogLock) {
            if (Instant.now().isBefore(catalogExpires)) {
                return catalogCache;
            }
            JsonNode fetched = null;
            try {
                URI uri = URI.create(TRUSTED_CATALOG_URL);
                endpoints.requirePublicHttps(uri);
                HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(20)).GET()
                        .header("Accept", "application/json").build();
                HttpResponse<String> response = settings.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonHttpModelClient.checkStatus(response.statusCode(), response.body(), mapper);
                JsonNode root = mapper.readTree(response.body());
                if (root != null && root.isObject()) {
                    fetched = root;
                }
            } catch (Exception exception) {
                // 目录不可用：保守回退，仅保留免费后缀模型；不抛出、不记录凭证。
                fetched = null;
            }
            catalogCache = fetched;
            catalogExpires = Instant.now().plus(fetched == null ? CATALOG_FAILURE_TTL : CATALOG_TTL);
            return catalogCache;
        }
    }

    @Override
    public void validateCredential(String apiKey, String model, AiRequestMetadata metadata) {
        if (apiKey == null || apiKey.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key required");
        if (model == null || model.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        // Use the same client envelope and SSE parser as inference, with a bounded
        // settings deadline. A completion probe does not certify tools or planning.
        try (var probe = new ProbeClient(mapper, endpoints, settings)) {
            var pol = policy();
            var now = java.time.OffsetDateTime.now();
            var config = new ModelConfiguration(null, null, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), null, model.strip(), true, 0, 256, pol.capabilities(), now, now);
            new OpenAiCompatibleModelAdapter(mapper, probe.client).completeStreamingSyncWithSession(config,
                    apiKey.strip(), new com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand(
                            "Synthetic connection probe.", "Return only {\"action\":\"finish\"}."), metadata, pol.userAgent());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR); }
    }
    public static ProxySelector directSelector() { return HttpClient.Builder.NO_PROXY; }
    public static Proxy directProxy() { return Proxy.NO_PROXY; }
    private static final class ProbeClient implements AutoCloseable {
        final JsonHttpModelClient client;
        ProbeClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient http) {
            client = new JsonHttpModelClient(mapper, endpoints, http, Duration.ofSeconds(45));
        }
        @Override public void close() { client.close(); }
    }
}
