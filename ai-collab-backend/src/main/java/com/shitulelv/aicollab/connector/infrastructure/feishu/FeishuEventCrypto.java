package com.shitulelv.aicollab.connector.infrastructure.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Official Feishu encrypted webhook format. All failures are deliberately opaque. */
@Component
public class FeishuEventCrypto {
    private static final int MAX_BYTES = 65536;
    private final ObjectMapper json = new ObjectMapper();

    public JsonNode decode(byte[] body, String key, String verificationToken,
                           String timestamp, String nonce, String signature) {
        try {
            if (body == null || body.length == 0 || body.length > MAX_BYTES || key == null || key.length() < 16
                    || verificationToken == null || verificationToken.length() < 16) throw new IllegalArgumentException();
            boolean signed = timestamp != null && nonce != null && signature != null;
            if (signed) {
                if (!timestamp.matches("[0-9]{10}") || !nonce.matches("[A-Za-z0-9_-]{1,128}")
                        || !signature.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
                long seconds = Long.parseLong(timestamp);
                long age = Instant.now().getEpochSecond() - seconds;
                // Feishu may retry delivery hours later; event IDs are persisted for deduplication.
                if (age < -60 || age > 24 * 60 * 60) throw new IllegalArgumentException();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update((timestamp + nonce + key).getBytes(StandardCharsets.UTF_8));
                if (!MessageDigest.isEqual(digest.digest(body), HexFormat.of().parseHex(signature))) throw new IllegalArgumentException();
            }
            JsonNode envelope = json.readTree(body);
            String encrypted = envelope.path("encrypt").asText("");
            byte[] packed = Base64.getDecoder().decode(encrypted);
            if (packed.length < 32 || packed.length % 16 != 0) throw new IllegalArgumentException();
            byte[] aesKey = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(packed, 0, 16));
            JsonNode decoded = json.readTree(aes.doFinal(Arrays.copyOfRange(packed,16,packed.length)));
            boolean challenge = "url_verification".equals(decoded.path("type").asText());
            if (!signed && !challenge) throw new IllegalArgumentException();
            String actual = challenge ? decoded.path("token").asText("") : decoded.path("header").path("token").asText("");
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), verificationToken.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException();
            }
            return decoded;
        } catch (Exception failure) {
            throw new IllegalArgumentException("飞书事件安全校验失败");
        }
    }
}
