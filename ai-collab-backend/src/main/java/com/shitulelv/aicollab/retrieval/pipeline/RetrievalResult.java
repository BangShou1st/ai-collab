package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;

import java.util.List;

/**
 * 统一检索管线输出结果包装。
 */
public record RetrievalResult(
        List<RetrievalCandidate> candidates,
        RetrievalTrace trace
) {

    public List<DocumentSearchHit> toDocumentSearchHits() {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream().map(RetrievalCandidate::toDocumentSearchHit).toList();
    }
}
