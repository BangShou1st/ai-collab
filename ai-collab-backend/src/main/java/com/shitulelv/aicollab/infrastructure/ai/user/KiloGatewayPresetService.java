package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kilo Gateway Free 预设连接：与 Zen 一样是服务端可信的产品预设，用户不能提交 baseUrl/路径。
 * 差别在于 Kilo 免费模型匿名调用，本预设不保存用户凭证，模型发现也不需要按凭证分桶。
 */
@Service
public class KiloGatewayPresetService {
    /** 无凭证，因此发现结果与用户无关；单条缓存 + 短 TTL 即可，避免每次请求都访问网关。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String PRESET = ProviderPresetCode.KILO_GATEWAY_FREE.name();
    private final UserAiProviderRepository repository;
    private final ProviderPresetRegistry registry;
    private final KiloGatewayTransport transport;
    private volatile CachedListing cache;

    @org.springframework.beans.factory.annotation.Autowired
    public KiloGatewayPresetService(UserAiProviderRepository repository, ProviderPresetRegistry registry,
            KiloGatewayTransport transport) {
        this.repository = repository;
        this.registry = registry;
        this.transport = transport;
    }

    public UserAiProviderPresetService.PresetStatus presets(UUID userId) {
        var policy = registry.require(ProviderPresetCode.KILO_GATEWAY_FREE);
        var connection = repository.findByUserAndPreset(userId, PRESET);
        return new UserAiProviderPresetService.PresetStatus(PRESET, policy.displayName(),
                connection.isPresent(),
                connection.map(UserAiProvider::modelName).orElse(null),
                connection.map(UserAiProvider::enabled).orElse(true),
                connection.map(UserAiProvider::isDefault).orElse(false),
                false);
    }

    public List<String> models(boolean refresh) {
        CachedListing hit = cache;
        if (!refresh && hit != null && Instant.now().isBefore(hit.expires())) {
            return hit.listing();
        }
        List<String> listing = transport.admittedModels();
        cache = new CachedListing(listing, Instant.now().plus(CACHE_TTL));
        return listing;
    }

    @Transactional
    public UserAiProviderPresetService.PresetStatus save(UUID userId, String modelName, boolean enabled,
            boolean setDefault) {
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        }
        requireAdmittedModel(modelName, models(false));
        var policy = registry.require(ProviderPresetCode.KILO_GATEWAY_FREE);
        var existing = repository.findByUserAndPreset(userId, PRESET);
        boolean first = repository.countByUserId(userId) == 0;
        boolean makeDefault = setDefault || first || existing.map(UserAiProvider::isDefault).orElse(false);
        if (makeDefault) repository.clearDefault(userId);
        OffsetDateTime now = OffsetDateTime.now();
        UserAiProvider row = new UserAiProvider(
                existing.map(UserAiProvider::id).orElseGet(UUID::randomUUID), userId, policy.displayName(),
                policy.protocol(), policy.baseUrl(), policy.completionPath(), null, modelName.strip(),
                enabled, policy.defaultTemperature(), policy.defaultMaxOutputTokens(),
                EnumSet.copyOf(policy.capabilities()), makeDefault,
                existing.map(UserAiProvider::createdAt).orElse(now), now, PRESET);
        repository.save(row);
        return presets(userId);
    }

    /** 断开仅删除本人 Kilo 预设行；默认值不自动回填，用途覆盖经外键级联清空。幂等：无连接时删除零行。 */
    @Transactional
    public void disconnect(UUID userId) {
        repository.deleteByUserAndPreset(userId, PRESET);
    }

    public void test(UUID userId, String modelName) {
        String model = modelName != null && !modelName.isBlank() ? modelName.strip()
                : repository.findByUserAndPreset(userId, PRESET).map(UserAiProvider::modelName)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "model required"));
        requireAdmittedModel(model, models(false));
        transport.validateModel(model);
    }

    /** 只允许当前网关仍在服务的免费模型；{@code :free} 后缀本身不构成证据，须以上游实时目录为准。 */
    private void requireAdmittedModel(String model, List<String> admitted) {
        String candidate = model == null ? "" : model.strip();
        if (candidate.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        }
        if (!admitted.contains(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model must be a current Kilo free model");
        }
    }

    private record CachedListing(List<String> listing, Instant expires) {}
}
