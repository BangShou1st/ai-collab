package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 项目级嵌入模型配置接口。
 * 只有项目 ADMIN/OWNER 可以管理嵌入配置。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/embedding-config")
public class ProjectEmbeddingController {
    private static final Logger log = LoggerFactory.getLogger(ProjectEmbeddingController.class);
    private final ProjectEmbeddingConfigRepository configRepo;
    private final ProjectAccessGuard accessGuard;
    private final ModelSecretCipher secrets;
    private final ProjectEmbeddingGateway embeddingGateway;

    public ProjectEmbeddingController(
            ProjectEmbeddingConfigRepository configRepo,
            ProjectAccessGuard accessGuard,
            ModelSecretCipher secrets,
            ProjectEmbeddingGateway embeddingGateway) {
        this.configRepo = configRepo;
        this.accessGuard = accessGuard;
        this.secrets = secrets;
        this.embeddingGateway = embeddingGateway;
    }

    @GetMapping
    public ApiResponse<ProjectEmbeddingConfigView> get(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        if (userId != null) accessGuard.requireAdmin(projectId, userId);
        return ApiResponse.success(configRepo.findByProjectId(projectId)
                .map(ProjectEmbeddingConfigView::from)
                .orElseGet(ProjectEmbeddingConfigView::empty));
    }

    @PostMapping("/test")
    public ApiResponse<Void> test(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        if (userId != null) accessGuard.requireAdmin(projectId, userId);
        ProjectEmbeddingConfig config = configRepo.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "项目未配置嵌入模型"));
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "嵌入模型已禁用");
        }
        try {
            embeddingGateway.embed(projectId, List.of("连接测试"), EmbeddingProgressListener.NONE);
        } catch (BusinessException e) {
            // DOCUMENT_EMBEDDING_FAILED 来自 gateway，需要更精确的错误信息
            log.error("Embedding test failed for project {}: {}", projectId, e.getMessage());
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "嵌入模型连接失败，请检查接口地址、API Key 和模型名称是否正确");
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "嵌入模型返回客户端错误 (" + e.getStatusCode().value() + ")：" + extractErrorDetail(e));
        } catch (HttpServerErrorException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "嵌入模型返回服务端错误 (" + e.getStatusCode().value() + ")：" + extractErrorDetail(e));
        } catch (ResourceAccessException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "无法连接到嵌入模型接口：" + e.getMessage());
        }
        return ApiResponse.success(null);
    }

    private static String extractErrorDetail(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "未知错误";
        // 截断过长的响应体
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    @PutMapping
    public ApiResponse<ProjectEmbeddingConfigView> save(
            @PathVariable UUID projectId,
            @RequestBody ProjectEmbeddingConfigRequest request,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        if (userId != null) accessGuard.requireAdmin(projectId, userId);
        String encrypted = (request.apiKey() != null && !request.apiKey().isBlank())
                ? secrets.encrypt(request.apiKey()) : null;

        ProjectEmbeddingConfig existing = configRepo.findByProjectId(projectId).orElse(null);
        if (existing != null && encrypted == null) {
            encrypted = existing.encryptedApiKey();
        }
        if (encrypted == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ProjectEmbeddingConfig config = new ProjectEmbeddingConfig(
                projectId,
                request.provider() != null ? request.provider().name() : ModelProviderType.OPENAI_COMPATIBLE.name(),
                request.baseUrl(),
                request.apiPath() != null ? request.apiPath() : "/v1/embeddings",
                encrypted,
                request.modelName(),
                request.dimensions() > 0 ? request.dimensions() : 1024,
                request.batchSize() > 0 ? request.batchSize() : 10,
                true,
                existing != null ? existing.createdAt() : now,
                now);
        return ApiResponse.success(ProjectEmbeddingConfigView.from(configRepo.save(config)));
    }

    public record ProjectEmbeddingConfigRequest(
            ModelProviderType provider,
            String baseUrl,
            String apiPath,
            String apiKey,
            String modelName,
            int dimensions,
            int batchSize) {
    }

    public record ProjectEmbeddingConfigView(
            String provider,
            String baseUrl,
            String apiPath,
            boolean hasApiKey,
            String modelName,
            int dimensions,
            int batchSize,
            boolean enabled) {

        public static ProjectEmbeddingConfigView from(ProjectEmbeddingConfig config) {
            return new ProjectEmbeddingConfigView(
                    config.provider(), config.baseUrl(), config.apiPath(),
                    config.encryptedApiKey() != null && !config.encryptedApiKey().isBlank(),
                    config.modelName(), config.dimensions(), config.batchSize(), config.enabled());
        }

        public static ProjectEmbeddingConfigView empty() {
            return new ProjectEmbeddingConfigView(null, null, null, false, null, 1024, 10, false);
        }
    }
}
