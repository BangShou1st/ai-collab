package com.shitulelv.aicollab.common.retrieval;

import java.util.List;

/**
 * 带检索元信息的证据集合。
 */
public record EvidenceBundle(
        List<EvidenceRef> items,
        String retrievalCore,
        String strategy) {
    public EvidenceBundle {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
