package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.opencodezen.OpenCodeZenFreeAdmissionPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JDK HttpClient Zen transport. Policy (endpoint/identity) comes from {@link ProviderPresetRegistry}.
 * Explicit DIRECT (NO_PROXY), Redirect.NEVER, runtime endpoint validation.
 *
 * <p>免费准入由共享库 {@code OpenCodeZenFreeAdmissionPolicy} 决定；本类只把白名单与上游实时列表相交，
 * 供界面列出可选模型。凭证无关的公共模型目录不再被抓取。</p>
 */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {
    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final ProviderPresetRegistry registry;
    private final OpenCodeZenRequestFactory requestFactory;
    private final HttpClient production;
    private final HttpClient settings;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints,
            ProviderPresetRegistry registry, OpenCodeZenRequestFactory requestFactory) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.requestFactory = requestFactory;
        this.production = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        this.settings = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(45)).build();
    }

    public HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry) {
        this(mapper, endpoints, registry,
                new OpenCodeZenRequestFactory(registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE).userAgent()));
    }

    HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry, HttpClient production, HttpClient settings) {
        this(mapper, endpoints, registry,
                new OpenCodeZenRequestFactory(registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE).userAgent()),
                production, settings);
    }

    HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry,
            OpenCodeZenRequestFactory requestFactory, HttpClient production, HttpClient settings) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.requestFactory = requestFactory;
        this.production = production;
        this.settings = settings;
    }

    ProviderPresetRegistry.PresetPolicy policy() {
        return registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
    }

    public static boolean isDirect(HttpClient client) { return client.proxy().map(s -> s == HttpClient.Builder.NO_PROXY).orElse(false); }

    public HttpClient productionClient() { return production; }

    @Override
    public List<String> admittedModels(String apiKey, AiRequestMetadata metadata) {
        return intersectWithAdmitted(upstreamModelIds(apiKey, metadata));
    }

    /**
     * 与共享库已评审白名单相交：只保留白名单内且当前上游仍在服务的模型，按 id 排序保证稳定输出。
     * {@code upstreamIds} 为 {@code null}（上游不可用）时退回白名单本身——准入始终以白名单为准。
     */
    static List<String> intersectWithAdmitted(List<String> upstreamIds) {
        Set<String> live = upstreamIds == null ? null : new HashSet<>(upstreamIds);
        return OpenCodeZenFreeAdmissionPolicy.ADMITTED_FREE_CHAT_MODELS.stream()
                .filter(id -> live == null || live.contains(id))
                .sorted()
                .toList();
    }

    /** 上游 {@code GET /models} 的模型 id 列表；不可用或结构异常时返回 {@code null}，由调用方保守回退。 */
    private List<String> upstreamModelIds(String apiKey, AiRequestMetadata metadata) {
        try {
            JsonNode root = listModelsRaw(apiKey, metadata);
            JsonNode data = root == null ? null : root.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
            return ids;
        } catch (Exception unavailable) {
            // 上游不可用：不把任何模型臆测为可用，退回白名单。
            return null;
        }
    }

    private JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) {
        ProviderPresetRegistry.PresetPolicy pol = policy();
        URI uri = URI.create(registry.modelsEndpoint(pol));
        endpoints.requirePublicHttps(uri);
        // 一次性模型发现：ses_/msg_ 标识与身份头统一由共享请求工厂生成（shape 校验失败即本地拒绝）。
        HttpRequest.Builder b = requestFactory.newEphemeralRequest(uri, true, apiKey)
                .timeout(Duration.ofSeconds(45)).GET();
        try {
            HttpResponse<String> resp = settings.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonHttpModelClient.checkStatus(resp.statusCode(), resp.body(), mapper);
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    @Override
    public void validateCredential(String apiKey, String model, AiRequestMetadata metadata) {
        if (model == null || model.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        // Use the same client envelope and SSE parser as inference, with a bounded
        // settings deadline. A completion probe does not certify tools or planning.
        try (var probe = new ProbeClient(mapper, endpoints, settings)) {
            var pol = policy();
            var now = java.time.OffsetDateTime.now();
            var config = new ModelConfiguration(null, null, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), null, model.strip(), true, 0, 256, pol.capabilities(), now, now);
            new OpenAiCompatibleModelAdapter(mapper, probe.client, requestFactory)
                    .completeStreamingSyncWithSession(config,
                    apiKey == null ? null : apiKey.strip(), new com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand(
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
