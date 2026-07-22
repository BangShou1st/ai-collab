package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.JwtProperties;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 负责把已认证用户签发为短期 JWT Access Token。
 * 它位于 AuthService 与 Spring Security JwtEncoder 之间，只写身份声明，不接触密码或数据库配置。
 */
@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder jwtEncoder, JwtProperties jwtProperties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    public IssuedToken createAccessToken(UserEntity user) {
        Instant issuedAt = clock.instant();
        long expiresInSeconds = Math.multiplyExact(jwtProperties.accessTokenMinutes(), 60);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                // jti 让同一用户在同一秒内的登录/刷新也得到不同 JWT，避免旧新 Access Token 相同。
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expiresInSeconds))
                .build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new IssuedToken(value, expiresInSeconds);
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
