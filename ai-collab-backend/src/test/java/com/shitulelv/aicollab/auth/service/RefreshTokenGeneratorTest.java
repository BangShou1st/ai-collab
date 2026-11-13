package com.shitulelv.aicollab.auth.service;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

    @Test
    void generated_token_is_43_chars_and_round_trips_its_version() {
        String token = generator.generate(17);

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(generator.tokenVersion(token)).isEqualTo(OptionalInt.of(17));
        assertThat(generator.generate(17)).isNotEqualTo(token);
    }

    @Test
    void malformed_or_short_tokens_have_no_version() {
        assertThat(generator.tokenVersion("not-a-token")).isEmpty();
        assertThat(generator.tokenVersion("A".repeat(42))).isEmpty();
    }

    @Test
    void legacy_random_token_is_treated_as_version_zero() {
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(generator.tokenVersion(legacy)).isEqualTo(OptionalInt.of(0));
    }

    @Test
    void negative_version_is_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.generate(-1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.generate(65_536))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
