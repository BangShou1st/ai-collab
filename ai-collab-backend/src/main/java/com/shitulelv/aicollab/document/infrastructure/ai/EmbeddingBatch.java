package com.shitulelv.aicollab.document.infrastructure.ai;

import java.util.List;

public record EmbeddingBatch(String provider, String model, int dimension, List<List<Double>> vectors,
        String fingerprint, java.util.UUID generationId) {
    public EmbeddingBatch(String provider, String model, int dimension, List<List<Double>> vectors, String fingerprint) {
        this(provider, model, dimension, vectors, fingerprint, null);
    }
    public EmbeddingBatch(String provider, String model, int dimension, List<List<Double>> vectors) {
        this(provider, model, dimension, vectors,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint(provider, model, dimension), null);
    }
}
