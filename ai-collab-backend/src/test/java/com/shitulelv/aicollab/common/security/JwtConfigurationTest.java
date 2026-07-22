package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTest {

    @Test
    void encoderAndDecoderShareHs256Secret() {
        JwtConfiguration configuration = new JwtConfiguration();
        JwtProperties properties = new JwtProperties("01234567890123456789012345678901", 30);
        JwtEncoder encoder = configuration.jwtEncoder(properties);
        JwtDecoder decoder = configuration.jwtDecoder(properties);
        AccessTokenService tokenService = new AccessTokenService(encoder, properties, Clock.systemUTC());
        UserEntity user = new UserEntity();
        user.setId(UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851"));
        user.setUsername("owner");

        AccessTokenService.IssuedToken issued = tokenService.createAccessToken(user);
        Jwt decoded = decoder.decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
        assertThat(decoded.getClaimAsString("username")).isEqualTo("owner");
    }

    @Test
    void rejectsSecretShorterThanHs256Minimum() {
        JwtConfiguration configuration = new JwtConfiguration();
        JwtProperties weak = new JwtProperties("too-short", 30);

        assertThatThrownBy(() -> configuration.jwtEncoder(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要 32");
    }

    @Test
    void rejectsNonPositiveAccessTokenLifetimeDuringConfigurationBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtConfiguration.class)
                .withPropertyValues(
                        "security.jwt.secret=01234567890123456789012345678901",
                        "security.jwt.access-token-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
