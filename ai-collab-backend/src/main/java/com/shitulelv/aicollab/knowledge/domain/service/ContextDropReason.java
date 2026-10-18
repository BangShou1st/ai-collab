package com.shitulelv.aicollab.knowledge.domain.service;

/** The concrete reason a retrieved chunk was omitted while building a knowledge context. */
public enum ContextDropReason {
    BELOW_SIMILARITY_THRESHOLD,
    EMPTY_BODY,
    INTRA_DOCUMENT_DUPLICATE,
    CROSS_DOCUMENT_DUPLICATE,
    SOURCE_CAP,
    BODY_BUDGET,
    UNATTRIBUTED
}
