package com.shitulelv.aicollab.infrastructure.ai.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMasterKeyFailFastTest {

    @Test
    void missing_master_key_fails_fast() {
        assertThatThrownBy(() -> new ModelSecretCipher(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void public_placeholder_key_is_rejected() {
        assertThatThrownBy(() -> new ModelSecretCipher("local-model-config-key-change-me"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void default_config_has_no_public_fallback() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(stream).isNotNull();
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).doesNotContain("local-model-config-key-change-me");
        }
    }

    @Test
    void encrypted_key_does_not_contain_plaintext() {
        ModelSecretCipher cipher = new ModelSecretCipher("current-master-key-for-test");
        String encrypted = cipher.encrypt("sk-secret-xyz");
        assertThat(encrypted).doesNotContain("sk-secret-xyz");
    }
}
