package com.shitulelv.aicollab.common.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 集中承载 JWT 密钥与有效期配置。
 * 配置对象避免敏感值散落在多个 @Value 字段中，也便于启动时统一校验。
 */
@ConfigurationProperties(prefix = "security.jwt")
@Validated
public record JwtProperties(
        @NotBlank String secret,
        @Min(1) @Max(1440) long accessTokenMinutes) {
}
