package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenPropertiesTest {

    @Test
    void rejectsMissingCookieSecureProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsLifetimesAndCookieAttributes() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "security.refresh-token.token-lifetime=PT336H",
                        "security.refresh-token.session-lifetime=PT720H",
                        "security.refresh-token.cookie-name=ai_collab_refresh_token",
                        "security.refresh-token.cookie-path=/api/v1/auth",
                        "security.refresh-token.same-site=Strict",
                        "security.refresh-token.cookie-secure=false")
                .run(context -> {
                    RefreshTokenProperties properties = context.getBean(RefreshTokenProperties.class);

                    assertThat(properties.tokenLifetime()).isEqualTo(Duration.ofDays(14));
                    assertThat(properties.sessionLifetime()).isEqualTo(Duration.ofDays(30));
                    assertThat(properties.cookieName()).isEqualTo("ai_collab_refresh_token");
                    assertThat(properties.cookiePath()).isEqualTo("/api/v1/auth");
                    assertThat(properties.sameSite()).isEqualTo("Strict");
                    assertThat(properties.cookieSecure()).isFalse();
                });
    }

    @Test
    void rejectsSessionLifetimeShorterThanTokenLifetime() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "security.refresh-token.token-lifetime=PT336H",
                        "security.refresh-token.session-lifetime=PT24H",
                        "security.refresh-token.cookie-name=ai_collab_refresh_token",
                        "security.refresh-token.cookie-path=/api/v1/auth",
                        "security.refresh-token.same-site=Strict")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsTokenLifetimeLongerThanFourteenDays() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "security.refresh-token.token-lifetime=PT360H",
                        "security.refresh-token.session-lifetime=PT720H")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsSessionLifetimeLongerThanThirtyDays() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "security.refresh-token.token-lifetime=PT336H",
                        "security.refresh-token.session-lifetime=PT744H")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lax", "None"})
    void rejectsCookieSameSiteOtherThanStrict(String sameSite) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("security.refresh-token.same-site=" + sameSite)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshTokenProperties.class)
    static class PropertiesConfiguration {
    }
}
