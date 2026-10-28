package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义 Provider 的模型列表发现：辅助选择，不是必要依赖。
 *
 * <p>发现失败（网关无 /models、鉴权差异、网络问题）返回分类错误，
 * 前端降级为手动填写模型 ID——绝不因发现失败推断真实生成必然失败。</p>
 *
 * <p>只读 GET、无请求体、不消耗推理配额；结果按 provider 内存缓存 5 分钟，
 * 与 Zen/Kilo 预设发现的缓存节奏一致。缓存键是 provider UUID（发现使用该行
 * 已保存的加密凭据解密后的密钥），provider 更新后凭据或端点可能变化，
 * 因此配置变更时应强制刷新（前端在保存后携带 refresh=true）。</p>
 */
@Service
public class ProviderModelDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(ProviderModelDiscoveryService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED_PROVIDERS = 512;
    /** 路径段形式的 models 段（前后是 /、: 或结尾），如 /v1beta/models/{model}:generateContent。 */
    private static final Pattern MODELS_SEGMENT = Pattern.compile("(^|/)models(/|:|$)");
    private static final List<String> CHAT_COMPLETIONS_SUFFIXES = List.of("/chat/completions", "/messages");

    private record CacheEntry(List<String> models, Instant fetchedAt) {
    }

    private final UserAiProviderRepository repository;
    private final UserAiProviderService providers;
    private final ModelSecretCipher secrets;
    private final JsonHttpModelClient http;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderModelDiscoveryService(
            UserAiProviderRepository repository,
            UserAiProviderService providers,
            ModelSecretCipher secrets,
            JsonHttpModelClient http) {
        this.repository = repository;
        this.providers = providers;
        this.secrets = secrets;
        this.http = http;
    }

    public List<String> models(UUID userId, UUID providerId, boolean refresh) {
        UserAiProvider provider = repository.findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在"));
        if (provider.presetCode() != null) {
            // 预设走各自的准入发现（免费模型白名单），不允许通过自定义通道绕开准入规则。
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "预置连接请使用各自的模型列表");
        }
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能发现已停用模型配置的模型列表");
        }
        if (!refresh) {
            CacheEntry cached = cache.get(providerId);
            if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return cached.models();
            }
        }
        List<String> models = fetchModels(provider).stream().sorted().toList();
        if (cache.size() >= MAX_CACHED_PROVIDERS) {
            cache.clear();
        }
        cache.put(providerId, new CacheEntry(models, Instant.now()));
        log.info("Discovered {} models for provider {} ({})", models.size(), provider.name(), provider.providerType());
        return models;
    }

    /**
     * 未保存配置的模型发现：用户在表单里输入 Key 后即可一键获取模型列表，
     * 无需先保存 Provider。只读 GET、不落库、不缓存、不消耗推理配额。
     *
     * <p>端点校验（HTTPS/公网/防 SSRF/重复版本前缀）与保存校验完全同口径；
     * 传入的 Key 只用于本次出站请求，不写入任何存储，也不进日志。</p>
     */
    public List<String> discoverUnsaved(UUID userId, ModelProviderType providerType,
            String baseUrl, String apiPath, String apiKey) {
        providers.validateEndpoint(baseUrl, apiPath);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        JsonNode response = http.get(
                discoveryUrl(baseUrl, apiPath),
                discoveryHeaders(providerType, apiKey));
        List<String> models = parseModels(providerType, response);
        if (models.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型列表为空或格式无法识别，请手动填写模型 ID");
        }
        log.info("Discovered {} models for unsaved {} configuration of user {}",
                models.size(), providerType, userId);
        return models.stream().sorted().toList();
    }

    private List<String> fetchModels(UserAiProvider provider) {
        String url = discoveryUrl(provider.baseUrl(), provider.apiPath());
        String apiKey = secrets.decrypt(provider.encryptedApiKey());
        JsonNode response = http.get(url, discoveryHeaders(provider.providerType(), apiKey));
        List<String> models = parseModels(provider.providerType(), response);
        if (models.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型列表为空或格式无法识别，请手动填写模型 ID");
        }
        return models;
    }

    /** 拼接发现端点：与推理端点同前缀，出站安全校验由 JsonHttpModelClient 统一执行。 */
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

    /** MODELS_SEGMENT 匹配可能吞掉尾部的 / 或 :，截断时保留它们之外的部分。 */
    private static int suffixLength(String apiPath, int matchEnd) {
        char last = apiPath.charAt(matchEnd - 1);
        return last == '/' || last == ':' ? 1 : 0;
    }

    /** 认证头与各协议推理适配器保持一致，避免发现与推理用不同的鉴权方式。 */
    private static Map<String, String> discoveryHeaders(ModelProviderType providerType, String apiKey) {
        return switch (providerType) {
            case OPENAI_COMPATIBLE -> Map.of("Authorization", "Bearer " + apiKey);
            case ANTHROPIC -> Map.of(
                    "x-api-key", apiKey,
                    "anthropic-version", "2023-06-01");
            case GEMINI -> Map.of("x-goog-api-key", apiKey);
        };
    }

    /** OpenAI/Anthropic 返回 data[].id；Gemini 返回 models[].name（形如 models/gemini-...）。 */
    static List<String> parseModels(ModelProviderType providerType, JsonNode response) {
        List<String> models = new ArrayList<>();
        if (providerType == ModelProviderType.GEMINI) {
            for (JsonNode model : response.path("models")) {
                String name = model.path("name").asText("");
                if (name.startsWith("models/")) name = name.substring("models/".length());
                if (!name.isBlank()) models.add(name);
            }
            return models;
        }
        for (JsonNode model : response.path("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) models.add(id);
        }
        return models;
    }
}
