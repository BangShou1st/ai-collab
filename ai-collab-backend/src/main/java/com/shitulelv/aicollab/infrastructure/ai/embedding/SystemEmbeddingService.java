package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.document.application.service.BatchReindexService;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.api.SystemEmbeddingConfigRequest;
import com.shitulelv.aicollab.infrastructure.ai.embedding.api.SystemEmbeddingConfigView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 系统 Embedding 服务：仅 systemAdmin 可管理。
 * 语义（provider/model/dimensions）变化且存在旧指纹向量时拒绝静默修改，
 * 管理员须经 reindex 通道（显式重建意图）切换并重建。
 */
@Service
public class SystemEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(SystemEmbeddingService.class);
    private final SystemEmbeddingConfigRepository repository;
    private final ModelSecretCipher secrets;
    private final OutboundEndpointPolicy endpoints;
    private final UserService users;
    private final DocumentRepository documents;
    private final BatchReindexService reindex;
    private final ProjectEmbeddingGateway gateway;

    public SystemEmbeddingService(
            SystemEmbeddingConfigRepository repository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            UserService users,
            DocumentRepository documents,
            BatchReindexService reindex,
            ProjectEmbeddingGateway gateway) {
        this.repository = repository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.users = users;
        this.documents = documents;
        this.reindex = reindex;
        this.gateway = gateway;
    }

    public SystemEmbeddingConfigView get(UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        return repository.findActive().map(this::view)
                .orElseGet(SystemEmbeddingConfigView::empty);
    }

    @Transactional
    public SystemEmbeddingConfigView update(UUID operatorId, SystemEmbeddingConfigRequest request) {
        users.requireSystemAdmin(operatorId);
        validateEndpoint(request);
        String encrypted = resolveKey(request, repository.findActive().orElse(null));
        String fingerprint = EmbeddingFingerprints.fingerprint(
                request.provider(), request.modelName(), request.dimensions());
        SystemEmbeddingConfig existing = repository.findActive().orElse(null);
        if (existing == null) {
            return view(repository.save(config(null, request, encrypted, fingerprint, true)));
        }
        if (sameSemantic(existing, request)) {
            return view(repository.save(config(existing.id(), request, encrypted,
                    existing.fingerprint(), true)));
        }
        if (documents.hasChunksWithOtherFingerprint(fingerprint)) {
            throw new BusinessException(ErrorCode.EMBEDDING_REINDEX_REQUIRED);
        }
        repository.disableAll();
        return view(repository.save(config(null, request, encrypted, fingerprint, true)));
    }

    public void test(UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        SystemEmbeddingConfig active = repository.findActive()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "系统未配置嵌入模型"));
        if (!active.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "嵌入模型已禁用");
        }
        try {
            gateway.embedWithConfig(asProjectConfig(active), List.of("连接测试"),
                    EmbeddingProgressListener.NONE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (HttpClientErrorException | HttpServerErrorException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "嵌入模型连接失败，请检查接口地址、API Key 和模型名称是否正确");
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "无法连接到嵌入模型接口：" + exception.getMessage());
        }
    }

    @Transactional
    public int reindex(UUID operatorId, SystemEmbeddingConfigRequest request) {
        users.requireSystemAdmin(operatorId);
        validateEndpoint(request);
        String encrypted = resolveKey(request, repository.findActive().orElse(null));
        String fingerprint = EmbeddingFingerprints.fingerprint(
                request.provider(), request.modelName(), request.dimensions());
        repository.disableAll();
        repository.save(config(null, request, encrypted, fingerprint, true));
        return reindex.reindexAllProjects(operatorId);
    }

    private String resolveKey(SystemEmbeddingConfigRequest request, SystemEmbeddingConfig existing) {
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            return secrets.encrypt(request.apiKey());
        }
        if (existing != null && existing.encryptedApiKey() != null) {
            return existing.encryptedApiKey();
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
    }

    private static boolean sameSemantic(SystemEmbeddingConfig existing,
            SystemEmbeddingConfigRequest request) {
        return existing.provider().equals(request.provider())
                && existing.modelName().equals(request.modelName())
                && existing.dimensions() == request.dimensions();
    }

    private static SystemEmbeddingConfig config(UUID id, SystemEmbeddingConfigRequest request,
            String encrypted, String fingerprint, boolean enabled) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SystemEmbeddingConfig(id == null ? UUID.randomUUID() : id,
                request.provider(), request.baseUrl(), request.apiPath(), encrypted,
                request.modelName(), request.dimensions(), request.batchSize(), fingerprint,
                enabled, now, now);
    }

    private static ProjectEmbeddingConfig asProjectConfig(SystemEmbeddingConfig active) {
        return new ProjectEmbeddingConfig(null, active.provider(), active.baseUrl(), active.apiPath(),
                active.encryptedApiKey(), active.modelName(), active.dimensions(), active.batchSize(),
                true, active.createdAt(), active.updatedAt());
    }

    private SystemEmbeddingConfigView view(SystemEmbeddingConfig config) {
        return SystemEmbeddingConfigView.from(config,
                documents.countChunksWithOtherFingerprint(config.fingerprint()));
    }

    private void validateEndpoint(SystemEmbeddingConfigRequest request) {
        try {
            URI base = URI.create(request.baseUrl().strip());
            URI path = URI.create(request.apiPath().strip());
            if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null
                    || path.isAbsolute() || path.getRawAuthority() != null
                    || request.apiPath().isBlank()) {
                throw new IllegalArgumentException();
            }
            if (request.dimensions() < 1 || request.dimensions() > 4096
                    || request.batchSize() < 1 || request.batchSize() > 256) {
                throw new IllegalArgumentException();
            }
            endpoints.requirePublicHttps(base);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "嵌入模型接口地址无效");
        }
        log.debug("System embedding endpoint validated");
    }
}
