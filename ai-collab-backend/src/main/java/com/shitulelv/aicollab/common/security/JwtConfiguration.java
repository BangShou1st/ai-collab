package com.shitulelv.aicollab.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.shitulelv.aicollab.auth.config.PublicRegistrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/**
 * 提供认证流程需要的密码哈希器、时钟及官方 JWT 编解码器。
 * HMAC 编码和解码共享同一环境密钥，启动时校验长度，防止误用弱密钥。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        JwtProperties.class, RefreshTokenProperties.class, CorsProperties.class, PublicRegistrationProperties.class})
public class JwtConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt 自带随机盐且计算成本可调；数据库只保存不可逆哈希，不保存或加密明文密码。
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(secretKey(properties))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), subjectUuidValidator()));
        return decoder;
    }

    /**
     * sub 是数据库用户主键；在过滤器层就要求它存在且为 UUID，可以让畸形 Token 统一走 401，
     * 而不是进入 Controller 后变成类型转换异常。
     */
    private OAuth2TokenValidator<Jwt> subjectUuidValidator() {
        OAuth2Error error = new OAuth2Error("invalid_token", "JWT sub 必须是用户 UUID", null);
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                return OAuth2TokenValidatorResult.failure(error);
            }
            try {
                UUID.fromString(subject);
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException exception) {
                return OAuth2TokenValidatorResult.failure(error);
            }
        };
    }

    private SecretKey secretKey(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少需要 32 个 UTF-8 字节");
        }
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
