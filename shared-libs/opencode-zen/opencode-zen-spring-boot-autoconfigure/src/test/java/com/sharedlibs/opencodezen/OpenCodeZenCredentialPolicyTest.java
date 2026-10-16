package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeZenCredentialPolicyTest {

    @Test
    void freeModelAlwaysUsesPublicCredentialEvenWhenPaidKeyExists() {
        assertThat(OpenCodeZenCredentialPolicy.credentialForModel(true, "stored-paid-key"))
                .isEqualTo("public");
        assertThat(OpenCodeZenCredentialPolicy.authorizationHeader(true, "stored-paid-key"))
                .isEqualTo("Bearer public");
    }

    @Test
    void freeModelDoesNotRequireAnApiKey() {
        assertThat(OpenCodeZenCredentialPolicy.authorizationHeader(true, null))
                .isEqualTo("Bearer public");
    }

    @Test
    void paidModelUsesConfiguredKeyAndRejectsMissingKey() {
        assertThat(OpenCodeZenCredentialPolicy.authorizationHeader(false, "paid-key"))
                .isEqualTo("Bearer paid-key");
        assertThatThrownBy(() -> OpenCodeZenCredentialPolicy.credentialForModel(false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required for paid");
        assertThatThrownBy(() -> OpenCodeZenCredentialPolicy.credentialForModel(false, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
