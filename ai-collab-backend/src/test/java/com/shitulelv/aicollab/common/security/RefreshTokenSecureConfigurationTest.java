package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenSecureConfigurationTest {

    private static final String COOKIE_SECURE = "security.refresh-token.cookie-secure";

    @Test
    void baseConfigurationDefaultsCookieSecureToTrue() throws IOException {
        assertThat(resolveCookieSecure("application.yml")).isTrue();
    }

    @Test
    void localConfigurationExplicitlyOverridesCookieSecureToFalse() throws IOException {
        assertThat(resolveCookieSecure("application.yml", "application-local.yml")).isFalse();
    }

    private Boolean resolveCookieSecure(String... resources) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            loader.load(resource, new ClassPathResource(resource))
                    .forEach(propertySources::addFirst);
        }
        return new PropertySourcesPropertyResolver(propertySources).getProperty(COOKIE_SECURE, Boolean.class);
    }
}
