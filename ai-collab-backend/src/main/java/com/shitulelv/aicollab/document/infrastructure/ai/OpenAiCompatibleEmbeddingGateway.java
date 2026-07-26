package com.shitulelv.aicollab.document.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.EmbeddingProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {
    private final EmbeddingProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingGateway(EmbeddingProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public EmbeddingBatch embed(List<String> input, EmbeddingProgressListener progressListener) {
        validateConfiguration();
        List<List<Double>> vectors = new ArrayList<>(input.size());
        int size = properties.batchSize();
        for (int from = 0; from < input.size(); from += size) {
            int to = Math.min(input.size(), from + size);
            vectors.addAll(callWithRetry(input.subList(from, to), progressListener));
        }
        validateVectors(vectors, input.size());
        return new EmbeddingBatch(properties.provider(), properties.model(), properties.dimensions(), vectors);
    }

    private List<List<Double>> callWithRetry(
            List<String> batch, EmbeddingProgressListener progressListener) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                progressListener.onProgress();
                EmbeddingResponse response = restClient.post()
                        .uri(join(properties.baseUrl(), properties.path()))
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .body(new EmbeddingRequest(properties.model(), batch, properties.dimensions()))
                        .retrieve().body(EmbeddingResponse.class);
                if (response == null || response.data() == null) throw failure();
                if (response.data().stream().anyMatch(
                        item -> item == null || item.index() == null)) {
                    throw failure();
                }
                List<EmbeddingData> ordered = response.data().stream()
                        .sorted(Comparator.comparingInt(EmbeddingData::index)).toList();
                if (ordered.size() != batch.size()) throw failure();
                for (int index = 0; index < ordered.size(); index++) {
                    if (ordered.get(index).index() != index) throw failure();
                }
                List<List<Double>> vectors = ordered.stream().map(EmbeddingData::embedding).toList();
                validateVectors(vectors, batch.size());
                progressListener.onProgress();
                return vectors;
            } catch (BusinessException exception) {
                throw exception;
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() != 429) throw failure();
                last = exception;
            } catch (HttpServerErrorException | ResourceAccessException exception) {
                last = exception;
            } catch (RuntimeException exception) {
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
        throw new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED,
                last == null ? ErrorCode.DOCUMENT_EMBEDDING_FAILED.message()
                        : ErrorCode.DOCUMENT_EMBEDDING_FAILED.message());
    }

    private void validateConfiguration() {
        if (!properties.enabled() || blank(properties.provider()) || blank(properties.baseUrl())
                || blank(properties.apiKey()) || blank(properties.model())
                || properties.dimensions() <= 0
                || properties.batchSize() < 1 || properties.batchSize() > 256) {
            throw failure();
        }
    }
    private void validateVectors(List<List<Double>> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) throw failure();
        for (List<Double> vector : vectors) {
            if (vector == null || vector.isEmpty() || vector.size() != properties.dimensions()) {
                throw failure();
            }
            for (Double value : vector) {
                if (value == null || !Double.isFinite(value)) throw failure();
            }
        }
    }
    private static String join(String base, String path) {
        return base.endsWith("/") && path.startsWith("/") ? base + path.substring(1)
                : !base.endsWith("/") && !path.startsWith("/") ? base + "/" + path : base + path;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BusinessException failure() {
        return new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED);
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions) {
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(Integer index, List<Double> embedding) {
    }
}
