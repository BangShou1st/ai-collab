package com.shitulelv.aicollab.document.infrastructure.ai;

@FunctionalInterface
public interface EmbeddingProgressListener {
    EmbeddingProgressListener NONE = () -> { };

    void onProgress();
}
