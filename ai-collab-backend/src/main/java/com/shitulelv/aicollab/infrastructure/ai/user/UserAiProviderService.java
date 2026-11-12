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
import java.util.Optional;
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
    private final com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository sharedRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public UserAiProviderService(
            UserAiProviderRepository repository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            List<ModelProviderAdapter> adapters,
            com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository sharedRepository) {
        this.repository = repository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.sharedRepository = sharedRepository;
    }

    public UserAiProviderService(
            UserAiProviderRepository repository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            List<ModelProviderAdapter> adapters) {
        this(repository, secrets, endpoints, adapters, null);
    }

    public List<UserAiProviderView> list(UUID userId) {
        var prices = repository.findPricesByUserId(userId);
        return repository.findAllByUserId(userId).stream()
                .map(provider -> UserAiProviderView.from(provider, prices.get(provider.id())))
                .toList();
    }

    public UserAiProviderView get(UUID userId, UUID id) {
        return UserAiProviderView.from(require(userId, id), repository.findPrices(id, userId).orElse(null));
    }

    @Transactional
    public UserAiProviderView create(UUID userId, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        boolean first = repository.countByUserId(userId) == 0;
        OffsetDateTime now = OffsetDateTime.now();
        UserAiProvider saved = repository.save(toProvider(
                UUID.randomUUID(), userId, request, secrets.encrypt(request.apiKey()),
                first, now, now));
        // 价格与配置同一更新通道落库（成本台账 §4.4）；save 的 upsert 不触碰价格列，
        // 价格整体替换由 updatePrices 完成
        repository.updatePrices(saved.id(), userId, request.pricePer1kInput(), request.pricePer1kOutput());
        return UserAiProviderView.from(saved, repository.findPrices(saved.id(), userId).orElse(null));
    }

    @Transactional
    public UserAiProviderView update(UUID userId, UUID id, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        UserAiProvider existing = require(userId, id);
        rejectPreset(existing);
        validateEndpoint(request.baseUrl(), request.apiPath());
        String encrypted = resolveEncryptedKey(existing.encryptedApiKey(), request.apiKey());
        UserAiProvider saved = repository.save(toProvider(
                id, userId, request, encrypted, existing.isDefault(),
                existing.createdAt(), OffsetDateTime.now()));
        // 价格走同一更新通道与同一守卫：require(userId, id) 已保证用户级配置只能本人改，
        // 预置/共享连接已在 rejectPreset 拦截（其不填价格，成本恒为 NULL）
        repository.updatePrices(id, userId, request.pricePer1kInput(), request.pricePer1kOutput());
        return UserAiProviderView.from(saved, repository.findPrices(id, userId).orElse(null));
    }

    /**
     * 相同明文密钥复用既有密文：AES-GCM 每次加密产生新密文，
     * 重加密会让仓储把「未变化的凭证」误判为请求行为变化而推进能力配置版本。
     */
    private String resolveEncryptedKey(String existingEncrypted, String newApiKey) {
        if (newApiKey == null || newApiKey.isBlank()) return existingEncrypted;
        String normalized = newApiKey.strip();
        String storedPlain;
        try {
            storedPlain = secrets.decryptOptional(existingEncrypted);
        } catch (RuntimeException unreadableStoredKey) {
            // 旧密文不可解（如主密钥轮换）：按凭证已变化处理，走重加密。
            storedPlain = null;
        }
        if (normalized.equals(storedPlain)) return existingEncrypted;
        return secrets.encrypt(normalized);
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
        assignPurpose(userId, purpose, providerId, false);
    }

    @Transactional
    public void assignPurpose(UUID userId, ModelPurpose purpose, UUID providerId, boolean isShared) {
        if (isShared) {
            if (sharedRepository == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "共享供应商不可用");
            }
            com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider shared = sharedRepository.findById(providerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "共享模型配置不存在"));
            if (!shared.isPublished()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能分配已停用或已撤销的共享模型配置");
            }
            repository.assignPurpose(userId, purpose, providerId, true);
            return;
        }

        // 尝试作为个人配置分配；若个人配置不存在但存在已发布的共享配置，也允许优雅分配为共享
        Optional<UserAiProvider> userProvider = repository.findByIdAndUserId(providerId, userId);
        if (userProvider.isPresent()) {
            UserAiProvider provider = userProvider.get();
            if (!provider.enabled()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能分配已停用的模型配置");
            }
            repository.assignPurpose(userId, purpose, providerId);
            return;
        }

        if (sharedRepository != null) {
            Optional<com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider> shared = sharedRepository.findById(providerId);
            if (shared.isPresent() && shared.get().isPublished()) {
                repository.assignPurpose(userId, purpose, providerId, true);
                return;
            }
        }

        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在");
    }

    @Transactional
    public void unassignPurpose(UUID userId, ModelPurpose purpose) {
        repository.unassignPurpose(userId, purpose);
    }

    public java.util.Map<ModelPurpose, UUID> listAssignments(UUID userId) {
        return repository.listAssignments(userId);
    }

    public java.util.Map<ModelPurpose, UserAiProviderRepository.PurposeAssignmentDetail> listAssignmentDetails(UUID userId) {
        return repository.listAssignmentDetails(userId);
    }

    public UserAiProvider resolve(UUID userId, ModelPurpose purpose) {
        return repository.findAssigned(userId, purpose)
                .or(() -> repository.findDefaultByUserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }

    public void requireSnapshotAuthorized(UserAiProvider snapshot) {
        if (snapshot.presetCode() != null && snapshot.presetCode().startsWith(com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider.PRESET_CODE_SHARED)) {
            if (sharedRepository == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
            com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider shared = sharedRepository.findById(snapshot.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "共享模型已被撤销"));
            if (!shared.isPublished() || !java.util.Objects.equals(shared.encryptedApiKey(), snapshot.encryptedApiKey())) {
                throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "共享模型配置已停用或凭据已轮换，请重新发起");
            }
            return;
        }

        UserAiProvider current = require(snapshot.userId(), snapshot.id());
        if (!current.enabled() || !java.util.Objects.equals(current.encryptedApiKey(), snapshot.encryptedApiKey()))
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "模型配置已停用或凭据已轮换，请重新发起");
    }

    public UserAiProvider requireRuntimeConfiguration(UUID userId, UUID id) {
        Optional<UserAiProvider> personal = repository.findByIdAndUserId(id, userId);
        if (personal.isPresent()) return personal.get();
        if (sharedRepository != null) {
            Optional<com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider> shared = sharedRepository.findById(id);
            if (shared.isPresent() && shared.get().isPublished()) {
                return shared.get().toRuntimeUserAiProvider();
            }
        }
        return require(userId, id);
    }

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

    private static void requireChatCompatible(ModelProviderType providerType) {
        if (providerType == null || !providerType.chatCompatible()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "LOCAL_CUSTOM 仅用于管理员能力 Provider，个人 Chat 配置不可使用");
        }
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
    /** 保存与未保存模型发现共用的端点校验（同包内复用）。 */
    void validateEndpoint(String baseUrl, String apiPath) {
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
        ProviderEndpointRules.duplicateVersionJunction(baseText, pathText).ifPresent(duplicate ->
                throwDuplicateVersionJunction(baseText, pathText, duplicate));
    }

    /** Base URL 末段与 API Path 首段重复版本前缀（如 /v1 + /v1/... → /v1/v1/...）：发请求前直接拒绝并给出修正方向。 */
    private static void throwDuplicateVersionJunction(String baseUrl, String apiPath, String duplicate) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "Base URL 与 API Path 拼接后会重复版本前缀 /" + duplicate
                        + "（最终端点：" + ProviderEndpointRules.inferenceUrlPreview(baseUrl, apiPath) + "）。"
                        + "请删除其一：Base URL 末尾的 /" + duplicate + "，或 API Path 开头的 /" + duplicate + "。");
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
