package com.shitulelv.aicollab.connector.infrastructure.feishu;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class FeishuEventCryptoTest {
    private final String key = "synthetic-event-encrypt-key";
    private final String token = "synthetic-verification-token";
    private final FeishuEventCrypto crypto = new FeishuEventCrypto();

    @Test
    void checksRawBodySignatureEncryptionTokenTimestampAndChallenge() throws Exception {
        byte[] event = envelope("{\"schema\":\"2.0\",\"header\":{\"token\":\""+token+"\",\"event_type\":\"auth.user_access_token.revoked_v4\"}}");
        String timestamp = Long.toString(Instant.now().getEpochSecond()), nonce = "synthetic-nonce";
        String signature = signature(timestamp,nonce,event);
        assertThat(crypto.decode(event,key,token,timestamp,nonce,signature).path("schema").asText()).isEqualTo("2.0");
        assertThatThrownBy(() -> crypto.decode(event,key,token,timestamp,nonce,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.decode(event,key,token,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.decode(event,key,"different-verification-token",timestamp,nonce,signature)).isInstanceOf(IllegalArgumentException.class);
        String retried = Long.toString(Instant.now().minusSeconds(6 * 3600).getEpochSecond());
        assertThat(crypto.decode(event,key,token,retried,nonce,signature(retried,nonce,event)).path("schema").asText()).isEqualTo("2.0");
        String old = Long.toString(Instant.now().minusSeconds(25 * 3600).getEpochSecond());
        assertThatThrownBy(() -> crypto.decode(event,key,token,old,nonce,signature(old,nonce,event))).isInstanceOf(IllegalArgumentException.class);
        byte[] challenge = envelope("{\"type\":\"url_verification\",\"token\":\""+token+"\",\"challenge\":\"synthetic-challenge\"}");
        assertThat(crypto.decode(challenge,key,token,null,null,null).path("challenge").asText()).isEqualTo("synthetic-challenge");
        challenge[challenge.length-2] ^= 1;
        assertThatThrownBy(() -> crypto.decode(challenge,key,token,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] envelope(String plaintext) throws Exception {
        byte[] aesKey = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length+encrypted.length];
        System.arraycopy(iv,0,packed,0,iv.length);
        System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);
        return ("{\"encrypt\":\""+Base64.getEncoder().encodeToString(packed)+"\"}").getBytes(StandardCharsets.UTF_8);
    }

    private String signature(String timestamp, String nonce, byte[] body) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update((timestamp+nonce+key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(body));
    }
}
