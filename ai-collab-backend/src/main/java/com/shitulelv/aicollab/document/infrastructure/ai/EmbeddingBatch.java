package com.shitulelv.aicollab.document.infrastructure.ai;

import java.util.List;

public record EmbeddingBatch(String provider, String model, int dimension, List<List<Double>> vectors) {
}
