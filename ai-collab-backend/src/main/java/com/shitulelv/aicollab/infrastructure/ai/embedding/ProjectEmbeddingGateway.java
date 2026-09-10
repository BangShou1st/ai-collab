package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 项目级向量嵌入网关。
 * 根据 projectId 从数据库读取嵌入配置，不再依赖 .env。
 */
@Component
public class ProjectEmbeddingGateway {
    private static final Logger log = LoggerFactory.getLogger(ProjectEmbeddingGateway.class);

    private final ProjectEmbeddingConfigRepository configRepo;
    private final ModelSecretCipher secrets;

    public ProjectEmbeddingGateway(ProjectEmbeddingConfigRepository configRepo, ModelSecretCipher secrets) {
        this.configRepo = configRepo;
        this.secrets = secrets;
    }

    public EmbeddingBatch embed(UUID projectId, List<String> input, EmbeddingProgressListener progressListener) {
        ProjectEmbeddingConfig config = configRepo.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "项目未配置嵌入模型"));
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "嵌入模型已禁用");
        }
        return embedWithConfig(config, input, progressListener);
    }

    EmbeddingBatch embedWithConfig(ProjectEmbeddingConfig config, List<String> input,
                                    EmbeddingProgressListener progressListener) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        RestClient restClient = RestClient.builder().requestFactory(factory).build();

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
                progressListener.onProgress();
                var response = restClient.post()
                        .uri(join(config.baseUrl(), config.apiPath()))
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
                        e.getStatusCode().value(), e.getResponseBodyAsString());
                if (e.getStatusCode().value() != 429) throw failure();
                last = e;
            } catch (HttpServerErrorException e) {
                log.warn("Embedding API server error (attempt {}): {} {}", attempt + 1,
                        e.getStatusCode().value(), e.getResponseBodyAsString());
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
