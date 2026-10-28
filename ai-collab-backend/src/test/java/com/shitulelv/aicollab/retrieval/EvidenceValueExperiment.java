package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptText;

import java.util.*;

/** P4-03: two frozen, test-only hypotheses. No labels, model calls or corpus-specific rules. */
final class EvidenceValueExperiment {
    private EvidenceValueExperiment() {}

    enum Strategy { A, D, E }

    // A with a sufficiently large limit gives all eligible, per-document hash-unique candidates.
    static List<DocumentSearchHit> eligible(List<DocumentSearchHit> hits) {
        return SourceCapStrategyExperiment.selectCurrent(hits, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparingDouble(DocumentSearchHit::similarity).reversed()
                        .thenComparing(DocumentSearchHit::id)).toList();
    }

    /** D changes representatives within documents; E protects all A representatives and ranks only spare slots. */
    static List<DocumentSearchHit> select(Strategy strategy, String query, List<DocumentSearchHit> hits, int limit) {
        if (strategy == Strategy.A) return SourceCapStrategyExperiment.selectCurrent(hits, limit);
        if (limit < 1) return List.of();
        List<DocumentSearchHit> unique = eligible(hits);
        Map<UUID, List<DocumentSearchHit>> groups = new LinkedHashMap<>();
        unique.forEach(h -> groups.computeIfAbsent(h.documentId(), ignored -> new ArrayList<>()).add(h));
        Set<String> terms = bigrams(query);
        List<DocumentSearchHit> selected = new ArrayList<>();
        for (List<DocumentSearchHit> group : groups.values()) {
            if (selected.size() == limit) break;
            DocumentSearchHit representative = group.getFirst();
            if (strategy == Strategy.D) {
                // Distinct query bigram coverage, not raw frequency: repeated boilerplate gets no bonus.
                representative = group.stream().max(Comparator
                        .comparingInt((DocumentSearchHit h) -> matched(terms, h).size())
                        .thenComparingDouble(DocumentSearchHit::similarity)
                        .thenComparing(DocumentSearchHit::id, Comparator.reverseOrder())).orElseThrow();
            }
            selected.add(representative);
        }
        while (selected.size() < limit) {
            List<DocumentSearchHit> remaining = unique.stream().filter(h -> !selected.contains(h)).toList();
            if (remaining.isEmpty()) break;
            DocumentSearchHit next = remaining.getFirst();
            if (strategy == Strategy.E) {
                Set<String> covered = new HashSet<>();
                selected.forEach(h -> covered.addAll(matched(terms, h)));
                next = remaining.stream().max(Comparator
                        .comparingInt((DocumentSearchHit h) -> novelty(terms, h, covered))
                        .thenComparingDouble(DocumentSearchHit::similarity)
                        .thenComparing(DocumentSearchHit::id, Comparator.reverseOrder())).orElseThrow();
            }
            selected.add(next);
        }
        return List.copyOf(selected);
    }

    private static int novelty(Set<String> terms, DocumentSearchHit hit, Set<String> covered) {
        Set<String> found = matched(terms, hit);
        found.removeAll(covered);
        return found.size();
    }

    static Set<String> matched(Set<String> terms, DocumentSearchHit hit) {
        Set<String> found = bigrams(Objects.toString(hit.heading(), "") + " " + hit.content());
        found.retainAll(terms);
        return found;
    }

    static Set<String> bigrams(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        int[] points = normalized.codePoints().toArray();
        for (int i = 1; i < points.length; i++) {
            if (Character.isLetterOrDigit(points[i - 1]) && Character.isLetterOrDigit(points[i])) {
                terms.add(new String(points, i - 1, 2));
            }
        }
        return terms;
    }

    /** Exact production fair-budget assembly, with order supplied by the experiment, never reselected. */
    static KnowledgeContext assemble(List<DocumentSearchHit> ordered, int limit) {
        if (limit < 1 || limit > 12) throw new IllegalArgumentException("source limit");
        List<KnowledgeSource> sources = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        int remaining = 8000;
        for (int index = 0; index < ordered.size() && sources.size() < limit; index++) {
            DocumentSearchHit hit = ordered.get(index);
            String prefix = (sources.isEmpty() ? "" : "\n\n") + "[S" + (sources.size() + 1) + "]\n文件："
                    + KnowledgePromptText.escapeXmlText(hit.originalFilename(), 180).escaped() + "\n标题："
                    + KnowledgePromptText.escapeXmlText(hit.heading(), 300).escaped() + "\n内容：";
            int prefixLength = prefix.codePointCount(0, prefix.length());
            int allowance = (remaining - prefixLength) / Math.max(1, ordered.size() - index);
            if (prefixLength >= remaining || allowance < 120) break;
            var body = KnowledgePromptText.escapeXmlText(hit.content(), allowance);
            if (body.original().isBlank()) continue;
            prompt.append(prefix).append(body.escaped());
            remaining -= prefixLength + body.escaped().codePointCount(0, body.escaped().length());
            sources.add(new KnowledgeSource(hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    body.original(), hit.contentHash(), hit.similarity(), sources.size() + 1,
                    hit.metadata() == null ? Map.of() : hit.metadata()));
        }
        return new KnowledgeContext(List.copyOf(sources), prompt.toString());
    }

