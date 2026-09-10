package com.shitulelv.aicollab.infrastructure.ai.user;

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
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView;
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
import java.util.UUID;

/**
 * 个人 AI Provider 服务：Credential 归属永远是当前用户。
 * purpose 有 assignment 则用 assignment，否则用 is_default，都没有则未配置。
 */
@Service
public class UserAiProviderService {
    private static final Logger log = LoggerFactory.getLogger(UserAiProviderService.class);
    private final UserAiProviderRepository repository;
    private final ModelSecretCipher secrets;
    private final OutboundEndpointPolicy endpoints;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;

    public UserAiProviderService(
            UserAiProviderRepository repository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            List<ModelProviderAdapter> adapters) {
        this.repository = repository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
    }

    public List<UserAiProviderView> list(UUID userId) {
        return repository.findAllByUserId(userId).stream().map(UserAiProviderView::from).toList();
    }

    public UserAiProviderView get(UUID userId, UUID id) {
        return UserAiProviderView.from(require(userId, id));
    }

    @Transactional
    public UserAiProviderView create(UUID userId, UserAiProviderRequest request) {
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        boolean first = repository.countByUserId(userId) == 0;
        OffsetDateTime now = OffsetDateTime.now();
        return UserAiProviderView.from(repository.save(toProvider(
                UUID.randomUUID(), userId, request, secrets.encrypt(request.apiKey()),
                first, now, now)));
    }

    @Transactional
    public UserAiProviderView update(UUID userId, UUID id, UserAiProviderRequest request) {
        validateEndpoint(request.baseUrl(), request.apiPath());
        UserAiProvider existing = require(userId, id);
        String encrypted = request.apiKey() == null || request.apiKey().isBlank()
                ? existing.encryptedApiKey() : secrets.encrypt(request.apiKey());
        return UserAiProviderView.from(repository.save(toProvider(
                id, userId, request, encrypted, existing.isDefault(),
                existing.createdAt(), OffsetDateTime.now())));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        require(userId, id);
        repository.deleteByIdAndUserId(id, userId);
    }

    @Transactional
    public void setDefault(UUID userId, UUID id) {
        require(userId, id);
        repository.clearDefault(userId);
        if (repository.markDefault(userId, id) != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在");
        }
    }

    @Transactional
    public void assignPurpose(UUID userId, ModelPurpose purpose, UUID providerId) {
        UserAiProvider provider = require(userId, providerId);
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能分配已停用的模型配置");
        }
        repository.assignPurpose(userId, purpose, providerId);
    }

    @Transactional
    public void unassignPurpose(UUID userId, ModelPurpose purpose) {
        repository.unassignPurpose(userId, purpose);
    }

    public UserAiProvider resolve(UUID userId, ModelPurpose purpose) {
        return repository.findAssigned(userId, purpose)
                .or(() -> repository.findDefaultByUserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }

    public ChatCompletionResult test(UUID userId, UUID id) {
        UserAiProvider provider = require(userId, id);
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能测试已停用的模型配置");
        }
        validateModelParameters(provider);
        ModelProviderAdapter adapter = adapters.get(provider.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        log.info("Testing user AI provider: {} (provider: {}, model: {})",
                provider.name(), provider.providerType(), provider.modelName());
        return adapter.complete(provider.toModelConfiguration(),
                secrets.decrypt(provider.encryptedApiKey()),
                new ChatCompletionCommand(provider.id(),
                        "只回答测试请求，不要输出其他内容。",
                        "回复：连接成功",
                        ChatCompletionCommand.OutputFormat.TEXT,
                        ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), userId));
    }

    private UserAiProvider require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在"));
    }

    private static UserAiProvider toProvider(UUID id, UUID userId, UserAiProviderRequest request,
            String encrypted, boolean isDefault, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new UserAiProvider(
                id, userId, request.name().strip(), request.providerType(), request.baseUrl().strip(),
                request.apiPath().strip(), encrypted, request.modelName().strip(), request.enabled(),
                request.temperature(), request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()), isDefault, createdAt, updatedAt);
    }

    private void validateEndpoint(String baseUrl, String apiPath) {
        try {
            URI base = URI.create(baseUrl.strip());
            URI path = URI.create(apiPath.strip());
            if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null
                    || path.isAbsolute() || path.getRawAuthority() != null
                    || apiPath.isBlank()) {
                throw new IllegalArgumentException();
            }
            endpoints.requirePublicHttps(base);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型接口地址无效");
        }
    }

    private static void validateModelParameters(UserAiProvider provider) {
        String modelName = provider.modelName() == null ? "" : provider.modelName().toLowerCase();
        if (modelName.contains("glm")) {
            if (provider.maxOutputTokens() > 4096) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "GLM 模型的最大输出 Token 不能超过 4096");
            }
            if (provider.temperature() < 0 || provider.temperature() > 1.0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "GLM 模型的温度必须在 0-1.0 之间");
            }
        }
        if (provider.temperature() < 0 || provider.temperature() > 2.0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "温度必须在 0-2.0 之间");
        }
        if (provider.maxOutputTokens() < 1 || provider.maxOutputTokens() > 131072) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "最大输出 Token 必须在 1-131072 之间");
        }
    }
}
