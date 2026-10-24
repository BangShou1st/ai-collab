package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh Token Cookie 安全基线的绑定校验：
 * 生产（默认 profile）必须 Secure；禁用 Secure 属于本地 HTTP 开发的显式让步，
 * 未显式确认（allow-insecure-cookie=true）时应用必须拒绝启动。
 */
class RefreshTokenPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void secureCookie_startsSuccessfully_andInsecureAllowDefaultsToFalse() {
        runner.withPropertyValues("security.refresh-token.cookie-secure=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RefreshTokenProperties.class);
                    RefreshTokenProperties properties = context.getBean(RefreshTokenProperties.class);
                    assertThat(properties.cookieSecure()).isTrue();
                    assertThat(properties.allowInsecureCookie()).isFalse();
                });
    }

    @Test
    void missingCookieSecure_failsStartup() {
        // 生产配置缺失（例如模板漏配、环境变量被清空）时不得静默回退到某个默认值。
        runner.run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void insecureCookie_withoutExplicitAllow_failsStartup() {
        runner.withPropertyValues("security.refresh-token.cookie-secure=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("allow-insecure-cookie");
                });
    }

    @Test
    void insecureCookie_withExplicitAllow_startsSuccessfully() {
        // 本地 HTTP 开发的合法组合（application-local.yml 即此形态）。
        runner.withPropertyValues(
                        "security.refresh-token.cookie-secure=false",
                        "security.refresh-token.allow-insecure-cookie=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RefreshTokenProperties.class);
                    RefreshTokenProperties properties = context.getBean(RefreshTokenProperties.class);
                    assertThat(properties.cookieSecure()).isFalse();
                    assertThat(properties.allowInsecureCookie()).isTrue();
                });
    }

    @Test
    void allowInsecureAlone_cannotOverrideSecure() {
        // allow-insecure-cookie 只是"允许禁用"的确认，不是禁用开关本身。
        runner.withPropertyValues(
                        "security.refresh-token.cookie-secure=true",
                        "security.refresh-token.allow-insecure-cookie=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RefreshTokenProperties.class);
                    assertThat(context.getBean(RefreshTokenProperties.class).cookieSecure()).isTrue();
                });
    }

    @Configuration
    @EnableConfigurationProperties(RefreshTokenProperties.class)
    static class PropertiesConfiguration {
    }
}