    record Target(UUID documentId, String marker) {}

    /** Labels enter only this evaluator, after selection. Retention checks actual truncated body. */
    static Map<String, Object> measure(Strategy strategy, String query, List<DocumentSearchHit> hits,
                                       int limit, List<Target> targets) {
        long started = System.nanoTime();
        var ordered = select(strategy, query, hits, limit);
        long nanos = System.nanoTime() - started;
        var context = assemble(ordered, limit);
        if (strategy == Strategy.A) {
            org.assertj.core.api.Assertions.assertThat(context).isEqualTo(
                    new com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder().buildDiverse(hits, limit));
        }
        org.assertj.core.api.Assertions.assertThat(context.sources()).hasSizeLessThanOrEqualTo(limit);
        int points = context.promptSources().codePointCount(0, context.promptSources().length());
        org.assertj.core.api.Assertions.assertThat(points).isLessThanOrEqualTo(8000);
        for (var source : context.sources()) {
            org.assertj.core.api.Assertions.assertThat(hits).anyMatch(h -> h.id().equals(source.chunkId())
                    && h.documentId().equals(source.documentId()) && h.content().startsWith(source.content())
                    && Objects.equals(h.metadata() == null ? Map.of() : h.metadata(), source.metadata()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Integer> kept = new ArrayList<>();
        Set<UUID> docs = new LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (context.sources().stream().anyMatch(s -> s.documentId().equals(t.documentId())
                    && s.content().contains(t.marker()))) { kept.add(i); docs.add(t.documentId()); }
        }
        int recall8 = 0, recall20 = 0;
        for (Target t : targets) {
            if (hits.stream().limit(8).anyMatch(h -> h.documentId().equals(t.documentId()) && h.content().contains(t.marker()))) recall8++;
            if (hits.stream().anyMatch(h -> h.documentId().equals(t.documentId()) && h.content().contains(t.marker()))) recall20++;
        }
        var baseline = assemble(select(Strategy.A, query, hits, limit), limit);
        List<Integer> gained = new ArrayList<>(), lost = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            boolean before = baseline.sources().stream().anyMatch(s -> s.documentId().equals(t.documentId()) && s.content().contains(t.marker()));
            if (kept.contains(i) && !before) gained.add(i);
            if (!kept.contains(i) && before) lost.add(i);
        }
        result.put("expected", targets.size()); result.put("retained", kept);
        result.put("gained", gained); result.put("lost", lost);
        result.put("recall8Count", recall8); result.put("recall20Count", recall20);
        result.put("recall8", targets.isEmpty() ? null : (double) recall8 / targets.size());
        result.put("recall20", targets.isEmpty() ? null : (double) recall20 / targets.size());
        result.put("retention", targets.isEmpty() ? null : (double) kept.size() / targets.size());
        result.put("expectedDocuments", targets.stream().map(Target::documentId).distinct().count());
        result.put("coveredExpectedDocuments", docs.size());
        long expectedDocCount = targets.stream().map(Target::documentId).distinct().count();
        result.put("documentCoverage", expectedDocCount == 0 ? null : (double) docs.size() / expectedDocCount);
        result.put("sourceCount", context.sources().size()); result.put("contextCodePoints", points);
        result.put("selectionNanos", nanos);
        result.put("unexpectedSources", context.sources().stream().filter(s -> targets.stream().noneMatch(t ->
                s.documentId().equals(t.documentId()) && s.content().contains(t.marker()))).count());
        result.put("sources", context.sources().stream().map(s -> Map.of("chunkId", s.chunkId(),
                "documentId", s.documentId(), "filename", s.originalFilename(), "heading", s.heading(),
                "bodyCodePoints", s.content().codePointCount(0, s.content().length()))).toList());
        return result;
    }
}
