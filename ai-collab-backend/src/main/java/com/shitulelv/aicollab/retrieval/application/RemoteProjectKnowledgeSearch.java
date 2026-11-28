package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.retrieval.RetrievalScores;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchClient;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchModels;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Executes a routed request within the project's current readable document scope. */
@Component
class RemoteProjectKnowledgeSearch {
    private static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");
    private final DocumentRepository documents;
    private final KnowledgeSearchClient search;
    private final KnowledgeHitVerifier verifier;
    private final RerankCapabilityResolver rerank;
    private final KnowledgeSearchProperties properties;

    RemoteProjectKnowledgeSearch(DocumentRepository documents, KnowledgeSearchClient search,
            KnowledgeHitVerifier verifier, RerankCapabilityResolver rerank,
            KnowledgeSearchProperties properties) {
        this.documents = documents;
        this.search = search;
        this.verifier = verifier;
        this.rerank = rerank;
        this.properties = properties;
    }

    List<DocumentSearchHit> execute(ProjectKnowledgeRetrieval.IntentRequest request,
            List<UUID> requested, String rawQuery) {
        UUID projectId = request.projectId();
        Map<UUID, String> visible = new LinkedHashMap<>();
        for (DocumentEntity document : documents.listVisibleForRetrieval(projectId, requested, List.of(),
                new DocumentFilterCriteria(), KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS + 1)) {
            visible.put(document.getId(), document.getOriginalFilename());
        }
        if (visible.size() > KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "可见文档数超过上限 " + KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS);
        }
        if (visible.isEmpty()) {
            return List.of();
        }

        var rerankMode = rerank.hasPublishedBinding(projectId)
                ? KnowledgeSearchModels.Rerank.CONFIGURED : KnowledgeSearchModels.Rerank.HEURISTIC;
        var outbound = new KnowledgeSearchModels.Request(UUID.randomUUID(), projectId, request.userId(),
                KnowledgeSearchModels.Intent.valueOf(request.intent().name()), RetrievalStrategy.HYBRID.name(),
                List.of(KnowledgeSearchModels.Query.primary(rawQuery)), rawQuery, List.copyOf(visible.keySet()),
                request.topK(), KnowledgeSearchClient.candidatePool(request.topK()),
                new KnowledgeSearchModels.HeadingFilter(null, null),
                rerankMode, new KnowledgeSearchModels.Expand(true, true), properties.deadlineMs());
        var response = search.search(outbound);
        var verified = verifier.verify(projectId, visible.keySet(), response.hits());
        return verified.accepted().stream()
                .map(hit -> toSearchHit(hit, visible.get(hit.documentId()), rerankMode.name()))
                .toList();
    }

    private static DocumentSearchHit toSearchHit(KnowledgeHitVerifier.VerifiedHit hit, String filename,
            String reranker) {
        var raw = hit.hit();
        var scores = raw.scores();
        Double dense = scores == null ? null : scores.dense();
        Double lexical = scores == null ? null : scores.lexical();
        double admission = scores == null || scores.finalScore() == null ? 0 : scores.finalScore();
        String basis = dense == null
                ? lexical == null ? RetrievalScores.BASIS_NONE : RetrievalScores.BASIS_LEXICAL
                : lexical == null ? RetrievalScores.BASIS_DENSE : RetrievalScores.BASIS_DENSE_AND_LEXICAL;
        Map<String, Object> metadata = RetrievalScores.of(dense, lexical,
                scores == null ? null : scores.rrf(), scores == null ? null : scores.rerank(),
                reranker, admission, basis);
        metadata.put("chunkNo", hit.chunkNo());
        metadata.put("bodySnapshotId", hit.bodySnapshotId());
        if (hit.pageNumber() != null) {
            metadata.put("pageNumber", hit.pageNumber());
        }
        if (raw.chunkType() != null) {
            metadata.put("chunkType", raw.chunkType());
        }
        if (raw.parentChunkNo() != null) {
            metadata.put("parentChunkNo", raw.parentChunkNo());
        }
        verifiedContext(hit).ifPresent(context -> metadata.put("expandedContent", context));
        return new DocumentSearchHit(hit.rowId(), hit.documentId(), filename, hit.heading(), hit.content(),
                hit.contentHash(), admission, Map.copyOf(metadata));
    }

    private static java.util.Optional<String> verifiedContext(KnowledgeHitVerifier.VerifiedHit hit) {
        var context = hit.hit().context();
        if (context == null || context.text() == null || context.text().isBlank()) {
            return java.util.Optional.empty();
        }
        String expected = normalizeContext(context.text());
        for (var row : hit.contextRows()) {
            if (row.content() == null) continue;
            String databaseText = normalizeContext(row.content());
            int start = databaseText.indexOf(expected);
            if (start >= 0) {
                return java.util.Optional.of(databaseText.substring(start, start + expected.length()));
            }
        }
        return java.util.Optional.empty();
    }

    private static String normalizeContext(String text) {
        return WHITESPACE.matcher(text.strip()).replaceAll(" ");
    }
}
