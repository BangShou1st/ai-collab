package com.shitulelv.aicollab.document.infrastructure.ai;

import java.util.List;

public interface EmbeddingGateway {
    default EmbeddingBatch embed(List<String> input) {
        return embed(input, EmbeddingProgressListener.NONE);
    }

    EmbeddingBatch embed(List<String> input, EmbeddingProgressListener progressListener);
}
