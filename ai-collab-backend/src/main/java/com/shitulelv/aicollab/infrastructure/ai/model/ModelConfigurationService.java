package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.api.ModelConfigurationRequest;
import com.shitulelv.aicollab.infrastructure.ai.model.api.ModelConfigurationView;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ModelConfigurationService {
    private final UserService users;
    private final ModelConfigurationRepository repository;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;

    public ModelConfigurationService(
            UserService users,
            ModelConfigurationRepository repository,
            ModelSecretCipher secrets,
            List<ModelProviderAdapter> adapters) {
        this.users = users;
        this.repository = repository;
        this.secrets = secrets;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
    }

    public List<ModelConfigurationView> list(UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        return repository.findAll().stream().map(ModelConfigurationView::from).toList();
    }

    public List<ModelConfigurationRepository.ModelAssignment> assignments(UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        return repository.assignments();
    }

    @Transactional
    public ModelConfigurationView create(ModelConfigurationRequest request, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        return ModelConfigurationView.from(repository.save(toModel(
                UUID.randomUUID(), request, secrets.encrypt(request.apiKey()), now, now)));
    }

    @Transactional
    public ModelConfigurationView update(
            UUID id, ModelConfigurationRequest request, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        validateEndpoint(request.baseUrl(), request.apiPath());
        ModelConfiguration existing = require(id);
        String encrypted = request.apiKey() == null || request.apiKey().isBlank()
                ? existing.encryptedApiKey() : secrets.encrypt(request.apiKey());
        return ModelConfigurationView.from(repository.save(toModel(
                id, request, encrypted, existing.createdAt(), OffsetDateTime.now())));
    }

    @Transactional
    public void assign(ModelPurpose purpose, UUID configurationId, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        ModelConfiguration configuration = require(configurationId);
        if (!configuration.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能分配已停用的模型配置");
        }
        repository.assign(purpose, configurationId);
    }

    public ChatCompletionResult test(UUID id, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        ModelConfiguration configuration = require(id);
        ModelProviderAdapter adapter = adapters.get(configuration.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        return adapter.complete(configuration, secrets.decrypt(configuration.encryptedApiKey()),
                new ChatCompletionCommand(
                        "只回答测试请求，不要输出其他内容。",
                        "回复：连接成功",
                        ChatCompletionCommand.OutputFormat.TEXT,
                        ModelPurpose.KNOWLEDGE_CHAT, null, List.of()));
    }

    @Transactional
    public void delete(UUID id, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        require(id);
        repository.delete(id);
    }

    private ModelConfiguration require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在"));
    }

    private static ModelConfiguration toModel(
            UUID id, ModelConfigurationRequest request, String encrypted,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new ModelConfiguration(
                id, request.name().strip(), request.providerType(), request.baseUrl().strip(),
                request.apiPath().strip(), encrypted, request.modelName().strip(), request.enabled(),
                request.temperature(), request.maxOutputTokens(),
                java.util.EnumSet.copyOf(request.capabilities()), createdAt, updatedAt);
    }

    private static void validateEndpoint(String baseUrl, String apiPath) {
        try {
            URI base = URI.create(baseUrl.strip());
            URI path = URI.create(apiPath.strip());
            if (!base.isAbsolute()
                    || (!"http".equalsIgnoreCase(base.getScheme())
                        && !"https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || path.isAbsolute() || path.getRawAuthority() != null
                    || apiPath.isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型接口地址无效");
        }
    }
}
