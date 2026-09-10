package com.shitulelv.aicollab.infrastructure.ai.user;

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
    private final UserAiProviderRepository repository;
    private final ModelSecretCipher secrets;
    private final ProviderPresetRegistry registry;
    private final OpenCodeZenModelCatalog catalog;
    private final OpenCodeZenTransport transport;
    private final Map<String, CachedModels> cache = new ConcurrentHashMap<>();
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
        String ck = "public";
        if (!refresh) {
            CachedModels hit = cache.get(ck);
            if (hit != null && Instant.now().isBefore(hit.expires)) return hit.models;
        }
        List<String> models;
        try {
            models = catalog.freeModels(apiKey, AiRequestMetadata.fresh());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "model catalog temporarily unavailable, please retry"); }
        cache.put(ck, new CachedModels(List.copyOf(models), Instant.now().plus(Duration.ofMinutes(5))));
        return models;
    }
    @Transactional
    public PresetStatus save(UUID userId, String apiKey, String modelName, boolean enabled, boolean setDefault) {
        if (modelName == null || modelName.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        var existing = repository.findByUserAndPreset(userId, PRESET);
        String key = apiKey != null ? apiKey.strip() : "";
        String effectiveKey;
        if (existing.isEmpty()) {
            if (key.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key required");
            effectiveKey = key;
        } else {
            effectiveKey = key.isBlank() ? secrets.decrypt(existing.get().encryptedApiKey()) : key;
        }
        List<String> free = freeModels(effectiveKey, false);
        catalog.requireFreeModel(modelName.strip(), free);
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        boolean first = repository.countByUserId(userId) == 0;
        boolean makeDefault = setDefault || first || existing.map(UserAiProvider::isDefault).orElse(false);
        if (makeDefault) repository.clearDefault(userId);
        var now = java.time.OffsetDateTime.now();
        UserAiProvider row;
        if (existing.isEmpty()) {
            row = new UserAiProvider(UUID.randomUUID(), userId, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), secrets.encrypt(effectiveKey), modelName.strip(),
                    enabled, 0.2, 1200, EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                    makeDefault, now, now, PRESET);
        } else {
            var prev = existing.get();
            row = new UserAiProvider(prev.id(), userId, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), secrets.encrypt(effectiveKey), modelName.strip(),
                    enabled, prev.temperature(), prev.maxOutputTokens(), prev.capabilities(),
                    makeDefault, prev.createdAt(), now, PRESET);
        }
        repository.save(row);
        return presets(userId).get(0);
    }
    public void test(UUID userId, String apiKey, String modelName) {
        var existing = repository.findByUserAndPreset(userId, PRESET);
        String key = apiKey != null && !apiKey.isBlank() ? apiKey.strip()
                : existing.map(c -> secrets.decrypt(c.encryptedApiKey()))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key required"));
        String model = modelName != null && !modelName.isBlank() ? modelName.strip()
                : existing.map(UserAiProvider::modelName)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "model required"));
        List<String> free = freeModels(key, false);
        catalog.requireFreeModel(model, free);
        transport.validateCredential(key, model, AiRequestMetadata.fresh());
    }
    private record CachedModels(List<String> models, Instant expires) {}
}
