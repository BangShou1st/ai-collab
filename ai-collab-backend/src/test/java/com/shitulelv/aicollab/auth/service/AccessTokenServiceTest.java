package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.JwtProperties;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessTokenServiceTest {

    @Test
    void createsShortLivedTokenWithRequiredClaims() {
        Instant now = Instant.parse("2026-07-20T15:00:00Z");
        JwtEncoder encoder = mock(JwtEncoder.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getTokenValue()).thenReturn("signed.jwt");
        when(encoder.encode(org.mockito.ArgumentMatchers.any())).thenReturn(jwt);
        AccessTokenService service = new AccessTokenService(
                encoder,
                new JwtProperties("01234567890123456789012345678901", 30),
                Clock.fixed(now, ZoneOffset.UTC));
        UserEntity user = new UserEntity();
        user.setId(UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851"));
        user.setUsername("owner");

        AccessTokenService.IssuedToken token = service.createAccessToken(user);

        assertThat(token.value()).isEqualTo("signed.jwt");
        assertThat(token.expiresInSeconds()).isEqualTo(1800);
        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(captor.capture());
        assertThat(captor.getValue().getClaims().getSubject()).isEqualTo(user.getId().toString());
        assertThat(captor.getValue().getClaims().getClaimAsString("username")).isEqualTo("owner");
        assertThat(captor.getValue().getClaims().getIssuedAt()).isEqualTo(now);
        assertThat(captor.getValue().getClaims().getExpiresAt()).isEqualTo(now.plusSeconds(1800));
    }

    @Test
    void assignsDistinctJwtIdsWhenTheSameUserIsIssuedTwiceAtTheSameInstant() {
        Instant now = Instant.parse("2026-07-20T15:00:00Z");
        JwtEncoder encoder = mock(JwtEncoder.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getTokenValue()).thenReturn("signed.jwt");
        when(encoder.encode(org.mockito.ArgumentMatchers.any())).thenReturn(jwt);
        AccessTokenService service = new AccessTokenService(
                encoder,
                new JwtProperties("01234567890123456789012345678901", 30),
                Clock.fixed(now, ZoneOffset.UTC));
        UserEntity user = new UserEntity();
        user.setId(UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851"));
        user.setUsername("owner");

        service.createAccessToken(user);
        service.createAccessToken(user);

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder, times(2)).encode(captor.capture());
        assertThat(captor.getAllValues().get(0).getClaims().getId()).isNotBlank();
        assertThat(captor.getAllValues().get(1).getClaims().getId())
                .isNotEqualTo(captor.getAllValues().get(0).getClaims().getId());
    }
}
