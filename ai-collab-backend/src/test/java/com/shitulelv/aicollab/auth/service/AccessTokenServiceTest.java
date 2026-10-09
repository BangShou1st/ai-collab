package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.JwtProperties;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T02:00:00Z");

    private JwtEncoder jwtEncoder;
    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        jwtEncoder = mock(JwtEncoder.class);
        service = new AccessTokenService(
                jwtEncoder,
                new JwtProperties("test-secret-key-at-least-32-bytes!", 30),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(jwtEncoder.encode(any())).thenAnswer(invocation -> {
            JwtEncoderParameters parameters = invocation.getArgument(0);
            return Jwt.withTokenValue("encoded-jwt")
                    .header("alg", "HS256")
                    .issuedAt(parameters.getClaims().getIssuedAt())
                    .expiresAt(parameters.getClaims().getExpiresAt())
                    .build();
        });
    }

    private UserEntity user(UUID id, String username) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUsername(username);
        return entity;
    }

    private JwtClaimsSet encodedClaims() {
        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        org.mockito.Mockito.verify(jwtEncoder).encode(captor.capture());
        return captor.getValue().getClaims();
    }

    @Test
    void token_carries_subject_and_username_claims() {
        UUID userId = UUID.randomUUID();

        AccessTokenService.IssuedToken issued = service.createAccessToken(user(userId, "owner"));

        assertThat(issued.value()).isEqualTo("encoded-jwt");
        JwtClaimsSet claims = encodedClaims();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat((String) claims.getClaim("username")).isEqualTo("owner");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void expiry_is_access_token_minutes_times_sixty() {
        AccessTokenService.IssuedToken issued = service.createAccessToken(
                user(UUID.randomUUID(), "owner"));

        assertThat(issued.expiresInSeconds()).isEqualTo(30 * 60);
        JwtClaimsSet claims = encodedClaims();
        org.assertj.core.api.Assertions.assertThat(
                        Duration.between(claims.getIssuedAt(), claims.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(30));
        org.assertj.core.api.Assertions.assertThat(claims.getIssuedAt()).isEqualTo(NOW);
    }
}
