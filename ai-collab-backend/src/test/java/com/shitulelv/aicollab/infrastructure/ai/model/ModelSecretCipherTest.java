package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelSecretCipherTest {
    @Test
    void reportsAStableCredentialErrorWhenTheMasterKeyCannotDecryptStoredData() {
        ModelSecretCipher original = new ModelSecretCipher("original-master-key-for-test");
        String encrypted = original.encrypt("provider-api-key");
        ModelSecretCipher rotated = new ModelSecretCipher("rotated-master-key-for-test");

        assertThatThrownBy(() -> rotated.decrypt(encrypted))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode().name())
                                .isEqualTo("AI_MODEL_CREDENTIAL_INVALID"));
    }

    @Test
    void decryptsCredentialsEncryptedWithTheCurrentMasterKey() {
        ModelSecretCipher cipher = new ModelSecretCipher("current-master-key-for-test");

        assertThat(cipher.decrypt(cipher.encrypt("provider-api-key")))
                .isEqualTo("provider-api-key");
    }

    @Test
    void ciphertextIsRandomizedBase64AndDoesNotContainThePlainText() {
        ModelSecretCipher cipher = new ModelSecretCipher("current-master-key-for-test");
        String first = cipher.encrypt("provider-api-key-plain-text");
        String second = cipher.encrypt("provider-api-key-plain-text");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("^[A-Za-z0-9+/]+={0,2}$");
        assertThat(first).doesNotContain("provider-api-key-plain-text");
        assertThat(cipher.decrypt(first)).isEqualTo("provider-api-key-plain-text");
        assertThat(cipher.decrypt(second)).isEqualTo("provider-api-key-plain-text");
    }
}
