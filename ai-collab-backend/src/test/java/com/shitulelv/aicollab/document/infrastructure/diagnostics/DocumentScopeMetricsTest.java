package com.shitulelv.aicollab.document.infrastructure.diagnostics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** J1-2-P1：删正则前必须先能回答"这条范围正则被依赖多少"。 */
class DocumentScopeMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DocumentScopeMetrics metrics = new DocumentScopeMetrics(registry);

    @AfterEach void close() { registry.close(); }

    @Test
    void countsEachScopeOutcomeSeparately() {
        metrics.scope("UNRESTRICTED");
        metrics.scope("ENFORCED");
        metrics.scope("ENFORCED");
        metrics.scope("UNRESOLVED_INCOMPLETE");

        assertThat(registry.get(DocumentScopeMetrics.COUNTER).tag("status", "UNRESTRICTED").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentScopeMetrics.COUNTER).tag("status", "ENFORCED").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(DocumentScopeMetrics.COUNTER).tag("status", "UNRESOLVED_INCOMPLETE").counter()
                .count()).isEqualTo(1);
        assertThat(registry.find(DocumentScopeMetrics.COUNTER).tag("status", "EMPTY").counter()).isNull();
    }

    @Test
    void unknownOrMalformedStatusNeverBecomesAPlausibleLabel() {
        metrics.scope(null);
        metrics.scope(" ");
        metrics.scope("enforced");
        metrics.scope("SOMETHING_ELSE");

        assertThat(registry.get(DocumentScopeMetrics.COUNTER).tag("status", "UNKNOWN").counter().count())
                .isEqualTo(4);
        assertThat(registry.getMeters()).hasSize(1);
        assertThat(registry.getMeters().stream().findFirst().orElseThrow().getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("status"));
    }

    @Test
    void brokenMeterRegistryNeverBreaksScopeResolution() {
        var broken = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("metrics unavailable"));

        assertThatCode(() -> new DocumentScopeMetrics(broken).scope("ENFORCED"))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizerAcceptsOnlyRealScopeStatuses() {
        assertThat(DocumentScopeMetrics.normalize("UNRESTRICTED")).isEqualTo("UNRESTRICTED");
        assertThat(DocumentScopeMetrics.normalize("ENFORCED")).isEqualTo("ENFORCED");
        assertThat(DocumentScopeMetrics.normalize("EMPTY")).isEqualTo("EMPTY");
        assertThat(DocumentScopeMetrics.normalize("UNRESOLVED_INCOMPLETE")).isEqualTo("UNRESOLVED_INCOMPLETE");
        assertThat(DocumentScopeMetrics.normalize(null)).isEqualTo("UNKNOWN");
        assertThat(DocumentScopeMetrics.normalize("Enforced")).isEqualTo("UNKNOWN");
    }
}
