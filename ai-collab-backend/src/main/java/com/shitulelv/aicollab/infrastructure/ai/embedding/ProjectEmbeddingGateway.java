package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 系统级向量嵌入网关（V2 运行时）。
 * 只读取系统 Embedding 配置；旧 project_embedding_config 表保留为 deprecated 数据，不再参与路由。
 * projectId 参数仅保留为业务上下文（日志/调用链），不决定配置归属。
 */
@Component
public class ProjectEmbeddingGateway {
    private static final Logger log = LoggerFactory.getLogger(ProjectEmbeddingGateway.class);

    private final SystemEmbeddingConfigRepository configRepo;
    private final ModelSecretCipher secrets;
    private final OutboundEndpointPolicy endpoints;
    private final RestClient injectedRestClient;

    public ProjectEmbeddingGateway(SystemEmbeddingConfigRepository configRepo, ModelSecretCipher secrets) {
        this(configRepo, secrets, new OutboundEndpointPolicy());
    }

    @Autowired
    public ProjectEmbeddingGateway(SystemEmbeddingConfigRepository configRepo, ModelSecretCipher secrets,
                                   OutboundEndpointPolicy endpoints) {
        this(configRepo, secrets, endpoints, null);
    }

    ProjectEmbeddingGateway(SystemEmbeddingConfigRepository configRepo, ModelSecretCipher secrets,
                            OutboundEndpointPolicy endpoints, RestClient injectedRestClient) {
        this.configRepo = configRepo;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.injectedRestClient = injectedRestClient;
    }

    public EmbeddingBatch embed(UUID projectId, List<String> input, EmbeddingProgressListener progressListener) {
        SystemEmbeddingConfig config = configRepo.findActive()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "系统未配置嵌入模型，请联系管理员在管理中心 AI Infrastructure 中配置"));
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "嵌入模型已禁用");
        }
        log.debug("Embedding via system config for project {}", projectId);
        return embedWithConfig(asProjectConfig(config), input, progressListener);
    }

    public String activeFingerprint() {
        return configRepo.findActive()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "系统未配置嵌入模型，请联系管理员在管理中心 AI Infrastructure 中配置"))
                .fingerprint();
    }

    private static ProjectEmbeddingConfig asProjectConfig(SystemEmbeddingConfig active) {
        return new ProjectEmbeddingConfig(null, active.provider(), active.baseUrl(), active.apiPath(),
                active.encryptedApiKey(), active.modelName(), active.dimensions(), active.batchSize(),
                true, active.createdAt(), active.updatedAt());
    }

    EmbeddingBatch embedWithConfig(ProjectEmbeddingConfig config, List<String> input,
                                    EmbeddingProgressListener progressListener) {

        RestClient restClient = injectedRestClient != null ? injectedRestClient : createRestClient();

        String apiKey = secrets.decrypt(config.encryptedApiKey());
        List<List<Double>> vectors = new ArrayList<>(input.size());
        int size = config.batchSize();
        for (int from = 0; from < input.size(); from += size) {
            int to = Math.min(input.size(), from + size);
            vectors.addAll(callWithRetry(restClient, config, apiKey, input.subList(from, to), progressListener));
        }
        validateVectors(vectors, input.size(), config.dimensions());
        return new EmbeddingBatch(config.provider(), config.modelName(), config.dimensions(), vectors);
    }

    private List<List<Double>> callWithRetry(
            RestClient restClient, ProjectEmbeddingConfig config, String apiKey,
            List<String> batch, EmbeddingProgressListener progressListener) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                URI endpoint = URI.create(join(config.baseUrl(), config.apiPath()));
                endpoints.requirePublicHttps(endpoint);
                progressListener.onProgress();
                var response = restClient.post()
                        .uri(endpoint)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(new EmbeddingRequest(config.modelName(), batch, config.dimensions()))
                        .retrieve().body(EmbeddingResponse.class);
                if (response == null || response.data() == null) throw failure();
                if (response.data().stream().anyMatch(item -> item == null || item.index() == null)) {
                    throw failure();
                }
                var ordered = response.data().stream()
                        .sorted(Comparator.comparingInt(EmbeddingData::index)).toList();
                if (ordered.size() != batch.size()) throw failure();
                for (int i = 0; i < ordered.size(); i++) {
                    if (ordered.get(i).index() != i) throw failure();
                }
                List<List<Double>> vectors = ordered.stream().map(EmbeddingData::embedding).toList();
                validateVectors(vectors, batch.size(), config.dimensions());
                progressListener.onProgress();
                return vectors;
            } catch (BusinessException e) {
                throw e;
            } catch (HttpClientErrorException e) {
                log.warn("Embedding API client error (attempt {}): {} {}", attempt + 1,
                        e.getStatusCode().value(), truncate(e.getResponseBodyAsString(), 200));
                if (e.getStatusCode().value() != 429) throw failure();
                last = e;
            } catch (HttpServerErrorException e) {
                log.warn("Embedding API server error (attempt {}): {} {}", attempt + 1,
                        e.getStatusCode().value(), truncate(e.getResponseBodyAsString(), 200));
                last = e;
            } catch (ResourceAccessException e) {
                log.warn("Embedding API connection error (attempt {}): {}", attempt + 1, e.getMessage());
                last = e;
            } catch (RuntimeException e) {
                log.warn("Embedding API unexpected error (attempt {}): {}", attempt + 1, e.getMessage());
                throw failure();
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(250L * (1L << attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure();
                }
            }
        }
        throw new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED);
    }

    private static void validateVectors(List<List<Double>> vectors, int expectedCount, int dimensions) {
        if (vectors == null || vectors.size() != expectedCount) throw failure();
        for (List<Double> vector : vectors) {
            if (vector == null || vector.isEmpty() || vector.size() != dimensions) throw failure();
            for (Double value : vector) {
                if (value == null || !Double.isFinite(value)) throw failure();
            }
        }
    }

    private static String join(String base, String path) {
        return base.endsWith("/") && path.startsWith("/") ? base + path.substring(1)
                : !base.endsWith("/") && !path.startsWith("/") ? base + "/" + path : base + path;
    }

    private static RestClient createRestClient() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "null";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static BusinessException failure() {
        return new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED);
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions) {
    }

    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    private record EmbeddingData(Integer index, List<Double> embedding) {
    }
}
