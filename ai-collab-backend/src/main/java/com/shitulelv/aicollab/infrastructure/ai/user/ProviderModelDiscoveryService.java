package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.user.api.DiscoveredModelView;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义 Provider 的模型列表与元数据发现：通过 {@code ai-model-bridge} 的
 * {@link ModelDiscoveryClient} 拉取模型 ID 与上下文窗口、输出上限、工具/推理支持等字段。
 *
 * <p>发现失败（网关无 /models、鉴权差异、网络问题）返回分类错误，
 * 前端降级为手动填写模型 ID——绝不因发现失败推断真实生成必然失败。</p>
 */
@Service
public class ProviderModelDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(ProviderModelDiscoveryService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED_PROVIDERS = 512;
    private static final Pattern MODELS_SEGMENT = Pattern.compile("(^|/)models(/|:|$)");
    private static final List<String> CHAT_COMPLETIONS_SUFFIXES = List.of("/chat/completions", "/messages");

    private record CacheEntry(List<DiscoveredModelView> models, Instant fetchedAt) {
    }

    private final UserAiProviderRepository repository;
    private final UserAiProviderService providers;
    private final ModelSecretCipher secrets;
    private final JsonHttpModelClient http;
    private final ModelDiscoveryClient bridgeDiscovery;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderModelDiscoveryService(
            UserAiProviderRepository repository,
            UserAiProviderService providers,
            ModelSecretCipher secrets,
            JsonHttpModelClient http) {
        this(repository, providers, secrets, http, null);
    }

    @Autowired
    public ProviderModelDiscoveryService(
            UserAiProviderRepository repository,
            UserAiProviderService providers,
            ModelSecretCipher secrets,
            JsonHttpModelClient http,
            ModelDiscoveryClient bridgeDiscovery) {
        this.repository = repository;
        this.providers = providers;
        this.secrets = secrets;
        this.http = http;
        this.bridgeDiscovery = bridgeDiscovery;
    }

    public List<String> models(UUID userId, UUID providerId, boolean refresh) {
        return modelDetails(userId, providerId, refresh).stream()
                .map(DiscoveredModelView::id)
                .toList();
    }

    public List<DiscoveredModelView> modelDetails(UUID userId, UUID providerId, boolean refresh) {
        UserAiProvider provider = repository.findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在"));
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能发现已停用模型配置的模型列表");
        }
        if (!refresh) {
            CacheEntry cached = cache.get(providerId);
            if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return cached.models();
            }
        }
        String apiKey = secrets.decrypt(provider.encryptedApiKey());
        List<DiscoveredModelView> discovered = fetchDescriptors(
                providerId.toString(),
                provider.providerType(),
                provider.baseUrl(),
                provider.apiPath(),
                apiKey);
        if (cache.size() >= MAX_CACHED_PROVIDERS) {
            cache.clear();
        }
        cache.put(providerId, new CacheEntry(discovered, Instant.now()));
        log.info("Discovered {} models (metadata via {}) for provider {} ({})",
                discovered.size(),
                bridgeDiscovery != null ? "ai-model-bridge" : "legacy-http",
                provider.name(),
                provider.providerType());
        return discovered;
    }

    public List<String> discoverUnsaved(UUID userId, ModelProviderType providerType,
            String baseUrl, String apiPath, String apiKey) {
        return discoverUnsavedModelDetails(userId, providerType, baseUrl, apiPath, apiKey).stream()
                .map(DiscoveredModelView::id)
                .toList();
    }

    /**
     * 未保存配置的模型发现（含元数据）：Key 只用于本次只读 GET，不落库、不缓存。
     */
    public List<DiscoveredModelView> discoverUnsavedModelDetails(UUID userId, ModelProviderType providerType,
            String baseUrl, String apiPath, String apiKey) {
        providers.validateEndpoint(baseUrl, apiPath);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        List<DiscoveredModelView> models = fetchDescriptors(
                "unsaved-" + userId, providerType, baseUrl, apiPath, apiKey.strip());
        log.info("Discovered {} models for unsaved {} configuration of user {}",
                models.size(), providerType, userId);
        return models;
    }

    private List<DiscoveredModelView> fetchDescriptors(
            String identity, ModelProviderType providerType,
            String baseUrl, String apiPath, String apiKey) {
        List<DiscoveredModelView> discovered;
        if (bridgeDiscovery != null) {
            discovered = fetchViaBridge(identity, providerType, baseUrl, apiPath, apiKey);
        } else {
            JsonNode response = http.get(
                    discoveryUrl(baseUrl, apiPath),
                    discoveryHeaders(providerType, apiKey));
            discovered = parseModelDescriptors(providerType, response);
        }
        if (discovered.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型列表为空或格式无法识别，请手动填写模型 ID");
        }
        return discovered.stream()
                .sorted(Comparator.comparing(DiscoveredModelView::id))
                .toList();
    }

    private List<DiscoveredModelView> fetchViaBridge(
            String identity, ModelProviderType providerType,
            String baseUrl, String apiPath, String apiKey) {
        ProtocolId protocol = switch (providerType) {
            case OPENAI_COMPATIBLE -> ProtocolId.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProtocolId.ANTHROPIC;
            case GEMINI -> ProtocolId.GEMINI;
            case LOCAL_CUSTOM -> throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "不支持 LOCAL_CUSTOM 模型发现");
        };
        EndpointConfig.Builder endpoints = EndpointConfig.builder()
                .modelsPath(discoveryUrl(baseUrl, apiPath));
        if (providerType == ModelProviderType.ANTHROPIC) {
            endpoints.anthropicVersion("2023-06-01");
        }
        ProviderConfig config = ProviderConfig.builder(identity, protocol)
                .baseUrl(baseUrl.strip())
                .apiKey(apiKey)
                .connectTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(30))
                .endpointConfig(endpoints.build())
                .build();
        try {
            List<ModelDescriptor> descriptors = bridgeDiscovery.discover(config);
            return descriptors.stream()
                    .filter(d -> d.id() != null && !d.id().isBlank())
                    .map(DiscoveredModelView::from)
                    .toList();
        } catch (ModelBridgeException failure) {
            throw translateDiscoveryError(failure);
        }
    }

    private static BusinessException translateDiscoveryError(ModelBridgeException failure) {
        int status = failure.upstreamStatus() > 0
                ? failure.upstreamStatus()
                : failure.category().statusCode();
        return switch (failure.category()) {
            case AUTHENTICATION -> new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID,
                    "模型 API Key 无效或无权访问 (HTTP " + status + ")");
            case AUTHORIZATION -> new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "模型服务拒绝访问模型列表 (HTTP " + status + ")");
            case TIMEOUT -> new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            case NETWORK_POLICY -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "模型发现地址不符合出站安全策略");
            case RATE_LIMIT -> new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
            case INVALID_RESPONSE, SCHEMA -> new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型列表响应格式无法识别，请手动填写模型 ID");
            default -> new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "获取模型列表失败 (HTTP " + status + ")");
        };
    }

    static String discoveryUrl(UserAiProvider provider) {
        return discoveryUrl(provider.baseUrl(), provider.apiPath());
    }

    static String discoveryUrl(String baseUrl, String apiPath) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = apiPath.strip();
        Matcher modelsSegment = MODELS_SEGMENT.matcher(path);
        if (modelsSegment.find()) {
            return base + path.substring(0, modelsSegment.end() - suffixLength(path, modelsSegment.end()));
        }
        for (String suffix : CHAT_COMPLETIONS_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return base + path.substring(0, path.length() - suffix.length()) + "/models";
            }
        }
        return base + "/models";
    }

    private static int suffixLength(String apiPath, int matchEnd) {
        char last = apiPath.charAt(matchEnd - 1);
        return last == '/' || last == ':' ? 1 : 0;
    }

    private static Map<String, String> discoveryHeaders(ModelProviderType providerType, String apiKey) {
        return switch (providerType) {
            case OPENAI_COMPATIBLE -> Map.of("Authorization", "Bearer " + apiKey);
            case ANTHROPIC -> Map.of(
                    "x-api-key", apiKey,
                    "anthropic-version", "2023-06-01");
            case GEMINI -> Map.of("x-goog-api-key", apiKey);
            case LOCAL_CUSTOM -> Map.of();
        };
    }

    static List<String> parseModels(ModelProviderType providerType, JsonNode response) {
        return parseModelDescriptors(providerType, response).stream()
                .map(DiscoveredModelView::id)
                .toList();
    }

    static List<DiscoveredModelView> parseModelDescriptors(ModelProviderType providerType, JsonNode response) {
        List<DiscoveredModelView> models = new ArrayList<>();
        if (providerType == ModelProviderType.GEMINI) {
            for (JsonNode model : response.path("models")) {
                String name = model.path("name").asText("");
                if (name.startsWith("models/")) name = name.substring("models/".length());
                if (name.isBlank()) continue;
                Integer input = optionalInt(model, "inputTokenLimit");
                Integer output = optionalInt(model, "outputTokenLimit");
                models.add(new DiscoveredModelView(
                        name,
                        textOrFallback(model, "displayName", name),
                        "Google",
                        input,
                        input,
                        output,
                        true,
                        model.path("thinking").asBoolean(false) || model.path("supports_reasoning").asBoolean(false),
                        true,
                        true,
                        input != null || output != null,
                        List.of()));
            }
            return models;
        }
        for (JsonNode model : response.path("data")) {
            String id = model.path("id").asText("");
            if (id.isBlank()) continue;
            Integer contextWindow = optionalInt(model, "context_window", "context_length", "max_input_tokens");
            Integer maxInput = optionalInt(model, "max_input_tokens");
            if (maxInput == null) maxInput = contextWindow;
            Integer maxOutput = optionalInt(model, "max_output_tokens");
            boolean supportsTools = model.path("tool_call").asBoolean(false)
                    || model.path("supports_tools").asBoolean(false);
            boolean supportsReasoning = model.path("reasoning").asBoolean(false)
                    || model.path("supports_reasoning").asBoolean(false);
            models.add(new DiscoveredModelView(
                    id,
                    textOrFallback(model, "display_name", textOrFallback(model, "displayName", id)),
                    textOrFallback(model, "owned_by", null),
                    contextWindow,
                    maxInput,
                    maxOutput,
                    supportsTools,
                    supportsReasoning,
                    true,
                    true,
                    contextWindow != null || maxOutput != null || supportsTools || supportsReasoning,
                    List.of()));
        }
        return models;
    }

    private static Integer optionalInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber() && value.asInt() > 0) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    int parsed = Integer.parseInt(value.asText().strip());
                    if (parsed > 0) return parsed;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static String textOrFallback(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText();
        }
        return fallback;
    }
}
