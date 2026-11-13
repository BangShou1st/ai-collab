package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.UserSessionStateService;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionGuardFilterTest {

    private final UserSessionStateService states = mock(UserSessionStateService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AuthSessionGuardFilter filter = new AuthSessionGuardFilter(states, json);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matching_active_session_continues() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(jwt(userId, 3));
        when(states.find(userId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.ACTIVE, 3)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missing_tv_is_treated_as_zero() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(jwt(userId, null));
        when(states.find(userId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.ACTIVE, 0)));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void version_mismatch_returns_revoked_401_and_clears_context() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(jwt(userId, 2));
        when(states.find(userId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.ACTIVE, 3)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.AUTH_SESSION_REVOKED.name());
        assertThat(response.getHeader("WWW-Authenticate")).contains("invalid_token");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabled_user_returns_revoked_401_without_querying_when_unauthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(jwt(userId, 1));
        when(states.find(userId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.DISABLED, 1)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unauthenticated_or_internal_request_is_not_guarded() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(states, never()).find(org.mockito.ArgumentMatchers.any());
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static Jwt jwt(UUID userId, Integer tokenVersion) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("username", "owner")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (tokenVersion != null) {
            builder.claim("tv", tokenVersion);
        }
        return builder.build();
    }
}
