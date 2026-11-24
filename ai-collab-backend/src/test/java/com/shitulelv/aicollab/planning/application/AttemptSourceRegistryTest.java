package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptSourceRegistryTest {

    @Test
    void preservesInitialSourcesAndNumbersDynamicHitsSequentially() {
        UUID doc1 = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        PlanSource s1 = new PlanSource("S1", doc1, "spec.md", chunk1, "Overview", 0.92d, "initial quote", "hash-1");
        AttemptSourceRegistry registry = new AttemptSourceRegistry(List.of(s1), 12, 6000);

        UUID doc2 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        Optional<PlanSource> registered = registry.register(new DocumentSearchHit(
                chunk2, doc2, "arch.md", "Backend", "dynamic hit content", "hash-2", 0.88d));

        assertThat(registered).isPresent();
        assertThat(registered.get().ref()).isEqualTo("S2");
        assertThat(registered.get().documentId()).isEqualTo(doc2);
        assertThat(registered.get().chunkId()).isEqualTo(chunk2);
        assertThat(registry.snapshot()).hasSize(2)
                .extracting(PlanSource::ref)
                .containsExactly("S1", "S2");
    }

    @Test
    void deduplicatesByContentHashAndByDocumentChunkPair() {
        UUID doc1 = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        PlanSource s1 = new PlanSource("S1", doc1, "spec.md", chunk1, "Sec", 0.95d, "base content", "hash-dup");
        AttemptSourceRegistry registry = new AttemptSourceRegistry(List.of(s1), 12, 6000);

        Optional<PlanSource> byHash = registry.register(new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "copy.md", "Sec", "base content", "hash-dup", 0.80d));
        Optional<PlanSource> byChunk = registry.register(new DocumentSearchHit(
                chunk1, doc1, "spec.md", "Sec", "slightly different quote", "hash-other", 0.85d));

        assertThat(byHash).contains(s1);
        assertThat(byChunk).contains(s1);
        assertThat(registry.snapshot()).hasSize(1);
    }

    @Test
    void capsSourcesAtTwelveToMatchValidatorInvariant() {
        AttemptSourceRegistry registry = new AttemptSourceRegistry(List.of(), 20, 100_000);
        assertThat(registry.maxSources()).isEqualTo(AttemptSourceRegistry.HARD_MAX_SOURCES).isEqualTo(12);

        for (int i = 1; i <= 15; i++) {
            Optional<PlanSource> res = registry.register(new DocumentSearchHit(
                    UUID.randomUUID(), UUID.randomUUID(), "doc-" + i + ".md",
                    "H" + i, "content-" + i, "hash-" + i, 0.8d));
            if (i <= 12) {
                assertThat(res).isPresent();
                assertThat(res.get().ref()).isEqualTo("S" + i);
            } else {
                assertThat(res).isEmpty();
            }
        }
        assertThat(registry.snapshot()).hasSize(12);
    }

    @Test
    void enforcesCodepointBudgetAndAllowsSmallerSubsequentHits() {
        String long250 = "a".repeat(250);
        String tooLong300 = "b".repeat(300);
        String small120 = "c".repeat(120);
        AttemptSourceRegistry registry = new AttemptSourceRegistry(List.of(), 12, 400);

        Optional<PlanSource> first = registry.register(new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "a.md", "H1", long250, "h1", 0.9d));
        Optional<PlanSource> oversized = registry.register(new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "b.md", "H2", tooLong300, "h2", 0.85d));
        Optional<PlanSource> fit = registry.register(new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "c.md", "H3", small120, "h3", 0.80d));

        assertThat(first).isPresent();
        assertThat(first.get().quoteText()).hasSize(250);
        assertThat(oversized).isEmpty();
        assertThat(fit).isPresent();
        assertThat(fit.get().ref()).isEqualTo("S2");
        assertThat(registry.register(null)).isEmpty();
        assertThat(registry.register(new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "blank.md", "H", "   ", "h-blank", 0.5d))).isEmpty();
    }
}
