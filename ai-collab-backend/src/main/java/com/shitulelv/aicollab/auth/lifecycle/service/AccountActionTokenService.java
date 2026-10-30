package com.shitulelv.aicollab.auth.lifecycle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shitulelv.aicollab.auth.lifecycle.entity.AccountActionTokenEntity;
import com.shitulelv.aicollab.auth.lifecycle.mapper.AccountActionTokenMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次性账号操作令牌（密码重置 / 邮箱验证）的生成、哈希存储与原子消费。
 * 同一用户同一类型只保留最新有效令牌：新请求会先作废旧令牌。
 */
@Service
public class AccountActionTokenService {
    public static final String TYPE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String TYPE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    private static final int RANDOM_BYTES = 32;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final AccountActionTokenMapper mapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountActionTokenService(AccountActionTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String create(UUID userId, String tokenType, String purposeEmail, Duration ttl) {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AccountActionTokenEntity entity = new AccountActionTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setTokenType(tokenType);
        entity.setTokenHash(hash(rawToken));
        entity.setPurposeEmail(purposeEmail);
        entity.setExpiresAt(OffsetDateTime.now().plus(ttl));
        mapper.insert(entity);
        return rawToken;
    }

    /**
     * 原子消费令牌；无效、已用、过期一律抛同一错误，防止状态枚举。
     */
    @Transactional
    public AccountActionTokenEntity consume(String rawToken, String tokenType) {
        validateFormat(rawToken);
        String tokenHash = hash(rawToken);
        if (mapper.consumeAtomically(tokenHash, tokenType) != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID);
        }
        AccountActionTokenEntity entity = mapper.selectOne(new LambdaQueryWrapper<AccountActionTokenEntity>()
                .eq(AccountActionTokenEntity::getTokenHash, tokenHash)
                .eq(AccountActionTokenEntity::getTokenType, tokenType));
        if (entity == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID);
        }
        return entity;
    }

    @Transactional
    public void invalidateActive(UUID userId, String tokenType) {
        mapper.invalidateActiveForUser(userId, tokenType);
    }

    private static void validateFormat(String rawToken) {
        if (rawToken == null || !TOKEN_FORMAT.matcher(rawToken).matches()) {
            throw new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID);
        }
    }

    private static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
