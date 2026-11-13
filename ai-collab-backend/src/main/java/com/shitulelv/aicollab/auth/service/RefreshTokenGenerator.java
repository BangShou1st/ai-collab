package com.shitulelv.aicollab.auth.service;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.OptionalInt;

/**
 * 生成只在短暂业务对象和 HttpOnly Cookie 中出现的 Refresh Token 原文。
 * 使用密码学安全随机数，避免可预测的会话凭据。
 */
@Service
public class RefreshTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final byte[] VERSION_MAGIC = {'T', 'V'};
    private static final int VERSION_OFFSET = VERSION_MAGIC.length;
    private static final int VERSION_BYTE_LENGTH = Short.BYTES;

    private final SecureRandom secureRandom;

    public RefreshTokenGenerator() {
        this(new SecureRandom());
    }

    RefreshTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * 生成带用户会话版本前缀的 43 字符不透明令牌。
     *
     * <p>版本不新增数据库列：前 2 字节为私有标记、随后 2 字节保存 token_version，
     * 剩余 28 字节为安全随机数。没有标记的历史随机令牌按版本 0 读取，兼容既有 tv=0 会话。
     * 服务端只持久化整串令牌的 SHA-256，版本字段不可被客户端用于猜测其他凭据。</p>
     */
    public String generate(int tokenVersion) {
        if (tokenVersion < 0 || tokenVersion > 0xFFFF) {
            throw new IllegalArgumentException("tokenVersion 必须在 0..65535 之间");
        }
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        System.arraycopy(VERSION_MAGIC, 0, bytes, 0, VERSION_MAGIC.length);
        ByteBuffer.wrap(bytes, VERSION_OFFSET, VERSION_BYTE_LENGTH).putShort((short) tokenVersion);
        byte[] random = new byte[TOKEN_BYTE_LENGTH - VERSION_OFFSET - VERSION_BYTE_LENGTH];
        secureRandom.nextBytes(random);
        System.arraycopy(random, 0, bytes, VERSION_OFFSET + VERSION_BYTE_LENGTH, random.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 读取 Refresh Token 承载的会话版本；无法解码的 32 字节历史随机令牌按 0 处理。
     * 历史令牌仍必须通过数据库 hash 查找，单纯伪造 43 字符不会获得会话。
     */
    public OptionalInt tokenVersion(String token) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != TOKEN_BYTE_LENGTH) {
                return OptionalInt.empty();
            }
            if (bytes[0] != VERSION_MAGIC[0] || bytes[1] != VERSION_MAGIC[1]) {
                return OptionalInt.of(0);
            }
            int version = Short.toUnsignedInt(
                    ByteBuffer.wrap(bytes, VERSION_OFFSET, VERSION_BYTE_LENGTH).getShort());
            return OptionalInt.of(version);
        } catch (IllegalArgumentException exception) {
            return OptionalInt.empty();
        }
    }
}
