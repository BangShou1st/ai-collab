package com.shitulelv.aicollab.agent.job;

import java.util.Set;

/** {@code ai_job.job_type} 的取值，与 V105 {@code ck_ai_job_type} 一一对应。 */
public final class AiJobTypes {

    public static final String MEMORY_EXTRACTION = "MEMORY_EXTRACTION";
    public static final String INDEX_DOCUMENT = "INDEX_DOCUMENT";
    public static final String DELETE_DOCUMENT_INDEX = "DELETE_DOCUMENT_INDEX";
    public static final String REINDEX_GENERATION = "REINDEX_GENERATION";

    public static final Set<String> ALL =
            Set.of(MEMORY_EXTRACTION, INDEX_DOCUMENT, DELETE_DOCUMENT_INDEX, REINDEX_GENERATION);

    private AiJobTypes() {
    }
}
