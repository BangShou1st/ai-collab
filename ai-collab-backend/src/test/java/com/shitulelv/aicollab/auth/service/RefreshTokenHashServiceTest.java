package com.shitulelv.aicollab.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHashServiceTest {

    private final RefreshTokenHashService service = new RefreshTokenHashService();

    @Test
    void hashes_to_expected_sha256_digest() {
        // echo -n "abc" | sha256sum
        assertThat(service.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void produces_lowercase_hex_of_64_chars() {
        String digest = service.hash("some-refresh-token-value");

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void different_tokens_produce_different_digests() {
        assertThat(service.hash("token-a")).isNotEqualTo(service.hash("token-b"));
    }

    @Test
    void same_token_produces_stable_digest() {
        String token = "stable-token";

        assertThat(service.hash(token)).isEqualTo(service.hash(token));
    }
}
