package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLoggingConfigurationTest {

    @Test
    void localProfileDoesNotEnableSqlParameterLoggingForUserMapper() throws Exception {
        var propertySources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"));

        assertThat(propertySources)
                .anySatisfy(source -> assertThat(source.getProperty(
                        "logging.level.com.shitulelv.aicollab.user.mapper")).isEqualTo("INFO"));
    }

    @Test
    void localProfileDoesNotEnableSqlParameterLoggingForRefreshTokenMapper() throws Exception {
        var propertySources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"));

        assertThat(propertySources)
                .anySatisfy(source -> assertThat(source.getProperty(
                        "logging.level.com.shitulelv.aicollab.auth.refresh.mapper")).isEqualTo("INFO"));
    }

    @Test
    void anonymousHealthResponseDoesNotExposeComponentDetails() throws Exception {
        var propertySources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"));

        assertThat(propertySources)
                .anySatisfy(source -> assertThat(source.getProperty(
                        "management.endpoint.health.show-details")).isEqualTo("when-authorized"));
    }
}
