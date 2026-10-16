package com.shitulelv.aicollab.infrastructure.ai.user;

import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.OpenCodeZenModelCatalog;
import com.shitulelv.aicollab.infrastructure.ai.model.OpenCodeZenTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetRegistry;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenFreeModelSelection;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAiProviderPresetService {
    private static final String PRESET = ProviderPresetCode.OPENCODE_ZEN_FREE.name();
    /** 按凭证分桶的模型发现缓存：有界容量 + 短 TTL。 */
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final UserAiProviderRepository repository;
    private final ModelSecretCipher secrets;
    private final ProviderPresetRegistry registry;
    private final OpenCodeZenModelCatalog catalog;
    private final OpenCodeZenTransport transport;
    private final Map<String, CachedListing> cache = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public UserAiProviderPresetService(UserAiProviderRepository repository, ModelSecretCipher secrets,
            ProviderPresetRegistry registry, OpenCodeZenModelCatalog catalog, OpenCodeZenTransport transport) {
        this.repository = repository;
        this.secrets = secrets;
        this.registry = registry;
        this.catalog = catalog;
        this.transport = transport;
    }
    public UserAiProviderPresetService(UserAiProviderRepository repository, ModelSecretCipher secrets,
            ProviderPresetRegistry registry, OpenCodeZenModelCatalog catalog) {
        this(repository, secrets, registry, catalog,
                new com.shitulelv.aicollab.infrastructure.ai.model.HttpOpenCodeZenTransport(
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.shitulelv.aicollab.common.security.OutboundEndpointPolicy(), registry));
    }
    public record PresetStatus(String code, String displayName, boolean connected, String modelName,
            boolean enabled, boolean isDefault, boolean hasApiKey) {}
    public List<PresetStatus> presets(UUID userId) {
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        var conn = repository.findByUserAndPreset(userId, PRESET);
        boolean connected = conn.isPresent();
        return List.of(new PresetStatus(PRESET, pol.displayName(), connected,
                conn.map(UserAiProvider::modelName).orElse(null),
                conn.map(UserAiProvider::enabled).orElse(true),
                conn.map(UserAiProvider::isDefault).orElse(false),
                conn.map(c -> c.encryptedApiKey() != null && !c.encryptedApiKey().isBlank()).orElse(false)));
    }
    public List<String> models(UUID userId, boolean refresh) {
        String key = repository.findByUserAndPreset(userId, PRESET)
                .map(c -> { try { return secrets.decrypt(c.encryptedApiKey()); } catch (Exception e) { return null; } })
                .orElse(null);
        return freeModels(key, refresh);
    }
    public List<String> freeModels(String apiKey, boolean refresh) {
        return selection(apiKey, refresh).models();
    }
    /**
     * 发现当前可执行的 Zen 模型。缓存按凭证分桶（凭证哈希，非明文），
     * 不同用户凭证关联的非公开可用性不会被混在一起；缓存有界且带 TTL，
     * 不会每次用户请求都访问公共目录（公共目录自身由传输层缓存）。
     */
    public ZenFreeModelSelection selection(String apiKey, boolean refresh) {
        String cacheKey = cacheKey(apiKey);
        if (!refresh) {
            CachedListing hit = cache.get(cacheKey);
            if (hit != null && Instant.now().isBefore(hit.expires())) {
                return hit.listing();
            }
        }
        ZenFreeModelSelection listing;
        try {
            listing = catalog.selection(apiKey, AiRequestMetadata.fresh());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "model catalog temporarily unavailable, please retry"); }
        cacheListing(cacheKey, listing);
        return listing;
    }
    private void cacheListing(String cacheKey, ZenFreeModelSelection listing) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expires()));
            if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        }
        cache.put(cacheKey, new CachedListing(listing, Instant.now().plus(CACHE_TTL)));
    }
    /** 只保存凭证的不可逆摘要作为缓存键；凭证明文既不持久化到缓存，也不写日志。 */
    private static String cacheKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "anonymous";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            return "unavailable";
        }
    }
    @Transactional
    public PresetStatus save(UUID userId, String apiKey, String modelName, boolean enabled, boolean setDefault) {
        if (modelName == null || modelName.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        var existing = repository.findByUserAndPreset(userId, PRESET);
        String key = apiKey != null ? apiKey.strip() : "";
        String effectiveKey;
        if (existing.isEmpty()) {
            effectiveKey = key.isBlank() ? null : key;
        } else {
            effectiveKey = key.isBlank()
                    ? secrets.decryptOptional(existing.get().encryptedApiKey()) : key;
        }
        catalog.requireExecutableModel(modelName, selection(effectiveKey, false));
        requireCredentialForModel(effectiveKey, modelName);
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        boolean first = repository.countByUserId(userId) == 0;
        boolean makeDefault = setDefault || first || existing.map(UserAiProvider::isDefault).orElse(false);
        if (makeDefault) repository.clearDefault(userId);
        var now = java.time.OffsetDateTime.now();
        UserAiProvider row;
        // Preset rows always snapshot current policy numbers (the preset API exposes no temp/token fields).
        if (existing.isEmpty()) {
            row = new UserAiProvider(UUID.randomUUID(), userId, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), secrets.encrypt(effectiveKey), modelName.strip(),
                    enabled, pol.defaultTemperature(), pol.defaultMaxOutputTokens(), EnumSet.copyOf(pol.capabilities()),
                    makeDefault, now, now, PRESET);
        } else {
            var prev = existing.get();
            row = new UserAiProvider(prev.id(), userId, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), secrets.encrypt(effectiveKey), modelName.strip(),
                    enabled, pol.defaultTemperature(), pol.defaultMaxOutputTokens(), EnumSet.copyOf(pol.capabilities()),
                    makeDefault, prev.createdAt(), now, PRESET);
        }
        repository.save(row);
        return presets(userId).get(0);
    }
    /**
     * Disconnect the caller's Zen connection: delete only their OPENCODE_ZEN_FREE row
     * (encrypted key included). Purpose assignments cascade via FK; a deleted default
     * is not replaced — "no default" is allowed and nothing is auto-promoted.
     * Idempotent: missing connection deletes zero rows without error.
     */
    @Transactional
    public void disconnect(UUID userId) {
        repository.deleteByUserAndPreset(userId, PRESET);
    }

    public void test(UUID userId, String apiKey, String modelName) {
        var existing = repository.findByUserAndPreset(userId, PRESET);
        String key = apiKey != null && !apiKey.isBlank() ? apiKey.strip()
                : existing.map(c -> secrets.decryptOptional(c.encryptedApiKey())).orElse(null);
        String model = modelName != null && !modelName.isBlank() ? modelName.strip()
                : existing.map(UserAiProvider::modelName)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "model required"));
        catalog.requireExecutableModel(model, selection(key, false));
        requireCredentialForModel(key, model);
        transport.validateCredential(key, model, AiRequestMetadata.fresh());
    }

    private void requireCredentialForModel(String apiKey, String model) {
        try {
            OpenCodeZenCredentialPolicy.credentialForModel(catalog.isVerifiedFreeModel(model), apiKey);
        } catch (IllegalArgumentException missingKey) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "API Key required for models not verified as free");
        }
    }
    private record CachedListing(ZenFreeModelSelection listing, Instant expires) {}
}
