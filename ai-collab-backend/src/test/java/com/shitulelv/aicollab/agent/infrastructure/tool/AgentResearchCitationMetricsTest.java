package com.shitulelv.aicollab.agent.infrastructure.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResearchCitationMetricsTest {
    @Test
    void unknownPersistedCitationIdentitiesAreCountedAsWrong() {
        UUID document = UUID.randomUUID();
        UUID validChunk = UUID.randomUUID();
        UUID unknownChunk = UUID.randomUUID();
        UUID unknownDocument = UUID.randomUUID();
        String raw = """
                [
                  {"documentId":"%s","chunkId":"%s"},
                  {"documentId":"%s","chunkId":"%s"}
                ]
                """.formatted(document, validChunk, unknownDocument, unknownChunk);

        var metrics = AgentResearchQualityPostgresTest.measureCitationMetrics(
                raw, Set.of(validChunk), Set.of(document), Set.of(validChunk));

        assertThat(metrics.total()).isEqualTo(3);
        assertThat(metrics.supported()).isEqualTo(1);
        assertThat(metrics.wrong()).isEqualTo(2);
    }

    @Test
    void malformedCitationPayloadCannotProduceZeroWrongCitations() {
        var metrics = AgentResearchQualityPostgresTest.measureCitationMetrics(
                "not-json", Set.of(), Set.of(), Set.of());

        assertThat(metrics.total()).isEqualTo(1);
        assertThat(metrics.supported()).isZero();
        assertThat(metrics.wrong()).isEqualTo(1);
    }
}
