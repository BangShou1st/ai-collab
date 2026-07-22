package com.shitulelv.aicollab.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** local 开发账号的环境配置，只在 local 初始化器启用时使用。 */
@Validated
@ConfigurationProperties(prefix = "demo.owner")
public record LocalDemoUserProperties(
        @NotBlank @Size(max = 40) String username,
        @NotBlank String password) {
}
