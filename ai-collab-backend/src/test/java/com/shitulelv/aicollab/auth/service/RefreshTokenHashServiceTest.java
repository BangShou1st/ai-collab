package com.shitulelv.aicollab.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHashServiceTest {

    @Test
    void createsDeterministicLowercaseSha256HexHash() {
        RefreshTokenHashService service = new RefreshTokenHashService();
        String token = new RefreshTokenGenerator().generate();

        String first = service.hash(token);
        String second = service.hash(token);

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(first).isNotEqualTo(service.hash(new RefreshTokenGenerator().generate()));
    }

    @Test
    void createsTheStandardSha256DigestForKnownInput() {
        RefreshTokenHashService service = new RefreshTokenHashService();

        assertThat(service.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
