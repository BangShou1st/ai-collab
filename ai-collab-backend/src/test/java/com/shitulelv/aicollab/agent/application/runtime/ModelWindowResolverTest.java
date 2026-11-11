package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileService;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileSource;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-P1：模型窗口解析优先级——全局档案 > window-overrides > 未知。 */
class ModelWindowResolverTest {
    private static final String PROVIDER = "OPENAI_COMPATIBLE";
    private static final String BASE_URL = "https://gateway.example/v1";
    private static final String API_PATH = "/chat/completions";
    private static final String MODEL = "gpt-4o";

    @Test
    void verifiedProfileWinsOverConfigOverrideAndIsNotEstimated() {
        var profiles = mock(ModelProfileService.class);
        when(profiles.resolve(PROVIDER, BASE_URL, API_PATH, MODEL))
                .thenReturn(Optional.of(profile(ModelProfileSource.ADMIN_VERIFIED, 128_000)));
        var resolver = new ModelWindowResolver(profiles,
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of(MODEL, 8_000)));

        var window = resolver.resolve(PROVIDER, BASE_URL, API_PATH, MODEL);

        assertThat(window.windowTokens()).isEqualTo(128_000);
        assertThat(window.estimated()).isFalse();
        assertThat(window.basis()).startsWith("PROFILE:");
    }

    @Test
    void unverifiedProfileIsUsableButStillMarkedEstimated() {
        var profiles = mock(ModelProfileService.class);
        when(profiles.resolve(PROVIDER, BASE_URL, API_PATH, MODEL))
                .thenReturn(Optional.of(profile(ModelProfileSource.UNVERIFIED, 64_000)));
        var resolver = new ModelWindowResolver(profiles,
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of()));

        var window = resolver.resolve(PROVIDER, BASE_URL, API_PATH, MODEL);

        assertThat(window.windowTokens()).isEqualTo(64_000);
        assertThat(window.estimated()).isTrue();
        assertThat(window.basis()).startsWith("PROFILE_UNVERIFIED:");
    }

    @Test
    void missingProfileFallsBackToConfiguredOverrides() {
        var resolver = new ModelWindowResolver(mock(ModelProfileService.class),
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of(MODEL, 32_000)));

        var window = resolver.resolve(PROVIDER, BASE_URL, API_PATH, MODEL);

        assertThat(window.windowTokens()).isEqualTo(32_000);
        assertThat(window.estimated()).isFalse();
        assertThat(window.basis()).isEqualTo(MODEL);
    }

    @Test
    void noProfileAndNoOverrideIsUnknown() {
        var resolver = new ModelWindowResolver(mock(ModelProfileService.class),
                AgentContextProperties.defaults());

        var window = resolver.resolve(PROVIDER, BASE_URL, API_PATH, MODEL);

        assertThat(window.windowTokens()).isNull();
        assertThat(window.estimated()).isTrue();
        assertThat(window.basis()).isEqualTo("UNKNOWN");
    }

    private static ModelProfile profile(ModelProfileSource source, int window) {
        boolean verified = source.verified();
        return new ModelProfile(UUID.randomUUID(), PROVIDER, BASE_URL, API_PATH, MODEL,
                window, 8_192, true, "cl100k", "2026-11-11", source,
                verified ? OffsetDateTime.now() : null, null, OffsetDateTime.now());
    }
}
