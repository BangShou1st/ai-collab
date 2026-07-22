package com.shitulelv.aicollab.auth.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 将 Refresh Token 原文转换为数据库可持久化的 SHA-256 摘要。
 * 持久层只接收该摘要，避免原文在数据库、日志或异常中出现。
 */
@Service
public class RefreshTokenHashService {

    public String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return toLowercaseHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 未提供 SHA-256 算法", exception);
        }
    }

    private String toLowercaseHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
