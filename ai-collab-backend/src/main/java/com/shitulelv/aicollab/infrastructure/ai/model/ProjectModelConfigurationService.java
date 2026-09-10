package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.api.ModelConfigurationRequest;
import com.shitulelv.aicollab.infrastructure.ai.model.api.ModelConfigurationView;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目级模型配置服务。
 * 只有项目 ADMIN/OWNER 可以管理模型配置。
 */
@Service
public class ProjectModelConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(ProjectModelConfigurationService.class);
    private final ProjectAccessGuard accessGuard;
    private final ModelConfigurationRepository repository;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;

    private final OutboundEndpointPolicy endpoints;

    public ProjectModelConfigurationService(
            ProjectAccessGuard accessGuard,
            ModelConfigurationRepository repository,
            ModelSecretCipher secrets,
            List<ModelProviderAdapter> adapters,
            OutboundEndpointPolicy endpoints) {
        this.accessGuard = accessGuard;
        this.repository = repository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
    }

    public List<ModelConfigurationView> list(UUID projectId, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        return repository.findAll(projectId).stream().map(ModelConfigurationView::from).toList();
    }

    public List<ModelConfigurationRepository.ModelAssignment> assignments(UUID projectId, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        return repository.assignments(projectId);
    }

    @Transactional
    public ModelConfigurationView create(UUID projectId, ModelConfigurationRequest request, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        return ModelConfigurationView.from(repository.save(toModel(
                UUID.randomUUID(), projectId, request, secrets.encrypt(request.apiKey()), now, now)));
    }

    @Transactional
    public ModelConfigurationView update(
            UUID projectId, UUID id, ModelConfigurationRequest request, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        validateEndpoint(request.baseUrl(), request.apiPath());
        ModelConfiguration existing = require(projectId, id);
        String encrypted = request.apiKey() == null || request.apiKey().isBlank()
                ? existing.encryptedApiKey() : secrets.encrypt(request.apiKey());
        return ModelConfigurationView.from(repository.save(toModel(
                id, projectId, request, encrypted, existing.createdAt(), OffsetDateTime.now())));
    }

    @Transactional
    public void assign(UUID projectId, ModelPurpose purpose, UUID configurationId, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        ModelConfiguration configuration = require(projectId, configurationId);
        if (!configuration.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能分配已停用的模型配置");
        }
        repository.assign(projectId, purpose, configurationId);
    }

    public ChatCompletionResult test(UUID projectId, UUID id, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        ModelConfiguration configuration = require(projectId, id);
        validateModelParameters(configuration);

        ModelProviderAdapter adapter = adapters.get(configuration.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        log.info("Testing model configuration: {} (provider: {}, model: {})",
                configuration.name(), configuration.providerType(), configuration.modelName());

        ChatCompletionCommand command = new ChatCompletionCommand(
                projectId,
                "只回答测试请求，不要输出其他内容。",
                "回复：连接成功",
                ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), operatorId);

        java.util.concurrent.atomic.AtomicReference<ChatCompletionResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> errorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        adapter.completeStream(configuration, secrets.decrypt(configuration.encryptedApiKey()),
                command,
                token -> { },
                done -> {
                    resultRef.set(done);
                    latch.countDown();
                },
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                });

        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        }

        if (errorRef.get() != null) {
            throw errorRef.get() instanceof BusinessException be
                    ? be : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
        ChatCompletionResult result = resultRef.get();
        if (result == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        return result;
    }

    private void validateModelParameters(ModelConfiguration config) {
        String modelName = config.modelName().toLowerCase();
        if (modelName.contains("glm")) {
            if (config.maxOutputTokens() > 4096) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "GLM 模型的最大输出 Token 不能超过 4096，当前值: " + config.maxOutputTokens());
            }
            if (config.temperature() < 0 || config.temperature() > 1.0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "GLM 模型的温度必须在 0-1.0 之间，当前值: " + config.temperature());
            }
        }
        if (config.temperature() < 0 || config.temperature() > 2.0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "温度必须在 0-2.0 之间，当前值: " + config.temperature());
        }
        if (config.maxOutputTokens() < 1 || config.maxOutputTokens() > 131072) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "最大输出 Token 必须在 1-131072 之间，当前值: " + config.maxOutputTokens());
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID id, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        require(projectId, id);
        repository.deleteByIdAndProjectId(id, projectId);
    }

    private ModelConfiguration require(UUID projectId, UUID id) {
        return repository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "模型配置不存在"));
    }

    private static ModelConfiguration toModel(
            UUID id, UUID projectId, ModelConfigurationRequest request, String encrypted,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new ModelConfiguration(
                id, projectId, request.name().strip(), request.providerType(), request.baseUrl().strip(),
                request.apiPath().strip(), encrypted, request.modelName().strip(), request.enabled(),
                request.temperature(), request.maxOutputTokens(),
                java.util.EnumSet.copyOf(request.capabilities()), createdAt, updatedAt);
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
}
