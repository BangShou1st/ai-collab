package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @Test
    void suppliesLocalDevelopmentOriginWhenNoProfilePropertiesAreLoaded() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173"));
    }

    @Test
    void bindsExplicitAllowedOrigins() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "security.cors.allowed-origins[0]=http://localhost:5173",
                        "security.cors.allowed-origins[1]=https://app.example.test")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173", "https://app.example.test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " ",
            "http://*.example.test",
            "ftp://app.example.test",
            "https://app.example.test/auth",
            "https://app.example.test/",
            "https://app.example.test?next=1",
            "https://user@app.example.test",
            "https://app.example.test#fragment",
            "HTTP://app.example.test",
            "http://APP.example.test",
            "http://app.example.test:",
            "http://app.example.test:80",
            "https://app.example.test:443"
    })
    void rejectsBlankWildcardAndNonOriginAllowedOriginValues(String origin) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("security.cors.allowed-origins[0]=" + origin)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class PropertiesConfiguration {
    }
}
