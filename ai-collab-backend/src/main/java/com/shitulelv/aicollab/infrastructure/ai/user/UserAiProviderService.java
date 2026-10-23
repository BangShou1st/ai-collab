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
        UserAiProvider existing = require(userId, id);
        rejectPreset(existing);
        validateEndpoint(request.baseUrl(), request.apiPath());
        String encrypted = request.apiKey() == null || request.apiKey().isBlank()
                ? existing.encryptedApiKey() : secrets.encrypt(request.apiKey());
        return UserAiProviderView.from(repository.save(toProvider(
                id, userId, request, encrypted, existing.isDefault(),
                existing.createdAt(), OffsetDateTime.now())));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        rejectPreset(require(userId, id));
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

    public java.util.Map<ModelPurpose, UUID> listAssignments(UUID userId) {
        return repository.listAssignments(userId);
    }

    public UserAiProvider resolve(UUID userId, ModelPurpose purpose) {
        return repository.findAssigned(userId, purpose)
                .or(() -> repository.findDefaultByUserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }

    public void requireSnapshotAuthorized(UserAiProvider snapshot) {
        UserAiProvider current = require(snapshot.userId(), snapshot.id());
        if (!current.enabled() || !java.util.Objects.equals(current.encryptedApiKey(), snapshot.encryptedApiKey()))
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "模型配置已停用或凭据已轮换，请重新发起");
    }
    public UserAiProvider requireRuntimeConfiguration(UUID userId, UUID id) { return require(userId, id); }

    public ChatCompletionResult test(UUID userId, UUID id) {
        UserAiProvider provider = require(userId, id);
        rejectPreset(provider);
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

    private static void rejectPreset(UserAiProvider provider) {
        if (provider.presetCode() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "预置连接请在 OpenCode Zen Free 专区管理");
        }
    }

    private static UserAiProvider toProvider(UUID id, UUID userId, UserAiProviderRequest request,
            String encrypted, boolean isDefault, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new UserAiProvider(
                id, userId, request.name().strip(), request.providerType(), request.baseUrl().strip(),
                request.apiPath().strip(), encrypted, request.modelName().strip(), request.enabled(),
                request.temperature(), request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()), isDefault, createdAt, updatedAt, null);
    }

    /** API Path 中唯一允许的占位符：由 GeminiModelAdapter 在出站前替换为模型 ID。 */
    static final String MODEL_PLACEHOLDER = "{model}";
    /** 占位符的 URI 安全校验替身：仅含 URI 路径合法字符，长度与真实模型 ID 同量级。 */
    private static final String MODEL_PLACEHOLDER_PROBE = "model-placeholder";

    /**
     * 校验 Base URL 与 API Path。
     *
     * <p>API Path 允许携带至多一个 {@code {model}} 占位符（Gemini 官方路径依赖它）；
     * 占位符先被替换为 URI 合法 token 再做文法校验，因此花括号本身不会破坏
     * URI 解析，其余任何花括号组合仍然被拒绝。出站安全策略（HTTPS/公网/防 SSRF）
     * 保持不变：占位符替换发生在安全校验之前，校验对象覆盖完整路径。</p>
     */
    private void validateEndpoint(String baseUrl, String apiPath) {
        String baseText = baseUrl == null ? "" : baseUrl.strip();
        String pathText = apiPath == null ? "" : apiPath.strip();
        URI base;
        try {
            base = URI.create(baseText);
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 格式无效，请填写完整的 HTTPS 地址");
        }
        if (!base.isAbsolute() || base.getHost() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 必须是带主机的绝对地址");
        }
        if (base.getUserInfo() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 不允许携带用户凭据信息（user@host）");
        }
        if (pathText.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Path 不能为空");
        }
        int placeholderCount = pathText.split(java.util.regex.Pattern.quote(MODEL_PLACEHOLDER), -1).length - 1;
        String sanitizedPath = pathText.replace(MODEL_PLACEHOLDER, MODEL_PLACEHOLDER_PROBE);
        if (sanitizedPath.indexOf('{') >= 0 || sanitizedPath.indexOf('}') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "API Path 仅支持 {model} 占位符，不支持其他花括号表达式");
        }
        if (placeholderCount > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Path 中的 {model} 占位符至多出现一次");
        }
        URI path;
        try {
            path = URI.create(sanitizedPath);
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "API Path 格式无效：必须是相对路径（以 / 开头，不含主机名）");
        }
        if (path.isAbsolute() || path.getRawAuthority() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Path 必须是相对路径，完整地址请填写在 Base URL");
        }
        try {
            endpoints.requirePublicHttps(base);
        } catch (RuntimeException unsafe) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Base URL 不符合出站安全策略：仅允许公网 HTTPS 地址（禁止内网、环回与链路本地地址）");
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
