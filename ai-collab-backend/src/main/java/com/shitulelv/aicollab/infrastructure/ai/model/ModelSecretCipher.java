package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ModelSecretCipher {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final java.util.Set<String> FORBIDDEN_PLACEHOLDERS = java.util.Set.of(
            "local-model-config-key-change-me",
            "change-me",
            "replace-with-a-long-random-master-key");
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ModelSecretCipher(@Value("${model.config.master-key}") String masterKey) {
        if (masterKey == null || masterKey.length() < 16
                || FORBIDDEN_PLACEHOLDERS.contains(masterKey.strip())) {
            throw new IllegalStateException("model.config.master-key must contain at least 16 characters");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize model secret cipher", exception);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.strip().getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt model API key", exception);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
        }
        try {
            byte[] packed = Base64.getDecoder().decode(encryptedText);
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
        }
    }
}
