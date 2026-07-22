package com.shitulelv.aicollab.auth.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

    @Test
    void generatesThirtyTwoRandomBytesAsUnpaddedUrlSafeBase64() {
        RefreshTokenGenerator generator = new RefreshTokenGenerator();

        String generated = generator.generate();

        assertThat(Base64.getUrlDecoder().decode(generated)).hasSize(32);
        assertThat(generated).matches("[A-Za-z0-9_-]+");
        assertThat(generated).doesNotContain("=");
    }
}
