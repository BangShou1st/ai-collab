package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic two-stage selection for cross-document knowledge context sources. */
public final class CrossDocumentSourceSelector {

    public List<DocumentSearchHit> select(List<DocumentSearchHit> hits, int maxSources) {
        return selectWithDiagnostics(hits, maxSources).hits();
    }

    /** Selects diverse representatives first, then fills remaining slots by global relevance. */
    public SelectionResult selectWithDiagnostics(List<DocumentSearchHit> hits, int maxSources) {
        if (hits == null || hits.isEmpty() || maxSources < 1) {
            return new SelectionResult(List.of(), Map.of());
        }

        Map<UUID, ContextDropReason> dropped = new LinkedHashMap<>();
        List<DocumentSearchHit> eligible = new ArrayList<>(hits.size());
        for (DocumentSearchHit hit : hits) {
            if (hit == null) {
                continue;
            }
            if (hit.similarity() < KnowledgeContextBuilder.MIN_SIMILARITY) {
                dropped.put(hit.id(), ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
            } else if (hit.content() == null || hit.content().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.EMPTY_BODY);
            } else {
                eligible.add(hit);
            }
        }
        eligible.sort(Comparator
                .comparingDouble(DocumentSearchHit::similarity).reversed()
                .thenComparing(DocumentSearchHit::id));

        // Sorted input ensures each document keeps its best chunk for each content hash.
        Map<UUID, List<DocumentSearchHit>> byDocument = new LinkedHashMap<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        List<DocumentSearchHit> unique = new ArrayList<>(eligible.size());
        for (DocumentSearchHit hit : eligible) {
            Set<String> hashes = hashesByDocument.computeIfAbsent(
                    hit.documentId(), ignored -> new HashSet<>());
            String hash = hit.contentHash();
            if (hash != null && !hashes.add(hash)) {
                dropped.put(hit.id(), ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
                continue;
            }
            unique.add(hit);
            byDocument.computeIfAbsent(hit.documentId(), ignored -> new ArrayList<>()).add(hit);
        }

        List<DocumentSearchHit> selected = new ArrayList<>(Math.min(maxSources, unique.size()));
        Set<UUID> selectedIds = new LinkedHashSet<>();

        // Stage one: each document gets its highest-ranked eligible, nonduplicate chunk.
        for (List<DocumentSearchHit> documentHits : byDocument.values()) {
            if (selected.size() == maxSources) {
                break;
            }
            DocumentSearchHit representative = documentHits.getFirst();
            selected.add(representative);
            selectedIds.add(representative.id());
        }

        // Stage two: fill remaining slots from all other chunks in global similarity order.
        for (DocumentSearchHit hit : unique) {
            if (selected.size() == maxSources) {
                break;
            }
            if (selectedIds.add(hit.id())) {
                selected.add(hit);
            }
        }

        // Every otherwise eligible chunk not selected was excluded by the source limit.
        for (DocumentSearchHit hit : unique) {
            if (!selectedIds.contains(hit.id())) {
                dropped.put(hit.id(), ContextDropReason.SOURCE_CAP);
            }
        }
        return new SelectionResult(selected, dropped);
    }

    public record SelectionResult(
            List<DocumentSearchHit> hits,
            Map<UUID, ContextDropReason> droppedReasons) {
        public SelectionResult {
            hits = List.copyOf(hits);
            droppedReasons = Map.copyOf(droppedReasons);
        }
    }
}
