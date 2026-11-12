package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderAdapter;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminSharedAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AdminAiProviderService {
    private static final Logger log = LoggerFactory.getLogger(AdminAiProviderService.class);

    private final UserAiProviderRepository userProviderRepository;
    private final SharedAiProviderRepository sharedRepository;
    private final ModelSecretCipher secrets;
    private final OutboundEndpointPolicy endpoints;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;
    private final AdminNotificationService notificationService;

    public AdminAiProviderService(
            UserAiProviderRepository userProviderRepository,
            SharedAiProviderRepository sharedRepository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            List<ModelProviderAdapter> adapters,
            AdminNotificationService notificationService) {
        this.userProviderRepository = userProviderRepository;
        this.sharedRepository = sharedRepository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.notificationService = notificationService;
    }

    public List<AdminSharedAiProviderView> listAdminProviders(UUID adminUserId) {
        List<UserAiProvider> sources = userProviderRepository.findAllByUserId(adminUserId);
        Map<UUID, SharedAiProvider> sharedMap = sharedRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SharedAiProvider::sourceProviderId, s -> s, (s1, s2) -> s1));

        return sources.stream()
                .filter(p -> p.presetCode() == null) // 过滤掉Zen等内置预设
                .map(p -> AdminSharedAiProviderView.from(p, sharedMap.get(p.id())))
                .toList();
    }

    @Transactional
    public AdminSharedAiProviderView create(UUID adminUserId, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        UserAiProvider created = userProviderRepository.save(new UserAiProvider(
                UUID.randomUUID(),
                adminUserId,
                request.name().strip(),
                request.providerType(),
                request.baseUrl().strip(),
                request.apiPath().strip(),
                secrets.encrypt(request.apiKey().strip()),
                request.modelName().strip(),
                request.enabled(),
                request.temperature(),
                request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()),
                false,
                now,
                now,
                null,
                0));

        return AdminSharedAiProviderView.from(created, null);
    }

    @Transactional
    public AdminSharedAiProviderView update(UUID adminUserId, UUID id, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        UserAiProvider existing = requireSource(adminUserId, id);
        validateEndpoint(request.baseUrl(), request.apiPath());
        String encrypted = resolveEncryptedKey(existing.encryptedApiKey(), request.apiKey());

        UserAiProvider updated = userProviderRepository.save(new UserAiProvider(
                id,
                adminUserId,
                request.name().strip(),
                request.providerType(),
                request.baseUrl().strip(),
                request.apiPath().strip(),
                encrypted,
                request.modelName().strip(),
                request.enabled(),
                request.temperature(),
                request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()),
                existing.isDefault(),
                existing.createdAt(),
                OffsetDateTime.now(),
                null,
                existing.capabilityConfigVersion()));

        // 如果该配置之前已经发布为全站共享，同步更新共享表里的内容（保持幂等关联）
        Optional<SharedAiProvider> sharedOpt = sharedRepository.findBySourceProviderId(id);
        SharedAiProvider shared = null;
        if (sharedOpt.isPresent()) {
            SharedAiProvider prev = sharedOpt.get();
            shared = sharedRepository.saveOrUpdate(new SharedAiProvider(
                    prev.id(),
                    id,
                    adminUserId,
                    updated.name(),
                    updated.providerType(),
                    updated.baseUrl(),
                    updated.apiPath(),
                    updated.encryptedApiKey(),
                    updated.modelName(),
                    updated.enabled(),
                    prev.status(),
                    updated.temperature(),
                    updated.maxOutputTokens(),
                    updated.capabilities(),
                    prev.capabilityConfigVersion() + 1,
                    prev.createdAt(),
                    OffsetDateTime.now(),
                    prev.publishedAt()));
        }

        return AdminSharedAiProviderView.from(updated, shared);
    }

    @Transactional
    public void delete(UUID adminUserId, UUID id) {
        requireSource(adminUserId, id);
        sharedRepository.deleteBySourceProviderId(id);
        userProviderRepository.deleteByIdAndUserId(id, adminUserId);
    }

    public ChatCompletionResult test(UUID adminUserId, UUID id) {
        UserAiProvider provider = requireSource(adminUserId, id);
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能测试已停用的模型配置");
        }
        ModelProviderAdapter adapter = adapters.get(provider.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        log.info("Admin testing AI provider: {} (type: {}, model: {})",
                provider.name(), provider.providerType(), provider.modelName());

        try {
            return adapter.complete(
                    provider.toModelConfiguration(),
                    secrets.decrypt(provider.encryptedApiKey()),
                    new ChatCompletionCommand(
                            provider.id(),
                            "只回答测试请求，不要输出其他内容。",
                            "回复：连接成功",
                            ChatCompletionCommand.OutputFormat.TEXT,
                            ModelPurpose.KNOWLEDGE_CHAT,
                            null,
                            List.of(),
                            adminUserId));
        } catch (Exception ex) {
            log.warn("Admin test provider failed: {}", ex.getMessage());
            notificationService.publishEvent(
                    "AI_PROVIDER",
                    "ERROR",
                    "AI 供应商测试失败: " + provider.name(),
                    "模型 " + provider.modelName() + " 连接测试失败: " + ex.getMessage(),
                    "/admin/ai-providers",
                    "test_fail_" + provider.id());
            throw ex;
        }
    }

    /**
     * 将管理员供应商发布为全站共享。
     * 重复调用保持幂等，更新同一共享项。
     */
    @Transactional
    public AdminSharedAiProviderView publishShared(UUID adminUserId, UUID sourceProviderId) {
        UserAiProvider source = requireSource(adminUserId, sourceProviderId);
        if (!source.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "已停用的供应商不能发布为全站共享");
        }

        OffsetDateTime now = OffsetDateTime.now();
        UUID sharedId = sharedRepository.findBySourceProviderId(sourceProviderId)
                .map(SharedAiProvider::id)
                .orElseGet(UUID::randomUUID);

        SharedAiProvider shared = sharedRepository.saveOrUpdate(new SharedAiProvider(
                sharedId,
                source.id(),
                adminUserId,
                source.name(),
                source.providerType(),
                source.baseUrl(),
                source.apiPath(),
                source.encryptedApiKey(),
                source.modelName(),
                true,
                SharedAiProvider.STATUS_PUBLISHED,
                source.temperature(),
                source.maxOutputTokens(),
                source.capabilities(),
                0,
                now,
                now,
                now));

        notificationService.publishEvent(
                "AI_PROVIDER",
                "INFO",
                "全站共享 AI 供应商已发布",
                "管理员已将供应商「" + source.name() + "」（模型：" + source.modelName() + "）发布为全站共享。",
                "/admin/ai-providers",
                "publish_" + source.id());

        return AdminSharedAiProviderView.from(source, shared);
    }

    /**
     * 撤销全站共享。
     */
    @Transactional
    public AdminSharedAiProviderView revokeShared(UUID adminUserId, UUID sourceProviderId) {
        UserAiProvider source = requireSource(adminUserId, sourceProviderId);
        sharedRepository.revokeBySourceProviderId(sourceProviderId);
        SharedAiProvider revoked = sharedRepository.findBySourceProviderId(sourceProviderId).orElse(null);

        notificationService.publishEvent(
                "AI_PROVIDER",
                "WARNING",
                "全站共享 AI 供应商已撤销",
                "管理员已撤销供应商「" + source.name() + "」的全站共享。",
                "/admin/ai-providers",
                "revoke_" + source.id());

        return AdminSharedAiProviderView.from(source, revoked);
    }

    private UserAiProvider requireSource(UUID adminUserId, UUID id) {
        return userProviderRepository.findByIdAndUserId(id, adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "指定的供应商配置不存在"));
    }

    private static void requireChatCompatible(ModelProviderType providerType) {
        if (providerType == null || !providerType.chatCompatible()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "LOCAL_CUSTOM 只能通过能力 Provider 管理入口配置");
        }
    }

    private String resolveEncryptedKey(String existingEncrypted, String newApiKey) {
        if (newApiKey == null || newApiKey.isBlank()) return existingEncrypted;
        String normalized = newApiKey.strip();
        String storedPlain;
        try {
            storedPlain = secrets.decryptOptional(existingEncrypted);
        } catch (RuntimeException unreadableStoredKey) {
            storedPlain = null;
        }
        if (normalized.equals(storedPlain)) return existingEncrypted;
        return secrets.encrypt(normalized);
    }

    private void validateEndpoint(String baseUrl, String apiPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 不能为空");
        }
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 格式无效，请填写完整的 HTTPS 地址");
        }
        try {
            endpoints.requirePublicHttps(base);
        } catch (RuntimeException unsafe) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Base URL 不符合出站安全策略：仅允许公网 HTTPS 地址（禁止内网、环回与链路本地地址）");
        }
    }
}
